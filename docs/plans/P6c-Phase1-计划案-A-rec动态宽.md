# P6c Phase 1 · 小计划 A：手机生产引擎 rec 预处理动态宽改造

> 状态：待评分（score_review.py，≥90 过线循环）
> 上游：P6c-Phase0-报告.md §7 闸门④已获老板拍板立项（2026-10-05）；本计划为 Phase 1 拆分的小计划 A（代码改造），小计划 B（模拟器 E2E 实测）在 A 落地后另案另评。

## 0. 一句话目标

把手机生产引擎 `PpOcrEngine` 的 rec 预处理从「固定 320×48 硬拉伸」改为「高 48 等比缩放 + 批内定宽右侧补零」，逐条对齐 PC 端 G11 已实证口径（`.e2e/p6c/replica_ocr.py` rec_pre_dyn + norm_pad_dyn），预期把手机端扫描书不可用页率从 87%（401/461 兜底，MEASURED）降到接近 PC 全量实测的 3.0%（14/461，MEASURED）。本计划只改 rec 输入构造，det 推理、CTC 解码、页级门控、下游管线零改动。

## 1. 背景与证据出处（全部可核对）

| # | 证据 | 出处 |
| --- | --- | --- |
| E1 | 根因：rec 硬拉伸把 1300-1500px 长行压约 4 倍 → 字形毁 → CTC 全 blank → conf 崩 → 401/461 页兜底 | 2026-10-05 交叉实验（P6c-Phase0-报告.md §1/§5） |
| E2 | PC 修复后全量 401 兜底页复跑：过闸 387/401=96.5%、页 median 中位 0.9225、单页中位 7.7s（PC CPU）；全书不可用页率 14/461=3.0% | `.e2e/p6c/matchB_dyn.json`（G11 全量，MEASURED） |
| E3 | PC 动态宽口径源码：rec_pre_dyn 裁剪后高 48 等比缩放（`rw = max(1, ceil(w*48/h))`）、norm_pad_dyn 批内定宽 `img_w` 归一化后右侧补零、批宽 `int(48 * max(320/48, 批内最大宽高比))` | replica_ocr.py:119-139、257-258、278-279 |
| E4 | 手机现行代码：`OcrEngine.kt:93-116` rec 段 `recW = 320` 固定宽拉伸；320 硬编码全项目仅此文件 3 处（41/93/95 行，grep 实证） | OcrEngine.kt |
| E5 | rec ONNX 模型接受动态宽输入：PC 用同一模型文件变宽批推理跑完 401 页（E2 即证据）；手机与 PC 用同一套模型文件（ppocrv5-mobile-rec.onnx） | E2 推得 + P6b 模型分发清单 |
| E6 | 批内宽高比实测最大 ≈31（输入宽 ≈1488），401 页真实数据无病态框 | m2_code_bundle.md §3 风险册（实测） |
| E7 | 引擎唯一消费方 `OcrImportRunner` 只读 `OcrLine(text/x0/y0/x1/y1/confidence)`，页级门控（median<0.85 或低置信行占比>20%）在 runner 侧，与 rec 输入构造解耦 | OcrImportRunner.kt:68,257-270 |

## 2. 改动范围（2 个文件新增/修改 + 1 个测试文件新增）

### 2.1 新增 `OcrRecPre` 纯函数对象（OcrEngine.kt 内新增，与 OcrDetPost/OcrCtc 同风格同文件）

```kotlin
/** rec 动态宽预处理纯函数（P6c Phase 1，对齐 PC replica_ocr.py rec_pre_dyn/norm_pad_dyn 口径）*/
object OcrRecPre {
    private const val REC_H = 48
    private const val REC_MIN_W = 320  // PC 口径：批宽下限 320（ratio 下限 320/48）

    /** 单框 rec 输入宽：高 48 等比缩放后的宽 = ceil(cropW*48/cropH)，最小 1（PC rec_pre_dyn:128 同式）*/
    fun cropRecWidth(cropW: Int, cropH: Int): Int =
        maxOf(1, ceil(cropW * REC_H.toDouble() / cropH).toInt())

    /** 批内统一定宽 = int(48 * max(320/48, 批内最大宽高比))，下限 320（PC replica:257-258 同式；
     *  末尾 coerceAtLeast 防 float 舍入 48*(320f/48f)=319.99 截断成 319）*/
    fun batchRecWidth(cropWs: IntArray, cropHs: IntArray): Int {
        var maxRatio = REC_MIN_W.toFloat() / REC_H
        for (i in cropWs.indices) {
            val r = cropWs[i].toFloat() / cropHs[i]
            if (r > maxRatio) maxRatio = r
        }
        return maxOf(REC_MIN_W, (REC_H * maxRatio).toInt())
    }

    /** 把已缩放到 (rw×48) 的 ARGB 像素归一化 ((c/255-0.5)/0.5) 填入批次 data 第 bi 行
     *  平面布局 [3][48][batchW]，内容靠左、右侧补零（PC norm_pad_dyn:137-138 同构）；
     *  契约 rw ≤ batchW（超宽下采样由引擎 Bitmap 缩放完成，本函数只管填）*/
    fun fillNormalized(px: IntArray, rw: Int, batchW: Int, data: FloatArray, bi: Int) {
        require(rw in 1..batchW) { "rw=$rw 超出批宽 batchW=$batchW" }
        val plane = REC_H * batchW
        val off = bi * 3 * plane
        for (y in 0 until REC_H) for (x in 0 until rw) {
            val p = px[y * rw + x]
            val di = off + y * batchW + x
            data[di] = (((p shr 16 and 0xFF) / 255f) - 0.5f) / 0.5f
            data[plane + di] = (((p shr 8 and 0xFF) / 255f) - 0.5f) / 0.5f
            data[2 * plane + di] = (((p and 0xFF) / 255f) - 0.5f) / 0.5f
        }
        // 右侧 [rw, batchW) 保持 FloatArray 默认 0f = 归一化零点（灰 0.5），与 PC np.zeros 同义
    }
}
```

### 2.2 修改 `PpOcrEngine.recognize` rec 段（OcrEngine.kt:93-141 整段替换，完整代码如下）

```kotlin
        // ---- rec：批 8，高 48 等比缩放 + 批内定宽右侧补零（P6c Phase 1 动态宽，
        //      对齐 PC replica_ocr.py rec_pre_dyn/norm_pad_dyn 口径），CTC greedy 解码 ----
        val sx = bitmap.width.toFloat() / W; val sy = bitmap.height.toFloat() / H
        val recH = 48
        val out = mutableListOf<OcrLine>()
        for (chunk in boxes.chunked(REC_BATCH)) {
            // 阶段一：逐框算裁剪矩形与等比目标宽（不缩放；坐标 coerce 与原 101-104 行逐行同）
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
                val tgtW = minOf(rws[bi], batchW) // rw>batchW 的 ≤2px 下采样，对齐 PC norm_pad_dyn:133-135
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
                // 解码空串的框丢弃（det 切出的无字符区域）；box 记原图像素坐标（与原 128-140 行逐行同）
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

对照说明：`rects`/`rws` 阶段一复用原 101-104 行坐标 coerce 逐行同款；从 `val t = OnnxTensor...` 起（tensor 构造、推理、解码、空串丢弃、OcrLine 回写）与原 117-141 行仅两处差异——形状第二维 `recW`→`batchW`、注释更新，其余逐行相同。`minOf(rws[bi], batchW)` 的缩放一步到位（原代码是固定 320 一步拉伸，同为单次缩放），**单步缩放微差声明**：PC 是先放大到 rw 再在超批宽时缩小到 batchW（双重插值）；Kotlin 直接一步缩到 min(rw, batchW)。差异仅在最大比框 ≤2px 的插值路径上，与「cv2.resize vs Bitmap.createScaledBitmap 本就非逐位等价」同一级别（P6b 起已接受的结构口径对齐，非像素级对齐）。结构口径（等比缩放式、批宽公式、补零语义、归一化式）逐条一致。

### 2.3 修改 KDoc 与注释

OcrEngine.kt:41 类注释「rec 批 8（48×320 拉伸）」改为「rec 批 8（高 48 等比缩放、批内定宽补零，P6c Phase 1 动态宽）」；93 行段注释同步。

### 2.4 新增 `OcrRecPreTest.kt`（android/app/src/test/.../ocr/，JVM 纯函数单测）

| 用例 | 断言 |
| --- | --- |
| cropRecWidth 正常行 | 1000×40 → ceil(1200)=1200；640×48 → 640 |
| cropRecWidth 细高框 | 1×100 → max(1, ceil(0.48))=1 |
| cropRecWidth 非整除 ceil | 100×48 → ceil(100)=100；101×48 → ceil(100.99…)=101 |
| batchRecWidth 全短行 | 多框 ratio<6.67 → 320（下限生效） |
| batchRecWidth 有长行 | 含 1488×48 框 → int(48*31)=1488（floor 截断口径） |
| batchRecWidth float 舍入护栏 | 全部 320×48 框 → 恰 320 而非 319 |
| fillNormalized 靠左填+右侧零 | 2×2 像素填 48×8 批：白像素→-1f、黑→1f、灰→0f；x≥rw 全 0f；三通道平面偏移正确 |
| fillNormalized 契约 | rw>batchW 抛 IllegalArgumentException |

## 3. 验证闭环

1. **新增单测**：上表 8 条全绿（`gradlew :app:testDebugUnitTest --tests "*OcrRecPreTest"`）。
2. **回归（B 实现后回查 A，老板纪律）**：`gradlew :app:testDebugUnitTest` 全量绿（P6b 基线 664 绿+1 skipped + 本计划新增），确认 det 路径（OcrDetPost）、CTC（OcrCtc）、门控（OcrImportRunnerPageGateTest）、后处理（OcrTextPostProcessorTest）、竖排检测（OcrVerticalDetectorTest）零回归——本次改动不触碰它们的输入输出契约。
3. **构建**：`gradlew :app:assembleDebug` 通过。
4. **接口不变量**：`OcrEngine` 接口签名、`OcrLine` 字段零变更（grep 证实唯一消费方 OcrImportRunner 无需改动）。
5. **端上真效**（属小计划 B，不在本计划判定内但预登记衔接）：模拟器 E2E 实测单页耗时/内存/兜底率，与 PC G11（过闸 96.5%、7.7s/页上界参考）对照。

## 4. 风险预案（触发条件 + 具体动作）

| 风险 | 触发条件 | 动作 |
| --- | --- | --- |
| R1 rec 模型拒绝变宽输入 | 模拟器首跑 ONNX 报 shape 错 | 停下报老板（PC 同模型变宽已实证 E5，预期概率低）；不走绕过改造 |
| R2 单页耗时膨胀（rec 输入像素 ≈4.7×） | 小计划 B 实测单页 >5s | 启用 Phase 0 预登记 hybrid 预案：rw≤320 的行走现行固定 320 口径、rw>320 的行走 dyn、批按方案分组——hybrid 属新改动，另写小计划案送评分，不直接改 |
| R3 内存膨胀 | 小计划 B 实测导入中增量 PSS >256MB | 同 R2 hybrid 路线；另评估 REC_BATCH 8→4（批宽不变时内存减半） |
| R4 病态宽高比框 | 理论边界（det 后处理 MIN_BOX_AREA=32 + 均值≥0.5 过滤后未实测出现，E6 实测最大 ratio≈31） | 不加宽度上限（保持与 PC 口径逐条一致）；若 B 阶段实测出现 batchW>3000，回本计划补上限并重评分 |
| R5 float 舍入致批宽 319 | 单测 batchRecWidth 护栏用例红 | 已在实现内置 coerceAtLeast(320)，红灯即实现错误当场修 |

## 5. 成本预算（前后对比申报）

- **LLM 调用**：本计划案评分 1 次/轮（score_review.py，第二支档案）；代码质检轮另计。不新增常驻调用。
- **机时**：改代码+单测 ≈1 轮构建验证（~3min gradle）；模拟器机时归小计划 B。
- **端上运行成本（申报）**：rec 输入像素量从固定 3×48×320/框 变为 3×48×min(rw,batchW)/框，实测最大批宽 ≈1488 vs 320（≈4.7×），rec 推理耗时上升是修复效果的必然代价——PC 实测 1.09→7.7s/页（≈7×，E2）。**手机端现行口径基线（MEASURED）**：S7 E2E shpc 461 页两轮导入总耗时 16.6-22.7min，折合全流程（渲染+det+rec+后处理）单页 2.2-3.0s（P6b-落地报告.md §S7）。手机端动态宽倍率 UNMEASURED，由小计划 B 实测申报。**R2「单页>5s」阈值出处**：非本计划新设判据，系 Phase 0 报告 §7 闸门④老板已拍板文本中预登记的 Phase 1 风险触发条件（单页>5s 或增量内存>256MB → hybrid 预案），本计划直接继承；R3 的 256MB 同源。

## 6. 资产复用

- 纯函数对象模式：照 `OcrDetPost`（OcrEngine.kt:159-205，纯函数+常量私有）与 `OcrCtc`（OcrEngine.kt:209-237）既有风格，JVM 可测、引擎薄壳化。
- PC 口径源码：replica_ocr.py:119-139/257-258 逐式对齐（E3），不发明新公式。
- 测试风格：OcrEngineTest 无共享 fixture 类，其辅助方法 `probMap`（OcrEngineTest.kt:64）示范「直构 FloatBuffer/数组 + 纯函数断言」写法；OcrRecPreTest 沿用同款直构风格，不引入新测试依赖（无 Robolectric、无 mock）。

## 7. 纪律合规

- 判据数值零变更：页级门控 0.85/0.5/20% 不动（老板已裁定不下调）。
- 危险操作：无删除/迁移/依赖变更/接口变更。
- 模块总览：改完立即更新 `docs/模块总览/M2-导入与解析.md`（P6b 段补动态宽一句）+ M2 已知坑若 B 阶段有实测新坑再补。
- git：本计划完成不 push；Phase 1 整体质检（含小计划 B）全绿后统一 commit+push。
- 标注纪律：本文所有数字已标 MEASURED/UNMEASURED；手机端端上效果在 B 前一律 UNMEASURED。

## 8. 不做什么（范围控制）

- 不动 det 预处理/后处理（unclip 是 Phase 1 备选增强，另案）。
- 不动 OcrImportRunner、门控阈值、视觉兜底队列。
- 不在本计划内做模拟器实测（小计划 B 另案另评）。
- 不加 rec 宽度硬上限（R4 触发才回补）。
