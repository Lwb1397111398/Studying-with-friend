#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bookocr — 扫描版 PDF → txt / 插图版 PDF 识别台（agent 驱动，操作手册见 SKILL.md）

子命令：
  init   <书名> --src <PDF路径> [--batch 10] [--dpi 200] [--color]   建识别工程（直接建批，免渲染）
  prep   <书名或工程路径> [--pages 521-530]                          渲染页图（可只渲染指定页）
  pull   <书名或工程路径> [--min 100]                                文字层拔取：自带文字层的书免视觉识别（分钟级）
  aivision <书名或工程路径> [--batch 批号] [--workers 4]              直连视觉 API 并发识图（绕开子代理并发限制，需 apis.json 或本机档案库）
  patch  <书名或工程路径> [--batch 批号]                              图版页任务卡：派视觉只补这几页的图描述
  figure-desc <书名或工程路径> [--workers 5] [--dry-run]             pull 图补语义：按 rect 裁小图→视觉 API 逐图补 title/desc/kind
  next   <书名或工程路径>                                            领下一批（输出子代理任务卡）
  check  <书名或工程路径> <批号> [--force] [--min 100]               质检一批
  retry  <书名或工程路径> <批号|--all-failed>                        重置一批待重转
  chapters <书名或工程路径> [--apply 裁决文件] [--no-review]          全书切章 + 目录对账（疑点出裁决任务卡）
  status <书名或工程路径>                                            进度一览
  pack   <书名或工程路径> [--no-pdf] [--force]                       打包 txt / 插图版 PDF / KIT 图包 zip（App 直入图文混排）

全局设置读 kit 目录 config.json（route 路线 / pages_per_batch / dpi / aivision_workers /
chapter_review / output），init 时快照进台账；缺省值见 DEFAULT_CONFIG。
台账在 jobs/<书名>/manifest.json（单文件状态机），所有命令可随时中断重跑。
设计要点：正文永远只落文件（batches/），台账里只存统计数字，不存正文。
"""
from __future__ import annotations

import argparse
import bisect
import difflib
import json
import msvcrt
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

KIT = Path(__file__).resolve().parent
JOBS = KIT / "jobs"


# ---------------------------------------------------------------- 全局设置

DEFAULT_CONFIG = {
    # 识别路线：subagent=AI 逐页看（子代理看图，老板默认） / aivision=AI 逐页看（视觉 API 直连）
    #           pull-first=高效模式（先拔文字层，拔不动的批再走视觉 API） / pull-only=只拔文字层
    "route": "subagent",
    "pages_per_batch": 10,
    "dpi": 200,
    "aivision_workers": 5,
    "chapter_review": True,    # 章界疑点是否派子代理裁决（无疑点自动跳过）
    # 产出物开关：txt 永远产出；"pdf"=插图版 PDF（重排版嵌真图）；"zip"=KIT 图包
    # （kit.json 章表+图清单 + book.txt + figures/*.png 裁真图，App 端 zip 直入图文混排）
    "output": ["txt", "pdf", "zip"],
}
VALID_ROUTES = ("subagent", "aivision", "pull-first", "pull-only")


def load_config() -> dict:
    """读 kit 目录 config.json（无密钥、可入库），缺键/坏文件用默认值兜底。"""
    cfg = dict(DEFAULT_CONFIG)
    p = KIT / "config.json"
    if p.exists():
        try:
            user = json.loads(p.read_text(encoding="utf-8"))
            if isinstance(user, dict):
                for k in DEFAULT_CONFIG:
                    if user.get(k) is not None:
                        cfg[k] = user[k]
        except (json.JSONDecodeError, OSError) as e:
            out(f"[警告] config.json 读不了（{e}），全用默认设置")
    if cfg["route"] not in VALID_ROUTES:
        die(f"config.json 的 route 取值不认识：{cfg['route']}（可选 {'/'.join(VALID_ROUTES)}）")
    if not isinstance(cfg["output"], list) or "txt" not in cfg["output"]:
        die('config.json 的 output 必须是列表且含 "txt"（如 ["txt", "pdf"]）')
    return cfg

# 子代理产出协议：〔页N〕整行 = 一页开始；〔图：标题〕行 = 真图占位（〕后可跟 desc 尾巴，
# App 端按前缀识别；旧版严格整行正则与 pack 写出的「〔图：题〕desc」不一致，已放宽）
# 批C修复：加 re.M——无 flag 时 ^ 只锚串首，多行文本一个页标记都剥不掉，check 字数每页虚高约 6 字
PAGE_MARK = re.compile(r"^\s*〔页\s*(\d+)\s*〕\s*$", re.M)
FIG_MARK = re.compile(r"^\s*〔图[:：]\s*(.+?)\s*〕(.*)$")
# pull 自动登记的占位图行（figure-desc 补描述前 / pack 重写前的过渡格式，pack 按
# 页内出现序用台账图重写为真实 title/desc）
PULL_FIG_MARK = re.compile(r"^〔图：第(\d+)页插图\d*〕$")
PULL_FIG_DESC = "原书嵌入插图（坐标按原 PDF 图块精确提取）"
# 排版标题启发式（与 APP 侧 BookParser 同一套档位口径）
CH_RE = re.compile(r"^[第笫]\s*[0-9〇零一二三四五六七八九十百千]+\s*[章编部篇次]")
# 二级/三级标题（「一、」「（二）」「(3)」倒装编号）也没有句末标点，质检时不算断段
SUBHEAD_RE = re.compile(r"^(?:[一二三四五六七八九十]+、|[（(][一二三四五六七八九十0-9]+[）)])")
SEC_RE = re.compile(r"^([第笫]\s*[0-9〇零一二三四五六七八九十百千]+\s*[节讲款]|[一二三四五六七八九十]+\s*、)")


def out(s: str) -> None:
    print(s)


def die(msg: str) -> "NoReturn":  # type: ignore[valid-type]
    out(f"[错误] {msg}")
    sys.exit(1)


def load_fit():
    try:
        import pymupdf  # PyMuPDF 新名字
        return pymupdf
    except ImportError:
        import fitz  # 旧名字回退
        return fitz


# ---------------------------------------------------------------- 工程定位

def resolve_job(name_or_path: str) -> Path:
    p = Path(name_or_path)
    if p.is_dir() and (p / "manifest.json").exists():
        return p
    cand = JOBS / name_or_path
    if cand.is_dir():
        return cand
    die(f"找不到识别工程「{name_or_path}」（既不是目录，也不在 {JOBS} 下）")


def load_manifest(job: Path) -> dict:
    mf = job / "manifest.json"
    if not mf.exists():
        die(f"{mf} 不存在，先跑 init")
    return json.loads(mf.read_text(encoding="utf-8"))


def save_manifest(job: Path, m: dict) -> None:
    (job / "manifest.json").write_text(
        json.dumps(m, ensure_ascii=False, indent=1), encoding="utf-8"
    )


class _ManifestLock:
    """跨进程/跨会话台账互斥锁（夜间 cron 每 2h 起新会话接力时，多个会话并行
    调度同一本书，无锁读改写会把别会话刚写的「派出/done」标记覆盖回去，实测发生过）。
    除只读命令外全部持锁执行；pull/aivision/figure-desc 内部直调 cmd_check 不经此锁，
    这三条路线不与子代理路线混跑即可。"""

    def __enter__(self):
        JOBS.mkdir(exist_ok=True)
        self._f = open(JOBS / ".manifest.lock", "a+")
        deadline = time.time() + 120
        while True:
            try:
                msvcrt.locking(self._f.fileno(), msvcrt.LK_NBLCK, 1)
                return self
            except OSError:
                if time.time() > deadline:
                    self._f.close()
                    die("台账锁等待超时（另一会话占锁过久），稍后重试")
                time.sleep(0.3)

    def __exit__(self, *exc):
        try:
            msvcrt.locking(self._f.fileno(), msvcrt.LK_UNLCK, 1)
        finally:
            self._f.close()


def batch_dir(job: Path) -> Path:
    d = job / "batches"
    d.mkdir(exist_ok=True)
    return d


# ---------------------------------------------------------------- init

def cmd_init(a: argparse.Namespace) -> None:
    src = Path(a.src).resolve()
    if not src.exists():
        die(f"PDF 不存在：{src}")
    fitz = load_fit()
    try:
        doc = fitz.open(src)
        npages = doc.page_count
        doc.close()
    except Exception as e:
        die(f"打不开 PDF（{e}）")
    if npages <= 0:
        die("这个 PDF 没有页面")

    cfg = load_config()
    bs = a.batch if a.batch is not None else cfg["pages_per_batch"]
    dpi = a.dpi if a.dpi is not None else cfg["dpi"]

    name = re.sub(r'[\\/:*?"<>|]', "_", a.name).strip() or "未命名"
    job = JOBS / name
    if job.exists():
        die(f"工程已存在：{job}（换个书名，或删掉旧工程重来）")
    (job / "images").mkdir(parents=True)
    (job / "output").mkdir()
    batch_dir(job)

    m = {
        "name": name,
        "src_pdf": str(src),
        "dpi": dpi,
        "color": a.color,
        "batch_size": bs,
        "min_chars_per_page": 100,
        "spec": str(KIT / "spec.md"),
        "npages": npages,
        "config": cfg,   # 设置快照：改全局 config.json 不影响进行中的书
        "pages": [{"no": i + 1, "image": f"images/{i + 1:04d}.png"} for i in range(npages)],
        "rendered": [],    # 已渲染出 PNG 的页号（pull 不需要渲染；prep 填充）
        "batches": {},     # bXXX -> {pages, status, body, notes, figures, stats, fail_reason, thin_pages}
        "figures": [],     # [{id, batch, page, rect, kind, title, desc, file}]
    }
    for bi, start in enumerate(range(0, npages, bs), 1):
        grp = list(range(start + 1, min(start + bs, npages) + 1))
        m["batches"][f"b{bi:03d}"] = {
            "pages": grp,
            "status": "pending",  # pending→assigned→done/failed
            "body": f"batches/b{bi:03d}.txt",
            "notes": f"batches/b{bi:03d}_notes.txt",
            "figures": f"batches/b{bi:03d}_figures.json",
            "stats": None,
            "fail_reason": None,
            "thin_pages": [],  # 疑似无文字层的图版页（patch 命令的视觉补描述对象）
        }
    save_manifest(job, m)
    out(f"[ok] 工程 {job}")
    out(f"     书名「{name}」 PDF {src}（{npages} 页） dpi={dpi} 每批 {bs} 页 → 共 {len(m['batches'])} 批 路线={cfg['route']}")
    tips = {
        "subagent": "下一步（AI 逐页看）：prep 渲染页图 → next 领批 → 派子代理看图转写",
        "aivision": "下一步（AI 逐页看·API 直连）：prep 渲染页图 → aivision 并发识别",
        "pull-first": "下一步（高效模式）：先 pull 试文字层拔取（分钟级），拔不动的批再走视觉",
        "pull-only": "下一步（只拔文字层）：pull；拔不动的批会标出来，由调度 agent 决定",
    }
    out(tips[cfg["route"]])


# ---------------------------------------------------------------- prep

def _parse_pages_arg(s: str, npages: int) -> list[int]:
    """解析 '521-530,12' 形式的页参数为页号列表。"""
    out_pages: list[int] = []
    for part in s.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo, hi = part.split("-", 1)
            lo, hi = int(lo), int(hi)
            if not (1 <= lo <= hi <= npages):
                die(f"页范围不合法：{part}（全书 {npages} 页）")
            out_pages.extend(range(lo, hi + 1))
        else:
            n = int(part)
            if not (1 <= n <= npages):
                die(f"页号不合法：{part}（全书 {npages} 页）")
            out_pages.append(n)
    return sorted(set(out_pages))


def cmd_prep(a: argparse.Namespace) -> None:
    job = resolve_job(a.job)
    m = load_manifest(job)
    fitz = load_fit()
    doc = fitz.open(m["src_pdf"])
    if not m["pages"]:  # 旧版工程兼容：init 未填页清单时补齐
        m["pages"] = [{"no": i + 1, "image": f"images/{i + 1:04d}.png"} for i in range(doc.page_count)]
    if not m["batches"]:  # 旧版工程兼容：init 未建批时补建
        bs = m["batch_size"]
        for bi, start in enumerate(range(0, len(m["pages"]), bs), 1):
            grp = list(range(start + 1, min(start + bs, len(m["pages"])) + 1))
            m["batches"][f"b{bi:03d}"] = {
                "pages": grp, "status": "pending",
                "body": f"batches/b{bi:03d}.txt", "notes": f"batches/b{bi:03d}_notes.txt",
                "figures": f"batches/b{bi:03d}_figures.json", "stats": None,
                "fail_reason": None, "thin_pages": [],
            }
    m.setdefault("rendered", [])

    want = _parse_pages_arg(a.pages, len(m["pages"])) if a.pages else list(range(1, len(m["pages"]) + 1))
    todo = [n for n in want if n not in m["rendered"]]
    if not todo:
        save_manifest(job, m)
        out(f"[ok] 目标 {len(want)} 页均已渲染过，跳过（重复跑安全）")
        return
    dpi, gray = m["dpi"], not m["color"]
    for k, n in enumerate(todo, 1):
        page = doc[n - 1]
        pix = page.get_pixmap(dpi=dpi)
        if gray and pix.colorspace and pix.colorspace.n > 1:
            pix = fitz.Pixmap(fitz.csGRAY, pix)
        pix.save(str(job / m["pages"][n - 1]["image"]))
        m["rendered"].append(n)
        if k % 20 == 0 or k == len(todo):
            out(f"  渲染 {k}/{len(todo)}")
    doc.close()
    save_manifest(job, m)
    out(f"[ok] 本次渲染 {len(todo)} 页（累计 {len(m['rendered'])}/{len(m['pages'])}）")
    out("下一步：pull 若已全 done 可直接 pack；否则 next 领批/aivision/patch 走视觉")


# ---------------------------------------------------------------- next

def cmd_next(a: argparse.Namespace) -> None:
    job = resolve_job(a.job)
    m = load_manifest(job)
    if not m["batches"]:
        die("还没有批次，先跑 prep")
    nxt = next((b for b in m["batches"].values() if b["status"] == "pending"), None)
    if nxt is None:
        bad = [b for b in m["batches"].values() if b["status"] == "failed"]
        out("[ok] 没有待领的批了。" + (f"有 {len(bad)} 批失败待处理（retry 或人工看）。" if bad else "全部完成可 pack。"))
        return
    unrendered = [n for n in nxt["pages"] if n not in (m.get("rendered") or [])]
    if unrendered:
        die(f"页图未渲染 {unrendered[:5]}{'…' if len(unrendered) > 5 else ''}，"
            f"先跑：python bookocr.py prep {a.job} --pages {unrendered[0]}-{unrendered[-1]}")
    nxt["status"] = "assigned"
    save_manifest(job, m)

    first, last = nxt["pages"][0], nxt["pages"][-1]
    imgs = "\n".join(f"  {job / m['pages'][p - 1]['image']}" for p in nxt["pages"])
    out(f"批 {bid_of(m, nxt)} | 第 {first}–{last} 页（共 {len(nxt['pages'])} 页）")
    out(f"转写规范（必读，先 Read 它）：{m['spec']}")
    out(f"图片（按页序）：\n{imgs}")
    out("写出（用 Write 工具，正文绝不贴进对话）：")
    out(f"  正文  {job / nxt['body']}（每页第一行写〔页N〕，N=页号；段间空行）")
    out(f"  脚注  {job / nxt['notes']}（每条一行；无脚注也写出空文件）")
    out(f"  真图  {job / nxt['figures']}（JSON 数组，格式见规范；无图写 []）")
    out(f"完成后只回报一行：{bid_of(m, nxt)} 完成 正文X字 脚注Y条 图Z张")


# ---------------------------------------------------------------- aivision（直连视觉 API 并发识别）

def _load_vision_api() -> tuple[str, str, str]:
    """视觉 API 三键（base_url, api_key, model）：kit 目录 apis.json 优先（换电脑自配，不入库），
    回退本机 Agent Manager 档案库挑第一个 caps 含 vision 的启用档案。key 只在内存流转，不打印。"""
    local = KIT / "apis.json"
    if local.exists():
        cfg = json.loads(local.read_text(encoding="utf-8"))
        model = cfg.get("default_model") or (cfg.get("models") or [None])[0]
        if not (cfg.get("base_url") and cfg.get("api_key") and model):
            die("apis.json 需要 base_url / api_key / default_model 三键")
        return cfg["base_url"], cfg["api_key"], model
    am = Path(r"C:\Agent\AImanager\ai-apis.json")
    if not am.exists():
        die(f"没有视觉 API 配置：把 {{base_url, api_key, default_model}} 写入 {local}（OpenAI 兼容格式；该文件已 gitignore）")
    items = json.loads(am.read_text(encoding="utf-8"))
    items = items if isinstance(items, list) else items.get("apis", [])
    for a in items:
        if isinstance(a, dict) and "vision" in (a.get("caps") or []) and a.get("enabled", True):
            model = a.get("default_model") or (a.get("models") or [None])[0]
            return a["base_url"], a["api_key"], model
    die("本机档案库里没有 caps 含 vision 的启用档案；在 apis.json 手工配一个")


def _aivision_page(base: str, key: str, model: str, spec: str, img: Path, pageno: int, timeout: int = 180) -> str:
    """单页识图：OpenAI 兼容 chat/completions，图片 base64 内嵌。返回模型文本输出。"""
    import base64 as _b64
    import urllib.request
    data_uri = "data:image/png;base64," + _b64.b64encode(img.read_bytes()).decode()
    body = {
        "model": model,
        "temperature": 0.1,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text":
                spec + f"\n\n现在转写这一张页图（页号 N={pageno}）。输出格式：\n"
                       "第一行：〔页" + str(pageno) + "〕\n之后：这一页的正文（段落间空行；图位置写〔图：…〕行）。\n"
                       "如有脚注：正文之后写一行〔脚注〕，下面每条脚注一行。\n"
                       "最后：写一行〔FIGURES〕，下面输出本页真图 JSON 数组（无图写 []，格式："
                       '[{"page": ' + str(pageno) + ', "rect": [左,上,右,下], "kind": "table|photo|diagram", '
                       '"title": "…", "desc": "…"}]，坐标为整页比例 0~1）。除以上内容不要输出任何别的话。'},
            {"type": "image_url", "image_url": {"url": data_uri}},
        ]}],
    }
    req = urllib.request.Request(
        base.rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        resp = json.loads(r.read().decode("utf-8"))
    return resp["choices"][0]["message"]["content"]


def _join_vision_lines(lines: list[str]) -> list[str]:
    """把视觉模型输出的换行折叠成段落：空行分段；标题/编号项/图占位独立成段。

    模型常把一个自然段按屏幕宽度折成多行，若逐行当段落，App 里会出现大量
    句中断段。这里按「空行才是段界」还原，中文直接相接，西文词间补空格。"""
    paras: list[str] = []
    cur: list[str] = []

    def flush() -> None:
        if not cur:
            return
        text = ""
        for s in cur:
            sep = " " if (text and text[-1:].isascii() and text[-1:].isalnum()
                          and s[:1].isascii() and s[:1].isalnum()) else ""
            text += sep + s
        paras.append(text.strip())
        cur.clear()

    for raw in lines:
        s = raw.strip()
        if not s:
            flush()
            continue
        standalone = (s.startswith("〔图") or CH_RE.match(s) or SEC_RE.match(s)
                      or re.match(r"^[（(][0-9一二三四五六七八九十]+[）)]", s)
                      or re.match(r"^[0-9]+[.、．]", s))
        if standalone:
            flush()
            paras.append(s)
        else:
            cur.append(s)
    flush()
    return paras


def _join_vision_notes(lines: list[str]) -> list[str]:
    """脚注行折叠：以编号开头的行是新脚注，其余行是上一条的折行续文。"""
    out: list[str] = []
    for raw in lines:
        s = raw.strip()
        if not s:
            continue
        if not out or re.match(r"^[①-⑳0-9]", s):
            out.append(s)
        else:
            out[-1] += s
    return out


def _parse_aivision_page(text: str, pageno: int) -> tuple[list[str], list[str], list[dict]]:
    """拆模型输出 → (正文段落, 脚注行, figures)。容忍模型漏写分区标记。"""
    body_lines, notes, figs = [], [], []
    section = "body"
    for ln in text.replace("\r\n", "\n").split("\n"):
        s = ln.strip()
        if re.fullmatch(r"〔\s*脚注\s*〕?", s) or s == "〔脚注〕":
            section = "notes"; continue
        if re.fullmatch(r"〔\s*FIGURES\s*〕?", s) or s.upper() == "〔FIGURES〕":
            section = "figs"; continue
        if section == "body":
            if s and (PAGE_MARK.match(s) or s == f"〔页{pageno}〕"):
                continue  # 模型自己写的页标记行，统一重打
            body_lines.append(s)  # 空行必须保留：它是 _join_vision_lines 的段界
        elif section == "notes":
            if s:
                notes.append(s)
        else:
            m = re.search(r"\[.*\]", s, re.S)
            if m:
                try:
                    arr = json.loads(m.group(0))
                    if isinstance(arr, list):
                        figs.extend(arr)
                except json.JSONDecodeError:
                    pass
    return _join_vision_lines(body_lines), _join_vision_notes(notes), figs


def cmd_aivision(a: argparse.Namespace) -> None:
    """直连视觉 API 并发识图：一批内页级并发（workers 路），批完自动质检。"""
    import concurrent.futures as futures
    job = resolve_job(a.job)
    m = load_manifest(job)
    if not m["batches"]:
        die("还没有批次，先跑 init")
    base, key, model = _load_vision_api()
    spec = (KIT / "spec.md").read_text(encoding="utf-8")
    workers = a.workers if a.workers is not None else m.get("config", {}).get("aivision_workers", 4)

    if a.batch:
        targets = [normalize_bid(m, a.batch)]
    else:
        targets = [k for k, v in m["batches"].items() if v["status"] in ("pending", "failed")]
    if not targets:
        out("[ok] 没有待识别的批（pending/failed 为空）")
        return
    out(f"[i] 视觉 API {model} @ {base.split('//')[-1].split('/')[0]}（key 已打码），目标 {len(targets)} 批，并发 {workers}")

    for bid in targets:
        b = m["batches"][bid]
        unrendered = [n for n in b["pages"] if n not in (m.get("rendered") or [])]
        if unrendered:
            out(f"[!] {bid} 页图未渲染 {unrendered[:5]}，跳过（先 prep --pages {unrendered[0]}-{unrendered[-1]}）")
            continue
        jobs = {n: job / m["pages"][n - 1]["image"] for n in b["pages"]}
        results: dict[int, str] = {}
        fails: list[int] = []
        with futures.ThreadPoolExecutor(max_workers=workers) as ex:
            futs = {ex.submit(_aivision_page, base, key, model, spec, img, n): n for n, img in jobs.items()}
            for fu in futures.as_completed(futs):
                n = futs[fu]
                try:
                    results[n] = fu.result()
                except Exception as e:  # 网络超时/限流等，页面级重试一次
                    try:
                        results[n] = _aivision_page(base, key, model, spec, jobs[n], n)
                        out(f"  · p{n} 重试成功")
                    except Exception as e2:
                        fails.append(n)
                        out(f"  · p{n} 失败：{type(e2).__name__}: {str(e2)[:80]}")

        parts, notes_all, figs_all = [], [], []
        for n in b["pages"]:
            if n not in results:
                continue
            body_ln, notes, figs = _parse_aivision_page(results[n], n)
            parts.append(f"〔页{n}〕")
            parts.extend(body_ln or ["〔空白页〕"])
            parts.append("")
            notes_all.extend(notes)
            figs_all.extend(figs)
        if fails:
            b["status"], b["fail_reason"] = "failed", f"aivision 失败页 {fails}"
            save_manifest(job, m)
            out(f"[!] {bid} 有 {len(fails)} 页识别失败（{fails}），批标 failed；再跑一次 aivision 只会重试未完成批")
            continue
        (job / b["body"]).write_text("\n".join(parts).rstrip() + "\n", encoding="utf-8")
        (job / b["notes"]).write_text("\n".join(notes_all) + ("\n" if notes_all else ""), encoding="utf-8")
        (job / b["figures"]).write_text(json.dumps(figs_all, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        b["status"] = "assigned"
        save_manifest(job, m)
        try:
            cmd_check(argparse.Namespace(job=a.job, batch=bid, force=a.force, min=a.min), m=m)
        except SystemExit:
            out(f"[!] {bid} 质检不过，retry 后可重跑 aivision 或改派子代理")
    out("下一步：status 看进度")


# ---------------------------------------------------------------- reocr（逐页视觉重识别，修烂文字层）

def _aivision_page_retry(base: str, key: str, model: str, spec: str, img: Path,
                         pageno: int, timeout: int = 240, tries: int = 5) -> str:
    """单页视觉识别 + 429/超时退避重试。视觉 API 实测 8 并发会 429，
    调用方默认只开 3~4 路，这里再兜一层退避，避免整批失败。"""
    last: Exception | None = None
    for i in range(tries):
        try:
            return _aivision_page(base, key, model, spec, img, pageno, timeout=timeout)
        except Exception as e:
            last = e
            msg = str(e)
            if "429" in msg or "Too Many" in msg:
                time.sleep(min(30, 3 * (2 ** i)))
            elif "timed out" in msg.lower() or "Timeout" in type(e).__name__:
                time.sleep(2 + i * 2)
            else:
                time.sleep(1 + i)
    assert last is not None
    raise last


def _write_batch_from_pages(job: Path, b: dict, results: dict[int, dict]) -> None:
    """用逐页视觉结果重写一个批（正文/脚注/图）。"""
    parts: list[str] = []
    notes_all: list[str] = []
    figs_all: list[dict] = []
    for n in b["pages"]:
        r = results[n]
        parts.append(f"〔页{n}〕")
        for j, para in enumerate(r.get("body") or ["〔空白页〕"]):
            if j:
                parts.append("")
            parts.append(para)
        parts.append("")
        notes_all.extend(r.get("notes") or [])
        figs_all.extend([f for f in (r.get("figs") or []) if f.get("page") in b["pages"]])
    (job / b["body"]).write_text("\n".join(parts).rstrip() + "\n", encoding="utf-8")
    (job / b["notes"]).write_text("\n".join(notes_all) + ("\n" if notes_all else ""), encoding="utf-8")
    (job / b["figures"]).write_text(json.dumps(figs_all, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def cmd_reocr(a: argparse.Namespace) -> None:
    """按页/批做视觉重识别，覆盖原批文件。用于两类救火：
    ① pull 路线碰到的扫描件 OCR 文字层烂页（qa 报告点名）；
    ② 子代理/aivision 路线个别页转坏。
    默认整批重识别（脚注/图台账按批才自洽），结果逐页落 reocr/ 可断点续跑。
    加 --exact 只重识别点名页，同批其余页沿用本地 pull 结果——pull 书的正常页
    本来就没问题，整批重跑会把识别量放大 10 倍。"""
    import shutil
    import concurrent.futures as futures
    job = resolve_job(a.job)
    m = load_manifest(job)
    if not m["batches"]:
        die("还没有批次，先跑 init/prep")
    exact = bool(getattr(a, "exact", False))
    if exact and m.get("route") != "pull":
        die("--exact 只适用于 pull 路线（自带文字层）的书：它靠本地重抽非目标页的正文，"
            "视觉识别路线的书没有文字层，去掉 --exact 整批重识别（或先确认 route=pull）")
    want: set[int] | None = None
    if a.needs_vision:
        want = {p for b in m["batches"].values() for p in (b.get("needs_vision") or [])}
        if not want:
            out("[ok] 没有 needs_vision 页（pull 未标记或已复核完）")
            return
        bids = [bid for bid, b in m["batches"].items() if want & set(b["pages"])]
    elif a.all:
        bids = list(m["batches"])
        if exact:
            want = {p for b in m["batches"].values() for p in b["pages"]}
    elif a.pages:
        want = set(_parse_pages_arg(a.pages, len(m["pages"])))
        bids = [bid for bid, b in m["batches"].items() if want & set(b["pages"])]
        if not bids:
            die(f"页 {sorted(want)[:8]} 不在任何批里")
    elif a.batch:
        bids = [normalize_bid(m, a.batch)]
    else:
        die("要指定 --pages / --batch / --all 之一")
    target_pages = sorted(want if exact and want is not None
                          else {p for bid in bids for p in m["batches"][bid]["pages"]})
    target_set = set(target_pages)

    store = job / "reocr"
    store.mkdir(exist_ok=True)
    todo = [p for p in target_pages
            if a.force or not (store / f"p{p:04d}.json").exists()]
    out(f"[i] 重识别目标：{len(bids)} 批 / {len(target_pages)} 页；待跑 {len(todo)} 页"
        f"（已完成的页自动续跑）")

    # 备份原批/产出（只备一次，按时间戳）
    if not a.no_backup:
        stamp = time.strftime("%Y%m%d-%H%M%S")
        bak = job / f"backup-reocr-{stamp}"
        try:
            shutil.copytree(job / "batches", bak / "batches")
            if (job / "output").exists():
                shutil.copytree(job / "output", bak / "output")
            out(f"[i] 原批已备份：{bak}")
        except OSError as e:
            out(f"[警告] 备份失败（{e}），继续重识别")

    # 渲染缺的页图
    fitz = load_fit()
    doc = fitz.open(m["src_pdf"])
    rendered = set(m.get("rendered") or [])
    need_render = [p for p in todo if p not in rendered]
    if need_render:
        out(f"[i] 渲染缺页 {len(need_render)} 张 …")
        for k, p in enumerate(need_render, 1):
            pix = doc[p - 1].get_pixmap(dpi=m["dpi"])
            if not m["color"] and pix.colorspace and pix.colorspace.n > 1:
                pix = fitz.Pixmap(fitz.csGRAY, pix)
            pix.save(str(job / m["pages"][p - 1]["image"]))
            rendered.add(p)
            if k % 20 == 0 or k == len(need_render):
                out(f"  · 渲染 {k}/{len(need_render)}")
        m["rendered"] = sorted(rendered)
        save_manifest(job, m)
    doc.close()

    if not todo:
        out("[ok] 目标页都已有重识别结果，直接重建批文件")
    else:
        base, key, model = _load_vision_api()
        spec = (KIT / "spec.md").read_text(encoding="utf-8")
        workers = a.workers if a.workers is not None else 3
        out(f"[i] 视觉 API {model}（key 已打码），并发 {workers}，待识别 {len(todo)} 页（约 {len(todo) * 8 // max(1, workers)} 秒）")
        fails: list[int] = []
        done = 0

        def run(p: int) -> None:
            nonlocal done
            img = job / m["pages"][p - 1]["image"]
            txt = _aivision_page_retry(base, key, model, spec, img, p)
            body, notes, figs = _parse_aivision_page(txt, p)
            (store / f"p{p:04d}.json").write_text(json.dumps(
                {"page": p, "body": body, "notes": notes, "figs": figs,
                 "model": model, "ts": time.strftime("%Y-%m-%d %H:%M:%S")},
                ensure_ascii=False, indent=1), encoding="utf-8")
            done += 1
            if done % 5 == 0 or done == len(todo):
                out(f"  · 识别 {done}/{len(todo)}（失败 {len(fails)}）")

        with futures.ThreadPoolExecutor(max_workers=workers) as ex:
            futs = {ex.submit(run, p): p for p in todo}
            for fu in futures.as_completed(futs):
                p = futs[fu]
                try:
                    fu.result()
                except Exception as e:
                    fails.append(p)
                    out(f"  · p{p} 失败：{type(e).__name__}: {str(e)[:100]}")
        if fails:
            out(f"[!] {len(fails)} 页失败：{fails[:12]}{'…' if len(fails) > 12 else ''}；重跑同一命令只补失败页")

    # 整批重建（缺页的批保持原样，不写半批）
    rebuilt: list[str] = []
    pull_doc = pull_fitz = None
    if exact:
        # --exact：未点名页不花 token，现场按原 pull 规则重抽（本地、确定性）
        pull_fitz = load_fit()
        pull_doc = pull_fitz.open(m["src_pdf"])

    def pull_result(p: int) -> dict:
        d = _pull_page(pull_doc[p - 1], pull_fitz,
                       furniture=set(m.get("furniture") or []))
        thin = d["chars"] == 0 and d["fig_area"] > 0.3
        body = [f"〔图：第{p}页图版〕", "〔图版页〕"] if thin else (d["paras"] or [])
        return {"page": p, "body": body, "notes": d["notes"], "figs": d["figs"],
                "thin": thin, "needs_vision": bool(d.get("needs_vision"))}

    for bid in bids:
        b = m["batches"][bid]
        missing = [p for p in b["pages"]
                   if p in target_set and not (store / f"p{p:04d}.json").exists()]
        if missing:
            b["status"], b["fail_reason"] = "failed", f"reocr 缺页 {missing}"
            save_manifest(job, m)
            out(f"[!] {bid} 还缺 {len(missing)} 页结果（{missing[:8]}…），本批暂不重建")
            continue
        results: dict[int, dict] = {}
        thin_pages: list[int] = []
        nv_pages: list[int] = []
        for p in b["pages"]:
            f = store / f"p{p:04d}.json"
            # 已有视觉结果的页一律优先用结果（哪怕它不在本次 --pages 里）：
            # 否则一次「只补几页」的重建会把先前重识别过的页退回本地 pull 文字层。
            if f.exists():
                results[p] = json.loads(f.read_text(encoding="utf-8"))
            elif exact:
                r = pull_result(p)
                results[p] = r
                if r["thin"]:
                    thin_pages.append(p)
                if r["needs_vision"]:
                    nv_pages.append(p)
            else:  # 非 exact 已由上面的 missing 拦掉，这里只防御
                results[p] = {"page": p, "body": ["〔空白页〕"], "notes": [], "figs": []}
        _write_batch_from_pages(job, b, results)
        b["status"], b["fail_reason"] = "assigned", None
        b["thin_pages"], b["needs_vision"] = thin_pages, nv_pages
        save_manifest(job, m)
        try:
            cmd_check(argparse.Namespace(job=a.job, batch=bid, force=a.force, min=a.min), m=m)
            rebuilt.append(bid)
        except SystemExit:
            out(f"[!] {bid} 重建后质检不过（原因见上）；可 --force 放行或人工看")
    if pull_doc is not None:
        pull_doc.close()

    if rebuilt:
        # 正文变了，旧跨页缝合/章表全部作废，强制重切
        if (job / ".stitched.json").exists():
            (job / ".stitched.json").unlink()
        m["chapters"], m["chapter_warnings"], m["packed"] = [], [], False
        save_manifest(job, m)
    out(f"[ok] reocr 完成：重建 {len(rebuilt)} 批（{rebuilt[:8]}{'…' if len(rebuilt) > 8 else ''}）")
    if rebuilt:
        out("下一步：chapters 重切章 → figure-desc（若新增图）→ pack")


# ---------------------------------------------------------------- figure-desc（pull 图补视觉描述）

def _aivision_figure(base: str, key: str, model: str, img: Path, pageno: int,
                     timeout: int = 120) -> dict:
    """单图识语义：按精确 rect 裁出的小图问视觉 API，返回 {title, desc, kind}。
    小图 token 远小于整页；坐标准（程序裁），语义有（视觉补）。"""
    import base64 as _b64
    import urllib.request
    data_uri = "data:image/png;base64," + _b64.b64encode(img.read_bytes()).decode()
    body = {
        "model": model,
        "temperature": 0.1,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text":
                f"这是从一本书第 {pageno} 页按精确坐标裁出的一张插图。"
                "只输出一个 JSON 对象（不要任何多余文字、不要代码块标记）：\n"
                '{"title": "图题：优先用图上印的标题/图号（如「图3-1 xx」），图上没有就一句话概括内容", '
                '"desc": "一两句话说明图里画的是什么，读者不看图也能知道图讲什么", '
                '"kind": "photo|table|diagram 三选一（照片插图/表格图/示意图或流程图）"}'},
            {"type": "image_url", "image_url": {"url": data_uri}},
        ]}],
    }
    req = urllib.request.Request(
        base.rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        resp = json.loads(r.read().decode("utf-8"))
    text = resp["choices"][0]["message"]["content"].strip()
    mm = re.search(r"\{.*\}", text, re.S)  # 容忍 ```json 包裹/前后废话
    if not mm:
        raise ValueError(f"输出不是 JSON 对象：{text[:60]}")
    got = json.loads(mm.group(0))
    if not isinstance(got, dict):
        raise ValueError("输出不是 JSON 对象")
    return got


def cmd_figdesc(a: argparse.Namespace) -> None:
    """pull 登记的图只有坐标没有语义（title=第N页插图、desc=固定占位）：按 rect 裁小图
    → 视觉 API 逐图补 title/desc/kind → 写回台账。幂等：desc 仍是占位文案的才算待办。"""
    import concurrent.futures as futures
    job = resolve_job(a.job)
    m = load_manifest(job)
    todo = {i: f for i, f in enumerate(m["figures"]) if f.get("desc") == PULL_FIG_DESC}
    if not todo:
        out("[ok] 没有待补描述的图（pull 登记的占位图已全部处理或本书无图）")
        return
    out(f"[i] 待补语义 {len(todo)} 张图（desc 仍为 pull 占位文案的）")

    # 1. 裁图（确定性，不花 API）：dpi 用渲染同级，存 figures/{id}_p{页号}.png，记宽高与 file
    fitz = load_fit()
    doc = fitz.open(m["src_pdf"])
    fig_dir = job / "figures"
    fig_dir.mkdir(exist_ok=True)

    def crop_to(fp: Path, rect: list, pageno: int, dpi: int):
        page = doc[pageno - 1]
        r = page.rect
        l, t, rr, bb = rect
        clip = fitz.Rect(r.width * l, r.height * t, r.width * rr, r.height * bb)
        if clip.width * clip.height < r.width * r.height * 0.01:
            raise ValueError("rect 过小疑似标错")
        pix = page.get_pixmap(dpi=dpi, clip=clip)
        if not m["color"] and pix.colorspace and pix.colorspace.n > 1:
            pix = fitz.Pixmap(fitz.csGRAY, pix)
        pix.save(str(fp))
        return pix

    crops: dict[int, Path] = {}
    for i, f in todo.items():
        try:
            fp = fig_dir / f"{f['id']}_p{f['page']:03d}.png"
            pix = crop_to(fp, f["rect"], f["page"], a.dpi)
            # 伪图判定：极扁横条（高 <48px 或高宽比 <0.06）是影印页上的装饰线/表格线，
            # 不是真图——喂视觉 API 只会报错（实测商汤 413），直接标记跳过
            if pix.height < 48 or pix.height / max(1, pix.width) < 0.06:
                f["w"], f["h"] = pix.width, pix.height
                f["file"] = f"figures/{fp.name}"
                f["desc"] = "页面装饰线（自动识别，无图义）"
                out(f"  [i] 图 {f['id']}（p{f['page']}）是装饰线/表格线，跳过识图")
                continue
            crops[i] = fp
            f["w"], f["h"] = pix.width, pix.height
            f["file"] = f"figures/{fp.name}"
        except Exception as e:
            out(f"  [警告] 图 {f['id']}（第 {f['page']} 页）裁剪失败：{e}")
    save_manifest(job, m)  # 宽高/file 先落（dry-run 或 API 失败时 zip 仍可用图）
    if not crops:
        doc.close()
        if any("装饰线" in (f.get("desc") or "") for f in todo.values()):
            out("[ok] 待补的图全是装饰线/表格线（无需识图），台账已标记")
        else:
            die("一张图都没裁出来，检查台账 rect（可跑 status 看figures记录）")
        return
    if a.dry_run:
        doc.close()
        out(f"[ok] dry-run：{len(crops)} 张已裁到 {fig_dir}，未调 API；确认裁得对后去掉 --dry-run 补描述")
        return

    # 2. 并发调视觉 API（单图失败重试一次；413 请求体过大按 dpi 减半重裁小图再试）
    base, key, model = _load_vision_api()
    workers = a.workers if a.workers is not None else m.get("config", {}).get("aivision_workers", 4)
    out(f"[i] 视觉 API {model} @ {base.split('//')[-1].split('/')[0]}（key 已打码），并发 {workers}")
    done, fails = 0, []

    def ask(i: int, fp: Path) -> dict:
        f = m["figures"][i]
        try:
            return _aivision_figure(base, key, model, fp, f["page"])
        except Exception as e:
            msg = f"{e}"
            if "413" in msg or "Too Large" in msg:
                small = fp.with_name(fp.stem + "_small.png")
                crop_to(small, f["rect"], f["page"], max(96, a.dpi // 2))
                return _aivision_figure(base, key, model, small, f["page"])
            raise

    with futures.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(ask, i, fp): i for i, fp in crops.items()}
        for fu in futures.as_completed(futs):
            i = futs[fu]
            f = m["figures"][i]
            try:
                got = fu.result()
            except Exception:
                try:
                    got = ask(i, crops[i])
                    out(f"  · p{f['page']} 重试成功")
                except Exception as e2:
                    fails.append(f"{f['id']}@p{f['page']}")
                    out(f"  · {f['id']}（p{f['page']}）失败：{str(e2)[:80]}")
                    continue
            if got.get("title"):
                f["title"] = str(got["title"]).strip()
            if got.get("desc"):
                f["desc"] = str(got["desc"]).strip()
            if got.get("kind") in ("photo", "table", "diagram"):
                f["kind"] = got["kind"]
            done += 1
    doc.close()
    save_manifest(job, m)
    tail = f"，失败 {len(fails)} 张（{fails[:8]}{'…' if len(fails) > 8 else ''}）——重跑只补失败的" if fails else ""
    out(f"[ok] figure-desc 完成：补语义 {done} 张{tail}")
    if done:
        out("下一步：pack 重写正文图占位行 + 产 KIT 图包")


# ---------------------------------------------------------------- pull（文字层拔取）

PNUM_RE = re.compile(r"^[\s·•.\-—]*\d{1,4}[\s·•.\-—]*$")


def _embedded_fig_rects(page) -> list[list[float]]:
    """页面上面积占比 0.015~0.85 的嵌入图块 rect（比例坐标 [左,上,右,下]）。
    整页扫描底图/水印与小图标滤掉；pull 登记与 check 的目测坐标交叉校正共用。"""
    H = page.rect.height
    rects: list[list[float]] = []
    for img in page.get_images(full=True):
        try:
            rs = page.get_image_rects(img[0])
        except Exception:
            continue
        for r in rs:
            area = (r.width * r.height) / (page.rect.width * H)
            if 0.015 < area < 0.85:
                rects.append([round(r.x0 / page.rect.width, 4), round(r.y0 / H, 4),
                              round(r.x1 / page.rect.width, 4), round(r.y1 / H, 4)])
    return rects


def _rect_iou(a: list[float], b: list[float]) -> float:
    """两个比例 rect 的交并比（0~1）。"""
    ix0, iy0 = max(a[0], b[0]), max(a[1], b[1])
    ix1, iy1 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix1 - ix0) * max(0.0, iy1 - iy0)
    if inter <= 0:
        return 0.0
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    return inter / (area_a + area_b - inter)

def _page_lines(page) -> list:
    """把一页的 dict 结构摊平成**视觉行**，按真实阅读序排好。

    2026-10-27 关键修正（两个独立 bug，都会造成整页乱断句）：
    ① 部分扫描页 `page.rotation=90`，PyMuPDF 的 dict 坐标仍是未旋转画布
       （一个横排句子变成 h≈325、w≈15 的竖条），旧版按 y0 排序必然读成乱序。
       这里先用 `page.rotation_matrix` 把 bbox 变换到显示坐标，再排序。
    ② 引号/上标脚注的 bbox 上沿与正文行差 3~8pt，旧版 2pt 容差会把同一行
       拆成三行并排错位置。现在按 bbox 垂直重叠聚成视觉行（重叠 ≥45% 行高
       即同一行），行内按 x 排序；只有横向间隙小于 4 字号的碎片才拼回一行。
    多栏/表格行保留 n_frag / max_gap 供图示区检测使用。"""
    if hasattr(page, "get_text"):
        src = page.get_text("dict")
        rot = page.rotation_matrix if getattr(page, "rotation", 0) else None
    else:  # 旧调用方式：直接传 dict（无法处理旋转，由调用方负责）
        src, rot = page, None
    fitz = load_fit() if rot is not None else None
    lines = []
    for blk in src.get("blocks", []):
        if blk.get("type") != 0:
            continue
        for ln in blk.get("lines", []):
            spans = [s for s in ln.get("spans", []) if s.get("text", "").strip()]
            if not spans:
                continue
            # 旋转页的 span 坐标也要先变换再排序：未旋转画布里同一行 span 的 x0 几乎
            # 相同（都等于行首），按原 x 排会保留内容流顺序，把「（第」「1201」拆错位。
            if rot is not None:
                spans.sort(key=lambda s: (fitz.Rect(s["bbox"]) * rot).x0)
            else:
                spans.sort(key=lambda s: s["bbox"][0])
            text = "".join(s["text"] for s in spans).strip()
            if not text:
                continue
            x0, y0, x1, y1 = ln["bbox"]
            if rot is not None:
                r = fitz.Rect(x0, y0, x1, y1) * rot
                x0, y0, x1, y1 = r.x0, r.y0, r.x1, r.y1
            size = max(s["size"] for s in spans)
            lines.append({"text": text, "size": size, "x0": x0, "y0": y0, "x1": x1, "y1": y1,
                          "cy": (y0 + y1) / 2, "h": max(1.0, y1 - y0)})

    # 1) 按垂直重叠聚行（不再用固定 y0 容差）
    rows: list = []
    for l in sorted(lines, key=lambda z: (z["cy"], z["x0"])):
        target = None
        for r in rows:
            oy0, oy1 = max(r["y0"], l["y0"]), min(r["y1"], l["y1"])
            ov = oy1 - oy0
            if ov >= 0.45 * min(r["h"], l["h"]):
                target = r
                break
        if target is None:
            rows.append({"y0": l["y0"], "y1": l["y1"], "h": l["h"], "cy": l["cy"], "items": [l]})
        else:
            target["items"].append(l)
            target["y0"] = min(target["y0"], l["y0"])
            target["y1"] = max(target["y1"], l["y1"])
            target["h"] = max(1.0, target["y1"] - target["y0"])
            target["cy"] = (target["y0"] + target["y1"]) / 2

    # 2) 行间自上而下；行内自左而右，小间隙碎片接回同一行
    rows.sort(key=lambda r: (r["cy"], min(i["x0"] for i in r["items"])))
    merged: list = []
    for r in rows:
        items = sorted(r["items"], key=lambda z: z["x0"])
        text, prev, n_frag, max_gap = "", None, 0, 0.0
        for it in items:
            if prev is None:
                text = it["text"]
            else:
                gap = it["x0"] - prev["x1"]
                max_gap = max(max_gap, gap)
                size = max(prev["size"], it["size"])
                if -size < gap < 4 * size:
                    a, bx = text[-1:], it["text"][:1]
                    sep = " " if a.isascii() and a.isalpha() and bx.isascii() and bx.isalpha() else ""
                    text += sep + it["text"]
                else:
                    text += " " + it["text"]
                    n_frag += 1
            prev = it
        merged.append({"text": text.strip(), "size": max(i["size"] for i in items),
                       "x0": min(i["x0"] for i in items), "y0": r["y0"],
                       "x1": max(i["x1"] for i in items), "y1": r["y1"],
                       "n_frag": n_frag, "max_gap": max_gap})
    return [m for m in merged if m["text"]]


# ---------------------------------------------------------------- OCR 文字层纠错

# 扫描件 OCR 层系统性误码：繁体「於/于」被识成「千」（王泽鉴三本书实测
# 976/525/521 处）。判据反过来：简体正文里「千 + 汉字」几乎必然是「于」，
# 只有下列千字词/成语的后续字、以及数字前缀（一千、两千、数千、成千）才保留。
_QIAN_LEGIT_NEXT = set("年月日万字百十人古秋金克米瓦佛岛山仞钧载禧里军马言语丝缕篇律姿态奇怪家户呼唤恩谢叮嘱疮孔锤炼难险水落乘门生"
                       "元圆倍余块吨亩顷尺寸丈套辆架艘匹只棵株座层间页张片场次遍回件名句计般变差奇真辛方丝头态状岁夜")
_QIAN_NUM_PREV = set("0123456789〇零一二三四五六七八九十百千万亿两几数成上若")
# 只在繁体字形中出现、简体书里不该出现的字（由 zhconv 繁→简映射静态生成的 4056 字）。
# 扫描件 OCR 层在部分页整页返回繁体字形（「枚利」「惡則」「美系」），这是最可靠的
# 「该页文字层不可信」信号；简体正文误报率实测为 0。
_TRAD_ONLY = frozenset(
    "丟丣並乗乹乾亁亂亙亝亞亱亷亾佇佈佔併來侖侶侷俁係俓俔俠俥俬俻倀倆倈倉個倐們倖倣倫倲倸偉偑側偵偽傌傑傖傘備傚傢傭傯傳傴債傷傾僂僅僉僊働僑僕僞僤僥僨僱價儀儁儂億儈儉"
    "儌儎儐儔儕儘償儣優儭儲儷儸儺儻儼兇兌兎兒兗兠內兩冄冊冐冑冪冺凈凍凙凜凢凱凴別刦刧刪刼剄則剉剋剎剏剗剙剛剝剮剳剴創剷剹剾劃劄劇劉劊劌劍劏劑劒劚効勁勅勌勑動務勛勝勞"
    "勢勣勦勩勱勳勵勸勻匟匭匯匱匲匳區協卹卻卽厀厙厠厤厭厰厲厴參叄叢吒吚吳吶呂呌呪咊咼員哯哶唄唓唕唘唚唸啎問啑啓啗啞啟啢啣喎喚喪喫喬單喲嗁嗆嗇嗊嗎嗚嗩嗰嗶嗹嘆嘍嘑嘓嘔"
    "嘖嘗嘜嘠嘩嘪嘮嘯嘰嘳嘵嘷嘸嘺嘽噁噅噉噓噚噝噞噠噥噦噯噲噴噸噹嚀嚇嚌嚐嚕嚙嚛嚥嚦嚧嚨嚮嚲嚳嚴嚶嚽囀囁囂囃囅囈囉囌囑囒囓囙囪圅圇國圍園圓圖團圞坿垜垻埡埨埬埰執堅堊堖"
    "堚堝堯報場塊塋塏塒塗塚塟塢塤塲塵塸塹塿墊墖墜墠墮墰墲墳墶墻墾壇壈壋壎壓壗壘壙壚壜壞壟壠壢壣壩壪壯壺壻壼壽夘夠夢夥夾奐奧奩奪奬奮奼妝妬妳妷姉姍姙姦姪姸娙娛婁婡婣婦"
    "婬婭媈媍媧媯媰媼媽媿嫋嫗嫰嫵嫺嫻嫿嬀嬃嬇嬈嬋嬌嬙嬝嬡嬣嬤嬦嬪嬭嬰嬸嬻嬾孃孄孆孇孋孌孎孫學孻孼孾孿宂宮寀寃寑寠寢實寧審寫寬寳寵寶尅將專尋對導尒尙尟尠尷屆屍屓屛屜屢"
    "層屨屩屬屭岅岡峝峯峴島峽崍崑崗崙崢崬嵐嵗嵼嵽嵾嶁嶃嶄嶇嶈嶔嶗嶘嶠嶢嶧嶨嶮嶸嶹嶺嶼嶽巊巋巒巔巖巗巘巠巰巵巹帀帋帥師帬帳帶幀幃幇幑幓幗幘幙幚幝幟幣幩幫幬幹幾庫庻庽廁"
    "廂廄廈廎廐廕廚廝廞廟廠廡廢廣廧廩廬廳廵廹廻弒弔弳張強彃彄彆彈彌彎彔彙彞彠彥彫彲彿徃後徑從徠徧復徵徹徿怱怳恆恠恡恥悅悞悤悵悶悽惏惡惥惱惲惷惻愛愜愨愴愷愻愽愾慄態慍"
    "慘慙慚慟慣慤慪慫慮慳慴慶慺慼慽慾憂憇憊憍憐憑憒憖憚憢憤憫憮憲憶憸憹懀懇應懌懍懎懞懟懣懤懧懨懲懶懷懸懺懼懾戀戇戔戞戧戩戰戱戲戶戹扞抝拋拚挩挱挵挾捄捨捫捱捲掃掄掆掗"
    "掙掚掛採掽揀揑揚換揫揮揯揷揹搆搇搉損搖搗搤搥搨搯搵搶搾摃摋摐摑摜摟摯摳摶摺摻撈撊撏撐撓撝撟撡撣撥撦撧撫撲撳撻撾撿擁擄擇擊擋擓擔擕據擟擠擣擥擧擫擬擯擰擱擲擴擷擺擻"
    "擼擽擾攄攆攋攏攔攖攙攛攜攝攢攣攤攩攪攬攷敂敍敎敓敗敘敵數敺斂斃斅斆斕斬斷斸於旂旣旤旹旾昇昬時晉晛晝暈暉暐暘暢暫暱曄曆曇曉曊曏曖曠曡曥曨曬書會朞朢朥朧朮朶東杴枱枴"
    "柵柺査栁栞栢栰桒桮桱桺桿梔梖梘梜條梟梲棄棊棖棗棟棡棧棲棶椏椗椲椶椷椾楇楊楓楥楨業極榘榦榪榮榲榿構槍槓槕槤槧槨槫槮槳槶槼樁樂樅樑樓標樞樠樢樣樤樧樫樳樸樹樺樿橈橋橜"
    "機橢橫橯檁檉檔檜檟檢檣檭檮檯檳檵檸檻櫃櫅櫈櫍櫓櫚櫛櫝櫞櫟櫠櫥櫧櫨櫪櫫櫬櫱櫳櫸櫻欄欅欇權欍欏欐欑欒欓欖欘欝欞欵欽歎歐歛歟歡歲歴歷歸歿殀殘殞殢殤殨殫殭殮殯殰殲殺殻殼"
    "毀毆毊毧毬毿氂氈氊氌氣氫氬氭氳氷氾汎汙汚決沒沖況泝洩洶浹浿涇涖涗涼淒淚淛淥淨淩淪淵淶淺渖渙減渢渦測渾湊湋湞湧湯湼溈準溝溡溫溮溳溼滄滅滌滎滙滛滬滯滲滷滸滻滾滿漁漊"
    "漍漚漢漣漬漲漵漸漿潁潄潑潔潕潙潚潛潣潤潯潰潷潿澀澁澅澆澇澐澗澠澤澦澩澫澬澮澱澾濁濃濄濆濇濕濘濚濛濜濟濤濧濫濰濱濶濺濼濾濿瀂瀃瀅瀆瀇瀉瀋瀏瀕瀘瀝瀟瀠瀦瀧瀨瀰瀲瀾灃"
    "灄灋灍灑灒灕灘灙灝灠灡灣灤灧灩災為烏烖烴無煇煉煑煒煗煙煢煥煩煬煱熂熅熈熉熌熒熓熗熚熡熰熱熲熾燀燁燄燈燉燒燖燙燜營燦燬燭燴燶燻燼燾爃爄爇爍爐爖爗爛爥爧爭爲爺爾牀牆"
    "牋牎牐牓牕牘牠牴牽犖犛犞犢犧狀狥狹狽猂猌猙猨猶猻獁獃獄獅獊獎獘獧獨獩獪獫獮獰獱獲獵獷獸獺獻獼玀玁玅珼現琖琱琺琿瑇瑋瑒瑣瑤瑩瑪瑯瑲瑻瑽璉璊璕璗璝璡璢璣璦璫璯環璵璸"
    "璼璽璾璿瓄瓅瓊瓏瓔瓕瓚瓛甌甎甕甖甞產産甦甯畂畆畝畢畧畫畮異畱畵當畼疇疊疎疘疿痐痙痠痮痲痺痾瘂瘉瘋瘍瘓瘖瘞瘡瘧瘮瘱瘲瘺瘻療癄癅癆癇癈癉癐癒癘癟癡癢癤癥癧癩癬癭癮癰"
    "癱癲發皁皐皚皜皟皰皷皸皺盃盇盌盜盞盡監盤盧盨盪眎眝眞眡眥眾睍睏睜睞睠睪瞇瞖瞘瞜瞞瞤瞭瞶瞼矁矇矉矑矓矙矚矯矽砲硃硜硤硨硯碕碙碩碪碭碸確碼碽磑磚磟磠磣磧磯磽磾礄礆礎"
    "礐礒礙礦礪礫礬礮礱祕祘祿禍禎禕禡禦禪禮禰禱禿秈秊秌秖稅稈稉稏稜稟稬稭種稱稾穀穇穌積穎穠穡穢穤穨穩穫穭穽窓窩窪窮窯窰窵窶窺窻竄竅竇竈竊竚竝竢竪竱競筆筍筞筧筩筯筴箇"
    "箋箏箒箠節範築篋篔篘篛篠篢篤篩篳篸簀簂簍簑簒簞簡簢簣簫簮簷簹簽簾籃籅籋籌籐籔籙籛籜籟籠籤籩籪籬籮籲粃粧粵糉糝糞糧糰糲糴糶糹糺糾紀紂紃約紅紆紇紈紉紋納紐紓純紕紖紗"
    "紘紙級紛紜紝紞紟紡紥紬紮細紱紲紳紵紹紺紼紿絀絁終絃組絅絆絍絎絏結絕絙絛絝絞絡絢絥給絧絨絪絰統絲絳絶絹絺綀綁綃綄綆綇綈綉綋綌綎綏綐綑經綖綜綝綞綟綠綡綢綣綧綪綫綬維"
    "綯綰綱網綳綴綵綸綹綺綻綽綾綿緄緇緊緋緍緐緑緒緓緔緗緘緙線緜緝緞緟締緡緣緤緥緦編緩緬緮緯緰緱緲練緶緷緸緹緻緼縈縉縊縋縍縎縐縑縕縗縛縝縞縟縣縧縫縬縭縮縯縰縱縲縳縴縵"
    "縶縷縸縹縺總績繂繃繅繆繈繏繐繒繓織繕繖繙繚繞繟繡繢繦繨繩繪繫繬繭繮繯繰繳繶繷繸繹繻繼繽繾繿纁纆纇纈纊續纍纏纓纔纕纖纗纘纚纜缐缽罃罇罈罋罌罎罰罵罷罸羅羆羈羋羗羢羣"
    "羥羨義羵羶翄習翫翬翶翹翽耡耬耮聖聞聯聰聲聳聵聶職聹聻聽聾肅肎肐肧胷脃脅脇脈脗脛脣脥脩脫脹腁腎腖腡腦腪腫腳腸膃膓膕膚膞膠膢膩膹膽膾膿臈臉臋臍臏臕臗臘臙臚臝臟臠臢臥"
    "臨臯臺與興舉舊舖舘舩艙艢艣艤艦艪艫艱艷芲芻茘茲荊荳莊莖莢莧菕菫華菴菸萇萊萬萲萴萵葉葒葝葠葤葦葯葷蒍蒐蒓蒔蒕蒞蒭蒼蓀蓆蓋蓡蓧蓮蓯蓴蓽蔄蔔蔕蔘蔞蔣蔥蔦蔭蔯蔿蕁蕆蕎蕒"
    "蕓蕕蕘蕚蕝蕢蕩蕪蕭蕳蕷蕽蕿薀薆薈薊薌薑薔薘薟薦薩薳薴薵薹薺藍藎藝藥藪藭藴藶藷藹藺藼蘀蘄蘆蘇蘊蘋蘐蘓蘚蘞蘟蘢蘤蘭蘺蘿虆虉處虛虜號虧虯蚘蛕蛺蛻蜆蜋蜖蜨蝀蝕蝟蝦蝨蝯蝱"
    "蝸螄螎螞螡螢螮螻螿蟁蟂蟄蟇蟈蟎蟘蟜蟣蟬蟯蟲蟳蟶蟻蠀蠁蠅蠆蠍蠏蠐蠑蠒蠔蠙蠟蠣蠦蠨蠭蠱蠶蠻蠾衂衆衇衊術衕衚衛衝衞衺袞袟袵裊裌裏補裝裠裡製複褌褘褭褲褳褸褻襀襃襇襉襍襏"
    "襓襖襗襘襝襠襤襪襬襯襰襲襴襵覇覈覊見覎規覓覔視覘覛覡覥覦覩親覬覯覲覷覹覺覼覽覿觀觝觴觶觸訁訂訃計訊訌討訏訐訑訒訓訕訖託記訛訜訝訞訟訢訣訥訨訩訪設許訴訶診註証詀詁"
    "詆詊詎詐詑詒詓詔評詖詗詘詛詝詞詠詡詢詣試詩詪詫詬詭詮詰話該詳詵詶詷詼詿誂誄誅誆誇誋誌認誑誒誕誖誘誚語誠誡誣誤誥誦誨說誫説誰課誳誴誶誷誹誺誼誾調諂諄談諉請諍諏諑諒"
    "諓論諗諛諜諝諞諟諡諢諣諤諥諦諧諫諭諮諯諰諱諲諳諴諶諷諸諺諼諾謀謁謂謄謅謆謉謊謌謎謏謐謔謖謗謙謚講謝謠謡謨謫謬謭謯謱謳謸謹謾譁譂譅譆證譊譌譎譏譑譓譔譖識譙譚譜譞譟"
    "譨譫譭譯議譴護譸譼譽譾讀讁讅變讋讌讎讐讒讓讕讖讚讜讞讬谘豈豎豐豓豔豬豵豶貍貓貗貙貛貝貞貟負財貢貧貨販貪貫責貯貰貲貳貴貶買貸貺費貼貽貿賀賁賂賃賄賅資賈賉賊賑賒賓賕"
    "賙賚賛賜賝賞賟賠賡賢賣賤賦賧質賫賬賭賰賴賵賷賺賻購賽賾贃贄贅贇贈贉贊贋贍贏贐贑贓贔贖贗贚贛贜赬趂趕趙趨趲跡跥踁踐踫踰踴蹌蹏蹔蹕蹟蹠蹣蹤蹧蹳蹵蹺蹻躂躉躊躋躍躎躑躒"
    "躓躕躘躚躝躡躥躦躪躭躳躶軀軉車軋軌軍軏軑軒軔軕軗軛軜軝軟軤軨軫軬軲軷軸軹軺軻軼軾軿較輄輅輇輈載輊輋輒輓輔輕輖輗輙輛輜輝輞輟輢輥輦輨輩輪輬輭輮輯輳輶輷輸輻輼輾輿轀"
    "轂轄轅轆轇轉轊轍轎轐轔轗轟轠轡轢轣轤辠辢辤辦辭辮辯農辳迴迻逈逕這連逥逩逬週進遉遊運過達違遙遜遞遠遡適遯遱遲遶遷選遺遼邁還邇邊邏邐郟郵鄆鄉鄒鄔鄖鄟鄧鄩鄭鄰鄲鄳鄴鄶"
    "鄺酇酈酖酧醃醆醕醖醜醞醟醣醫醬醯醱醲醶醻醼釀釁釃釅釋釐釒釓釔釕釗釘釙釚針釟釣釤釦釧釨釩釬釲釳釴釵釷釹釺釾釿鈀鈁鈃鈄鈅鈆鈇鈈鈉鈋鈍鈎鈐鈑鈒鈔鈕鈖鈗鈛鈞鈠鈡鈣鈥鈦鈧"
    "鈮鈯鈰鈲鈳鈴鈷鈸鈹鈺鈽鈾鈿鉀鉁鉄鉅鉆鉈鉉鉊鉋鉍鉑鉔鉕鉗鉚鉛鉝鉞鉠鉢鉤鉥鉦鉧鉨鉬鉭鉮鉲鉳鉶鉷鉸鉺鉻鉽鉾鉿銀銁銂銃銅銈銊銍銏銑銓銖銘銚銛銜銠銣銥銦銨銩銪銫銬銱銲銳銶"
    "銷銹銻銼鋁鋂鋃鋅鋇鋉鋌鋏鋐鋒鋗鋙鋝鋟鋠鋣鋤鋥鋦鋨鋩鋪鋭鋮鋯鋰鋱鋶鋸鋹鋼錀錁錂錄錆錇錈錏錐錒錕錘錙錚錛錜錝錞錟錠錡錢錤錥錦錨錩錫錮錯録錳錶錸錼錽鍀鍁鍃鍄鍅鍆鍇鍈鍉"
    "鍊鍋鍍鍒鍔鍘鍚鍛鍠鍤鍥鍩鍫鍬鍭鍮鍰鍳鍵鍶鍺鍼鍾鎂鎄鎇鎈鎊鎌鎍鎓鎔鎖鎗鎘鎙鎚鎛鎝鎞鎡鎢鎣鎦鎧鎩鎪鎬鎭鎮鎯鎰鎲鎳鎵鎶鎷鎸鎻鎿鏃鏆鏇鏈鏉鏌鏍鏏鏐鏑鏗鏘鏚鏜鏝鏞鏟鏡鏢鏤"
    "鏥鏦鏨鏰鏵鏷鏹鏺鏻鏽鏾鐃鐄鐇鐈鐋鐍鐎鐏鐐鐒鐓鐔鐘鐙鐝鐠鐥鐦鐧鐨鐩鐪鐫鐮鐯鐲鐳鐵鐶鐸鐺鐼鐽鐿鑀鑄鑈鑉鑊鑌鑑鑒鑔鑕鑚鑛鑞鑠鑣鑤鑥鑪鑭鑰鑱鑲鑴鑵鑷鑹鑼鑽鑾鑿钁钂钜锺镟"
    "長門閂閃閆閈閉開閌閍閎閏閐閑閒間閔閗閘閙閝閞閡閣閤閥閧閨閩閫閬閭閱閲閵閶閹閻閼閽閾閿闃闆闇闈闉闊闋闌闍闐闑闒闓闔闕闖闚關闞闠闡闢闤闥阨阬陗陘陜陝陞陣陰陳陸陻陽陿"
    "隂隄隉隊階隑隕隖際隣隤隨險隮隯隱隴隷隸隻雋雖雙雛雜雞離難雲電霑霢霣霧霼霽靂靄靆靈靉靚靜靝靦靧靨靭靱鞀鞏鞝鞦鞵鞽鞾韁韃韆韈韉韋韌韍韓韙韚韛韜韝韞韠韤韮韻響頁頂頃項"
    "順頇須頊頌頍頎頏預頑頒頓頔頗領頜頟頠頡頤頦頫頭頮頰頲頴頵頷頸頹頻頼頽顂顃顅顆顇顋題額顎顏顒顓顔顗願顙顛類顢顣顥顦顧顫顬顯顰顱顳顴風颭颮颯颰颱颳颶颷颸颺颻颼颾飀飃"
    "飄飆飈飋飛飜飠飢飣飤飥飦飩飪飫飭飯飱飲飴飵飶飼飽飾飿餁餃餄餅餈餉養餌餎餏餑餒餓餔餕餖餗餘餚餛餜餞餡餦餧館餪餫餬餭餱餳餵餶餷餸餹餺餻餼餽餾餿饁饃饅饈饉饊饋饌饍饑饒"
    "饗饘饜饝饞饟饠饢馀馬馭馮馯馱馳馴馹馼駁駃駈駉駊駎駐駑駒駓駔駕駘駙駚駛駝駞駟駡駢駤駧駩駪駫駭駰駱駶駸駻駼駿騁騂騃騄騅騉騊騌騍騎騏騐騑騔騖騙騚騜騝騞騟騠騣騤騧騪騫騭"
    "騮騰騱騴騵騶騷騸騻騼騾驀驁驂驃驄驅驊驋驌驍驎驏驓驕驗驘驙驚驛驟驢驤驥驦驨驪驫骯骽骾髈髏髒體髕髖髥髮鬀鬆鬉鬍鬖鬚鬠鬢鬥鬦鬧鬨鬩鬪鬮鬰鬱鬹魎魘魚魛魟魢魥魦魨魯魴魵魷"
    "魺魽鮀鮁鮃鮄鮅鮆鮈鮊鮋鮍鮎鮐鮑鮒鮓鮚鮜鮝鮞鮟鮠鮡鮣鮤鮦鮪鮫鮭鮮鮯鮰鮳鮵鮶鮸鮺鮿鯀鯁鯄鯆鯇鯉鯊鯒鯔鯕鯖鯗鯛鯝鯞鯡鯢鯤鯧鯨鯪鯫鯬鯰鯱鯴鯶鯷鯻鯽鯾鯿鰁鰂鰃鰆鰈鰉鰊鰋鰌"
    "鰍鰏鰐鰑鰒鰓鰕鰛鰜鰟鰠鰣鰤鰥鰦鰧鰨鰩鰫鰭鰮鰱鰲鰳鰵鰶鰷鰹鰺鰻鰼鰽鰾鱀鱂鱄鱅鱆鱇鱈鱉鱊鱒鱔鱖鱗鱘鱚鱝鱟鱠鱢鱣鱤鱧鱨鱭鱮鱯鱲鱷鱸鱺鳥鳧鳩鳬鳲鳳鳴鳶鳷鳼鳽鳾鴀鴃鴅鴆鴇"
    "鴈鴉鴐鴒鴔鴕鴗鴛鴜鴝鴞鴟鴣鴥鴦鴨鴮鴯鴰鴲鴳鴴鴷鴻鴽鴿鵁鵂鵃鵊鵏鵐鵑鵒鵓鵚鵜鵝鵞鵟鵠鵡鵧鵩鵪鵫鵬鵮鵯鵰鵲鵶鵷鵾鶄鶇鶉鶊鶌鶒鶓鶖鶗鶘鶚鶠鶡鶥鶦鶩鶪鶬鶭鶯鶰鶱鶲鶴鶹鶺"
    "鶻鶼鶿鷀鷁鷂鷄鷅鷈鷉鷊鷐鷓鷔鷖鷗鷙鷚鷟鷣鷤鷥鷦鷨鷩鷫鷭鷯鷰鷲鷳鷴鷷鷸鷹鷺鷽鷿鸂鸇鸊鸋鸌鸎鸏鸑鸕鸗鸘鸚鸛鸝鸞鹵鹹鹺鹻鹼鹽麗麥麨麩麪麫麬麯麲麳麴麵麷麹麺麼麽黃黌點黨"
    "黲黴黶黷黽黿鼂鼃鼇鼈鼉鼕鼴齊齋齎齏齒齔齕齗齘齙齜齟齠齡齣齦齧齩齪齬齭齮齯齰齲齴齶齷齼齾龍龎龐龑龓龔龕龜龭龯鿁鿐鿓"
)



def _fix_ocr_text(s: str) -> str:
    """pull 路线专用：修 OCR 层的系统性形近误字（不影响视觉路线）。"""
    if not s:
        return s
    # 上标脚注标记被 OCR 成 ＠：句末/段首的 ＠ 直接删（脚注本体在 notes 里），
    # 不当成列表序号——「微博＠北京大学出版社」这类正文里的 ＠ 不受影响
    s = re.sub(r"＠(?=[。，；：、！？）】》]|$)", "", s)
    s = re.sub(r"(^|(?<=[。；：！？]))＠(?=[\u4e00-\u9fff])", r"\1", s)
    # 列表标记 ①②③④ 被 OCR 认成 心/卫/卢/竺；只替换「段首或句读后 + 紧跟汉字」
    # 的位置，并避开「心理/心证/卫生/卢梭」等真词（统一换成 ●，不猜具体序号）
    s = re.sub(r"(^|(?<=[。；：！？\n]))心(?![理中情态灵得意愿证血脏智存怀思])(?=[\u4e00-\u9fff])"
               r"|(^|(?<=[。；：！？\n]))[卫卢竺](?![生星梭森法])(?=[\u4e00-\u9fff])",
               lambda m: (m.group(1) if m.group(1) is not None else m.group(2)) + "●", s)
    if "千" not in s:
        return s
    out: list[str] = []
    for i, ch in enumerate(s):
        if ch == "千":
            prev = s[i - 1] if i > 0 else ""
            nxt = s[i + 1] if i + 1 < len(s) else ""
            is_cjk = "\u4e00" <= nxt <= "\u9fff"
            # 合法千：数字前缀（一千、两千、数千、成千、上万）或千字词后续字
            if prev in _QIAN_NUM_PREV or nxt in _QIAN_LEGIT_NEXT:
                out.append(ch)
                continue
            # 「相当于千台湾」：前面的于已正确，这个千是 OCR 多出来的，删掉
            if prev == "于" and (is_cjk or nxt.isdigit()):
                continue
            # 其余「千 + 汉字/数字/标点」= 繁体「於」被识成「千」。
            # 实测「有助于千」「致力于千」「于千，」「屈服千」「使其千」都靠这条兜住。
            if nxt and (is_cjk or nxt.isdigit() or (nxt.isascii() and nxt.isalpha())
                        or nxt in "，。；：、！？（）“”｀“＂'\"（《"):
                out.append("于")
                continue
        out.append(ch)
    return "".join(out)


def _fix_quotes(s: str) -> str:
    """修 OCR 层把成对引号拆错/认成正引号的问题。

    扫描件 OCR 常把后引号也认成「“」，于是出现「“民法“总则」这种两个正引号
    连用。只要一段里有两个以上正引号，就按「开-闭」交替纠正；只有一个正引号
    的段落可能跨段引用，保持原样不硬改。同时去掉引号内侧 OCR 带出的空格。"""
    if not s:
        return s
    # OCR 把中文引号认成 ｀/＇/＂：｀ 按开-闭交替还原，＇/＂ 一律当后引号
    if "｀" in s or "＇" in s or "＂" in s:
        s = s.replace("｀｀", "“").replace("＇", "”").replace("＂", "”")
        buf: list[str] = []
        opened = False
        for ch in s:
            if ch == "｀":
                buf.append("”" if opened else "“")
                opened = not opened
            else:
                buf.append(ch)
        s = "".join(buf)
    if "“" not in s and "”" not in s:
        return s
    s = re.sub(r"“\s+", "“", s)
    s = re.sub(r"\s+”", "”", s)
    s = re.sub(r"”\s+(?=[\u4e00-\u9fff])", "”", s)
    if s.count("“") < 2:
        return s
    out: list[str] = []
    opened = False
    for ch in s:
        if ch == "“":
            if opened:
                out.append("”")
                opened = False
            else:
                out.append(ch)
                opened = True
        elif ch == "”":
            if opened:
                out.append(ch)
                opened = False
            else:
                out.append("“")
                opened = True
        else:
            out.append(ch)
    return "".join(out)


# 图示/表格垃圾区检测：OCR 层把图里的坐标文字按框乱序拼出来时，会出现
# 「｛｝｀＼｜〜」这类正文几乎不会用的符号，或一行里多个冒号且没有句读。
_FIG_SYMBOLS = set(r"｛｝＼｜〜\|")


def _is_caption(t: str) -> bool:
    t = t.strip()
    return (4 <= len(t) <= 42 and not re.search(r"[，。；！？：]$", t)
            and not CH_RE.match(t) and not SEC_RE.match(t))


def _detect_fig_bands(rows: list, page) -> list:
    """在一页的视觉行里找出「图/表垃圾区」，返回
    [{start,end,rect,title,kind}]（start/end 为 rows 下标，rect 为比例坐标）。

    精度优先：只有出现 ≥2 个制图符号（｛｝｀＼｜〜 等，正文几乎不用）或
    密集冒号无句读的行才**启动**一个图区；相邻的表格式多碎片行可以并入，
    但不会单凭「碎片多」就删正文。面积 <2% 或高度 <2% 的小块直接忽略
    （影印页边缘常有一两个噪声符号，不能当图）。"""
    W, H = page.rect.width, page.rect.height
    kinds: list[str] = []
    for r in rows:
        t = r["text"]
        if r["y1"] <= 0 or r["y0"] >= H or r["y1"] <= r["y0"]:
            kinds.append("")
            continue
        sym = sum(1 for c in t if c in _FIG_SYMBOLS)
        strong = ("｛" in t or "｝" in t) or (sym >= 2 and sym / max(1, len(t)) >= 0.03)
        dense_colon = len(t) >= 10 and t.count("：") >= 4 and not re.search(r"[，。！？；]", t)
        table_like = (r.get("n_frag", 0) >= 2 and r.get("max_gap", 0) > 2.5 * r["size"]
                      and len(t) <= 80)
        kinds.append("strong" if (strong or dense_colon) else ("table" if table_like else ""))

    bands: list[dict] = []
    i = 0
    while i < len(rows):
        if kinds[i] != "strong":
            i += 1
            continue
        j = i
        while j + 1 < len(rows):
            k = kinds[j + 1]
            if k in ("strong", "table"):
                j += 1
                continue
            t = rows[j + 1]["text"]
            # 图区里夹的短残片（如「英”\」）也并入
            if len(t) <= 12 and any(c in _FIG_SYMBOLS for c in t):
                j += 1
                continue
            # 允许中间夹一行图题/说明（下一行仍是垃圾行）
            if j + 2 < len(rows) and kinds[j + 2] in ("strong", "table") and _is_caption(t):
                j += 2
                continue
            break
        # 紧跟其后的短行如果是图题，一并纳入（作为图题，不留在正文）
        end = j
        if end + 1 < len(rows):
            cap = rows[end + 1]
            if _is_caption(cap["text"]) and (cap["x1"] - cap["x0"]) < 0.75 * W:
                end += 1
        band_rows = rows[i:end + 1]
        widths = [r["x1"] - r["x0"] for r in band_rows]
        # 整段都是满行正文 → 符号只是正文里的特殊符号，不当图
        if widths and max(widths) > 0.9 * W and sum(1 for w in widths if w > 0.8 * W) >= max(2, len(widths) // 2):
            i = j + 1
            continue
        y0 = min(r["y0"] for r in band_rows)
        y1 = max(r["y1"] for r in band_rows)
        if y1 - y0 > 0.75 * H or y1 - y0 < 0.02 * H:
            i = j + 1
            continue
        x0 = min(r["x0"] for r in band_rows)
        x1 = max(r["x1"] for r in band_rows)
        if (x1 - x0) * (y1 - y0) < 0.02 * W * H:
            i = j + 1
            continue
        cap = [r["text"] for r in band_rows[-2:]
               if _is_caption(r["text"]) and not any(c in _FIG_SYMBOLS for c in r["text"])]
        rect = [max(0.02, x0 / W - 0.02), max(0.02, y0 / H - 0.02),
                min(0.98, x1 / W + 0.02), min(0.98, y1 / H + 0.02)]
        bands.append({"start": i, "end": end, "rect": [round(x, 4) for x in rect],
                      "title": cap[-1] if cap else "",
                      "kind": "table" if any(r.get("n_frag", 0) >= 2 for r in band_rows) else "diagram"})
        i = end + 1
    return bands


def _running_norm(text: str) -> str:
    """书眉/书脚归一化：去掉数字与空白（页码常粘在标题尾部），用于跨页比对。"""
    return re.sub(r"[\s\d０-９]+", "", text)


def _pull_page(page, fitz, body_size: float | None = None, lines: list | None = None,
               furniture: set[str] | None = None) -> dict:
    """拔一页：返回 {paras, notes, figs, chars, first/last 行属性}。页眉页脚/页码剔除，脚注按字号分区。

    body_size/lines 可由 cmd_pull 全书预算后传入：页内小样本会被脚注/年表等
    小字号块带偏主字号，致正文行被误判标题、脚注漏分区（全书主字号是稳定的）。

    职责声明（双端分段规则各自标定，勿互相"对齐"）：本函数阈值按**出版社级 PDF
    文字层**标定（字号/坐标来自内嵌字体度量，可靠），分钟级批量零 token；
    App 侧 PdfCleaner/ParagraphAssembler 面向手机导入、要同时伺候 OCR 行
    （size=框高×0.68 有系统性偏差），判据更保守。两套数值差异数值表见
    docs/模块总览/M9-书籍识别台bookocr-kit.md「双端分段规则对照」。"""
    H = page.rect.height
    raw = lines if lines is not None else _page_lines(page)

    # 正文主字号：中带区按字数加权的众数（未传全书值时按本页算）
    if body_size is None:
        mid = [l for l in raw if 0.10 * H < l["y0"] < 0.85 * H]
        tally: dict[float, int] = {}
        for l in mid:
            k = round(l["size"], 1)
            tally[k] = tally.get(k, 0) + len(l["text"])
        body_size = max(tally, key=tally.get) if tally else 12.0

    body, note_items = [], []
    for l in raw:
        y0n, y1n = l["y0"] / H, l["y1"] / H
        # 全书级书眉：同一行文字在 ≥3 页顶部重复出现（去页码后相同），必然是书眉。
        # 单页启发式（短行/小字号）会漏掉与正文同字号的长书眉，书眉还会和页首正文
        # 粘成一句（「第九章…形成481诉人犹复提起上诉…」）。
        if furniture and y1n <= 0.13 and _running_norm(l["text"]) in furniture:
            continue
        # 页眉带/页脚带/孤立页码；另加顶带补充判：有的影印 PDF（z-lib 裁切本）书眉坐在
        # y≈8%~10.5%（高于 0.062 主带），特征是短行（「10不当得利」「笫一章绪论11」）
        # 或小字号或孤立页码——正文首行 y0≥0.13 且字号=正文，不受影响
        top_furniture = y1n <= 0.11 and (
            bool(PNUM_RE.match(l["text"])) or l["size"] < body_size * 0.88
            or (len(l["text"]) <= 12 and l["size"] < body_size * 1.15))
        if y1n <= 0.062 or y0n >= 0.958 or top_furniture or (
                PNUM_RE.match(l["text"]) and (y1n <= 0.10 or y0n >= 0.90)):
            continue
        if y0n > 0.72 and l["size"] < body_size * 0.88:
            note_items.append((l["y0"], l["text"]))
            continue
        # 页眉页码与正文首行落在同一视觉行时会被拼成「7束；但…」：剥掉开头
        # 1~3 位页码（后接非年/月/日等量词单位的汉字）再进正文
        if y0n < 0.20 and len(l["text"]) > 20:
            mm = re.match(r"^\d{1,3}(?![\d年月日世条项款章节页人次个种分秒元万亿])([\u4e00-\u9fff].*)$",
                          l["text"])
            if mm:
                l["text"] = mm.group(1)
        body.append(l)

    # 脚注区分隔：正文与页脚注之间常有一大段空白（脚注首行字号可能接近正文，
    # 单靠字号会漏判，如「0关于罗马法上不当得利…」）。正文末行到某行的垂直
    # 间距 > 2.2 字号且该行落在页面下 1/3，则从这里整段划入脚注。
    if body:
        split = None
        for i in range(1, len(body)):
            if (body[i]["y0"] / H > 0.68 and (body[i]["y0"] - body[i - 1]["y1"]) > 2.2 * body_size):
                split = i
                break
        if split is not None:
            note_items.extend((x["y0"], x["text"]) for x in body[split:])
            body = body[:split]
    notes = [t for _, t in sorted(note_items, key=lambda x: x[0])]

    # 图示/表格垃圾区：OCR 层常把图里的坐标文字按文本框乱序拼出来（如
    # 「/ 厂｀昙岱气典f》」「中国三气民法：二（2（立了」）。先把这些高置信
    # 垃圾区整体摘出、换成图占位行，rect 交给 figure-desc 裁图补语义，
    # 不让乱字混进正文。正文段落只由剩余行重组。
    bands = _detect_fig_bands(body, page)
    W, H = page.rect.width, page.rect.height

    def _bodylike(t: str) -> bool:
        """像正文的行：有句读且够长，或以冒号收尾引出图表。"""
        return (len(t) > 20 and bool(re.search(r"[，。；]", t))) or (len(t) > 8 and t.endswith("："))

    # 图区上下扩展：把紧邻的短图注/图内文字一并纳入（如「实、控制契约」「迭标 的金」），
    # 遇到正文行就停。扩展后重算 rect，figure-desc 裁到的才是完整图。
    for bd in bands:
        start, end = bd["start"], bd["end"]
        while start > 0:
            r = body[start - 1]
            if _bodylike(r["text"]):
                break
            if len(r["text"]) <= 40 or r["size"] > body_size * 1.5:
                start -= 1
            else:
                break
        while end + 1 < len(body):
            r = body[end + 1]
            if _bodylike(r["text"]):
                break
            if len(r["text"]) <= 40 or r["size"] > body_size * 1.5:
                end += 1
            else:
                break
        bd["start"], bd["end"] = start, end
        rows_bd = body[start:end + 1]
        x0 = min(r["x0"] for r in rows_bd)
        y0 = min(r["y0"] for r in rows_bd)
        x1 = max(r["x1"] for r in rows_bd)
        y1 = max(r["y1"] for r in rows_bd)
        bd["rect"] = [round(max(0.02, x0 / W - 0.02), 4), round(max(0.02, y0 / H - 0.02), 4),
                      round(min(0.98, x1 / W + 0.02), 4), round(min(0.98, y1 / H + 0.02), 4)]

    # 纯垃圾图区（无正文句）就地摘掉换图占位；混着正文的图区不动正文，
    # 整页标 needs_vision 交给 reocr（本地硬拆会丢句子）。
    pure_bands: list[dict] = []
    band_idx: set[int] = set()
    for bd in bands:
        mixed = any(_bodylike(r["text"]) for r in body[bd["start"]:bd["end"] + 1])
        bd["mixed"] = mixed
        if mixed:
            continue
        pure_bands.append(bd)
        band_idx.update(range(bd["start"], bd["end"] + 1))
    needs_vision = any(bd["mixed"] for bd in bands)
    # 索引/年表页：双栏 + 密集页码，OCR 层会按文本框把两栏交错拼成一大段
    # （「… 122, 1(J7,错误374 …」）。视觉模型能按栏还原，逐条对齐。
    # 判据用「短行 + ≥3 个独立数字」而不是数字字符数：正文句子里的「第10页」「第1201条」
    # 也含 3 个以上数字字符，却不会交错；索引行是短词条 + 一串页码，多个独立数字才是真信号。
    if len(body) >= 10:
        num_rows = sum(1 for r in body
                       if len(r["text"]) <= 45 and len(re.findall(r"\d+", r["text"])) >= 3)
        needs_vision = needs_vision or num_rows >= len(body) * 0.5
    # 整页繁体字形：扫描件 OCR 层在部分页返回繁体（「枚利」「惡則」「美系」），
    # 简体正文里出现 ≥3 个繁体专用字几乎必然是识别层坏了，整页交视觉重识别。
    trad = sum(1 for ch in "".join(r["text"] for r in body) if ch in _TRAD_ONLY)
    if trad >= 3:
        needs_vision = True
    # 图区矩形内夹着的短残片（如「英”\」）一并清掉；满行正文不动
    for i, l in enumerate(body):
        if i in band_idx or ((l["x1"] - l["x0"]) > 0.5 * W and len(l["text"]) > 20):
            continue
        cx, cy = (l["x0"] + l["x1"]) / 2 / W, (l["y0"] + l["y1"]) / 2 / H
        if any(bd["rect"][0] <= cx <= bd["rect"][2] and bd["rect"][1] <= cy <= bd["rect"][3]
               for bd in pure_bands):
            band_idx.add(i)
    body_clean = [l for i, l in enumerate(body) if i not in band_idx]

    # 段落重组：标题独立成行；正文行首缩进超 1.4 字号 = 新段
    left = min((l["x0"] for l in body_clean), default=0.0)
    paras: list[str] = []
    para_y0: list[float] = []  # 每段首行 y0（图占位行按 y 插段间用）
    for l in body_clean:
        is_head = l["size"] >= body_size * 1.15 or CH_RE.match(l["text"]) or SEC_RE.match(l["text"])
        new_para = is_head or (l["x0"] - left > body_size * 1.4) or not paras
        if new_para or is_head:
            paras.append(l["text"])
            para_y0.append(l["y0"])
        else:
            paras[-1] += l["text"]

    # OCR 层系统性误字/引号纠错（只对 pull 路线做；视觉路线输出本是干净的）
    paras = [_fix_quotes(_fix_ocr_text(p)) for p in paras]
    notes = [_fix_quotes(_fix_ocr_text(n)) for n in notes]

    # 嵌入真图：按实际图块坐标登记（比目测准），整页水印图跳过。
    # 图示垃圾区按检测到的 band 登记（rect 为比例坐标）。
    # title/desc 先写占位（pull 不看图内容），figure-desc 命令补语义、pack 重写正文占位行
    figs = [{"page": page.number + 1, "rect": rr, "kind": "photo", "from_pull": True,
             "title": f"第{page.number + 1}页插图", "desc": PULL_FIG_DESC}
            for rr in _embedded_fig_rects(page)]
    for bd in pure_bands:
        figs.append({"page": page.number + 1, "rect": bd["rect"], "kind": bd["kind"],
                     "from_pull": True,
                     "title": bd["title"] or f"第{page.number + 1}页插图",
                     "desc": PULL_FIG_DESC})
    chars = sum(len(re.sub(r"\s", "", p)) for p in paras)

    # 图占位行按 y 插段间：图的 y0 落在第一个首行 y0 大于它的段之前（两段之间），
    # 页顶图插最前、页底图插最后——正文里的图位保真，App 端 FIGURE 段位置与此一致。
    # 占位行不计 chars（上面已算完），thin 图版页判定不受影响
    for k, f in enumerate(sorted(figs, key=lambda x: x["rect"][1]), 1):
        fy = f["rect"][1] * H
        at = bisect.bisect_right(para_y0, fy)
        paras.insert(at, f"〔图：第{f['page']}页插图{k}〕")
        para_y0.insert(at, fy)

    # 跨页缝合依据：本页首/末行的缩进与标题属性（中文书段首缩进约两字、续行顶格）
    def _is_head(l: dict) -> bool:
        return bool(l["size"] >= body_size * 1.15 or CH_RE.match(l["text"]) or SEC_RE.match(l["text"]))

    first, last = (body_clean[0], body_clean[-1]) if body_clean else (None, None)
    return {"paras": paras, "notes": notes, "figs": figs, "chars": chars, "fig_area": sum(
        (f["rect"][2] - f["rect"][0]) * (f["rect"][3] - f["rect"][1]) for f in figs),
        "needs_vision": needs_vision,
        "first_x0": first["x0"] if first else None,
        "first_size": first["size"] if first else None,
        "first_is_head": _is_head(first) if first else True,
        "last_is_head": _is_head(last) if last else True,
        "page_left": left if body_clean else None}


def cmd_pull(a: argparse.Namespace) -> None:
    """对自带文字层的书：直接拔取文字层产出批文件，免视觉识别（分钟级、零幻觉）。"""
    job = resolve_job(a.job)
    m = load_manifest(job)
    if not m["batches"]:
        die("还没有批次，先跑 prep")
    m["route"] = "pull"  # reocr --exact 据此判断能否沿用本地文字层
    fitz = load_fit()
    doc = fitz.open(m["src_pdf"])

    # 全书一次拔取：跨页缝合需要全书视野（批边界上的段落照样缝）
    npages = len(m["pages"])
    all_lines = {n: _page_lines(doc[n - 1]) for n in range(1, npages + 1)}
    # 全书正文主字号：跨页统计——页内小样本会被脚注/年表小字号带偏（实测 43 页误判）
    tally: dict[float, int] = {}
    for n in range(1, npages + 1):
        ph = doc[n - 1].rect.height
        for l in all_lines[n]:
            if 0.10 * ph < l["y0"] < 0.85 * ph:
                k = round(l["size"], 1)
                tally[k] = tally.get(k, 0) + len(l["text"])
    gbody = max(tally, key=tally.get) if tally else 12.0

    # 全书书眉：同一行在 ≥3 页顶部重复（去页码后相同）即视为书眉。页眉字号常与正文
    # 相同、又长，单页规则抓不住，还会和页首正文粘成一段——必须在全书视野里判。
    from collections import Counter
    head_tally: Counter = Counter()
    for n in range(1, npages + 1):
        ph = doc[n - 1].rect.height
        heads = {_running_norm(l["text"]) for l in all_lines[n]
                 if l["y1"] / ph <= 0.13}
        head_tally.update(t for t in heads if 4 <= len(t) <= 60 and not t.endswith("。"))
    furniture = {t for t, c in head_tally.items() if c >= 3}
    m["furniture"] = sorted(furniture)
    all_pages = {n: _pull_page(doc[n - 1], fitz, body_size=gbody, lines=all_lines[n],
                               furniture=furniture)
                 for n in range(1, npages + 1)}
    doc.close()

    # 跨页缝合：中文书每段首行缩进约两字、续行顶格——下页首行顶格且非标题、
    # 上页末行非标题时，把下页首段拼回上页末段（修「请求|权基础」式跨页断句）
    gleft = min((d["page_left"] for d in all_pages.values() if d["page_left"] is not None),
                default=0.0)
    stitched = 0
    for n in range(2, npages + 1):
        prev, cur = all_pages[n - 1], all_pages[n]
        if not prev["paras"] or not cur["paras"]:
            continue  # 图版页/空白页不参与
        if prev["last_is_head"] or cur["first_is_head"]:
            continue
        # 图占位行不参与缝合：页顶有图（cur 首段是占位行）或页尾以图收（prev 末段是占位行）
        # 时拼段会把文字粘到图行前后，图文交错关系失真
        if prev["paras"][-1].startswith("〔图：") or cur["paras"][0].startswith("〔图："):
            continue
        # 顶格判据用当页左缘（镜像排版单双页左缘不同，全局 gleft 会把双数页续行误判成段首；
        # page_left 由 _pull_page 按当页正文行算好带出，空页已被上面 continue 拦掉）
        indent = (cur["first_x0"] - (cur["page_left"] or gleft)) / (cur["first_size"] or 1)
        if indent < 0.6:
            tail, head = prev["paras"][-1][-1:], cur["paras"][0][:1]
            sep = " " if tail.isascii() and tail.isalpha() and head.isascii() and head.isalpha() else ""
            prev["paras"][-1] += sep + cur["paras"][0]
            cur["paras"] = cur["paras"][1:]
            stitched += 1

    pulled, thin_total, vision_total = [], 0, 0
    for bid, b in m["batches"].items():
        pages_data = {n: all_pages[n] for n in b["pages"]}
        # 页级路由：无文字但被大图占据的页 = 图版页，正文写占位、记 thin_pages 留给 patch 补描述；
        # 不再整批跳过——一批里混几页图版不再连累其余 9 页走视觉
        thin = sorted(n for n, d in pages_data.items()
                      if d["chars"] == 0 and d["fig_area"] > 0.3)
        vision_pages = sorted(n for n, d in pages_data.items() if d.get("needs_vision"))

        parts, all_notes = [], []
        figs = []
        for n in b["pages"]:
            d = pages_data[n]
            parts.append(f"〔页{n}〕")
            if n in thin:
                parts.append(f"〔图：第{n}页图版〕")
                parts.append("〔图版页〕")
            else:
                # 段间空行：与视觉路线 spec「段落之间空一行」对齐——App 端 splitBlocks 按
                # 空行分块，无空行则一页多段被 softMerge 并成一段（段落结构丢失）
                for j, para in enumerate(d["paras"] or ["〔空白页〕"]):
                    if j:
                        parts.append("")
                    parts.append(para)
            parts.append("")  # 页间空行
            all_notes.extend(d["notes"])
            figs.extend(d["figs"])
        (job / b["body"]).write_text("\n".join(parts).rstrip() + "\n", encoding="utf-8")
        (job / b["notes"]).write_text("\n".join(all_notes) + ("\n" if all_notes else ""), encoding="utf-8")
        (job / b["figures"]).write_text(json.dumps(figs, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

        b["status"] = "assigned"  # 防止 next 重派；由 pull 内部直接质检
        b["thin_pages"] = thin
        b["needs_vision"] = vision_pages
        thin_total += len(thin)
        vision_total += len(vision_pages)
        save_manifest(job, m)
        try:
            # 传共享台账：check 在外层 m 上标 done、重建图台账，循环内不产生状态回退
            cmd_check(argparse.Namespace(job=a.job, batch=bid, force=a.force, min=a.min), m=m)
            pulled.append(bid)
        except SystemExit:
            out(f"[!] {bid} 质检不过，看 check 输出（可 retry 后派视觉子代理/aivision）")
    out(f"[ok] pull 完成：拔取 {len(pulled)}/{len(m['batches'])} 批，跨页缝合 {stitched} 处，图版页 {thin_total} 页，"
        f"图示/表格页需视觉复核 {vision_total} 页"
        + (f"（先 patch {a.job} 补图版描述）" if thin_total else "")
        + (f"（再 reocr {a.job} --pages <qa 报告点名的页> 或按 needs_vision 列表复核）" if vision_total else ""))
    if pulled:
        out("下一步：status 看进度；全 done 即可 pack")


# ---------------------------------------------------------------- patch（图版页视觉补描述）

def cmd_patch(a: argparse.Namespace) -> None:
    """对 pull 记录的 thin_pages（图版页）派视觉补描述：只读这几页图，替换占位段落。"""
    job = resolve_job(a.job)
    m = load_manifest(job)
    if not m["pages"] or len(m.get("rendered") or []) < len(m["pages"]):
        need = sorted({n for b in m["batches"].values() for n in b.get("thin_pages", [])
                       if n not in (m.get("rendered") or [])})
        if need:
            out(f"[!] 有图版页还没渲染页图：{need[:8]}{'…' if len(need) > 8 else ''}")
            out(f"    先跑：python bookocr.py prep {a.job} --pages {need[0]}-{need[-1]}")
            if not m["pages"]:
                return
    targets = {bid: list(b.get("thin_pages") or []) for bid, b in m["batches"].items()
               if b.get("thin_pages")}
    if a.batch:
        bid = normalize_bid(m, a.batch)
        targets = {bid: targets[bid]} if bid in targets else {}
    if not targets:
        out("[ok] 没有待补描述的图版页（thin_pages 为空）")
        return
    for bid, pages in targets.items():
        b = m["batches"][bid]
        unrendered = [n for n in pages if n not in (m.get("rendered") or [])]
        if unrendered:
            out(f"[!] 批 {bid} 图版页页图未渲染：{unrendered}（不发任务卡）")
            out(f"    先跑：python bookocr.py prep {a.job} --pages {unrendered[0]}-{unrendered[-1]}，"
                "渲染完重跑 patch")
            continue
        imgs = "\n".join(f"  {job / m['pages'][n - 1]['image']}" for n in pages)
        out(f"批 {bid} 图版页 {pages}（共 {len(pages)} 页）")
        out(f"转写规范（必读，先 Read 它）：{m['spec']}")
        out(f"图片（按页序）：\n{imgs}")
        out("任务（用 Edit 工具改文件，描述文字绝不贴进对话）：")
        out(f"  正文  {job / b['body']}——每页把「〔页N〕\\n〔图：第N页图版〕\\n〔图版页〕」三行中的「〔图版页〕」"
            f"替换为你从图里看出的内容描述（一两句，如「影印照片：×××手稿首页」）；〔图：…〕行保留不动")
        out(f"  真图  {job / b['figures']}——Read 现有 JSON，为每页补一条本页图记录（rect 按整页画面估比例，"
            "title 用图版说明文字，desc 一两句），保留已有记录，Write 回去")
        out(f"写完自查后只回报一行：{bid} patch 完成 页X字 图Y条")
        b["thin_pages"] = []  # 任务卡已发出，避免重复派
    save_manifest(job, m)
    out(f"派完后对每批跑：python bookocr.py check {a.job} <批号> --force（图版页字数天然少）")


def bid_of(m: dict, batch: dict) -> str:
    return next(k for k, v in m["batches"].items() if v is batch)


# ---------------------------------------------------------------- check

def read_text(p: Path) -> str:
    return p.read_text(encoding="utf-8-sig") if p.exists() else ""


def cmd_check(a: argparse.Namespace, m: dict | None = None) -> None:
    # m 可选共享台账：调用方（pull 循环）传入时直接在其上改状态并落盘，
    # 避免内部另开副本、下一轮被调用方的旧台账把 done 覆盖回 assigned
    job = resolve_job(a.job)
    if m is None:
        m = load_manifest(job)
    bid = normalize_bid(m, a.batch)
    b = m["batches"][bid]
    if b["status"] == "pending":
        die(f"{bid} 还没派出（先 next）")
    body, notes = job / b["body"], job / b["notes"]
    if not body.exists():
        b["status"], b["fail_reason"] = "failed", f"正文文件缺失：{b['body']}"
        save_manifest(job, m)
        die(b["fail_reason"])

    text = read_text(body)
    # 1) 页标记对账：漏页/多页/乱序一律不过（永不豁免）
    got = [int(mm.group(1)) for ln in text.splitlines() if (mm := PAGE_MARK.match(ln))]
    want = b["pages"]
    if len(got) != len(set(got)):
        b["status"], b["fail_reason"] = "failed", "页标记重复"
        save_manifest(job, m)
        die(f"{bid} 质检不过：〔页N〕行有重复（同一页号出现两次），让子代理重写")
    missing = [p for p in want if p not in got]
    extra = [p for p in got if p not in want]
    if missing or extra:
        reason = (f"缺页标记{missing}" if missing else "") + (f" 多余页标记{extra}" if extra else "")
        b["status"], b["fail_reason"] = "failed", reason.strip()
        save_manifest(job, m)
        die(f"{bid} 质检不过：{reason.strip()}（让子代理补齐〔页N〕行重写）")
    ordered = all(g1 <= g2 for g1, g2 in zip(got, got[1:]))
    if not ordered:
        b["status"], b["fail_reason"] = "failed", "页标记乱序"
        save_manifest(job, m)
        die(f"{bid} 质检不过：页标记乱序")

    # 2) 乱码硬判
    if "\ufffd" in text:
        b["status"], b["fail_reason"] = "failed", "出现替换符乱码（U+FFFD）"
        save_manifest(job, m)
        die(f"{bid} 质检不过：正文含 U+FFFD 乱码")

    # 3) 字数与中文占比（--force 豁免；图表页/空白页字数天然少，重转无效时用 --force 放行）
    body_only = PAGE_MARK.sub("", text)
    chars = len(re.sub(r"\s", "", body_only))
    # 批C：图版页自动豁免——该批 figures.json 里归一化 rect 面积 >0.3 的页（大图占页），
    # 字数天然不足，不再要求人工判断后 --force（pull 已算出 fig_area，check 复用台账复算）
    fig_heavy_pages: set[int] = set()
    fp_early = job / b["figures"]
    if fp_early.exists() and fp_early.read_text(encoding="utf-8-sig").strip():
        try:
            for f in json.loads(fp_early.read_text(encoding="utf-8-sig")):
                r = f.get("rect")
                if (isinstance(r, list) and len(r) == 4 and f.get("page") in want
                        and (r[2] - r[0]) * (r[3] - r[1]) > 0.3):
                    fig_heavy_pages.add(int(f["page"]))
        except (json.JSONDecodeError, TypeError, ValueError):
            fig_heavy_pages = set()
    eff_want = [p for p in want if p not in fig_heavy_pages]
    if eff_want and len(eff_want) < len(want):
        # 剔除图版页后重算页均字数（按页标记切分；此路仅在真有大图页时走）
        # split 带捕获组时页号落在奇数位（偶数位是标记前的正文），从 1 起跳，取 0 会把空串当页号
        parts = PAGE_MARK.split(text)
        page_chars = {int(parts[i]): len(re.sub(r"\s", "", parts[i + 1])) for i in range(1, len(parts) - 1, 2)}
        chars_eff = sum(page_chars.get(p, 0) for p in eff_want)
        per_page = chars_eff / len(eff_want)
        out(f"[i] 图版页自动豁免 {len(want) - len(eff_want)} 页（{sorted(fig_heavy_pages)[:8]}），按剩余 {len(eff_want)} 页计页均")
    else:
        per_page = chars / len(want)
    # 可读字符占比：中文 + 拉丁字母 + 数字。书末参考书目（德文）、索引（密集页码）
    # 本来就该低中文，按纯中文占比判会把正常页误判成串台；真乱码页连可读字符都很少。
    readable = len(re.findall(r"[\u4e00-\u9fffA-Za-z0-9]", body_only))
    ratio = readable / max(1, len(re.sub(r"\s", "", body_only)))
    if not a.force and per_page < a.min:
        b["status"], b["fail_reason"] = "failed", f"页均字数 {per_page:.0f} < {a.min}"
        save_manifest(job, m)
        die(f"{bid} 质检不过：页均 {per_page:.0f} 字（阈值 {a.min}）。确认是图表页/空白页就 retry 后带 --force 放行，否则重转")
    if not a.force and ratio < 0.5:
        b["status"], b["fail_reason"] = "failed", f"可读字符占比 {ratio:.0%} 异常"
        save_manifest(job, m)
        die(f"{bid} 质检不过：可读字符占比 {ratio:.0%}，疑似乱码/串台")

    # 4) 真图台账（figures.json 缺失按无图处理）
    figs = []
    fp = job / b["figures"]
    if fp.exists() and fp.read_text(encoding="utf-8-sig").strip():
        try:
            raw = json.loads(fp.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError as e:
            b["status"], b["fail_reason"] = "failed", f"figures.json 解析失败：{e}"
            save_manifest(job, m)
            die(f"{bid} 质检不过：figures.json 不是合法 JSON")
        for f in raw:
            r = f.get("rect")
            ok = (
                isinstance(r, list) and len(r) == 4
                and all(isinstance(x, (int, float)) for x in r)
                and 0 <= r[0] < r[2] <= 1 and 0 <= r[1] < r[3] <= 1
            )
            if ok and f.get("page") in want:
                figs.append({"batch": bid, "page": int(f["page"]), "rect": [round(float(x), 4) for x in r],
                             "kind": str(f.get("kind", "figure")), "title": str(f.get("title", "")).strip(),
                             "desc": str(f.get("desc", "")).strip(),
                             "from_pull": bool(f.get("from_pull"))})
    # rect 交叉校正：视觉路线的 rect 是模型按画面目测的，pack 按 rect 裁图会偏——
    # 与原 PDF 嵌入图块坐标（程序级精确）比对，IoU 最大且 ≥0.3 即用程序坐标替换。
    # pull 路线 rect 本就来自 PDF 坐标（IoU=1.0），替换等于不变，无副作用
    if figs:
        try:
            fitz = load_fit()
            doc = fitz.open(m["src_pdf"])
            corrected = 0
            for f in figs:
                best, best_iou = None, 0.0
                for pr in _embedded_fig_rects(doc[f["page"] - 1]):
                    iou = _rect_iou(f["rect"], pr)
                    if iou > best_iou:
                        best, best_iou = pr, iou
                if best is not None and 0.3 <= best_iou < 1.0:
                    f["rect"] = list(best)
                    f["rect_src"] = "pdf"
                    corrected += 1
            doc.close()
            if corrected:
                out(f"  [i] rect 交叉校正：{corrected}/{len(figs)} 张目测坐标已换成 PDF 图块精确坐标")
        except Exception as e:
            out(f"  [警告] rect 交叉校正跳过（{e}）")

    kept = [x for x in m["figures"] if x["batch"] != bid]
    # id 取全局最大号 +1（不按 kept 长度：重跑 pull 时后批旧账仍在列表里，
    # 按长度算基数会让多批领到同一 id，裁图文件名互相覆盖）
    _ids = [int(x["id"][1:]) for x in m["figures"]
            if str(x.get("id", "")).startswith("f") and x["id"][1:].isdigit()]
    base = max(_ids, default=0)
    for i, f in enumerate(figs):
        f["id"] = f"f{base + i + 1:03d}"
    m["figures"] = kept + figs

    n_notes = len([ln for ln in read_text(notes).splitlines() if ln.strip()]) if notes.exists() else 0
    b["status"], b["fail_reason"] = "done", None
    b["stats"] = {"chars": chars, "notes": n_notes, "figures": len(figs)}
    save_manifest(job, m)
    out(f"[ok] {bid} 质检通过：正文 {chars} 字（页均 {per_page:.0f}） 脚注 {n_notes} 条 图 {len(figs)} 张")


def normalize_bid(m: dict, s: str) -> str:
    s = s.strip().lower().lstrip("b")
    if not s.isdigit() or f"b{int(s):03d}" not in m["batches"]:
        die(f"批号不认识：{s}（跑 status 看有哪些批）")
    return f"b{int(s):03d}"


# ---------------------------------------------------------------- retry / status

def cmd_retry(a: argparse.Namespace) -> None:
    job = resolve_job(a.job)
    m = load_manifest(job)
    if a.all_failed:
        targets = [k for k, v in m["batches"].items() if v["status"] in ("failed", "assigned")]
    else:
        targets = [normalize_bid(m, a.batch)]
    n = 0
    for bid in targets:
        b = m["batches"][bid]
        if b["status"] in ("failed", "assigned"):
            b["status"], b["fail_reason"], b["stats"] = "pending", None, None
            n += 1
    save_manifest(job, m)
    out(f"[ok] 重置 {n} 批 → pending（next 重新领）")


def cmd_qa(a: argparse.Namespace) -> None:
    """逐页质量体检：揪出 OCR 文字层烂页（图示乱码/系统性误字/引号乱序），
    产出 qa_report.json + qa_report.md。只读正文与 PDF，不改批文件。"""
    job = resolve_job(a.job)
    m = load_manifest(job)
    book, _ = collect_book(job, m)
    if not book:
        die("还没有 done 批，先识别或 pull")
    fitz = load_fit()
    doc = fitz.open(m["src_pdf"])
    sel = _parse_pages_arg(a.pages, len(m["pages"])) if a.pages else sorted(book)
    recs: list[dict] = []
    for p in sel:
        if p not in book:
            continue
        paras = book[p]
        text = "\n".join(paras)
        page = doc[p - 1]
        # 已视觉重识别的页：正文来自看图转写，已有图占位/图台账，不再拿原 PDF
        # 文字层的乱码区去扣分（否则修好的页会一直显示「图示乱码区」）。
        reocred = (job / "reocr" / f"p{p:04d}.json").exists()
        raw = _page_lines(page)
        bands = [] if reocred else _detect_fig_bands(raw, page)
        fixed = _fix_ocr_text(text)
        bad = sum(1 for x, y in zip(text, fixed) if x != y)
        bad += sum(text.count(c) for c in "＠｛｝｀＼｜〜�")
        trad = sum(1 for c in text if c in _TRAD_ONLY)
        quote_bad = sum(1 for x in paras if len(x) >= 8 and x.count("“") != x.count("”"))
        short = sum(1 for x in paras if 0 < len(x) <= 3 and not x.startswith("〔")
                    and not CH_RE.match(x) and not SEC_RE.match(x) and not SUBHEAD_RE.match(x))
        noterm = sum(1 for x in paras if len(x) >= 15 and not x.startswith("〔")
                     and not CH_RE.match(x) and not SEC_RE.match(x) and not SUBHEAD_RE.match(x)
                     and x[-1] not in "。！？；…”』」）】》?!;.：:")
        fig_ph = sum(1 for x in paras if x.startswith("〔图"))
        open_bands = max(0, len(bands) - fig_ph)
        score = 3 * open_bands + 2 * bad + 2 * trad + quote_bad + 0.5 * short + 0.5 * noterm
        recs.append({"page": p, "chars": len(re.sub(r"\s", "", text)), "score": round(score, 1),
                     "bands": len(bands), "open_bands": open_bands, "fig_ph": fig_ph,
                     "bad": bad, "trad": trad, "quote": quote_bad, "short": short,
                     "noterm": noterm, "sample": (paras[0] if paras else "")[:60]})
    doc.close()

    recs.sort(key=lambda r: (-r["score"], r["page"]))
    bad_pages = [r["page"] for r in recs if r["score"] >= 3]
    band_pages = [r["page"] for r in recs if r["open_bands"]]
    out(f"[ok] QA：检查 {len(recs)} 页；可疑 {len(bad_pages)} 页"
        f"（图示乱码区 {len(band_pages)} 页，坏字/引号/断段见下）")
    out("  top 页：")
    for r in recs[:min(a.top, len(recs))]:
        out(f"   p{r['page']:>4} score={r['score']:<5} 图示区{r['open_bands']} 坏字{r['bad']}"
            f" 繁体{r['trad']} 引号{r['quote']} 短行{r['short']} 无句读{r['noterm']} | {r['sample']}")
    rp_json = job / "qa_report.json"
    rp_json.write_text(json.dumps({"name": m["name"], "pages": recs}, ensure_ascii=False, indent=1),
                       encoding="utf-8")
    md = [f"# 质量体检：{m['name']}", "",
          f"检查 {len(recs)} 页，可疑 {len(bad_pages)} 页。分数越高越可疑；"
          f"图示区=OCR 把图里的字按框乱拼的区域，坏字=千→于等系统性误码，繁体=简体书里混入的繁体字形。", "",
          "| 页 | 分数 | 图示区 | 坏字 | 繁体 | 引号 | 短行 | 无句读 | 样本 |",
          "|---|---|---|---|---|---|---|---|---|"]
    for r in recs[:200]:
        md.append(f"| {r['page']} | {r['score']} | {r['open_bands']} | {r['bad']} | {r['trad']} | "
                  f"{r['quote']} | {r['short']} | {r['noterm']} | {r['sample'].replace('|', '｜')} |")
    rp_md = job / "qa_report.md"
    rp_md.write_text("\n".join(md) + "\n", encoding="utf-8")
    out(f"[ok] 报告 → {rp_md}")
    if bad_pages:
        pages = sorted(r["page"] for r in recs if r["score"] >= 6)
        exact = " --exact" if m.get("route") == "pull" else ""
        out(f"    建议：python bookocr.py reocr {job.name}{exact} --pages "
            f"{','.join(str(x) for x in pages[:80])}"
            + (f"（共 {len(pages)} 页，超出部分见 qa_report.json）" if len(pages) > 80 else ""))


def cmd_status(a: argparse.Namespace) -> None:
    job = resolve_job(a.job)
    m = load_manifest(job)
    if not m["batches"]:
        out(f"「{m['name']}」 {m['npages']} 页，还没 prep")
        return
    cnt = {"pending": 0, "assigned": 0, "done": 0, "failed": 0}
    chars = 0
    for b in m["batches"].values():
        cnt[b["status"]] += 1
        if b["stats"]:
            chars += b["stats"]["chars"]
    out(f"「{m['name']}」 {m['npages']} 页 / {len(m['batches'])} 批 | 完成 {cnt['done']} 派出 {cnt['assigned']} 待领 {cnt['pending']} 失败 {cnt['failed']} | 已录 {chars} 字 图 {len(m['figures'])} 张")
    for bid, b in m["batches"].items():
        s = b["stats"]
        tail = f" {s['chars']}字/{s['figures']}图" if s else (f" ✗ {b['fail_reason']}" if b["fail_reason"] else "")
        mark = {"done": "✓", "assigned": "…", "pending": "·", "failed": "✗"}[b["status"]]
        out(f"  {mark} {bid} p{b['pages'][0]}–{b['pages'][-1]}{tail}")
    ch = m.get("chapters")
    if ch:
        extra = f"切章 {len(ch)} 章"
        if m.get("chapter_review_pending"):
            extra += f"（{len(m.get('chapter_warnings') or [])} 疑点待裁决）"
        extra += " | 已打包" if m.get("packed") else " | 未打包"
        out(f"  ◆ {extra}")


# ---------------------------------------------------------------- chapters（全书切章 + 目录对账）

# 章号族（1 级正文章）：第X章/编/部/篇/卷/回；「第X版序」是前置标题变体族
CH_NUM_RE = re.compile(r"^第\s*([0-9〇零一二三四五六七八九十百千]+)\s*[章编部篇卷回次]")
RE_EDITION_PRE = re.compile(r"^第\s*[0-9〇零一二三四五六七八九十]+\s*版序")
CH_WORD_FRONT = ("前言", "序言", "自序", "再版序", "初版序", "重排版序", "增订版序", "修订版序",
                 "总序", "译序", "代序", "导读", "引言", "导论", "导言", "写在前面", "绪论",
                 "开场白", "凡例", "作者简介", "出版说明", "编译说明")
CH_WORD_BACK = ("结语", "结论", "结束语", "后记", "再版后记", "译后记", "附录", "参考文献",
                "参考书目", "致谢", "谢辞", "索引", "大事记", "年表")
TOC_TITLE_RE = re.compile(r"^(目\s*录|目\s*次|contents)\s*$", re.I)
# 目录条目行：标题 + 可选点线 + 行尾页码（行尾带数字的行一律视为目录形态，不当标题）
TOC_ENTRY_RE = re.compile(r"^(.{2,60}?)[\s.·•…\-—]*?(\d{1,4})\s*$")
SEC_NUM_RE = re.compile(r"^第\s*[0-9〇零一二三四五六七八九十百千]+\s*[节讲]")
_ZH_DIGITS = {"零": 0, "〇": 0, "一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6,
              "七": 7, "八": 8, "九": 9, "两": 2}


def _zh_to_int(s: str):
    """中文/阿拉伯章号 → int（十/百位组合），认不出返回 None。"""
    s = s.strip()
    if s.isdigit():
        return int(s)
    total = num = 0
    for ch in s:
        if ch in _ZH_DIGITS:
            num = _ZH_DIGITS[ch]
        elif ch == "十":
            total += (num or 1) * 10
            num = 0
        elif ch == "百":
            total += (num or 1) * 100
            num = 0
        else:
            return None
    return total + num


def _title_kind(s: str):
    """一行文本 → None（不是标题）或 ('num',序号)/('front',)/('back',)/('sec',)。
    护栏与 App 端 BookParser 同族：全行锚定、≤40 字、无逗号分号、不以句读结尾、
    行尾带数字（目录形态）不认。"""
    if not s or len(s) > 40 or TOC_ENTRY_RE.match(s):
        return None
    # 序号锚定的行（第X章/次/编…）优先于句读护栏：章题可自带问号（「第三次 推论是什么？」），
    # 但带逗号分号的仍按对话行处理，直接不认
    mm = CH_NUM_RE.match(s)
    if mm:
        if not any(c in s for c in "，,；;"):
            return ("num", mm.group(1))
        return None
    if s[-1:] in "。！？；，,;?!" or any(c in s for c in "，,；;"):
        return None
    if RE_EDITION_PRE.match(s):
        return ("front",)
    for w in CH_WORD_FRONT:
        if s == w or (s.startswith(w) and len(s) > len(w) and s[len(w)] in "：:．.、(（"):
            return ("front",)
    if s == "序" or s.startswith("序：") or s.startswith("序:"):
        return ("front",)
    for w in CH_WORD_BACK:
        if s == w or s.startswith(w):
            return ("back",)
    if s == "跋" or s.startswith("跋：") or s.startswith("跋:"):
        return ("back",)
    if SEC_NUM_RE.match(s):
        return ("sec",)
    return None


def _detect_toc(book: dict[int, list[str]]) -> dict:
    """识别目录区：{pages: [页号…], entries: [(条目标题, 书内页码)…]}；无目录返回空。
    目录页码是印刷页码（≠PDF 页号），对账时用匹配对偏移换算。"""
    start = None
    for p in sorted(book):
        if any(TOC_TITLE_RE.match(ln) for ln in book[p]):
            start = p
            break
    if start is None:
        return {"pages": [], "entries": []}
    pages, entries = [], []
    for p in sorted(book):
        if p < start:
            continue
        hits = [ln for ln in book[p] if TOC_ENTRY_RE.match(ln) and not TOC_TITLE_RE.match(ln)]
        # 目录标题页强制划入；其后条目占比达标的连续页划入，遇到不达标页即止
        if p == start or (pages and len(hits) >= 2 and len(hits) >= len(book[p]) * 0.4):
            pages.append(p)
            for ln in hits:
                mm = TOC_ENTRY_RE.match(ln)
                entries.append((mm.group(1).strip(), int(mm.group(2))))
        elif pages:
            break
    return {"pages": pages, "entries": entries}


def collect_book(job: Path, m: dict) -> tuple[dict[int, list[str]], list[str]]:
    """合并全部 done 批 → ({页号: [段落…]}, 脚注列表)。pack 与 chapters 共用。"""
    book: dict[int, list[str]] = {}
    notes: list[str] = []
    for bid in sorted(m["batches"]):
        b = m["batches"][bid]
        if b["status"] != "done":
            continue
        book.update(split_pages(read_text(job / b["body"])))
        for ln in read_text(job / b["notes"]).splitlines():
            if ln.strip():
                notes.append(ln.strip())
    return book, notes


def _scan_chapters(book: dict[int, list[str]], rulings: dict) -> tuple[list[dict], dict]:
    """扫描全书切章。返回 (chapters, toc)；chapters 元素 {t,p,lv,r,a}：
    t=标题 p=起始页 lv=1章/2节 r=FRONT前置/TOC目录/BODY正文/BACK后置 a=标题前8字锚（App 端定位章界用）。"""
    toc = _detect_toc(book)
    toc_pages = set(toc["pages"])

    cands, seen = [], set()
    for p in sorted(book):
        if p in toc_pages:
            continue
        for ln in book[p]:
            k = _title_kind(ln)
            if k and (p, ln) not in seen:
                seen.add((p, ln))
                cands.append({"p": p, "t": ln, "k": k})
    # 应用持久裁决：DROP 剔除误切章；ADD 补漏切章（按正文 1 级处理）
    cands = [c for c in cands if rulings.get(f"p{c['p']}:{c['t'][:12]}") != "DROP"]
    have = {f"p{c['p']}:{c['t'][:12]}" for c in cands}
    for key, val in rulings.items():
        if isinstance(val, str) and val.startswith("ADD|"):
            page = key.split(":", 1)[0][1:]
            if page.isdigit() and key not in have:
                cands.append({"p": int(page), "t": val[4:], "k": ("num", None)})
    cands.sort(key=lambda c: c["p"])

    first_body_p = min((c["p"] for c in cands if c["k"][0] == "num"), default=None)
    chapters: list[dict] = []
    if toc["pages"]:
        chapters.append({"t": "目录", "p": toc["pages"][0], "lv": 1, "r": "TOC"})
    for c in cands:
        kind = c["k"][0]
        if kind in ("num", "sec"):
            r = "BODY"
        elif kind == "front":
            r = "FRONT" if first_body_p is None or c["p"] < first_body_p else "BACK"
        else:
            r = "BACK"
        chapters.append({"t": c["t"], "p": c["p"], "lv": 2 if kind == "sec" else 1, "r": r})
    chapters.sort(key=lambda c: c["p"])
    if not chapters or chapters[0]["p"] > 1:
        chapters.insert(0, {"t": "开篇", "p": 1, "lv": 1, "r": "FRONT"})
    for c in chapters:
        c["a"] = c["t"][:8]
    return chapters, toc


def _norm_t(s: str) -> str:
    return re.sub(r"[\s·•.。:：、，,;；\-—…（）()「」『』〔〕\[\]]+", "", s)


def _reconcile(book: dict[int, list[str]], chapters: list[dict], toc: dict) -> tuple[list[dict], dict]:
    """三方体检：目录条目对账 + 章号断号 + 薄章检测 → (疑点列表, 统计)。"""
    warns: list[dict] = []
    lv1 = [c for c in chapters if c["lv"] == 1 and c["r"] != "TOC"]
    entries = toc["entries"]

    # 目录条目 ↔ 章：difflib 相似度，每条目取最高分章
    best: dict[int, tuple[int, float]] = {}
    for ei, (et, _) in enumerate(entries):
        ne = _norm_t(et)
        if not ne:
            continue
        for ci, c in enumerate(lv1):
            r = difflib.SequenceMatcher(None, ne, _norm_t(c["t"])).ratio()
            if ei not in best or r > best[ei][1]:
                best[ei] = (ci, r)
    # 印刷页码 → PDF 页号偏移：用命中对的 (章页 - 目录页码) 众数
    offsets = []
    for ei, (ci, r) in best.items():
        if r >= 0.55:
            offsets.append(lv1[ci]["p"] - entries[ei][1])
    off = statistics.mode(offsets) if offsets else None
    matched_n = sum(1 for _, r in best.values() if r >= 0.55)
    for ei, (et, ep) in enumerate(entries):
        ci, r = best.get(ei, (None, 0.0))
        if r >= 0.55:
            continue
        near = f"（最接近：{lv1[ci]['t'][:20]}@p{lv1[ci]['p']} 相似度 {r:.2f}）" if ci is not None else ""
        calc = (ep + off) if off is not None else None
        note = f"目录条目「{et[:30]} …{ep}」没切出对应章{near}"
        if calc:
            note += f"；若确有此章，ADD 页 ≈ {calc}"
        warns.append({"id": f"toc_miss:{ep}:{_norm_t(et)[:10]}", "kind": "toc_miss",
                      "page": calc, "entry": et, "book_page": ep, "note": note})

    # 章号断号（分册重新编号的书序号有重复，自动跳过检测）
    seq = []
    for c in lv1:
        if c["r"] != "BODY":
            continue
        mm = CH_NUM_RE.match(c["t"])
        n = _zh_to_int(mm.group(1)) if mm else None
        if n:
            seq.append(n)
    if len(seq) >= 2 and len(set(seq)) == len(seq):
        for miss in sorted(set(range(min(seq), max(seq) + 1)) - set(seq)):
            warns.append({"id": f"gap:{miss}", "kind": "gap", "page": None,
                          "note": f"章号断号：第{min(seq)}~{max(seq)}章之间缺「第{miss}」——"
                                  f"分册重新编号选 IGNORE，真漏切翻到对应位置找到标题后 ADD"})

    # 薄章（疑似误切；图版页/过渡页字少属正常）
    page_chars = {p: sum(len(re.sub(r"\s", "", ln)) for ln in lines) for p, lines in book.items()}
    for i, c in enumerate(chapters):
        if c["r"] != "BODY" or c["lv"] != 1:
            continue
        nxt_p = chapters[i + 1]["p"] if i + 1 < len(chapters) else 10 ** 9
        chars = sum(v for p, v in page_chars.items() if c["p"] <= p < nxt_p)
        if chars < 50:
            warns.append({"id": f"thin:{c['p']}:{_norm_t(c['t'])[:10]}", "kind": "thin",
                          "page": c["p"], "ch_title": c["t"],
                          "note": f"章「{c['t'][:24]}」(p{c['p']}) 全章不足 50 字——误切选 DROP，图版/过渡页选 IGNORE"})
    return warns, {"entries": len(entries), "matched": matched_n}


def _parse_review(fp: Path) -> tuple[dict, list[str]]:
    """解析裁决文件 → (rulings, ignored_ids)。行格式：id | 动作 [| 页 | 标题]，# 开头为注释。"""
    rulings, ignored = {}, []
    for ln in read_text(fp).splitlines():
        ln = ln.strip()
        if not ln or ln.startswith("#"):
            continue
        parts = [x.strip() for x in ln.split("|")]
        if len(parts) < 2 or not parts[0] or not parts[1]:
            continue
        wid, act = parts[0], parts[1].upper()
        if act == "IGNORE":
            ignored.append(wid)
        elif act == "DROP" and len(parts) >= 4 and parts[2].isdigit() and parts[3]:
            rulings[f"p{parts[2]}:{parts[3][:12]}"] = "DROP"
        elif act == "ADD" and len(parts) >= 4 and parts[2].isdigit() and parts[3]:
            rulings[f"p{parts[2]}:{parts[3][:12]}"] = f"ADD|{parts[3]}"
    return rulings, ignored


def _review_task_lines(m: dict, warns: list[dict], book: dict[int, list[str]]) -> list[str]:
    """疑点裁决任务卡（给纯文本子代理，不用看图；正文摘录仅上下文必要行）。"""
    lines = [f"书名「{m['name']}」切章疑点裁决（共 {len(warns)} 条）", "",
             "逐条判定，把下面每条的「裁决行」补全动作后原样写进 chapters_review.txt（每条一行，四段用 | 分隔）。",
             "动作三选一：ADD=这里确实有个章但程序没切出（填页号+标题）；DROP=程序切错了删掉这章；",
             "IGNORE=维持现状不改（目录错列/分册重编号/图版页字少都属正常）。除该文件外不要写任何文件，",
             "不要把书稿正文粘贴到对话里。", ""]
    for i, w in enumerate(warns, 1):
        lines.append(f"#{i} [{w['kind']}] {w['note']}")
        p = w.get("page")
        if p and p in book:
            lines.append(f"   第 {p} 页开头：{' / '.join(book[p][:3])[:100]}")
        if w["kind"] == "thin":
            lines.append(f"   裁决行：{w['id']} | 动作 | {w.get('page')} | {w.get('ch_title', '')}")
        elif w["kind"] == "toc_miss":
            lines.append(f"   裁决行：{w['id']} | 动作 | {w.get('page') or ''} | {w.get('entry', '')[:20]}")
        else:
            lines.append(f"   裁决行：{w['id']} | 动作 |  | ")
        lines.append("")
    return lines


def cmd_chapters(a: argparse.Namespace) -> None:
    """全书切章 + 目录对账。疑点出裁决任务卡，--apply 吸收子代理裁决后重切。"""
    job = resolve_job(a.job)
    m = load_manifest(job)
    undone = [k for k, v in m["batches"].items() if v["status"] != "done"]
    if undone:
        die(f"还有 {len(undone)} 批没完成（{undone[:5]}…），切章需要全书视野，补完再来")
    # 子代理/aivision 路线的批文件没有跨页缝合（pull 路线在拔取时就缝了），全书转完
    # 在此统一缝一次——哪个会话收尾都会走到 chapters，缝合不落在个人自觉上；
    # .stitched.json 标记保证幂等（多会话重复跑 chapters 不会二次缝合）
    if not (job / ".stitched.json").exists() and (KIT / "stitch.py").exists():
        r = subprocess.run([sys.executable, str(KIT / "stitch.py"), job.name, "--apply"],
                           capture_output=True, text=True, encoding="utf-8", cwd=str(KIT))
        lines = ((r.stdout or "") + (r.stderr or "")).strip().splitlines()
        out(f"[i] 跨页缝合：{lines[-1] if lines else '(stitch.py 无输出)'}")
        m = load_manifest(job)
    book, _ = collect_book(job, m)

    rulings = dict(m.get("chapter_rulings") or {})
    ignored = set(m.get("chapter_ignored") or [])
    if a.apply:
        if not Path(a.apply).exists():
            die(f"裁决文件不存在：{a.apply}")
        new_r, new_i = _parse_review(Path(a.apply))
        if not new_r and not new_i:
            die("裁决文件一条都没解析出来（检查格式：id | 动作 | 页 | 标题）")
        rulings.update(new_r)
        ignored |= set(new_i)
        out(f"[ok] 吸收裁决：{len(new_r)} 条章界改动，{len(new_i)} 条忽略")

    chapters, toc = _scan_chapters(book, rulings)
    warns_all, stats = _reconcile(book, chapters, toc)
    warns = [w for w in warns_all if w["id"] not in ignored]

    m["chapters"] = chapters
    m["chapter_rulings"] = rulings
    m["chapter_ignored"] = sorted(ignored)
    m["chapter_warnings"] = warns
    m["chapter_review_pending"] = bool(warns)
    save_manifest(job, m)

    n = {r: sum(1 for c in chapters if c["r"] == r) for r in ("FRONT", "TOC", "BODY", "BACK")}
    out(f"[ok] 切章完成：{len(chapters)} 章（前置 {n['FRONT']} 目录 {n['TOC']} 正文 {n['BODY']} 后置 {n['BACK']}）")
    for c in chapters[:30]:
        out(f"  p{c['p']:>4}  {'·' if c['lv'] == 2 else ''}{c['t']}")
    if len(chapters) > 30:
        out(f"  …（共 {len(chapters)} 章，略）")
    if toc["entries"]:
        out(f"目录对账：条目 {stats['entries']}，命中 {stats['matched']}"
            + (f"，疑点 {len(warns)} 条" if warns else "，无疑点"))

    if not warns:
        out("[ok] 可直接 pack")
        return
    out(f"[!] 疑点 {len(warns)} 条（不阻塞，但建议裁决后再 pack）：")
    for i, w in enumerate(warns, 1):
        out(f"  #{i} [{w['kind']}] {w['note']}")
    if a.no_review or not (m.get("config") or {}).get("chapter_review", True):
        out("（--no-review 或 config 已关裁决：疑点只记台账，pack 不拦）")
        return
    task = job / "chapters_review_task.txt"
    task.write_text("\n".join(_review_task_lines(m, warns, book)), encoding="utf-8")
    out(f"[i] 裁决任务卡已写：{task}")
    out("    派一个纯文本子代理（不用看图）读任务卡，把每条裁决行补全动作写进：")
    out(f"    {job / 'chapters_review.txt'}")
    out(f"    然后吸收：python bookocr.py chapters {a.job} --apply {job / 'chapters_review.txt'}")


# ---------------------------------------------------------------- pack

def split_pages(text: str) -> dict[int, list[str]]:
    """按〔页N〕把批正文切成 {页号: [段落...]}。"""
    pages: dict[int, list[str]] = {}
    cur, no = [], 0
    for ln in text.splitlines():
        mm = PAGE_MARK.match(ln)
        if mm:
            if no:
                pages[no] = cur
            no, cur = int(mm.group(1)), []
        elif ln.strip():
            cur.append(ln.strip())
    if no:
        pages[no] = cur
    return pages


def cmd_pack(a: argparse.Namespace) -> None:
    job = resolve_job(a.job)
    m = load_manifest(job)
    undone = [k for k, v in m["batches"].items() if v["status"] != "done"]
    if undone and not a.force:
        die(f"还有 {len(undone)} 批没完成（{undone[:5]}…），补完再 pack；确要出半本书加 --force")
    if not m["batches"]:
        die("还没有批次")
    thin_pending = {bid: b.get("thin_pages") or [] for bid, b in m["batches"].items()
                    if b.get("thin_pages")}
    if thin_pending and not a.force:
        pages = sorted(p for v in thin_pending.values() for p in v)
        die(f"有 {len(pages)} 页图版页没补描述（{pages[:8]}{'…' if len(pages) > 8 else ''}）："
            f"先跑 patch {a.job} 派视觉补图版描述，再 pack；确要带「〔图版页〕」占位打包加 --force")
    # pull 文字层检测到图示/表格垃圾区的页：本地拆分有丢句风险，必须视觉复核后才算成品
    vision_pending = {bid: b.get("needs_vision") or [] for bid, b in m["batches"].items()
                      if b.get("needs_vision")}
    if vision_pending and not a.force:
        pages = sorted(p for v in vision_pending.values() for p in v)
        die(f"有 {len(pages)} 页图示/表格区需要视觉复核（{pages[:8]}{'…' if len(pages) > 8 else ''}）："
            f"先跑 reocr {a.job} --needs-vision，再 pack；确要带文字层乱码打包加 --force")
    # 批C：pull 占位图拦截——README 写「有图的书必跑 figure-desc」却靠人记得，漏跑即
    # 静默降级（App 里全是「第N页插图」占位文案）。台账里 desc 仍是占位的图非空即拦
    placeholder_figs = [f for f in m.get("figures") or [] if f.get("desc") == PULL_FIG_DESC]
    if placeholder_figs and not a.force:
        pages = sorted({f["page"] for f in placeholder_figs})
        die(f"有 {len(placeholder_figs)} 张图还没补语义（第 {pages[:8]}{'…' if len(pages) > 8 else ''} 页）："
            f"先跑 figure-desc {a.job}，再 pack；确要带「第N页插图」占位打包加 --force")

    if not m.get("chapters") and not a.no_chapters:
        if a.auto_chapters:
            out("[i] 还没切章，先自动跑一次 chapters …")
            cmd_chapters(argparse.Namespace(job=a.job, apply=None, no_review=False))
            m = load_manifest(job)
            if not m.get("chapters"):
                die("自动切章没产出章表，人工检查：python bookocr.py chapters <书名>")
        else:
            die("还没切章。先跑：python bookocr.py chapters <书名>（或 pack --auto-chapters 一条龙；"
                "确要无章界元数据的旧格式用 --no-chapters）")

    # 页号 → 段落（跨批拼接，页号排序兜底）
    book, notes = collect_book(job, m)

    # 图位一次性确定性分配 + pull 占位行重写：每页按图在页面里的纵向位置排序；正文里
    # 第 k 个〔图：…〕占位行吃掉第 k 张。pull 自动写的「第N页插图k」占位行重写为台账
    # 最新 title/desc（figure-desc 补过语义即真实图题）；子代理/aivision 写的行保留原样；
    # 没写占位行的图补在页尾。txt 与 PDF 用同一份重写结果，保证两边一致。
    figs_by_page: dict[int, list[dict]] = {}
    for page in sorted(book):
        figs_here = sorted(
            [f for f in m["figures"] if f["page"] == page], key=lambda f: f["rect"][1]
        )
        figs_by_page[page] = figs_here
        fi, rewritten = 0, []
        for p in book[page]:
            mm = FIG_MARK.match(p)
            if mm:
                # 归一化冒号/边缘空白笔误为规范形态〔图：题〕desc——kit 质检 FIG_MARK
                # 容 [:：]，App 端按前缀识别图段，这里统一写全角
                p = f"〔图：{mm.group(1).strip()}〕{mm.group(2).strip()}".strip()
                if fi < len(figs_here):
                    f = figs_here[fi]
                    fi += 1
                    if PULL_FIG_MARK.match(p) or f.get("from_pull"):
                        p = f"〔图：{f['title'] or f['kind']}〕{f['desc'] or ''}".strip()
            rewritten.append(p)
        for f in figs_here[fi:]:
            line = f"〔图：{f['title'] or f['kind']}〕{f['desc'] or ''}".strip()
            if line:
                rewritten.append(line)
        book[page] = rewritten

    # ---- txt ----
    # 头部带 KIT 章界元数据块：App 端识别到即走「成品直入」通道（按章表切章，跳过通用正则猜测）
    txt_parts: list[str] = []
    if m.get("chapters") and not a.no_chapters:
        # kit=1 是协议版本位：App 端 KitBookParser.parseMeta 只认 kit==1，扩展只能加可选
        # 字段（optString/optInt 天然宽容），绝不能 bump 这个数字——旧 App 收到 kit:2 的
        # zip 会整包报「不是有效的 KIT 图包」。v 仅展示用，txt/zip 保持同一版本串。
        meta = {"kit": 1, "v": "bookocr-kit 1.2", "title": m["name"], "pages": m["npages"],
                "chapters": [{"t": c["t"], "p": c["p"], "lv": c["lv"], "r": c["r"], "a": c["a"]}
                             for c in m["chapters"]]}
        txt_parts += ["〔KIT-书 开始〕", json.dumps(meta, ensure_ascii=False), "〔KIT-书 结束〕"]
    txt_parts += [m["name"], ""]
    for page in sorted(book):
        paras = list(book[page])
        # 保留〔页N〕页标记行：App 端（BookParser/KitBookParser）剥标记页码落库（阅读页
        # 显示页码、图归属按页），人读时可当页分隔符
        txt_parts.append(f"〔页{page}〕")
        # 段间空行：App 端 splitBlocks 按空行分块（一块=一段），无空行一页多段会被并成一段
        for j, para in enumerate(paras):
            if j:
                txt_parts.append("")
            txt_parts.append(para)
        txt_parts.append("")
    if notes:
        # 注释区协议：每条脚注独立成段（空行分隔）+ 〔脚注〕前缀——App 端
        # （KitBookParser/BookParser 同口径）剥前缀标 FOOTNOTE（次级小字 + 粗读/总结
        # AI 全跳过）。连续无空行会被 App 端 softMerge 粘成一个 BODY 大段、混进 AI 输入。
        # 插图版 PDF 的注释区走独立 notes 参数渲染，不经此处。
        txt_parts.append("注释")
        for i, n in enumerate(notes, 1):
            line = n if re.match(r"^[①-⑳0-9]+", n) else f"{i}. {n}"
            txt_parts += ["", f"〔脚注〕{line}"]
    out_txt = job / "output" / f"{m['name']}.txt"
    out_txt.write_text("\n".join(txt_parts).rstrip() + "\n", encoding="utf-8-sig")

    total_chars = len(re.sub(r"\s", "", "\n".join(txt_parts)))
    out(f"[ok] txt → {out_txt}（{total_chars} 字）"
        + ("（含章界元数据块）" if len(txt_parts) > 2 else ""))

    m["packed"] = True
    save_manifest(job, m)

    # ---- 插图版 PDF ----
    if not a.no_pdf and "pdf" in (m.get("config") or {}).get("output", DEFAULT_CONFIG["output"]):
        pdf_path = pack_pdf(job, m, book, notes, figs_by_page)
        if pdf_path:
            out(f"[ok] 插图版 PDF → {pdf_path}")

    # ---- KIT 图包（zip）：kit.json 章表+图清单 + book.txt + figures 裁真图，App 端一个文件直入 ----
    deliverable: Path | None = None
    if "zip" in (m.get("config") or {}).get("output", DEFAULT_CONFIG["output"]):
        zip_path = pack_zip(job, m, out_txt, figs_by_page)
        if zip_path:
            out(f"[ok] KIT 图包 → {zip_path}")
            deliverable = zip_path
    if deliverable is None:
        deliverable = out_txt

    # ---- 批C：抽检报告（固定种子随机 5 页图文对照）+ adb 直传手机 ----
    spotcheck_report(job, m, book)
    adb_push_to_phone(deliverable)


def spotcheck_report(job: Path, m: dict, book: dict[int, list[str]]) -> None:
    """批C：纯扫描书零参照系（simcheck 只对自带文字层的书可用），错字此前只能靠
    阅读发现。pack 末尾自动产「随机 5 页 图 vs 文」对照报告供 30 秒目检；
    种子=书名——重复 pack 抽到同一组页，前后两轮报告可直接对比。"""
    try:
        import random
        fitz = load_fit()
    except Exception as e:  # fitz 缺失等：抽检是增值产出，不拦打包
        out(f"[警告] 抽检报告跳过：{e}")
        return
    pages = sorted(book)
    if not pages:
        return
    rng = random.Random(m["name"])
    sample = rng.sample(pages, min(5, len(pages)))
    rep_dir = job / "report"
    rep_dir.mkdir(exist_ok=True)
    lines = [
        f"# 抽检报告：{m['name']}",
        "",
        f"固定种子抽样 {len(sample)} 页（重复 pack 抽到同一组页）。对照每页截图与识别文本，",
        "重点目检：① 形近字错字（损害陪/赔偿）② 漏段/串段 ③ 页码缺失。发现批量问题回到对应批重转。",
        "",
    ]
    try:
        doc = fitz.open(m["src_pdf"])
    except Exception as e:
        out(f"[警告] 抽检报告跳过（原 PDF 打不开）：{e}")
        return
    try:
        for n in sample:
            page = doc[n - 1]
            pix = page.get_pixmap(dpi=110)
            img_name = f"spotcheck_p{n:04d}.png"
            pix.save(str(rep_dir / img_name))
            lines += [
                f"## 第 {n} 页",
                "",
                f"![p{n}]({img_name})",
                "",
                "识别文本：",
                "```",
                *(book.get(n) or []),
                "```",
                "",
            ]
    finally:
        doc.close()
    rp = rep_dir / "spotcheck.md"
    rp.write_text("\n".join(lines), encoding="utf-8")
    out(f"[ok] 抽检报告 → {rp}（编辑器打开对照 5 页，30 秒目检）")


def adb_push_to_phone(deliverable: Path) -> None:
    """批C：pack 产物直推已连接的安卓手机 Download 目录——全链路唯一纯手工步骤
    （微信传文件）并入打包。无 adb/无设备/推失败都不拦流程，只提示手动路径。"""
    import shutil
    import subprocess
    adb = shutil.which("adb")
    if not adb:
        out("[i] 本机无 adb，传输走老办法：微信/网盘发送 → 手机文件管理器选它导入")
        return
    try:
        r = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=10)
        devs = [ln.split("\t")[0] for ln in r.stdout.splitlines() if ln.endswith("\tdevice")]
        if not devs:
            out(f"[i] 未检测到手机（adb devices 空）；产物在电脑：{deliverable}")
            return
        # Git Bash 会把 /sdcard 改写成 C:/Program Files/Git/sdcard——双斜杠 //sdcard 规避
        target = "//sdcard/Download/" + deliverable.name
        p = subprocess.run([adb, "-s", devs[0], "push", str(deliverable), target],
                           capture_output=True, text=True, timeout=600)
        if p.returncode == 0:
            out(f"[ok] 已推送到手机 Download/{deliverable.name}（手机上「导入书籍」选它）")
        else:
            out(f"[警告] adb push 失败：{(p.stderr or p.stdout).strip()[:200]}；手动传：{deliverable}")
    except Exception as e:
        out(f"[警告] adb 直传跳过：{e}；手动传：{deliverable}")


def pack_pdf(job: Path, m: dict, book: dict[int, list[str]], notes: list[str],
             figs_by_page: dict[int, list[dict]]):
    try:
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.units import cm
        from reportlab.lib.styles import ParagraphStyle
        from reportlab.lib.enums import TA_CENTER
        from reportlab.platypus import (BaseDocTemplate, Frame, PageTemplate,
                                        Paragraph, Spacer, Image, PageBreak)
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
        from reportlab.lib.utils import ImageReader
    except ImportError:
        out("[警告] 未安装 reportlab（pip install reportlab），跳过插图版 PDF，txt 已产出")
        return None

    # 字体：Windows 自带中文字体按序探测，注册为 zh / zh-b
    reg = False
    for cand, idx in [("msyh.ttc", 0), ("msyh.ttf", 0), ("simhei.ttf", 0), ("simsun.ttc", 0)]:
        p = Path(r"C:\Windows\Fonts") / cand
        if p.exists():
            try:
                pdfmetrics.registerFont(TTFont("zh", str(p), subfontIndex=idx))
                reg = True
                bold = p
                break
            except Exception:
                continue
    if not reg:
        out("[警告] 找不到中文字体（C:\\Windows\\Fonts），跳过插图版 PDF")
        return None
    for cand, name in [("msyhbd.ttc", "zh-b"), ("simhei.ttf", "zh-b")]:
        p = Path(r"C:\Windows\Fonts") / cand
        if p.exists():
            try:
                pdfmetrics.registerFont(TTFont(name, str(p), subfontIndex=0))
                break
            except Exception:
                pass
    if "zh-b" not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont("zh-b", str(bold), subfontIndex=0))

    st_title = ParagraphStyle("t", fontName="zh-b", fontSize=22, leading=30, alignment=TA_CENTER, spaceAfter=18)
    st_h1 = ParagraphStyle("h1", fontName="zh-b", fontSize=15, leading=22, spaceBefore=14, spaceAfter=8)
    st_h2 = ParagraphStyle("h2", fontName="zh-b", fontSize=12.5, leading=18, spaceBefore=10, spaceAfter=6)
    st_body = ParagraphStyle("b", fontName="zh", fontSize=10.5, leading=17, firstLineIndent=21, spaceAfter=4)
    st_cap = ParagraphStyle("c", fontName="zh", fontSize=9, leading=13, alignment=TA_CENTER,
                            textColor="#555555", spaceBefore=2, spaceAfter=8)
    st_note = ParagraphStyle("n", fontName="zh", fontSize=9.5, leading=14, spaceAfter=2)

    fig_dir = job / "figures"
    fig_dir.mkdir(exist_ok=True)
    fitz = load_fit()
    doc_src = fitz.open(m["src_pdf"])

    def fig_png(f: dict) -> "Image | None":  # type: ignore[valid-type]
        try:
            fp = fig_dir / f"{f['id']}_p{f['page']:03d}.png"
            if not fp.exists() or fp.stat().st_size == 0:  # figure-desc/zip 已裁过则复用
                page = doc_src[f["page"] - 1]
                r = page.rect
                l, t, rr, bb = f["rect"]
                clip = fitz.Rect(r.width * l, r.height * t, r.width * rr, r.height * bb)
                if clip.width * clip.height < r.width * r.height * 0.01:
                    return None  # 裁剪区过小，疑似标错，跳过
                pix = page.get_pixmap(dpi=m["dpi"], clip=clip)
                if not m["color"] and pix.colorspace and pix.colorspace.n > 1:
                    pix = fitz.Pixmap(fitz.csGRAY, pix)
                pix.save(str(fp))
            iw, ih = ImageReader(str(fp)).getSize()
            w = min(14 * cm, iw * 72.0 / m["dpi"] * 1.6)
            return Image(str(fp), width=w, height=w * ih / iw)
        except Exception as e:
            out(f"  [警告] 图 {f['id']}（第 {f['page']} 页）裁剪失败：{e}")
            return None

    story: list = [Paragraph(m["name"], st_title), Spacer(1, 6), PageBreak()]
    # 封面独占一页：书名短行不与第一个章标题同页，防 APP 段落合并把
    # 「第X章」并进书名行导致 A 档章正则失配（探针实机实测教训）
    for page in sorted(book):
        figs_here = figs_by_page.get(page, [])
        fi = 0
        for para in book[page]:
            fm = FIG_MARK.match(para)
            if fm and fi < len(figs_here):
                f = figs_here[fi]
                fi += 1
                img = fig_png(f)
                if img:
                    story += [img, Paragraph(f["title"] or fm.group(1), st_cap)]
                else:
                    story.append(Paragraph(para, st_body))
                continue
            story.append(Paragraph(_esc(para), _style_of(para, st_h1, st_h2, st_body)))
        # 记了台账但正文没写占位行的图，补到页尾
        for f in figs_here[fi:]:
            img = fig_png(f)
            if img:
                story += [img, Paragraph(f["title"] or f["desc"], st_cap)]
    doc_src.close()

    if notes:
        story.append(PageBreak())
        story.append(Paragraph("注释", st_h1))
        for i, n in enumerate(notes, 1):
            story.append(Paragraph(f"{i}. {_esc(n)}" if not re.match(r"^[①-⑳0-9]+", n) else _esc(n), st_note))

    out_pdf = job / "output" / f"{m['name']}_插图版.pdf"
    pdf = BaseDocTemplate(str(out_pdf), pagesize=A4,
                          leftMargin=2 * cm, rightMargin=2 * cm, topMargin=2 * cm, bottomMargin=2 * cm)
    pdf.addPageTemplates(PageTemplate("p", [Frame(pdf.leftMargin, pdf.bottomMargin,
                                                  A4[0] - 4 * cm, A4[1] - 4 * cm, id="f")]))
    pdf.build(story)
    return out_pdf


def _esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _png_size(fp: Path) -> tuple[int, int]:
    """读 PNG IHDR 拿宽高（大端，偏移 16）；非 PNG/读不了返回 (0, 0)。"""
    try:
        import struct
        b = fp.read_bytes()[:24]
        if b[:8] != b"\x89PNG\r\n\x1a\n":
            return 0, 0
        w, h = struct.unpack(">II", b[16:24])
        return w, h
    except Exception:
        return 0, 0


def pack_zip(job: Path, m: dict, txt_file: Path, figs_by_page: dict[int, list[dict]]):
    """KIT 图包：一个 zip 让 App 端拿到「章表 + 正文 + 真图」全套（手机导入只选一个文件，
    真图直接进 figures 表、阅读页图文混排）。结构：kit.json（kit:1 元数据 + figures 清单
    带 file/w/h，App 免解码拿尺寸）+ book.txt（与 output txt 同内容）+ figures/*.png。
    裁图与 figure-desc / 插图版 PDF 同名复用（figures/{id}_p{页号}.png）。"""
    import zipfile
    fitz = load_fit()
    doc = fitz.open(m["src_pdf"])
    fig_dir = job / "figures"
    fig_dir.mkdir(exist_ok=True)
    fig_entries = []
    for page in sorted(figs_by_page):
        for f in figs_by_page[page]:
            fp = fig_dir / f"{f['id']}_p{f['page']:03d}.png"
            if not fp.exists() or fp.stat().st_size == 0:
                try:
                    pg = doc[f["page"] - 1]
                    r = pg.rect
                    l, t, rr, bb = f["rect"]
                    clip = fitz.Rect(r.width * l, r.height * t, r.width * rr, r.height * bb)
                    if clip.width * clip.height < r.width * r.height * 0.01:
                        out(f"  [警告] 图 {f['id']}（第 {f['page']} 页）rect 过小疑似标错，图包缺此图")
                        continue
                    pix = pg.get_pixmap(dpi=m["dpi"], clip=clip)
                    if not m["color"] and pix.colorspace and pix.colorspace.n > 1:
                        pix = fitz.Pixmap(fitz.csGRAY, pix)
                    pix.save(str(fp))
                    f["w"], f["h"] = pix.width, pix.height
                except Exception as e:
                    out(f"  [警告] 图 {f['id']}（第 {f['page']} 页）裁剪失败，图包缺此图：{e}")
                    continue
            if not f.get("w"):  # 旧台账无宽高（figure-desc 未跑过）：读 PNG 头补
                w, h = _png_size(fp)
                if w:
                    f["w"], f["h"] = w, h
            entry = {"page": f["page"], "rect": f["rect"], "kind": f.get("kind", "photo"),
                     "title": f.get("title", ""), "desc": f.get("desc", ""),
                     "file": f"figures/{fp.name}"}
            if f.get("w"):
                entry["w"], entry["h"] = f["w"], f["h"]
            fig_entries.append(entry)
    doc.close()
    save_manifest(job, m)  # 宽高回写台账

    # kit=1 协议版本位勿动（见 cmd_pack txt 段注释）；扩展只加可选字段
    meta = {"kit": 1, "v": "bookocr-kit 1.2", "title": m["name"], "pages": m["npages"],
            "chapters": [{"t": c["t"], "p": c["p"], "lv": c["lv"], "r": c["r"], "a": c["a"]}
                         for c in (m.get("chapters") or [])],
            "figures": fig_entries}
    out_zip = job / "output" / f"{m['name']}_Kit图包.zip"
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("kit.json", json.dumps(meta, ensure_ascii=False, indent=1))
        z.write(txt_file, "book.txt")
        for e in fig_entries:
            fp = fig_dir / Path(e["file"]).name
            if fp.exists():
                z.write(fp, e["file"])
    return out_zip


def _style_of(para: str, h1, h2, body):
    if CH_RE.match(para) and len(para) <= 40:
        return h1
    if SEC_RE.match(para) and len(para) <= 40:
        return h2
    return body


# ---------------------------------------------------------------- main

def _cmd_proofread_bridge(a: argparse.Namespace) -> None:
    """proofread 子命令：转发到独立模块 proofread.py（命令行入口统一在 bookocr.py）。"""
    import proofread
    proofread.cmd_proofread(a)


def main() -> None:
    ap = argparse.ArgumentParser(description="扫描版 PDF 识别台（agent 驱动）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("init")
    p.add_argument("name")
    p.add_argument("--src", required=True)
    p.add_argument("--batch", type=int, default=None, help="每批页数（缺省=config.pages_per_batch，默认 10）")
    p.add_argument("--dpi", type=int, default=None, help="渲染 dpi（缺省=config.dpi，默认 200）")
    p.add_argument("--color", action="store_true")
    p.set_defaults(fn=cmd_init)

    p = sub.add_parser("prep")
    p.add_argument("job")
    p.add_argument("--pages", default=None, help="只渲染指定页，如 521-530 或 12,521-530（缺省=全书）")
    p.set_defaults(fn=cmd_prep)

    p = sub.add_parser("patch")
    p.add_argument("job")
    p.add_argument("--batch", default=None, help="只处理指定批（缺省=所有含图版页的批）")
    p.set_defaults(fn=cmd_patch)

    p = sub.add_parser("figure-desc")
    p.add_argument("job")
    p.add_argument("--workers", type=int, default=None, help="并发数（缺省=config.aivision_workers，默认 5）")
    p.add_argument("--dpi", type=int, default=200, help="裁图 dpi（与页图渲染同级即可）")
    p.add_argument("--dry-run", action="store_true", help="只裁图不调 API（先看裁得对不对）")
    p.set_defaults(fn=cmd_figdesc)

    p = sub.add_parser("next")
    p.add_argument("job")
    p.set_defaults(fn=cmd_next)

    p = sub.add_parser("check")
    p.add_argument("job")
    p.add_argument("batch")
    p.add_argument("--force", action="store_true", help="豁免字数/占比判据（页对账永不豁免）")
    p.add_argument("--min", type=int, default=100, help="页均字数阈值")
    p.set_defaults(fn=cmd_check)

    p = sub.add_parser("pull")
    p.add_argument("job")
    p.add_argument("--force", action="store_true")
    p.add_argument("--min", type=int, default=100)
    p.set_defaults(fn=cmd_pull)

    p = sub.add_parser("aivision")
    p.add_argument("job")
    p.add_argument("--batch", default=None, help="只识别指定批（缺省=全部 pending/failed 批）")
    p.add_argument("--workers", type=int, default=None, help="页级并发数（缺省=config.aivision_workers，默认 5）")
    p.add_argument("--force", action="store_true")
    p.add_argument("--min", type=int, default=100)
    p.set_defaults(fn=cmd_aivision)

    p = sub.add_parser("qa")
    p.add_argument("job")
    p.add_argument("--pages", default=None, help="只查指定页，如 31,100-120（缺省=全书）")
    p.add_argument("--top", type=int, default=40, help="打印最可疑的前 N 页（默认 40）")
    p.set_defaults(fn=cmd_qa)

    p = sub.add_parser("reocr")
    p.add_argument("job")
    p.add_argument("--pages", default=None, help="按页选批重识别，如 31,100-120（默认扩到整批，--exact 则只跑点名页）")
    p.add_argument("--batch", default=None, help="只重识别指定批（配 --exact 可只跑 --pages 点名的页）")
    p.add_argument("--all", action="store_true", help="全书重识别")
    p.add_argument("--needs-vision", action="store_true", help="只重识别 pull 标记的图示/表格页")
    p.add_argument("--exact", action="store_true",
                   help="只重识别点名页，同批其余页沿用本地 pull 结果（pull 书省钱首选）")
    p.add_argument("--workers", type=int, default=None, help="页级并发（默认 3，过高会 429）")
    p.add_argument("--force", action="store_true", help="已有重识别结果也重跑；质检失败也放行")
    p.add_argument("--min", type=int, default=100)
    p.add_argument("--no-backup", action="store_true", help="不备份原批（默认备份）")
    p.set_defaults(fn=cmd_reocr)

    p = sub.add_parser("retry")
    p.add_argument("job")
    p.add_argument("batch", nargs="?")
    p.add_argument("--all-failed", action="store_true")
    p.set_defaults(fn=cmd_retry)

    p = sub.add_parser("chapters")
    p.add_argument("job")
    p.add_argument("--apply", default=None, help="吸收裁决文件（chapters_review.txt）后重切章")
    p.add_argument("--no-review", action="store_true", help="不生成裁决任务卡（疑点只记台账，pack 不拦）")
    p.set_defaults(fn=cmd_chapters)

    p = sub.add_parser("pack")
    p.add_argument("job")
    p.add_argument("--no-pdf", action="store_true")
    p.add_argument("--force", action="store_true", help="允许带未完成批打包（出半本书）")
    p.add_argument("--auto-chapters", action="store_true", help="还没切章时自动先跑一次 chapters")
    p.add_argument("--no-chapters", action="store_true", help="txt 不带章界元数据块（旧格式）")
    p.set_defaults(fn=cmd_pack)

    p = sub.add_parser("status")
    p.add_argument("job")
    p.set_defaults(fn=cmd_status)

    p = sub.add_parser("proofread")
    p.add_argument("job")
    p.add_argument("--pages", default=None, help="只校对指定页，如 31,100-120")
    p.add_argument("--batch", default=None, help="只校对指定批，如 b011")
    p.add_argument("--all", action="store_true", help="全书所有 done 批")
    p.add_argument("--rules-only", action="store_true", help="只跑规则层，不调文本 API")
    p.add_argument("--no-ai", action="store_true", help="禁用 AI 层（与 --rules-only 等效）")
    p.add_argument("--dry-run", action="store_true", help="只出报告和标记，不写回批文件")
    p.add_argument("--workers", type=int, default=3, help="AI 层并发（默认 3，过高会 429）")
    p.add_argument("--no-backup", action="store_true", help="不备份原批")
    p.set_defaults(fn=_cmd_proofread_bridge)

    a = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if a.cmd == "status":  # 只读命令不占锁
        a.fn(a)
    else:
        with _ManifestLock():
            a.fn(a)


if __name__ == "__main__":
    main()
