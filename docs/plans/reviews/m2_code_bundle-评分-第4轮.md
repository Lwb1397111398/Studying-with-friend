# 评分记录：m2_code_bundle.md · 第 4 轮 · 代码

**总分：83**（≥90 过）

- 1 目标与范围: 10
- 2 证据出处: 9
- 3 方法可行: 8
- 4 验证闭环: 8
- 5 风险预案: 9
- 6 成本预算: 9
- 7 资产复用: 8
- 8 无占位符: 7
- 9 纪律合规: 8
- 10 可执行性: 7

## 问题清单

- [3 方法可行 / 8 无占位符 / 10 可执行性] run_ablation.py 在第 6 节验证命令中被引用（13 组+G11/G12 一键跑批）但未包含在送评 5 文件中，零上下文工程师无法执行消融批量 → 建议：将 run_ablation.py 完整代码纳入送评包，或将消融逻辑内联为逐组调用 replica_ocr.py+match_report.py 的 shell 循环写入文档
- [10 可执行性] unclip+dyn 代码路径存在坐标空间错误：det_post_unclip 返回的框在 im（缩放后）坐标空间，但 rec_pre_dyn 以 img（原图）+sx=1.0/sy=1.0 调用（replica_ocr.py:unclip+dyn 分支），裁剪区域偏移；虽文档声明 G12 路线已排除但错误代码仍保留 → 建议：将 rec_pre_dyn(img,...) 改为 rec_pre_dyn(im,...)，或在函数调用处计算并传递正确缩放因子 sx=img.shape[1]/im.shape[1], sy=img.shape[0]/im.shape[0]
- [4 验证闭环] 对账 B（401 页 PDF 渲染）实测 210/401 不过（Δ≤0.02+sim≥0.85），文档声明主因为 PC/Android 渲染器像素差异并以对账 A 承担成立性，但送评包内无 Δ>0.02 页的渲染尺寸差异量化数据或像素对比脚本，R1 归因链缺实证支撑 → 建议：补充对账 B 中不通过页的渲染尺寸差统计表（PC vs Android 像素级对比），或提供渲染差异量化脚本实证 R1 归因
- [10 可执行性] 文档仅声明 .e2e/p6a/venv/ 为「既有 venv，Python 3.13」，未提供 venv 创建或依赖安装命令；零上下文工程师无法从零搭建运行环境 → 建议：补充 venv 初始化命令（python -m venv .e2e/p6a/venv）与 pip install 命令序列，或提供 requirements.txt 路径及内容
- [9 纪律合规] 会话收尾闸门 4 项检查均为「作者自查」，无交叉验证或第二人复核机制；模块总览更新触发时机为 M6 收口会话但无预填充模板 → 建议：为闸门④增加「另一位评审者确认 .e2e/ 已在 .gitignore」的交叉检查项；补充模块总览更新的预填充段落模板与 diff 示例
- [10 可执行性] match_report.py 的 load_draft 函数对无制表符的草稿行（rpartition('\t') 返回 ('','','')）无异常处理，float('') 抛出 ValueError 导致全流程崩溃 → 建议：在 rpartition 后增加 if not conf: continue 或 try/except ValueError 跳过格式异常行，并在报告中记录跳过行数
- [2 证据出处] post_rules.py 中 6 条正则表达式（RE_CIP_LINE/RE_FN_HEAD/RE_IN/RE_BR_HEAD/RE_BR_IN 等）仅追溯 OcrTextPostProcessor.kt:38-78 范围，未逐条对应 Kotlin 行号，无法验证逐位一致性 → 建议：在 post_rules.py 注释中为每条正则标注对应 Kotlin 行号（如 RE_CIP_LINE←kt:42, RE_FN_HEAD←kt:48, RE_IN←kt:52 等）
- [3 方法可行] 消融参数预期效果表（第 4 节）中 preproc=gray_unsharp 预期「+0.01~0.02」、rec-aspect=on 预期「0.0000」等使用范围值或单值，非可验证的通过/不过判据，评审无法据此判定消融是否达成预期 → 建议：将预期效果改为可验证区间（如 preproc=gray_unsharp：Δmedian∈[+0.01,+0.02] 则预期达成），或显式标注为「定性预判，不用于通过判定」