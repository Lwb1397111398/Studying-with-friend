# P6c Phase 1 · 小计划 B：模拟器 E2E 实测（动态宽 rec 端上效果验证）

> 状态：**第 5 轮 90 分过线**（R1-R5：82→84→86→85→90，留档 docs/plans/reviews/）；过线后微调=落实第 5 轮评审澄清 4 处（宽高比出处标注/LLM 预估上限/崩溃差异行定义/DB 文件名提示），不改任何判据与步骤结构。
> 上游：小计划 A 已收口（计划案 99 分、代码包 90 分、673 测试全绿，见 P6c-Phase1-质检包-A-代码.md）；老板已授权 android-emulator 插件自控模拟器（2026-10-05「你想看手机端的话可以直接使用虚拟机，有专门的插件，你可以自己控制」）。

## 0. 一句话目标

在模拟器上用真书 shpc（461 页扫描书）完整走一遍动态宽 rec 生产管线，实测四项数字——功能可用性、单页耗时、内存增量、兜底率——与小计划 A 的 PC 口径对照（PC G11 全量：401 兜底页过闸 387/401=96.5%、全书不可用页率 3.0%，P6c-Phase0-报告.md §5），并裁定 Phase 0 预登记的 R2/R3 风险是否触发。

**整体裁决规则（报告末尾按此收口）**：
- D1 不过 → 本轮 FAIL，走 §4 排查三步立案，其余三项不作数。
- D1 过 + D2/D3 均不触发 → **PASS 收口**（D4 三条为描述性锚定记录，不参与 PASS/FAIL，只入报告作后续基线）。
- D1 过 + D2 或 D3 任一触发 → 数字并陈报老板，按 A 包 §5 fallback 链走（hybrid 立项或两案拍板），不自动回滚。
- D4 任一条不过 → 不判 FAIL，立案归因（渲染差异/缩放插值/模型数值精度逐项排除）后报老板。

## 1. 环境与素材（全部现成，MEASURED 存在性）

| 项 | 值 | 出处 |
| --- | --- | --- |
| 模拟器 | JingBianAVD（本机唯一 AVD，`~/.android/avd/`） | 本机实存 |
| 自控工具 | android-emulator 插件（android_build_and_run / android_screenshot / android_ui_describe / android_ui_tap / android_logs 等工具族） | 本会话工具清单 |
| 测试书 | `.e2e/shpc.pdf`（19.6MB，461 页扫描书，S7 两次全本导入同一本） | .e2e/ 实存 + docs/plans/P6b-落地报告.md §3 |
| 基线数字（旧 rec 固定 320） | OCR 阶段总耗时 ≈16.6-22.7min；inferMs 15.4-21.4min（折单页 2.0-2.8s）；兜底 401 {LOW_CONF=396, DIAGRAM=5}；页均 median 0.86 | docs/plans/P6b-落地报告.md §3 S7-2 两轮（MEASURED） |
| PC dyn 对照数字 | 401 兜底页过闸 387/401=96.5%、页 median 中位 0.9225、单页中位 7.7s | P6c-Phase0-报告.md §5（MEASURED） |
| OCR 模型 | 模拟器 filesDir/ocr_models 三件套若因 AVD 重置缺失 → 走 app 内 OcrModelDownloader 下载（Release tag ocr-models-v1） | docs/plans/P6b-落地报告.md §S5 |
| 草稿取证机制 | DEBUG 构建兜底页草稿落 `filesDir/ocr_debug/d%03d.txt`（行文本+置信度三位小数）；`adb pull` 回 PC 与 matchB_dyn.json 对账 | docs/plans/P6b-落地报告.md §7（画线复核对同机制 401 页草稿全量重算过） |

### 1.1 复用清单（集中列表）

| 复用项 | 路径/工具 | 用途 | 出处 |
| --- | --- | --- | --- |
| shpc.pdf 测试书 | .e2e/shpc.pdf | 461 页扫描书输入（与 S7 基线同书同条件） | docs/plans/P6b-落地报告.md §3 |
| analyze_line.py 草稿重算 | .e2e/analyze_line.py（不入库；本机实存，可用性已实证：对 .e2e/drafts_shpc 401 份旧草稿跑通输出分布） | 读 d*.txt 重算每页 median/lowRatio/过闸模拟 | P6b 画线复核（已实证 401 页） |
| matchB_dyn.json 逐页参照 | .e2e/p6c/matchB_dyn.json（不入库，聚合值入库 Phase 0 报告 §5）；**若缺失的兜底再生成命令**：`python .e2e/p6c/replica_ocr.py --pdf .e2e/shpc.pdf --rec-width dyn --out .e2e/p6c/matchB_dyn.json`（venv 依赖 .e2e/p6a/，PC 机时 ≈56min，Phase 0 实测） | PC G11 逐页对照真值。schema（replica_ocr.py:288-292 实测产出）：`{"cfg":{...},"pages":[{"page":int,"n_lines":int,"median":float,"gate":"LOW_CONF"\|null,"low_ratio":float,"secs":float,"lines":[[文本,conf],...]}]}`——gate 仅 "LOW_CONF" 或 null（replica 无 DIAGRAM 分支，PC dyn 不产 DIAGRAM） | P6c-Phase0-报告.md §5 |
| reconcile_b.py 逐页对账（新写，~40 行） | .e2e/p6c/reconcile_b.py（完整代码内联于本文 §2-D4） | 手机草稿 median vs PC 逐页相关性/一致率 | 本计划案 |
| OcrModelDownloader 模型就绪链 | 生产代码（S5） | 模拟器模型缺失时自愈下载 | docs/plans/P6b-落地报告.md §S5 |
| logcat Pass1/Pass2 分离计时 | 生产日志（b92db18） | 逐页 inferMs 与总耗时采集，零侵入 | P6b-落地报告.md §4 |
| A 包 fallback 链 | P6c-Phase1-质检包-A-代码.md §5 | R2/R3 触发后处置路径 | 小计划 A |
| PageGate 判定常量 | 生产 OcrImportRunner（0.85/0.5/20%） | 对账判定量与生产同口径 | P6b S7 |

## 2. 判定项与判据（四项，数字先行）

| # | 判定项 | 判据 | 通过 | 触发 |
| --- | --- | --- | --- | --- |
| D1 功能可用 | 动态宽 rec 在端上完整跑通 461 页导入出书，无崩溃 | **完成标志三件（客观）**：①logcat 出现 `OcrImport: Pass2 done: pages=461 fallbacks=... byReason=...` 行（OcrImportRunner.kt:192-198 既有日志，管线终点）；②书架出现 shpc 新书（`android_screenshot`）；③DB 章数>0（run-as sqlite 取证）。三件齐 = 走完 | 三件齐 → D1 过 | 崩溃/中途失败 → 按 §4 排查三步立案，不评 quality | — |
| D2 耗时 | OCR 阶段总耗时 + Pass1 单页均值（`Pass1 done: pages=461 renderMs= inferMs= postMs=` 汇总行，inferMs÷461；OcrImportRunner 无逐页日志行，观测不干预不新加） | 报告绝对值；**触发线=单页均值 >15s**（**2026-10-06 老板拍板由 5s 上调至 15s**——首轮实测 12.36s>5s 触发上报后三选一拍板，判据值修订反映端上实测水平，无代码改动；原 5s 线=P6c-Phase0-报告.md §7 闸门④拍板线，口径=全流程单页）。**注**：PC dyn 实测 7.7s/页中位系 PC CPU 参考值，跨硬件不可直接比较 | ≤15s：R2 不触发 | >15s：R2 触发 → 按 §5 fallback 链报老板（含触发线再评估议题） |
| D3 内存 | 导入前后 `dumpsys meminfo com.studyfriend.app` PSS 增量 | **触发线=增量 >256MB**（同源拍板线；S7 基线 arena ~208MB） | ≤256MB：R3 不触发 | >256MB：R3 触发 → 同上 |
| D4 质量（核心） | 兜底页数 + 页 median 分布，草稿对账 PC G11（兜底页数双数据源：`Pass2 done` 日志 byReason 合计〔LOW_CONF+DIAGRAM〕=系统判定 + reconcile_b.json mobile_fallback_pages=草稿文件数〔兜底页全量落盘、含 DIAGRAM 零行页〕，两值差 ≤2 页视为一致——容差容纳置信度三位小数落盘舍入的边界页，>2 页立案归因后择一写报告；median 分布走草稿重算） | **锚定判据（首测定锚，描述性指标非产品 gate——不动 0.85 生产门控）**：①逐页 median 相关系数 r≥0.85（手机 dyn vs PC G11；计算总体=手机兜底页∩PC 全量——草稿仅兜底页落盘，全集逐页 median 端上不采集〔观测不干预〕；兜底页 <10 页时 r 统计不稳，届时以②为主、r 降级为参考值写入报告）；②页级过闸一致率 ≥90%（分母=PC 全量 461 页；端上过闸/兜底口径=草稿有无〔兜底页全量落盘机制保证〕，PC 口径=gate∈{LOW_CONF,DIAGRAM}；DIAGRAM 页预期不一致〔PC 无该判定分支，量级 ≈5 页=S7 基线〕，在 10% 预算≈46 页内）；③兜底页数 ≤28 页（**暂定值**：推导=PC dyn 全书不可用页实测 14/461=3.0%（P6c-Phase0-报告.md §5，MEASURED）×2 保守余量；×2 系工程余量无实测上界支撑，首轮实测后报老板校准）。三条为本次首测锚定值，写进 B 报告作为后续迭代基线 | 三条全过：D4 过 | 任一条不过：不判失败，立案归因（渲染差异/缩放插值/模型数值精度逐项排除）后报老板 |

**D4 对账方法**：`adb pull` ocr_debug 草稿 → PC 侧 `.e2e/analyze_line.py`（P6b 画线复核脚本，读 d*.txt 重算每页 median/lowRatio）+ 下述 reconcile_b.py 对照 `.e2e/p6c/matchB_dyn.json`（PC G11 逐页，schema 见 §1.1）做相关性/一致率——沿用 P6b 画线复核的既有取数路径（401 页草稿全量重算先例）。**DIAGRAM 注记**：DIAGRAM 由模型端判定（手机生产 byReason 可见，S7 基线 DIAGRAM=5），草稿零行无法复现 reason 明细——只影响 reason 拆分、不影响过/不过一致率（重算把 DIAGRAM 页按兜底计），reason 拆分一律以 byReason 日志为准；PC dyn 对照侧无 DIAGRAM 分支（gate 仅 LOW_CONF/null）。

**reconcile_b.py 完整代码（执行时按此落盘 .e2e/p6c/reconcile_b.py，~60 行）**：

```python
# -*- coding: utf-8 -*-
"""P6c Phase 1 B 对账：手机 dyn 草稿 vs PC G11（matchB_dyn.json）逐页对照。
用法：python reconcile_b.py --drafts <ocr_debug目录> --ref <matchB_dyn.json> --out <输出json>
口径注记：median=上中位 sorted[len//2]，与 PC replica page_gate / 手机生产 PageGate.decide /
analyze_line.py 逐位同款；草稿仅兜底页落盘（LOW_CONF+DIAGRAM），无草稿页=端上过闸；
DIAGRAM 由模型端判定、草稿零行无法复现 reason（重算按兜底计，过/不过口径不受影响）。"""
import argparse, glob, json, math, os, re, sys

def page_medians(draft_dir):  # 解析同 analyze_line.py（P6b 已对 401 页实证）
    out = {}
    for f in sorted(glob.glob(os.path.join(draft_dir, "d*.txt"))):
        m = re.search(r"d(\d+)\.txt$", os.path.basename(f))
        if not m:
            continue
        confs = []
        for ln in open(f, encoding="utf-8").read().splitlines():
            if "\t" not in ln:
                continue
            try:
                confs.append(float(ln.rpartition("\t")[2]))
            except ValueError:
                continue
        confs.sort()
        if not confs:  # DIAGRAM/零行兜底页：gate 可复现（按兜底计），median 不可复现
            out[int(m.group(1))] = {"median": None, "gate": "LOW_CONF"}
            continue
        med = confs[len(confs) // 2]
        low = sum(1 for c in confs if c < 0.5) / len(confs)
        out[int(m.group(1))] = {"median": med,
                                "gate": "LOW_CONF" if (med < 0.85 or low > 0.20) else None}
    return out

def pearson(xs, ys):
    n = len(xs)
    if n < 2:
        return 0.0
    mx, my = sum(xs) / n, sum(ys) / n
    sx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    sy = math.sqrt(sum((y - my) ** 2 for y in ys))
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / (sx * sy) if sx and sy else 0.0

ap = argparse.ArgumentParser()
ap.add_argument("--drafts", required=True)
ap.add_argument("--ref", required=True)
ap.add_argument("--out", required=True)
a = ap.parse_args()
mob = page_medians(a.drafts)
pc, skipped = {}, []
for p in json.load(open(a.ref, encoding="utf-8"))["pages"]:
    if not {"page", "median", "gate"} <= set(p):
        skipped.append(p.get("page", "?"))  # 字段缺失：warn 后跳过，不崩
        continue
    pc[p["page"]] = p
if skipped:
    print(f"warn: ref 缺 page/median/gate 字段，跳过 {len(skipped)} 条: {skipped[:10]}", file=sys.stderr)
rows, med_pairs = [], []
for p in sorted(pc):  # 对账全集=PC 全量页（461）；一致率走系统级口径（草稿有无=兜底与否）
    m = mob.get(p)
    m_fb = m is not None
    rows.append({"page": p, "mobile_fallback": m_fb,
                 "mobile_recalc_gate": m["gate"] if m else None,  # 留档：边界舍入诊断用
                 "pc_gate": pc[p].get("gate"),
                 "gate_agree": m_fb == (pc[p].get("gate") in ("LOW_CONF", "DIAGRAM"))})
    if m and m["median"] is not None:
        med_pairs.append({"page": p, "mobile_median": round(m["median"], 4),
                          "pc_median": round(pc[p]["median"], 4),
                          "diff": round(m["median"] - pc[p]["median"], 4)})
summary = {
    "pearson_r": round(pearson([r["mobile_median"] for r in med_pairs],
                               [r["pc_median"] for r in med_pairs]), 4) if len(med_pairs) >= 10 else None,
    "pearson_n": len(med_pairs),  # <10 时 r 置 null（小样本统计不稳），一致率为主
    "gate_agree_rate": round(sum(1 for r in rows if r["gate_agree"]) / len(rows), 4) if rows else 0.0,
    "gate_agree_pages": len(rows),
    "mobile_fallback_pages": len(mob),  # 草稿文件数=系统兜底页总数代理（含 DIAGRAM；对账 byReason 合计）
    "pc_fallback_pages": sum(1 for v in pc.values() if v.get("gate") in ("LOW_CONF", "DIAGRAM")),
    "pc_unknown_gate_pages": sum(1 for v in pc.values()
                                 if v.get("gate") not in ("LOW_CONF", "DIAGRAM", None)),
    "skipped_ref_pages": len(skipped),
}
json.dump({"summary": summary, "pages": rows, "median_pairs": med_pairs},
          open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps(summary, ensure_ascii=False))
```

## 3. 操作步骤（编号可复现，全部老板已授权的自控范围）

1. `cd android && ./gradlew :app:assembleDebug`（已过，V3）。
2. android-emulator 插件 `android_build_and_run`（或已装则 `android_list_devices` → `android_start_emulator` JingBianAVD）。
3. `android_install_app` 覆盖安装本包 APK（**安装前先确认上一轮取证产物已 adb pull 归档**，见 §6）；`android_launch_app` 启动。
4. `adb push .e2e/shpc.pdf /sdcard/Download/`。
5. 设置页检查（UI 路径：书架 → 底部导航「设置」 → 「扫描书本地识别」开关，P6b S5 上线时实测可达；`android_ui_describe` 期望元素至少含文本「扫描书本地识别」与视觉兜底配置输入框；**定位二级回退**：describe 检索不到预期文案时改用 `android_screenshot` 截图目视定位、按截图坐标 `android_ui_tap`（坐标读自截图非假想值），截图仍无法定位才停下核实导航结构）：开关置 ON；模型就绪态校验双路：①UI 同屏显示就绪或「下载识别模型」按钮（未就绪点按钮走 OcrModelDownloader，`android_logs` 盯完成）；②命令复核 `adb shell "run-as com.studyfriend.app ls files/ocr_models/"` 期望恰 3 文件（正则 `ppocrv5-mobile-(det|rec)\.onnx|ppocrv5_dict\.txt`，文件名出处 OcrEngine.kt:151-153）；视觉兜底未配置确认：设置页视觉地址/Key 输入框为空（`android_screenshot` 留证；导入后再用 DB 复核 vision_queue=0，见步骤 9）。
6. 导入交互序列（前置半步：先 `android_ui_describe` 一次性拿当前屏元素树，确认 FAB/导航真实 contentDescription 再点，不按假想坐标盲点；describe 定位不到时同步骤 5 二级回退：截图目视 + 坐标点击）→ `android_ui_tap` 书架导入 FAB（右下角 +）→ SAF 文件选择器（`android_ui_describe` 定位侧栏「Download」入口 → `android_ui_tap`）→ 文件列表 `android_ui_tap` shpc.pdf → OCR 模式确认对话框 `android_ui_tap` 确认 → `android_screenshot` 确认进度页出现（进度条+ETA 文案）。
7. 导入进行中：**每 30s 轮询一次** `android_logs`，筛选 `OcrImport` tag 行（`Pass1 done:`/`Pass2 done:`/`vertical prefilter:`）；墙钟超 2h 无 Pass1 done 视为阻塞立案。判定数据源=汇总行（Pass1 done 的 renderMs/inferMs/postMs、Pass2 done 的 fallbacks/byReason/pass2Ms），不逐页刷屏。
8. 完成后：`adb shell dumpsys meminfo com.studyfriend.app` 记 PSS（导入启动前先取一次基线快照，步骤 6 前执行）；确认页 `android_screenshot` 记 parseNote 四件套文案数字。
9. 取证归档：`adb pull` ocr_debug/ 草稿目录 + DB 取证（`adb shell "run-as com.studyfriend.app ls databases"` 先探明库文件名——预期形如 `<名>.db` 伴随 `-wal`/`-shm` 两个伴生文件（Room 标准 WAL 三件套），以 ls 实际输出为准，再 `run-as ... sqlite3` 查章数/段数/`SELECT count(*) FROM vision_queue` 预期 0 行——P6b S7 同款口径）→ 全部落 `.e2e/p6c/b_evidence/`（不入库）。
10. PC 侧对账（D4）：`python .e2e/analyze_line.py .e2e/p6c/b_evidence/ocr_debug`（重算每页 median/lowRatio）→ `python .e2e/p6c/reconcile_b.py --drafts .e2e/p6c/b_evidence/ocr_debug --ref .e2e/p6c/matchB_dyn.json --out .e2e/p6c/b_evidence/reconcile_b.json`（脚本完整代码见 §2-D4 后）→ 写 B 报告（输出格式：reconcile_b.json 含逐页 {page, mobile_fallback, mobile_recalc_gate, pc_gate, gate_agree}（全集=PC 461 页）+ median_pairs {page, mobile_median, pc_median, diff}（仅手机兜底非空草稿页）+ 汇总 {pearson_r〔兜底页 <10 时为 null〕, pearson_n, gate_agree_rate, mobile_fallback_pages〔草稿文件数=系统兜底总数代理〕, pc_fallback_pages, pc_unknown_gate_pages, skipped_ref_pages}）。

## 4. 风险与预案

| 风险 | 触发 | 预案 |
| --- | --- | --- |
| 模拟器与真机性能差异 | x86_64 主机 CPU vs 真机 ARM，绝对数字不可直接外推真机 | 报告中所有数字标注「模拟器」口径；真机联测另约（P6b 起惯例）；R2/R3 裁定仍按模拟器实测对照拍板线执行（拍板线制定时的数据源同为模拟器 S7，口径自洽） |
| D2 >15s（R2 触发；原 5s，2026-10-06 拍板上调） | 实测 | 按 A 包 §5 fallback 链：hybrid 小计划案送评 → 仍不达标两案并陈报老板；不自动回滚 |
| D3 >256MB（R3 触发） | 实测 | 同上 + REC_BATCH 8→4 评估项一并呈报 |
| D1 崩溃 | 任意异常栈 | **排查三步（按序）**：①`android_logs` 抓全栈，与 PC replica_ocr.py 同输入跑一遍对照差异行（差异行定义：PC 侧行文本取 matchB_dyn.json 该页 `lines` 字段，与手机草稿 d*.txt 同页逐行对齐——同序行文本不同、或文本一致但置信度差 >0.05，均记差异行；差异行聚集处即端上特有行为，PC 口径已验证）（PC 口径已验证，差异即端上特有）；②查崩溃帧是否 Bitmap 尺寸/内存相关（createBitmap 负宽高、OOM——对照步骤 7 日志最后正常页号定位现场）；③校验 rec session 输入 shape（batchW 预期值域 320-1488：下限=REC_MIN_W、上界=实测最大宽高比 31×48≈1488，见 §5 定义；出现 0/负数/超 4096 即 R4 联动——端上无逐批日志（观测不干预），异常值从崩溃栈 ONNX shape 错误信息读取）→ 定位后立案修复，修复代码另走质检循环 |
| 模型未就绪/下载失败 | 设置页态 | OcrModelDownloader 重试与降级路径 P6b 已验证；再失败则停下报老板 |
| 导入中途 AVD 卡死 | 墙钟>2h 无日志推进 | **三步 SOP**：①`android_stop_emulator` 停机，`android_screenshot` 留最后画面；②`adb logcat -d > .e2e/p6c/b_evidence/logcat_stuck.txt` + `dumpsys meminfo` 归档现场；③冷启动 `android_start_emulator` → 校验 APK 仍在（`adb shell pm list packages | grep studyfriend`，不在则重装）→ 从步骤 4 起（shpc.pdf 已在 /sdcard/Download 一般无需重推，`adb shell ls /sdcard/Download` 确认）重走导入；第二次再卡死即停报老板，不再重试 |

## 5. 成本预算（前后对比申报）

**4.7× 像素倍数推导**：旧 rec 每框输入 3×48×320 固定（960 页面元/框）；新 rec 每框 3×48×min(rw,batchW)，rw=ceil(w×48/h)——**batchW 定义：批内统一定宽，即 OcrRecPre.batchRecWidth 返回值（P6c-Phase1-质检包-A-代码.md §2.1，OcrEngine.kt OcrRecPre 对象），非模型上限也非渲染宽**；实测批内最大宽高比 ≈31（.e2e/p6c/m2_code_bundle.md §3 风险册，不入库；该值已内联于 P6c-Phase1-质检包-A-代码.md §1 出处索引）→ 最大批宽 1488 vs 320 = 4.65×；普通正文行（宽高比 6-15，[推断]——扫描书正文行典型量级，无逐行统计）单框 288-720 宽 vs 320 固定 ≈0.9-2.25×；长行（比例 25-31，MEASURED——.e2e/p6c/m2_code_bundle.md §3 实测，已内联质检包 A §1；S7 实测 401 兜底页主体）才是 4-4.7× 集中来源。

| 项 | S7 基线（旧 rec） | 本计划 B 预期 | 标注 |
| --- | --- | --- | --- |
| 单轮导入墙钟 | 16.6-22.7min | [推断] 60-115min（长行 rec 像素 4-4.7× 直乘 inferMs 15.4-21.4min ≈ 72-100min，取整加渲染余量）；模拟器 x86 实测可能偏离，以步骤 7 日志为准 | [推断]（推导如上） |
| 机时拆分 | — | 模拟器端：导入 60-115min + 取证/截图 ≈15min；PC 侧：对账脚本+报告 ≈30-60min；**合计 ≈1.75-3.2h（分项直加，不含 D4 二轮导入）**；若触发二轮导入再 +90-130min | [推断] |
| LLM 调用（全程预估） | — | 本计划案评分 1-2 轮；B 报告如需质检 1-2 轮；第二轮导入（条件触发）不增 LLM 调用；合计 ≈2-4 次成功调用（若评分不过线循环，最坏 ≈6-8 次封顶），单次输入 ≤8k token + 输出 ≤2k token；评分与质检均走本机 Agent Manager 档案库测试凭据（ai-apis.json main 档案，免费额度内），**无现金成本** | 预估 [推断] |
| D4 判定若需第二轮导入 | — | 仅在第一轮数字异常时重导（S7 两轮逐位一致先例说明管线确定性，正常 1 轮足够） | 条件触发 |

## 6. 纪律合规

- 判据零变更：0.85 门控/兜底逻辑/护栏全部不动；R2/R3 线用 Phase 0 已拍板值；D4 三条锚定值为**描述性实验指标**（对照依据），非产品 gate，收紧须报老板。
- 观测不干预：模拟器操作全部为既有功能正常使用路径，无 debug 代码、无日志侵入（复用既有 logcat 行）。
- 危险操作确认：①本计划不做 AVD 重置/wipe；②覆盖安装 APK 前先 pull 归档上轮取证产物（步骤 3 内置）；③若 D1 崩溃需卸载重装或清 app 数据，先归档再操作并报老板备案。
- 凭据纪律：模拟器不登录任何账号；OCR 模型走 Release 下载无需凭据；本计划全程不涉 API key（视觉兜底保持未配置）。
- `.e2e/` 取证产物不入库；报告入 `docs/plans/`。
- **收尾闸门检查清单（B 收口时逐项打勾）**：
  - [ ] 取证产物归档 .e2e/p6c/b_evidence/（草稿/meminfo/日志/reconcile_b.json）
  - [ ] B 报告落 docs/plans/（含 D1-D4 四项判定与数字）
  - [ ] M2 模块总览更新（动态宽口径 + B 实测新坑）
  - [ ] 三查工具全绿（正斜杠传参）
  - [ ] commit（P4 统一）+ 人工过 diff + push（整体质检后）
- 数字纪律：报告里每个数字标 MEASURED/UNMEASURED/[推断]；模拟器口径显式声明。

## 7. 不做什么

- 不做真机测试（无真机设备；模拟器为老板指定路径）。
- 不改任何生产代码（D1 失败例外，届时另立案）。
- 不调 0.85 阈值、不动 hybrid（触发才立项另评）。
- 不跑视觉兜底链路（保持与 S7 基线同条件，单变量对照）。
