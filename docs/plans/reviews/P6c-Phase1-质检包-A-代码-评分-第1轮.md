# 评分记录：P6c-Phase1-质检包-A-代码.md · 第 1 轮 · 代码

**总分：51**（≥90 过）

- 1 目标与范围: 7
- 2 证据出处: 7
- 3 方法可行: 8
- 4 验证闭环: 6
- 5 风险预案: 4
- 6 成本预算: 6
- 7 资产复用: 9
- 8 无占位符: 6
- 9 纪律合规: 3
- 10 可执行性: 5

## 问题清单

- [8 无占位符] §2.2 rec 段末明确写「段尾 val o = r.run(...) 至 return out 未改动，此处省略展示；完整 diff 见 commit」——质检包要求逐字与仓库一致，但关键推理/解码/回写段被省略，评审者无法验证该段与 batchW 改造是否自洽（如 tensor shape 第二维从固定 recW 改为 batchW 后解码索引是否匹配）。 → 建议：在质检包内补齐段尾完整代码（从 val o = r.run 到 return out），或提供 git diff 全文粘贴；若确因篇幅省略，至少贴出形状参数变化处的完整上下文（前后各 5 行）并附 git diff --stat 验证行数一致。
- [4 验证闭环] §4 仅给出测试结论文本（673 tests, 0 failures）但未列出任何可复现的验证命令；零上下文工程师拿到此包无法独立跑测试或验证构建。 → 建议：在 §4 前新增「验证命令清单」小节，列出：1) `./gradlew :app:testDebugUnitTest --tests "com.studyfriend.app.data.importer.ocr.OcrRecPreTest"` 及期望输出 `BUILD SUCCESSFUL` + `9 passed`；2) `./gradlew :app:testDebugUnitTest` 及期望 XML 汇总行数命令；3) `./gradlew :app:assembleDebug` 及期望输出。每条注明预期耗时和退出码。
- [5 风险预案] 已知风险仅以文字描述形式列出（float 截断→coerceAtLeast 护栏；≤2px 下采样差；手机端耗时 UNMEASURED），但无触发条件（何时判定为风险事件）、无具体预案（触发后怎么做）。例如 batchW 极端大（如 >4096）时 ONNX 内存溢出无预案。 → 建议：为每个风险补充三列表：触发条件（如 batchW>2048 或单页耗时>15s）、检测方式（日志/断言/监控指标）、预案动作（截断到上限并降级为 320 固定宽 + 上报降级事件）。
- [10 可执行性] 零上下文工程师拿到此包后：不知道 clone 哪个仓库、不知道 checkout 哪个 commit、不知道 PC Python 参考文件路径（replica_ocr.py 在哪个目录）、不知道 gradle 版本和 SDK 版本。§1 只列文件名但未给相对路径基准或仓库 URL。 → 建议：在文档头部增加「环境前置条件」小节：仓库 URL/SSH、目标 commit hash、Android SDK/Gradle/JDK 版本、PC replica_ocr.py 绝对路径或仓库相对路径、首次 clone 到跑测试的完整步骤编号（≤15 步）。
- [2 证据出处] §3 口径对照表中多处引用精度不一致：PC 侧标注了行号（如 :128、:257-258），但 Kotlin 侧旧版引用只写「旧版 101-104 行」「旧版 128-140 行」无文件名；「计划案 §2.4」无行号或页码。MEASURED 标注虽在 §4 标题出现，但 §1 改动范围表中「41 行」等数字未标注出处。 → 建议：统一为「文件名:起始行-结束行」格式，如 `OcrEngine.kt:101-104（改动前）`；计划案引用补行号如 `P6c-Phase1-计划案-A-rec动态宽.md:L45-52`；所有数值结论在首次出现处加 `[MEASURED]` 或 `[推断]` 标签。
- [6 成本预算] §6 成本申报量化不全：手机端 rec 耗时标 UNMEASURED 且无估算区间；LLM 调用仅写「每轮 1 次」无 token 数或模型名；ONNX 推理内存增量（batchW 从 320 涨到 1488，单帧 FloatArray 从 ~46KB 涨到 ~212KB×batch）未评估。 → 建议：补充表格：1) 手机端 rec 耗时估算区间（基于 PC 7× 倍率 × 手机 CPU 衰减系数，标注为 [推断]）；2) LLM 模型名、输入 token 预估、总费用；3) ONNX 单帧峰值内存增量及 GC 影响评估。
- [1 目标与范围] 目标「rec 动态宽改造」可理解但缺少量化验收标准——何为「对齐 PC 口径」？conf 偏差容忍度？同一图片 Android↔PC 文本一致率？当前仅以「JVM 单测全绿」作为判据，但单测只测纯函数正确性，不测端到端输出一致性。 → 建议：在 §1 末尾补充可量化验收指标：如「同一 50 页测试集，Android 与 PC 输出文本 BLEU ≥0.95 且 conf 均值偏差 <0.03」，并声明这些指标的验证时点（本包或小计划 B）。
- [3 方法可行] §2.1 OcrRecPre 的 `fillNormalized` 函数注释写「契约 rw ≤ batchW（超宽下采样由引擎 Bitmap 缩放完成）」，但调用方 §2.2 中 `tgtW = minOf(rws[bi], batchW)` 后才传 `tgtW` 给 `fillNormalized(cpx, tgtW, batchW, data, bi)`，此时 tgtW ≤ batchW 恒成立，require 不会触发。该契约描述虽正确但不构成对调用方的约束力——若未来调用方重构漏掉 minOf，require 会抛异常而非静默错误。 → 建议：在 fillNormalized 的 KDoc 中增加 @param rw 说明「由调用方保证 ≤ batchW（minOf 截断后传入）」，并在 require 消息中提示调用方检查 minOf 是否遗漏；或在调用方处增加断言 `assert(tgtW <= batchW)` 作为调试期防护。