# 评分记录：m2_code_bundle.md · 第 5 轮 · 代码

**总分：96**（≥90 过）

- 1 目标与范围: 10
- 2 证据出处: 10
- 3 方法可行: 9
- 4 验证闭环: 10
- 5 风险预案: 10
- 6 成本预算: 10
- 7 资产复用: 10
- 8 无占位符: 9
- 9 纪律合规: 10
- 10 可执行性: 8

## 问题清单

- [3 方法可行 / 8 无占位符 / 10 可执行性] run_ablation.py 依赖 .e2e/p6c/eval_pages.json（含 pages 列表与 n_band 字段），但该文件未包含在送评包中。零上下文工程师无法直接运行消融跑批。 → 建议：将 eval_pages.json 加入送评包（或提供生成脚本），并在验证命令节补充其内容摘要（页号列表、n_band 值）。
- [3 方法可行 / 10 可执行性] G11（rec-width dyn，median 0.5697→0.9112，过闸 3→15/17）是根因实证的最关键实验，但未纳入 run_ablation.py 的 OVERRIDES 矩阵，仅在文档中以手动命令方式执行。消融报告 m3_ablation.md 缺少该组数据，无法在单一汇总表内对比所有因子。 → 建议：在 run_ablation.py 的 OVERRIDES 中增加 G11_S5_recdyn: dict(rec_width="dyn")（需 BASE 字典增加 rec_width="off" 键），使消融报告包含最影响因子；组合组选择逻辑自动覆盖。
- [10 可执行性] run_ablation.py 文件 docstring 写「固定 20 页」，但文档第 1 节写「固定 17 页评测集」，G11/G12 手动命令也列出 17 个页号。页数以 eval_pages.json 为准但包内缺失该文件，导致实际页数不确定。 → 建议：统一为实际页数（17 或 20），修正 docstring；在包内提供 eval_pages.json 或明确标注页号列表为唯一来源。
- [4 验证闭环 / 10 可执行性] replica_ocr.py 无输入校验：--pdf/--pngs 均为 None 时 fitz.open(None) 抛异常无友好提示；pages 为空列表时静默完成无警告；ONNX 文件不存在时 InferenceSession 抛原始 RuntimeError。 → 建议：在 main() 入口增加互斥检查（ap.add_mutually_exclusive_group(required=True)）与文件存在性校验（os.path.isfile / os.path.isdir），输出明确错误信息。
- [3 方法可行] preproc gray_unsharp 分支中 cv2.cvtColor(img, cv2.COLOR_RGB2GRAY) 被调用两次（第 3 行和第 4 行各一次），第 4 行应直接复用第 2 行的 img_gray 变量而非重复转换。虽无功能错误但浪费一次 CPU 转换。 → 建议：提取 img_gray = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY) 一次，后续 addWeighted 使用 img_gray。
- [5 风险预案] 风险登记册未覆盖 run_ablation.py 的 subprocess.run(check=True) 无超时机制：若 replica_ocr.py 某组挂死（如 ONNX 推理死循环或内存溢出），整个跑批进程永久阻塞，无自动中断与恢复。 → 建议：subprocess.run 增加 timeout 参数（如 timeout=300），捕获 TimeoutExpired 异常记 SKIPPED 并继续下一组。
- [10 可执行性] G05（unclip 非 dyn 路径）在消融矩阵中，但该路径（crop_and_rec_pre rec_aspect=True + 逐框推理）未在任何验证命令中单独执行过。若该分支存在隐蔽 bug，消融报告将产出错误数字且无独立验证手段。 → 建议：在验证命令节增加 G05 单独运行命令与期望输出（行数、median、gate），或将 G05 纳入 test_replica_units.py 的单元覆盖。
- [2 证据出处] post_rules.py 注释标注源追溯行号（kt:35, kt:38-40, kt:52-59, kt:66-78），但正则表达式 RE_FN_HEAD、RE_BR_HEAD 使用 f-string 嵌套 rf 前缀，生成的实际正则与 Kotlin 源可能因 Unicode 转义差异产生细微区别（如 \s 展开范围），未提供交叉验证脚本。 → 建议：增加一条单元测试：用 Kotlin 实际输出（非 fixture）与 Python 输出做逐字符对比，或提供 Kotlin-JVM 与 Python-re 的正则等价性验证脚本。