# 评分记录：quality_packet_c3a_fix.md · 第 3 轮 · 计划案

**总分：80**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 8
- 3 方法可行: 8
- 4 验证闭环: 8
- 5 风险预案: 7
- 6 成本预算: 7
- 7 资产复用: 8
- 8 无占位符: 9
- 9 纪律合规: 8
- 10 可执行性: 8

## 问题清单

- [5 风险预案] R1 预案「比对新旧章标题清单人工裁定」无裁定标准与责任人；R3 预案「拍板值变更报老板」无老板响应后的处置分支——两者均缺乏可操作闭环。 → 建议：R1 补裁定标准（如章标题集合差异≤20%视为漂移可接受）与裁定人；R3 补老板响应分支（同意则调阈值重跑 J3、拒绝则立案重查解析逻辑）。
- [6 成本预算] LLM 轮次「≤7 轮」与模拟器「≈12min」均为整体粗估；OCR/消化/断言三阶段仅合计无分项，若消化阶段超预期（如缓存未命中触发商汤调用）总时长无法预测。 → 建议：按 §4 step 编号逐项估时（step1 JVM 4min、step2 打包 3min、step4 启动+logcat 1min、step5 OCR 5min、step5.5 BOOKID 0.5min、step6 断言 2min、step7 监控 0.5min），标注缓存命中与未命中两种消化路径的商汤调用数与耗时。
- [4 验证闭环] J3b 的 Python 计时脚本假设 logcat 行含特定字段（「VisionWorker: run bookId=」「Worker result SUCCESS」），J3c 的 SQL 假设表结构与列名——均无前置校验，若格式/schema 不符则静默失败（脚本报 KeyError、SQL 返回空集）。 → 建议：§4 step 4 后加 logcat 格式校验（grep 一次目标行确认存在）；step 6 前加 schema 校验（sqlite3 .schema 比对 paragraphs/chapters/vision_queue 列名），不符则显式中止并报错。
- [2 证据出处] J3c 三个关键期望值（chapters==11、chars∈[14772,15200]、pageNo=3 段落数=9）未标注证据路径或推断依据，评审者无法验证这些数字的正确来源。 → 建议：在 §0.1 F1 证据列或 §3 J3c 旁补充出处（如「.e2e/p6c/c3a_after.db 快照中 chapters=11、sum(length(text))=14772、pageNo=3 段落=9 均为导入管线原始产物，修复后应等价重组」），或标注「推断：基于 C3a 快验实录中导入完成时库状态」。
- [3 方法可行] coverageOk 80% 阈值与 TOC_INFER_MIN=3 均标 DECLARED 但无实证数据支撑（如历史书重建字符比分布或目录页条目密度统计），若阈值不当则 J3c 断言可能系统性失败或守卫形同虚设。 → 建议：在 §0 补 1-2 行实测数据（如「C2 期间 3 本仿真书重建字符比实测范围 0.85-0.97，80% 裕量充足」；「仿真书目录页条目数实测 9 条，阈值 3 为 33% 裕量」），将 DECLARED 转为 MEASURED。
- [10 可执行性] §4 step 5 FAB 坐标 (953,1937) 硬编码无分辨率适配说明；整个 §4 假设 Windows/MSYS2 环境但未声明前置条件（adb 路径、sqlite3 安装、MSYS_NO_PATHCONV 环境变量行为）。 → 建议：step 5 前加分辨率检测（adb shell wm size）并标注坐标适用分辨率；§4 开头加前置条件检查清单（adb/sqlite3 已安装、MSYS2 bash、Python3）。
- [9 纪律合规] §4 step 6 直接执行 §3 判据表 SQL，未先校验表结构；若 C2 改动引入 schema 变更（如新增列或改列名），断言全部静默返回空集而非显式报错，可能导致误判通过。 → 建议：step 6 前加 sqlite3 .schema 校验命令，比对 paragraphs/chapters/vision_queue 预期列名，不符则 echo「SCHEMA MISMATCH」并 exit 1。
- [1 目标与范围] 目标复合（重建修复 + Worker 消化缺口）但验收判据未分离优先级——J3a/b 验证 Worker 改动、J1/J2/J3c/d 验证重建改动，若 Worker 改动通过但重建失败，无法判断修复整体成败，也无法决定先修哪块。 → 建议：在 §1 验收判据前加优先级声明（如「P0 重建保书=J1/J2/J3c/d 全过；P2 Worker 缺口=J3a/b 全过；P0 未过则 P2 无效」），明确两组判据的依赖关系。