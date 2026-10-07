# P6c 小计划 C —— 视觉兜底配置与端上取证闭环（v3）

> v3 修订说明：按第 2 轮评分 63 分的 8 条意见——C1 开关补真点击+checked 断言、删 km 无效断言（Key 写入改 DB 侧唯一强断言）、补 saf_import.py 与 diff_vision_ocr.py 全文、J1 具体路径+轮次规则、meminfo 轮询 PID 捕获+kill、C1 降级链逐级化（废弃「受控文件」方案）、成本 N/M 数值化（3-5 次/90-150K tokens）。
> v2 修订说明：按第 1 轮评分 65 分的 8 条意见——C1/C2 补代码骨架、C3 命令全内联、判据落到 SQL、成本补机时与 token 换算、F 表加 MEASURED/推断标注、旧书确认闸门显式化、C5 展开 checklist、日志条数动态化。

## 0. 一句话目标

配置商汤视觉 API 到 App 视觉兜底（老板 2026-10-06 授权自配测试 API），用仿真扫描书快速验证「兜底页入队→视觉转写→整书重建」链路后，重导 shpc 461 页闭环三项遗留：①fallback 页码+bboxHw 日志（代码改动）②meminfo_timeline.csv 内存曲线 ③47 兜底页视觉增强补齐，并做新旧 A/B 对账。

## 1. 前置事实

| # | 事实 | 标记 | 出处 |
| --- | --- | --- | --- |
| F1 | 商汤 vision 档案：id=`20261004174350-39d5`「识图小模型」，caps 含 vision，base=`https://token.sensenova.cn/v1`，模型 `sensenova-6.8-flash-lite`；key 存本机档案库 `C:\Agent\AImanager\ai-apis.json`（输出一律打码） | MEASURED（档案库实查 2026-10-06） | ai-apis.json |
| F2 | 商汤识图 PC 实测：p041.png（shpc 乱码页，OCR 侧「劳工保险条例」→「带工煤险条」）整页转写 **16.4s**，标题+正文全对、引注圈码保留；对照龙猫 S7 实测 40s/页 | MEASURED | `.e2e/p6c/test_vision_api.py` 实测 2026-10-06；40s/页=M2 总览决策 26 |
| F3 | App 视觉配置链路：`SettingsRepository.buildVisionTranscriber()`——`visionEnabled` 开关 + `resolveVisionKeyOrNull()`（视觉专属 Key 优先，留空回退主配置）+ `visionBaseUrl` 留空回退主配置 + `visionModel`；Key 经 Android Keystore 加密存 settings 表 `vision_key_enc` 行，**UI 填写是唯一正路** | MEASURED（源码实查） | SettingsRepository.kt:211-217、238-249 |
| F4 | 设置页三输入框：视觉 API 地址 / 视觉 API Key / 视觉模型名 + 开关（即点即存） | MEASURED | SettingsScreen.kt:103-160 |
| F5 | 兜底页入队条件=重导时 `buildVisionTranscriber()` 非 null（视觉未配置只提示不入队）；**无补入队机制，重导是已导入书补齐的唯一路径**；队列全部终态后 `VisionRebuilder` 守卫式重建（有 AI 消费记录则拒绝） | MEASURED | ImportViewModel.kt:372、VisionRebuilder.kt |
| F6 | `FallbackPage(pageNo, reason)` 已带页码，仅 Pass2 done 日志只打 byReason 计数；DIAGRAM 判定=`classifyPage` 内 `w>12.3pt 且 h/w>3.0` | MEASURED | OcrImportRunner.kt:168-198、OcrTextPostProcessor.kt:86-101 |
| F7 | B 报告缺口：22 页 DIAGRAM 仅 21 页页码可推定（1 页缺口）；bbox h/w 无一级量化 | MEASURED | P6c-Phase1-报告-B-模拟器E2E.md §4/§10 |
| F8 | 仿真扫描书产物已存在：`.e2e/sample_book_scanned.pdf`（PyMuPDF 140dpi 图像型 PDF，22 页 8 章，S7 实证与生产渲染同规格） | MEASURED | .e2e/ 目录、P6b-落地报告.md 决策 33-⑥ |
| F9 | 视觉转写机制：单页读超时 180s、失败重试 1 次（VisionTranscriber.kt:46,90）；Worker 页级 attempts 达 3 定格 FAILED、未配置 Key 不烧 attempts 退避等重调度（VisionWorker.kt:35,101）；逐页串行无并发 | MEASURED | VisionTranscriber.kt / VisionWorker.kt |
| F10 | 视觉消化总时长 ≈47×16.4s≈12.9min（商汤）；C3a ≈22 页×16.4s≈6min | 推断（公式：页数×F2 实测页均；未计网络方差，±50%） | 本计划推算 |
| F11 | 测试基线 673 全绿+1 skipped（=P6b 664 基线+OcrRecPreTest 9）；本计划 C2 预期 673+新增 3=676 | MEASURED（基线）/推断（新增数） | P6c-Phase1-质检包-A-代码.md V2 行 |

## 2. 执行步骤

### C1 App 视觉配置（预计 20min，人工零参与）

**原则：UI 坐标一律运行时 `uiautomator dump` 动态解析 bounds 中心，不硬编码（分辨率无关）；key 只在 python 进程内从档案库读取，bash 命令行零明文。**

脚本 `.e2e/p6c/configure_app_vision.py` 全文（执行时按此落盘）：

```python
# -*- coding: utf-8 -*-
"""C1：从档案库读商汤视觉档案，adb UI 自动化填入 App 设置页。key 零明文落命令行/会话。"""
import json, re, subprocess, sys, time

ADB, PKG = "adb", "com.studyfriend.app"
ARCHIVE = r"C:\Agent\AImanager\ai-apis.json"
API_ID, BASE, MODEL = "20261004174350-39d5", "https://token.sensenova.cn/v1", "sensenova-6.8-flash-lite"

def sh(*args, timeout=40):
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout).stdout

def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml"); time.sleep(1)
    return sh("shell", "cat", "/sdcard/ui.xml")

def center(xml, needle):
    """首个 text/desc 含 needle 的节点 bounds 中心。找不到返回 None。"""
    for m in re.finditer(r'<node[^>]*(?:text|content-desc)="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if needle in m.group(1):
            l, t, r, b = map(int, m.groups()[1:]); return (l+r)//2, (t+b)//2
    return None

def tap(x, y): sh("shell", "input", "tap", str(x), str(y)); time.sleep(1)
def typ(s): sh("shell", "input", "text", s); time.sleep(0.5)  # key 经 subprocess 参数进模拟器进程，不出现在本会话/脚本字面量

def clear_field():  # 幂等清空当前焦点框：DEL×60（重复执行配置无副作用）
    for _ in range(60): sh("shell", "input", "keyevent", "67")

def fill(xml, needle, value, secret=False):
    c = center(xml, needle)
    assert c, f"未找到输入框: {needle}"
    tap(*c); clear_field(); typ(value)

def switch_row(xml):
    """视觉兜底行：text 节点定位 y，同行(±80px)内找 checkable 节点；无 checkable 则点行右侧偏移。"""
    rows = [m for m in re.finditer(
        r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml) if "视觉兜底" in m.group(1)]
    assert rows, "未找到视觉兜底行"
    l, t, r, b = map(int, rows[0].groups()[1:]); y = (t+b)//2
    for m in re.finditer(r'<node[^>]*checkable="true"[^>]*checked="(\w+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        cl, ct, cr, cb = map(int, m.groups()[1:])
        if abs((ct+cb)//2 - y) < 80:
            return ("checked" if m.group(1) == "true" else "off"), (cl+cr)//2, (ct+cb)//2
    return "off", min(r+220, 1040), y

cfg = json.load(open(ARCHIVE, encoding="utf-8"))
apis = cfg if isinstance(cfg, list) else cfg.get("apis", [])
prof = next(a for a in apis if a.get("id") == API_ID)
KEY = prof.get("key") or prof.get("api_key") or prof.get("apiKey")
assert KEY, "档案库未取到 key"

sh("shell", "am", "force-stop", PKG)
sh("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(4)
c = center(dump(), "设置"); assert c, "未找到设置 tab"; tap(*c)
for _ in range(5):                                # 滚动到视觉区（最多 5 屏）
    x = dump()
    if "视觉 API 地址" in x: break
    sh("shell", "input", "swipe", "540", "1800", "540", "600"); time.sleep(1)
else: sys.exit("未找到视觉配置区")

x = dump()
fill(x, "视觉 API 地址", BASE)
fill(dump(), "视觉 API Key", KEY, secret=True)
fill(dump(), "视觉模型名", MODEL)
state, sx, sy = switch_row(dump())
if state == "off":
    tap(sx, sy); time.sleep(1)                    # ← 开关必须真点：不点则 buildVisionTranscriber()==null，C3a 入队恒 0
x = dump()
after = switch_row(x)
assert after[0] == "checked", f"开关未翻开（state={after[0]}）——立案"
# UI 回显断言：地址/模型名全文比对（明文框 dump 可见）；Key 框不做 UI 断言（Compose dump 对敏感框
# 的 text 时序不可靠），Key 写入正确性由下方 DB 侧强断言承担（密文非明文）
assert BASE in x and MODEL in x, "地址/模型名回显不符——立案"
print("UI 填写完成；开关=checked；Key 已写入（长度=%d，不回显）" % len(KEY))
```

执行后 DB 侧断言（Key 唯一强断言通道，pull 后 PC sqlite3 实查）：

```bash
adb exec-out run-as com.studyfriend.app cat databases/study_friend.db > c1_settings.db
sqlite3 c1_settings.db "SELECT key, length(value), CASE WHEN key='vision_key_enc' THEN substr(value,1,3)||'***' ELSE value END FROM settings WHERE key LIKE 'vision%' AND key != 'visionEnabled';"
# 期望 3 行：vision_base（商汤地址全文）/ vision_key_enc（k1:/s1: 前缀密文、长度>20、非明文 sk- 开头）/ vision_model（非空）
# 任一失败=UI 静默写入失败，脚本重跑一次（幂等清空后重填）；再败立案
```

dump 失效降级链（逐级，不跳级）：① content-desc 兜底匹配（center 已含）→ ② B 报告实测坐标手动模式（1080×2400 前提，key 由脚本经 subprocess `input text` 注入、不经会话）→ ③ 人工在模拟器窗口键入（本机单用户环境）。不采用「key 打印到受控文件」方案——明文落盘与凭据纪律冲突，已废弃。

### C2 fallback 页码+bboxHw 日志（代码改动，预计 1h 含质检）

**OcrTextPostProcessor.kt 改动**（现行 :91-102 `classifyPage` 的 tallCount 循环判定抽为单源函数，行为零变化；lResidue 观察逻辑原样保留）：

```kotlin
// ---- 改动前（现行 :91-102）----
fun classifyPage(lines: List<OcrLine>, log: (String) -> Unit = {}): PageType {
    var tallCount = 0
    var lResidue = 0
    for (l in lines) {
        val w = l.x1 - l.x0
        val h = l.y1 - l.y0
        if (w > MIN_TALL_WIDTH_PT && h / w > TALL_ASPECT) tallCount++
        if (RE_TREE_RESIDUE.matcher(l.text).find()) lResidue++
    }
    if (lResidue > 0) log("treeResidue L-lines=$lResidue（观察特征，不判定）")
    return if (tallCount > 0) PageType.DIAGRAM else PageType.BODY
}

// ---- 改动后：判定表达式单源化（新增函数插在 classifyPage 之前，同 object 内）----
/** 首个竖排框（w>MIN_TALL_WIDTH_PT 且 h/w>TALL_ASPECT，入参 pt 口径 OcrLine）；无则 null。
 *  classifyPage 与 OcrImportRunner 诊断日志共用本函数——判定表达式单源，防漂移。 */
fun findTallBox(lines: List<OcrLine>): OcrLine? =
    lines.firstOrNull { l ->
        val w = l.x1 - l.x0
        val h = l.y1 - l.y0
        w > MIN_TALL_WIDTH_PT && h / w > TALL_ASPECT
    }

/** 诊断量化：首个竖排框 (h/w, w)（pt 口径）；无竖排框返回 null。供 OcrImportRunner 日志。 */
fun tallBoxAspect(lines: List<OcrLine>): Pair<Float, Float>? =
    findTallBox(lines)?.let { (it.y1 - it.y0) to (it.x1 - it.x0) }

fun classifyPage(lines: List<OcrLine>, log: (String) -> Unit = {}): PageType {
    var lResidue = 0
    for (l in lines) {
        if (RE_TREE_RESIDUE.matcher(l.text).find()) lResidue++
    }
    if (lResidue > 0) log("treeResidue L-lines=$lResidue（观察特征，不判定）")
    return if (findTallBox(lines) != null) PageType.DIAGRAM else PageType.BODY
}
```

**OcrImportRunner.kt Pass2 done 日志追加**（现行 :192-198，DIAGRAM 条数按实际动态输出，不预设 22）：

```kotlin
// ---- 改动前（现行 :192-198）----
android.util.Log.w(
    "OcrImport",
    "Pass2 done: pages=$total fallbacks=${fallbacks.size} " +
        "byReason=${fallbacks.groupingBy { it.reason }.eachCount()} " +
        "fragmentLinesMerged=$fragmentLinesMerged " +
        "pass2Ms=${System.currentTimeMillis() - pass2Start}",
)

// ---- 改动后：追加 fallbackPages 明细段 + DIAGRAM 页 tallBox 量化行 ----
android.util.Log.w(
    "OcrImport",
    "Pass2 done: pages=$total fallbacks=${fallbacks.size} " +
        "byReason=${fallbacks.groupingBy { it.reason }.eachCount()} " +
        "fallbackPages=${fallbacks.joinToString(",") { it.pageNo.toString() }} " +
        "fragmentLinesMerged=$fragmentLinesMerged " +
        "pass2Ms=${System.currentTimeMillis() - pass2Start}",
)
fallbacks.filter { it.reason == "DIAGRAM" }.forEach { f ->
    val box = OcrTextPostProcessor.tallBoxAspect(pagesOcrLines[f.pageNo - 1])
    if (box != null) android.util.Log.w(
        "OcrImport",
        "tallBox page=${f.pageNo} hw=${"%.2f".format(box.first)} w=${"%.1f".format(box.second)}pt",
    )
}
```

**单测 3 条**（OcrTextPostProcessorTest 追加；OcrLine 构造器 `OcrLine(text, x0, y0, x1, y1, confidence)` 六字段全必填，OcrEngine.kt:16，入参 pt 口径）：

```kotlin
@Test fun `findTallBox - tall box hits`() {
    val lines = listOf(OcrLine("竖排标题", 0f, 0f, 30f, 120f, 0.9f)) // w=30pt h=120pt
    assertEquals(lines[0], OcrTextPostProcessor.findTallBox(lines))
}
@Test fun `findTallBox - wide box misses`() {
    val lines = listOf(OcrLine("正文行", 0f, 0f, 300f, 30f, 0.9f)) // h/w=0.1
    assertNull(OcrTextPostProcessor.findTallBox(lines))
}
@Test fun `findTallBox - boundary at threshold misses`() {
    // w=12.3f 恰等、h/w=3.0f 恰等均不触发（严格大于语义锁定）
    val lines = listOf(OcrLine("边界框", 0f, 0f, 12.3f, 36.9f, 0.9f))
    assertNull(OcrTextPostProcessor.findTallBox(lines))
}
```

**回归与质检**：`gradlew :app:testDebugUnitTest` 全量（基线 673+新增 3，期望 676 全绿；实际数以 XML 汇总为准，见 F11 口径）；assembleDebug 通过。质检：C2 完成后写质检包落盘 `.e2e/p6c/quality_packet_c2.md`（内容含改动 diff、测试证据、判据对照——结构同 Phase 1 质检包-A），送审命令（轮次从 1 起步，不过线改完质检包后轮次 +1 重送）：

```bash
python .e2e/p6c/score_review.py .e2e/p6c/quality_packet_c2.md 质检包 1 20261004174350-39d5
# exit 0=过线（≥90）；exit 1=按 reviews/ 下第 N 轮意见修改后轮次+1 重送，循环至过线
```

### C3a 仿真书快验（预计 30min，先小后大防 95min 白跑）

```bash
# 1. 推书（产物 F8 已存在）
adb push .e2e/sample_book_scanned.pdf /sdcard/Download/sample_book_scanned.pdf
# 2. 防睡眠（宿主侧，B 报告 §5 同款）
powercfg /change standby-timeout-ac 0
# 3. App 冷启动→书架 FAB→SAF，之后 UI 自动化交给 saf_import.py（下附全文）：
python .e2e/p6c/saf_import.py pick sample_book_scanned
# 4. OCR 轮询（60s 间隔、上限 30min）：
adb logcat -c && adb logcat > c3a_logcat.txt &   # 后台归档
for i in $(seq 1 30); do grep -q "Pass2 done" c3a_logcat.txt && break; sleep 60; done
grep "Pass2 done" c3a_logcat.txt
# 5. 两段式确认（OCR done 后按钮才出现，故 pick/confirm 分离）：
python .e2e/p6c/saf_import.py confirm
# 6. DB 取证（pull 后 PC sqlite3）：
adb exec-out run-as com.studyfriend.app cat databases/study_friend.db > c3a_after.db
sqlite3 c3a_after.db "SELECT id,title,totalChapters FROM books ORDER BY id DESC LIMIT 2;"
# 下述 SQL 的 :bookId 一律以刚查到的新书 id 实值代入（每轮导入 id 递增，不可硬编）
sqlite3 c3a_after.db "SELECT status, count(*) FROM vision_queue WHERE bookId=:bookId GROUP BY status;"
```

`saf_import.py` 全文（C3a/C3b 共用；两段式按钮在 OCR 完成后才出现，故拆 pick/confirm 两个子命令，中间由调用方 logcat 轮询衔接；needle 运行时 dump 匹配，B 报告实测坐标二级降级）：

```python
# -*- coding: utf-8 -*-
"""用法：
  python saf_import.py pick <文件名关键字>   # SAF 文件列表点选目标（调用方先冷启动 App 到书架、点 FAB 打开 SAF）
  python saf_import.py confirm               # 等 OCR 完成后调用：识别结果页「下一步：确认目录」→章节确认页「完成导入」
"""
import re, subprocess, sys, time

ADB = "adb"

def sh(*args, timeout=40):
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout).stdout

def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml"); time.sleep(1)
    return sh("shell", "cat", "/sdcard/ui.xml")

def center(xml, needle):
    for m in re.finditer(r'<node[^>]*(?:text|content-desc)="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if needle in m.group(1):
            l, t, r, b = map(int, m.groups()[1:]); return (l+r)//2, (t+b)//2
    return None

def tap(x, y): sh("shell", "input", "tap", str(x), str(y)); time.sleep(1.5)

def wait_and_tap(needles, fallback=None, tries=15, gap=5):
    """轮询 dump 直到任一 needle 出现并点击；半程后 dump 仍不命中则改试 fallback 坐标。"""
    half = tries // 2
    for i in range(tries):
        x = dump()
        for n in needles:
            c = center(x, n)
            if c: tap(*c); return True
        if fallback and i >= half: tap(*fallback)
        time.sleep(gap)
    return False

mode = sys.argv[1] if len(sys.argv) > 1 else sys.exit("用法: pick <kw> | confirm")
if mode == "pick":
    kw = sys.argv[2] if len(sys.argv) > 2 else sys.exit("缺文件名关键字")
    assert wait_and_tap([kw], fallback=(784, 1125), tries=15, gap=4), f"SAF 未找到 {kw}"
elif mode == "confirm":
    assert wait_and_tap(["下一步", "确认目录"], fallback=(540, 1148), tries=15, gap=6), "段1确认按钮未现（OCR 未完成？先跑 logcat 轮询）"
    assert wait_and_tap(["完成导入"], fallback=(540, 2170), tries=15, gap=4), "段2完成按钮未现"
else:
    sys.exit(f"未知模式: {mode}")
print(f"{mode} 完成")
```

断言链（J3）：
- 入队数=`Pass2 done` fallbacks 数（两值相等；数据源双通道：日志+DB）；
- 视觉消化（推断时长 F10≈6min，判据=终态非时长）：徽标 done/total 递增至 N/N，或 `SELECT count(*) FROM vision_queue WHERE bookId=<new> AND status='DONE'` =入队数、FAILED=0；
- 缓存落盘：`run-as ls files/../cache`（vision 缓存目录）文件数=入队数；
- 重建：全部终态后 chapters/paragraphs 计数变化（重建前后各查一次对比；视觉转写内容与 OCR 文字层不同即证明重建发生）。

任一断言败 → 立案修复重跑本步，**不进 C3b**。

### C3b shpc 461 页重导（预计 ~95min OCR + F10≈13min 视觉，后台）

1. **确认闸门（破坏性防御）**：导入前后各 pull 一次 DB（命令同 C3a-6，存 before/after 两个 db 文件）。导入前打印全表 `sqlite3 c3b_before.db "SELECT id,title,totalChapters FROM books;"`——旧 shpc（预期 id=5，7 章 2913 段）**保留不删不覆盖**，新书另立条目；导入完成后断言旧书数据未动：

```sql
-- 旧 bookId 关联段落计数：after 查询结果必须等于 before 同查询结果
SELECT count(*) FROM paragraphs
WHERE chapterId IN (SELECT id FROM chapters WHERE bookId = :oldShpcId);  -- :oldShpcId=导入前查到的旧条目 id（预期 5）
```
2. **内存曲线**（OCR 全程 30s 间隔，后台；PID 落盘防孤儿循环）：

```bash
echo "ts,phase,pss_kb" > .e2e/p6c/meminfo_timeline.csv
( while true; do
    pss=$(adb shell dumpsys meminfo com.studyfriend.app 2>/dev/null | grep "TOTAL PSS" | head -1 | grep -o '[0-9]*')
    echo "$(date +%s),run,${pss:-0}" >> .e2e/p6c/meminfo_timeline.csv; sleep 30
  done ) & MEM_PID=$!
echo "$MEM_PID" > .e2e/p6c/meminfo_pid.txt
# OCR 完成后停表（kill 失败走 pgrep 兜底）：
kill "$(cat .e2e/p6c/meminfo_pid.txt)" 2>/dev/null || pgrep -f meminfo_timeline | xargs -r kill
echo "$(date +%s),stop,0" >> .e2e/p6c/meminfo_timeline.csv
```

3. 重导 shpc（`python .e2e/p6c/saf_import.py pick shpc` → logcat 轮询同 C3a-4 → `python .e2e/p6c/saf_import.py confirm`）→ Pass2 done 日志断言：`fallbackPages` 数字个数=byReason 合计=47；DIAGRAM tallBox 行数=实际 DIAGRAM 页数（动态；预期 22，不一致走 J4 立案）——**闭环 F7 缺口**。
4. 入队/消化/重建断言同 C3a-6（bookId=新书 id）。
5. 管线确定性旁证（J4）：重导 fallbacks=47 {LOW_CONF=25, DIAGRAM=22} 与 B 报告 §1 表逐位一致；不一致先查输入（同 APK sha256 `5ae0b7cc…` 同书）再立案。

### C4 对账与 A/B（预计 30min）

1. 22 页 DIAGRAM 页码+tallBox 数据回填 B 报告缺口（B 报告追加「小计划 C 补齐」小节，不改已裁定正文）。
2. 视觉质量抽验——**诚实口径裁剪**：vision_queue 无 result 列、paragraphs 无页码列（Entities.kt 实查），视觉文本与 OCR 文字层本就不同文，跨源相似度 ratio 无判据意义。故程序侧只做**字符量级统计**（观察项，不设通过线），质量判定以目视 3 页为主（J6）。脚本 `.e2e/p6c/diff_vision_ocr.py` 全文：

```python
# -*- coding: utf-8 -*-
"""C4-2：视觉转写字符量级统计。数据源=视觉缓存文件（run-as pull 落盘）+ vision_queue.originChars。
输出 .e2e/p6c/vision_ocr_diff.json：[{pageNo, vision_chars, ocr_origin_chars, chars_ratio}]。
chars_ratio 仅量级观察（不设通过线）；质量判定以 J6 目视 3 页为准。
用法：python diff_vision_ocr.py <缓存目录> <db文件> <bookId>
（缓存文件名以执行时 `run-as ls cache/` 实际命名为准，下例假设 p{pageNo}.txt，不符时改 FILEPAT）"""
import json, sqlite3, sys, pathlib

cache_dir, db_path, book_id = sys.argv[1], sys.argv[2], int(sys.argv[3])
FILEPAT = "p{pageNo}.txt"
rows = sqlite3.connect(db_path).execute(
    "SELECT pageNo, originChars FROM vision_queue WHERE bookId=? ORDER BY pageNo", (book_id,)).fetchall()
out = []
for pageNo, ocrChars in rows:
    f = pathlib.Path(cache_dir) / FILEPAT.format(pageNo=pageNo)
    vt = f.read_text(encoding="utf-8") if f.exists() else ""
    out.append({"pageNo": pageNo, "vision_chars": len(vt), "ocr_origin_chars": ocrChars,
                "chars_ratio": round(len(vt) / ocrChars, 3) if ocrChars else None})
json.dump(out, open(r".e2e/p6c/vision_ocr_diff.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"{len(out)} 页统计完成")
# 期望示例：[{"pageNo": 41, "vision_chars": 1230, "ocr_origin_chars": 1180, "chars_ratio": 1.042}, ...]
# 异常信号：vision_chars=0（缓存缺失/转写空）→ 该页立案
```

目视 3 页固定清单：p041（已知乱码页）/任一 DIAGRAM 页/任一 LOW_CONF 增量页；判定线=3 页中 ≥2 页「通读无乱码块、段落连贯」（目视结论写进报告，标 MEASURED-目视）。
3. A/B：新旧两本 shpc 的 chapters/paragraphs 计数+抽样 2 段文本对比表（视觉增强版 vs 本地 OCR 版），MEASURED/UNMEASURED 分标。

### C5 收尾 checklist

- [ ] M2 总览更新：P6c 遗留条 ③④ 闭环改写、决策 36 视觉链路实测数字补注（若 C2 合入，关键文件清单 OcrTextPostProcessor 行同步 findTallBox）；
- [ ] C2 代码若变更 → 全量测试回归证据（XML 汇总数字）附质检包；
- [ ] 三查：`python C:/Agent/AImanager/tools/check_session_close.py C:/AIWorkSpace/Studying-with-friend` 退出码 0（commit 前基线跑一次记录红项构成，commit 后复跑全绿，两次证据随报告落档）；
- [ ] commit（模板 `feat(ocr): P6c 小计划 C——视觉兜底商汤配置+fallback 页码日志+47 页增强取证`；.e2e/ 零入库）；
- [ ] push 前人工过 diff（隐私扫描不认识自选密码类内容；本次重点核 key 零出现）；
- [ ] `python C:/Agent/AImanager/tools/git_push_github.py C:/AIWorkSpace/Studying-with-friend`。

## 3. 判据（全部落到可运行命令）

| # | 判据 | 通过线 | 验证命令 | 不过处置 |
| --- | --- | --- | --- | --- |
| J1 | C2 代码质检 | ≥90（10 维评分，score_review.py） | `python .e2e/p6c/score_review.py .e2e/p6c/quality_packet_c2.md 质检包 1 20261004174350-39d5`（轮次 1 起步，不过线改后 +1 重送） | 按意见修改循环 |
| J2 | 测试回归 | 全量 0 failures（期望 676=673+3，实际以 XML 汇总为准） | `gradlew :app:testDebugUnitTest` + XML 汇总命令（质检包 V2 行同款） | 立案 |
| J3 | C3a 链路 | 入队数=fallbacks；DONE=入队数 且 FAILED=0；重建后段落文本变化 | `sqlite3 c3a_after.db "SELECT status,count(*) FROM vision_queue WHERE bookId=:bookId GROUP BY status;"`（:bookId=新书 id 实值代入） | 立案修复重跑，不进 C3b |
| J4 | 管线确定性 | fallbacks=47 {LOW_CONF=25,DIAGRAM=22} 与 B 报告逐位一致 | `grep "Pass2 done" c3b_logcat.txt` | 立案归因（同 APK 同书两轮差异才升级） |
| J5 | 视觉消化 | `SELECT count(*) ... status='DONE'`=47 且 FAILED=0（重试上限 VisionWorker.kt:101 MAX_PAGE_ATTEMPTS=3 内） | 同 J3 SQL（bookId=新书） | FAILED 清单立案 |
| J6 | A/B 与抽验 | 3 页目视 ≥2 页「通读无乱码块」（目视记录落报告）；vision_ocr_diff.json 落档且 vision_chars=0 页数为 0（有则立案） | `.e2e/p6c/diff_vision_ocr.py`（C4-2） | 记档不阻断（质量观察项；chars_ratio 不设线，理由见 C4-2） |

## 4. 风险与预案

| 风险 | 预案 |
| --- | --- |
| adb input text 泄 key | key 仅在 python 进程内从档案库读取；脚本/命令行/会话记录零明文；模拟器进程列表（ps）1s 窗口理论可见——单机测试环境接受，风险表记档 |
| 商汤限流/单页超时 | VisionWorker 逐页串行（F9）；180s 超时+重试 1 次（VisionTranscriber.kt:90/46）；F2 实测 16.4s 余量 10 倍 |
| 95min 重导中模拟器死亡 | powercfg 防睡眠先行（C3b-2）；死亡走 B 报告 §5.2 恢复链后补跑；meminfo CSV 断点续写 |
| 重建覆盖阅读成果 | 旧 shpc 保留（确认闸门 C3b-1）；仿真书/新书均为测试数据无 AI 消费记录，重建即测试目标 |
| OCR 结果与 B 报告不一致 | J4 立案：先核对 APK sha256 与输入文件，再查环境；两轮差异才升级 |
| C2 改动触碰判定行为 | findTallBox 单源抽取不改表达式；全量回归+边界单测锁定；质检 J1 独立把关 |
| uiautomator dump 失效（Compose 节点无 text） | content-desc 兜底匹配；再失效降级 B 报告实测坐标（1080×2400 前提）；仍不行手动 UI 填写一次（key 走粘贴板方案，零会话明文） |

## 5. 成本申报

- **LLM 调用**：计划案评分（本计划案已 2 轮，预估再 1-2 轮）+C2 质检预估 2 轮（Phase 1 历史：计划案 3 轮过线、质检包 1 轮过线——按偏保守取值），合计 ≈3-5 次调用×30K tokens/轮（Phase 1 单轮 30K tokens/67.8s，MEASURED 基线外推）≈90-150K tokens；**循环超 5 轮未过线即上报老板，不无限重试**；
- **商汤 vision 实调**：C3a 22 页+C3b 47 页≈69 次转写（测试 key，老板授权）；token 换算【推断】：每页输入=1 张 140dpi 图+~200 token 提示词，输出 ~1-2K token/页，总计 ≈7-14 万输出 token 量级（商汤侧计费口径未实测，标注推断）；
- **模拟器机时**：C3a ≈30min+C3b ≈110min ≈2.5h（emulator-5554）；**PC 常驻机时**：meminfo 轮询+logcat 归档 ≈2.5h（后台，无人值守）；
- **用户等待**：C1-C5 合计约 3.5h（其中 95min 后台跑，期间主动汇报中间进展）；
- **代码面**：改动 2 个生产文件（OcrTextPostProcessor.kt / OcrImportRunner.kt）+1 个测试文件；
- **不做**：hybrid 提速（未立项）、删旧 shpc（保留 A/B）、多分辨率/真机、DPI 200 实验、竖排假阳性专项、FAILED 页手动重试入口。

## 6. 资产复用

评分工具 `.e2e/p6c/score_review.py`（JSONL usage 留痕）；仿真书 `.e2e/sample_book_scanned.pdf`（F8）；恢复链/轮询/两段式导入（B 报告 §5/§6）；对账脚本 `reconcile_b.py`/`analyze_line.py`（C4 复用）；PC 连通测试 `.e2e/p6c/test_vision_api.py`（已建）。

## 7. out-of-scope

真机联测、多书批量、DPI 200 实验、竖排假阳性专项、vision_queue FAILED 手动重试入口（既有遗留记档）、P6c Phase 2/3（总体计划另议）。

## 8. 执行期偏差与事故登记（C3b 执行期间实录，随执行滚动追加）

### 8.1 R2 模拟器快照回滚事故（10-06，已恢复）

- **经过**：A2 收口后用 TaskStop 停后台 logcat 任务，进程树连带杀死 qemu；重启 JingBianAVD 后设备数据回滚至 10-05 15:09 Quickboot 保存点。
- **损失**：books 5-8（旧 shpc id=5 7 章 2913 段、sample 三代 6/7/8）全丢；全部 vision 视觉缓存文件丢；A2 设备态、C1 视觉配置、主 OCR 配置全丢。宿主侧 `.e2e/p6c/` 证据文件零损失（a2.db 快照、c3b_before.db 回滚前库、日志全在）。
- **恢复**：重装 APK → adb root + 修时钟 → C1 全量重配（见 8.2）。mfzz 视觉队列 146 页回到 PENDING，将重新消耗商汤额度（约 40min 机时，成本申报见 §5 追加）。
- **教训**：后台任务一律用任务系统的显式后台（run_in_background），禁用 `(cmd &)` 子壳——TaskStop 会杀整棵进程树。

### 8.2 C1 重配置两处缺陷与修复（10-06，已闭环）

- **缺陷一（旧脚本假证据）**：`configure_app_vision.py` 保存后以「回显比对 PASS」作证据——实测证明输入框值留存只能证明填写、不能证明保存落库；且该脚本声称「权威断言在 DB 侧」但从未实现 DB 断言。
- **缺陷二（根因）**：保存按钮位于 LazyColumn 页底，填写/开关/提示信息等状态变化会把按钮挤出视口或移位，脚本用旧 dump 坐标 tap 落空（对照实验：同页「检查更新」按钮 tap 有响应、按钮 enabled=true、焦点正常——排除机制性问题，实锤坐标失效）。
- **修复**：`configure_app_full2.py` 三处闭环——①主+视觉八项全量重配（回滚后主配置同丢，旧脚本只填视觉四项）；②tap 前验证按钮完整落在 ScrollView 视口内（y+60<2060）；③tap 后以 message「已保存」出现为唯一点击生效证据，未出现则滚动重找重试。
- **DB 断言（8 项全 PASS）**：vision_enabled=true、vision_base/vision_model=商汤识图档案值、vision_key_enc 密文落库（k1: 前缀，长度 87）、api_base/api_model=main 档案值、api_key_enc 密文落库、api_temp=0.3。键名实录：settings 表主配置键为 `api_base`/`api_model`/`api_key_enc`/`api_temp`（非 base_url/model）。
- **实测坑（新增）**：Compose LazyColumn 语义树只含视口内组合项，「保存」按钮滚出视口即从 uiautomator dump 消失；页面底部 message 文本出现会改变页底节点布局——凡 tap 页底按钮必须 tap 前即时 dump 即时验证。

### 8.3 C3b 闸门对象偏差（10-06，登记不改判据）

- 原 C3b-1 保全闸门对象含旧 shpc id=5（7 章 2913 段）——已随 R2 回滚消失；回滚前状态有 `c3b_before.db` 留档为证。
- 现存保全对象改为：mfzz id=1（6599 段 12 章）与 shpc-ocr id=4（1293 段 4 章）；after 闸门仍断言此二书段落文本零变化（判据 SQL 不变，仅 bookId 代入值改）。
- J4/J5 判据数值（fallbacks=47 {LOW_CONF=25,DIAGRAM=22}）不变——同 APK 同书重导，确定性预期不受回滚影响。
