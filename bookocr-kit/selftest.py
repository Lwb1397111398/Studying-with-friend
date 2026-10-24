#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bookocr-kit 自测：合成一本 12 页假书，把 init→prep→next→check→retry→chapters→pack 全链路
跑一遍并断言（含切章/目录对账/疑点裁决/KIT 元数据块）。不依赖任何真实样本，换电脑装好依赖即可回归。

跑法：python selftest.py   （全部通过输出 ALL PASS，任何失败退出码 1）
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

KIT = Path(__file__).resolve().parent
PY = sys.executable
CLI = str(KIT / "bookocr.py")
JOB = KIT / "jobs" / "_selftest"

FAILS: list[str] = []


def run(args: str, expect_fail: bool = False) -> str:
    r = subprocess.run([PY, CLI, *args.split()], capture_output=True, text=True,
                       encoding="utf-8", cwd=str(KIT))
    outp = (r.stdout or "") + (r.stderr or "")
    if expect_fail and r.returncode == 0:
        fail(f"应失败却成功：{args}\n{outp}")
    if not expect_fail and r.returncode != 0:
        fail(f"应成功却失败：{args}\n{outp}")
    return outp


def check(cond: bool, msg: str) -> None:
    if cond:
        print(f"  [PASS] {msg}")
    else:
        fail(msg)


def fail(msg: str) -> None:
    FAILS.append(msg)
    print(f"  [FAIL] {msg}")


def filler(page: int, n_paras: int = 4) -> str:
    """造一页 ~250 字的假正文。"""
    sents = [
        "当事人订立合同，应当具有相应的民事权利能力和民事行为能力。",
        "依法成立的合同，自成立时生效，但是法律另有规定或者当事人另有约定的除外。",
        "要约是希望与他人订立合同的意思表示，该意思表示应当符合下列规定。",
        "承诺生效时合同成立，但是法律另有规定或者当事人另有约定的除外。",
    ]
    paras = [f"第{page}页测试{i}。" + "".join(sents[(page + i + k) % 4] for k in range(2))
             for i in range(n_paras)]
    return "\n\n".join(paras)


def write_batch(bid: str, pages: list[int], *, marks: list[int] | None = None,
                low: bool = False, figs: list[dict] | None = None, notes: str = "",
                heads: dict[int, str] | None = None) -> None:
    mk = marks if marks is not None else pages

    def page_body(p: int) -> str:
        if p == 3 and not low:
            # 半角冒号是子代理偶发笔误（质检放行）：pack 应归一化为全角规范形态
            return "〔图:表3-1 测试表格〕\n这是图的说明文字。"
        pre = ""
        if heads and p in heads:
            pre = heads[p] + "\n\n"
        return pre + filler(p, 1 if low else 4)

    body = "\n\n".join(f"〔页{p}〕\n" + page_body(p) for p in mk)
    (JOB / "batches" / f"{bid}.txt").write_text(body, encoding="utf-8")
    (JOB / "batches" / f"{bid}_notes.txt").write_text(notes, encoding="utf-8")
    (JOB / "batches" / f"{bid}_figures.json").write_text(
        json.dumps(figs or [], ensure_ascii=False), encoding="utf-8")


def write_empty(bid: str) -> None:
    for suf in (".txt", "_notes.txt"):
        (JOB / "batches" / f"{bid}{suf}").write_text("", encoding="utf-8")
    (JOB / "batches" / f"{bid}_figures.json").write_text("[]", encoding="utf-8")


def main() -> None:
    print("== 造样本书 ==")
    import pymupdf as fitz
    src = KIT / "jobs" / "_selftest_src.pdf"
    JOB.parent.mkdir(exist_ok=True)
    for stale in KIT.glob("jobs/_selftest*"):  # 上次异常退出可能残留工程，先清干净
        shutil.rmtree(stale, ignore_errors=True) if stale.is_dir() else stale.unlink(missing_ok=True)
    doc = fitz.open()
    tiny = fitz.Pixmap(fitz.csRGB, fitz.IRect(0, 0, 8, 8), False)  # 8x8 深色小图，供第 11 页嵌图
    tiny.set_rect(tiny.irect, (90, 90, 90))
    # 第 4-8 页手工摆行造跨页缝合场景：(y, x, 文本)。x=72 顶格（续行）、x=94 缩进两字（新段首）
    LINES = {
        4: [(140, 94, "第4页测试一。当事人订立合同，应当具有相应的民事权利能力和民事行为能力。"),
            (160, 72, "依法成立的合同，自成立时生效。"),
            (180, 94, "本书设有问题及例题，尤其是采用请求")],       # 页末半句、无标点
        5: [(140, 72, "权基础的案例，其目的在于引发思考方向。"),     # 顶格续行 → 应缝回第 4 页（跨批边界）
            (160, 94, "第5页测试一。要约是希望与他人订立合同的意思表示，"),
            (180, 72, "该意思表示应当符合下列规定。"),
            (200, 94, "第5页测试二。承诺生效时合同成立，但是法律另有规定的除外。"),
            (220, 72, "当事人另有约定的除外也不影响。")],
        6: [(140, 94, "第6页测试一。承诺生效时合同成立，但是法律另有规定或者当事人另有约定的除外。"),  # 缩进 → 不缝
            (160, 72, "要约邀请是希望他人向自己发出要约的表示。"),
            (180, 94, "第6页测试二。拍卖公告、招标公告、招股说明书、债券募集办法、基金招募说明书。"),
            (200, 72, "商业广告和宣传的内容符合要约条件的构成要约。")],
        7: [(140, 94, "第7页测试一。要约以对话方式作出的，应当即时作出承诺。"),
            (160, 72, "当事人对承诺方式没有约定的，应当即时作出承诺。"),
            (180, 94, "第7页测试二。承诺应当在要约确定的期限内到达要约人。"),
            (200, 72, "要约没有确定承诺期限的，承诺应当依照规定到达。"),
            (220, 72, "第二章 跨页标题测试")],                        # 页末标题
        8: [(140, 72, "标题下的正文续行内容。"),                     # 顶格但上页末是标题 → 不得缝进标题
            (160, 94, "第8页测试一。承诺通知到达要约人时生效。"),
            (180, 72, "承诺不需要通知的，根据交易习惯作出承诺的行为生效。"),
            (200, 94, "第8页测试二。当事人订立合同，可以采取要约、承诺方式。"),
            (220, 72, "其他方式订立合同的情形依照其规定处理。")],
    }
    def put_paras(page, pageno: int, n_paras: int, y0: int = 140) -> None:
        """逐段两行摆放：段首行 x=94（缩进两字）、续行 x=72（顶格）——模拟真实书的排版证据，
        pull 的段落重组与跨页缝合都靠缩进判据，整块 textbox 顶格会让段落全拼成一坨。"""
        sents = [
            "当事人订立合同，应当具有相应的民事权利能力和民事行为能力。",
            "依法成立的合同，自成立时生效，但是法律另有规定或者当事人另有约定的除外。",
            "要约是希望与他人订立合同的意思表示，该意思表示应当符合下列规定。",
            "承诺生效时合同成立，但是法律另有规定或者当事人另有约定的除外。",
        ]
        y = y0
        for i in range(n_paras):
            first = f"第{pageno}页测试{i}。" + sents[(pageno + i) % 4]
            rest = sents[(pageno + i + 1) % 4]
            page.insert_text((94, y), first, fontname="china-s", fontsize=11)
            page.insert_text((72, y + 18), rest, fontname="china-s", fontsize=11)
            y += 52

    for i in range(12):
        page = doc.new_page()
        if i == 0:
            page.insert_text((94, 110), "第一章 总则", fontname="china-s", fontsize=16)
        if i == 2:  # 第 3 页画一块"插图"区
            page.draw_rect(fitz.Rect(page.rect.width * 0.1, page.rect.height * 0.55,
                                     page.rect.width * 0.9, page.rect.height * 0.85),
                           color=(0, 0, 0), fill=(0.2, 0.2, 0.2))
            page.insert_textbox(fitz.Rect(72, page.rect.height * 0.86, page.rect.width - 72, page.rect.height * 0.9),
                                "图3-1 测试插图", fontname="china-s", fontsize=10)
        if i == 10:  # 第 11 页：纯图版页（无文字层、整页大图）→ pull 应记 thin_pages
            page.insert_image(fitz.Rect(page.rect.width * 0.1, page.rect.height * 0.1,
                                        page.rect.width * 0.9, page.rect.height * 0.9),
                              pixmap=tiny)
            continue
        if i == 8:  # 第 9 页：普通正文页中间嵌真图 → pull 应按 y 坐标在段间插图占位行
            # 段首 x=94（缩进两字）/续行 x=72（顶格）：pull 分段靠这组缩进证据
            page.insert_text((94, 140), "第9页测试一。要约是希望与他人订立合同的意思表示。",
                             fontname="china-s", fontsize=11)
            page.insert_text((72, 158), "依法成立的合同，自成立时生效。",
                             fontname="china-s", fontsize=11)
            page.insert_text((94, 190), "第9页测试二。承诺生效时合同成立。",
                             fontname="china-s", fontsize=11)
            page.insert_text((72, 208), "但是法律另有规定的除外。",
                             fontname="china-s", fontsize=11)
            page.insert_image(fitz.Rect(page.rect.width * 0.2, 240, page.rect.width * 0.8, 320),
                              pixmap=tiny)
            page.insert_text((94, 350), "第9页测试三。当事人订立合同可以采取要约承诺方式。",
                             fontname="china-s", fontsize=11)
            page.insert_text((72, 368), "其他方式订立合同的情形依照其规定处理。",
                             fontname="china-s", fontsize=11)
            continue
        if i + 1 in LINES:
            for y, x, txt in LINES[i + 1]:
                page.insert_text((x, y), txt, fontname="china-s", fontsize=11)
            continue
        put_paras(page, i + 1, 3 if i == 2 else 6, y0=150 if i == 0 else 140)
    doc.save(str(src))
    doc.close()

    print("== init / prep ==")
    shutil.rmtree(JOB, ignore_errors=True)
    run(f"init _selftest --src {src} --batch 4")
    m = json.loads((JOB / "manifest.json").read_text(encoding="utf-8"))
    check(m["npages"] == 12, "init 记录 12 页")
    check(len(m["batches"]) == 3, "init 直接建批（每批 4 页 → 3 批，渲染前即可 pull）")
    run("prep _selftest")
    m = json.loads((JOB / "manifest.json").read_text(encoding="utf-8"))
    check(len(m["pages"]) == 12, "prep 渲染 12 页")
    check((JOB / "images" / "0003.png").exists(), "页图 0003.png 落盘")
    check((JOB / "images" / "0011.png").exists(), "页图 0011.png 落盘")
    run("prep _selftest")  # 幂等
    check("跳过" in run("prep _selftest"), "prep 重复跑安全")

    print("== next / check 状态机 ==")
    o1 = run("next _selftest")
    check("批 b001 | 第 1–4 页" in o1, "next 领到 b001（1–4 页）")
    check("spec.md" in o1 and "0004.png" in o1, "任务卡含规范路径与图片清单")
    o2 = run("next _selftest")
    check("批 b002" in o2, "next 再领 b002")
    run("check _selftest b001", expect_fail=True)          # 文件未写 → 失败
    write_batch("b001", [1, 2, 3, 4],
                marks=[1, 2, 3],                            # 漏第 4 页
                figs=[{"page": 3, "rect": [0.1, 0.55, 0.9, 0.85], "kind": "table",
                       "title": "表3-1", "desc": "测试表格"}])
    o = run("check _selftest b001", expect_fail=True)
    check("缺页标记[4]" in o, "漏页被质检拦下")
    write_batch("b001", [1, 2, 3, 4],
                figs=[{"page": 3, "rect": [0.1, 0.55, 0.9, 0.85], "kind": "table",
                       "title": "表3-1 要约与承诺", "desc": "对比表格"},
                      {"page": 3, "rect": [0.9, 0.1, 0.2, 0.5], "kind": "photo",  # rect 反 → 应被过滤
                       "title": "坏数据", "desc": ""},
                      {"page": 99, "rect": [0.1, 0.1, 0.9, 0.5], "kind": "photo",  # 页不在本批 → 过滤
                       "title": "越界", "desc": ""}],
                heads={1: "第二版序",
                       2: "目录\n第一章 合同的成立 ……3\n第二章 合同的效力 ……9\n第三章 违约责任 ……10\n附录 ……11",
                       4: "第一章 合同的成立"})
    run("check _selftest b001")
    m = json.loads((JOB / "manifest.json").read_text(encoding="utf-8"))
    check(m["batches"]["b001"]["status"] == "done", "b001 质检通过")
    check(len(m["figures"]) == 1 and m["figures"][0]["id"] == "f001", "坏图记录被过滤，好图入账")

    write_empty("b002")
    o = run("check _selftest b002", expect_fail=True)
    check("缺页标记[5" in o, "空文件先被页对账拦下（b002）")
    run("retry _selftest b002")
    run("next _selftest")  # 重新派出 b002
    write_batch("b002", [5, 6, 7, 8], notes="① 王泽鉴：《民法思维》，第35页。\n② 测试脚注二。")
    run("check _selftest b002")
    run("next _selftest")                                    # 派出 b003
    write_batch("b003", [9, 10, 11, 12], low=True,           # 每页约 70 字 → 应被拦
                heads={10: "第二章 合同的效力", 11: "第三章 违约责任", 12: "附录"},
                figs=[{"page": 9, "rect": [0.25, 0.27, 0.85, 0.42], "kind": "photo",
                       "title": "目测图", "desc": "视觉路线目测坐标（故意写偏）"}])
    o = run("check _selftest b003", expect_fail=True)
    check("页均" in o, "图表页字数过低被拦（b003）")
    run("retry _selftest --all-failed")
    run("next _selftest")
    o = run("check _selftest b003 --force")                 # 图表页 --force 放行
    check("质检通过" in o, "b003 --force 放行")
    m = json.loads((JOB / "manifest.json").read_text(encoding="utf-8"))
    corr = [f for f in m["figures"] if f.get("title") == "目测图"]
    check(len(corr) == 1 and corr[0].get("rect_src") == "pdf",
          "rect 交叉校正：目测坐标被 PDF 图块精确坐标替换（rect_src=pdf）")
    check(corr and abs(corr[0]["rect"][0] - 0.2) < 0.01 and abs(corr[0]["rect"][2] - 0.8) < 0.01,
          "校正后 rect 与第 9 页嵌入图块真值一致（x 0.2~0.8）")
    o = run("status _selftest")
    check("完成 3" in o, "status 显示 3 批全完成")

    print("== chapters 切章 + 目录对账 ==")
    o = run("chapters _selftest")
    m = json.loads((JOB / "manifest.json").read_text(encoding="utf-8"))
    ch = m["chapters"]
    check("切章完成：6 章" in o, "chapters 切出 6 章（版序/目录/三个正文章/附录）")
    check([c["t"] for c in ch] == ["第二版序", "目录", "第一章 合同的成立", "第二章 合同的效力",
                                   "第三章 违约责任", "附录"], "章表顺序与标题正确")
    check([c["p"] for c in ch] == [1, 2, 4, 10, 11, 12], "各章起始页正确（页号锚定）")
    check([c["r"] for c in ch] == ["FRONT", "TOC", "BODY", "BODY", "BODY", "BACK"],
          "章角色正确（前置/目录/正文/后置）")
    check(ch[2]["a"] == ch[2]["t"][:8] and ch[2]["lv"] == 1, "章锚=标题前8字、层级 1")
    check("命中 4" in o and "无疑点" in o, "目录 4 条目全部对账命中、无疑点")
    check(m.get("chapter_review_pending") is False, "无疑点时不需要裁决")

    print("== pack 三产出（txt / 插图版 PDF / KIT 图包 zip） ==")
    o = run("pack _selftest")
    txt = JOB / "output" / "_selftest.txt"
    pdf = JOB / "output" / "_selftest_插图版.pdf"
    zipf = JOB / "output" / "_selftest_Kit图包.zip"
    check(txt.exists() and "txt →" in o, "txt 产出")
    check(pdf.exists() and "插图版 PDF →" in o, "插图版 PDF 产出")
    check(zipf.exists() and "KIT 图包 →" in o, "KIT 图包 zip 产出")
    body = txt.read_text(encoding="utf-8-sig")
    check(re.search(r"〔页\d+〕", body) is not None, "txt 保留〔页N〕页标记行（App 端剥标记记页码）")
    check("王泽鉴" in body and "注释" in body, "脚注归入书末注释区")
    check("〔脚注〕① 王泽鉴" in body and "\n\n〔脚注〕②" in body,
          "注释区每条脚注独立成段带〔脚注〕前缀（App 端剥标落 FOOTNOTE，AI 全跳过）")
    check("〔图：表3-1 测试表格〕" in body, "半角冒号图占位行经 pack 归一化为全角规范形态")
    check("表3-1" in body, "图描述保留在 txt 正文")
    mm = re.search(r"〔KIT-书 开始〕\r?\n(.*?)\r?\n〔KIT-书 结束〕", body, re.S)
    check(mm is not None, "txt 头部有 KIT 元数据块")

    import zipfile as _zf
    with _zf.ZipFile(zipf) as z:
        names = z.namelist()
        kjson = json.loads(z.read("kit.json").decode("utf-8"))
        book_in_zip = z.read("book.txt").decode("utf-8-sig")
    check("kit.json" in names and "book.txt" in names, "图包含 kit.json 与 book.txt")
    check(kjson.get("kit") == 1 and kjson.get("v") == "bookocr-kit 1.2", "kit.json 协议版本正确")
    check(kjson.get("chapters") and kjson["chapters"][0]["t"] == "第二版序", "kit.json 带章表")
    figs_in_zip = kjson.get("figures") or []
    check(any(f.get("file", "").startswith("figures/") for f in figs_in_zip),
          "kit.json figures 清单带 file 路径")
    check(any(f.get("w") for f in figs_in_zip), "figure-desc/裁图回填的宽高进了 kit.json")
    fig_files = [n for n in names if n.startswith("figures/")]
    check(len(fig_files) >= 1, f"图包内嵌真图文件（{len(fig_files)} 张）")
    check(book_in_zip.replace("\r\n", "\n") == body.replace("\r\n", "\n"),
          "book.txt 与产出 txt 内容一致（忽略换行符差异）")
    meta = json.loads(mm.group(1)) if mm else {}
    check(meta.get("kit") == 1 and meta.get("title") == "_selftest", "元数据 JSON 可解析、书名正确")
    check(meta.get("v") == "bookocr-kit 1.2", "txt 元数据 v 与 zip kit.json 同版本串")
    check(len(meta.get("chapters") or []) == 6 and meta["chapters"][0]["r"] == "FRONT",
          "元数据含 6 章章表（首章为前置）")
    mpk = json.loads((JOB / "manifest.json").read_text(encoding="utf-8"))
    check(mpk.get("packed") is True, "pack 后台账记 packed")
    check("已打包" in run("status _selftest"), "status 显示已打包")

    d = fitz.open(str(pdf))
    check(d.page_count >= 3, f"PDF 排版 {d.page_count} 页")
    img_pages = [i + 1 for i in range(d.page_count) if d[i].get_images()]
    check(len(img_pages) >= 1, f"PDF 嵌入插图（第 {img_pages} 页）")
    alltext = "".join(d[i].get_text() for i in range(d.page_count))
    check("要约" in alltext and "第9页测试0" in alltext, "PDF 文字层可抽取、含正文")
    d.close()

    print("== pull 文字层拔取（多批台账一致性回归） ==")
    run(f"init _selftest_pull --src {src} --batch 4")
    JOB2 = KIT / "jobs" / "_selftest_pull"
    run("prep _selftest_pull")
    o = run("pull _selftest_pull")
    mp = json.loads((JOB2 / "manifest.json").read_text(encoding="utf-8"))
    check(all(b["status"] == "done" for b in mp["batches"].values()),
          "pull 一次拔完 3 批全部 done（状态不被下一轮循环覆盖回退）")
    check("完成 3" in run("status _selftest_pull"), "pull 后 status 显示 3 批全完成")
    check("跨页缝合" in o, "pull 汇报跨页缝合数量")
    b1txt = (JOB2 / "batches" / "b001.txt").read_text(encoding="utf-8")
    b2txt = (JOB2 / "batches" / "b002.txt").read_text(encoding="utf-8")
    check("尤其是采用请求权基础的案例，其目的在于引发思考方向。" in b1txt,
          "跨页缝合：第4页末半句+第5页顶格续行拼回一句（跨批边界 b001|b002）")
    check("权基础的案例" not in b2txt, "缝合后续行从下一页移除（不残留半句）")
    check("除外也不影响。第6页测试一" not in b2txt, "下页首行缩进两字=新段首，不缝")
    check("第二章 跨页标题测试标题下" not in b2txt, "上页末行是标题，下页首行不缝进标题")
    check(mp["batches"]["b003"]["thin_pages"] == [11], "纯图版页记入 thin_pages（不再整批跳过）")
    b3txt = (JOB2 / "batches" / "b003.txt").read_text(encoding="utf-8")
    check("〔图版页〕" in b3txt and "〔图：第11页图版〕" in b3txt, "图版页占位写入正文")
    # 图占位行按 y 插段间：第 9 页嵌入图在段2/段3 之间 → 占位行夹在两段之间（非页尾堆放）
    l9 = b3txt.split("〔页9〕")[1].split("〔页")[0].strip().splitlines()
    l9 = [x for x in l9 if x.strip()]
    try:
        i_fig = next(i for i, x in enumerate(l9) if x.strip() == "〔图：第9页插图1〕")
        fig_ok = (i_fig == 2 and l9[1].startswith("第9页测试二") and l9[3].startswith("第9页测试三"))
    except StopIteration:
        fig_ok = False
    check(fig_ok, "pull 图占位行按 y 坐标插在段间（段2 后段3 前），非页尾")
    check("\n\n" in b3txt.split("〔页9〕")[1].split("〔图：第9页")[0],
          "pull 批文件段落间空行（App 端 splitBlocks 按空行分块，一段一块）")
    # figure-desc dry-run：只裁图不调 API，宽高/file 入账、desc 仍是占位
    o = run("figure-desc _selftest_pull --dry-run")
    check("dry-run" in o, "figure-desc --dry-run 不调 API")
    mp = json.loads((JOB2 / "manifest.json").read_text(encoding="utf-8"))
    fd = [f for f in mp["figures"] if f["page"] == 9]
    check(len(fd) == 1 and fd[0].get("w") and fd[0].get("file", "").startswith("figures/"),
          "dry-run 后裁图宽高与 file 入账")
    check(fd[0]["desc"] == "原书嵌入插图（坐标按原 PDF 图块精确提取）", "dry-run 不改 desc（仍占位待补语义）")
    check((JOB2 / "figures" / (fd[0]["file"].split("/")[-1])).exists(), "裁图文件落盘 figures/")
    o = run("patch _selftest_pull")
    check("b003" in o and "0011.png" in o, "patch 输出图版页任务卡（只含 p11）")
    mp = json.loads((JOB2 / "manifest.json").read_text(encoding="utf-8"))
    check(mp["batches"]["b003"]["thin_pages"] == [], "patch 派出后 thin_pages 清空（防重派）")

    print("== init 免渲染直 pull（懒渲染路线） ==")
    run(f"init _selftest_pull2 --src {src} --batch 4")
    JOB3 = KIT / "jobs" / "_selftest_pull2"
    run("pull _selftest_pull2")  # 不跑 prep，直接拔
    mp3 = json.loads((JOB3 / "manifest.json").read_text(encoding="utf-8"))
    check(all(b["status"] == "done" for b in mp3["batches"].values()),
          "init → pull 零渲染完成全批（文字层书不需要页图）")
    check(len(list((JOB3 / "images").glob("*.png"))) == 0, "一张页图都没渲染（省时省盘）")
    # 图版页挂账（thin_pages 未 patch）→ pack 先拦，防「〔图版页〕」占位混进成品
    o = run("pack _selftest_pull2 --no-pdf --auto-chapters", expect_fail=True)
    check("图版页没补描述" in o, "pack 拦截：图版页挂账（先 patch 再 pack）")
    mp3 = json.loads((JOB3 / "manifest.json").read_text(encoding="utf-8"))
    for b in mp3["batches"].values():
        b["thin_pages"] = []  # 模拟 patch 派卡完成（patch 发卡即清账防重派）
    (JOB3 / "manifest.json").write_text(
        json.dumps(mp3, ensure_ascii=False, indent=1), encoding="utf-8")
    o = run("pack _selftest_pull2 --no-pdf --auto-chapters")
    check("切章完成" in o and "txt →" in o, "pack --auto-chapters 一条龙：自动切章后打包")
    body2 = (JOB3 / "output" / "_selftest_pull2.txt").read_text(encoding="utf-8-sig")
    mm2 = re.search(r"〔KIT-书 开始〕\r?\n(.*?)\r?\n〔KIT-书 结束〕", body2, re.S)
    check(mm2 is not None, "pull 书 txt 也带头部元数据块")
    meta2 = json.loads(mm2.group(1)) if mm2 else {}
    check([c["t"] for c in meta2.get("chapters") or []] == ["第一章 总则", "第二章 跨页标题测试"],
          "pull 书自动切章正确（PDF 里的两个章标题）")

    print("== chapters 疑点与裁决（toc_miss/gap/thin + --apply） ==")
    run(f"init _selftest_ch2 --src {src} --batch 12")
    JOB4 = KIT / "jobs" / "_selftest_ch2"
    run("prep _selftest_ch2")
    run("next _selftest_ch2")
    parts = ["〔页1〕", "目录", "",
             "第一章 起点 ……2", "第二章 终点 ……5", "第四章 缺章 ……9", "",
             "〔页2〕", "第一章 起点", filler(2, 1), ""]
    parts += [x for p in (3, 4) for x in (f"〔页{p}〕", filler(p, 1), "")]
    parts += ["〔页5〕", "第二章 终点", filler(5, 1), ""]
    parts += [x for p in range(6, 12) for x in (f"〔页{p}〕", filler(p, 1), "")]
    parts += ["〔页12〕", "第五章 误切", "只有几个字。", ""]
    (JOB4 / "batches" / "b001.txt").write_text("\n".join(parts), encoding="utf-8")
    (JOB4 / "batches" / "b001_notes.txt").write_text("", encoding="utf-8")
    (JOB4 / "batches" / "b001_figures.json").write_text("[]", encoding="utf-8")
    run("check _selftest_ch2 b001 --force")
    run("chapters _selftest_ch2")
    m4 = json.loads((JOB4 / "manifest.json").read_text(encoding="utf-8"))
    kinds = sorted(w["kind"] for w in m4["chapter_warnings"])
    check("toc_miss" in kinds and "gap" in kinds and "thin" in kinds,
          "三类疑点齐出：目录缺章/章号断号/薄章")
    check(m4.get("chapter_review_pending") is True, "有疑点 → 待裁决")
    check((JOB4 / "chapters_review_task.txt").exists(), "裁决任务卡落盘")
    review = JOB4 / "chapters_review.txt"
    review.write_text(
        "toc_miss:9:第四章缺章 | ADD | 9 | 第四章 缺章\n"
        "thin:12:第五章误切 | DROP | 12 | 第五章 误切\n"
        "gap:3 | IGNORE\n"
        "gap:4 | IGNORE\n", encoding="utf-8")
    run(f"chapters _selftest_ch2 --apply {review}")
    m4 = json.loads((JOB4 / "manifest.json").read_text(encoding="utf-8"))
    titles = [c["t"] for c in m4["chapters"]]
    check("第四章 缺章" in titles, "ADD 裁决补进漏切章")
    check("第五章 误切" not in titles, "DROP 裁决删掉误切章")
    check(m4.get("chapter_review_pending") is False, "IGNORE 后疑点清零、可 pack")
    check(m4["chapter_rulings"].get("p9:第四章 缺章") == "ADD|第四章 缺章", "裁决持久入台账（重跑不丢）")

    print("== 清理 ==")
    shutil.rmtree(JOB, ignore_errors=True)
    shutil.rmtree(JOB2, ignore_errors=True)
    shutil.rmtree(JOB3, ignore_errors=True)
    shutil.rmtree(JOB4, ignore_errors=True)
    src.unlink(missing_ok=True)
    if FAILS:
        print(f"SELFTEST FAIL：{len(FAILS)} 项未过")
        sys.exit(1)
    print("ALL PASS")


if __name__ == "__main__":
    main()
