#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bookocr — 扫描版 PDF → txt / 插图版 PDF 识别台（agent 驱动，操作手册见 SKILL.md）

子命令：
  init   <书名> --src <PDF路径> [--batch 10] [--dpi 200] [--color]   建识别工程
  prep   <书名或工程路径>                                            渲染页图
  next   <书名或工程路径>                                            领下一批（输出子代理任务卡）
  check  <书名或工程路径> <批号> [--force] [--min 100]               质检一批
  retry  <书名或工程路径> <批号|--all-failed>                        重置一批待重转
  status <书名或工程路径>                                            进度一览
  pack   <书名或工程路径> [--no-pdf] [--force]                       打包 txt / 插图版 PDF

台账在 jobs/<书名>/manifest.json（单文件状态机），所有命令可随时中断重跑。
设计要点：正文永远只落文件（batches/），台账里只存统计数字，不存正文。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

KIT = Path(__file__).resolve().parent
JOBS = KIT / "jobs"

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
        "dpi": a.dpi,
        "color": a.color,
        "batch_size": a.batch,
        "min_chars_per_page": 100,
        "spec": str(KIT / "spec.md"),
        "npages": npages,
        "pages": [],       # [{no, image}]，prep 填充
        "batches": {},     # b001 -> {pages, status, body, notes, figures, stats, fail_reason}
        "figures": [],     # [{id, batch, page, rect, kind, title, desc, file}]
    }
    save_manifest(job, m)
    out(f"[ok] 工程 {job}")
    out(f"     书名「{name}」 PDF {src}（{npages} 页） dpi={a.dpi} 每批 {a.batch} 页")
    out("下一步：prep 渲染页图")


# ---------------------------------------------------------------- prep

def cmd_prep(a: argparse.Namespace) -> None:
    job = resolve_job(a.job)
    m = load_manifest(job)
    if m["pages"]:
        out(f"[ok] 已渲染过 {len(m['pages'])} 页，跳过（重复跑安全）")
        return
    fitz = load_fit()
    doc = fitz.open(m["src_pdf"])
    dpi, gray = m["dpi"], not m["color"]
    pages = []
    for i in range(doc.page_count):
        page = doc[i]
        pix = page.get_pixmap(dpi=dpi)
        if gray and pix.colorspace and pix.colorspace.n > 1:
            pix = fitz.Pixmap(fitz.csGRAY, pix)
        img_rel = f"images/{i + 1:04d}.png"
        pix.save(str(job / img_rel))
        pages.append({"no": i + 1, "image": img_rel})
        if (i + 1) % 20 == 0 or i + 1 == doc.page_count:
            out(f"  渲染 {i + 1}/{doc.page_count}")
    doc.close()

    bs = m["batch_size"]
    batches = {}
    for bi, start in enumerate(range(0, len(pages), bs), 1):
        grp = pages[start:start + bs]
        batches[f"b{bi:03d}"] = {
            "pages": [p["no"] for p in grp],
            "status": "pending",  # pending→assigned→done/failed
            "body": f"batches/b{bi:03d}.txt",
            "notes": f"batches/b{bi:03d}_notes.txt",
            "figures": f"batches/b{bi:03d}_figures.json",
            "stats": None,
            "fail_reason": None,
        }
    m["pages"], m["batches"] = pages, batches
    save_manifest(job, m)
    out(f"[ok] 渲染 {len(pages)} 页 → 共 {len(batches)} 批（每批 {bs} 页）")
    out("下一步：next 领批，派子代理识别（见 SKILL.md）")


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


def bid_of(m: dict, batch: dict) -> str:
    return next(k for k, v in m["batches"].items() if v is batch)


# ---------------------------------------------------------------- check

def read_text(p: Path) -> str:
    return p.read_text(encoding="utf-8-sig") if p.exists() else ""


def cmd_check(a: argparse.Namespace) -> None:
    job = resolve_job(a.job)
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

    # 页号 → 段落（跨批拼接，页号排序兜底）
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

    # 图位一次性确定性分配：每页按图在页面里的纵向位置排序；正文里第 k 个〔图：…〕
    # 占位行吃掉第 k 张，没写占位行的图补在页尾。txt 与 PDF 用同一份分配，保证两边一致。
    figs_by_page: dict[int, list[dict]] = {}
    for page in sorted(book):
        figs_by_page[page] = sorted(
            [f for f in m["figures"] if f["page"] == page], key=lambda f: f["rect"][1]
        )

    # ---- txt ----
    txt_parts: list[str] = [m["name"], ""]
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
    out(f"[ok] txt → {out_txt}（{total_chars} 字）")

    # ---- 插图版 PDF ----
    if a.no_pdf:
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
    p.add_argument("--batch", type=int, default=10)
    p.add_argument("--dpi", type=int, default=200)
    p.add_argument("--color", action="store_true")
    p.set_defaults(fn=cmd_init)

    p = sub.add_parser("prep")
    p.add_argument("job")
    p.set_defaults(fn=cmd_prep)

    p = sub.add_parser("next")
    p.add_argument("job")
    p.set_defaults(fn=cmd_next)

    p = sub.add_parser("check")
    p.add_argument("job")
    p.add_argument("batch")
    p.add_argument("--force", action="store_true", help="豁免字数/占比判据（页对账永不豁免）")
    p.add_argument("--min", type=int, default=100, help="页均字数阈值")
    p.set_defaults(fn=cmd_check)

    p = sub.add_parser("retry")
    p.add_argument("job")
    p.add_argument("batch", nargs="?")
    p.add_argument("--all-failed", action="store_true")
    p.set_defaults(fn=cmd_retry)

    p = sub.add_parser("status")
    p.add_argument("job")
    p.set_defaults(fn=cmd_status)

    p = sub.add_parser("pack")
    p.add_argument("job")
    p.add_argument("--no-pdf", action="store_true")
    p.add_argument("--force", action="store_true", help="允许带未完成批打包（出半本书）")
    p.set_defaults(fn=cmd_pack)

    a = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    a.fn(a)


if __name__ == "__main__":
    main()
