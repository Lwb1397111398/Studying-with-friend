# P6c 扫描件 OCR 质量提升 · 总体计划案（含 Phase 0 详细执行案）

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:executing-plans 逐任务执行本计划；每任务完成即验证，进度用 checkbox 追踪。
> **评分纪律（老板 2026-10-05 指令）**：本计划案及后续每个小计划案、执行策略、关键代码，一律经 AI 评分员（§1 M0）打分，<90 分按建议修改循环至 ≥90 分才开工/合入。

**Goal**：扫描书导入的不可用页率从 87%（401/461 兜底）降到 ≤15%（兜底 ≤70 页），PC 实验先行、数据说话、老板闸门后移植。

**Architecture**：在 PC 上用 onnxruntime-python 逐位复刻手机 `PpOcrEngine` 管线（同模型文件、同预处理、同解码、同闸门），用 P6b 留档的 401 页手机原始行置信度（`.e2e/drafts_shpc/`）验证复刻成立；然后在实验台上做**差异消融**（缩图参数/识别输入口径/后处理口径/预处理/模型档位逐开关归因），用 P6a 真值页算 CER，产出推荐组合报老板拍板，再进 Phase 1 移植。

**Tech Stack**：PC `.e2e/p6a/venv`（Python 3.12.7 + onnxruntime 1.30.0 + rapidocr 3.9.2 + opencv 5.0.0.93 + pymupdf 1.28.2 + paddle2onnx 2.1.0，已装齐勿重装）；模型 PP-OCRv5 mobile ONNX 三件套（`.e2e/p6a/onnx/`，与 GitHub Release `ocr-models-v1` 逐字节一致）；评分通道 `C:\Agent\AImanager\tools\ai_client.py`（已验证连通，往返 1.8s）。

**开工依据**：`docs/plans/P6c-扫描件质量提升交付文档.md`（老板授权：质量优先，耗时/体积可让步）。本计划案是其 Phase 0 的落地细化 + 全程评分机制；Phase 1/2 只给路线，各自另立小计划案过评分后再执行。

---

## 0. 调查结论（2026-10-05 实测，全部 MEASURED，本计划的证据基础）

### 0.1 生产管线真实参数（读源码所得）

| 项 | 值 | 出处 |
| --- | --- | --- |
| 渲染 DPI | 140（PdfRenderer RENDER_MODE_FOR_PRINT，ARGB_8888） | `PdfPageRenderer.kt:79-81,35-47` |
| det 缩图 | limit=960 **max-side**：1351px 长边→960（丢 29% 像素）；/32 向下取整；无 padding | `OcrEngine.kt:67-72` |
| 归一化 | (x/255−0.5)/0.5，RGB CHW，det/rec 相同 | `OcrEngine.kt:78-83,110-115` |
| det 后处理 | 概率>0.3 → 8 连通 BFS → 轴对齐框（面积≥32，均值≥0.5）→ 四边扩 25%（min 2px）→ y/24 桶排序；**无 unclip、无多边形** | `OcrEngine.kt:159-206` |
| rec 输入 | 行图从原图裁剪（坐标×缩放比取整+钳制），**硬拉伸 48×320**（长宽比被破坏，无保比例、无补白），批 8 | `OcrEngine.kt:93-121` |
| CTC | 贪心；class0=blank；去重相邻→跳 blank；行置信度=**保留字符步概率均值**；空行 0f；越界字典索引跳字符不崩 | `OcrEngine.kt:209-237` |
| 页闸门 | 行置信度**上中位**（sorted()[n/2]）<0.85 或 <0.5 行占比>0.20 → LOW_CONF；另有 DIAGRAM 竖排规则 | `OcrImportRunner.kt:248-273,168` |
| 模型分发 | GitHub Release `ocr-models-v1`（det 4,748,769B + rec 16,517,247B + dict 74,012B + SHA256SUMS），SHA256 校验，落 `filesDir/ocr_models` | `OcrModelDownloader.kt:29-35` |

### 0.2 关键新发现（改写立项假设；除注明〔推断〕外均为 MEASURED，2026-10-05 本会话实测）

1. 〔MEASURED〕**「0.9751 是 v4 模型跑的」是错误记忆**：P6a 实跑脚本 `t3_run.py:27-31`、`t3_dval_run.py:21-25` 明确加载 **PP-OCRv5 mobile 同款 ONNX**（`onnx/ppocrv5-mobile-det.onnx` + `models/ppocrv5_dict.txt`）。〔推断，M2/M3 实证〕→ PC 0.9751 与手机 0.895 的差距**主要嫌疑从「模型档位」转为「管线配置差异」**（缩图口径、unclip、rec 拉伸 vs 保比例）；可能不换大模型就能救回一大截，换模型降级为备选。
2. 〔MEASURED〕det 后处理是**简化版**（轴对齐+25% 扩张，`OcrEngine.kt:159-206`）；rapidocr 标准是 minAreaRect+unclip_ratio=1.6+旋转裁剪，切行可能切坏字或裹进邻行。
3. 〔MEASURED〕rec 输入**硬拉伸 48×320**（`OcrEngine.kt:95-106`）；PaddleOCR 标准是保比例缩放+补白到批内最大宽。窄行被横向拉胖、宽行被压扁，都可能掉置信度。
4. 〔MEASURED〕手机页中位用的是**上中位**（`OcrImportRunner.kt:259` sorted()[n/2]），复刻时用 np.median 会差半个位置——必须逐位复刻。
5. 〔MEASURED〕P6b 留档可直接当对账基准：`.e2e/drafts_shpc/` 有 401 页 `d*.txt`（`text\tconf` 行）+ 24 张手机端渲染 PNG（p001,p021,…），**对账不需要重跑模拟器**。
6. 〔MEASURED〕P6a 真值页已有 8 页：`.e2e/p6a/gt_fixed2/p003,p032,p062,p081,p092,p122,p247,p384.txt`；三视角 CER 工具已有：`.e2e/p6a/t3_cer3.py`。
7. 〔MEASURED〕评分通道可用（探针往返 2.56s；22,275 字符评分 prompt 85.2s 返回合法 JSON，见 §0.4）。

### 0.3 五疑凶清单（M3 逐开关归因，不猜）

| # | 嫌疑 | 生产值 | 参照值（rapidocr 标准） | 验证开关 |
| --- | --- | --- | --- | --- |
| S1 | det 缩图口径 | 960 max-side | 736 min-side（3.9.2 默认）/更大/不缩 | det_limit × limit_type |
| S2 | det 后处理 | 轴对齐+25% 扩张 | minAreaRect+unclip 1.6 旋转裁剪 | unclip |
| S3 | rec 输入 | 硬拉伸 48×320 | 保比例+补白 | rec_aspect |
| S4 | 预处理/DPI | 无/140 | 灰度+unsharp/200 | preproc × dpi |
| S5 | 置信度口径 | 保留字符步均值 | rapidocr 自身口径 | M1 钉死 + M2 对账实测 |

### 0.4 调查实测记录（2026-10-05 本会话 MEASURED，评分员质询补证）

| 断言 | 实测证据 |
| --- | --- |
| venv 已装齐勿重装 | `.e2e/p6a/venv/Scripts/python -m pip list` 实测：numpy 2.5.3 / onnx 1.17.0 / onnxruntime 1.30.0 / opencv-python 5.0.0.93 / paddle2onnx 2.1.0 / paddlepaddle 3.3.1 / pillow 12.3.0 / pyclipper 1.4.0 / pymupdf 1.28.2 / rapidocr 3.9.2 |
| 本地模型=Release 模型 | `sha256sum .e2e/p6a/onnx/ppocrv5-mobile-{det,rec}.onnx` = d7fe3ea7…c7924 / bf66820f…faf2，与 `gh api repos/Lwb1397111398/Studying-with-friend/releases/tags/ocr-models-v1` 的 SHA256SUMS 资产内容逐条一致（dict 同验 d1979e9f…af1b） |
| Release 资产体积 | 同上 API 实测：det 4,748,769B / rec 16,517,247B / dict 74,012B / SHA256SUMS 266B |
| drafts 资产规模 | `.e2e/drafts_shpc/` 实测 449 文件 = 401 个 d*.txt（`text\tconf`）+ 24 张 p*.png + 24 个 p*.txt（20 个空占位） |
| 评分通道可用 | 探针 chat 往返 2.56s 返回合法 JSON；22,275 字符评分 prompt 实测 85.2s 返回合法 JSON；一次 60s 超时为偶发，score_review.py 已内置 240s 超时 + 失败重试 1 次 |
| 文本规则测试资产 | `OcrTextPostProcessorTest.kt` 实测 21 个 @Test：规则 a 3 条、规则 b 8 条、规则 c 5 条、规则 d 3 条、fixture 回放 2 条；规则 a/b 共 11 条抽样用例 + `src/test/resources/ocr/t3_sample8.json` fixture（243 行）为本计划移植校验源 |

---

## 1. M0 常设 AI 评分员机制（全程有效，先于一切执行）

### T0.1 评分脚本

- Create: `.e2e/p6c/score_review.py`
- **代码（完整）**：

```python
"""AI 评分员：读文档 → 10 维评分卡 → 严格 JSON → 留档 docs/plans/reviews/
用法: python .e2e/p6c/score_review.py <被评文件> <计划案|执行策略|代码> <轮次>"""
import sys, json, re
from pathlib import Path
sys.path.insert(0, r"C:\Agent\AImanager\tools")
from ai_client import chat

RUBRIC = """评分卡（每维 0-10，总分 0-100）：
1 目标与范围：目标一句话可验证；范围边界明确（做什么/明确不做什么）。
2 证据出处：关键数值/结论标注出处（文件:行 或 文档节）；区分 MEASURED/推断。
3 方法可行：每步具体到可执行；出现「适当/相关/一些/等等」类模糊词即扣分。
4 验证闭环：每个任务有客观过/不过判据与可运行的验证命令。
5 风险预案：主要风险有触发条件与具体预案，非泛泛而谈。
6 成本预算：LLM 调用次数、PC/模拟器机时有量化预估。
7 资产复用：明确列出复用的既有文件/工具/数据，不重复造轮子。
8 无占位符：无 TBD/TODO/「后续补充」；代码步骤给全代码。
9 纪律合规：危险操作确认、凭据纪律、模块总览更新、会话收尾闸门均有安排。
10 可执行性：零上下文工程师照文档可逐步执行；路径/命令/期望输出齐全。"""

PROMPT = """你是资深工程评审，独立评审以下{kind}。只依据文本本身评审，不看作者身份。
{rubric}
要求：先逐维给 0-10 分并一句话评语（指出具体证据），再给总分（各维之和）。
严格只输出 JSON，无其他文字：
{{"scores":{{"1 目标与范围":0,"2 证据出处":0,"3 方法可行":0,"4 验证闭环":0,"5 风险预案":0,"6 成本预算":0,"7 资产复用":0,"8 无占位符":0,"9 纪律合规":0,"10 可执行性":0}},"total":0,"issues":[{{"dim":"维度名","problem":"具体问题","suggestion":"具体修改建议"}}]}}
issues 按影响排序，最多 8 条；没有问题给空数组。

待评{kind}全文：
---
{body}
---"""

def review(path: str, kind: str, round_no: int) -> dict:
    body = Path(path).read_text(encoding="utf-8")
    raw, _ = chat([{"role": "user", "content": PROMPT.format(kind=kind, rubric=RUBRIC, body=body)}])
    m = re.search(r"\{.*\}", raw, re.S)
    if not m:
        raise RuntimeError(f"评分员输出非 JSON：{raw[:200]}")
    data = json.loads(m.group(0))
    arch = Path("docs/plans/reviews")
    arch.mkdir(parents=True, exist_ok=True)
    out = arch / f"{Path(path).stem}-评分-第{round_no}轮.md"
    lines = [f"# 评分记录：{Path(path).name} · 第 {round_no} 轮 · {kind}",
             f"", f"**总分：{data['total']}**（≥90 过）", ""]
    for k, v in data["scores"].items():
        lines.append(f"- {k}: {v}")
    lines += ["", "## 问题清单", ""]
    for i in data.get("issues", []):
        lines.append(f"- [{i['dim']}] {i['problem']} → 建议：{i['suggestion']}")
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"TOTAL={data['total']} archived={out}")
    return data

if __name__ == "__main__":
    r = review(sys.argv[1], sys.argv[2], int(sys.argv[3]))
    sys.exit(0 if r["total"] >= 90 else 1)
```

- **验证**：`python .e2e/p6c/score_review.py docs/plans/P6c-总体计划案.md 计划案 1` 退出码 0（本计划案自己先过评分，见 T0.2）。

### T0.2 评分循环规则（写死，防走样）

1. 被评对象三类：**计划案**（本文档 + Phase 1 各小计划案）、**执行策略**（每个 M 开工前一页策略案）、**关键代码**（replica_ocr.py、Phase 1 的引擎 diff）。
2. 退出码 ≥90 → 过；<90 → 逐条按 issues 修改，**每条修改都要能指出改了哪里**，再送第 N+1 轮。
3. 同一维度连续 2 轮同样问题 → 该节推倒重写，不再缝补。
4. 全部评分留档 `docs/plans/reviews/`（该目录已存在，沿用惯例）。
5. 评分员故障（非 JSON/超时）重试 1 次，仍败则在汇报中声明并改用第二支 caps=chat 的档案，不静默跳过评分。

---

## 2. 总体路线

| 阶段 | 内容 | 闸门 |
| --- | --- | --- |
| M0 | 评分员机制上线 | 本计划案 ≥90 分 |
| M1 | 证据核对：钉死 0.9751 出处 + 待移植函数清单 | 一页结论，事实全带出处 |
| M2 | PC 复刻实验台 + 对账（决定性实验） | 对账 A ≥20/24 页、对账 B ≥90% 页 median 差 ≤0.02 |
| M3 | 差异消融（S1-S4 开关归因）+ rapidocr 锚点 | 归因表完整；若最优组合达标 → 不换模型 |
| M4 | 模型对照（仅当 M3 不达标） | 对照表：median/过闸/CER/体积/耗时 |
| M5 | CER 真值判定 | 推荐组合 CER 数字入报告 |
| M6 | 收口报告 `docs/plans/P6c-Phase0-报告.md` | **老板确认选型与判据后才进 Phase 1** |
| Phase 1 | 移植+新画线+模拟器全本回归+真机 | 各小计划案过评分后执行；改 CONF_THRESHOLD 必须老板点头 |
| Phase 2 | 可选：分层重识别/行级兜底（兜底仍 >70 页才启动） | 同上 |

---

## 3. M1 证据核对（PC，约 30 分钟）

### T1.1 钉死 0.9751 出处

- 精读 `.e2e/p6a/t3_run.py`、`t3_dval_run.py`、`t3_cer3.py`、`poc_report.md`、`poc_result_emulator.json`，回答：0.9751 是哪个脚本×哪批页×哪个渲染源×rapidocr 哪些生效参数（det limit_side_len/limit_type/unclip_ratio/text_score、rec_img_shape、置信度算法）跑出来的；其页集合与 drafts_shpc 页号重叠多少。
- 产出：`.e2e/p6c/m1_evidence.json`（每条事实带 `source` 字段指向文件:行）+ 结论写进 M2 对账基线。
- Create: `.e2e/p6c/check_evidence.py`（证据完整性断言），**代码（完整）**：

```python
"""用法: python .e2e/p6c/check_evidence.py .e2e/p6c/m1_evidence.json"""
import json, sys

d = json.loads(open(sys.argv[1], encoding="utf-8").read())
missing = [k for k, v in d.items() if not isinstance(v, dict) or "source" not in v]
assert not missing, f"缺 source 出处字段: {missing}"
print(f"evidence ok: {len(d)} 条事实全带出处")
```

- **验证**：`python .e2e/p6c/check_evidence.py .e2e/p6c/m1_evidence.json` 退出码 0；「0.9751 页集合」字段若为 null → 记 UNMEASURED 并降级为待测锚点（M3 锚点改为自建，不阻塞后续）。

### T1.2 文本规则移植（post_rules.py）

- 目标：把 `OcrTextPostProcessor.kt:38-43,52-81` 的 `normalizePunctLine`/`stripCitationLine` 逐位移植为纯文本函数——drafts 行文本已过这两道规则（`OcrImportRunner.kt:137-139`），对账前必须同口径。
- Create: `.e2e/p6c/post_rules.py`，**代码（完整）**：

```python
"""OcrTextPostProcessor 规则 a/b 的 Python 逐位移植。
注意：Kotlin 的 \s 是 JVM 语义 [ \t\n\x0B\f\r]（S7 修复后为显式类 [\s\u00A0\u3000]），
Python re 的 \s 是 Unicode 超集——为逐位一致，这里按 JVM \s 显式拼写。"""
import re

TO_HALF = str.maketrans({"，": ",", "（": "(", "）": ")", "：": ":", "；": ";"})
WS = r"[ \t\n\x0b\f\r\u00A0\u3000]"          # Kotlin [\s\u00A0\u3000] 的 JVM \s 展开
DIGIT = r"[0-9\uFF10-\uFF19]"
CIRCLED = r"[\u2460-\u2473]"                 # ①-⑳
RE_CIP_LINE = re.compile(rf"[IVX\u2160-\u2163]+\.(?:{WS})*{CIRCLED}")
RE_FN_HEAD = re.compile(rf"^({WS}*){CIRCLED}({WS}+)")
RE_IN = re.compile(CIRCLED)
RE_BR_HEAD = re.compile(rf"^({WS}*)[【\[]{DIGIT}{{1,3}}[】\]]({WS}*)")
RE_BR_IN = re.compile(rf"[【\[]{DIGIT}{{1,3}}[】\]]")


def normalize_punct_line(text: str) -> str:
    return text.translate(TO_HALF)


def strip_citation_line(text: str) -> str:
    if RE_CIP_LINE.search(text):
        return text
    out = text
    m = RE_FN_HEAD.search(out)
    if m:
        out = m.group(1) + m.group(2) + out[m.end():]
    m = RE_BR_HEAD.search(out)
    if m:
        out = m.group(1) + out[m.end():]
    if RE_IN.search(out):
        out = RE_IN.sub("", out)
    if RE_BR_IN.search(out):
        out = RE_BR_IN.sub("", out)
    return out
```

- Create: `.e2e/p6c/test_replica_rules.py`，**代码（完整）**（11 条抽样用例逐条移植自 `OcrTextPostProcessorTest.kt:36-119`，另加 fixture 回放）：

```python
"""规则 a/b 移植验证：11 条抽样用例 + t3_sample8.json 回放（243 行 0 diff，
与 Kotlin 用例 test fixture replay - rule a plus b matches python prototype 同一数据源）。"""
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from post_rules import normalize_punct_line, strip_citation_line

FIXTURE = (Path(__file__).parents[2] / "android" / "app" / "src" / "test"
           / "resources" / "ocr" / "t3_sample8.json")


def test_rule_a_five_puncts():
    assert normalize_punct_line("，（）：；") == ",():;"


def test_rule_a_unmapped_untouched():
    t = "一、「引号」。！？—…"
    assert normalize_punct_line(t) == t


def test_rule_b_fn_head():
    assert strip_citation_line("  ① 结果") == "   结果"


def test_rule_b_circled_no_space_inline():
    assert strip_citation_line("①结果") == "结果"


def test_rule_b_inline_circled():
    assert strip_citation_line("依契约①履行") == "依契约履行"


def test_rule_b_bracket_head():
    assert strip_citation_line("【12】结果") == "结果"
    assert strip_citation_line("[3] 结果") == "结果"


def test_rule_b_inline_bracket():
    assert strip_citation_line("依契约[12]履行") == "依契约履行"


def test_rule_b_cip_exempt():
    for t in ("I. ①损害赔偿 ②民法典研究", "II. ①王泽鉴著"):
        assert strip_citation_line(t) == t


def test_rule_b_body_numbering_untouched():
    t = "(1)（一）1. 条文引用"
    assert strip_citation_line(t) == t


def test_rule_b_fullwidth_space_and_digit():
    assert strip_citation_line("①　结果") == "　结果"
    assert strip_citation_line("【１】结果") == "结果"
    assert strip_citation_line("依契约[３]履行") == "依契约履行"


def test_fixture_replay_243_lines():
    root = json.loads(FIXTURE.read_text(encoding="utf-8"))
    checked = 0
    for page in root["pages"]:
        out = [strip_citation_line(normalize_punct_line(l["text"])) for l in page["lines"]]
        exp = [e["text"] for e in root["expected"][str(page["page"])]]
        assert out == exp, f"p{page['page']:03d} 不一致"
        checked += len(exp)
    assert checked == 243
```

- **验证**：`.e2e/p6a/venv/Scripts/python -m pytest .e2e/p6c/test_replica_rules.py -q` 全绿（11 抽样 + 1 回放 243 行）；结果记入 m1_evidence.json 的 `post_rules_port` 条目。

---

## 4. M2 复刻实验台 + 对账（决定性实验，PC，机时约 25 分钟）

### T2.1 复刻脚本 replica_ocr.py

- Create: `.e2e/p6c/replica_ocr.py`
- **代码（完整）**：

```python
"""PC 复刻手机 PpOcrEngine 管线（OcrEngine.kt 逐位口径），参数可调做差异消融。
用法:
  python replica_ocr.py --pdf .e2e/shpc.pdf --pages 5,6,7 --dpi 140 \
      --det-limit 960 --rec-aspect off --unclip off --preproc none \
      --models .e2e/p6a/onnx --dict .e2e/p6a/models/ppocrv5_dict.txt --out run.json
  python replica_ocr.py --pngs .e2e/drafts_shpc/p001.png,p021.png ... 同上参数"""
import argparse, json, time
from pathlib import Path
import numpy as np
import cv2
import onnxruntime as ort
import fitz


def load_dict(p: str):
    # 手机侧 open(): 空行过滤（OcrEngine.kt:56）
    return [ln for ln in Path(p).read_text("utf-8").splitlines() if ln.strip()]


def norm_chw(img_rgb: np.ndarray) -> np.ndarray:
    x = img_rgb.astype(np.float32) / 255.0
    x = (x - 0.5) / 0.5
    return x.transpose(2, 0, 1)[None]


def render_pdf_page(pdf: str, page_1based: int, dpi: int) -> np.ndarray:
    doc = fitz.open(pdf)
    pg = doc[page_1based - 1]
    pm = pg.get_pixmap(matrix=fitz.Matrix(dpi / 72, dpi / 72), colorspace=fitz.csRGB, alpha=False)
    img = np.frombuffer(pm.samples, np.uint8).reshape(pm.height, pm.width, 3).copy()
    doc.close()
    return img  # RGB


def preproc(img: np.ndarray, mode: str) -> np.ndarray:
    if mode == "none":
        return img
    if mode == "gray_unsharp":
        g = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY)
        g = cv2.GaussianBlur(g, (0, 0), 2.0)
        sharp = cv2.addWeighted(cv2.cvtColor(img, cv2.COLOR_RGB2GRAY), 1.5, g, -0.5, 0)
        return cv2.cvtColor(sharp, cv2.COLOR_GRAY2RGB)
    raise ValueError(mode)


def det_pre(img: np.ndarray, det_limit, limit_type: str = "max") -> tuple:
    # max 型=生产口径（OcrEngine.kt:67-72，只缩不放）；min 型=PaddleOCR DetResizeForTest
    # 的 'min' 语义（min 边 < limit 才放大，否则原样）——rapidocr 开箱口径，供消融对照
    h, w = img.shape[:2]
    if det_limit is not None:
        if limit_type == "min":
            if min(h, w) < det_limit:
                s = det_limit / min(h, w)
                h, w = int(h * s), int(w * s)
        else:
            s = det_limit / max(h, w)
            if s < 1.0:
                h, w = int(h * s), int(w * s)
    h -= h % 32
    w -= w % 32
    im = cv2.resize(img, (w, h), interpolation=cv2.INTER_LINEAR)
    return im, (img.shape[1] / w, img.shape[0] / h)  # (sx, sy) 回原图比例


def det_post_axis_aligned(prob: np.ndarray, det_limit_cfg) -> list:
    # OcrDetPost.extractBoxes 逐位复刻（OcrEngine.kt:159-206）
    H, W = prob.shape
    mask = prob > 0.3
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), connectivity=8)
    boxes = []
    sums = np.bincount(labels.ravel(), weights=prob.ravel(), minlength=n)
    cnts = np.bincount(labels.ravel(), minlength=n)
    for i in range(1, n):
        x, y, bw, bh, area = stats[i]
        if area >= 32 and sums[i] / cnts[i] >= 0.5:
            ex = max(int(bw * 0.25), 2)
            ey = max(int(bh * 0.25), 2)
            boxes.append([max(x - ex, 0), max(y - ey, 0),
                          min(x + bw - 1 + ex, W - 1), min(y + bh - 1 + ey, H - 1)])
    boxes.sort(key=lambda b: (b[1] // 24, b[0]))
    return boxes


def det_post_unclip(prob: np.ndarray) -> list:
    # rapidocr/PaddleOCR 标准口径：findContours → minAreaRect → unclip 1.6 → 旋转框列表
    import pyclipper
    mask = (prob > 0.3).astype(np.uint8)
    contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    out = []
    for c in contours:
        rect = cv2.minAreaRect(c)
        w, h = rect[1]
        if min(w, h) < 3 or cv2.contourArea(c) < 3:
            continue
        m = cv2.mean(prob, mask=cv2.drawContours(
            np.zeros(mask.shape, np.uint8), [c], -1, 1, -1))[0]
        if m < 0.5:
            continue
        pts = cv2.boxPoints(rect)
        area = abs(pyclipper.Area(pts.tolist()))
        peri = max(cv2.arcLength(pts, True), 1e-6)
        po = pyclipper.PyclipperOffset()
        po.AddPath(pts.tolist(), pyclipper.JT_ROUND, True)
        poly = po.Execute(area * 1.6 / peri)
        if not poly:
            continue
        r2 = cv2.minAreaRect(np.array(poly[0], np.float32))
        out.append((cv2.boxPoints(r2), r2[1][0], r2[1][1]))
    out.sort(key=lambda t: (int(min(t[0][:, 1])) // 24, int(min(t[0][:, 0]))))
    return out


def crop_and_rec_pre(img: np.ndarray, box, sx: float, sy: float, rec_aspect: bool) -> np.ndarray:
    H, W = img.shape[:2]
    if rec_aspect:
        pts = box  # 旋转框：paddle get_rotate_crop_image 口径
        w = int(max(np.linalg.norm(pts[0] - pts[1]), np.linalg.norm(pts[2] - pts[3])))
        h = int(max(np.linalg.norm(pts[0] - pts[3]), np.linalg.norm(pts[1] - pts[2])))
        dst = np.array([[0, 0], [w, 0], [w, h], [0, h]], np.float32)
        M = cv2.getPerspectiveTransform(pts.astype(np.float32), dst)
        crop = cv2.warpPerspective(img, M, (w, h))
        ratio = w / h
        rh = 48
        rw = min(int(np.ceil(ratio * rh)), 320)
        im = cv2.resize(crop, (rw, rh), interpolation=cv2.INTER_LINEAR)
        x = (im.astype(np.float32) / 255.0 - 0.5) / 0.5
        x = x.transpose(2, 0, 1)
        pad = np.zeros((3, rh, 320), np.float32)
        pad[:, :, :rw] = x
        return pad
    # 生产口径：OcrEngine.kt:100-115（取整+钳制+硬拉伸 320x48）
    x0 = int(box[0] * sx); y0 = int(box[1] * sy)
    x0 = min(max(x0, 0), W - 2); y0 = min(max(y0, 0), H - 2)
    x1 = int(box[2] * sx); y1 = int(box[3] * sy)
    x1 = min(max(x1, x0 + 1), W - 1); y1 = min(max(y1, y0 + 1), H - 1)
    crop = img[y0:y1 + 1, x0:x1 + 1]
    im = cv2.resize(crop, (320, 48), interpolation=cv2.INTER_LINEAR)
    return norm_chw(im)[0]


def ctc_decode(logits: np.ndarray, dict_: list) -> list:
    # OcrCtc.decode 逐位复刻（OcrEngine.kt:209-237）：贪心、去重相邻、跳 blank、保留步均值
    out = []
    for n in range(logits.shape[0]):
        seq = logits[n]
        best = seq.argmax(1)
        probs = seq.max(1)
        prev = 0
        s = 0.0; cnt = 0; chars = []
        for ti in range(seq.shape[0]):
            b = int(best[ti]); v = float(probs[ti])
            if b != 0 and b != prev and b <= len(dict_):
                chars.append(dict_[b - 1]); s += v; cnt += 1
            prev = b
        out.append(("".join(chars), s / cnt if cnt else 0.0))
    return out


def page_gate(confs: list) -> tuple:
    # PageGate.decide 逐位复刻（OcrImportRunner.kt:248-262）：上中位 + <0.5 占比
    if not confs:
        return "LOW_CONF", 0.0
    med = sorted(confs)[len(confs) // 2]
    low_ratio = sum(1 for c in confs if c < 0.5) / len(confs)
    gate = "LOW_CONF" if (med < 0.85 or low_ratio > 0.20) else None
    return gate, med


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pdf"); ap.add_argument("--pngs")
    ap.add_argument("--pages", default=""); ap.add_argument("--dpi", type=int, default=140)
    ap.add_argument("--det-limit", default="960")     # 960 | 1280 | 1536 | none | min736
    ap.add_argument("--unclip", default="off")        # off | on
    ap.add_argument("--rec-aspect", default="off")    # off | on
    ap.add_argument("--preproc", default="none")      # none | gray_unsharp
    ap.add_argument("--models", default=".e2e/p6a/onnx")
    ap.add_argument("--dict", default=".e2e/p6a/models/ppocrv5_dict.txt")
    ap.add_argument("--det-name", default="ppocrv5-mobile-det.onnx")  # M4 换模型时指向新文件
    ap.add_argument("--rec-name", default="ppocrv5-mobile-rec.onnx")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    dl = a.det_limit
    det_limit = None if dl == "none" else (int(dl[3:]) if dl.startswith("min") else int(dl))
    limit_type = "min" if dl.startswith("min") else "max"
    sess = ort.InferenceSession(f"{a.models}/{a.det_name}",
                                providers=["CPUExecutionProvider"])
    rsess = ort.InferenceSession(f"{a.models}/{a.rec_name}",
                                 providers=["CPUExecutionProvider"])
    dict_ = load_dict(a.dict)
    pages = ([int(x) for x in a.pages.split(",") if x] if a.pages
             else sorted(int(Path(p).stem[1:]) for p in Path(a.pngs).glob("p*.png")))
    rec_aspect = a.rec_aspect == "on"

    result = {"cfg": vars(a), "pages": []}
    for pno in pages:
        t0 = time.time()
        img = (cv2.cvtColor(cv2.imread(str(Path(a.pngs) / f"p{pno:03d}.png")), cv2.COLOR_BGR2RGB)
               if a.pngs else render_pdf_page(a.pdf, pno, a.dpi))
        img = preproc(img, a.preproc)
        if a.unclip == "on":
            im, _ = det_pre(img, det_limit, limit_type)
            prob = sess.run(None, {"x": norm_chw(im)})[0][0]
            boxes = det_post_unclip(prob)
            lines = []
            for pts, bw, bh in boxes:
                inp = crop_and_rec_pre(im, pts, 1.0, 1.0, True)
                txt, cf = ctc_decode(rsess.run(None, {"x": inp[None]})[0], dict_)[0]
                if txt:
                    lines.append((txt, cf))
        else:
            im, (sx, sy) = det_pre(img, det_limit, limit_type)
            prob = sess.run(None, {"x": norm_chw(im)})[0][0]
            boxes = det_post_axis_aligned(prob, det_limit)
            lines = []
            for i in range(0, len(boxes), 8):
                chunk = boxes[i:i + 8]
                data = np.stack([crop_and_rec_pre(img, b, sx, sy, False) for b in chunk])
                logits = rsess.run(None, {"x": data})[0]
                for bi, (txt, cf) in enumerate(ctc_decode(logits, dict_)):
                    if txt:
                        lines.append((txt, cf))
        confs = [c for _, c in lines]
        gate, med = page_gate(confs)
        result["pages"].append({
            "page": pno, "n_lines": len(lines), "median": med, "gate": gate,
            "low_ratio": (sum(1 for c in confs if c < 0.5) / len(confs)) if confs else 1.0,
            "secs": round(time.time() - t0, 2),
            "lines": [(t, round(c, 4)) for t, c in lines],
        })
        print(f"p{pno:03d} lines={len(lines)} median={med:.4f} gate={gate} {time.time()-t0:.1f}s")
    Path(a.out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
```

- **验证（单元级，先于对账）**：T2.2。

### T2.2 单元测试移植（OcrEngineTest.kt 8 条 → pytest + 新增 3 条）

- Create: `.e2e/p6c/test_replica_units.py`，**代码（完整）**：

```python
"""OcrEngineTest.kt 8 条用例逐条移植（CTC 4 + det 后处理 4）+ 新增 3 条
（gate 上中位 / gate 低分占比 / det 面积 31 边界）。"""
import sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).parent))
from replica_ocr import ctc_decode, det_post_axis_aligned, page_gate


def ctc_case(data, n, t, c, dict_):
    return ctc_decode(np.array(data, np.float32).reshape(n, t, c), dict_)


# ---------- ctc_decode（= OcrCtc.decode，OcrEngineTest.kt:18-60） ----------

def test_ctc_dedupe_blank_and_conf_mean():
    data = np.zeros((1, 5, 3), np.float32)
    data[0, 0, 1] = 0.9
    data[0, 1, 1] = 0.85   # 相邻重复 → 去重
    data[0, 2, 0] = 0.9    # blank
    data[0, 3, 2] = 0.8
    data[0, 4, 0] = 0.9    # blank
    out = ctc_case(data, 1, 5, 3, ["中", "文"])
    assert out[0][0] == "中文"
    assert abs(out[0][1] - (0.9 + 0.8) / 2) < 1e-4


def test_ctc_all_blank():
    data = np.zeros((1, 3, 2), np.float32)
    for ti in range(3):
        data[0, ti, 0] = 0.99
    out = ctc_case(data, 1, 3, 2, ["字"])
    assert out[0] == ("", 0.0)


def test_ctc_class_beyond_dict_skipped():
    data = np.zeros((1, 2, 3), np.float32)
    data[0, 0, 2] = 0.9    # class2 > dict.size=1 → 跳过不崩
    data[0, 1, 1] = 0.8
    out = ctc_case(data, 1, 2, 3, ["字"])
    assert out[0][0] == "字" and abs(out[0][1] - 0.8) < 1e-4


def test_ctc_batch_independent():
    data = np.zeros((2, 1, 2), np.float32)
    data[1, 0, 1] = 0.7
    out = ctc_case(data, 2, 1, 2, ["甲"])
    assert out[0][0] == "" and out[1][0] == "甲"


# ---------- det_post_axis_aligned（= OcrDetPost.extractBoxes，prob 输入 [H,W]） ----------

def prob_map(H, W, fn):
    return np.array([[fn(x, y) for x in range(W)] for y in range(H)], np.float32)


def test_det_single_block_expanded():
    # 块 x∈[10,40] y∈[10,30]（w=31,h=21）→ ex=7 ey=5 → 框 [3,5,47,35]（Kotlin 同期望）
    prob = prob_map(64, 64, lambda x, y: 0.9 if 10 <= x <= 40 and 10 <= y <= 30 else 0.0)
    assert det_post_axis_aligned(prob, None) == [[3, 5, 47, 35]]


def test_det_tiny_block_dropped():
    prob = prob_map(64, 64, lambda x, y: 0.9 if 10 <= x <= 13 and 10 <= y <= 13 else 0.0)
    assert det_post_axis_aligned(prob, None) == []


def test_det_low_mean_dropped():
    prob = prob_map(64, 64, lambda x, y: 0.4 if 10 <= x <= 40 and 10 <= y <= 30 else 0.0)
    assert det_post_axis_aligned(prob, None) == []


def test_det_sorted_y_bucket_then_x():
    def fn(x, y):
        if 60 <= x <= 100 and 0 <= y <= 20:
            return 0.9
        if 0 <= x <= 40 and 10 <= y <= 30:
            return 0.9
        if 0 <= x <= 40 and 60 <= y <= 90:
            return 0.9
        return 0.0
    boxes = det_post_axis_aligned(prob_map(128, 128, fn), None)
    assert len(boxes) == 3
    assert boxes[0][0] < boxes[1][0]              # 同桶 x 升序
    assert boxes[1][1] // 24 <= boxes[2][1] // 24  # y 桶升序


# ---------- 新增 3 条 ----------

def test_gate_upper_median_even():
    gate, med = page_gate([0.84, 0.86])
    assert med == 0.86 and gate is None   # Kotlin sorted()[n/2] 取上中位，非均值 0.85


def test_gate_low_ratio_rule():
    gate, _ = page_gate([0.9] * 7 + [0.4] * 3)   # median 高但 <0.5 行占 30%>20%
    assert gate == "LOW_CONF"


def test_det_area_31_dropped():
    # h=1 w=31 → 面积 31 < MIN_BOX_AREA=32 → 丢框（MIN_BOX_AREA 边界）
    prob = prob_map(64, 64, lambda x, y: 0.9 if 10 <= x <= 40 and y == 10 else 0.0)
    assert det_post_axis_aligned(prob, None) == []
```

- **验证**：`.e2e/p6a/venv/Scripts/python -m pytest .e2e/p6c/test_replica_units.py -q` 全绿（11 条）。

### T2.3 对账 A（强对账，24 页手机 PNG）

- Create: `.e2e/p6c/match_report.py`，**代码（完整）**：

```python
"""对账报告：replica 输出 vs drafts_shpc 手机草稿（判据计算全在此）。
用法: python .e2e/p6c/match_report.py --replica .e2e/p6c/matchA.json \
         --drafts .e2e/drafts_shpc --out .e2e/p6c/matchA_report.md [--text-sim]"""
import argparse, json
from difflib import SequenceMatcher
from pathlib import Path
from post_rules import normalize_punct_line, strip_citation_line


def load_draft(drafts: Path, pno: int):
    f = drafts / f"d{pno:03d}.txt"
    if not f.exists():
        return None
    lines = []
    for ln in f.read_text("utf-8").splitlines():
        if not ln.strip():
            continue
        text, _, conf = ln.rpartition("\t")
        lines.append((text, float(conf)))
    return lines


def text_sim(rep_texts, phone_texts):
    # 无框坐标，用最优匹配相似度：每个手机行在 replica 行集中找最高 ratio，取均值
    if not phone_texts or not rep_texts:
        return 0.0
    return sum(max(SequenceMatcher(None, pt, rt).ratio() for rt in rep_texts)
               for pt in phone_texts) / len(phone_texts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--replica", required=True)
    ap.add_argument("--drafts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--text-sim", action="store_true")
    a = ap.parse_args()
    rep = json.loads(Path(a.replica).read_text(encoding="utf-8"))
    drafts = Path(a.drafts)
    rows, passed = [], 0
    for pg in rep["pages"]:
        ph = load_draft(drafts, pg["page"])
        if ph is None:
            continue
        ph_confs = [c for _, c in ph]
        ph_med = sorted(ph_confs)[len(ph_confs) // 2]
        delta = abs(pg["median"] - ph_med)
        n_diff = abs(pg["n_lines"] - len(ph))
        sim = None
        if a.text_sim:
            sim = text_sim(
                [normalize_punct_line(strip_citation_line(t)) for t, _ in pg["lines"]],
                [t for t, _ in ph])
        ok = delta <= 0.02 and n_diff <= max(2, int(0.1 * len(ph))) \
            and (sim is None or sim >= 0.85)
        passed += ok
        rows.append((pg["page"], len(ph), pg["n_lines"], ph_med, pg["median"], delta, sim, ok))
    rows.sort(key=lambda r: -r[5])
    md = ["# 对账报告：replica vs 手机 drafts", "",
          f"- 对账页数 {len(rows)}，通过 {passed}（判据：median Δ≤0.02 且行数差≤max(2,10%)"
          + (" 且文本相似度≥0.85）" if a.text_sim else "）"), "",
          "| 页 | 手机行数 | 复刻行数 | 手机median | 复刻median | Δ | 文本相似 | 过 |",
          "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for pno, nph, nre, pm, rm, d, sim, ok in rows:
        md.append(f"| p{pno:03d} | {nph} | {nre} | {pm:.4f} | {rm:.4f} | {d:.4f} | "
                  f"{'-' if sim is None else f'{sim:.3f}'} | {'✅' if ok else '❌'} |")
    Path(a.out).write_text("\n".join(md), encoding="utf-8")
    print(f"passed={passed}/{len(rows)} → {a.out}")


if __name__ == "__main__":
    main()
```

- Run（两条命令）：

```bash
python .e2e/p6c/replica_ocr.py --pngs .e2e/drafts_shpc --models .e2e/p6a/onnx \
    --dict .e2e/p6a/models/ppocrv5_dict.txt --out .e2e/p6c/matchA.json
python .e2e/p6c/match_report.py --replica .e2e/p6c/matchA.json \
    --drafts .e2e/drafts_shpc --out .e2e/p6c/matchA_report.md --text-sim
```

- **判据**：报告 `passed≥20/24` → 复刻成立；不成立 → 对 Δ 最大的页做归因（先看 R1 的渲染像素检查，再看裁剪/插值细节），修 replica 直到达标。**这是实验台的验收，不过不做任何扫描。**

### T2.4 对账 B（广对账，401 页）

- Run：

```bash
python .e2e/p6c/replica_ocr.py --pdf .e2e/shpc.pdf \
    --pages $(ls .e2e/drafts_shpc/d*.txt | sed 's/.*d\([0-9]*\)\.txt/\1/' | paste -sd,) \
    --models .e2e/p6a/onnx --dict .e2e/p6a/models/ppocrv5_dict.txt --out .e2e/p6c/matchB.json
python .e2e/p6c/match_report.py --replica .e2e/p6c/matchB.json \
    --drafts .e2e/drafts_shpc --out .e2e/p6c/matchB_report.md
```

（预计 15-20 分钟；页号 = drafts_shpc/d*.txt 全集）

- **判据**：`passed≥361/401`（90%）且全 401 页 Δ 分布中位数 ≤0.01。
- 产出：`.e2e/p6c/m2_match_report.md`（两对账结论 + 差异归因 + 复刻成立声明）。

---

## 5. M3 差异消融 + 锚点（PC，机时约 40 分钟）

### T3.1 固定评测页集（确定性规则，不许「适选」）

- 从 `.e2e/drafts_shpc/d*.txt` 算每页 median（analyze_line.py 同口径）：取**最低 10 页**（乱码重灾区）+ **0.84-0.85 区间最高 10 页**（擦线页），共 20 页，写死进 `.e2e/p6c/eval_pages.json`（含选取理由字段）。

### T3.2 开关扫描（run_ablation.py 一键跑批 + rapidocr 锚点）

- Create: `.e2e/p6c/run_ablation.py`，**代码（完整）**（单因子 9 组 + 组合 3 组，组合选择规则在代码里写死）：

```python
"""差异消融跑批：eval_pages.json 固定 20 页 × 组合矩阵 → .e2e/p6c/m3_ablation.md
用法: .e2e/p6a/venv/Scripts/python .e2e/p6c/run_ablation.py"""
import json, subprocess, time
from pathlib import Path

PY = ".e2e/p6a/venv/Scripts/python.exe"
REPLICA = ".e2e/p6c/replica_ocr.py"
PDF = ".e2e/shpc.pdf"
EVAL = json.loads(Path(".e2e/p6c/eval_pages.json").read_text(encoding="utf-8"))["pages"]

BASE = dict(det_limit="960", rec_aspect="off", unclip="off", preproc="none", dpi="140")
# 单因子组：每次只动一个开关（S1 组内四档互斥）
OVERRIDES = {
    "G01_S1_limit1280":  dict(det_limit="1280"),
    "G02_S1_limit1536":  dict(det_limit="1536"),
    "G03_S1_nolimit":    dict(det_limit="none"),
    "G04_S1_min736":     dict(det_limit="min736"),
    "G05_S2_unclip":     dict(unclip="on"),
    "G06_S3_aspect":     dict(rec_aspect="on"),
    "G07_S4_gray":       dict(preproc="gray_unsharp"),
    "G08_S4_dpi200":     dict(dpi="200"),
    "G09_S4_dpi200gray": dict(dpi="200", preproc="gray_unsharp"),
}
AXIS = {"G01_S1_limit1280": "S1", "G02_S1_limit1536": "S1", "G03_S1_nolimit": "S1",
        "G04_S1_min736": "S1", "G05_S2_unclip": "S2", "G06_S3_aspect": "S3",
        "G07_S4_gray": "S4", "G08_S4_dpi200": "S4", "G09_S4_dpi200gray": "S4"}


def run_group(name: str, cfg: dict) -> tuple:
    out = f".e2e/p6c/abl_{name}.json"
    args = [PY, REPLICA, "--pdf", PDF, "--pages", ",".join(map(str, EVAL)),
            "--det-limit", cfg["det_limit"], "--rec-aspect", cfg["rec_aspect"],
            "--unclip", cfg["unclip"], "--preproc", cfg["preproc"], "--dpi", cfg["dpi"],
            "--out", out]
    t0 = time.time()
    subprocess.run(args, check=True)
    res = json.loads(Path(out).read_text(encoding="utf-8"))
    meds = [p["median"] for p in res["pages"]]
    med = sorted(meds)[len(meds) // 2]
    gate = sum(1 for p in res["pages"] if p["gate"] is None)
    secs = sum(p["secs"] for p in res["pages"]) / len(res["pages"])
    return med, gate, secs


def main():
    results = {"G00_base": run_group("G00_base", BASE)}
    print("G00_base", results["G00_base"], flush=True)
    for name, over in OVERRIDES.items():
        results[name] = run_group(name, {**BASE, **over})
        print(name, results[name], flush=True)

    # 组合选择规则（确定性）：同轴取 median 最高组；增益 ≤0 的轴不入选；
    # C2 = base + 最大正增益轴的最优组；C3 = C2 + 次大正增益轴最优组；
    # C4 固定 = rapidocr 开箱口径三开关全开（不依赖选择结果，永远跑）
    base_med = results["G00_base"][0]
    axis_best = {}
    for name in OVERRIDES:
        ax = AXIS[name]
        if ax not in axis_best or results[name][0] > results[axis_best[ax]][0]:
            axis_best[ax] = name
    ranked = sorted(axis_best.items(), key=lambda kv: results[kv[1]][0] - base_med,
                    reverse=True)
    positive = [(ax, name) for ax, name in ranked if results[name][0] - base_med > 0]
    c2_axis = positive[0][0] if positive else None
    c2 = dict(BASE, **OVERRIDES[positive[0][1]]) if positive else dict(BASE)
    combos = {"C2_combo_1axis": c2,
              "C4_rapidocr_full": dict(det_limit="min736", rec_aspect="on", unclip="on",
                                       preproc="none", dpi="140")}
    c3 = dict(c2)
    for ax, name in positive:          # 次大正增益轴（与 C2 不同轴）叠加进 C3
        if ax != c2_axis:
            c3 = dict(c3, **OVERRIDES[name])
            break
    combos["C3_combo_2axis"] = c3
    for name, cfg in combos.items():
        results[name] = run_group(name, cfg)
        print(name, results[name], flush=True)

    lines = ["# M3 差异消融（20 页评测集）", "",
             "| 组 | 页median | 过闸(20) | 单页耗时s | Δmedian |", "| --- | --- | --- | --- | --- |"]
    for name, (med, gate, secs) in results.items():
        lines.append(f"| {name} | {med:.4f} | {gate} | {secs:.2f} | {med - base_med:+.4f} |")
    Path(".e2e/p6c/m3_ablation.md").write_text("\n".join(lines), encoding="utf-8")
    print("written .e2e/p6c/m3_ablation.md")


if __name__ == "__main__":
    main()
```

- Create: `.e2e/p6c/anchor_rapidocr.py`（锚点），**代码（完整）**：

```python
"""rapidocr 引擎本体锚点（同 20 页、同渲染源）：外部参照，仅方向对比不进判据。
用法: .e2e/p6a/venv/Scripts/python .e2e/p6c/anchor_rapidocr.py"""
import json
from pathlib import Path
import cv2
import fitz
import numpy as np
from rapidocr import RapidOCR

EVAL = json.loads(Path(".e2e/p6c/eval_pages.json").read_text(encoding="utf-8"))["pages"]
eng = RapidOCR(params={
    "Det.model_path": ".e2e/p6a/onnx/ppocrv5-mobile-det.onnx",
    "Rec.model_path": ".e2e/p6a/onnx/ppocrv5-mobile-rec.onnx",
    "Rec.rec_keys_path": ".e2e/p6a/models/ppocrv5_dict.txt",
    "Global.use_cls": False,
})
out = {"pages": []}
for pno in EVAL:
    doc = fitz.open(".e2e/shpc.pdf")
    pm = doc[pno - 1].get_pixmap(matrix=fitz.Matrix(140 / 72, 140 / 72),
                                 colorspace=fitz.csRGB, alpha=False)
    img = cv2.cvtColor(np.frombuffer(pm.samples, np.uint8).reshape(
        pm.height, pm.width, 3), cv2.COLOR_RGB2BGR)
    doc.close()
    res = eng(img)
    txts = list(res.txts or [])
    scores = [float(s) for s in (res.scores or [])]
    med = sorted(scores)[len(scores) // 2] if scores else 0.0
    out["pages"].append({"page": pno, "n_lines": len(txts), "median": med,
                         "lines": [[t, c] for t, c in zip(txts, scores)]})
    print(f"p{pno:03d} lines={len(txts)} median={med:.4f}")
Path(".e2e/p6c/abl_G10_anchor_rapidocr.json").write_text(
    json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
```

- **每组判据（跑完即出，run_ablation 输出表即判据载体）**：
  - 单因子组「有效」= Δmedian ≥ +0.02 或 过闸 ≥ G00+2；无效开关在归因表标「排除」。
  - 组合组「达标候选」= 过闸 ≥16/20 且 页median ≥0.93。
  - 锚点组（G10）：不进判据，只记录，方向参照。
- **验证**：`m3_ablation.md` 含 G00-G09 + C2/C3/C4 共 13 行且无组崩溃（subprocess check=True，崩即停）；锚点 json 落盘。

### T3.3 决策点（条件写死，数据说话）

- **M4 触发条件**：C2/C3/C4 三组合中「过闸最高、并列取 median 高」者为最优组合；最优组合过闸 <16/20 **或** 页median <0.93 → 执行 M4；否则 M4 记 SKIPPED（报告写明触发条件与实测数字）。
- **推荐不换模型的充要数据**：最优组合过闸 ≥16/20 且 median ≥0.93 且 M5 CER ≤8%。
- **回查规则（B 后回查 A）**：M3 全部组跑完后——① 重跑 `.e2e/p6a/venv/Scripts/python -m pytest .e2e/p6c/test_replica_rules.py .e2e/p6c/test_replica_units.py -q`（纯函数回归）；② 用 G00_base 生产配置重跑 T2.3 对账命令，passed 必须 ≥20/24（复刻实验台未漂移；组合配置本就有意偏离手机口径，不对账手机，只对账 base）。

---

## 6. M4 模型对照（触发条件=T3.3 写死条件；PC，机时约 1 小时）

### T4.1 下载与转换（命令写死；URL 开工时核对一次并留痕）

```bash
mkdir -p .e2e/p6c/m4 && cd .e2e/p6c/m4
BASE=https://paddle-model-ecology.bj.bcebos.com/paddleocr/1.1.0
curl -fL -o ch_PP-OCRv4_det_infer.tar        "$BASE/ch_PP-OCRv4_det_infer.tar"
curl -fL -o ch_PP-OCRv4_rec_infer.tar        "$BASE/ch_PP-OCRv4_rec_infer.tar"
curl -fL -o ch_PP-OCRv5_server_det_infer.tar "$BASE/ch_PP-OCRv5_server_det_infer.tar"
curl -fL -o ch_PP-OCRv5_server_rec_infer.tar "$BASE/ch_PP-OCRv5_server_rec_infer.tar"
curl -fL -o ppocr_keys_v1.txt https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/ppocr_keys_v1.txt
for t in ch_PP-OCRv4_det_infer ch_PP-OCRv4_rec_infer ch_PP-OCRv5_server_det_infer ch_PP-OCRv5_server_rec_infer; do tar -xf "$t.tar"; done
V="C:/AIWorkSpace/Studying-with-friend/.e2e/p6a/venv/Scripts"
"$V/paddle2onnx.exe" --model_dir ch_PP-OCRv4_det_infer        --model_filename inference.pdmodel --params_filename inference.pdiparams --save_file ppocrv4-mobile-det.onnx  --opset_version 16
"$V/paddle2onnx.exe" --model_dir ch_PP-OCRv4_rec_infer        --model_filename inference.pdmodel --params_filename inference.pdiparams --save_file ppocrv4-mobile-rec.onnx  --opset_version 16
"$V/paddle2onnx.exe" --model_dir ch_PP-OCRv5_server_det_infer --model_filename inference.pdmodel --params_filename inference.pdiparams --save_file ppocrv5-server-det.onnx    --opset_version 16
"$V/paddle2onnx.exe" --model_dir ch_PP-OCRv5_server_rec_infer --model_filename inference.pdmodel --params_filename inference.pdiparams --save_file ppocrv5-server-rec.onnx    --opset_version 16
sha256sum *.onnx ppocr_keys_v1.txt | tee sha256_local.txt
```

- **URL 留痕**：开工时以 PaddleOCR 官方 GitHub（docs/models.md 或 README 模型表）核对四个文件名与体积，把最终 URL+字节数+`sha256_local.txt` 记进 `.e2e/p6c/m4/m4_models.md`（MEASURED）。任一 curl 404 → 改用 modelscope 镜像 `RapidAI/RapidOCR` 模型库（文件清单现查现记）；镜像也失败 → 停手报老板，不猜 URL。
- **验证**：四个 onnx 生成且体积 >1MB；`python -c "import onnxruntime as o; o.InferenceSession(r'.e2e/p6c/m4/ppocrv4-mobile-rec.onnx', providers=['CPUExecutionProvider'])"` 等 4 条逐个可加载。

### T4.2 跑分（replica 已支持 --models/--det-name/--rec-name，命令级对照）

固定 5 组（同 20 页、M3 最优输入组合、dpi=140 生产渲染）：

| 组 | --models | --det-name | --rec-name | --dict |
| --- | --- | --- | --- | --- |
| M0 v5 mobile 基线（最优输入组合重跑） | .e2e/p6a/onnx | 默认 | 默认 | ppocrv5_dict.txt |
| M1 v4 mobile det+rec | .e2e/p6c/m4 | ppocrv4-mobile-det.onnx | ppocrv4-mobile-rec.onnx | .e2e/p6c/m4/ppocr_keys_v1.txt |
| M2 v4 rec + v5 mobile det | .e2e/p6a/onnx（det）混合：先用 .e2e/p6c/m4 跑 M1，再单独建目录 m4_mix（拷 v5 mobile det + v4 rec） | ppocrv5-mobile-det.onnx | ppocrv4-mobile-rec.onnx | ppocr_keys_v1.txt |
| M3 v5 server rec + v5 mobile det | .e2e/p6c/m4_mix2（拷 v5 mobile det + v5 server rec） | ppocrv5-mobile-det.onnx | ppocrv5-server-rec.onnx | ppocrv5_dict.txt |
| M4 v5 server det+rec（仅当 M3 组 median <0.93 或过闸 <16 才跑） | .e2e/p6c/m4 | ppocrv5-server-det.onnx | ppocrv5-server-rec.onnx | ppocrv5_dict.txt |

混合目录命令（执行时照抄）：

```bash
mkdir -p .e2e/p6c/m4_mix .e2e/p6c/m4_mix2
cp .e2e/p6a/onnx/ppocrv5-mobile-det.onnx  .e2e/p6c/m4_mix/    # M2 组：v5 mobile det
cp .e2e/p6c/m4/ppocrv4-mobile-rec.onnx    .e2e/p6c/m4_mix/    #        + v4 rec
cp .e2e/p6a/onnx/ppocrv5-mobile-det.onnx  .e2e/p6c/m4_mix2/   # M3 组：v5 mobile det
cp .e2e/p6c/m4/ppocrv5-server-rec.onnx    .e2e/p6c/m4_mix2/   #        + v5 server rec
```

（server rec 的字典仍是 ppocrv5_dict.txt，v4 rec 是 6623 行 ppocr_keys_v1——**字典必须跟 rec 模型走**。）

- 每组记录：median、过闸(20)、CER（真值页交集）、模型总体积、PC 单页耗时 → `.e2e/p6c/m4_models.md` 对照总表 + 推荐组合。
- **验证**：表 5 组（或按条件 4 组 + 1 组 SKIPPED 记录）齐全、json 落盘；手机端耗时预估 = PC 单页 ×3-5，标注 UNMEASURED（Phase 1 真机实测把关）。

---

## 7. M5 CER 真值与达标判定

1. 复用 P6a 真值 8 页（`.e2e/p6a/gt_fixed2/p003,p032,p062,p081,p092,p122,p247,p384.txt`）+ 三视角 CER 工具 `t3_cer3.py`：先读其头注释确认 CLI 与输入格式，把推荐组合在这 8 页的输出喂进去算 CER；适配结论（CLI 或调用方式）记进报告。
2. 若 `t3_cer3.py` 不适配新输出格式，启用兜底 `.e2e/p6c/cer.py`（**代码（完整）**，口径：去空白后字符级编辑距离/真值长度）：

```python
"""用法: from cer import cer; cer(真值文本, 识别文本) -> 0.0~1.0"""
def cer(ref: str, hyp: str) -> float:
    r = [c for c in ref if not c.isspace()]
    h = [c for c in hyp if not c.isspace()]
    dp = list(range(len(h) + 1))
    for i, cr in enumerate(r, 1):
        prev = dp[0]
        dp[0] = i
        for j, ch in enumerate(h, 1):
            cur = dp[j]
            dp[j] = min(dp[j] + 1, dp[j - 1] + 1, prev + (cr != ch))
            prev = cur
    return dp[-1] / max(len(r), 1)
```

3. 若真值页与 20 页评测集交集 <5 页：用 `gt_transcribe.py` 同款视觉调用补转写 ≤10 页（龙猫 vision，凭据走 ai_client 档案库，key 不落对话不落代码）。
4. 判定基线（Phase 0 报告里申报，最终老板定稿）：过闸页 CER ≤8% 且真值页 median ≥0.93。
5. 局限声明：视觉转写错误率非零，是近似真值。

---

## 8. M6 收口报告与老板闸门

- 产出 `docs/plans/P6c-Phase0-报告.md`：M1 证据结论、M2 复刻结论、M3 归因表、（M4）模型对照、M5 CER、**推荐组合**（含：是否换模型、预期兜底页数换算、体积/耗时/内存代价、手机端耗时预估）、验收判据定稿建议。
- **硬闸门：老板确认选型与判据后，才立 Phase 1 小计划案（过评分）开工。**
- 同步更新 `docs/模块总览/P6-OCR-PoC模块总览.md`（Phase 0 不改产品代码，只追加实验结论与资产指针）。

---

## 9. 验证与回归策略（对应老板 A/B/ABC 要求）

| 层级 | 本计划对应 | 具体做法 |
| --- | --- | --- |
| 小功能即验 | T2.1→T2.2；T2.3 是 T2.1 的验；T3.2 每组跑完即出该组数字 | 每个任务有「过/不过」判据，不过不进下一步 |
| B 后回查 A | M3 全部组跑完：重跑 T2.2/T1.2 单测 + G00_base 生产配置重跑 T2.3 对账（≥20/24，复刻未漂移）；M4 换模型后重跑全部单测（纯函数与模型无关） | base 对账掉到 <20 立即停下归因，不带病扫描 |
| ABC 合体验证 | M6 收口：M1-M5 结论交叉复核 + 最优组合 20 页终跑总表 + 判据换算全本兜底页数 | 报告里数字必须能互相推导对上 |
| 整体质检 | Phase 1 末：模拟器全本导入 461 页 + 判据表 ①-⑥ + 全量单测 | Phase 1 计划案过评分后执行 |

---

## 10. 风险与预案

| # | 风险 | 触发条件 | 预案 |
| --- | --- | --- | --- |
| R1 | 复刻对不上（渲染器差异/插值/裁剪细节） | T2.3 两轮修复后仍 <20/24 | 跑渲染像素检查：pymupdf 渲染 p001/p021/p101 与手机同名 PNG 逐像素 `cv2.absdiff` 非零占比并记录；占比 >5% → M3/M5 全链路改用 24 张手机 PNG 页集（报告声明页集缩小、代表性下降）；≤5% → 差异在管线不在渲染，继续修 replica |
| R2 | rapidocr 锚点复现不出 0.97 档 | 锚点组 20 页 median 与 M1 出处值差 >0.05 | 锚点降级为纯方向参照（不进任何判据）；用 M1 钉死的当年页集重跑一次对齐口径，仍不齐则报告声明「锚点仅作相对比较」，不影响 M3 归因结论 |
| R3 | server/v4 模型下载失败 | M4 | modelscope 镜像 / 本地 paddle2onnx 转换双路；URL 以官方 models.md 现查为准 |
| R4 | 视觉真值质量存疑 | M5 | 近似真值声明 + 老板可选人工录 1-2 页金标准 |
| R5 | 评分员口径漂移 | 连续轮次 | 评分卡固化在脚本；每轮留档可追溯 |
| R6 | unclip 分支代码引入缺陷 | T2.2/T2.3 | unclip 不在生产复刻路径上，T2.2 先过纯函数测试再入扫描 |

---

## 11. 成本与耗时预算

| 项 | 量化 | 对比 |
| --- | --- | --- |
| LLM 调用 | 评分 3-6 次（每次 ~2k tokens）；真值补转写 ≤10 次 vision | 对比现状：每本 461 页书 401 次视觉兜底，Phase 0 成本可忽略 |
| PC 机时 | M2 对账 B ~17min + M3 扫描 ~40min + M4 ~1h ≈ **<2.5h** | 原 Phase 0 预算 0.5-1 天，本细化不超 |
| 模拟器 | **Phase 0 零占用** | 全部留 Phase 1 |
| 网络下载 | 仅 M4 触发时 ~200-400MB 模型文件 | 不达标才花 |

---

## 12. 纪律承诺（继承交付文档 §6 全文，此处只列执行要点）

1. 每步过评分 ≥90 才动；策略与代码同标准。
2. 危险操作（删除/迁移/依赖变更/Git 破坏性）与判据数值变更 → 人话报老板确认。
3. 凭据：key 只走环境变量/档案库，输出打码；`.e2e/` 不入库。
4. Phase 0 结束更新 `docs/模块总览/P6-OCR-PoC模块总览.md`；改产品代码的 Phase 1 按总览流程走。
5. 完成前三查（`check_session_close.py`）全绿 + commit；Phase 0 完成即 commit（`.e2e` 除外）；GitHub 推送按会话闸门规则。

---

**最后更新**：2026-10-05 · 基于当日源码级调查重写立项假设（0.9751 同款模型实锤），Phase 0 细化到可直接执行
