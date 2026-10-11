#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bookocr-kit 自测：合成一本 12 页假书，把 init→prep→next→check→retry→pack 全链路
跑一遍并断言。不依赖任何真实样本，换电脑装好依赖即可回归。

跑法：python selftest.py   （全部通过输出 ALL PASS，任何失败退出码 1）
"""
from __future__ import annotations

import json
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
                low: bool = False, figs: list[dict] | None = None, notes: str = "") -> None:
    mk = marks if marks is not None else pages
    body = "\n\n".join(
        (f"〔页{p}〕\n" + ("〔图：表3-1 测试表格〕\n这是图的说明文字。" if (p == 3 and not low) else filler(p, 1 if low else 4)))
        for p in mk
    )
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
    doc = fitz.open()
    for i in range(12):
        page = doc.new_page()
        if i == 0:
            page.insert_textbox(fitz.Rect(72, 72, page.rect.width - 72, 120),
                                "第一章 总则", fontname="china-s", fontsize=16)
        if i == 2:  # 第 3 页画一块"插图"区
            page.draw_rect(fitz.Rect(page.rect.width * 0.1, page.rect.height * 0.55,
                                     page.rect.width * 0.9, page.rect.height * 0.85),
                           color=(0, 0, 0), fill=(0.2, 0.2, 0.2))
            page.insert_textbox(fitz.Rect(72, page.rect.height * 0.86, page.rect.width - 72, page.rect.height * 0.9),
                                "图3-1 测试插图", fontname="china-s", fontsize=10)
        page.insert_textbox(fitz.Rect(72, 140, page.rect.width - 72, page.rect.height - 90),
                            filler(i + 1, 6), fontname="china-s", fontsize=11)
    doc.save(str(src))
    doc.close()

    print("== init / prep ==")
    shutil.rmtree(JOB, ignore_errors=True)
    run(f"init _selftest --src {src} --batch 4")
    m = json.loads((JOB / "manifest.json").read_text(encoding="utf-8"))
    check(m["npages"] == 12, "init 记录 12 页")
    run("prep _selftest")
    m = json.loads((JOB / "manifest.json").read_text(encoding="utf-8"))
    check(len(m["pages"]) == 12, "prep 渲染 12 页")
    check(len(m["batches"]) == 3, "每批 4 页 → 3 批")
    check((JOB / "images" / "0003.png").exists(), "页图 0003.png 落盘")
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
                       "title": "越界", "desc": ""}])
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
    write_batch("b003", [9, 10, 11, 12], low=True)           # 每页约 70 字 → 应被拦
    o = run("check _selftest b003", expect_fail=True)
    check("页均" in o, "图表页字数过低被拦（b003）")
    run("retry _selftest --all-failed")
    run("next _selftest")
    o = run("check _selftest b003 --force")                 # 图表页 --force 放行
    check("质检通过" in o, "b003 --force 放行")
    o = run("status _selftest")
    check("完成 3" in o, "status 显示 3 批全完成")

    print("== pack 双产出 ==")
    o = run("pack _selftest")
    txt = JOB / "output" / "_selftest.txt"
    pdf = JOB / "output" / "_selftest_插图版.pdf"
    check(txt.exists() and "txt →" in o, "txt 产出")
    check(pdf.exists() and "插图版 PDF →" in o, "插图版 PDF 产出")
    body = txt.read_text(encoding="utf-8-sig")
    check("〔页" not in body, "txt 里无页标记残留")
    check("王泽鉴" in body and "注释" in body, "脚注归入书末注释区")
    check("表3-1" in body, "图描述保留在 txt 正文")

    d = fitz.open(str(pdf))
    check(d.page_count >= 3, f"PDF 排版 {d.page_count} 页")
    img_pages = [i + 1 for i in range(d.page_count) if d[i].get_images()]
    check(len(img_pages) >= 1, f"PDF 嵌入插图（第 {img_pages} 页）")
    alltext = "".join(d[i].get_text() for i in range(d.page_count))
    check("要约" in alltext and "第9页测试0" in alltext, "PDF 文字层可抽取、含正文")
    d.close()

    print("== 清理 ==")
    shutil.rmtree(JOB, ignore_errors=True)
    src.unlink(missing_ok=True)
    if FAILS:
        print(f"SELFTEST FAIL：{len(FAILS)} 项未过")
        sys.exit(1)
    print("ALL PASS")


if __name__ == "__main__":
    main()
