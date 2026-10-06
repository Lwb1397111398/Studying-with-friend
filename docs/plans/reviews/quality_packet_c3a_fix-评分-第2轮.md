# 评分记录：quality_packet_c3a_fix.md · 第 2 轮 · 计划案

**总分：88**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 9
- 3 方法可行: 9
- 4 验证闭环: 9
- 5 风险预案: 9
- 6 成本预算: 9
- 7 资产复用: 9
- 8 无占位符: 8
- 9 纪律合规: 9
- 10 可执行性: 8

## 问题清单

- [10 可执行性] §4 step6 引用§3 SQL 但 SQL 中 bookId 写作占位符「<new>」，§4 未显式要求先执行 SELECT max(id) FROM books 获取实际值再代入；该获取方式仅写在§7-R6 风险预案中，零上下文工程师照§4 逐步执行时会卡住 → 建议：在§4 step5 与 step6 之间插入一行：先跑 sqlite3 … 'SELECT max(id) FROM books' 取新书 id 并记为 BOOKID，后续所有 <new> 替换为该值；同时将§3 表格中 J3a-J4 的 <new> 统一替换为 <BOOKID> 并注明来源
- [8 无占位符] §2.5 VisionRebuilderPureTest.kt 代码块仅含 assertFalse/assertTrue/Test 三条 import，但第11用例 assemble_viaPdfExtractResult_formatLocked 使用了 assertEquals 及 PdfExtractResult/DocStats/PageOut/Para 五个类——代码块本身无法编译；补充 import 信息写在代码块下方的注释段落，未内联进代码块 → 建议：将代码块首部的 import 区补全为完整列表（加入 import org.junit.Assert.assertEquals 与 com.studyfriend.app.data.importer.PdfLoader、com.studyfriend.app.data.importer.pdfpipeline.DocStats/PageOut/Para），使代码块自包含可直接编译，删除下方补充说明段落