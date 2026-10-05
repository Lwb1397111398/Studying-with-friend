# 评分记录：m2_code_bundle.md · 第 2 轮 · 代码

**总分：84**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 9
- 3 方法可行: 9
- 4 验证闭环: 9
- 5 风险预案: 9
- 6 成本预算: 8
- 7 资产复用: 8
- 8 无占位符: 7
- 9 纪律合规: 9
- 10 可执行性: 6

## 问题清单

- [10 可执行性] §6 验证命令使用 --pages-dir 参数，但 replica_ocr.py argparse 定义为 --pngs；直接执行会报 unrecognized arguments 错误，零上下文工程师无法运行对账命令 → 建议：将 §6 命令中 --pages-dir 统一改为 --pngs，或在 argparse 中添加 --pages-dir 作为 --pngs 的别名
- [10 可执行性] test_replica_rules.py 中 FIXTURE 指向 android/app/src/test/resources/ocr/t3_sample8.json，但 §5 资产复用表标注为 .e2e/p6c/fixtures/；两处路径不一致，按文档定位文件会导致 test_fixture_replay_243_lines 找不到文件而失败 → 建议：统一 fixture 路径：若文件实际在 .e2e/p6c/fixtures/，改 FIXTURE 为 Path(__file__).parent / 'fixtures' / 't3_sample8.json'；若在 Android 测试目录，更新 §5 表格路径
- [8 无占位符] 文件清单描述 post_rules.py 为「11 条纯函数」，实际仅含 2 个函数（normalize_punct_line、strip_citation_line）；11 实为 test_replica_rules.py 的测试用例数，表述与实际不符，误导读者对模块复杂度产生错误预期 → 建议：将描述改为「规则 a/b 后处理（2 函数，11 测试用例）」
- [3 方法可行] det_post_axis_aligned 第二参数 det_limit_cfg 在函数体内从未引用，调用处传入 det_limit 被静默忽略；属死参数，可能误导后续维护者误以为该参数生效 → 建议：删除该参数，函数签名改为 det_post_axis_aligned(prob: np.ndarray) -> list，调用处同步简化
- [6 成本预算] §2 提供各场景分项耗时（1.0s/页、1min、7min+6min、35min）但缺少全流程汇总上限（最坏情况总耗时、输出磁盘空间估算），评审无法一次性评估整体资源消耗 → 建议：在 §2 末尾增加「全流程最坏情况总耗时 ≈XX min、输出文件 ~XX MB」汇总行，涵盖消融跑批+对账 A/B+单测