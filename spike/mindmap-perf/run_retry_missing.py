# -*- coding: utf-8 -*-
"""补跑缺档组合 + 关键组复测（加固版：组间喘息 5s、驱动重试 3 次）。

用法：python run_retry_missing.py
补跑清单：全矩阵 summary.json 里 status != OK 的组 + jank 复测组（cur/full n500 各 2 遍）。
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_spike1 as R  # 复用 adb/wait_report_for

RESULTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")

# 组间喘息从 1s 加到 5s；驱动 per-attempt 重试从 2 提到 3
R_INTER_CASE_SLEEP = 5.0


def main():
    summary_path = os.path.join(RESULTS, "summary.json")
    with open(summary_path, encoding="utf-8") as f:
        summary = json.load(f)
    missing = [(r["lib"], int(r["nodes"]), int(r["fold"])) for r in summary if r["status"] != "OK"]
    print("补跑缺档组:", missing)

    extra_rows = []
    for lib, nodes, fold in missing:
        row = R.run_case_with_retry(lib, nodes, fold, attempts=3)
        extra_rows.append(row)
        time.sleep(R_INTER_CASE_SLEEP)

    # jank 复测：cur 与 full 各抽 n500_f0 跑一遍（与矩阵数据并排比对，验证 full 高 jank 是否稳定）
    for lib in ("cur", "full"):
        row = R.run_case_with_retry(lib, 500, 0, attempts=3)
        row["note"] = "jank 复测"
        extra_rows.append(row)
        time.sleep(R_INTER_CASE_SLEEP)

    with open(os.path.join(RESULTS, "summary_retry.json"), "w", encoding="utf-8") as f:
        json.dump(extra_rows, f, ensure_ascii=False, indent=1)
    print("\n===== 补跑汇总 =====")
    for r in extra_rows:
        if r["status"] == "OK":
            print("%-5s %-5s f%s init=%-7s expand=%-7s p50=%s jk32=%s %s" % (
                r["lib"], r["nodes"], r["fold"], r["initMs"], r["expandMs"], r["p50"], r["jank32"], r.get("note", "")))
        else:
            print("%-5s %-5s f%s %s" % (r["lib"], r["nodes"], r["fold"], r["status"]))


if __name__ == "__main__":
    main()
