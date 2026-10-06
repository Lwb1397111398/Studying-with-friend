# 评分记录：P6c-小计划-C-视觉兜底与取证.md · 第 3 轮 · 计划案

**总分：90**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 10
- 3 方法可行: 8
- 4 验证闭环: 8
- 5 风险预案: 9
- 6 成本预算: 9
- 7 资产复用: 10
- 8 无占位符: 9
- 9 纪律合规: 10
- 10 可执行性: 8

## 问题清单

- [3 方法可行] C3a 第4步 OCR 轮询循环（30 次×60s）无超时失败处理——若 Pass2 done 始终未出现，循环静默结束后脚本直接进第5步 confirm，失败信息指向「确认按钮未现」而非「OCR 超时」，误导排障。 → 建议：循环后追加超时判定：if ! grep -q "Pass2 done" c3a_logcat.txt; then echo "ERROR: OCR 轮询超时（30min）"; exit 1; fi
- [4 验证闭环] C3a 重建验证要求「重建前后各查一次对比」但计划仅在第6步给出 after 查询，未显式要求在 C3a 开始时 pull before 快照（C3b 第1步有此模式但 C3a 缺）。零上下文工程师照做会缺少 before 基线。 → 建议：C3a 开头追加：adb exec-out run-as com.studyfriend.app cat databases/study_friend.db > c3a_before.db，重建断言处明确给出 before/after 对比 SQL。
- [5 风险预案] 模拟器死亡恢复链引用 B 报告 §5.2 但未内联，零上下文工程师无法仅凭本文档执行恢复。风险表第3行预案依赖外部文档，闭环不完整。 → 建议：将 §5.2 恢复链核心步骤（至少 3-5 条关键命令）摘要内联至风险预案行或本计划附录。
- [3 方法可行] diff_vision_ocr.py 的 FILEPAT 假设为 p{pageNo}.txt 但计划承认实际命名可能不同；若不匹配，脚本以空字符串静默计算 chars_ratio，产生无意义数据且不报错。 → 建议：将 FILEPAT 改为脚本第4个命令行参数，脚本开头先 ls 缓存目录打印实际文件名，与 FILEPAT 不匹配则 exit 1 并提示。
- [5 风险预案] shpc 重导中途失败后的部分条目清理与重试机制未定义。确认闸门仅防旧书覆盖，未覆盖新书半成品的处理。 → 建议：C3b 第1步确认闸门后追加：导入失败时执行 DELETE FROM books WHERE id=:newBookId（连带 chapters/paragraphs/vision_queue）再重跑；或采用事务包裹整个导入流程。
- [10 可执行性] 计划假设 adb 与模拟器已就绪但无前置验证。若 adb server 未启动或模拟器未连接，所有 subprocess.run 调用静默失败（未检查 returncode）。 → 建议：C1 脚本开头追加预检：subprocess.run([ADB, "devices"], ...) 检查输出含 emulator-5554 且状态为 device，否则 exit 1。
- [4 验证闭环] J6 目视判据「3 页中 ≥2 页通读无乱码块」为主观标准，无结构化记录模板，不同评审者可能得出不同结论。 → 建议：提供目视记录模板（JSON 或 Markdown 表），字段含 pageNo、vision_chars、ocr_chars、乱码块有无（Y/N）、段落连贯（Y/N）、备注；3 页记录汇总后才做 ≥2 页判定。
- [10 可执行性] C5 收尾步骤中工具路径使用 Windows 绝对路径（C:/Agent/AImanager/tools/），未说明路径可变；非该环境的工程师无法直接执行。 → 建议：标注路径为「计划编写环境路径，执行时按实际仓库根目录替换」，或改用相对于仓库根目录的路径。