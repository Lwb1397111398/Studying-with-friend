# 评分记录：quality_packet_c3a_fix.md · 第 5 轮 · 计划案

**总分：88**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 9
- 3 方法可行: 9
- 4 验证闭环: 8
- 5 风险预案: 9
- 6 成本预算: 9
- 7 资产复用: 9
- 8 无占位符: 8
- 9 纪律合规: 9
- 10 可执行性: 8

## 问题清单

- [可执行性] §4 step5 OCR轮询for循环30轮后无论是否命中「Pass2 done」均直接执行confirm，无超时检测与中止机制，OCR未完成时confirm可能在半成品状态操作 → 建议：循环结束后加 `grep -q「Pass2 done」… || { echo「OCR TIMEOUT」; exit 1; }` 确保命中后才continue
- [验证闭环] §3 J3c期望日志含「…」省略号，grep无法精确匹配，J3c断言无法自动化 → 建议：将「…」改为精确正则如 `chars=14772→(\d+)` 并注明提取后用范围校验
- [可执行性] saf_import.py全文不在本文档，引用「小计划C计划案C3a节」，零上下文工程师需另找来源才能执行step5 → 建议：在§4 step5前附saf_import.py全文或至少附完整接口签名与错误返回码说明
- [验证闭环] §4 step6六条SQL断言命令无set -e、无前置exit检查，BOOKID为空或错误时全部静默返回空结果，误判通过 → 建议：命令块首加 `set -e` 并在BOOKID赋值后加 `[ -z "$BOOKID" ] && { echo「BOOKID EMPTY」; exit 1; }`
- [验证闭环] J3b-计时Python脚本用next()生成器无try/except，无匹配行时抛StopIteration崩溃而非友好报错，调试成本高 → 建议：将两个next()调用包裹try/except StopIteration，print含上下文消息后exit 1
- [可执行性] §4 step4.5固定sleep 30检测VisionWorker行，系统慢时不够、系统快时浪费30秒，时序依赖脆弱 → 建议：改为轮询式：`for i in $(seq 1 6); do grep -q「VisionWorker: run bookId=」file && break; sleep 5; done` 间隔5秒×6次=30秒总超时
- [方法可行] §0.2 inferTocLike阈值3基于仿真书n=1样本（9条÷3），标注DECLARED但真书目录条目数分布未实测，推断依据薄弱 → 建议：补1-2本真书目录页视觉转写样本验证条目数≥8假设，或将阈值参数化并附敏感性分析
- [可执行性] §4 step1-3（gradlew test/assembleDebug/adb install -r）无失败检测，构建或安装失败后后续步骤仍继续执行，浪费时间且可能产生误导 → 建议：命令块首加 `set -e` 或每条关键命令后加 `|| { echo「FAILED」; exit 1; }`