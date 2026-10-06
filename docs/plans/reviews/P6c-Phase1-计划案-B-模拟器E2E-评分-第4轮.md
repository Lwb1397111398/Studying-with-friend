# 评分记录：P6c-Phase1-计划案-B-模拟器E2E.md · 第 4 轮 · 计划案

**总分：85**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 9
- 3 方法可行: 8
- 4 验证闭环: 7
- 5 风险预案: 9
- 6 成本预算: 8
- 7 资产复用: 9
- 8 无占位符: 9
- 9 纪律合规: 9
- 10 可执行性: 8

## 问题清单

- [4 验证闭环] D2 触发线「单页均值>5s」低于 PC dyn 基线（单页中位 7.7s），D2 在 PC 口径上已必然触发，阈值失去对照意义，无法区分「模拟器更慢」与「阈值本身不合理」。 → 建议：将 D2 触发线改为基于 PC dyn 基线的倍数或绝对值（如>PC 中位 7.7s×1.5=11.55s），或在计划中明确说明 5s 线仅对旧 rec 有效、dyn rec 应使用更高阈值，并给出推导依据。
- [4 验证闭环] reconcile_b.py 中 `pc[p]["gate"]` 未做缺失防护——matchB_dyn.json 由 replica_ocr.py 生成，若输出结构不含 gate 字段（仅含 median），运行时 KeyError 直接崩溃，且 plan 未内联该文件结构定义。 → 建议：(1) 在 §1.1 matchB_dyn.json 条目后追加期望 JSON schema（字段名+类型+示例）；(2) reconcile_b.py 改为 `pc[p].get("gate")` 并对缺失字段输出 warn。
- [4 验证闭环] reconcile_b.py 的 page_medians 仅检测 LOW_CONF（median<0.85 或 low>0.20），不检测 DIAGRAM 兜底，与 S7 基线分类（LOW_CONF=396+DIAGRAM=5=401）口径不一致，导致 mobile_fallback_pages 系统性低估约 1.2%。 → 建议：在 reconcile_b.py 中增加 DIAGRAM 检测规则（或注明 DIAGRAM 由模型端判定、草稿无法复现，并在校准说明中写明偏差方向与量级）。
- [4 验证闭环] D4 兜底页数判据 ≤28 页的 ×2 余量系数（14→28）无实测数据支撑，纯拍脑袋，在首测定锚语境下易被后续质疑可追溯性。 → 建议：给出 ×2 的推导逻辑（如引用模拟器渲染差异的已知上下界，或 S7 两轮偏差范围），或改为「首轮实测记录 + 报老板校准」并在判据栏标注为「暂定值，待老板确认」。
- [4 验证闭环] D4 兜底计数存在双数据源——Pass2 日志行 byReason 字段（系统判定）与草稿重算 reconcile_b.json 的 mobile_fallback_pages（脚本判定），两者若不一致无对账机制，报告将出现矛盾数字。 → 建议：在 §2-D4 或步骤 10 增加一致性校验步骤：两值差≤2 页视为一致，差>2 页则立案归因（哪页判定不同、原因），再择一写报告。
- [8 无占位符] reconcile_b.py 中 pc_fallback_pages 使用 `sum(1 for v in pc.values() if v["gate"])` 真值判断兜底——若 PC 侧 gate 字段为字符串（如"LOW_CONF"）则 truthy 成立，若为 null 则 falsy 正确，但若为数值 0/1 或其他类型则逻辑错乱。 → 建议：改为显式比较 `if v.get("gate") in ("LOW_CONF","DIAGRAM")`，或在校准说明中确认 PC 侧 gate 字段的确切类型。
- [3 方法可行] Step 5-6 依赖 UI 元素固定文案（「扫描书本地识别」「下载识别模型」等），未考虑 UI 迭代导致文案变更时步骤阻塞，虽有「若文案检索不到即停下核实」但无回退方案。 → 建议：在 Step 5 增加备选定位策略：若 `android_ui_describe` 找不到预期文案，改用 `android_screenshot` 截图 + 人工判断位置，或提供 contentDescription 关键词匹配作为二级回退。
- [6 成本预算] LLM 调用成本仅给 token 数量（输入≤8k/输出≤2k/合计 2-4 次），未指定模型（Claude 版本）、单价或总费用，无法做财务对账。 → 建议：补充模型名称（如 claude-sonnet-4-20250514）、单价（$X/M input tokens, $Y/M output tokens）和预估总费用（$Z），与项目预算对齐。