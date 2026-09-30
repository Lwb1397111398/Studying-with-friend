# M4a 计划案 v2：阅读页 + 粗读流水线

> 里程碑定位（总计划 §M4）：M4 拆为两个可验证交付。M4a = 阅读页 UI + 粗读流水线（分块+进度+断点）；M4b = 段落讲解生成+缓存+批量队列+讲解卡片（另立计划）。
> v1 82 分不通过（1 P0 + 6 P1 + 9 P2），v2 已逐条修订：P0 多块预算占位符；P1 force 重标注 / groupId 约定 / Runner 单例互斥 / 缺标异常入块级重试 / 归并失败保留旧值 / 块内段数上限；P2 九条全改。
> 本计划不含：讲解生成/缓存/队列/卡片（M4b）、章末总结包（M5）、全书总览（M6）。

## §0 范围与非目标

**做**：章目录页（书架点书进入）、阅读页（分段显示+标注渲染）、粗读流水线（prompt 加载→分块→逐块 chatJson→解析容错→归并→即时落库→断点续跑/强制重跑）、全局粗读互斥、粗读进度 UI。

**不做**：段落讲解的生成与展示（M4b；本计划只落 aiAction/groupId/why 标注）、章末资产（M5）、复习（M6）。

## §1 现状盘点（已有/需补）

**已有（M1/M2/M3）**：`ParagraphEntity(aiAction/groupId/why)`、`ChapterEntity(gist/keyTermsJson/readState)`；`ParagraphDao.byChapterFlow/byChapter/updateAiAction`、`ChapterDao.updateAiGist/updateReadState`、`ChapterWithAsset` 投影与 `byBookFlow`；`AiClient.chatJson(req, deserializer, onDelta)`；`SettingsRepository.load/decryptKeyOrNull`；room-ktx 依赖在（Room Flow 支持前提）；IMPORT/TOC 二级页隐藏底栏的模式可复用。

**需补**：
- assets/prompts/rough_read.txt、rough_read_merge.txt + PromptLoader
- RoughReadChunks（确定性分块：字符上限 + 段数上限）、PlanParser（容错解析）、RoughReadPlanner（流水线）、RoughReadRunner（全局单例互斥）
- ChapterDao 补 `byIdFlow(chapterId): Flow<ChapterEntity?>`（章级卡随流水线完成即时刷新）；上下章切换复用已有 `byBook`
- ChapterListScreen+VM、ReadScreen+VM、Routes（BOOK/READ）、ShelfScreen 书目行（现组合项名 BookList）加 onOpen

## §2 粗读流水线设计

### 2.1 Prompt（assets/prompts/，首次建立）

**rough_read.txt**（system，中文），含**三处占位符**，调用前由 Planner 填充：
- `{block_pos}`：当前块序号（1 起）；`{block_total}`：总块数；`{unit_budget}`：本块讲解单元预算
- 单块时（block_total=1）措辞按"整章"生成（占位符填充后自然退化：prompt 内写清"若总块数为 1，本清单即整章"）
- 输出 JSON 规格：`{"gist":"本章大意≤120字","key_terms":[{"term":"术语≤30字","plain":"一句大白话≤60字"}],"paragraphs":[{"id":0,"action":"explain|skip|group","group":[0,1],"why":"≤40字理由"}]}`
- 规则：直白叙述/寒暄/过渡段 → skip；难懂且相邻的段 → group（group 数组必须连续 id，2~3 段）；核心概念/推导/易误解段 → explain；**本块讲解单元（explain+group 合计）不超过 {unit_budget} 个，宁少勿多**；id 用输入编号，全部出现且只出现一次；key_terms 3~8 个

**unit_budget 计算**（Planner 内，纯函数可测）：多块 = `clamp(1..4, round(10 × blockChars / chapterChars))`（示例：2:1 字符占比 → round 后 7:3 → clamp 后 4:3）；单块 = 10（整章由 prompt "3~10" 条目约束）。

**rough_read_merge.txt**：输入各块 gist+terms JSON，输出 `{"gist","key_terms"}` 同构；temperature 0.2、maxTokens 800。

user 消息由程序拼：`{"paragraphs":[{"id":0,"text":"……"}]}`（**每段截断 200 字**，kotlinx 负责转义）。

PromptLoader：`object PromptLoader { fun load(context, name): String }` 读 assets 缓存内存。promptVersion 常量 "rough-read-v1"：**本计划标注结果不落库版本号**（ParagraphEntity 无此列，M4b 起 ParaNote/ChapterAsset 实体自带 promptVersion 字段会落库）——此取舍显式声明。

### 2.2 分块（RoughReadChunks）

- 输入按 idx 排序的段落列表；maxBlockChars = 8000、maxBlockParas = 40（**双上限**，先按段数截断再按字符截断，两者取先触发者）
- 段落不跨块；单段超 8000 字：该段**独立成块、不丢弃**；AI 输入仍按 200 字/段截断（不存在"整体喂全文"路径）
- 确定性：同输入 → 同划分（断点续跑前提），单测锁定
- 输出 `List<RoughReadBlock>`（paragraphs 引用不复制文本，省内存）

### 2.3 解析容错（PlanParser）

kotlinx serializer `RoughReadPlan`，`Json { ignoreUnknownKeys = true; isLenient = true }`（宽松解析：id 出成字符串数字也能过）。
校验（**先归一化再校验**：action `trim().lowercase()`；id 宽松转 Int）：
- id 必须是本块段落 idx 之一，否则丢弃该条
- action 非 explain/skip/group → 丢弃
- group：全部为本块 idx 且**连续**且**长度 2~3**，否则整条降级 explain（只标 id 自己）；单段/空 group → explain
- 重复 id：后到生效
- 缺标段保持 NONE（不冒充 AI 决定）；块缺标率 >30% → 抛 PlannerException，**计入块失败**（走 2.6 块级重试同一路径）
- gist >200 字截断；key_terms >10 截断；term ≤30 字、plain ≤60 字截断

**groupId 约定（写库定死）**：`groupId = 组首段 idx`（章内唯一、确定、可从标注重建，M4b 按 groupId 枚举讲解单元以此为准）。

### 2.4 归并（多块 → 章节级 gist/key_terms）

- 1 块：直接用该块 gist/key_terms，无归并调用
- N>1：全部块完成后一次归并调用（rough_read_merge.txt，temp 0.2，maxTokens 800）
- **归并失败（两次重试后仍败）降级规则——"失败绝不覆盖已有内容"**：
  - **非 force 场景**（runMergeOnly / 归并重试）且数据库旧 gist **非空** → 保留旧值不写，仅提示"章节大意归并失败，可重试"
  - 旧 gist 为空（首次粗读）或 **force 重标注**（旧 gist 对应旧标注，保留会与新标注错位）→ 写降级值：第一块 gist + 全部块 key_terms 按 term 去重合并（上限 10）
- 归并成功 → `ChapterDao.updateAiGist`；段落标注逐块即时落库（见 2.5），非归并阶段职责

### 2.5 落库与断点续跑

- **块完成即落库**：该块全部段的 `updateAiAction(action, groupId, why)`（groupId 按 2.3 约定；skip/explain 的 groupId=null），在 `withContext(IO)` 循环写；量级 ≤40 段/块，无需显式事务
- 重跑判定：确定性分块后，**块内所有段 aiAction != NONE 才跳过**；部分标注的块整块重跑（覆盖式更新该块段落）
- **force 参数**：`Planner.run(chapterId, force: Boolean = false)`——force=true 忽略跳过判定整章覆盖重跑（"重新标注"）；force=false 走断点判定（"继续粗读"）
- **只跑归并**（独立入口 `runMergeOnly()`，不受空转保护限制）：全部块已完成但 gist 为空，或完成态用户点"重试归并"→ 只跑归并
- 全部块完成且 gist 非空时 force=false 且非 runMergeOnly → 什么都不做直接返回（避免"重新标注"误触发的空转）
- 进度回调 `onProgress(doneBlocks, totalBlocks)`
- 开始粗读时 `updateReadState(READING)`（已是 DONE 不回退）
- 归并完成后统计讲解单元总数（explain+group），超 10 时在章级卡提示"本章讲解偏多，可重新标注"（宁少勿多可观测）

### 2.6 全局互斥、调用参数与错误

- **RoughReadRunner**（class，由 StudyApp 容器持有单例；测试直接 new 传假 chat）：持有唯一 `Job`；`start()` 前先 cancel 旧 job（跨章切换/重复触发互斥，低内存约束下同时只允许一章粗读）；暴露 `isRunning(chapterId)`、`cancel()`、状态 StateFlow。VM 只转发调用，不自己持 job——**导航语义**：从阅读页返回书架 → Runner 是应用级单例**粗读不中断**（进度在章目录页不可见但落库继续；重新进入章节页可见进度），用户可从阅读页按钮 cancel
- 每块：chatJson，temperature 0.2，maxTokens = `clamp(4096, 8192, 段数×130+600)`——按 2.2 段数上限 40，公式最大 5800，实际输出预算充足；串行逐块（不并行：内存+限速）
- **块级失败处理**：块调用失败（网络/HTTP/JSON 不可解）或 PlannerException（缺标>30%）→ **重试 2 次**；两次仍败 → 停止流水线，保留已完成块（已落库），状态"粗读中断于第 N 块"
- **不可重试错误不重试**：`AiException` 增加只读字段 `httpCode: Int?`（M3 客户端小改，非 2xx 分支填充），httpCode in 401/403/404 → 直接中断不重试（白打请求徒增延迟）；其余错误重试
- 前置检查：decryptKeyOrNull 非 null（否则引导设置）、段落列表非空

## §3 UI 设计

### 3.1 章目录页（ChapterListScreen，新）

- 路由 `BOOK/{bookId}`；书架 BookList 行点击 → navigate
- 列表项：章标题 + readState 徽标（未读灰/在读蓝/读完绿；READING 由开始粗读写入）+ hasAsset 徽标（"本章包"）；点章 → READ
- 顶部书名+作者；章列表来自 `byBookFlow`

### 3.2 阅读页（ReadScreen，新）

- 路由 `READ/{chapterId}`（chapterId 反查 bookId）
- 结构：顶栏（章标题 + 上/下章 icon）→ 章级卡（gist + key_terms chips，`byIdFlow` 收集，粗读完成即时刷新，可折叠）→ LazyColumn 正文段
- 段落渲染：BODY 16sp 宽行距；按 aiAction：
  - EXPLAIN：左侧主题色竖条 + ★ 徽标 + 段下小字 why
  - GROUP：组首段（groupId==idx）显示"合并讲解"徽标 + why；组内段共享该徽标视觉（按 groupId 匹配）
  - SKIP：文字 60% 透明度
  - NONE：正常显示（未跑粗读或 AI 未提及，无徽标）
- 底部操作条（**四态**状态机由 VM 从段落/章数据推导）：
  - 未粗读（无任何标注）：主按钮"开始粗读"→ RUNNING（进度"粗读中 i/n"+ 取消按钮，进度来自 Runner 回调）
  - 中断（部分标注）：主按钮"继续粗读（已完成 i/n）"
  - **标注完成待归并（全标注+gist 空）**：主按钮"重试归并"（走 runMergeOnly）
  - 完成（全标注+gist 非空）：主按钮"重新标注"（**force=true**，弹二次确认"将覆盖现有标注"）+ 副按钮"重试归并"（多块章显示，走 runMergeOnly）+ "标记本章读完"（readState→DONE）
- 粗读 job 由 Runner 持有（§2.6），VM 只观察状态与落库结果（段落 Flow 自动刷新）

### 3.3 导航接线

- Routes 加 BOOK、READ；BookList onOpen(bookId)；BOOK/READ 隐藏底栏（沿用 IMPORT/TOC 模式）

### 3.4 视觉规格分期声明（M4a 质检登记，防范围漂移）

本章视觉细节**延至 M7 打磨**统一实现，M4a 以结构可用为准（标注信息全部以文字可达：徽标用文字标签、why 用小字正文）：

- 章目录：readState 彩色徽标（未读灰/在读蓝/读完绿）、hasAsset"本章包"徽标
- 阅读页：上/下章切换 icon、gist 卡 key_terms chips、EXPLAIN ★ 徽标与左侧主题色竖条、正文 16sp 宽行距
- 此项为质检复验建议的显式登记：M4a 不因视觉规格返工，M7 统一执行（见 00-总计划 M7 清单）

## §4 新增/修改文件清单

```
新增 main/java/com/studyfriend/app/data/ai/PromptLoader.kt        assets prompt 加载（缓存）
新增 main/java/com/studyfriend/app/data/study/RoughReadModels.kt  RoughReadPlan/BlockPlan/PlanParser（容错+unit_budget）
新增 main/java/com/studyfriend/app/data/study/RoughReadChunks.kt  确定性分块（8000 字 + 40 段双上限）
新增 main/java/com/studyfriend/app/data/study/RoughReadPlanner.kt 流水线（run(force)/断点/归并降级/落库/块级重试）
新增 main/java/com/studyfriend/app/data/study/RoughReadRunner.kt  全局单例：唯一 Job、start 前 cancel 旧、cancel、状态流
新增 main/java/com/studyfriend/app/ui/screens/ChapterListScreen.kt + ChapterListViewModel
新增 main/java/com/studyfriend/app/ui/screens/ReadScreen.kt + ReadViewModel
新增 assets/prompts/rough_read.txt、rough_read_merge.txt
改   main/java/com/studyfriend/app/data/db/Daos.kt                ChapterDao 补 byIdFlow
改   main/java/com/studyfriend/app/ui/nav/AppNav.kt               BOOK/READ 路由
改   main/java/com/studyfriend/app/ui/screens/ShelfScreen.kt      BookList 行 onOpen
新增 test/.../study/RoughReadChunksTest.kt
新增 test/.../study/PlanParserTest.kt
新增 test/.../study/RoughReadPlannerTest.kt
新增 test/.../ui/ReadScreenSmokeTest.kt（Robolectric，4 用例）
```

**可测性关键决策**：Planner 构造注入 `ChatJsonFn`（`fun interface ChatJsonFn { suspend fun <T> invoke(req: ChatRequest, deserializer: DeserializationStrategy<T>, onDelta: (String) -> Unit): T }`——泛型函数类型需接口承载；生产引用 AiClient.chatJson，测试传假实现）→ 分块/解析/归并/断点全 JVM 单测；Runner 为 class，StudyApp 持有单例，测试直接 new。

## §5 测试矩阵（新增 ≥32 用例）

1. Chunks（5）：确定性 / 段不跨块 / 8000 字边界 / 40 段上限触发 / 单段超 8000 独立成块且输入仍截 200 字
2. unit_budget（2）：占比计算（2:1 字符占比 → clamp 后 4:3）/ 单块=10
3. PlanParser（10）：合法全量 / id 越界丢 / action 大小写+空白归一后合法 / group 非连续→explain / group 长度>3→explain / 单段 group→explain / 重复 id 后到生效 / gist+terms 截断 / 缺标≤30% 通过 / 缺标>30% 抛 PlannerException
4. Planner（11）：单块直用 gist / 多块归并一次 / **归并失败+旧 gist 非空+非 force→保留旧值** / **归并失败+旧 gist 空→写降级值** / **force 重标注归并失败→写降级新值（不保留旧 gist）** / **块 1 成功块 2 连续失败（含缺标异常路径）→中断+块 1 保留** / **缺标第 1 次失败第 2 次通过→块成功** / **重跑跳过已完成块+缺标块整块重跑** / **force=true 覆盖全部块** / **取消中断后已落库块保留** / **无 Key 前置检查报错**
5. Runner 互斥（1）：start A → start B → A 被取消、B 运行（假 chat 挂起验证）
6. runMergeOnly（1）：全标注+gist 空 → 只调归并不跑块
5. UI 冒烟（Robolectric，4）：章目录渲染+readState 徽标 / 阅读页段落渲染 / EXPLAIN 段 why 显示 / SKIP 段淡显+GROUP 组首徽标
6. groupId（跨块不撞号断言并入 Planner 测试 4 与 Parser 测试 3）

## §6 验证与回归

- `:app:testDebugUnitTest` 全绿（现 64 + 新增 ≥32 = ≥96）
- `:app:assembleDebug :app:assembleRelease` 双绿
- M2 回归：BookParser/TextLoader/ImportRepository 原用例全绿；M3 回归：AiClient/Settings 全绿（AiException 加 httpCode 字段为非破坏性改动）
- 内存自查：串行块调用、分块引用不复制文本、Robolectric 仅 4 用例

## §7 风险与对策

| 风险 | 对策 |
| --- | --- |
| AI 幻觉编号/漏标 | 归一化+越界丢弃+缺标率阈值；缺标计入块失败走重试；块级重跑可补 |
| 长章块多耗时长 | 进度+断点+串行限速；unit_budget 控制输出规模；maxTokens 按段数公式 |
| 低内存设备 | Runner 全局单章互斥；串行调用；分块引用不复制 |
| 归并再花额度 | 仅多块章一次；失败降级且保留旧值 |
| 用户对标注不满意 | "重新标注" force 路径 + 二次确认 |
| 返回书架误以为中断已停 | Runner 存活继续落库；章目录/阅读页重新进入可见进度；文案明确"后台继续" |
