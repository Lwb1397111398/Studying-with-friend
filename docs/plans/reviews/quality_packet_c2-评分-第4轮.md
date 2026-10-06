# 评分记录：quality_packet_c2.md · 第 4 轮 · 质检包

**总分：89**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 9
- 3 方法可行: 8
- 4 验证闭环: 9
- 5 风险预案: 9
- 6 成本预算: 9
- 7 资产复用: 9
- 8 无占位符: 10
- 9 纪律合规: 9
- 10 可执行性: 8

## 问题清单

- [3 方法可行] §4 V1 python XML汇总脚本glob路径为app/build/test-results/...（基于android目录），但命令块未标注cd android前缀；零上下文工程师若在仓库根目录执行将glob零命中导致汇总输出全零 → 建议：在V1 python命令前加cd android &&前缀或在python命令块上方加注释「执行目录=android/」
- [10 可执行性] §2.3三用例调用line(「竖排标题」, 100f, 100f, 130f, 220f)但仅注「line()为该测试类既有私有方法」，未内联签名或参数说明；零上下文工程师须自行checkout源码后推断fun line(text: String, x0: Float, y0: Float, x1: Float, y1: Float): OcrLine → 建议：在§0.5复用资产表line()行或§2.3用例注释中补内联签名行：private fun line(text: String, x0: Float, y0: Float, x1: Float, y1: Float): OcrLine
- [1 目标与范围] 文档标「第 2 版」但§9处理台账含3轮反馈（74→85→88共19条意见），版本号与实际处理轮次不一致，评审者难以判断当前版本对应的反馈基线 → 建议：将版本标记更新为「第 4 版」（原版=第1版→74分修订=第2版→85分修订=第3版→88分修订=第4版），或在§9开头加版本口径说明
- [5 风险预案] R2预案以结构性论证「不会发生」为主（pageNo∈[1,total]不变量），若IndexOutOfBoundsException实际触发仅说「走J4立案链」，缺少具体修复步骤 → 建议：R2预案补具体修复步骤：第一步在f.pageNo-1访问前加if(f.pageNo<1||f.pageNo>pagesOcrLines.size){Log.w(...);return@forEach}防御；第二步查pass2DoneLine入参total与pagesOcrLines.size一致性定位漂移根因
- [9 纪律合规] §8三查命令python C:/Agent/AImanager/tools/check_session_close.py C:/AIWorkSpace/Studying-with-friend硬编码绝对Windows路径，非Windows或不同工作目录结构下不可执行 → 建议：补相对路径方案或环境变量声明：如export TOOL=... && cd repo-root && python $TOOL/check_session_close.py .；或声明该命令仅在本机C5收尾执行、属非通用工具