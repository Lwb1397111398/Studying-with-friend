# -*- coding: utf-8 -*-
"""Spike① 驱动：跑全部组合（lib × nodes × fold），汇总 results/ 出表。

前置：模拟器在线（adb）、本 server.py 已在另一窗口跑着（或留空 SERVE 自动拉起）。
用法：python run_spike1.py [--quick]
  --quick 只跑 cur/lib 两版 ×1000 一档冒烟。
流程：adb 打开模拟器 Chrome → 探针自动 init → 等 phase=pre 报文 → adb swipe×4 →
      等 phase=final 报文（超时记 fail）→ 下一组合。最后打印汇总表。
"""
import glob
import json
import os
import subprocess
import sys
import time

# Windows GBK 控制台兜底：任何非 GBK 字符降级替换而不是崩
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "results")
PORT = 8123
URL_BASE = "http://10.0.2.2:%d" % PORT
SERIAL = "emulator-5554"

LIBS = ["cur", "full", "lite"]
NODES = [100, 500, 1000, 2000]


def adb(*args, timeout=30):
    cmd = ["adb", "-s", SERIAL] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def wait_report(phase, key, timeout_s=90):
    """轮询 results/ 等待匹配 phase 的最新报文文件。"""
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        for f in sorted(glob.glob(os.path.join(RESULTS, "report_*.json")), key=os.path.getmtime, reverse=True):
            try:
                with open(f, encoding="utf-8") as fh:
                    d = json.load(fh)
            except Exception:
                continue
            if d.get("phase") == phase and key in json.dumps(d, ensure_ascii=False):
                # 用 (lib,nodes,fold) 精确匹配当前组合
                return d, f
        time.sleep(1.0)
    return None, None


def wait_report_for(phase, lib, nodes, fold, since_ts, timeout_s=90):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        for f in sorted(glob.glob(os.path.join(RESULTS, "report_*.json")), key=os.path.getmtime, reverse=True):
            if os.path.getmtime(f) < since_ts:
                continue
            try:
                with open(f, encoding="utf-8") as fh:
                    d = json.load(fh)
            except Exception:
                continue
            if (d.get("phase") == phase and d.get("lib") == lib
                    and str(d.get("nodes")) == str(nodes) and str(d.get("fold")) == str(fold)):
                return d, f
        time.sleep(1.0)
    return None, None


def run_case(lib, nodes, fold):
    url = "%s/probe.html?lib=%s&nodes=%d&fold=%d" % (URL_BASE, lib, nodes, fold)
    print("== %s n=%d fold=%d == %s" % (lib, nodes, fold, url))
    before = time.time()
    # 系统 WebView 壳（与产品同引擎）；force-stop 保证每次全新加载。
    # 注意：adb shell 会在设备端再解析一次命令行，URL 的 & 必须用引号保护（否则
    # nodes/fold 参数被设备 shell 当作后台符/赋值吞掉，页面 URL 缺参全走默认值）。
    adb("shell", "am", "force-stop", "org.chromium.webview_shell")
    r = adb("shell", "am start -n org.chromium.webview_shell/.WebViewBrowserActivity -d '%s'" % url)
    if "Error" in (r.stdout or "") + (r.stderr or ""):
        print("  am start:", ((r.stdout or "") + (r.stderr or "")).strip()[:200])

    pre, _ = wait_report_for("pre", lib, nodes, fold, before, timeout_s=60)
    if pre:
        time.sleep(0.8)
        # 四向平移 swipe（覆盖采样窗口；窗口 5s，swipe 总耗 ~2.4s）
        w, h = 1080, 2400
        adb("shell", "input", "swipe", "700", "1200", "200", "1200", "250")
        time.sleep(0.4)
        adb("shell", "input", "swipe", "200", "1200", "700", "1200", "250")
        time.sleep(0.4)
        adb("shell", "input", "swipe", "540", "1600", "540", "800", "250")
        time.sleep(0.4)
        adb("shell", "input", "swipe", "540", "800", "540", "1600", "250")

    final, fpath = wait_report_for("final", lib, nodes, fold, before, timeout_s=90)
    if not final:
        print("  !! 超时未收到 final 报文")
        return {"lib": lib, "nodes": nodes, "fold": fold, "status": "TIMEOUT"}
    if final.get("error"):
        print("  !! 探针报错:", final["error"][:200])
        return {"lib": lib, "nodes": nodes, "fold": fold, "status": "ERROR", "error": final["error"][:200]}
    fps = final.get("fps", {})
    row = {
        "lib": lib, "nodes": nodes, "fold": fold, "status": "OK",
        "initMs": final.get("initMs"), "expandMs": final.get("expandMs"),
        "p50": fps.get("p50"), "p95": fps.get("p95"), "max": fps.get("max"),
        "jank32": fps.get("jank32"), "jank50": fps.get("jank50"),
        "heapMB": final.get("heapMB"), "domNodes": final.get("domNodes"),
        "window": final.get("window"),
    }
    print("  init=%sms expand=%s | fps p50=%s p95=%s max=%s jank=%s/%s | heap=%sMB dom=%s" % (
        row["initMs"], row["expandMs"], row["p50"], row["p95"], row["max"],
        row["jank32"], row["jank50"], row["heapMB"], row["domNodes"]))
    return row


def run_case_with_retry(lib, nodes, fold, attempts=2):
    """WebView 冷启动偶发子资源加载竞态：失败 force-stop 重跑一次。"""
    for i in range(attempts):
        r = run_case(lib, nodes, fold)
        if r["status"] == "OK":
            return r
        if i < attempts - 1:
            print("  -> 重试一次（attempt %d/%d）" % (i + 2, attempts))
            time.sleep(2.0)
    return r


def main():
    quick = "--quick" in sys.argv
    # 清掉旧报文防串档
    for f in glob.glob(os.path.join(RESULTS, "report_*.json")):
        os.remove(f)
    cases = []
    for lib in LIBS:
        for nodes in NODES:
            for fold in (0, 1):
                cases.append((lib, nodes, fold))
    if quick:
        cases = [("cur", 1000, 0), ("full", 1000, 0)]

    rows = []
    for lib, nodes, fold in cases:
        rows.append(run_case_with_retry(lib, nodes, fold))
        time.sleep(1.0)

    print("\n===== 汇总 =====")
    print("%-5s %-5s %-4s %-6s %-7s %-6s %-6s %-6s %-5s %-5s %-7s" % (
        "lib", "nodes", "fold", "init", "expand", "p50", "p95", "max", "jk32", "jk50", "heap"))
    for r in rows:
        if r["status"] == "OK":
            print("%-5s %-5s %-4s %-6s %-7s %-6s %-6s %-6s %-5s %-5s %-7s" % (
                r["lib"], r["nodes"], r["fold"], r["initMs"], r["expandMs"] or "-",
                r["p50"], r["p95"], r["max"], r["jank32"], r["jank50"], r["heapMB"]))
        else:
            print("%-5s %-5s %-4s %s" % (r["lib"], r["nodes"], r["fold"], r["status"]))
    with open(os.path.join(RESULTS, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    print("\n明细落盘 results/summary.json")


if __name__ == "__main__":
    main()
