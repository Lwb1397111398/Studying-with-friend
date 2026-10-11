# M4a 阅读页与粗读流水线 · 模块总览

> 状态：✅ 完成（计划 v1 82 分不通过 → v2 92 分通过 → 实现 → 质检第一轮 82 分 → 6 P1 全修复 → 复验 90 分通过 → 6 项 P2 收尾 → P5 章节树 UI → P4-D 阅读页示意图混排 → **P7 阅读页美化 + 长按随手问（去横线/字号四档/工具收纳 chips/长按取词浮条/QuickQaSheet 流式问答）**）
> 验证：**750/750 单测全绿**（全模块，1 skipped），assembleDebug 构建通过；P7 E2E：阅读页新观感（无横线留白分段/单 chip 工具行/Aa 按钮）、字号弹层四档+默认 18 勾选、选 20 号即时生效、杀进程重进记忆 20 号、长按「法」字高亮+浮条四按钮、问含义→QuickQaSheet 引用块+自动发问+真实 AI 调用（429 错误态+重试按钮实证，流式 200 分支因 Key 限流未实测）、滚动取消划词（截图 .e2e/p7_*.png）

## 功能清单

| 功能 | 说明 |
| --- | --- |
| 章目录页 | 路由 `BOOK/{bookId}`；书架点书进入；**P5 章节树**：`byBookTreeFlow` 全行投影（章+节）→ `buildChapterTree` 按 parentOrder 挂接（防御：越界/null 挂前最近 level1、零 level1 扁平自成树）→ `flattenTree` 拍平渲染，章行尾「展开/收起小节」箭头（无节行不显示）；**校准徽标**「✓ 已按目录校准」（任一行 calibrated=true 即显示）；章行 = 序号/标题/一行摘要/在读·读完·未读徽标/本章包徽标，行尾操作按数据推导：未粗读→"粗读"、中断或断点数据（READING+gist 空）→"继续"、已完成→"重读"、进行中→"进度 x/y+停止"；**节行**（level=2）= 缩进小字次级样式，无粗读按钮，点击=目录锚跳父章+highlight 滚动定位（节行段落恒空，不作为导航目的地）；点行进阅读页 |
| 阅读页 | 顶栏章标题+返回+**Aa 字号按钮**；**P7 观感**：段落无分隔线（删 HorizontalDivider），段间留白 vertical padding 12dp；正文字号四档 16/18/20/22（默认 18）经 `settingsRepo.readFontSize/saveReadFontSize` 存 settings 表 KV（键 `read_font_size`，越界值回退默认），FontSizeSheet（ModalBottomSheet）示例文字按实际 sp 渲染、当前档 Check；本章要点卡（gist + key_terms，`byIdFlow` 收集，粗读完成即时刷新）**默认收起成「要点」chip，点开才展开 GistCard**；段落流按 aiAction 渲染：EXPLAIN/GROUP→"讲/合并讲"徽标+why 小字、SKIP→"已跳过"标签+全文半透明（alpha 0.55，仍可读）、NONE→正常文本；**role=TOC/FOOTNOTE/FRONT/BACK 段小字次级样式**（`ReadStyles.isSecondaryRole` 纯函数判定，P5-F 定案：FRONT/BACK 仅视觉降级，AI 规划/计数口径不变——展示与加工解耦），TOC/FOOTNOTE 不参与标注计数，粗读规划两处过滤同跳过 FOOTNOTE；**P5 highlight 定位**：Routes.READ 可选 `?highlight=` 参数（Uri.encode），`findHighlightIndex` 在正文段（排除 TOC/FOOTNOTE）里三级匹配（归一化精确 == → startsWith 多命中取最短 → null 静默不滚动），`listState.animateScrollToItem` 滚到节标题段；**P4-D 示意图混排**：`byChapterFlow` 收集本章 figures → `buildReadRows` sealed ReadRow（Para/Fig）——图插在同 pageNo 第 ordAfterPara 段后（渲染列表=页内全量段口径），ord=-1 三态（该页有段→页首段前/该页无段→页码更小的最后一段后/全章无更早段→章首），ord 越界 `coerceAtMost(页段数-1)` 兜底页末，同锚多图保持 byChapterFlow 序稳定展开；LazyColumn itemKey 段/图前缀防碰撞；highlight 滚动经 paraId 反查行下标（混排后下标不再相等） |
| 工具行收纳（P7） | 原 ActionBar 七态+NoteBar+总结按钮+要点卡四层堆叠 → **ChapterToolsRow 一行横向滚动 chips**：粗读 chip（目录章灰/进行中"粗读 x/y"灰/重读→force 确认/继续 k/n/粗读）、讲解 chip（noteUnitCount>0 才显示，"讲解 x/y"进行中/"讲解 ✓"→force 确认/"讲解 k·n"）、总结 chip（markedCount>0）、要点 chip（hasGist，展开态"收起要点"）；**状态行按需展开**：仅进行中（TaskProgressRow 进度+停止）/interrupted/failed（degraded 重试归并、keyIssue 去设置）/succeeded/allMarked&&!hasGist（完成归并）时占位，`noteState is NoteRunState.Interrupted && noteState.chapterId == chapterId` 中断小字，gateLabel 占用提示「另一项 AI 任务进行中」，稳态 allMarked&&hasGist→标记读完/已读完；两个 force 确认 AlertDialog 保留 |
| 长按取词（P7） | SelectableText：`pointerInput+detectTapGestures.onLongPress` → `TextLayoutResult.getOffsetForPosition` 定位 → `SelectionBounds.wordAt` 取词（ASCII 字母数字+下划线连续段成词、汉字/标点单字符；偏移 coerce 到段内）→ `getCursorRect(word.first).centerLeft` + `boundsInWindow` 换算全局锚点；选中词 SpanStyle(primaryContainer) 高亮；SelectionFloatBar（Popup）：问含义（扩句）/问整段/复制（ClipboardManager）/✕，x 居中于词 clamp 屏内、y 悬词上方 clamp；`expandToSentence` 句末标点集「。！？!?；;…：:」+右引号吸收（弯直引号，句首直引号开关两用不跳过=SKIP_AT_SENTENCE_HEAD 集合不含直引号）、换行收句、段无标点扩整段；**滚动即取消划词**（`LaunchedEffect(listState.isScrollInProgress)`） |
| 随手问 QuickQa（P7） | QuickQaSheet（ModalBottomSheet，heightIn(max=560dp)+imePadding）：打开即自动发问（defaultQuestion：「选中的内容是什么意思？」/问整段「这一段是什么意思？」）；上下文经 `QuickQa.userMessage()` buildJsonObject 组装（chapter/gist/selection/paragraph/prev/next/question，截断 gist 300/selection 500/paragraph 800/prev·next 400）；messages 首轮带全上下文 JSON、追问只发问题正文、history.takeLast(10)；走**讲解模型** `cfg.noteModel.ifBlank { cfg.model }`，PromptLoader 加载 assets/prompts/quick_qa.txt（读书搭子人设：先答再补上下文、≤300 字）；AiClient.chat 流式 onDelta 打字机渲染；**不走 AiGate 全局互斥**（单请求轻量，粗读讲解跑着也能随手问），弹层内 streaming != null 防重入、关闭取消 job；无 Key→引导卡+去设置按钮；错误→红字+重试；滚底自动 scrollToItem；问答不落盘（第一版，关掉即没） |
| P4-D FigureRow | `FigureEntity` 尺寸 `aspectRatio` 先占位（无布局跳动）→ `LaunchedEffect` 经 `FigureBitmapCache.decodeDispatcher`（`Dispatchers.Default.limitedParallelism(2)`，r5-P2-2）解码：先 LRU 命中（读前 `isRecycled` 防竞态）、decodeBounds → 官方采样算法按屏宽预算算 `inSampleSize` → 刷 State；**LRU 按字节** `maxMemory×15%` clamp [15MB, 50MB]（r7-P1-5），驱逐弃引用不显式 recycle（API≥26 像素在 Java 堆，recycle 与 Compose 持有引用竞态）；**失败双态**（r7-P2-7）：`file.exists()==false` → 灰底「图片已丢失」整块可点重试（仍失败维持提示）、BitmapFactory 返回 null → 「图片已损坏」+Log.w 记 file；圆角+1dp 描边+「图 {seqNo}（p{pageNo}）」角标；`contentDescription="示意图，第{n}页"`（TODO(P6) caption）；**vision DONE 命中 Log.w**（r11-P2-2）：produceState 预取 `donePageNosByBook(bookId)`，FigureRow 命中打「锚定可能偏 1-2 段」日志，logcat 直接定位错位图；**全屏预览 Dialog**（P2-1/r5-P2-10）：挂 ReadScreen 顶层（不进 LazyColumn，开关不重建列表、关闭回原滚动位），独立解码不占 LRU、按屏幕尺寸重算采样、`DisposableEffect.onDispose` 用完即回收 |
| 操作条七态（已并入 ChapterToolsRow） | 进行中（进度条+停止）/ 中断（消息+从断点继续+全部重读）/ 成功（消息+标记读完；degraded 时加"重试归并"）/ 失败（重试；keyIssue 时加"去设置"）/ 待归并（全标注+无 gist→"完成归并"）/ 未开始（"开始粗读"）/ 部分标注（数据推导断点入口，进程重启后仍可用）——P7 起以 chips+状态行形态呈现，状态逻辑不变 |
| force 二次确认 | 所有"重读/全部重读"先弹确认框（"覆盖现有标注"），确认后才 force=true——防误触覆盖 |
| 确定性分块 | RoughReadChunks：8000 字 + 40 段双上限、段不跨块、超长段独立成块不丢弃；同输入同划分（断点续跑前提） |
| 逐块粗读 | prompt 三占位符（block_pos/block_total/unit_budget）+ user 消息每段截 200 字；块级重试 3 次，401/403/404 直断不重试；块完成即落库（断点） |
| 解析容错 | PlanParser：action 归一化→越界 id 丢弃→group 连续+2~3 段校验（违者降级 explain）→重复 id 后到生效→缺标>30% 抛异常计入块失败；LenientInt：null/非法 id → -1（幻觉条目静默丢弃，不撞真实 idx=0） |
| 归并 | 单块章直用块 gist（不发归并调用，省额度）；多块一次归并（temp 0.2 / maxTokens 800）；失败降级三态：非 force+旧 gist 非空→保留旧值 / 首块 gist 可用→写降级值 / 无可写→维持空；完成态可用"重试归并"（mergeOnly 只跑归并） |
| 全局互斥 | RoughReadRunner 由 StudyApp 持单例：start 前 cancel 旧 job（同时只允许一章粗读），返回书架粗读不中断（后台继续落库）；状态 StateFlow 五态（Idle/Running/Succeeded/Interrupted/Failed） |
| 空转保护 | 全部已标+gist 非空+非 force+非 mergeOnly → 零 API 调用直接返回 |
| 结构化错误 | 无 Key/密钥失效 → `MissingKeyException`（`PlannerException` 子类）→ Failed.keyIssue=true → UI"去设置"按钮，避免文案字符串耦合 |
| 标记读完 | `app.appScope.launch` 写 readState=DONE——应用级作用域，用户点完立即返回上一页也不取消写库 |

## 关键文件

```
data/study/RoughReadModels.kt   RoughReadPlan/PlanEntry/MergedOverview、LenientIntSerializer、PlanParser、MissingKeyException
data/study/RoughReadChunks.kt   确定性分块（8000 字 + 40 段双上限）
data/study/RoughReadPlanner.kt  流水线 run(force/mergeOnly)：逐块+落库+归并降级+空转保护；ChatJsonFn 注入点
data/study/RoughReadRunner.kt   全局单例：唯一 Job、五态 StateFlow、start/stop/mergeOnly、keyIssue/degraded 结构化
data/study/KeyTermsCodec.kt     要点编解码（term+plain ListSerializer）
data/ai/PromptLoader.kt         assets prompt 加载（内存缓存）
assets/prompts/rough_read.txt   粗读 prompt（三占位符 + JSON 规格 + 宁少勿多）
assets/prompts/rough_read_merge.txt  归并 prompt
ui/screens/ChapterListScreen.kt 章目录页（P5 章节树：buildChapterTree 挂接+flattenTree 拍平+校准徽标+SectionRow 目录锚；行尾操作按数据推导）
ui/screens/ChapterTree.kt       P5 树纯函数：ChapterNode/TreeItem、buildChapterTree（onFallback 注入日志）、flattenTree、normalizeHighlight、findHighlightIndex
ui/screens/ReadStyles.kt        P5-F 次级角色判定 isSecondaryRole（TOC/FOOTNOTE/FRONT/BACK）
ui/screens/ReadScreen.kt        阅读页 + Aa 字号 + ChapterToolsRow chips/状态行 + SelectableText 长按取词 + SelectionFloatBar + FontSizeSheet + QuickQaSheet + ParaRow/ParaTag + force 确认弹窗 + highlight 滚动定位（LaunchedEffect+listState）；P4-D：buildReadRows 混排（sealed ReadRow）、FigureRow（aspectRatio 占位/IO 解码/LRU/失败双态/角标/vision Log.w）、FigurePreviewDialog 全屏预览、FigureBitmapCache（decodeDispatcher limitedParallelism(2)+按字节 LRU）、calcInSampleSize 官方采样
ui/screens/SelectionBounds.kt   P7 划词纯函数：wordAt（ASCII 连续词/汉字标点单字+越界 coerce）、expandToSentence（句末标点+右引号吸收+SKIP_AT_SENTENCE_HEAD）、EMPTY
data/study/QuickQa.kt           P7 随手问消息组装：Turn/Ctx、ASK_MEANING/ASK_PARA、userMessage()（buildJsonObject+截断）、messages()（首轮全上下文/追问仅正文/history.takeLast(10)）
assets/prompts/quick_qa.txt     P7 随手问 system prompt（读书搭子人设、字段说明、≤300 字）
ui/nav/AppNav.kt                BOOK/READ 路由（READ 传 onOpenSettings；READ 可选 highlight 参数 Uri.encode）
StudyApp.kt                     database/appScope/roughReadRunner 三单例
test/.../RoughReadChunksTest.kt    5 例
test/.../PlanParserTest.kt         14 例（含 LenientInt null→-1 专测）
test/.../RoughReadPlannerTest.kt   14 例（Robolectric+真内存库+FakeChat 按 deserializer 分流）
test/.../RoughReadRunnerTest.kt    1 例（start A→start B 互斥，挂起门控）
test/.../M4aUiSmokeTest.kt         4 例（Compose 冒烟）
test/.../ChapterTreeTest.kt        12 例（P5：正常挂接/越界/null/前无章/零 level1/拍平/highlight 三级匹配+TOC 排除）
test/.../P5TreeUiSmokeTest.kt      4 例（P5 Compose 冒烟：树渲染+徽标/节行点击回调/收起隐藏/未校准无徽标）
test/.../ReadStylesTest.kt         3 例（P5-F 次级角色四值 true/BODY/未知值）
test/.../ReadScreenRowsTest.kt     9 例（P4-D：无图恒等/锚后插/ord=-1 三态/clamp 页末/同锚 DAO 序稳定/null 页码回退/采样真值表）
test/.../SelectionBoundsTest.kt    15 例（P7：中文单字/ASCII 连续词/CJK 夹数字/标点单字/越界 coerce/句扩展/句尾吸引号/无标点整段/换行收句/句首右弯引号跳过/句末标点收句/冒号引语/词范围扩句/空串）
test/.../QuickQaMessagesTest.kt    4 例（P7：首轮全上下文/追问仅正文/历史裁最近 10 轮/长字段截断）
```

## 设计决策（与踩坑）

1. **Kotlin 禁止 `fun interface` 带泛型方法** → `ChatJsonFn` 用普通 interface + `suspend operator fun <T> invoke`（invoke 运算符约定保持调用处简洁）；Runner 次构造的泛型 SAM lambda（`ChatJsonFn { … }`）推断不可靠 → 改对象表达式 `object : ChatJsonFn { … }`。
2. **countUnits 必须从库重读段落**：blocks 里持有的是运行前实体引用（aiAction=NONE），标注只落库不回写内存——首次实现单元数恒 0；改为 run 末尾 `db.paragraphDao().byChapter(chapterId)` 重读。
3. **LenientInt 的 null/非法 → -1 而非 0**：0 会撞真实 idx=0 被"重复 id 后到生效"覆盖；-1 必不在块内 → 幻觉条目静默丢弃（专测锁定）。
4. **单块章直用不发归并**：省一次 API 调用；FakeChat 默认 onMerge 抛"意外的归并调用"，单块用例以 `mergeCalls==0` 断言锁定。
5. **Runner 取消防误抹**：`catch (CancellationException)` 里 `if (coroutineContext[Job] === job)` 才回 Idle——新任务 start 时旧 job 的取消异常不能抹掉新状态。
6. **空转保护与待归并并存**：全标注+gist 空（上次归并前中断）不触发空转（gist 条件排除），由"完成归并"入口走 mergeOnly；mergeOnly 不受空转保护限制。
7. **Robolectric 三坑**：chapters 表有 FK 必须先插父书；`ui-test-manifest` 必须 `debugImplementation`（testDebugUnitTest 读 debug variant merged manifest，testImplementation 不进）；Compose 测试视口小 → `waitUntil(5000)+assertExists` 替代 assertIsDisplayed。
8. **冒烟 seed 章必须 readState="NOT_READ"**：READING+gist 空会被数据推导判成"断点续跑"，"粗读"按钮变"继续"导致 ComposeTimeoutException；页内库经 StudyApp 拿（文件库落 Robolectric 沙盒），不 close（lazy 固化，关闭会污染同 JVM 后续测试）。
9. **归并类测试必须 2 块章**（paras=2, textLen=5000）：1 段=单块直用，归并路径根本不走；onBlock 按 call 序号返回对应块 plan，否则块 2 缺标 100% 触发重试噪音。
10. **`repeat {}` 里不能 break**：mergeOverview 改 `for (attempt in 0 until ATTEMPTS)`（成功 return、失败记录 lastError 继续循环）。
11. **构建备忘**：`lintVitalAnalyzeRelease` 在 768m 堆下 lint FIR 分析崩溃（`NoClassDefFoundError: ConeIntegerLiteralConstantTypeImpl`，分析 AiClient.kt 时类加载失败）——内存不足诱发的 lint 工具自身 bug，非代码问题；release 构建用 1280m 堆即稳定通过。
12. **SettingsSnapshot 是 SettingsRepository.kt 的顶层类**而非嵌套类，import 直接 `com.studyfriend.app.data.SettingsSnapshot`。
13. **E2E 点按坐标必须用 ui_describe 的 bounds（真实像素 1080×2400）**，不能按截图坐标换算——截图是缩放过的（900×2000），P7 第一轮按截图点 Aa 按钮点偏、弹层没弹出。
14. **Compose 冒烟 hasScrollAction() 会双匹配**：P7 后阅读页同屏有横向滚动 chips 行+纵向 LazyColumn 两个滚动容器，performScrollToNode 报"Expected exactly 1 node"→ 自定义 `hasVerticalScrollAction()`（读 SemanticsProperties.VerticalScrollAxisRange）。
15. **委托属性不能 smart cast**：`noteState by viewModel` 后 `noteState is X && noteState.chapterId==…` 编译不过 → 用 `(noteState as? X)?.chapterId == …` 或对参数（可 smart cast）写条件。

## 测试矩阵（M4a 新增 38 例，全项目 102/102 绿）

- Chunks（5）：确定性 / 段不跨块 / 8000 字恰好同块 / 40 段上限 / 超长段独立成块
- PlanParser（14）：合法混合 / 幻觉 id 丢 / action 归一 / group 非连续→explain / group>3→explain / 单段 group→explain / 重复 id 后到 / why 40 截断 / 缺标 30% 恰好过 / 缺标>30% 抛 / unitBudget 4:3 / 单块=10 / 宽松字符串数字 / **null+非法 id→-1 幻觉全丢不撞 idx=0**
- Planner（14，Robolectric+FakeChat）：单块直用（mergeCalls=0、占位符替换、200 字截断、terms 截断）/ 多块归并一次 / 归并失败保留旧值 / 归并失败写降级 / force 降级 / 块 2 失败中断+块 1 保留 / 缺标重试第二次过 / **重跑跳块（第二次 blockCalls=+1、gist=归并新值、onMerge 正常出值无噪音）** / force 单块覆盖 / 取消保留 / 无 Key 抛 MissingKeyException / 401 直断（blockCalls=2）/ 空转保护 / mergeOnly
- Runner（1）：start A（挂起门控）→ start B → A 取消 B 运行 + B 完成段落 EXPLAIN 落库
- UI 冒烟（4）：章目录渲染 / 阅读页段落流 / EXPLAIN 段 why 显示 / SKIP 半透明+GROUP 徽标

## 质检记录

| 轮次 | 分数 | 处理 |
| --- | --- | --- |
| 计划 v1 | 82 不通过（1 P0 + 6 P1 + 9 P2） | 逐条修订为 v2 |
| 计划 v2 | **92 通过** | 进入实现 |
| 实现质检第一轮 | 82（6 P1 + 5 P2，无 P0） | P1 全修复 |
| 复验 | **90 通过**（无 P0/P1，6 P2） | P2 趁热收尾 |

- **P1 六项**：单块直用不发归并 / 完成态待归并入口（数据推导）/ "标记本章读完"按钮 / 断点入口从数据推导（进程重启可用）/ Failed 态补操作按钮 / SKIP 段全文半透明仍可读
- **P2 六项收尾**：①完成态 degraded 时"重试归并"按钮；②force 二次确认弹窗；③onMarkDone 改应用级 appScope；④Failed 结构化 keyIssue（MissingKeyException）；⑤测试补强（LenientInt null→-1 专测、rerun 用例补 onMerge 断言消归并噪音）；⑥陈旧注释/测试名清理（ChapterListScreen"阅读态徽标"注释、冒烟测试改名 read_skipFullTextDimmedAndGroupTag）
- **P2 不修留档**：isLenient 不开（LenientInt 自定义序列化器已按字段粒度容错，全局 isLenient 反而放过裸 token 引入新风险，质检认可）；视觉规格延 M7（计划 §3.4 显式登记）
- 收尾后回归：**102/102 全绿 + 双 APK 重建成功**（debug 23:40 / release 00:03 时间戳）

## 对后续模块的接口承诺

- **M4b 讲解单元枚举**：`aiAction == "EXPLAIN"` 的段 + `aiAction == "GROUP" && groupId == idx` 的组首（groupId=组首段 idx，章内唯一、可从标注重建）；ParaNoteEntity 将带 promptVersion 落库（M4a 标注不落版本号的取舍已在计划 §2.1 声明）
- **M5 章末总结包**：chapter.gist/keyTermsJson 已就位；chapter_assets 表已建未写；ParaNote/讲解内容是总结包的输入
- **Runner/Planner 模式复用**：ChatJsonFn 构造注入 + StudyApp 单例 + StateFlow 五态 + 全局互斥——M4b 批量讲解队列沿用同一互斥纪律（讲解与粗读互斥或排队由 M4b 计划定）

## 最后更新

- 2026-10-03 P5 章节树 UI：章目录页树形挂接（buildChapterTree/flattenTree+越界防御）、校准徽标、节行=目录锚（点击跳父章+findHighlightIndex 滚动定位）、FRONT/BACK 次级展示（ReadStyles.isSecondaryRole 展示与加工解耦）；新增 ChapterTree.kt/ReadStyles.kt，ChapterTreeTest 12 例+P5TreeUiSmokeTest 4 例+ReadStylesTest 3 例；E2E J1-J9 全过（详见 docs/plans/P5-章节树UI计划案.md）
- 2026-10-04 P4-D 阅读页示意图混排（commit 795592e）：sealed ReadRow 段图混排（ordAfterPara 锚+ord=-1 三态+clamp）、FigureRow（aspectRatio 占位/limitedParallelism(2) IO 解码/按字节 LRU clamp[15,50MB]/失败双态「图片已丢失可重试·图片已损坏 Log.w」/seqNo 角标）、vision DONE 页命中 Log.w（donePageNosByBook）、全屏预览 Dialog 挂顶层独立解码用完即回收、TocConfirmScreen 图片提取明细展开区（figureNoteDetail）；ReadScreenRowsTest 9 例，全模块 572 绿。**已知取舍**：LRU 驱弃不 recycle（API≥26 像素在 Java 堆，recycle 与 Compose 引用竞态，isRecycled 读取守卫兜底）；失败态无断图图标（material-icons-extended 未引入，纯文案）；J3 走查随 J2 E2E 模拟器实测
- 2026-10-04 六跑渲染回归（largeHeap 修复后 630 页真书 mfzz）：FigureRow 混排代码零改动，图 2（p482）真机渲染实证——真图+「图 2（p482）」seqNo 角标+锚位与 buildReadRows 一致（截图 .e2e/j8_s22.png），figures 表 4 行+4 文件+J3 历史走查共同构成四图回归证据链；渲染链路不受五跑 OOM 影响（OOM 根因在 Java heap 上限=导入链 PDFBox，见 M2 r12-P0-4，FigureRow 解码在阅读页 IO 线程独立发生）
- 2026-10-11 P7 阅读页美化+长按随手问：①删段间 HorizontalDivider 改留白（12dp）；②正文字号四档 16/18/20/22（默认 18）Aa 按钮+FontSizeSheet+settings 表 KV（`read_font_size`，新键无需 DB migration）；③顶部四层工具堆叠收纳为 ChapterToolsRow chips 行+状态行按需展开+要点卡默认收起；④长按取词 SelectionBounds（15 例单测）+SelectionFloatBar 浮条（问含义扩句/问整段/复制）；⑤QuickQaSheet 流式问答：走讲解模型、QuickQa 消息组装（4 例单测）、quick_qa.txt prompt、不走 AiGate、无 Key 引导、错误重试、问答不落盘（第一版）。M4a/M4b 冒烟测试改 hasVerticalScrollAction 自定义 matcher+新 chip 文案断言。全模块 750 绿；E2E 八项过（观感/弹层/即时生效/重启记忆/长按浮条/问答 429 错误链路/重试/滚动取消）。**已知取舍**：流式 200 分支因老板 Key 限流未实测（UNMEASURED，共用 AiClient.chat 已被既有功能覆盖）；划词第一版无拖拽手柄（易碎，三粒度按钮补足）；问答不存历史
