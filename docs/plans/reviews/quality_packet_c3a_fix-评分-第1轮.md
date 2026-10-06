# 评分记录：quality_packet_c3a_fix.md · 第 1 轮 · 计划案

**总分：83**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 9
- 3 方法可行: 8
- 4 验证闭环: 8
- 5 风险预案: 8
- 6 成本预算: 9
- 7 资产复用: 8
- 8 无占位符: 7
- 9 纪律合规: 9
- 10 可执行性: 8

## 问题清单

- [8 无占位符] §2.3两处VisionWorker改动以代码片段形式呈现（原文标注「两处小改」），§2.4测试适配以文字描述而非完整代码diff给出；§4 step5引用的python .e2e/p6c/saf_import.py pick sample_book_scanned脚本是验证路径关键环节但未提供实现或接口说明，零上下文工程师遇阻即停 → 建议：§2.3补全两处改动的完整diff（含上下文行号与函数签名），§2.4给出VisionRebuilderTest修改后的完整代码或unified diff；§4 step5附saf_import.py的接口文档或至少其help输出与sample_book_scanned文件路径确认命令
- [3 方法可行] §2.2 VisionRebuilder.kt中PdfExtractResult构造函数传入DocStats(Float.NaN, 0f, 0f, null)，但未说明DocStats构造函数签名或验证NaN与null是否被assembleText和parse路径正确处理，存在运行时崩溃风险 → 建议：在§2.2注释或§3断言中补充对DocStats构造函数的参数合法性验证，或在新增纯函数用例中加入DocStats NaN/null参数的边界测试
- [4 验证闭环] J3b要求VisionWorker run行与SUCCESS行间隔小于10s但未给出具体测量方法（如何从logcat中提取两行时间戳并计算秒差），断言不可复现 → 建议：补充awk或grep命令提取两行logcat时间戳并计算秒差的脚本示例，例如grep两行后管道至date命令差值计算
- [5 风险预案] R5预案本质为断言逻辑互斥不可能出问题（Worker收尾只在有PENDING时retry，gate/命中页已置DONE不进PENDING集合）而非真正的应急预案，若实际交互出现异常则无补救措施 → 建议：增加R5应急预案：若retry导致Worker无限循环或异常退避，在VisionWorker中添加retryCount上限并在超限时降级为SUCCESS并Log.e留痕
- [10 可执行性] §4 step5依赖sample_book_scanned文件和saf_import.py脚本，但未说明sample_book_scanned在工作区中的绝对路径，也未验证saf_import.py脚本是否存在 → 建议：在§0基线或§4开头补充sample_book_scanned的文件路径确认命令（ls或find）和saf_import.py的存在性检查（python -c import check或ls）