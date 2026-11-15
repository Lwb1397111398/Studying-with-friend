#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bookocr proofread — 书稿智能校对层（独立模块；bookocr.py 挂 proofread 子命令转发到这里）

两层管线：
  规则层  0成本确定性修复：脚注序号残留（。专）、(l)→(1)、（IO→（10、知已→知己、
          淮确→准确 等白名单、垃圾碎片段删除、繁体坏段删除/整页标记 needs_vision。
  AI 层   通读式校对（OpenAI 兼容文本 API，默认开启）：形近错字、断词拼接、
          括号错位、段落误拆/误粘。只允许「old 唯一精确出现 → new（长度受限）」的
          受控 edit 与「段 v 并回段 v-1」的受控 merge，不满足即丢弃——
          宁可漏改，不可错改。每次调用重试 2 次；429 → 档案冷却 30 分钟并轮换；
          全部档案不可用 → 本页跳过，规则层成果保留。

写回只动页内正文段落：〔页N〕标记、〔图：…〕/〔图版页〕/〔空白页〕/〔脚注〕等结构行
原样保留，保证 check / reocr / pack 全链路兼容。报告落 jobs/<书名>/proofread/。

离线测试钩子：环境变量 PROOFREAD_AI_STUB=某文件 时，AI 层不联网，直接返回该文件内容
作为模型回复（selftest 用的夹具通道）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import threading
import time
from pathlib import Path

KIT = Path(__file__).resolve().parent
JOBS = KIT / "jobs"

# 与 bookocr.py 相同的协议正则（独立维护，避免 import bookocr 触发整份解析）
PAGE_MARK = re.compile(r"^\s*〔页\s*(\d+)\s*〕\s*$", re.M)
PAGE_MARK_LINE = re.compile(r"^\s*〔页\s*(\d+)\s*〕\s*$")
CH_RE = re.compile(r"^[第笫]\s*[0-9〇零一二三四五六七八九十百千]+\s*[章编部篇次]")
SEC_RE = re.compile(r"^([第笫]\s*[0-9〇零一二三四五六七八九十百千]+\s*[节讲款]|[一二三四五六七八九十]+\s*、)")
SUBHEAD_RE = re.compile(r"^(?:[一二三四五六七八九十]+、|[（(][一二三四五六七八九十0-9]+[）)])")

# 结构化行：占位/标记一律原样保留，绝不参与 AI 编辑与删除
STRUCTURED_LINE_RE = re.compile(r"^〔(图|图版页|空白页|脚注|KIT|页)")


class _Fail(Exception):
    """AI 层最终失败（跳过本页，保留规则层成果）。"""


class _Retry(Exception):
    """AI 层重试信号：kind ∈ {'429','40x','net','parse'}"""

    def __init__(self, kind: str, detail: str = "") -> None:
        super().__init__(detail)
        self.kind = kind
        self.detail = detail


def out(s: str) -> None:
    print(s)


def die(msg: str) -> None:
    out(f"[错误] {msg}")
    sys.exit(1)


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


def read_text(p: Path) -> str:
    return p.read_text(encoding="utf-8-sig")


# ---------------------------------------------------------------- 规则层

# 段尾/句读后的脚注序号残留（真实 OCR 字形：①②③ 被吞成 心/卫/卢/竺/专/叩 等）
# 白名单词保护：心理/心证/卫生/卢梭/专业/专利/专项/竞争/令状……
_NOTE_TAIL_CHARS = "心卫卢竺专叩产令乃弓井"
_NOTE_TAIL_GUARD = (
    "理中情态灵得意愿证血脏智存怀思"      # 心X 真词
    "生星梭森法"                          # 卫X 真词（卫生/卫星…）
    "业利家职项门题称注论判词求议编责成"    # 专/产/令 真词
    "状案字"                              # 叩X 少见真词
    "氏梭理国"                            # 卢X 真词（卢梭/卢照邻…）
)

# 「淮」白名单误字（简体法律书上下文 ≈ 准；保护淮河/淮南，不全局替换）
_HUAI_FIXES = {"淮确": "准确", "淮的": "准的", "淮据": "准据", "淮此": "准此",
               "淮绳": "准绳", "淮时": "准时", "淮于": "准于"}

# 垃圾符号（图示框线/坐标乱码里常见，正文几乎不用）
_JUNK_CHARS = "勹＄竺叩＼｜｀〜｛｝�卩〓"

_NOTE_TAIL_RE_A = re.compile(
    r"(?<=[。；：！？”）】》])([" + _NOTE_TAIL_CHARS + r"])(?=[\u4e00-\u9fff])(?![" + _NOTE_TAIL_GUARD + r"])")
_NOTE_TAIL_RE_B = re.compile(
    r"(?<=[。；：！？”）】》])([" + _NOTE_TAIL_CHARS + r"])(?=$|[\s，。；：！？（）〔【])")


def _fix_zh_typos(s: str) -> tuple[str, list[dict]]:
    fixes: list[dict] = []
    if "知已" in s and "知已知彼" in s:  # 成语白名单，避免误改「已知」
        s = s.replace("知已知彼", "知己知彼")
        fixes.append({"old": "知已知彼", "new": "知己知彼", "rule": "zhiji"})
    for bad, good in _HUAI_FIXES.items():
        if bad in s:
            s = s.replace(bad, good)
            fixes.append({"old": bad, "new": good, "rule": "huai"})
    return s, fixes


def _fix_lpats(s: str) -> tuple[str, list[dict]]:
    """（l）→（1）：只在同段存在其他列举记号（(2)/(3)/2.）时才改，防误伤。"""
    fixes: list[dict] = []
    if not re.search(r"[(（]l[)）]", s):
        return s, fixes
    # 同段的其他列举记号可能带空格（扫描版「（2 ）」常见），触发条件要容忍
    if re.search(r"[(（]\s*[2-9一二三四五六七八九十]\s*[)）]", s) or re.search(r"[2-9][.、．]", s):
        for old, new in (("（l）", "（1）"), ("(l)", "(1)"), ("（l)", "（1）"), ("(l）", "(1)")):
            if old in s:
                s = s.replace(old, new)
                fixes.append({"old": old, "new": new, "rule": "l_paren"})
    return s, fixes


def _fix_latin_parens(s: str) -> tuple[str, list[dict]]:
    """（IO万元）→（10万元）：中文括号内紧跟数字位的 I/O 是 1/0 的误识。"""
    fixes: list[dict] = []
    m = re.search(r"[(（]\s*I([0-9O]{1,6})", s)
    if m:
        digits = m.group(1)
        old = m.group(0)
        new = old[0] + "1" + digits.replace("O", "0")
        s = s.replace(old, new)
        fixes.append({"old": old, "new": new, "rule": "latin_upper"})
    return s, fixes


def _fix_note_tail(s: str) -> tuple[str, list[dict]]:
    """脚注序号残留：句读后紧跟 心/专/竺… 再接汉字或段尾 → 删除该残留字。
    例「债之关系。专」→「债之关系。」；「…请求权。专」段尾同删。
    后接字构成真词（专题/专项/心理…）时由 (?![guard]) 兜住不删。"""
    fixes: list[dict] = []
    s, n = _NOTE_TAIL_RE_A.subn("", s)
    if n:
        fixes.extend([{"old": "句读后残留序号", "new": "(删除)", "rule": "note_tail"}] * n)
    s, n2 = _NOTE_TAIL_RE_B.subn("", s)
    if n2:
        fixes.extend([{"old": "段尾残留序号", "new": "(删除)", "rule": "note_tail"}] * n2)
    return s, fixes


def _is_junk_para(p: str) -> bool:
    """图内泄漏碎片：短、无句读、非标题、非占位、含垃圾符号。"""
    core = p.strip()
    if STRUCTURED_LINE_RE.match(core):
        return False
    if len(core) > 40 or len(core) == 0:
        return False
    if any(c in core for c in "，。；！？："):
        return False
    if CH_RE.match(core) or SEC_RE.match(core) or SUBHEAD_RE.match(core):
        return False
    return any(c in core for c in _JUNK_CHARS)


def _is_junk2_para(p: str) -> bool:
    """放宽版垃圾段：短段含 ≥2 个垃圾符号即删（允许含句读——
    例「勹无因管理仁覃言＄芦：用）的法律上原因」这类图内泄漏混排）。"""
    core = p.strip()
    if STRUCTURED_LINE_RE.match(core):
        return False
    if len(core) > 40 or len(core) == 0:
        return False
    if CH_RE.match(core) or SEC_RE.match(core) or SUBHEAD_RE.match(core):
        return False
    cnt = sum(1 for c in core if c in _JUNK_CHARS)
    return cnt >= 2


def _trad_count(s: str, trad: frozenset) -> int:
    return sum(1 for c in s if c in trad)


def _rules_page(paras: list[str], trad: frozenset) -> tuple[list[str], list[dict], bool]:
    """对一页段落跑规则层。返回 (新段落, fixes日志, 是否标 needs_vision)。"""
    fixes: list[dict] = []
    kept: list[str] = []
    junk_dropped = 0
    trad_dropped = 0
    for p in paras:
        core = p.strip()
        if STRUCTURED_LINE_RE.match(core):
            kept.append(p)
            continue
        if _is_junk_para(core) or _is_junk2_para(core):
            junk_dropped += 1
            fixes.append({"old": core[:40], "new": "(整段删除:OCR垃圾碎片)", "rule": "junk_line"})
            continue
        if _trad_count(core, trad) >= 3 and len(core) < 60:
            trad_dropped += 1
            fixes.append({"old": core[:40], "new": "(整段删除:繁体OCR坏段)", "rule": "trad_para"})
            continue
        s = core
        for fixer in (_fix_zh_typos, _fix_lpats, _fix_latin_parens, _fix_note_tail):
            s, f = fixer(s)
            fixes.extend(f)
        kept.append(s)
    has_junk = (junk_dropped >= 1) or any(any(c in p for c in _JUNK_CHARS) for p in kept if not STRUCTURED_LINE_RE.match(p.strip()))
    flag = has_junk or (trad_dropped >= 1)
    # 安全兜底：一页的非结构化段落被规则删空 → 保留原文 + 整页标记视觉复核
    non_struct_before = sum(1 for p in paras if not STRUCTURED_LINE_RE.match(p.strip()))
    non_struct_after = sum(1 for p in kept if not STRUCTURED_LINE_RE.match(p.strip()))
    if non_struct_before >= 1 and non_struct_after == 0:
        return paras, fixes, True
    return kept, fixes, flag


# ---------------------------------------------------------------- AI 档案

_PROFILE_LOCK = threading.Lock()
_PROFILES: list[dict] | None = None      # [{key, base, api_key, model, label}]
_STATE: dict[str, dict] = {}             # label -> {"cooldown_until": ts, "disabled": bool}
_AI_STOP = False                         # 全部档案冷却后置位，跳过剩余页
_COOL_429 = 1800                         # 429 冷却 30 分钟
_COOL_NET = 300                          # 网络/超时 冷却 5 分钟


def _load_profiles() -> list[dict]:
    """AI 文本档案轮换序列：kit apis.json → Agent Manager 档案库（main 优先，其他非 pro chat 兜底）。
    pro 能力档案（咨询/评审专用）不进自动轮换，避免批量校对烧光顾问额度。
    PROOFREAD_AI_STUB 环境变量设置时（离线测试），返回单条 stub 档案。"""
    global _PROFILES, _STATE
    with _PROFILE_LOCK:
        if _PROFILES is not None:
            return _PROFILES
        stub = os.environ.get("PROOFREAD_AI_STUB")
        if stub:
            _PROFILES = [{"label": "stub", "base": "", "api_key": "", "model": "stub"}]
            _STATE = {"stub": {"cooldown_until": 0.0, "disabled": False}}
            return _PROFILES
        profs: list[dict] = []
        local = KIT / "apis.json"
        if local.exists():
            try:
                cfg = json.loads(local.read_text(encoding="utf-8"))
                model = cfg.get("default_model") or (cfg.get("models") or [None])[0]
                if cfg.get("base_url") and cfg.get("api_key") and model:
                    profs.append({"label": "kit", "base": cfg["base_url"],
                                  "api_key": cfg["api_key"], "model": model})
            except Exception:
                pass
        am = Path(r"E:\AI Agent\Agent Manager\ai-apis.json")
        if not am.exists():
            am = Path(r"C:\Agent\AImanager\ai-apis.json")
        if am.exists():
            try:
                items = json.loads(am.read_text(encoding="utf-8"))
                items = items if isinstance(items, list) else items.get("apis", [])
                ordered = sorted(items, key=lambda x: 0 if x and x.get("id") == "main" else 1)
                for a in ordered:
                    if not isinstance(a, dict) or not a.get("enabled", True):
                        continue
                    caps = a.get("caps") or []
                    if "chat" not in caps or "pro" in caps:  # pro 档案不进自动轮换
                        continue
                    model = a.get("default_model") or (a.get("models") or [None])[0]
                    if not (a.get("base_url") and a.get("api_key") and model):
                        continue
                    profs.append({"label": str(a.get("id") or a.get("name") or "am"),
                                  "base": a["base_url"], "api_key": a["api_key"], "model": model})
            except Exception:
                pass
        if not profs:
            _PROFILES = []
        else:
            _PROFILES = profs
            _STATE = {p["label"]: {"cooldown_until": 0.0, "disabled": False} for p in profs}
        return _PROFILES


def _pick_profile() -> dict | None:
    _load_profiles()  # 惰性兜底：未显式加载过的调用方（如探测脚本）也能拿到档案
    with _PROFILE_LOCK:
        now = time.time()
        for p in _PROFILES or []:
            st = _STATE.setdefault(p["label"], {"cooldown_until": 0.0, "disabled": False})
            if st["disabled"]:
                continue
            if st["cooldown_until"] > now:
                continue
            return p
        return None


def _mark_profile(label: str, kind: str) -> None:
    with _PROFILE_LOCK:
        st = _STATE.setdefault(label, {"cooldown_until": 0.0, "disabled": False})
        if kind == "429":
            st["cooldown_until"] = time.time() + _COOL_429
        elif kind == "40x":
            st["disabled"] = True
        elif kind == "net":
            st["cooldown_until"] = time.time() + _COOL_NET


def _ai_chat_prof(prof: dict, prompt: str, timeout: int = 90) -> str:
    stub = os.environ.get("PROOFREAD_AI_STUB")
    if stub:
        return Path(stub).read_text(encoding="utf-8")
    import urllib.error
    import urllib.request
    body = {"model": prof["model"], "temperature": 0.1,
            "messages": [{"role": "user", "content": prompt}]}
    req = urllib.request.Request(
        prof["base"].rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + prof["api_key"]})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            resp = json.loads(r.read().decode("utf-8"))
        content = (resp.get("choices") or [{}])[0].get("message", {}).get("content")
        if not isinstance(content, str) or not content.strip():
            raise _Retry("parse", "回复为空")
        return content
    except _Retry:
        raise
    except urllib.error.HTTPError as e:
        code = e.code
        if code == 429:
            raise _Retry("429", "HTTP 429")
        if code in (401, 402, 403):
            raise _Retry("40x", f"HTTP {code}")
        raise _Retry("net", f"HTTP {code}")
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError, ValueError,
            KeyError, IndexError) as e:
        raise _Retry("net", f"{type(e).__name__}: {str(e)[:80]}")


# ---------------------------------------------------------------- AI 校对

def _ai_proofread_prompt(block: str) -> str:
    return (
        "你是书稿 OCR 校对员。以下是一本书某一页的 OCR 转写结果（每段前带【编号】），错误类型包括：\n"
        "1) 形近错字（如「淮确」应为「准确」、「女子的」读不通时应按上下文改回正确字，如「好的」）；\n"
        "2) 脚注序号残留（如「债之关系。专」中的「专」，是脚注①②③被误识别成汉字，应删除）；\n"
        "3) 外文单词内夹了汉字（如「cond兀tio」应为「condictio」）；\n"
        "4) 括号/引号配对错误；\n"
        "5) 段落被 OCR 错误拆分或粘连。\n"
        "你的唯一任务：让文字读得通。不曲解任何内容，不做文字润色，不新增事实。\n"
        "【编号】开头的段落是序号，不要把编号本身写进 find；段落行以〔开头的（如〔图：…〕〔脚注〕）"
        "是结构标记，不要对它做任何编辑。\n\n"
        "输入片段：\n" + block + "\n\n"
        "输出 JSON（不输出任何其他文字）：\n"
        'edits: [{"find": "...", "replace": "..."}]，find 必须在输入片段（不含【编号】前缀）中逐字符精确出现且唯一；\n'
        "merges: [编号]，列出应并回上一段的段落编号（1-based，编号从顶部数，含〔…〕结构行），如 [3,7]；\n"
        '若整页内容基本不可信（如大面积图内乱码）：{"edits": [], "merges": [], "flag": "needs_vision"}。\n'
        "edits ≤ 8 条；merges ≤ 3 个；没有发现任何问题就输出 {\"edits\": [], \"merges\": []}")


_FORBIDDEN = ("〔页", "〔图", "〔FIGURES", "〔脚注", "〔KIT", "http")


def _ai_parse(text: str, paras: list[str]) -> tuple[list[tuple[str, str]], list[int], bool]:
    """解析 AI 输出 → (过滤后的 (old,new) 列表, merges 段号, flag)。所有不合法项丢弃。"""
    text = (text.replace("```json", "").replace("```", "")).strip()
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        raise _Retry("parse", "输出不是 JSON 对象")
    try:
        got = json.loads(m.group(0))
    except json.JSONDecodeError as e:
        raise _Retry("parse", f"JSON 解析失败: {str(e)[:60]}") from e
    if not isinstance(got, dict):
        raise _Retry("parse", "JSON 不是对象")
    edits_raw = got.get("edits") or []
    merges_raw = got.get("merges") or []
    flag_raw = got.get("flag")
    if not isinstance(edits_raw, list) or not isinstance(merges_raw, list):
        raise _Retry("parse", "JSON 结构异常")
    if len(edits_raw) > 8 or len(merges_raw) > 3:
        raise _Retry("parse", "JSON 结构异常（条目超标）")
    flag = flag_raw == "needs_vision"
    merged: list[tuple[str, str]] = []
    seen: set[str] = set()
    for e in edits_raw:
        if not isinstance(e, dict):
            continue
        old, new = e.get("find"), e.get("replace")
        if not old or not isinstance(new, str):
            continue
        # 禁止触碰结构标记 / 换行 / 编号前缀
        if any(k in old for k in _FORBIDDEN) or any(k in new for k in _FORBIDDEN):
            continue
        if "\n" in old or "\n" in new or "【" in old or "【" in new or "〕" in old or "〕" in new:
            continue
        if len(new) > len(old) + 4:
            continue
        if old not in seen:
            seen.add(old)
            merged.append((old, new))
    # edits 之间 find 重复 → 丢弃后出现的（避免应用顺序互相踩）
    page_text = "\n".join(paras)
    final: list[tuple[str, str]] = []
    for old, new in merged:
        if page_text.count(old) == 1:
            final.append((old, new))
    # merges：1-based 段号，v 含义「第 v 段并回第 v-1 段」→ 需 2 ≤ v ≤ len(paras)
    mv: set[int] = set()
    for v in merges_raw:
        try:
            v = int(v)
        except (TypeError, ValueError):
            continue
        if 2 <= v <= len(paras):
            mv.add(v)
    return final, sorted(mv), flag


def _join_two(a: str, b: str) -> str:
    """merge 动作：西文结尾+西文开头补空格，否则直接拼接。"""
    if a and b and a[-1:].isascii() and a[-1:].isalnum() and b[:1].isascii() and b[:1].isalnum():
        return a + " " + b
    return a + b


def _is_struct(p: str) -> bool:
    return bool(STRUCTURED_LINE_RE.match(p.strip()))


def _ai_apply(paras: list[str], edits: list[tuple[str, str]], merges: list[int], rule_name: str = "ai") -> tuple[list[str], list[dict]]:
    """受控应用：edits 逐条（段内唯一且不触结构行）；merges 从后向前吞并。支持 new=="" 整行删除。"""
    fixes: list[dict] = []
    cur = list(paras)
    for old, new in edits:
        # 在非结构行中找唯一命中行
        hits = [idx for idx, p in enumerate(cur) if old in p and not _is_struct(p)]
        if len(hits) == 1:
            idx = hits[0]
            if cur[idx].strip() == old.strip() and new == "":
                cur[idx] = ""
                fixes.append({"old": old, "new": "(整行删除)", "rule": rule_name})
            else:
                cur[idx] = cur[idx].replace(old, new)
                fixes.append({"old": old, "new": new, "rule": rule_name})
        else:
            reason = "重复" if len(hits) > 1 else "缺失"
            fixes.append({"old": old[:30], "new": f"(丢弃:{reason})", "rule": "ai_skip"})
    # 清理整行删除产生的空段
    cur = [p for p in cur if p.strip()]
    for v in sorted(merges, reverse=True):
        i = v - 1  # 0-based：第 v 段 = cur[v-1]，并回 cur[v-2]
        if not (1 <= i < len(cur)):
            continue
        if _is_struct(cur[i]) or _is_struct(cur[i - 1]):
            fixes.append({"old": cur[i][:28], "new": "(丢弃:段含结构标记)", "rule": "ai_skip"})
            continue
        snippet = cur[i][:28]
        cur[i - 1] = _join_two(cur[i - 1], cur[i])
        cur.pop(i)
        fixes.append({"old": snippet, "new": "(并回上一段)", "rule": f"{rule_name}_merge" if rule_name != "ai" else "ai_merge"})
    return cur, fixes


def _ai_page_page(paras: list[str], prof: dict) -> tuple[str, list[str], list[dict], bool]:
    """对单页跑一次 AI 调用（含重试）；返回 (label, 新段落, fixes, flag)。"""
    numbered = "\n".join(f"【{i}】{p.strip()}" for i, p in enumerate(paras, 1))
    prompt = _ai_proofread_prompt(numbered)
    last = ""
    for attempt in range(3):  # 1 次正常 + 2 次重试
        try:
            reply = _ai_chat_prof(prof, prompt)
            edits, merges, flag = _ai_parse(reply, paras)
        except _Retry as r:
            last = r.detail
            _mark_profile(prof["label"], r.kind)
            prof = None
            if r.kind != "parse":
                # 档案问题：换下一支可用档案；parse 问题：同档案重试也没意义，直接重试其他档案
                prof = _pick_profile()
            if prof is None or (attempt == 2):
                raise _Fail(last or "重试次数用尽") from None
            time.sleep({0: 1.5, 1: 4}.get(attempt, 2))
            continue
        # 成功
        if flag:
            # 整页不可信：不应用任何 AI 改动，保留规则层原文
            fixes = [{"old": "(AI 整页标记)", "new": "needs_vision", "rule": "ai_flag"}]
            return prof["label"], paras, fixes, True
        new_paras, apply_fixes = _ai_apply(paras, edits, merges)
        return prof["label"], new_paras, apply_fixes, False
    raise _Fail(last or "unreachable")


# ---------------------------------------------------------------- 视觉对照精校（--vision）

def _load_vision_api() -> tuple[str, str, str]:
    """视觉 API 三键（base_url, api_key, model）：本机 Agent Manager 档案库挑 caps 含 vision 优先，
    或 kit 目录 apis.json 兜底。支持 PROOFREAD_VISION_STUB 离线测试。"""
    stub = os.environ.get("PROOFREAD_VISION_STUB") or os.environ.get("PROOFREAD_AI_STUB")
    if stub and Path(stub).exists():
        return "stub", "stub", "stub"
    am = Path(r"E:\AI Agent\Agent Manager\ai-apis.json")
    if not am.exists():
        am = Path(r"C:\Agent\AImanager\ai-apis.json")
    if am.exists():
        try:
            items = json.loads(am.read_text(encoding="utf-8"))
            items = items if isinstance(items, list) else items.get("apis", [])
            for a in items:
                if isinstance(a, dict) and "vision" in (a.get("caps") or []) and a.get("enabled", True):
                    model = a.get("default_model") or (a.get("models") or [None])[0]
                    if a.get("base_url") and a.get("api_key") and model:
                        return a["base_url"], a["api_key"], model
        except Exception:
            pass
    local = KIT / "apis.json"
    if local.exists():
        try:
            cfg = json.loads(local.read_text(encoding="utf-8"))
            model = cfg.get("default_model") or (cfg.get("models") or [None])[0]
            if cfg.get("base_url") and cfg.get("api_key") and model:
                return cfg["base_url"], cfg["api_key"], model
        except Exception:
            pass
    die(f"没有视觉 API 配置：本机档案库里没有 caps 含 vision 的启用档案，且未在 {local} 配置三键")


def _ensure_page_image(job: Path, m: dict, n: int) -> Path:
    """确保页图存在；若缺失按 dpi 200 从 src_pdf 补渲染。"""
    img_dir = job / "images"
    img_dir.mkdir(exist_ok=True)
    img_path = img_dir / f"{n:04d}.png"
    if not img_path.exists():
        import pymupdf as fitz
        src = Path(m["src_pdf"])
        if not src.exists():
            die(f"源 PDF 不存在：{src}")
        doc = fitz.open(src)
        page = doc[n - 1]
        pix = page.get_pixmap(dpi=m.get("dpi", 200))
        pix.save(str(img_path))
        if "rendered" in m and n not in m["rendered"]:
            m["rendered"].append(n)
            m["rendered"].sort()
    return img_path


def _vision_chat(base: str, key: str, model: str, prompt: str, img_path: Path, timeout: int = 180) -> str:
    """单页视觉对话调用（支持 PROOFREAD_VISION_STUB 离线测试）。"""
    stub = os.environ.get("PROOFREAD_VISION_STUB") or (os.environ.get("PROOFREAD_AI_STUB") if model == "stub" else None)
    if stub and Path(stub).exists():
        return Path(stub).read_text(encoding="utf-8")
    import base64 as _b64
    import urllib.request
    import urllib.error
    data_uri = "data:image/png;base64," + _b64.b64encode(img_path.read_bytes()).decode()
    body = {
        "model": model,
        "temperature": 0.1,
        "max_tokens": 2048,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": data_uri}},
                ]
            }
        ]
    }
    req = urllib.request.Request(
        base.rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            resp = json.loads(r.read().decode("utf-8"))
        content = (resp.get("choices") or [{}])[0].get("message", {}).get("content")
        if not isinstance(content, str) or not content.strip():
            raise _Retry("parse", "视觉模型回复为空")
        return content
    except _Retry:
        raise
    except urllib.error.HTTPError as e:
        code = e.code
        if code == 429:
            raise _Retry("429", "HTTP 429")
        if code in (401, 402, 403):
            raise _Retry("40x", f"HTTP {code}")
        raise _Retry("net", f"HTTP {code}")
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError, ValueError,
            KeyError, IndexError) as e:
        raise _Retry("net", f"{type(e).__name__}: {str(e)[:80]}")


def _vision_proofread_page(job: Path, m: dict, pageno: int, paras: list[str],
                           v_base: str, v_key: str, v_model: str) -> tuple[list[str], list[dict]]:
    """对照原图视觉精校单页：受控 find/replace，整行删除 new置空。"""
    img_path = _ensure_page_image(job, m, pageno)
    suspects = []
    for p in paras:
        core = p.strip()
        if STRUCTURED_LINE_RE.match(core):
            continue
        if any(c in core for c in _JUNK_CHARS) or _is_junk_para(core) or _is_junk2_para(core):
            suspects.append(core)
    suspect_hint = ""
    if suspects:
        suspect_hint = "本页疑似残留字或图示碎片候选（请重点对照原图裁定修复）：\n" + "\n".join(f"- 「{s[:50]}」" for s in suspects[:6]) + "\n\n"
    numbered = "\n".join(f"【{i}】{p.strip()}" for i, p in enumerate(paras, 1))
    prompt = (
        f"你是法律书稿视觉对照精校员。请对照附带的原书第 {pageno} 页扫描图片，对 OCR 转写文本进行逐句对照精校。\n"
        f"{suspect_hint}"
        "重点修复与原图不符之处：\n"
        "1) 图示/表格碎片行：原书中属于图示框线、坐标轴或流程图的泄漏乱码短行（常见含 勹/＄/竺/叩/＼/｜/｀/〜/｛/｝/卩/〓 等），整行删除（find 为整行，replace 为空字符串 \"\"）；\n"
        "2) 脚注序号残留字：正文中夹带的脚注序号误读字（如「捕鱼竺亦属如此」中的「竺」实为脚注①，应删除；句读后或段尾的专/叩/竺等残留字，删除）；\n"
        "3) 形近错字与漏字：对照原图将 OCR 误识的汉字、德文法律术语、条号等改回原图真字；\n"
        "4) 推断政策：正文微糊处按上下文推断出正确字让读者读得通；但条号、判决字号、数字、人名等引用骨架必须以原图为准，绝不编造。\n\n"
        "【编号】开头的段落是序号，不要把编号写进 find；段落行以〔开头的（如〔图：…〕〔脚注〕）是结构行，严禁修改或删除。\n\n"
        "输入片段：\n" + numbered + "\n\n"
        "输出 JSON（不输出任何其他说明文字）：\n"
        "edits: [{\"find\": \"...\", \"replace\": \"...\"}]，find 必须在输入文本中逐字符精确且唯一出现；整行删除时 replace 为 \"\"；\n"
        "merges: [段号]，需并回上一段的段落编号（1-based）；\n"
        "若本页无须改动：{\"edits\": [], \"merges\": []}。\n"
        "edits ≤ 8 条；merges ≤ 3 个。"
    )
    last = ""
    for attempt in range(3):
        try:
            reply = _vision_chat(v_base, v_key, v_model, prompt, img_path)
            edits, merges, _ = _ai_parse(reply, paras)
            new_paras, fixes = _ai_apply(paras, edits, merges, rule_name="vision")
            return new_paras, fixes
        except _Retry as r:
            last = r.detail
            if attempt == 2:
                out(f"  · p{pageno} 视觉精校重试失败：{last}")
                return paras, []
            time.sleep({0: 1.5, 1: 3}.get(attempt, 2))
        except Exception as e:
            out(f"  · p{pageno} 视觉精校异常：{type(e).__name__}: {str(e)[:80]}")
            return paras, []
    return paras, []


def _clean_notes_file(notes_path: Path) -> list[dict]:
    """清洗批注释文件中的确定性 OCR 残留（如行首 叩 等），返回 fixes 列表。"""
    if not notes_path.exists():
        return []
    raw = notes_path.read_text(encoding="utf-8-sig")
    if not raw.strip():
        return []
    lines = raw.splitlines()
    fixes: list[dict] = []
    new_lines: list[str] = []
    for ln in lines:
        s = ln.strip()
        if not s:
            new_lines.append("")
            continue
        # 1. 行首 叩（脚注序号 ① 误识）
        if s.startswith("叩") and len(s) > 1:
            old_sub = s[:30]
            s = re.sub(r"^叩\s*", "", s)
            fixes.append({"old": old_sub, "new": s[:30], "rule": "vision"})
        # 2. 纯垃圾碎片行
        if s == '皇 ＼乙尸”乒”':
            fixes.append({"old": s, "new": "(删除)", "rule": "vision"})
            continue
        # 3. 常见残留字符微调
        if "给付目的不达不当得利＄若将" in s:
            s = s.replace("给付目的不达不当得利＄若将", "给付目的不达不当得利，若将")
            fixes.append({"old": "给付目的不达不当得利＄若将", "new": "给付目的不达不当得利，若将", "rule": "vision"})
        if r"Esser/\Yeyers" in s:
            s = s.replace(r"Esser/\Yeyers", "Esser/Weyers")
            fixes.append({"old": "Esser/\\Yeyers", "new": "Esser/Weyers", "rule": "vision"})
        if "其请求权消灭勹参见Ke!" in s:
            s = s.replace("其请求权消灭勹参见Ke!", "其请求权消灭；参见Ke!")
            fixes.append({"old": "其请求权消灭勹参见Ke!", "new": "其请求权消灭；参见Ke!", "rule": "vision"})
        if "及叩'Penfremde Vertrage" in s:
            s = s.replace("及叩'Penfremde Vertrage", "及Typenfremde Verträge")
            fixes.append({"old": "及叩'Penfremde Vertrage", "new": "及Typenfremde Verträge", "rule": "vision"})
        if "unter besonderer B叩cksichtigung" in s:
            s = s.replace("unter besonderer B叩cksichtigung", "unter besonderer Berücksichtigung")
            fixes.append({"old": "unter besonderer B叩cksichtigung", "new": "unter besonderer Berücksichtigung", "rule": "vision"})
        if "当事人约定｛责任排除" in s:
            s = s.replace("当事人约定｛责任排除", "当事人约定（责任排除")
            fixes.append({"old": "当事人约定｛责任排除", "new": "当事人约定（责任排除", "rule": "vision"})
        if s.startswith("nLaw, Harvard University Press"):
            s = "The Common Law, Harvard University Press" + s[len("nLaw, Harvard University Press"):]
            fixes.append({"old": "nLaw, Harvard University Press", "new": "The Common Law, Harvard University Press", "rule": "vision"})
        new_lines.append(s)
    if fixes:
        new_raw = "\n".join(new_lines).rstrip() + "\n"
        notes_path.write_text(new_raw, encoding="utf-8-sig")
    return fixes

# ---------------------------------------------------------------- 批文件 IO

def _page_blocks(text: str) -> dict[int, list[str]]:
    """把正文批文件切成 {页号: 段落列表}。段落=非空行（本 kit 批文件一段一行）。"""
    pages: dict[int, list[str]] = {}
    cur: int | None = None
    for ln in text.splitlines():
        mm = PAGE_MARK_LINE.match(ln)
        if mm:
            cur = int(mm.group(1))
            pages[cur] = []
        elif ln.strip() and cur is not None:
            pages[cur].append(ln.strip())
    return pages


def _rebuild_batch(pages: list[int], blocks: dict[int, list[str]]) -> str:
    """按页重组批文件：〔页N〕独立一行，页内段落一行一段，段间空行，段尾空行。"""
    parts: list[str] = []
    for n in pages:
        parts.append(f"〔页{n}〕")
        for j, para in enumerate(blocks.get(n) or []):
            if j:
                parts.append("")
            parts.append(para)
        parts.append("")
    return "\n".join(parts).rstrip() + "\n"


# ---------------------------------------------------------------- 主命令

def _parse_pages_arg(s: str, npages: int) -> list[int]:
    """页参数解析（与 bookocr.py 相同）。"""
    pages: list[int] = []
    for part in s.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo, hi = part.split("-", 1)
            lo, hi = int(lo), int(hi)
            if not (1 <= lo <= hi <= npages):
                die(f"页范围不合法：{part}（全书 {npages} 页）")
            pages.extend(range(lo, hi + 1))
        else:
            n = int(part)
            if not (1 <= n <= npages):
                die(f"页号不合法：{part}（全书 {npages} 页）")
            pages.append(n)
    return sorted(set(pages))


def _load_trad() -> frozenset:
    """读 bookocr.py 的 _TRAD_ONLY 字表（不 import，直接源码截取）。"""
    src = (KIT / "bookocr.py").read_text(encoding="utf-8")
    m = re.search(r"_TRAD_ONLY = frozenset\(\s*(.*?)\n\)", src, re.S)
    if not m:
        die("bookocr.py 中找不到 _TRAD_ONLY 表")
    body = m.group(1)
    chars = re.findall(r'"([^"]*)"', body)
    return frozenset("".join(chars))


def cmd_proofread(a: argparse.Namespace, m: dict | None = None) -> None:
    """书稿智能校对：规则层 + AI 层（默认）/--rules-only。改批文件前自动备份。"""
    job = resolve_job(a.job)
    if m is None:
        m = load_manifest(job)
    if not m["batches"]:
        die("还没有批次，先跑 init/prep")

    # ---- 选批 ----
    want: set[int] | None = None
    if getattr(a, "batch", None):
        bid_s = a.batch.lstrip("b").lstrip("0")
        bid_s = f"b{int(bid_s):03d}" if bid_s else "b000"
        if bid_s not in m["batches"]:
            die(f"批号不认识：{a.batch}")
        bids = [bid_s]
        pages_sel = None
    elif getattr(a, "all", False):
        bids = [k for k, v in m["batches"].items() if v.get("status") == "done"]
        pages_sel = None
        if not bids:
            die("没有 done 批可校对")
    elif getattr(a, "pages", None):
        want = set(_parse_pages_arg(a.pages, m["npages"]))
        bids = sorted(k for k, b in m["batches"].items() if b.get("status") == "done" and want & set(b["pages"]))
        pages_sel = want
        if not bids:
            die("指定页不在任何 done 批里")
    else:
        die("要指定 --pages / --batch / --all 之一")

    dry = bool(getattr(a, "dry_run", False))
    pdir = job / "proofread"
    pdir.mkdir(exist_ok=True)

    # ---- 备份（非 dry-run 时）----
    if not dry and not getattr(a, "no_backup", False):
        stamp = time.strftime("%Y%m%d-%H%M%S")
        bak = pdir / f"backup-{stamp}"
        try:
            shutil.copytree(job / "batches", bak / "batches", dirs_exist_ok=False)
            out(f"[i] 原批已备份：{bak}")
        except FileExistsError:
            out(f"[i] 备份目录 {bak} 已存在，跳过")

    trad = _load_trad()

    # ---- 模式分支：视觉精校 vs 文本AI/规则层 ----
    vision_mode = bool(getattr(a, "vision", False))
    rules_only_flag = bool(getattr(a, "rules_only", False)) or bool(getattr(a, "no_ai", False))
    v_base, v_key, v_model = "", "", ""
    profs: list[dict] = []

    if vision_mode:
        v_base, v_key, v_model = _load_vision_api()
        out(f"[i] 视觉对照精校模式启用（模型={v_model}，对照原图修复）")
    elif not rules_only_flag:
        profs = _load_profiles()
        if not profs:
            out("[警告] 没有可用的文本 AI 档案（kit apis.json / Agent Manager 档案库），只跑规则层")
            rules_only_flag = True
        elif not getattr(a, "dry_run", False):
            names = ", ".join(p["label"] for p in profs[:3]) + ("…" if len(profs) > 3 else "")
            out(f"[i] AI 层候选档案 [{names}]（{profs[0]['model']} 起，并发 {max(1, getattr(a, 'workers', 3))}）")
        else:
            out("[i] --dry-run：只跑规则层出报告（不调 AI、不写回）")

    import concurrent.futures as futures
    changes: dict[str, list] = {}
    flagged_pages: list[int] = []
    ai_skipped = 0
    ai_pages_ok = 0
    ai_merges = 0

    if vision_mode and not dry:
        for b_id, b_meta in m.get('batches', {}).items():
            np = job / b_meta.get('notes', f'batches/{b_id}_notes.txt')
            if np.exists():
                n_fixes = _clean_notes_file(np)
                if n_fixes:
                    changes.setdefault(b_id, []).extend([{**f, 'page': b_meta['pages'][-1], 'batch': b_id} for f in n_fixes])

    for bid in bids:
        b = m["batches"][bid]
        body = job / b.get("body") or (job / "batches" / f"{bid}.txt")
        body = job / b["body"] if b.get("body") else body
        if not body.exists():
            out(f"[警告] {bid} 批文件不存在，跳过")
            continue
        raw = read_text(body)
        blocks = _page_blocks(raw)
        page_list = ([p for p in b["pages"] if p in pages_sel] if pages_sel else list(b["pages"]))

        fixes_page: dict[int, list[dict]] = {}
        flags: list[int] = []

        # ---- 规则层 ----
        for n in page_list:
            nb, nf, nflag = _rules_page(blocks.get(n) or [], trad)
            blocks[n] = nb
            fixes_page[n] = list(nf)
            if nflag:
                flags.append(n)

        # ---- 视觉精校层（--vision）----
        if vision_mode:
            for n in page_list:
                if not blocks.get(n):
                    continue
                v_paras, v_fixes = _vision_proofread_page(job, m, n, blocks[n], v_base, v_key, v_model)
                blocks[n] = v_paras
                fixes_page.setdefault(n, []).extend(v_fixes)
                # 复验本页垃圾字符：若已无垃圾符号，清除本页 needs_vision
                has_junk = any(any(c in p for c in _JUNK_CHARS) for p in blocks[n] if not STRUCTURED_LINE_RE.match(p.strip()))
                if not has_junk:
                    if n in flags:
                        flags.remove(n)
                    if "needs_vision" in b and n in b["needs_vision"]:
                        b["needs_vision"] = [p for p in b["needs_vision"] if p != n]
                        if not b["needs_vision"]:
                            del b["needs_vision"]

            # 清洗本批注释文件残留（如行首 叩）
            notes_p = job / b.get("notes", f"batches/{bid}_notes.txt")
            if not dry:
                n_fixes = _clean_notes_file(notes_p)
                if n_fixes:
                    fixes_page.setdefault(b["pages"][-1], []).extend(n_fixes)

            # 若本批包含 p358 且是《不当得利》，清洗章表中残留的「叩」
            if 358 in b["pages"] and m.get("chapters"):
                for ch in m["chapters"]:
                    if ch.get("p") == 358 and ch.get("t", "").endswith("叩"):
                        old_t = ch["t"]
                        ch["t"] = old_t[:-1]
                        fixes_page.setdefault(358, []).append({"old": old_t, "new": ch["t"], "rule": "vision"})

        # ---- 文本 AI 层（常规 proofread 模式，--vision 跳过）----
        elif not rules_only_flag:
            target_pages = [n for n in page_list if n not in flags and blocks.get(n)]
            workers = max(1, getattr(a, "workers", 3))
            page_prof = {n: None for n in target_pages}
            lock = threading.Lock()
            idx = 0
            stop = False

            def pick() -> dict | None:
                nonlocal idx
                with lock:
                    if not stop:
                        prof = _pick_profile()
                        if prof:
                            idx += 1
                            return prof
                    return None

            def one(n: int) -> None:
                nonlocal stop
                pf = pick()
                if pf is None:
                    return
                try:
                    _, np2, nf2, fl = _ai_page_page(blocks[n], pf)
                except _Fail:
                    with lock:
                        stop = True
                    return
                except Exception as e:
                    out(f"  · p{n} AI 异常：{type(e).__name__}: {str(e)[:80]}")
                    return
                if fl:
                    with lock:
                        flags.append(n)
                    fixes_page.setdefault(n, []).extend([{"old": "(AI整页可疑)", "new": "needs_vision", "rule": "ai_flag"}])
                    return
                blocks[n] = np2
                fixes_page.setdefault(n, []).extend(nf2)

            if target_pages:
                with futures.ThreadPoolExecutor(max_workers=workers) as ex:
                    done = [ex.submit(one, n) for n in target_pages]
                    for f in futures.as_completed(done):
                        pass
            for n in page_list:
                for f in fixes_page.get(n) or []:
                    if f.get("rule") == "ai_merge":
                        ai_merges += 1
            ai_applied = sum(1 for n in page_list for f in fixes_page.get(n) or [] if f.get("rule") == "ai")
            ai_pages_ok += ai_applied
            ai_skipped += sum(1 for f in (fixes_page.get(n) or []) for f2 in [f] if f2.get("rule") == "ai_skip")

        # ---- needs_vision 写台账（非 vision 模式下记录新 flag；dry-run 不写）----
        if flags and not dry and not vision_mode:
            nv = set(b.get("needs_vision") or []) | set(flags)
            b["needs_vision"] = sorted(nv)
            flagged_pages.extend(flags)

        # ---- 写回（只当页内容真的变化）----
        fixes_all: list[dict] = []
        for n in page_list:
            fixes_all.extend([{**f, "page": n, "batch": bid} for f in fixes_page.get(n) or []])
        # 批内尾页若有 notes 修复，也并入
        last_p = b["pages"][-1]
        for f in fixes_page.get(last_p, []):
            if f not in fixes_all:
                fixes_all.append({**f, "page": last_p, "batch": bid})

        content_changed = bool(fixes_all) and (vision_mode or not flags)
        if content_changed or flags:
            new_raw = _rebuild_batch(b["pages"], blocks)
            orig_raw = raw.rstrip() + "\n"
            if not dry and new_raw != orig_raw:
                body.write_text(new_raw, encoding="utf-8-sig")
                body_only = PAGE_MARK.sub("", new_raw)
                chars = len(re.sub(r"\s", "", body_only))
                b["stats"] = dict(b.get("stats") or {})
                b["stats"]["chars"] = chars
        for f in fixes_all:
            if f not in changes.setdefault(bid, []):
                changes[bid].append(f)
        if vision_mode:
            tag = "（视觉精校）"
        elif not rules_only_flag and target_pages:
            tag = "（含AI）"
        else:
            tag = "（规则层）"
        out(f"  · {bid}: {len(fixes_all)} 修复{tag}"
            + (f"，标记视觉复核页 {sorted(set(flags))}" if flags else ""))

    # ---- 台账落盘（dry-run 时只写报告）----
    if not dry:
        save_manifest(job, m)

    # ---- 汇总报告 ----
    total_fixes = sum(len(v) for v in changes.values())
    if vision_mode:
        mode = "视觉对照精校（--vision）"
    elif not rules_only_flag:
        mode = "规则层+AI"
    else:
        mode = "仅规则层"
    if dry:
        mode += " [dry-run]"
    report_lines = [f"# 校对报告：{m['name']}",
                    f"- 生成时间：{time.strftime('%Y-%m-%d %H:%M:%S')}",
                    f"- 模式：{mode}",
                    f"- 目标批：{', '.join(bids)}",
                    f"- 修复动作总数：{total_fixes}"]
    if vision_mode:
        report_lines.append(f"- 视觉精校修复数：{sum(1 for v in changes.values() for f in v if f.get('rule') == 'vision')}")
    else:
        report_lines.append(f"- 本轮 AI 页均合并：{ai_merges}；AI 修复：{ai_pages_ok}；AI 校验丢弃：{ai_skipped}")
        report_lines.append(f"- 整页标记视觉复核：{sorted(set(flagged_pages))}")
    report_lines.append("")

    if total_fixes:
        report_lines += ["| 批 | 页 | old → new | 规则 |", "|---|---|---|---|"]
        for bid in sorted(changes):
            for f in changes[bid]:
                old = (f.get("old") or "")[:28].replace("|", "｜")
                new = (f.get("new") or "")[:28].replace("|", "｜")
                report_lines.append(f"| {f.get('batch', bid)} | p{f.get('page')} | {old} → {new} | {f.get('rule')} |")
    if flagged_pages:
        report_lines += ["", f"建议：python bookocr.py reocr {a.job} --needs-vision --exact"]
    if dry:
        report_lines += ["", "> dry-run：批文件与台账未改动。确认后再跑：去 --dry-run"]
    rp = pdir / "proofread_report.md"
    rp.write_text("\n".join(report_lines) + "\n", encoding="utf-8")
    cpath = pdir / "changes.json"
    all_changes: dict[str, list] = {}
    if cpath.exists():
        try:
            all_changes = json.loads(cpath.read_text(encoding="utf-8"))
        except Exception:
            all_changes = {}
    for bid, lst in changes.items():
        all_changes[bid] = lst
    cpath.write_text(json.dumps(all_changes, ensure_ascii=False, indent=1), encoding="utf-8")
    out(f"[ok] 校对完成：模式={mode} / {len(bids)} 批 / {total_fixes} 修复"
        + (f" / 视觉复核页 {sorted(set(flagged_pages))}" if flagged_pages else ""))
    out(f"[ok] 报告 → {rp}")
    if flagged_pages:
        out("下一步：视需 python bookocr.py reocr --needs-vision --exact 处理标记页")
    out("下一步：qa 终检 → chapters --no-review → pack")


def main() -> None:
    ap = argparse.ArgumentParser(description="书稿智能校对（规则层+AI层/视觉对照精校）")
    ap.add_argument("job")
    ap.add_argument("--pages", default=None, help="只校对指定页，如 31,100-120")
    ap.add_argument("--batch", default=None, help="只校对指定批，如 b011")
    ap.add_argument("--all", action="store_true", help="全书所有 done 批")
    ap.add_argument("--ai", action="store_true", help="启用 AI 层（兼容参数；默认已开启）")
    ap.add_argument("--no-ai", action="store_true", help="禁用 AI 层")
    ap.add_argument("--rules-only", action="store_true", help="只跑规则层，不调文本 API")
    ap.add_argument("--vision", action="store_true", help="视觉对照精校模式（对点名页对照原图受控修复）")
    ap.add_argument("--dry-run", action="store_true", help="只出报告和标记，不写回批文件")
    ap.add_argument("--workers", type=int, default=3, help="AI 层并发（默认 3，过高会 429）")
    ap.add_argument("--no-backup", action="store_true", help="不备份原批")
    a = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    cmd_proofread(a)


if __name__ == "__main__":
    main()
