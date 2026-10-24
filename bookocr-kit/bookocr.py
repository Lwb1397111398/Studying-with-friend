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
  next   <书名或工程路径>                                            领下一批（输出子代理任务卡）
  check  <书名或工程路径> <批号> [--force] [--min 100]               质检一批
  retry  <书名或工程路径> <批号|--all-failed>                        重置一批待重转
  chapters <书名或工程路径> [--apply 裁决文件] [--no-review]          全书切章 + 目录对账（疑点出裁决任务卡）
  status <书名或工程路径>                                            进度一览
  pack   <书名或工程路径> [--no-pdf] [--force]                       打包 txt / 插图版 PDF（txt 头部带章界元数据）

全局设置读 kit 目录 config.json（route 路线 / pages_per_batch / dpi / aivision_workers /
chapter_review / output），init 时快照进台账；缺省值见 DEFAULT_CONFIG。
台账在 jobs/<书名>/manifest.json（单文件状态机），所有命令可随时中断重跑。
设计要点：正文永远只落文件（batches/），台账里只存统计数字，不存正文。
"""
from __future__ import annotations

import argparse
import difflib
import json
import re
import statistics
import sys
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
    "output": ["txt", "pdf"],  # 产出物开关（txt 永远产出；"pdf" 不在列表则不出插图版）
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

# 子代理产出协议：〔页N〕整行 = 一页开始；〔图：标题〕整行 = 真图占位
PAGE_MARK = re.compile(r"^\s*〔页\s*(\d+)\s*〕\s*$")
FIG_MARK = re.compile(r"^\s*〔图[:：]\s*(.+?)\s*〕\s*$")
# 排版标题启发式（与 APP 侧 BookParser 同一套档位口径）
CH_RE = re.compile(r"^第\s*[0-9〇零一二三四五六七八九十百千]+\s*[章编部篇]")
SEC_RE = re.compile(r"^(第\s*[0-9〇零一二三四五六七八九十百千]+\s*[节讲]|[一二三四五六七八九十]+\s*、)")


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


def _parse_aivision_page(text: str, pageno: int) -> tuple[list[str], list[str], list[dict]]:
    """拆模型输出 → (正文行, 脚注行, figures)。容忍模型漏写分区标记。"""
    body_lines, notes, figs = [], [], []
    section = "body"
    for ln in text.replace("\r\n", "\n").split("\n"):
        s = ln.strip()
        if re.fullmatch(r"〔\s*脚注\s*〕?", s) or s == "〔脚注〕":
            section = "notes"; continue
        if re.fullmatch(r"〔\s*FIGURES\s*〕?", s) or s.upper() == "〔FIGURES〕":
            section = "figs"; continue
        if section == "body":
            if PAGE_MARK.match(s) or s == f"〔页{pageno}〕":
                continue  # 模型自己写的页标记行，统一重打
            if s.startswith("〔图") and "〕" in s:
                body_lines.append(s)
            elif s:
                body_lines.append(s)
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
    return body_lines, notes, figs


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
            cmd_check(argparse.Namespace(job=a.job, batch=bid, force=a.force, min=a.min))
        except SystemExit:
            out(f"[!] {bid} 质检不过，retry 后可重跑 aivision 或改派子代理")
    out("下一步：status 看进度")


# ---------------------------------------------------------------- pull（文字层拔取）

PNUM_RE = re.compile(r"^[\s·•.\-—]*\d{1,4}[\s·•.\-—]*$")

def _page_lines(page: dict) -> list:
    """把一页的 dict 结构摊平成行：[{text,size,x0,y0,x1,y1}]，按阅读序排好。

    两端对齐的排版会把同一视觉行在西文词间大空隙处拆成多个 line 对象
    （如「dingliche|Einigung」），按 y0 聚簇后把横向间隙小于 4 字号的碎片
    拼回一行，否则缩进判据会把碎片当新段首。"""
    lines = []
    for blk in page.get("blocks", []):
        if blk.get("type") != 0:
            continue
        for ln in blk.get("lines", []):
            spans = [s for s in ln.get("spans", []) if s.get("text", "").strip()]
            if not spans:
                continue
            spans.sort(key=lambda s: s["bbox"][0])
            text = "".join(s["text"] for s in spans).strip()
            if not text:
                continue
            x0, y0, x1, y1 = ln["bbox"]
            size = max(s["size"] for s in spans)
            lines.append({"text": text, "size": size, "x0": x0, "y0": y0, "x1": x1, "y1": y1})
    lines.sort(key=lambda l: (l["y0"], l["x0"]))
    merged: list = []
    for l in lines:
        if merged and abs(l["y0"] - merged[-1]["y0"]) <= 2.0:
            g = merged[-1]
            gap = l["x0"] - g["x1"]
            if -g["size"] < gap < 4 * max(g["size"], l["size"]):
                a, bx = g["text"][-1:], l["text"][:1]
                sep = " " if a.isascii() and a.isalpha() and bx.isascii() and bx.isalpha() else ""
                g["text"] += sep + l["text"]
                g["x1"] = max(g["x1"], l["x1"])
                g["size"] = max(g["size"], l["size"])
                continue
        merged.append(dict(l))
    return merged


def _pull_page(page, fitz, body_size: float | None = None, lines: list | None = None) -> dict:
    """拔一页：返回 {paras, notes, figs, chars, first/last 行属性}。页眉页脚/页码剔除，脚注按字号分区。

    body_size/lines 可由 cmd_pull 全书预算后传入：页内小样本会被脚注/年表等
    小字号块带偏主字号，致正文行被误判标题、脚注漏分区（全书主字号是稳定的）。

    职责声明（双端分段规则各自标定，勿互相"对齐"）：本函数阈值按**出版社级 PDF
    文字层**标定（字号/坐标来自内嵌字体度量，可靠），分钟级批量零 token；
    App 侧 PdfCleaner/ParagraphAssembler 面向手机导入、要同时伺候 OCR 行
    （size=框高×0.68 有系统性偏差），判据更保守。两套数值差异数值表见
    docs/模块总览/M9-书籍识别台bookocr-kit.md「双端分段规则对照」。"""
    H = page.rect.height
    raw = lines if lines is not None else _page_lines(page.get_text("dict"))

    # 正文主字号：中带区按字数加权的众数（未传全书值时按本页算）
    if body_size is None:
        mid = [l for l in raw if 0.10 * H < l["y0"] < 0.85 * H]
        tally: dict[float, int] = {}
        for l in mid:
            k = round(l["size"], 1)
            tally[k] = tally.get(k, 0) + len(l["text"])
        body_size = max(tally, key=tally.get) if tally else 12.0

    body, notes = [], []
    for l in raw:
        y0n, y1n = l["y0"] / H, l["y1"] / H
        if y1n <= 0.062 or y0n >= 0.958 or PNUM_RE.match(l["text"]) and (y1n <= 0.10 or y0n >= 0.90):
            continue  # 页眉带/页脚带/孤立页码
        if y0n > 0.72 and l["size"] < body_size * 0.88:
            notes.append(l["text"])
            continue
        body.append(l)

    # 段落重组：标题独立成行；正文行首缩进超 1.4 字号 = 新段
    left = min((l["x0"] for l in body), default=0.0)
    paras: list[str] = []
    for l in body:
        is_head = l["size"] >= body_size * 1.15 or CH_RE.match(l["text"]) or SEC_RE.match(l["text"])
        new_para = is_head or (l["x0"] - left > body_size * 1.4) or not paras
        if new_para or is_head:
            paras.append(l["text"])
        else:
            paras[-1] += l["text"]

    # 嵌入真图：按实际图块坐标登记（比目测准），整页水印图跳过
    figs = []
    for img in page.get_images(full=True):
        try:
            rects = page.get_image_rects(img[0])
        except Exception:
            continue
        for r in rects:
            area = (r.width * r.height) / (page.rect.width * page.rect.height)
            if 0.015 < area < 0.85:
                figs.append({"page": page.number + 1,
                             "rect": [round(r.x0 / page.rect.width, 4), round(r.y0 / H, 4),
                                      round(r.x1 / page.rect.width, 4), round(r.y1 / H, 4)],
                             "kind": "photo", "title": f"第{page.number + 1}页插图",
                             "desc": "原书嵌入插图（坐标按原 PDF 图块精确提取）"})
    chars = sum(len(re.sub(r"\s", "", p)) for p in paras)

    # 跨页缝合依据：本页首/末行的缩进与标题属性（中文书段首缩进约两字、续行顶格）
    def _is_head(l: dict) -> bool:
        return bool(l["size"] >= body_size * 1.15 or CH_RE.match(l["text"]) or SEC_RE.match(l["text"]))

    first, last = (body[0], body[-1]) if body else (None, None)
    return {"paras": paras, "notes": notes, "figs": figs, "chars": chars, "fig_area": sum(
        (f["rect"][2] - f["rect"][0]) * (f["rect"][3] - f["rect"][1]) for f in figs),
        "first_x0": first["x0"] if first else None,
        "first_size": first["size"] if first else None,
        "first_is_head": _is_head(first) if first else True,
        "last_is_head": _is_head(last) if last else True,
        "page_left": left if body else None}


def cmd_pull(a: argparse.Namespace) -> None:
    """对自带文字层的书：直接拔取文字层产出批文件，免视觉识别（分钟级、零幻觉）。"""
    job = resolve_job(a.job)
    m = load_manifest(job)
    if not m["batches"]:
        die("还没有批次，先跑 prep")
    fitz = load_fit()
    doc = fitz.open(m["src_pdf"])

    # 全书一次拔取：跨页缝合需要全书视野（批边界上的段落照样缝）
    npages = len(m["pages"])
    all_lines = {n: _page_lines(doc[n - 1].get_text("dict")) for n in range(1, npages + 1)}
    # 全书正文主字号：跨页统计——页内小样本会被脚注/年表小字号带偏（实测 43 页误判）
    tally: dict[float, int] = {}
    for n in range(1, npages + 1):
        ph = doc[n - 1].rect.height
        for l in all_lines[n]:
            if 0.10 * ph < l["y0"] < 0.85 * ph:
                k = round(l["size"], 1)
                tally[k] = tally.get(k, 0) + len(l["text"])
    gbody = max(tally, key=tally.get) if tally else 12.0
    all_pages = {n: _pull_page(doc[n - 1], fitz, body_size=gbody, lines=all_lines[n])
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
        indent = (cur["first_x0"] - gleft) / (cur["first_size"] or 1)
        if indent < 0.6:
            tail, head = prev["paras"][-1][-1:], cur["paras"][0][:1]
            sep = " " if tail.isascii() and tail.isalpha() and head.isascii() and head.isalpha() else ""
            prev["paras"][-1] += sep + cur["paras"][0]
            cur["paras"] = cur["paras"][1:]
            stitched += 1

    pulled, thin_total = [], 0
    for bid, b in m["batches"].items():
        pages_data = {n: all_pages[n] for n in b["pages"]}
        # 页级路由：无文字但被大图占据的页 = 图版页，正文写占位、记 thin_pages 留给 patch 补描述；
        # 不再整批跳过——一批里混几页图版不再连累其余 9 页走视觉
        thin = sorted(n for n, d in pages_data.items()
                      if d["chars"] == 0 and d["fig_area"] > 0.3)

        parts, all_notes = [], []
        figs = []
        for n in b["pages"]:
            d = pages_data[n]
            parts.append(f"〔页{n}〕")
            if n in thin:
                parts.append(f"〔图：第{n}页图版〕")
                parts.append("〔图版页〕")
            else:
                parts.extend(d["paras"] or ["〔空白页〕"])
            parts.append("")  # 页间空行
            all_notes.extend(d["notes"])
            figs.extend(d["figs"])
        (job / b["body"]).write_text("\n".join(parts).rstrip() + "\n", encoding="utf-8")
        (job / b["notes"]).write_text("\n".join(all_notes) + ("\n" if all_notes else ""), encoding="utf-8")
        (job / b["figures"]).write_text(json.dumps(figs, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

        b["status"] = "assigned"  # 防止 next 重派；由 pull 内部直接质检
        b["thin_pages"] = thin
        thin_total += len(thin)
        save_manifest(job, m)
        try:
            # 传共享台账：check 在外层 m 上标 done、重建图台账，循环内不产生状态回退
            cmd_check(argparse.Namespace(job=a.job, batch=bid, force=a.force, min=a.min), m=m)
            pulled.append(bid)
        except SystemExit:
            out(f"[!] {bid} 质检不过，看 check 输出（可 retry 后派视觉子代理/aivision）")
    out(f"[ok] pull 完成：拔取 {len(pulled)}/{len(m['batches'])} 批，跨页缝合 {stitched} 处，图版页 {thin_total} 页"
        + (f"（python bookocr.py patch {a.job} 可派视觉补图版描述）" if thin_total else ""))
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
    per_page = chars / len(want)
    zh = len(re.findall(r"[\u4e00-\u9fff]", body_only))
    ratio = zh / max(1, len(re.sub(r"\s", "", body_only)))
    if not a.force and per_page < a.min:
        b["status"], b["fail_reason"] = "failed", f"页均字数 {per_page:.0f} < {a.min}"
        save_manifest(job, m)
        die(f"{bid} 质检不过：页均 {per_page:.0f} 字（阈值 {a.min}）。确认是图表页/空白页就 retry 后带 --force 放行，否则重转")
    if not a.force and ratio < 0.5:
        b["status"], b["fail_reason"] = "failed", f"中文占比 {ratio:.0%} 异常"
        save_manifest(job, m)
        die(f"{bid} 质检不过：中文占比 {ratio:.0%}，疑似识别串台")

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
                             "desc": str(f.get("desc", "")).strip()})
    base = len(m["figures"])
    for i, f in enumerate(figs):
        f["id"] = f"f{base + i + 1:03d}"
    m["figures"] = [x for x in m["figures"] if x["batch"] != bid] + figs

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
CH_NUM_RE = re.compile(r"^第\s*([0-9〇零一二三四五六七八九十百千]+)\s*[章编部篇卷回]")
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
    if s[-1:] in "。！？；，,;?!" or any(c in s for c in "，,；;"):
        return None
    mm = CH_NUM_RE.match(s)
    if mm:
        return ("num", mm.group(1))
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

    # 图位一次性确定性分配：每页按图在页面里的纵向位置排序；正文里第 k 个〔图：…〕
    # 占位行吃掉第 k 张，没写占位行的图补在页尾。txt 与 PDF 用同一份分配，保证两边一致。
    figs_by_page: dict[int, list[dict]] = {}
    for page in sorted(book):
        figs_by_page[page] = sorted(
            [f for f in m["figures"] if f["page"] == page], key=lambda f: f["rect"][1]
        )

    # ---- txt ----
    # 头部带 KIT 章界元数据块：App 端识别到即走「成品直入」通道（按章表切章，跳过通用正则猜测）
    txt_parts: list[str] = []
    if m.get("chapters") and not a.no_chapters:
        meta = {"kit": 1, "v": "bookocr-kit 1.1", "title": m["name"], "pages": m["npages"],
                "chapters": [{"t": c["t"], "p": c["p"], "lv": c["lv"], "r": c["r"], "a": c["a"]}
                             for c in m["chapters"]]}
        txt_parts += ["〔KIT-书 开始〕", json.dumps(meta, ensure_ascii=False), "〔KIT-书 结束〕"]
    txt_parts += [m["name"], ""]
    for page in sorted(book):
        paras = list(book[page])
        figs_here = figs_by_page[page]
        # 子代理已写〔图：…〕行的保留原样；记了台账但没写占位行的补在页尾
        marked = sum(1 for p in paras if FIG_MARK.match(p))
        for f in figs_here[marked:]:
            line = f"〔图：{f['title'] or f['kind']}〕{f['desc']}".strip()
            if line:
                paras.append(line)
        txt_parts.extend(paras + [""])
    if notes:
        txt_parts += ["注释", ""]
        txt_parts += [f"{i}. {n}" if not re.match(r"^[①-⑳0-9]+", n) else n for i, n in enumerate(notes, 1)]
    out_txt = job / "output" / f"{m['name']}.txt"
    out_txt.write_text("\n".join(txt_parts).rstrip() + "\n", encoding="utf-8-sig")

    total_chars = len(re.sub(r"\s", "", "\n".join(txt_parts)))
    out(f"[ok] txt → {out_txt}（{total_chars} 字）"
        + ("（含章界元数据块）" if len(txt_parts) > 2 else ""))

    m["packed"] = True
    save_manifest(job, m)

    # ---- 插图版 PDF ----
    if a.no_pdf:
        return
    if "pdf" not in (m.get("config") or {}).get("output", ["txt", "pdf"]):
        out("[i] config.output 未含 pdf，跳过插图版 PDF（txt 已产出）")
        return
    pdf_path = pack_pdf(job, m, book, notes, figs_by_page)
    if pdf_path:
        out(f"[ok] 插图版 PDF → {pdf_path}")


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
            page = doc_src[f["page"] - 1]
            r = page.rect
            l, t, rr, bb = f["rect"]
            clip = fitz.Rect(r.width * l, r.height * t, r.width * rr, r.height * bb)
            if clip.width * clip.height < r.width * r.height * 0.01:
                return None  # 裁剪区过小，疑似标错，跳过
            pix = page.get_pixmap(dpi=m["dpi"], clip=clip)
            if not m["color"] and pix.colorspace and pix.colorspace.n > 1:
                pix = fitz.Pixmap(fitz.csGRAY, pix)
            fp = fig_dir / f"{f['id']}_p{f['page']:03d}.png"
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
                    story += [img, Paragraph(fm.group(1) or f["title"], st_cap)]
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


def _style_of(para: str, h1, h2, body):
    if CH_RE.match(para) and len(para) <= 40:
        return h1
    if SEC_RE.match(para) and len(para) <= 40:
        return h2
    return body


# ---------------------------------------------------------------- main

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

    p = sub.add_parser("status")
    p.add_argument("job")
    p.set_defaults(fn=cmd_status)

    p = sub.add_parser("pack")
    p.add_argument("job")
    p.add_argument("--no-pdf", action="store_true")
    p.add_argument("--force", action="store_true", help="允许带未完成批打包（出半本书）")
    p.add_argument("--auto-chapters", action="store_true", help="还没切章时自动先跑一次 chapters")
    p.add_argument("--no-chapters", action="store_true", help="txt 不带章界元数据块（旧格式）")
    p.set_defaults(fn=cmd_pack)

    a = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    a.fn(a)


if __name__ == "__main__":
    main()
