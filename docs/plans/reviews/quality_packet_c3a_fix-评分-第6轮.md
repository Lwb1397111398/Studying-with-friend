# 评分记录：quality_packet_c3a_fix.md · 第 6 轮 · 计划案

**总分：95**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 10
- 3 方法可行: 9
- 4 验证闭环: 10
- 5 风险预案: 9
- 6 成本预算: 10
- 7 资产复用: 10
- 8 无占位符: 9
- 9 纪律合规: 10
- 10 可执行性: 9

## 问题清单

- [方法可行] §4 step0 引用 saf_import.py（~120 行）但未内联全文，仅列接口说明与退出码口径。零上下文工程师拿到计划后需另行读仓库源码才能判断脚本行为是否符合预期（如 SAF 列表关键字匹配逻辑、确认流程坐标定位）。 → 建议：在 §4 step0 注释块中追加 saf_import.py 的 pick/confirm 两模式最小可运行代码段（或完整源码），或在 §2 资产复用清单中增加 saf_import.py 一行并链接到 .e2e/p6c/saf_import.py 具体行号范围。
- [风险预案] R5 预案仅靠人工监控 run 行计数与 force-stop 留证，明确「不加代码 retry 上限」。若 WorkManager 退避在极端网络抖动下失效（如 captive portal 反复 flapping），Worker 可能无限 retry 消耗商汤额度，预案无自动熔断。 → 建议：在 VisionWorker.kt 改 2 的 retry 分支追加 bookId 级 run 计数查询（如 dao.countRunsByBook(bookId) ≥ 5 时 return Result.success() 定格），与 §4 step7 人工监控形成双保险；或在 R5 预案中补充「若商汤调用量超过预算阈值则 force-stop 并登记工单」。
- [验证闭环] J3a 判据仅用 grep 检查 logcat 文本含 fallbacks=3 fallbackPages=3,4,5，但 grep 命中即过——若同一行文本格式变更（如 fallbacks 与 fallbackPages 间加换行）则 grep 失效而判据假过。Worker 改 2 引入的自动重排与既有 restorePending 路径的交互（restorePending 是否跳过已有 PENDING 项）无测试用例覆盖。 → 建议：J3a grep 改为多行组合断言（grep -A1 fallbacks 验证 fallbackPages 紧随其后）；在 VisionRebuilderPureTest 或新建 WorkerIntegrationTest 中添加 restorePending + retry 交互场景的纯函数测试（模拟 dao.byBook 返回含 PENDING 项时 Result.retry 行为）。
- [目标与范围] §1 一句话目标将 P0 重建修复与 P2 Worker 消化缺口两个独立改动合并陈述，验收判据已按 P0/P2 分级依赖，但目标句本身未体现优先级依赖（P0 未过则整体未完成）。 → 建议：将目标句改为分句结构：「P0：重建底稿改从数据库取……（保书应用而非洗书）；P2：Worker 消化缺口补自动重排与失败日志（P0 未过则 P2 不生效）」。
- [证据出处] §0.2 inferTocLike 阈值 3 的「真书目录条目数恒 ≥8 章」标注 DECLARED 且样本仅仿真书 1 本，但 §2.2 代码注释与 §3 J3c 期望值均隐含依赖该假设成立（11 章来自仿真书实测而非真书）。 → 建议：在 §0.2 末尾补一行敏感性预算：若真书目录条目 <3 导致 inferTocLike 假阴性，仅影响该页切分密度不影响重建触发与字符量——量化最坏情况字符偏差（如 ±200 字），确认仍落在 J3c [14772,15200] 区间内即可。
- [验证闭环] J3c 上界 15200 的推导写为「14772 + 目录页 9 条实测字数 × 余量」但未展示余量数值与计算过程，零上下文评审无法复核 15200 是否过松或过紧。 → 建议：在 §3 J3c 期望值出处行追加推导：「目录页 9 条视觉条目实测 486 字符，上界 = 14772 + 486 × 1.08（合并/断句差异裕量）≈ 15200，取整到百位」。
- [无占位符] §0.3 基线描述「commit 4034a75 之后工作区含 C2 改动（6 文件已质检过线未提交）」未列出 6 文件清单，评审无法确认本修复的改动面与 C2 是否有重叠或冲突。 → 建议：在 §0.3 基线行后追加 C2 6 文件清单（文件路径或文件名列表），或注明「C2 文件清单见 .e2e/p6c/c2_diffstat.txt」。
- [证据出处] §0.2 页 4/5 标记为空白页的依据是「.e2e/p6c/probe_p4.png 渲染实证全白」与「wiped 前库中页 3/4/5 段落数=0」，但 probe_p4.png 为图像文件无法在文本计划中复核，段落数=0 的查询命令未给出。 → 建议：在 §0.2 页 4/5 论证处追加验证命令：「复核命令：sqlite3 c3a_after.db "SELECT p.pageNo, count(*) FROM paragraphs p JOIN chapters c ON p.chapterId=c.id WHERE c.bookId=1 AND p.pageNo IN (3,4,5) GROUP BY p.pageNo"」，使图像证据可被数据库查询交叉验证。