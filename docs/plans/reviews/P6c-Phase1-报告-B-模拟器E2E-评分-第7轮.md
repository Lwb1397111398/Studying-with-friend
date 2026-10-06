# 评分记录：P6c-Phase1-报告-B-模拟器E2E.md · 第 7 轮 · 报告

**总分：88**（≥90 过）

- 1 目标与范围: 10
- 2 证据出处: 10
- 3 方法可行: 8
- 4 验证闭环: 9
- 5 风险预案: 8
- 6 成本预算: 9
- 7 资产复用: 9
- 8 无占位符: 7
- 9 纪律合规: 10
- 10 可执行性: 8

## 问题清单

- [8 无占位符] §4-③ 中「双兜底 14 页 [8,11-17,21,25,71,266,311,393] 内待定」的 1 页页码未闭环，DIAGRAM 22 页列表仍缺 1 页归属；虽已归因并建议下轮补日志闭环，但当前轮存在已知缺口 → 建议：在本轮报告中明确标注该页为「待下轮 fallback 日志补全」并给出预期闭环命令（如附录 B 所述在 fallbacks.add 处补一行 Log.w），使缺口状态可追踪而非隐含待定
- [10 可执行性] 完整 E2E 十步链（装 APK→推书→开开关→选文件→导入→OCR→两段确认→书架）仅以「见计划案 §2 步骤 1-9」交叉引用，未内联；最小启动链虽已内联但 SAF 文件选择步骤（784,1125）标注为系统选择器偶发 dump 不到时需手动双击，该手动步骤对零上下文工程师无自动化替代 → 建议：将计划案 §2 步骤 1-9 的关键命令与期望输出摘要内联至本报告（至少覆盖 SAF 选择失败时的完整 fallback 路径，例如用 intent 直接传入 URI：adb shell am start -a android.intent.action.VIEW -t application/pdf -d file:///storage/emulated/0/Download/shpc.pdf），消除对外部文档的硬依赖
- [3 方法可行] §6 最小启动链中 UI 坐标（954,1938 / 272,790 / 784,1125 / 970,677 / 540,1148 / 540,2170）全部硬编码于 1080×2400 分辨率；虽注明换分辨率须 dump 重取，但 SAF 选择步骤 fallback 为「手动双击」——这不是可执行命令而是人工操作，削弱自动化闭环 → 建议：对 SAF 选择步骤提供纯 CLI fallback：adb shell am start -a android.intent.action.VIEW -t application/pdf -d file:///storage/emulated/0/Download/shpc.pdf 或 uiautomator 脚本化点击（uiautomator2.py），确保零上下文工程师无需手动触屏
- [5 风险预案] 模拟器死亡恢复链完整，但预防仅靠「跑前设从不睡眠」一条命令；无死亡自动检测→自动恢复的闭环（当前依赖人工轮询 adb devices ≤5min）；若工程师忘设不睡眠或中途锁屏，恢复全靠事后手工 → 建议：在计划案或本报告中补充一个 5 行 Python/bash 守护脚本示例：每 3min adb devices 检查，死亡则触发 powercfg 设置 + emulator -avd 重启 + 本报告 §5.2 恢复链自动执行，降低对人值守的依赖
- [8 无占位符] 附录 A hybrid 提速预案中「端上低配置耗时无实测数据」「t1<10s 才启用二遍」为条件门但未给出 t1 的测量命令与判据脚本；虽声明待拍板后实施，但预案参数表中 det_limit/dpi 建议值无对应的自动化验证步骤 → 建议：在附录 A 中补充一条可运行命令链示例（如 adb shell 修改 OcrImportRunner.kt 编译常量→重编译→10 页采样→提取 inferMs 均值），使预案从「参数表」升级为「可执行验证流程」，便于拍板后直接执行
- [4 验证闭环] D1-② UI 断言依赖 android_ui_describe 或 uiautomator dump grep，但 dump 后 grep "7 章" 期望 ≥1 不够严谨——若书架有多本含「7 章」的书会产生假阳性；同理 D4-② 断言 mobile_fallback_pages/461<0.15 中 461 为硬编码字面量，换书时需手动修改 → 建议：UI 断言改为 grep -c 'shpc.*7 章'（同时匹配书名与章数）避免假阳性；D4 断言将 461 提取为变量 TOTAL_PAGES=461 并在脚本顶部声明，避免魔数
- [3 方法可行] §5.3 两段式导入中 sleep 2 和 sleep 6 标注为「实测足够，非判据」，严谨判据为 DB 行出现；但轮询间隔与上限仅给了 OCR 的 60s/130min，两段确认的 DB 轮询（等 books 表出现 title=shpc 行）未给具体命令与超时值 → 建议：补充两段确认后的 DB 轮询命令示例：for i in $(seq 1 30); do python -c "import sqlite3;..." 2>/dev/null && break; sleep 2; done，超时 60s 未出现则报障
- [5 风险预案] D2 触发线上调选项 2（5s→15s）声明「仅改触发线常数，回归验证范围=D2 判定一条」，但未给出该常数的文件路径与行号、修改后回归验证的具体命令（如跑单元测试 grep 或编译后断言），「回归验证范围=D2 判定一条」不够可执行 → 建议：补充：常数位于 OcrImportRunner.kt:XXX（给行号），修改后验证命令为 python -m pytest tests/test_d2_trigger.py -k test_trigger_line -v（给具体测试名），期望 PASS 且无其他测试失败