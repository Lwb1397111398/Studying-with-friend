# 评分记录：P6c-Phase1-报告-B-模拟器E2E.md · 第 11 轮 · 报告

**总分：85**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 9
- 3 方法可行: 8
- 4 验证闭环: 9
- 5 风险预案: 8
- 6 成本预算: 7
- 7 资产复用: 9
- 8 无占位符: 9
- 9 纪律合规: 9
- 10 可执行性: 8

## 问题清单

- [6 成本预算] LLM token 消耗全部为估算（≈59K，±20% 误差），score_review.py 未落 usage 返回值，无法事后审计；下轮补 usage 日志属遗留项而非闭环 → 建议：在 score_review.py 调用返回后追加 prompt_tokens/completion_tokens 至 JSONL 文件（一行一次调用），使每轮成本为 MEASURED 而非估算；本轮可先记录已估值的精确计算式（已给）并标注 ±20% 为上限
- [3 方法可行] §3 内存平台期描述用「长时间稳定（68min 观察窗）」中「长时间」为模糊词；§1「约 95.4 分钟」等时间估算缺置信区间 → 建议：将「长时间稳定」改为具体判据（如「68min 内 PSS 波动 <5%」并给出实测极差）；时间估算补一个 95% 区间（如 inferMs 5697886/461=12.36s 页均，给出 min/max/std）
- [5 风险预案] 模拟器第一次死亡根因标 UNMEASURED（宿主夜间休眠），事后无法取证；预防措施（powercfg standby-timeout-ac 0）虽给了命令，但死亡发生时已无 logcat 可回溯 → 建议：在预防措施段增加一条：跑前执行 `powercfg /change standby-timeout-ac 0` 后用 `powercfg /query SCHEME_CURRENT SUB_SLEEP STANDBYIDLE` 截图或落盘验证（期望 0x00000000），使预防措施本身可验证；并在 adb 轮询脚本中增加 `logcat -b crash -d` 抓取宿主崩溃日志（若可用）
- [10 可执行性] SAF 文件选择步骤为唯一人工断点（~10s），报告已解释 SAF 无法 CLI 自动化，但零上下文工程师若遇 SAF 列表 UI 变化（系统版本升级、主题变化）则完全阻塞且无 dump 替代 → 建议：在 SAF 步骤旁补一条降级链：若 SAF 列表项 dump 不到 text，用 keyevent KEYCODE_DPAD_DOWN 导航到 Download 目录（shpc.pdf 文件名固定、字母排序靠前）+ KEYCODE_ENTER 选中；并标注该降级链的验证命令（dump 后 grep Download 目录条目）
- [2 证据出处] 过程内存轮询逐时点值仅存执行会话记录，未落盘为结构化文件；平台期 1.73-1.74GB 轨迹无法从 b_evidence/ 目录复现，违反「关键数值标注出处」原则 → 建议：将执行会话中的轮询值誊抄为 meminfo_timeline.csv（列：timestamp_ms, pss_kb, note），追加至 b_evidence/ 清单；下轮长跑用 `while true; do adb shell dumpsys meminfo | grep TOTAL; sleep 30; done` 管道直接落 CSV
- [4 验证闭环] D4-③ 锚定值 47 虽标为「校准锚非判据」，但缺乏回归断言：下轮若兜底页数突增至 80+ 页，当前断言（仅检查 pearson 和 fallback_rate）无法自动拦截 → 建议：在 §6 断言脚本中增加一个警告阈值检查：`if s['mobile_fallback_pages'] > 60: print('WARN: fallback count jumped, review DIAGRAM/LOW_CONF breakdown')`，不阻断但强制人工复核；60 取 47×1.27 余量（≈2σ 估计）
- [8 无占位符] 下轮待办项（fallback 页码日志、bbox h/w 量化、meminfo_timeline.csv、SAF 自动化）均有明确代码位置和命令，但未标注优先级和预期耗时，存在无限延期风险 → 建议：在 §10 遗留表增加一列「预期耗时」和「阻塞条件」：如 fallback 日志补页码=0.5h 无阻塞、meminfo_timeline.csv=0.3h 无阻塞、SAF 自动化=2h 需立项评分、bbox h/w 闭环=随下轮 E2E 零额外成本；使后续会话可按 ROI 排序执行
- [1 目标与范围] out-of-scope 列了 5 项但不含「模拟器死亡恢复后的数据一致性验证」——本轮死亡后强制冷启动、修时钟、force-stop，若 DB 残留脏数据会污染下轮基线 → 建议：在 scope 内追加一项「冷启动恢复后 DB/内存基线复核」：恢复后执行 `adb shell dumpsys meminfo` 确认 PSS<100MB 且 `sqlite3` 查 books 表行数与恢复前一致，将此项纳入 D1 前置检查（当前 §5.2 已隐含但不在目标声明中）