# 评分记录：P6c-Phase1-报告-B-模拟器E2E.md · 第 10 轮 · 报告

**总分：80**（≥90 过）

- 1 目标与范围: 8
- 2 证据出处: 9
- 3 方法可行: 8
- 4 验证闭环: 9
- 5 风险预案: 7
- 6 成本预算: 6
- 7 资产复用: 9
- 8 无占位符: 7
- 9 纪律合规: 8
- 10 可执行性: 9

## 问题清单

- [5 风险预案] hybrid 提速最大风险项缺触发条件与实测数据：附录 A 明示端上低配置耗时无实测、t1 待测、收益边界 UNMEASURED；17 页消融仅 PC 侧。报告把这条风险列为最大风险，但预案本身没跑过，老板无法据「实测」拍板。 → 建议：在报请拍板前，追加一次 10 页端上实测：改 det_limit 常量走小计划评分流程 → 首章 10 页 inferMs 均值=t1（含方差）→ 用 t1 与 t2 直接外推 461 页总时长；t1<10s 才立项，否则自动回退到上调 5s 触发线。把这条触发式预案作为拍板前置条件写进 §2 选项 3。
- [8 无占位符] 22 页 DIAGRAM 页码清单落盘状态 21/22，第 22 页仍 UNMEASURED 待下轮 fallback 页码日志；附录 A 中 det_limit 常量写「1280→拟测值」为显式占位符；结论置信度自评为「中」且缺 bbox h/w 一级数据。 → 建议：本轮内以最小成本闭环：在生产代码 OcrImportRunner.kt:169 fallbacks.add 附近补一行页码+bboxHw 日志（生产代码改动须走小计划评分流程），下次导入即可 22/22 落盘；附录 A 的「拟测值」在拍板前改为实测枚举值（如 960/1280/1536 各测一次），不给未量化占位符。
- [6 成本预算] LLM 调用成本估算为 ≈59K tokens、误差 ±20%；score_review.py 未记录 usage 返回值，报告承认「以上为估算非实测」。PC 对照机时下轮两条路径（3min vs 60min）也是估算。 → 建议：score_review.py 补 usage 日志（prompt_tokens+completion_tokens append 到 JSONL），下轮用实测值替换「≈」；PC 对照在下轮立项时先跑 21 页补页并计时取 s/页，再决定是否外推到 461。
- [2 证据出处] D3 过程轮询逐时点值仅存执行会话记录，未结构化落盘；平台期 1.73-1.74GB 轨迹无法从 b_evidence 文件复现。报告自承下轮改 meminfo_timeline.csv 后可复现，本轮 D3 通过主要依赖完成态快照的静态 delta。 → 建议：下轮导入期间以 30s 间隔写 meminfo_timeline.csv（列：host_epoch, app_epoch, pid, TOTAL_PSS, NativeHeap, SHeap, GLDevM），事后可 plot 出平台期形态并量化 68min 观察窗的实际起止时点，作为 D3 长期证据链。
- [9 纪律合规] 三查声明写的是命令与判据（流程约定），但报告未附三条 check 命令的实际输出、退出码或时间戳；读者无法验证三查已实际跑过。设备白名单校验、adb unroot 收尾也仅列流程未附收尾时刻记录。 → 建议：在 §9 或 commit 前置块附三查实际输出：`git status --porcelain` 空行、check_modules.py 退出码、check_session_close.py 退出码、执行时间戳；三查全绿为硬闸门，报告须给证据不是规则。
- [10 可执行性] SAF 文件选择是唯一不可自动化步骤，报告已诚实声明 SAF intent 不可 CLI 伪造；若坐标 dump 失效，E2E 链断在该步；本轮坐标可用性依赖 1080×2400 实测且需 uiautomator dump 兜底，未给「dump 完全失效」时的备用方案。 → 建议：追加一条：若需零人工闭环，改代码支持调试通道固定路径导入（`adb shell content` 或直接 FileProvider 路径 + debug build 特判），走小计划评分流程；否则把「SAF 需人工双击约 10s」作为 E2E 自动化率固有限制写进 §10，并给下一步 uiautomator2/adb shell input keyevent 备用方案。