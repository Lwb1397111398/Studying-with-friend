# 评分记录：P6c-Phase1-报告-B-模拟器E2E.md · 第 4 轮 · 报告

**总分：87**（≥90 过）

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

- [可执行性] §6 最小启动链标注「十步细节见计划案 §2 步骤 1-9」，报告本身不自包含；零上下文工程师需跨文档执行 → 建议：将计划案 §2 步骤 1-9 中关键 adb 命令（装 APK、推书、开开关、选文件）内联到 §6，使单文档即可跑通全链
- [验证闭环] reconcile_b.py 期望输出 summary 需人工肉眼比对，无自动断言；pearson_r≥0.9、fallback_rate<15% 等判据未嵌入可运行脚本 → 建议：在 §6 末尾追加一行 python -c assert 语句，自动校验 reconcile_b.json 中 pearson_r>=0.9 与 mobile_fallback_pages<=69，失败即 FAIL
- [方法可行] 22 页 DIAGRAM 归因缺少逐页 bbox h/w 数值，仅靠源码逻辑+median 旁证+单页目视三角验证，证据链偏弱 → 建议：对 22 页 DIAGRAM 单独跑竖排框检测（不需全量 OCR，仅 classifyPage 逻辑+bbox dump），输出 bbox 表即可闭环归因
- [无占位符] §2 中「实测单页 12.36s 超过 5s 触发线→触发 hybrid 预案报告义务」同句重复出现两次，属排版遗留 → 建议：删除第二段重复文字，保留含预案引用附录 A 的那条即可
- [验证闭环] D3 内存过程轮询逐时点值未落盘，仅存完成态快照与执行会话记录，无法回溯泄漏增长曲线 → 建议：下轮跑前写循环脚本：每 5min 执行 adb shell dumpsys meminfo 追加到 meminfo_timeline.csv，作为 D3 完整证据
- [可执行性] §5.3 两段式 UI 点击坐标（540/1148、540/2170）依赖 android_ui_describe 输出，但 describe 调用方式与坐标提取未提供可运行命令 → 建议：补充 MCP android_ui_describe 调用示例或等价 adb shell uiautomator dump 命令，并给出从 JSON 树提取 text=「下一步：确认目录」bounds 的 python one-liner
- [风险预案] hybrid 预案附录 A 承认 t1≈12.36s 时可能不划算，但仅留「先跑 10 页低配置试测」为未来动作，本轮 UNMEASURED 空洞未闭合 → 建议：本轮即可跑 10 页低配置（det_limit=1280、dpi=150）试测（<2 分钟），给出 t1 实测均值，消除附录 A 收益边界 UNMEASURED 标记
- [成本预算] LLM 调用「1-3 次/轮」估算未说明与 token 量关系，无法据此控制成本或预判重试开销 → 建议：补充单轮 token 估算（报告约 N 字≈M tokens×重试系数），并声明免费额度消耗百分比，便于下轮预估