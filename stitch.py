#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
stitch — 子代理/aivision 路线的跨页段落缝合（对齐 pull 路线能力）。

把相邻两页之间被页界切断的段落拼回上一页末段（段落归前页名下，与 pull 缝合产出一致，
App 端阅读不出现「请求|权基础」式句中断段）。子代理路线没有缩进/字号信息，
判据用纯文字启发，四重护栏防误拼：
  ① 下一页首段像标题/编号列表/图占位/空白标记 → 不拼；
  ② 上一页末段太短（<12字）、自身像标题、或纯表格行（末字符非汉字） → 不拼；
  ③ 上一页末段以终止标点（。！？；…”等）收尾 → 不拼；
  ④ 只在「上段末字符是汉字/顿逗冒/开引号」且「下段首字符是汉字」时才拼。

用法：python stitch.py <书名> [--apply]    缺省 dry-run 只报告不写盘
时机：全书批全部 done 之后、chapters 切章之前跑一次。
"""
import json
import re
import sys
from pathlib import Path

KIT = Path(__file__).resolve().parent

PAGE_MARK = re.compile(r"^\s*〔页\s*(\d+)\s*〕\s*$")
# 标题/列表/表图题：出现在「下页首段」位置时几乎必然是新段起点，绝不拼
HEAD_RE = re.compile(
    r"^(第\s*[0-9〇零一二三四五六七八九十百千]+\s*[章编部篇节讲]"
    r"|[一二三四五六七八九十]+\s*、"
    r"|[（(][0-9①-⑳一二三四五六七八九十]+\s*[）)、．.]"
    r"|[0-9①-⑳]+\s*[、．.]"
    r"|附录|目录|序言|前言|导论|结语|后记|参考文献|索引|注释|表\s*[0-9一二三四五六七八九十]|图\s*[0-9一二三四五六七八九十])"
)
# 上页末段允许收在这些字符上（=句子没说完，可以拼）：汉字、顿逗冒、开引号开括号
MERGE_OK_TAIL = re.compile(r"[\u4e00-\u9fff，、：：]")
MERGE_OK_HEAD = re.compile(r"^[\u4e00-\u9fff]")
TERMINAL = "。！？；…”』」）】》〕?!;.)]"


# 版权页/版本说明的元数据行（「定价：20.00 元」「ISBN 978-…」等）——行间不是段落关系，
# 且行尾常落汉字，会被误判成半句，出现即整段不拼
META_RE = re.compile(r"定价|ISBN|印张|开本|版次|印次|字数|书号|发行|经销|印刷|装订")


def split_pages(text: str) -> dict[int, list[str]]:
    """与 bookocr.split_pages 同口径：〔页N〕切段，非空行即段落。"""
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


def load(job: Path) -> tuple[dict, dict[int, list[str]], dict[int, str]]:
    m = json.loads((job / "manifest.json").read_text(encoding="utf-8"))
    pages: dict[int, list[str]] = {}
    page_bid: dict[int, str] = {}
    for bid in sorted(m["batches"]):
        b = m["batches"][bid]
        if b["status"] != "done":
            continue
        got = split_pages((job / b["body"]).read_text(encoding="utf-8-sig"))
        for n, paras in got.items():
            pages[n] = paras
            page_bid[n] = bid
    return m, pages, page_bid


def main() -> None:
    args = [a for a in sys.argv[1:] if a != "--apply"]
    apply = "--apply" in sys.argv
    if not args:
        print("用法: python stitch.py <书名> [--apply]")
        sys.exit(1)
    job = KIT / "jobs" / args[0]
    m, pages, page_bid = load(job)
    all_pages = sorted(pages)
    # 幂等标记：缝合过的页界记档，重跑跳过（多会话都会触发缝合，绝不能二次缝）
    mark_path = job / ".stitched.json"
    done_before = set()
    if mark_path.exists():
        try:
            done_before = set(json.loads(mark_path.read_text(encoding="utf-8")).get("merged", []))
        except (json.JSONDecodeError, OSError):
            done_before = set()
    merges: list[tuple[int, str, str]] = []  # (页n, 上段末尾样例, 下段开头样例)

    for n in all_pages[:-1]:
        nxt = n + 1
        if n in done_before or nxt not in pages:
            continue
        prev_paras, cur_paras = pages[n], pages[nxt]
        if not prev_paras or not cur_paras:
            continue
        a, b = prev_paras[-1], cur_paras[0]
        # 护栏①②：图占位/空白标记/标题/短段不拼
        if a.startswith("〔") or b.startswith("〔"):
            continue
        if HEAD_RE.match(a) or HEAD_RE.match(b) or META_RE.search(a):
            continue
        if len(a) < 12:
            continue
        # 护栏②续：末字符非汉字开头的段（数字页码/西文/标点收尾）多见于目录表行，不拼
        if not MERGE_OK_TAIL.match(a[-1]) or not MERGE_OK_HEAD.match(b):
            continue
        # 护栏③：终止标点收尾 = 句子已完，不拼
        if a[-1] in TERMINAL:
            continue
        merges.append((n, a[-14:], b[:14]))
        pages[n][-1] = a + b
        pages[nxt] = cur_paras[1:]

    if not merges:
        if apply:  # 0 处也要落标记，防止 chapters 反复触发
            mark_path.write_text(json.dumps(
                {"merged": sorted(done_before)}, ensure_ascii=False), encoding="utf-8")
        print("[ok] 无需缝合（0 处跨页断段）")
        return
    print(f"[i] 候选缝合 {len(merges)} 处：" + ("" if apply else "（dry-run，未写盘）"))
    for n, ta, tb in merges[:60]:
        print(f"  页{n}→{n + 1}: …{ta}｜{tb}…")
    if len(merges) > 60:
        print(f"  …（其余 {len(merges) - 60} 处略）")
    if not apply:
        return

    # 按批重写受影响的批文件（只动段落列表，页标记/页序照 manifest 原样）
    touched = {page_bid[n] for n, _, _ in merges}
    for bid in sorted(touched):
        b = m["batches"][bid]
        parts: list[str] = []
        for n in b["pages"]:
            parts.append(f"〔页{n}〕")
            for j, para in enumerate(pages.get(n) or ["〔空白页〕"]):
                if j:
                    parts.append("")
                parts.append(para)
            parts.append("")
        (job / b["body"]).write_text("\n".join(parts).rstrip() + "\n", encoding="utf-8")
    mark_path.write_text(json.dumps(
        {"merged": sorted(done_before | {n for n, _, _ in merges})}, ensure_ascii=False),
        encoding="utf-8")
    print(f"[ok] 已缝合 {len(merges)} 处并写回 {len(touched)} 个批文件（累计 {len(done_before) + len(merges)} 处）")


if __name__ == "__main__":
    main()
