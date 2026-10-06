# 评分记录：P6c-Phase1-计划案-B-模拟器E2E.md · 第 2 轮 · 计划案

**总分：84**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 9
- 3 方法可行: 7
- 4 验证闭环: 9
- 5 风险预案: 8
- 6 成本预算: 8
- 7 资产复用: 10
- 8 无占位符: 8
- 9 纪律合规: 9
- 10 可执行性: 7

## 问题清单

- [10 可执行性] 步骤6 UI交互序列依赖App导航知识（FAB位置、菜单路径、SAF选择器路径），零上下文工程师照android_ui_describe输出可能无法定位目标元素 → 建议：补充App UI关键元素截图或更详细的元素描述（如FAB图标文案、菜单层级结构），或在步骤6前加一个UI探索前置步骤
- [3 方法可行] 步骤7「周期抓」未指定轮询间隔，属模糊指令，不同工程师执行频率不一致导致数据不可比 → 建议：改为「每30s轮询一次android_logs，筛选inferMs=/renderMs=/postMs=行」或类似具体间隔
- [3 方法可行] D4方法描述中「m5_cer同目录工具」引用不明，步骤10实际使用analyze_line.py和reconcile_b.py，存在混淆风险 → 建议：删除「m5_cer同目录工具」表述，或明确指向.e2e/analyze_line.py完整路径
- [4 验证闭环] D1判据「导入流程走完」缺少客观完成标志，零上下文工程师无法判断何时算走完 → 建议：指定具体完成信号：如logcat中出现'OcrImportRunner: pipeline_complete'日志行、或UI进度页出现「导入完成」文案
- [5 风险预案] D1崩溃预案「系统性调试（对照PC replica已验证口径，优先查端上特有路径：Bitmap尺寸/模型shape）」过于笼统，未列具体排查步骤顺序 → 建议：补充前3步排查动作：①对比模拟器与PC端logcat差异行 ②检查Bitmap尺寸是否超设备内存限制 ③校验模型加载shape是否与训练时一致
- [10 可执行性] 步骤5「视觉兜底未配置」缺少具体验证命令或UI状态描述，无法客观确认 → 建议：指定验证方式：如adb exec-out run-as com.studyfriend.app sqlite3 <db> 'SELECT count(*) FROM vision_queue' 确认行数为0，或UI上视觉兜底开关OFF状态截图
- [6 成本预算] LLM token估计「6-10k token」范围宽泛且未区分输入/输出token，无法精确预估费用 → 建议：细化为「输入≤8k token + 输出≤2k token」并标注为[推断]
- [8 无占位符] 引用「m2_code_bundle.md §3 风险册」和「P6b-落地报告.md S5」等处未给出完整文件路径 → 建议：统一补充完整路径，如「docs/plans/m2_code_bundle.md §3」、「docs/plans/P6b-落地报告.md §5」