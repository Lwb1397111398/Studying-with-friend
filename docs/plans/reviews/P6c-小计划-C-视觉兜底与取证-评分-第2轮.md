# 评分记录：P6c-小计划-C-视觉兜底与取证.md · 第 2 轮 · 计划案

**总分：63**（≥90 过）

- 1 目标与范围: 9
- 2 证据出处: 8
- 3 方法可行: 4
- 4 验证闭环: 8
- 5 风险预案: 7
- 6 成本预算: 6
- 7 资产复用: 8
- 8 无占位符: 4
- 9 纪律合规: 8
- 10 可执行性: 5

## 问题清单

- [3 方法可行] C1 脚本「开关打开」段仅有两条 re.search 匹配 sw 变量，之后直接 print 收尾，无 sh「input tap」点击操作；视觉兜底开关实际未被启用，链路可能未开就进 C3a 导致入队为 0 → 建议：补全开关启用逻辑：用 re 抓开关节点自身 bounds 中心后 sh(「shell','input','tap',...)，并在 dump 后断言 checkable 字段从 false 翻转为 true；失败降级用 dump 找到的开关节点坐标硬点击
- [8 无占位符] C4-2 引用 .e2e/p6c/diff_vision_ocr.py「python difflib.SequenceMatcher 对 3 页算相似度 ratio 并落 JSON」但只给一句话描述，无脚本全文；J6 判据依赖此脚本产出，实际不可运行 → 建议：补 diff_vision_ocr.py 全文：DB pull 后取 vision_queue result 与 ocr_draft 两表 join，对指定 3 个 pageNo 用 difflib.SequenceMatcher.ratio() 计算，输出 .e2e/p6c/vision_ocr_diff.json 含 {pageNo, vision_chars, ocr_chars, ratio}，附期望示例
- [3 方法可行] C3a 步骤 3 与 5（SAF 选书、两段式确认导入）只有文字描述「dump 找 sample_book_scanned 点击」「dump 动态取坐标」+ B 报告实测坐标降级（784,1125 / 540,1148 / 540,2170），未给可执行 python 脚本；零上下文工程师需自行拼凑 → 建议：补 .e2e/p6c/saf_import.py：接收 pdf 路径与两个按钮 needle，循环 uiautomator dump 匹配 SAF 文件列表 → 点 sample_book_scanned → 等 Pass2 完成后再 dump 匹配「下一步：确认目录」「完成导入」并 tap；带 B 报告坐标做二级降级
- [8 无占位符] C1 脚本定义 km=re.search(r'视觉 API Key[^<]*', x) 但之后无 assert 用 km，仅断言 len(KEY)>8（KEY 是本地变量长度而非 UI 回显长度）；注释「Key 框只断言长度（不打印内容）」与实现不符——UI 未填入的静默失败不会被拦住 → 建议：用 dump 抓视觉 API Key 输入框节点后读其 text 长度断言 >=len(KEY) 且非空，或降级为断言 vision_key_enc 落库后非明文（已有 DB 侧断言），二者择一即可，但必须真正断言 UI 侧写入
- [8 无占位符] J1 验证命令 score_review.py <质检包> <轮次> 中的 <质检包>、<轮次> 是尖括号占位符；质检包路径未在正文给出；无法零上下文执行 → 建议：给出具体质检包路径（如 .e2e/p6c/quality_packet_c2_v1.md）与轮次编号规则（1 起始，追加 _v2/_v3），把命令替换为可直接粘贴的完整形式
- [3 方法可行] C3b-2 meminfo 轮询用 ( while true; do ... sleep 30; done ) & 起后台循环，「OCR 完成后向 CSV 写 stop 标记并杀后台循环」但未记录后台 PID，无 kill 命令，也无 pgrep 匹配；轮询可能永久运行 → 建议：改造为 PID 捕获：MEM_PID=$( ( while ...; done ) & echo $! )；OCR 完成后 kill $MEM_PID 并追加「stop」行；同时给 pgrep -f meminfo_timeline.csv 兜底
- [5 风险预案] C1 兜底方案「key 由脚本粘贴板注入 adb shell am broadcast 或临时脚本打印至受控文件后删除——仍零会话明文」仅描述未给命令；且「打印至受控文件」与「零会话明文」语义冲突（文件若泄露则明文落盘） → 建议：给具体降级命令：adb shell input text 走 shell 单引号包裹 + IME 直注，或写 C:/Agent/AImanager/temp/.vision_key（受控目录，chmod 700），用毕立刻 del；明确「受控文件」定义与清理时点
- [6 成本预算] LLM 调用「计划案评分 N 轮+C2 质检 M 轮」以变量占位，无具体预估次数；仅 phase1 单轮 ≈30K tokens 外推，缺总调用次数与 token 上限预估 → 建议：按历史质检循环（Phase 1 用 2 轮达到 ≥90）保守预估：质检 2-3 轮 + 计划案自评分 1 轮 ≈ 3-4 次调用，总 tokens ≈ 90-120K；商汤 vision 侧 token 换算标注 ±50% 已够，LLM 侧同样需给数值区间而非变量