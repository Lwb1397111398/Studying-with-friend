# 评分记录：m2_code_bundle.md · 第 6 轮 · 代码

**总分：88**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 7
- 3 方法可行: 9
- 4 验证闭环: 9
- 5 风险预案: 9
- 6 成本预算: 9
- 7 资产复用: 9
- 8 无占位符: 8
- 9 纪律合规: 9
- 10 可执行性: 9

## 问题清单

- [7 资产复用] 文档引用 build_eval_pages.py（§6 注释「eval_pages.json 内容摘要……build_eval_pages.py 产物」）但送评包未包含该脚本，零上下文工程师无法生成 eval_pages.json，run_ablation.py 的入口输入缺失 → 建议：将 build_eval_pages.py 加入送评包或直接在文档中内联其生成逻辑/输出 JSON 全文（17 项 dict 已给出页号全集与字段，可补完整 JSON 原文）
- [3 方法可行] run_ablation.py 中 PY = ".e2e/p6a/venv/Scripts/python.exe" 硬编码 Windows 路径（Scripts/ + .exe），跨平台不可运行；文档 venv 安装命令 Scripts/pip 同样仅 Windows 有效 → 建议：PY 改为跨平台写法：Path(__file__).parents[2] / ".e2e/p6a/venv" / ("Scripts" if os.name=="nt" else "bin") / ("python.exe" if os.name=="nt" else "python")；文档命令同步给出 POSIX 替代路径 bin/pip
- [8 无占位符] test_replica_rules.py 中 FIXTURE = Path(__file__).parents[2] / "android" / ...，parents[2] 从 .e2e/p6c/ 上溯仅到 .e2e/，而 android/ 在仓库根（再上一级），FIXTURE 路径必然指向不存在的 .e2e/android/，fixture 回放测试将 FileNotFoundError → 建议：改为 Path(__file__).parents[3]（.e2e/p6c→.e2e→.→repo_root→android）或 parents[4]（若实际 .e2e 在仓库根下）；以 android/app/src/test/resources/ocr/t3_sample8.json 的真实相对层级校准
- [2 证据出处] 大量关键结论引用送评包外文档且无法独立验证：m3_ablation.md（13 组消融实测数字、G12 误判更正节）、m2_match_report.md（§三 R1 预案证据链）、eval_pages.json（消融输入）均未随包提供 → 建议：将 m3_ablation.md 与 m2_match_report.md §三 的关键表格/数字摘录内联至送评包 §4/§6 对应位置，或附完整文件内容，使评审可逐行核对 Δmedian/过闸 数字与 MEASURED 标注一致性
- [4 验证闭环] §6 验证命令预期输出「22 passed」与代码实际不符：test_replica_units.py 10 条 + test_replica_rules.py 11 条 = 21 条，文档与代码存在 1 条计数偏差，跑批时 22 断言不成立会误判 → 建议：修正文档预期为「21 passed」，或在 test_replica_units.py 补 1 条边界用例（如 det_post_unclip 单框 unclip 放大比验证）使总数匹配 22
- [5 风险预案] 风险登记册「动态宽批内存膨胀」行末标注「手机端需 Phase 1 实测内存」，Phase 1 无触发条件、无时间线、无预案归属，风险敞口未闭环 → 建议：补充 Phase 1 触发条件（如 M3 达标后启动）、预估机时、负责人，或显式声明「Phase 1 超本包范围，风险登记至下一里程碑任务书」并给出追踪编号
- [6 成本预算] 成本预算表「全流程最坏情况 110 min」与消融节「13 组 + G11/G12 延伸 2 组 ≈40 min」存在口径不一致：40 min 仅含 13 组消融，110 min 含 G11 全量 401 页 dyn（≈61 min），但 G12 复跑（unclip+dyn 17 页）耗时未纳入最坏情况汇总 → 建议：在 110 min 汇总中补列 G12 复跑 ≈17×9.1s≈2.6 min，或注明「G12 延伸组耗时已含在消融 40 min 内（同参数量级）」以消除口径歧义