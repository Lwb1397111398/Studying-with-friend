#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
simcheck — 成品质量抽检：把识别产出的正文与 PDF 自带文字层做逐页相似度比对。

仅对带文字层的书有意义（纯扫描书没有参照系，跑不了）。只输出数字，不打印正文。
用法：python simcheck.py <书名> [批号...]   缺省批号 = 全部已质检通过的批
"""
import difflib
import json
import re
import sys
from pathlib import Path

KIT = Path(__file__).resolve().parent


def norm(t: str) -> str:
    # 去空白与标点，只比实字
    return re.sub(r"[\s，。、；：？！「」『』（）〔〕《》”“‘’\"'—…·．.，,;:!?()\-－—]", "", t)


def main() -> None:
    import pymupdf

    job = KIT / "jobs" / (sys.argv[1] if len(sys.argv) > 1 else die("用法: python simcheck.py <书名> [批号...]"))
    m = json.loads((job / "manifest.json").read_text(encoding="utf-8"))
    doc = pymupdf.open(m["src_pdf"])
    mark = re.compile(r"〔页\s*(\d+)\s*〕")

    bids = sys.argv[2:] or [k for k, v in m["batches"].items() if v["status"] == "done"]
    for bid in bids:
        b = m["batches"][bid]
        txt = (job / b["body"]).read_text(encoding="utf-8-sig") if (job / b["body"]).exists() else ""
        pos = [(mm.start(), int(mm.group(1))) for mm in mark.finditer(txt)]
        ratios = []
        for i, (s, n) in enumerate(pos):
            e = pos[i + 1][0] if i + 1 < len(pos) else len(txt)
            got = norm(txt[s:e])
            ref = norm(doc[n - 1].get_text())
            if len(got) > 200 and ref:  # 版权页/空白页跳过
                ratios.append(difflib.SequenceMatcher(None, got, ref).ratio())
        if ratios:
            print(f"{bid}: 正文页相似度均值 {sum(ratios)/len(ratios):.3f} (n={len(ratios)})")
    doc.close()


def die(msg: str):
    print(f"[错误] {msg}")
    sys.exit(1)


if __name__ == "__main__":
    main()
