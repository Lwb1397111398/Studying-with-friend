# 质检所有「已派出且正文文件已写出」的批：一行一批，幂等可重复跑。
# 用法：python check_ready.py <书名>
import json
import subprocess
import sys
from pathlib import Path

KIT = Path(__file__).resolve().parent


def main() -> None:
    name = sys.argv[1]
    job = KIT / "jobs" / name
    m = json.loads((job / "manifest.json").read_text(encoding="utf-8"))
    n = 0
    for bid, b in m["batches"].items():
        if b["status"] == "assigned" and (job / b["body"]).exists():
            r = subprocess.run(
                [sys.executable, str(KIT / "bookocr.py"), "check", name, bid],
                capture_output=True, text=True, encoding="utf-8", cwd=str(KIT),
            )
            lines = (r.stdout or "").strip().splitlines()
            print(lines[-1] if lines else f"{bid} 无输出")
            n += 1
    if not n:
        print("(无待检批)")


if __name__ == "__main__":
    main()
