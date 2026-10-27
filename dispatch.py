# 调度辅助：领 N 批任务卡，每批生成一个子代理 prompt 文件，主对话只见一行摘要。
# 用法：python dispatch.py <书名> [批数=3] [--note "补充提醒（追加到 prompt 末尾）"]
# 输出：一行，空格分隔的批号（如 b004 b005 b006）；无批可领输出 (无) 或 STOP+原因
import json
import re
import subprocess
import sys
from pathlib import Path

KIT = Path(__file__).resolve().parent


def prev_tail(name: str, bid: str) -> str:
    """上一批末段结尾（≤60字），供子代理校验批间衔接；无上一批返回空串。"""
    n = int(bid[1:])
    if n <= 1:
        return ""
    try:
        m = json.loads((KIT / "jobs" / name / "manifest.json").read_text(encoding="utf-8"))
        prev = m["batches"].get(f"b{n - 1:03d}")
        if not prev or prev.get("status") != "done":
            return ""
        body = KIT / "jobs" / name / prev["body"]
        paras = [ln.strip() for ln in body.read_text(encoding="utf-8-sig").splitlines()
                 if ln.strip() and not re.match(r"^〔页\d+〕$", ln.strip())]
        if not paras:
            return ""
        page = prev["pages"][-1]
        return f"上一批末段（页{page}）结尾是：「…{paras[-1][-60:]}」"
    except (OSError, KeyError, ValueError):
        return ""


def main() -> None:
    args = sys.argv[1:]
    name = args[0]
    n = 3
    note = ""
    i = 1
    while i < len(args):
        if args[i] == "--note":
            note = args[i + 1]
            i += 2
        else:
            n = int(args[i])
            i += 1
    spec = str(KIT / "spec.md")
    assigned = []
    for _ in range(n):
        r = subprocess.run(
            [sys.executable, str(KIT / "bookocr.py"), "next", name],
            capture_output=True, text=True, encoding="utf-8", cwd=str(KIT),
        )
        o = (r.stdout or "") + (r.stderr or "")
        mm = re.search(r"^批 (b\d{3}) ", o, re.M)
        if r.returncode != 0 or not mm:
            tail = "; ".join(o.strip().splitlines()[-2:])
            print(f"STOP {tail}" if not assigned else f"{' '.join(assigned)} (无更多批：{tail})")
            return
        bid = mm.group(1)
        header = (
            "你是书页转写员。先用 Read 工具读转写规范：\n"
            f"  {spec}\n"
            "严格按规范执行。然后按下面的任务卡，逐张 Read 页图并转写，\n"
            "用 Write 写出任务卡列出的三个文件（正文绝不贴进对话）。\n"
            "写完三个文件后，自查规范末尾的清单。\n"
            "只做本任务卡这一批：不要运行 bookocr.py / dispatch.py / stitch.py 的任何命令，"
            "不要领新批、不要动别的批次文件、不要修改任何程序代码。\n"
            "最后只回报一行统计（格式见任务卡末行），不要回报其他内容。\n"
            "\n—— 任务卡 ——\n"
        )
        pdir = KIT / "jobs" / name / "prompts"
        pdir.mkdir(exist_ok=True)
        txt = header + o.strip() + "\n"
        tail = prev_tail(name, bid)
        if tail:
            hint = (tail + "\n本批第一页开头若接续这个半句，照实转写，不要补造上文、不要改写该句开头。\n")
        elif bid == "b001":
            hint = "本批是全书第一批，无上文参照。\n"
        else:
            hint = "上一批尚未完成转写，无衔接参照，照常转写。\n"
        hint += ("转写完全部页后自查衔接：若首页开头与上文明显对不上（内容跳脱、疑似缺页），"
                 "在回报行末尾加「衔接异常：<一句话>」。\n")
        txt += "—— 衔接提示 ——\n" + hint
        if note:
            txt += f"\n—— 特别提醒 ——\n{note}\n"
        (pdir / f"{bid}.txt").write_text(txt, encoding="utf-8")
        assigned.append(bid)
    print(" ".join(assigned))


if __name__ == "__main__":
    main()
