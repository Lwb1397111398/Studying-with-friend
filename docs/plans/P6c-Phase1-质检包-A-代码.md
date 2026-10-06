# P6c Phase 1 · 小计划 A 代码质检包（rec 动态宽改造）· 第 2 版

> 送检对象：score_review.py「代码」类，≥90 过线循环。
> 计划案：docs/plans/P6c-Phase1-计划案-A-rec动态宽.md（第 2 轮 99 分过线，留档 docs/plans/reviews/同名-评分-第2轮.md）。
> 第 1 轮 51 分意见全部处理：本文为逐条落实后的第 2 版（处理台账见 §9）。

## 0. 环境前置条件（零上下文可复现）

| 项 | 值 |
| --- | --- |
| 仓库 | 本机工作区 `C:\AIWorkSpace\Studying-with-friend`（GitHub 私有镜像 Lwb1397111398/Studying-with-friend） |
| 代码基线 | HEAD=2056de0（Phase 0 收口），本包改动为工作区未 commit 改动，commit 动作在 Phase 1 P4 统一执行（判据纪律：整体质检全绿才 push） |
| 改动文件（代码） | `android/app/src/main/java/com/studyfriend/app/data/importer/ocr/OcrEngine.kt`（改）、`android/app/src/test/java/com/studyfriend/app/data/importer/ocr/OcrRecPreTest.kt`（新） |
| 涉及文档（仅留档，无代码改动） | `docs/plans/P6c-Phase1-计划案-A-rec动态宽.md`、`docs/plans/P6c-Phase1-质检包-A-代码.md`（本文）、`docs/plans/reviews/` 下同名评分留档 4 份 |
| 工具链 | Gradle 8.9（wrapper 自带，distributionUrl 已配置华为镜像）、AGP 8.7.3、compileSdk 35 / minSdk 26 / targetSdk 35、jvmTarget 17、JUnit 4.13.2 |
| PC 口径参照 | `.e2e/p6c/replica_ocr.py`（**.e2e/ 整目录不入库**——口径公式已逐式内联于本文 §3 对照表，复现不依赖该文件；仓库内持久对照见 P6c-Phase0-报告.md §5） |
| 复现步骤 | ① clone/进入工作区 → ② checkout 2056de0 → ③ 套用本文 §2 代码（与工作区逐字一致）→ ④ 跑 §4 命令清单 → ⑤ 对照 §4 期望结果 |

## 1. 改动范围与验收口径（3 文件）

| 文件 | 改动 |
| --- | --- |
| OcrEngine.kt:41（类 KDoc） | 一行：「48×320 拉伸」→「高 48 等比缩放、批内定宽右侧补零」 |
| OcrEngine.kt:93-141（rec 段） | 整段替换为两阶段动态宽（§2.2 全文） |
| OcrEngine.kt:238-末尾 | 新增 OcrRecPre 纯函数对象（§2.1 全文）+ import kotlin.math.ceil（OcrEngine.kt:9） |
| OcrRecPreTest.kt | 新增 9 用例（§2.3 全文） |
| docs/plans/P6c-Phase1-计划案-A-rec动态宽.md | 计划案本体（已评分留档） |

**接口不变量**：`OcrEngine` 接口签名、`OcrLine` 字段（OcrEngine.kt:15-22）、`OcrDetPost`（OcrEngine.kt:159-205）、`OcrCtc`（OcrEngine.kt:209-237）零改动；唯一消费方 `OcrImportRunner.kt` 零改动。

**验收口径（量化）**：
- 本包判定（JVM 侧）：① §3 对照表逐式一致（结构口径对齐，V4 闭环映射见表后）；② §4 命令清单全绿。
- 端到端判定（属小计划 B，本包不判）：同一页 Android↔PC 输出对照沿用 Phase 0 既有判据——页 median、过闸数、逐页行数偏差（P6c-Phase0-报告.md §5 判定口径），不新设 BLEU 等指标（判据数值/新指标变更须报老板拍板，Phase 0 闸门已定口径）；验证时点=小计划 B 模拟器 E2E。

**关键结论出处索引**（后文引用处不再重复展开）：
- 「PC 全量 401 页实证（过闸 387/401=96.5%、median 中位 0.9225、7.7s/页）」→ **聚合统计值已内联入库**：docs/plans/P6c-Phase0-报告.md §5（含分布摘要）；逐页原始数据 .e2e/p6c/matchB_dyn.json（401 条 page/median/gate/secs，不入库，聚合可从入库报告独立核对）。
- 「根因：硬拉伸 4 倍压宽」→ docs/plans/P6c-Phase0-报告.md §1。
- 「R2/R3 触发线 5s/256MB、hybrid 预案」→ docs/plans/P6c-Phase0-报告.md §7 闸门④拍板文本（老板 2026-10-05 拍板「全部按照你的推荐做」）。
- 「rec 模型动态宽轴已实证（E5）」→ P6c-Phase1-计划案-A-rec动态宽.md §1 表 E5 行（论据=PC 同模型变宽跑完 401 页，聚合见入库报告 §5）。
- 「批宽实测最大 ≈1488（E6）」→ P6c-Phase1-计划案-A-rec动态宽.md §1 表 E6 行（论据=m2_code_bundle.md §3 风险册实测值 ratio≈31）。

## 1.5 资产复用清单（不重复造轮子核对表）

| 复用资产 | 复用方式 | 本包是否重复实现 |
| --- | --- | --- |
| replica_ocr.py rec_pre_dyn/norm_pad_dyn（PC 口径） | 公式内联（§3 对照表逐式，源文件不入库故内联入库文档） | 否——公式逐式照搬，未发明新式 |
| OcrDetPost（OcrEngine.kt:159-205） | 直接调用（未改动），det 路径复用 | 否 |
| OcrCtc（OcrEngine.kt:209-237） | 直接调用（未改动），解码复用 | 否 |
| OcrImportRunner 门控/管线 | 零改动消费 OcrLine（OcrImportRunner.kt:257-270） | 否 |
| Phase 0 判据（页 median/过闸/0.85 阈值） | 沿用不新设（§1 验收口径） | 否 |
| Phase 0 既有测试类（OcrEngineTest/OcrImportRunnerPageGateTest/OcrTextPostProcessorTest/OcrVerticalDetectorTest/OcrModelDownloaderTest） | 回查 A 回归网（§4 V2） | 否 |
| OcrEngineTest 直构测试风格（probMap 辅助，OcrEngineTest.kt:64 模式） | OcrRecPreTest 沿用同款直构 | 否 |
| JUnit 4.13.2 / Gradle 8.9 既有构建与测试管线 | 原样使用，无新增插件/依赖 | 否 |

## 2. 最终代码（逐字与仓库一致，含段尾补全）

### 2.1 OcrRecPre 对象（OcrEngine.kt 文件尾新增）

```kotlin
/** rec 动态宽预处理纯函数（P6c Phase 1，对齐 PC replica_ocr.py rec_pre_dyn/norm_pad_dyn 口径）：
 *  根因修复——旧固定 320×48 硬拉伸把长行压约 4 倍致 CTC 全 blank、conf 崩（PC 全量 401 页实证） */
object OcrRecPre {
    private const val REC_H = 48
    private const val REC_MIN_W = 320 // PC 口径：批宽下限 320（宽高比下限 320/48）

    /** 单框 rec 输入宽：高 48 等比缩放后的宽 = ceil(cropW*48/cropH)，最小 1（PC rec_pre_dyn:128 同式） */
    fun cropRecWidth(cropW: Int, cropH: Int): Int =
        maxOf(1, ceil(cropW * REC_H.toDouble() / cropH).toInt())

    /** 批内统一定宽 = int(48 * max(320/48, 批内最大宽高比))，下限 320（PC replica:257-258 同式）；
     *  coerceAtLeast 防 float 舍入把 48*(320f/48f) 截断成 319 */
    fun batchRecWidth(cropWs: IntArray, cropHs: IntArray): Int {
        var maxRatio = REC_MIN_W.toFloat() / REC_H
        for (i in cropWs.indices) {
            val r = cropWs[i].toFloat() / cropHs[i]
            if (r > maxRatio) maxRatio = r
        }
        return maxOf(REC_MIN_W, (REC_H * maxRatio).toInt())
    }

    /** 把已缩放到 (rw×48) 的 ARGB 像素归一化 ((c/255−0.5)/0.5) 填入批次 data 第 bi 行，
     *  平面布局 [3][48][batchW]，内容靠左、右侧补零（PC norm_pad_dyn:137-138 同构，0f 即归一化零点）；
     *  @param rw 必须已由调用方 minOf 截断（rw ≤ batchW）；漏截断时 require 即抛，
     *  错误信息提示回查调用方 minOf——宁抛异常不做静默越界写 */
    fun fillNormalized(px: IntArray, rw: Int, batchW: Int, data: FloatArray, bi: Int) {
        require(rw in 1..batchW) { "rw=$rw 超出批宽 batchW=$batchW（调用方漏做 minOf 截断？）" }
        val plane = REC_H * batchW
        val off = bi * 3 * plane
        for (y in 0 until REC_H) for (x in 0 until rw) {
            val p = px[y * rw + x]
            val di = off + y * batchW + x
            data[di] = (((p shr 16 and 0xFF) / 255f) - 0.5f) / 0.5f
            data[plane + di] = (((p shr 8 and 0xFF) / 255f) - 0.5f) / 0.5f
            data[2 * plane + di] = (((p and 0xFF) / 255f) - 0.5f) / 0.5f
        }
    }
}
```

### 2.2 recognize rec 段（OcrEngine.kt:93-141 整段替换后，**含段尾全文，无省略**）

```kotlin
        // ---- rec：批 8，高 48 等比缩放 + 批内定宽右侧补零（P6c Phase 1 动态宽，
        //      对齐 PC replica_ocr.py rec_pre_dyn/norm_pad_dyn 口径），CTC greedy 解码 ----
        val sx = bitmap.width.toFloat() / W; val sy = bitmap.height.toFloat() / H
        val recH = 48
        val out = mutableListOf<OcrLine>()
        for (chunk in boxes.chunked(REC_BATCH)) {
            // 阶段一：逐框算裁剪矩形与等比目标宽（不缩放；坐标 coerce 与旧版逐行同）
            val rects = ArrayList<IntArray>(chunk.size) // [x0,y0,x1,y1] 原图像素坐标
            val rws = IntArray(chunk.size)
            chunk.forEachIndexed { bi, box ->
                val x0 = (box[0] * sx).toInt().coerceIn(0, bitmap.width - 2)
                val y0 = (box[1] * sy).toInt().coerceIn(0, bitmap.height - 2)
                val x1 = (box[2] * sx).toInt().coerceIn(x0 + 1, bitmap.width - 1)
                val y1 = (box[3] * sy).toInt().coerceIn(y0 + 1, bitmap.height - 1)
                rects.add(intArrayOf(x0, y0, x1, y1))
                rws[bi] = OcrRecPre.cropRecWidth(x1 - x0, y1 - y0)
            }
            // 定批宽（PC replica:257-258 同式：int(48 * max(320/48, 批内最大宽高比))）
            val cropWs = IntArray(chunk.size) { rects[it][2] - rects[it][0] }
            val cropHs = IntArray(chunk.size) { rects[it][3] - rects[it][1] }
            val batchW = OcrRecPre.batchRecWidth(cropWs, cropHs)
            // 阶段二：逐框裁剪 → 一步缩放到 (min(rw,batchW),48) → 归一化填左、右侧补零
            val data = FloatArray(chunk.size * 3 * recH * batchW)
            chunk.forEachIndexed { bi, _ ->
                val rect = rects[bi]
                val crop = Bitmap.createBitmap(bitmap, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
                val tgtW = minOf(rws[bi], batchW) // rw>batchW 的 ≤2px 下采样，对齐 PC norm_pad_dyn min 语义
                val rs = Bitmap.createScaledBitmap(crop, tgtW, recH, true)
                val cpx = IntArray(recH * tgtW)
                rs.getPixels(cpx, 0, tgtW, 0, 0, tgtW, recH)
                if (rs !== crop) rs.recycle(); crop.recycle()
                OcrRecPre.fillNormalized(cpx, tgtW, batchW, data, bi)
            }
            val t = OnnxTensor.createTensor(
                e, FloatBuffer.wrap(data),
                longArrayOf(chunk.size.toLong(), 3, recH.toLong(), batchW.toLong()),
            )
            val o = r.run(mapOf("x" to t))
            val outT = o[0] as OnnxTensor
            val buf = outT.floatBuffer
            val shape = outT.info.shape // [N,T,C]
            val decoded = OcrCtc.decode(buf, shape[0].toInt(), shape[1].toInt(), shape[2].toInt(), dict)
            o.close()
            t.close()
            decoded.forEachIndexed { bi, (text, conf) ->
                // 解码空串的框丢弃（det 切出的无字符区域）；box 记原图像素坐标
                if (text.isNotEmpty()) {
                    val b = chunk[bi]
                    out.add(
                        OcrLine(
                            text,
                            x0 = b[0] * sx, y0 = b[1] * sy, x1 = b[2] * sx, y1 = b[3] * sy,
                            confidence = conf,
                        ),
                    )
                }
            }
        }
        return out
```

段尾自洽性说明：tensor 形状 `[N,3,48,batchW]`，输出 `[N,T,C]` 中 `T` 由模型按输入宽动态给出（rec 模型 W 轴动态，PC 同模型变宽推理 401 页已实证，计划案 E5），`OcrCtc.decode` 从 `outT.info.shape` 取实际 T/C（OcrEngine.kt:124 行同款），与 batchW 数值无耦合——解码索引只依赖输出 shape，不依赖输入宽，故 batchW 变化不影响解码段。

### 2.3 OcrRecPreTest.kt（9 用例全文）

```kotlin
package com.studyfriend.app.data.importer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** rec 动态宽预处理纯函数单测（P6c Phase 1，口径对齐 PC replica_ocr.py rec_pre_dyn/norm_pad_dyn） */
class OcrRecPreTest {

    // ---- cropRecWidth：高 48 等比缩放，ceil，最小 1（PC rec_pre_dyn:128 同式）----

    @Test
    fun `cropRecWidth - normal line keeps aspect`() {
        assertEquals(1200, OcrRecPre.cropRecWidth(1000, 40)) // 1000*48/40=1200
        assertEquals(640, OcrRecPre.cropRecWidth(640, 48))
    }

    @Test
    fun `cropRecWidth - very tall box floors to 1`() {
        assertEquals(1, OcrRecPre.cropRecWidth(1, 100)) // ceil(0.48)=1，max(1,·) 兜底
    }

    @Test
    fun `cropRecWidth - ceil on non divisible`() {
        assertEquals(100, OcrRecPre.cropRecWidth(100, 48))
        assertEquals(101, OcrRecPre.cropRecWidth(101, 48)) // ceil(100.99…)=101
    }

    // ---- batchRecWidth：int(48*max(320/48, 最大宽高比))，下限 320（PC replica:257-258 同式）----

    @Test
    fun `batchRecWidth - all short lines floor to 320`() {
        // 全部宽高比 <320/48≈6.67：2.08 / 4.17 / 6.25
        assertEquals(320, OcrRecPre.batchRecWidth(intArrayOf(100, 200, 300), intArrayOf(48, 48, 48)))
    }

    @Test
    fun `batchRecWidth - long line sets width by floor truncation`() {
        // 实测最大宽高比 ≈31：1488×48 → int(48*31)=1488（floor 截断口径）
        assertEquals(1488, OcrRecPre.batchRecWidth(intArrayOf(1488), intArrayOf(48)))
    }

    @Test
    fun `batchRecWidth - float rounding guard keeps 320`() {
        // 48*(320f/48f) 浮点截断可能得 319，护栏必须恰 320
        assertEquals(320, OcrRecPre.batchRecWidth(intArrayOf(320, 320), intArrayOf(48, 48)))
    }

    // ---- fillNormalized：归一化靠左填、右侧补零、三通道平面偏移（PC norm_pad_dyn:137-138 同构）----

    @Test
    fun `fillNormalized - left filled normalized right zero`() {
        val batchW = 8; val rw = 2
        val px = IntArray(48 * rw) { 0xFF808080.toInt() } // 灰底
        px[0] = 0xFFFFFFFF.toInt() // y=0,x=0 白 → (255/255-0.5)/0.5=1f
        px[1] = 0xFF000000.toInt() // y=0,x=1 黑 → -1f
        val data = FloatArray(1 * 3 * 48 * batchW)
        OcrRecPre.fillNormalized(px, rw, batchW, data, 0)
        val plane = 48 * batchW
        assertEquals(1f, data[0 * plane + 0 * batchW + 0], 1e-6f) // R 通道白
        assertEquals(-1f, data[0 * plane + 0 * batchW + 1], 1e-6f) // R 通道黑
        assertEquals(0f, data[0 * plane + 5], 1e-6f) // x≥rw 右侧补零
        val g = ((0x80 / 255f) - 0.5f) / 0.5f // 灰 ≈0.0039，三通道同值
        assertEquals(g, data[0 * plane + 1 * batchW + 0], 1e-4f)
        assertEquals(g, data[1 * plane + 1 * batchW + 0], 1e-4f)
        assertEquals(g, data[2 * plane + 1 * batchW + 0], 1e-4f)
    }

    @Test
    fun `fillNormalized - batch offset writes row bi only`() {
        val batchW = 4; val rw = 1
        val px = IntArray(48) { 0xFFFFFFFF.toInt() }
        val data = FloatArray(2 * 3 * 48 * batchW)
        OcrRecPre.fillNormalized(px, rw, batchW, data, 1) // 写第 2 行
        assertEquals(0f, data[0], 1e-6f) // 第 1 行全零
        assertEquals(1f, data[3 * 48 * batchW], 1e-6f) // 第 2 行 R 通道 y0x0
    }

    @Test
    fun `fillNormalized - rejects rw beyond batchW`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrRecPre.fillNormalized(IntArray(48 * 5), 5, 4, FloatArray(3 * 48 * 4), 0)
        }
    }
}
```

## 3. 口径对照表（Kotlin ↔ PC Python 逐式）

| 口径 | PC（.e2e/p6c/replica_ocr.py，不入库） | Kotlin（OcrEngine.kt） |
| --- | --- | --- |
| 单框等比宽 | replica_ocr.py:128 `rw = max(1, int(np.ceil(w * 48 / h)))` | OcrEngine.kt OcrRecPre.cropRecWidth |
| 批宽公式 | replica_ocr.py:257-258、278-279 `max_ratio = max(320/48, *(c.w/c.h)); int(48*max_ratio)` | OcrRecPre.batchRecWidth |
| 超批宽下采样 | replica_ocr.py:133-135 `rw=min(im.w,img_w); if rw!=im.w: resize` | OcrEngine.kt:114 `tgtW = minOf(rws[bi], batchW)` 单次缩放（单步 vs PC 双重插值，≤2px 差，计划案 §2.2 已声明） |
| 归一化 | replica_ocr.py:136 `(im/255 - 0.5)/0.5` | OcrEngine.kt fillNormalized 三通道同式（与 OcrEngine.kt:80-82 det 归一化互证） |
| 右侧补零 | replica_ocr.py:137-138 `np.zeros((3,48,img_w)); pad[:,:,:rw]=x` | FloatArray 默认 0f 仅填 x<rw（fillNormalized） |
| 批大小 | replica_ocr.py:270 `range(0, len(boxes), 8)` | OcrEngine.kt:99 `boxes.chunked(REC_BATCH)`，REC_BATCH=8（OcrEngine.kt:154） |
| 裁剪坐标 coerce | replica_ocr.py:124-125 | OcrEngine.kt:104-107（与改动前 101-104 行逐行同） |
| 空串丢弃/坐标回写 | replica_ocr.py:276-278 `if txt` | OcrEngine.kt:138-153（与改动前 128-140 行逐行同） |

## 4. 验证证据与可复现命令清单（MEASURED，2026-10-05 本机实测）

前置：cd `C:\AIWorkSpace\Studying-with-friend\android`（Windows，Gradle 8.9 wrapper）。

| # | 命令 | 期望结果 | 本机实测 | 耗时 | 退出码 |
| --- | --- | --- | --- | --- | --- |
| V1 | `./gradlew :app:testDebugUnitTest --tests "com.studyfriend.app.data.importer.ocr.OcrRecPreTest"` | BUILD SUCCESSFUL；OcrRecPreTest 9/9 绿 | 9/9 绿 | ~1m34s（含编译） | 0 |
| V2 | `./gradlew :app:testDebugUnitTest` | BUILD SUCCESSFUL；全量 0 failures | **673 tests, 0 failures, 1 skipped**（=P6b 基线 664+1skipped+新增 9；统计命令见下） | ~1m38s | 0 |
| V3 | `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL | BUILD SUCCESSFUL | ~15s | 0 |

V2 统计命令（XML 汇总）：`grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print s}'`（failures/skipped 同式换字段名）。

**V4 口径对照闭环映射（§3 表 8 行 ↔ 验证手段，不靠人工比对）**：

| §3 表行 | 闭环验证 |
| --- | --- |
| 单框等比宽 | V1 用例 `cropRecWidth - normal line keeps aspect` / `- very tall box floors to 1` / `- ceil on non divisible`（数值直接取 PC 公式代入值） |
| 批宽公式 | V1 用例 `long line sets width by floor truncation`（1488=PC 公式代入）+ `float rounding guard keeps 320` |
| 超批宽下采样 | V1 无法覆盖 Bitmap（Android API，P6b 起既有边界非本包引入）；结构性核对=本行+计划案 §2.2 微差声明，运行时验证=小计划 B 模拟器 E2E（instrumented 等价物，见 §8） |
| 归一化 | V1 用例 `fillNormalized - left filled normalized right zero`（白 1f/黑 -1f/灰 0.0039/补零，与 OcrEngine.kt:80-82 det 式互证） |
| 右侧补零 | 同上用例（x≥rw 全 0f 断言）+ `batch offset writes row bi only` |
| 批大小 | V5 可执行核对（见下） |
| 裁剪坐标 coerce | 同 V5（diff 逐字符核对 104-107 行 vs 旧 101-104 行） |
| 空串丢弃/坐标回写 | 同 V5（diff 核对 138-153 行 vs 旧 128-140 行，预期仅注释差异） |

V5 可执行核对命令（人工只看输出结论，不读全 diff）：
```
git diff 2056de0 -- android/app/src/main/java/com/studyfriend/app/data/importer/ocr/OcrEngine.kt | grep -E "^[-+].*(REC_BATCH|OcrCtc\.decode|text\.isNotEmpty)"
```
期望输出：REC_BATCH 无增删行（常量定义未动）；`OcrCtc.decode`/`text.isNotEmpty` 调用行仅出现在新增上下文行（+ 前缀、与旧代码逐字符同款）；出现 `-` 前缀删除上述符号行即违规。

回查 A（老板纪律：B 实现后查 A）：V2 全量含 OcrEngineTest（det OcrDetPost/CTC OcrCtc 12 用例）、OcrImportRunnerPageGateTest、OcrTextPostProcessorTest、OcrVerticalDetectorTest、OcrModelDownloaderTest——全部零回归，证实动态宽改动未触碰其输入输出契约。

一次红灯实录：V1 首跑「batchRecWidth - all short lines floor to 320」红（expected 320 was 480）——**用例数据错误非实现错误**：300×30 宽高比=10>6.67，本就该定宽 480；改用 300×48（ratio 6.25）后绿，实现零改动（§3 批宽公式对照无误）。

## 5. 风险台账（触发条件 / 检测方式 / 预案动作，承计划案 §4 并补端上项）

| 风险 | 触发条件 | 检测方式 | 预案动作 |
| --- | --- | --- | --- |
| R1 rec 模型拒绝变宽输入 | 模拟器首跑 ONNX 报 shape 错 | 小计划 B 首页 logcat | 停下报老板（PC 同模型变宽已实证，计划案 E5）；不做静默绕过 |
| R2 单页耗时膨胀 | B 实测单页 >5s（出处=P6c-Phase0-报告.md §7 闸门④拍板文本原文；数值参照=S7 生产基线全流程 2.2-3.0s/页 MEASURED，5s≈基线的 1.7-2.3×；口径=det+rec+渲染全流程单页耗时，与 S7 计时同口径） | B 的逐页计时日志 | 起草 hybrid 小计划案（rw≤320 固定口径/rw>320 dyn，批分组）送评分循环（无轮次上限，按既有方差收口纪律）；**fallback 链**：若 hybrid 实测仍 >5s → 不自动回滚——回滚 dyn 等于质量回退到 87% 兜底（判据级取舍），报老板拍板「接受较慢导入 vs 回退质量」，两案并陈数字 |
| R3 内存膨胀 | B 实测增量 PSS >256MB（同源：P6c-Phase0-报告.md §7 闸门④拍板文本） | `dumpsys meminfo` 导入前后对比 | 同 R2 hybrid；另评估 REC_BATCH 8→4（批宽不变时批次内存减半） |
| R4 batchW 极端大 | 实测出现 batchW>2048（E6 实测最大 ≈1488，1.4× 余量） | B 阶段逐页记录 max batchW 日志 | 停下记录触发页特征 → 回本计划补宽度上限并重新评分（不做「超限静默降级 320」——那会无声改变识别结果，违反口径纪律） |
| R5 float 舍入批宽 319 | V1 用例 `float rounding guard keeps 320` 红 | JVM 单测 | 已内置 coerceAtLeast(320)，红灯即实现错误当场修（未触发） |
| R6 调用方漏 minOf 截断 | fillNormalized require 抛 IllegalArgumentException | require 消息含「调用方漏做 minOf 截断？」提示 | 宁抛异常不做静默越界写（OcrRecPre KDoc 已声明） |

## 6. 成本预算（量化申报）

| 项 | 改动前 | 改动后 | 标注 |
| --- | --- | --- | --- |
| rec 单批输入 FloatArray | 8×3×48×320×4B ≈ 1.47MB | 批宽 320（全短行）≈1.47MB；最大批宽 1488 ≈ 6.8MB（+5.3MB/批，短命对象随批释放，不常驻） | 公式计算 MEASURED（式子即代码）；端上实测峰值归小计划 B（§8） |
| rec 单页耗时（PC 参照） | 1.09s/页 | 7.7s/页中位（≈7×） | MEASURED（matchB_dyn.json，聚合入库于 Phase 0 报告 §5） |
| rec 单页耗时（手机） | 全流程 2.2-3.0s/页 | 三档 [推断]：**保守 15s**（PC 7× 倍率全流程直乘 2.2s+渲染开销上浮）、**基线 11s**（倍率 5×，rec 占比<100% 折减）、**乐观 8s**（倍率 3×，批调度/内存带宽差异）——三档假设均为外推，实测归小计划 B；三档均可能超 5s 触发线，R2 预案已刚性化（§5 fallback 链），触发线不因外推值放宽 | [推断]（假设如列） |
| LLM 评分调用 | — | 本计划案 2 轮 + 代码包 3 轮 = 5 次成功调用，评审模型与本会话同源（glm-4.6 档），单次输入 ≈8-10k token；凭据走本机档案库（key 打码） | MEASURED 轮次 |
| 模块总览/文档机时 | — | M2 总览更新 + 本包 ≈30min（更新内容与收尾动作见 §10） | [推断] |

## 7. 与计划案偏差声明（2 处已定案）

1. 计划案 §2.4 测试表方向笔误（写「白→-1f、黑→1f」）：实际归一化下白(255)→1f、黑(0)→-1f（OcrEngine.kt:80-82 det 同式互证）。实现与单测按正确方向，计划案不回改（评分留档已定，此处勘误即台账）。
2. OcrRecPreTest 用例数据勘误：见 §4 红灯实录，实现零改动。

## 8. 端上效果边界（全部 UNMEASURED 项集中于此）

本质检包判定「实现与 PC 口径逐式对齐 + JVM 侧全绿」。以下端上项 **UNMEASURED**，属小计划 B 模拟器 E2E（计划案 A 已预登记衔接与 R2/R3 触发线）：手机端单页耗时（§6 三档 [推断] 的实测归位处）、增量内存峰值、兜底率改善幅度、Bitmap 路径运行时行为。

**Bitmap 路径 JVM 不可测的边界声明**：recognize 段的 Bitmap.createBitmap/createScaledBitmap/getPixels/recycle 为 Android API，JVM 单测（V1/V2）无法执行——这是 P6b 起的既有测试边界（旧版 rec 段同样 JVM 不可测），非本包新引入。处置：① 不引入 Robolectric 模拟 Bitmap（新依赖=依赖变更，须老板确认，P6b 曾裁定走真机/模拟器 E2E 路线）；② 不做接口抽象拆 BitmapCropScale（为测试而抽象会改生产代码结构，超出小计划 A 范围，且引擎薄壳化已由 OcrRecPre 纯函数抽取达成——数学逻辑全部 JVM 可测）；③ 运行时验证=小计划 B 模拟器 E2E，逐页计时/内存/兜底率实测即 instrumented 等价物。

## 9. 第 1 轮意见处理台账（51→本版）

| 第 1 轮意见 | 处理 |
| --- | --- |
| [8] 段尾代码省略 | §2.2 补 `val o = r.run` 至 `return out` 全文 + batchW/解码解耦自洽说明（输出 shape 驱动，与输入宽无耦合） |
| [4] 缺可复现命令 | §4 新增命令清单（V1-V3）+ 期望/实测/耗时/退出码 + XML 统计命令 |
| [5] 风险无触发条件/预案 | §5 改三列表（触发/检测/预案），补 R4 batchW>2048 条目；明确不采纳「静默降级 320」建议（口径纪律：无声改结果不可接受，改为停下重评分） |
| [10] 缺环境前置 | §0 新增：仓库、基线 commit 2056de0、工具链版本（Gradle 8.9/AGP 8.7.3/SDK 35/JUnit 4.13.2）、replica_ocr.py 位置与不入库说明、5 步复现路径 |
| [2] 行号引用不规范 | §1/§3/§5 统一「文件名:行号」；计划案引用标 §号 |
| [6] 成本量化不全 | §6 表格化：内存增量公式计算、手机端 [推断] 区间 8-15s/页（外推声明）、LLM 轮次与 token 量级 |
| [1] 缺量化验收 | §1 验收口径两条：本包=口径对照+命令全绿；端到端=小计划 B 沿用 Phase 0 判据（页 median/过闸/行数对照），不新设 BLEU（判据变更须报老板，不擅自立） |
| [3] require 约束力 | OcrRecPre KDoc 补 @param 契约与错误提示（生产代码同步更新，§2.1 为最新版）；V1 重跑绿 |

### 第 2 轮（89 分）意见处理

| 第 2 轮意见 | 处理 |
| --- | --- |
| [2] 关键结论无出处节号 | §1 新增「关键结论出处索引」五条（matchB_dyn.json、Phase 0 报告 §1/§5/§7、计划案 E5/E6 行） |
| [9] 缺收尾闸门 | 新增 §10：M2 总览真实路径与预期变更、三查命令（含正斜杠教训）、commit 模板、push 判据链 |
| [4] 口径对照无自动化 | §4 新增 V4 闭环映射表（V1 用例↔§3 行逐条挂钩）+ V5 git diff 未动段核对命令 |
| [5] R2 依据与 fallback 不刚性 | §5 R2 补：出处文件与原文位置、数值参照（基线 1.7-2.3×）、计时口径、fallback 链（hybrid 仍不达标→两案并陈报老板，不自动回滚） |

### 第 3 轮（85 分）意见处理（落实 6 条 + 声明式回应 2 条）

| 第 3 轮意见 | 处理 |
| --- | --- |
| [7] 复用散落无清单 | 新增 §1.5 资产复用清单表（8 行三列：资产/方式/是否重复实现） |
| [2] matchB_dyn.json 不入库数值不可追溯 | §1 出处索引改为「聚合值已内联入库（Phase 0 报告 §5）+ .e2e 逐页原始（不入库）」双轨引用——消除对非版本化文件的数值依赖 |
| [6] 耗时区间跨度大 | §6 拆保守 15/基线 11/乐观 8 三档并逐档标假设；触发线不放宽声明保留 |
| [4] V5 人工确认非自动化 | V5 改为可执行 grep 命令 + 期望输出判定规则（输出为空/仅 + 前缀同款行） |
| [1] §0 与 §1 文件数不一致 | §0 补「涉及文档（仅留档）」行，与 §1 对齐 |
| [3] Bitmap 路径 JVM 未验证 | §8 边界声明三处置：不引 Robolectric（依赖变更须老板确认）、不做接口抽象（超范围且纯函数抽取已达成薄壳化）、运行时验证归小计划 B 模拟器 E2E——P6b 起既有边界非本包引入 |
| [8] §6 混入 UNMEASURED 行 | §6 手机行改三档 [推断]（假设列明），UNMEASURED 全部集中 §8 边界节；§6 表内不再出现 UNMEASURED 字样 |
| [6-旧]（第 2 轮已处理项的连带） | LLM 调用次数更正 4→5（含本轮） |

## 10. 收尾闸门（本包通过后、Phase 1 P4 执行）

1. **模块总览更新**：路径 `docs/模块总览/M2-导入与解析.md`——「P6b 扫描书本地 OCR 主路径」行补一句动态宽口径（rec 高 48 等比缩放+批内定宽补零，对齐 PC G11，2026-10-05）；「已知坑」若小计划 B 实测出新坑随 B 补；`最后更新` 行改日期与摘要。同步检查 `docs/模块总览/README.md` 索引行是否需联动（职责未变则不动）。
2. **会话收尾三查**：`python "C:/Agent/AImanager/tools/check_session_close.py" "C:/AIWorkSpace/Studying-with-friend"`（正斜杠传参——Git Bash 反斜杠会被转义吞掉，Phase 0 实录教训）三项全绿。
3. **commit 纪律**：Phase 1 A+B 全部质检收口后统一 commit，message 模板 `feat(ocr): P6c Phase 1 rec 动态宽——OcrRecPre 纯函数+recognize 两阶段改造，673 测试全绿`；`.e2e/` 全目录 gitignore 不入库；commit 前人工过 diff（隐私扫描不认识自选密码类内容）。
4. **push 判据**：老板纪律=整体质检确认完全没问题才 push——即小计划 B 模拟器 E2E 亦收口后才执行 `python "C:/Agent/AImanager/tools/git_push_github.py" C:/AIWorkSpace/Studying-with-friend`（私有仓库 Lwb1397111398/Studying-with-friend）；本包通过≠push 信号。
