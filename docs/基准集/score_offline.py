# -*- coding: utf-8 -*-
"""基准集离线评分脚本（P1-④③）：模拟 App NotePlanner 输入结构，用测试 API（免费）
对「改写前/改写后」两版 prompt 逐段生成讲解，再由评委按 5 维度打分，产出对照数据。

用法：
  python score_offline.py gen <prompt文件> <输出jsonl> [--api 档案id] [--ids B01,B03,...]
  python score_offline.py judge <生成jsonl> <评委jsonl> [--api 档案id]
  python score_offline.py judge2 <改写前jsonl> <改写后jsonl> <评委jsonl>   # 高智商批量评委：一次 consult 评 4 条（2段×2版），走 pro 台账
  python score_offline.py report <改写前评委jsonl> <改写后评委jsonl>

输入结构对齐 App data/study/NotePlanner.kt textsForPrompt/buildUserJson 同构字段：
  gist/terms/why/context_prev/context_next/memos/paragraphs[{id,text}]
"""
import json
import sys
import time
import pathlib

sys.path.insert(0, r"C:\Agent\AImanager\tools")
from ai_client import chat  # noqa: E402

BASE = pathlib.Path(__file__).parent
BENCH = BASE / "法律讲解基准集.json"


def _argflag(name: str) -> str | None:
    """--flag value 形式参数提取（不在则 None）"""
    if name in sys.argv:
        return sys.argv[sys.argv.index(name) + 1]
    return None


def build_user_json(case: dict) -> str:
    paras = [{"id": 0, "text": case["paraText"]}]
    obj = {
        "gist": case.get("gist", ""),
        "terms": case.get("terms", ""),
        "why": case.get("why", ""),
        "context_prev": case.get("prev", ""),
        "context_next": case.get("next", ""),
    }
    if case.get("memos"):
        obj["memos"] = [
            {"quote": m.get("quote", ""), "text": m.get("text", "")} for m in case["memos"]
        ]
    obj["paragraphs"] = paras
    return json.dumps(obj, ensure_ascii=False)


def gen_one(system_prompt: str, case: dict, api_id: str | None = None) -> str:
    user = build_user_json(case)
    # chat 返回 (text, secs) 元组；glm 推理慢，timeout 提到 300s（默认 60s 大半超时）
    text, _ = chat(
        [{"role": "system", "content": system_prompt}, {"role": "user", "content": user}],
        timeout=300,
        api_id=api_id,
    )
    return text


def gen_main(prompt_path: str, out_path: str, api_id: str | None = None, ids: set | None = None) -> None:
    prompt_text = pathlib.Path(prompt_path).read_text(encoding="utf-8")
    bench = json.loads(BENCH.read_text(encoding="utf-8"))
    cases = [c for c in bench["cases"] if ids is None or c["id"] in ids]
    done_ids = set()
    out = pathlib.Path(out_path)
    if out.exists():
        for line in out.read_text(encoding="utf-8").splitlines():
            if line.strip():
                done_ids.add(json.loads(line)["id"])
    total = len(cases)
    for i, case in enumerate(cases):
        if case["id"] in done_ids:
            print(f"[skip] {case['id']} 已有")
            continue
        t0 = time.time()
        try:
            reply = gen_one(prompt_text, case, api_id)
        except Exception as e:
            print(f"[fail] {case['id']}: {e}")
            time.sleep(3)
            continue
        rec = {"id": case["id"], "type": case["type"], "reply": reply, "secs": round(time.time() - t0, 1)}
        with out.open("a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        print(f"[{i+1}/{total}] {case['id']} {rec['secs']}s {len(reply)}字")
        time.sleep(1.5)


JUDGE_SYSTEM = """你是法律教材讲解质量的评委。给你一段法律教材原文和一段 AI 生成的讲解，按五个维度各打 0~5 分（整数）：
- fidelity 忠实度：讲解与原文一致，不编造原文没有的含义、法条、数字；歪曲原文含义 0~1 分。
- clarity 可懂度：法学术语第一次出现是否给接地气解释；完全没解释术语 0~2 分；逐条解释 4~5 分。
- structure 一句话说透：开头一两句先把核心说透再展开；开头即铺垫空话 0~2 分；直给核心+有收拢 4~5 分。
- example 例子质量：例子具体（甲乙丙场景）、贴切、助记；无例子无类比 0 分；类比到位 4~5 分。
- coverage 覆盖度：原文要点都照顾到，多要件不漏；漏掉一半以上要点 0~1 分；有读者困惑批注时是否呼应（未呼应扣 1 分）。
只输出 JSON：{"fidelity":n,"clarity":n,"structure":n,"example":n,"coverage":n,"comment":"一句话总评"}"""


def judge_main(gen_path: str, out_path: str, api_id: str | None = None) -> None:
    bench = {c["id"]: c for c in json.loads(BENCH.read_text(encoding="utf-8"))["cases"]}
    out = pathlib.Path(out_path)
    done_ids = set()
    if out.exists():
        for line in out.read_text(encoding="utf-8").splitlines():
            if line.strip():
                done_ids.add(json.loads(line)["id"])
    for line in pathlib.Path(gen_path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        rec = json.loads(line)
        if rec["id"] in done_ids:
            print(f"[skip] {rec['id']}")
            continue
        case = bench[rec["id"]]
        user = (
            f"【教材原文】\n{case['paraText']}\n\n"
            f"【读者批注】{json.dumps(case.get('memos', []), ensure_ascii=False)}\n\n"
            f"【AI 讲解】\n{rec['reply']}"
        )
        t0 = time.time()
        try:
            reply, _ = chat(
                [{"role": "system", "content": JUDGE_SYSTEM}, {"role": "user", "content": user}],
                timeout=300,
                api_id=api_id,
            )
            scores = json.loads(extract_json(reply))
        except Exception as e:
            print(f"[fail] {rec['id']}: {e}")
            time.sleep(3)
            continue
        scores.update({"id": rec["id"], "type": rec["type"]})
        with out.open("a", encoding="utf-8") as f:
            f.write(json.dumps(scores, ensure_ascii=False) + "\n")
        total = sum(scores[k] for k in ("fidelity", "clarity", "structure", "example", "coverage"))
        print(f"{rec['id']} → {total}/25 ({round(time.time()-t0,1)}s) {scores.get('comment','')[:40]}")
        time.sleep(1.5)


JUDGE2_SYSTEM = """你是法律教材讲解质量的评委。一次给你多篇讲解（同一批教材段落，分属不同 prompt 版本），逐篇按五个维度各打 0~5 分（整数）：
- fidelity 忠实度：讲解与原文一致，不编造原文没有的含义、法条、数字；歪曲原文含义 0~1 分。
- clarity 可懂度：法学术语第一次出现是否给接地气解释；完全没解释术语 0~2 分；逐条解释 4~5 分。
- structure 一句话说透：开头一两句先把核心说透再展开；开头即铺垫空话 0~2 分；直给核心+有收拢 4~5 分。
- example 例子质量：例子具体（甲乙丙场景）、贴切、助记；无例子无类比 0 分；类比到位 4~5 分。
- coverage 覆盖度：原文要点都照顾到，多要件不漏；漏掉一半以上要点 0~1 分；有读者困惑批注时是否呼应（未呼应扣 1 分）。
只输出 JSON 数组，每篇一项：[{"id":"条目id","fidelity":n,"clarity":n,"structure":n,"example":n,"coverage":n,"comment":"一句话总评"}]，不要输出其他内容。"""


def judge2_main(old_path: str, new_path: str, out_path: str, ids: set | None = None) -> None:
    """高智商批量评委（pro_client.consult，走台账与限额闸）：每次评 4 条（2 段 × 改写前/改写后两版）。"""
    from pro_client import consult

    bench = {c["id"]: c for c in json.loads(BENCH.read_text(encoding="utf-8"))["cases"]}
    out = pathlib.Path(out_path)
    done_ids = set()
    if out.exists():
        for line in out.read_text(encoding="utf-8").splitlines():
            if line.strip():
                done_ids.add(json.loads(line)["id"])

    def load(p):
        recs = []
        for line in pathlib.Path(p).read_text(encoding="utf-8").splitlines():
            if line.strip():
                recs.append(json.loads(line))
        return recs

    # 配对：同段两版讲解相邻（old_B01, new_B01, old_B03, new_B03, ...）
    pairs = []
    old_recs = {r["id"]: r for r in load(old_path)}
    new_recs = {r["id"]: r for r in load(new_path)}
    for bid in sorted(old_recs.keys()):
        if bid in new_recs and bid not in done_ids and (ids is None or bid in ids):
            pairs.extend([old_recs[bid], new_recs[bid]])
    n_groups = (len(pairs) + 3) // 4
    print(f"待评 {len(pairs)} 条，分 {n_groups} 组（每组 4 条）")
    for g in range(0, len(pairs), 4):
        group = pairs[g : g + 4]
        # 组内呈现顺序：基名第 1 次出现=改写前、第 2 次=改写后（评委可能给 id 加 -1/-2 后缀，按此映射回）
        seq_no = {}
        expected = {}
        for r in group:
            n = seq_no.get(r["id"], 0)
            expected[f"{r['id']}-{n + 1}"] = ("old" if n == 0 else "new") + "_" + r["id"]
            expected[f"{r['id']}-v{n + 1}"] = ("old" if n == 0 else "new") + "_" + r["id"]
            expected[r["id"] if n == 0 else f"{r['id']}__new"] = ("old" if n == 0 else "new") + "_" + r["id"]
            seq_no[r["id"]] = n + 1
        parts = []
        for r in group:
            case = bench[r["id"]]
            parts.append(
                f"【条目 {r['id']} | 类型 {r['type']}】\n【教材原文】\n{case['paraText']}\n"
                f"【读者批注】{json.dumps(case.get('memos', []), ensure_ascii=False)}\n"
                f"【AI 讲解】\n{r['reply']}"
            )
        user = "\n\n====\n\n".join(parts)
        t0 = time.time()
        try:
            reply, secs = consult(
                [{"role": "system", "content": JUDGE2_SYSTEM}, {"role": "user", "content": user}],
                task="基准集讲解评分（P1-④③ 改写前后对照评委）",
            )
            arr = json.loads(extract_array(reply))
        except Exception as e:
            print(f"[fail] 组 {g//4+1}: {e}")
            continue
        with out.open("a", encoding="utf-8") as f:
            for d in arr:
                raw = str(d.get("id", ""))
                mapped = expected.get(raw) or expected.get(raw.split("-")[0])
                if mapped:
                    ver, bid = mapped.split("_", 1)
                    d["id"] = bid
                    d["ver"] = ver
                    d["type"] = bench[bid]["type"]
                    f.write(json.dumps(d, ensure_ascii=False) + "\n")
                else:
                    print(f"[warn] 评委 id 无法映射：{raw}（组内期望 {list(expected)}）")
        scored = {d.get("id"): sum(d[k] for k in ("fidelity", "clarity", "structure", "example", "coverage")) for d in arr if d.get("id")}
        print(f"组 {g//4+1}: {scored} ({round(time.time()-t0,1)}s)")
        time.sleep(2)


def extract_array(text: str) -> str:
    s, e = text.find("["), text.rfind("]")
    if s < 0 or e < 0:
        raise ValueError("no json array")
    return text[s : e + 1]


def extract_json(text: str) -> str:
    s, e = text.find("{"), text.rfind("}")
    if s < 0 or e < 0:
        raise ValueError("no json")
    return text[s : e + 1]


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "gen":
        ids = _argflag("--ids")
        gen_main(
            sys.argv[2],
            sys.argv[3],
            api_id=_argflag("--api"),
            ids=set(ids.split(",")) if ids else None,
        )
    elif cmd == "judge":
        judge_main(sys.argv[2], sys.argv[3], api_id=_argflag("--api"))
    elif cmd == "judge2":
        ids = _argflag("--ids")
        judge2_main(sys.argv[2], sys.argv[3], sys.argv[4], ids=set(ids.split(",")) if ids else None)
    elif cmd == "report":
        # 汇总两份评委 jsonl 出对照表
        rows = {}
        for tag, path in (("改写前", sys.argv[2]), ("改写后", sys.argv[3])):
            for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                d = json.loads(line)
                rows.setdefault(d["id"], {})[tag] = sum(d[k] for k in ("fidelity", "clarity", "structure", "example", "coverage"))
        keys = ["fidelity", "clarity", "structure", "example", "coverage"]
        agg = {tag: {k: [] for k in keys} for tag in ("改写前", "改写后")}
        for line in pathlib.Path(sys.argv[2]).read_text(encoding="utf-8").splitlines():
            d = json.loads(line)
            for k in keys:
                agg["改写前"][k].append(d[k])
        for line in pathlib.Path(sys.argv[3]).read_text(encoding="utf-8").splitlines():
            d = json.loads(line)
            for k in keys:
                agg["改写后"][k].append(d[k])
        print("维度均值（满分5）：")
        for k in keys:
            a = sum(agg["改写前"][k]) / max(1, len(agg["改写前"][k]))
            b = sum(agg["改写后"][k]) / max(1, len(agg["改写后"][k]))
            print(f"  {k}: {a:.2f} → {b:.2f} ({b-a:+.2f})")
        ta = sum(sum(v) for v in agg["改写前"]) / max(1, len(agg["改写前"] * 5))
        tb = sum(sum(v) for v in agg["改写后"]) / max(1, len(agg["改写后"] * 5))
        print(f"总分均值：{ta:.1f}/25 → {tb:.1f}/25 ({tb-ta:+.1f})")
        wins = sum(1 for r in rows.values() if r.get("改写后", 0) > r.get("改写前", 0))
        print(f"逐段胜负：改写后更好 {wins}/{len(rows)}")
    else:
        print("用法: gen <prompt> <out> | judge <gen> <out> | report <old_judge> <new_judge>")
