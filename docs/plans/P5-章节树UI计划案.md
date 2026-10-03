# P5 计划案：章节树 UI（目录校准消费 + 确认页删章 + 导航修复）

> 前置：P3b-2 已落库章节树数据层（chapters.level/parentOrder/calibrated、paragraphs.pageNo），
> 八判据全 PASS（2997491）。本计划案消费该数据层，把老板诉求「进一步划分出目录标出的小章节，
> 用章节树的形式」落到界面；顺路修三件识别侧遗留：断网导航偶发、同步视觉失败页不入队（原总体
> 计划案 F3）、FRONT/BACK 段无次级样式（原总体计划案 P5-1 剩余）。
> 工作流：本计划案 AI 评分 ≥90 循环 → 按 commit 切片实施 → 每片验证 → 模拟器 E2E → 模块总览更新。

## 1. 现状与问题（代码核实，2026-10-03）

| # | 现状 | 证据 | 后果 |
|---|------|------|------|
| S1 | `ChapterDao.byBookFlow` 过滤 `level=1`，节行完全不可见 | Daos.kt:88 注释「P5 章节树 UI 消费，阅读侧只看章」 | mzzz 校准后 70 个节在章目录页看不到，目录树诉求未兑现 |
| S2 | ReadScreen 上下章切换用 `chapterDao.byBook`（不过滤 level） | ReadScreen.kt:98-104 | 节行能通过箭头到达（数据模型自洽），但入口只有箭头，无目录视图 |
| S3 | `calibrated` 列已落库但无任何 UI 消费 | P3b-2 计划案 §3.3「加列不消费，语义定义权归 P5」 | 用户无法知道这本书是否经过目录校准 |
| S4 | 确认页只能改名不能删章 | TocConfirmScreen.kt（仅 rename/规则/完成） | BookParser 本地假章（如 mzzz「第二章：人 ｛」）只能带病入库 |
| S5 | 断网下「完成导入」后导航偶发不触发（数据已入库无损） | M2 总览决策 27-⑥（P3a 既有，P3b-2 判据 #5 复测仍偶发） | 导入成功但界面停留，用户不知书已入库 |
| S6 | 同步视觉转写失败页不入 vision_queue | ImportViewModel.applyVision:301-303 catch 只写文案 | 失败页的文字层内容永久滞留，视觉增强静默丢失（原总体计划案 F3，判据「失败页 100% 入队」未达成） |
| S7 | FRONT/BACK 段按正文 16sp 大字展示 | ReadScreen.ParaRow:423 仅 TOC/FOOTNOTE 次级样式 | 封面/版权页大字喧宾夺主（老板诉求 2「非正文信息特殊展示」未完全落地） |

## 2. 方案

### A. 数据层：章树投影 + 树构建纯函数（commit 1）

1. `ChapterDao` 新增 `byBookTreeFlow(bookId): Flow<List<ChapterTreeRow>>`：不过滤 level，
   返回全部章行，`ORDER BY idx`。**不改 `byBookFlow`**——其现有消费方（ChapterListScreen 摘要
   计数、M6 门控）行为零变化。
   **ChapterTreeRow 字段清单（钉死来源表，杜绝列名/表错配）**：

   | 字段 | 来源 | 说明 |
   | --- | --- | --- |
   | id/idx/title/readState/gist/keyTermsJson | `chapters c` 同名列 | 与 ChapterWithAsset 一致 |
   | level/parentOrder/calibrated | `chapters c` 同名列 | **三列就在 chapters 表**（Entities.kt ChapterEntity，P3b-2 Migration 3→4 落的四列之三，第四列=paragraphs.pageNo）；评审一轮曾疑在 books 表，已用 Entities.kt 源码证伪 |
   | hasAsset/assetPromptVersion/assetSummaryPresent | `LEFT JOIN chapter_assets a` 派生 | `a.chapterId IS NOT NULL AS hasAsset` 等，与 byBookFlow 同写法 |

   单条 SQL 即可出全部字段（chapters 自身列 + 固定 LEFT JOIN chapter_assets），无需 JOIN books。
2. 新文件 `ui/screens/ChapterTree.kt`：纯函数 `buildChapterTree(rows: List<ChapterTreeRow>): List<TreeNode>`。
   TreeNode = `Node.Chapter(row, sections: List<ChapterTreeRow>)`。挂接规则用 **parentOrder 语义**
   （= 父章在 level1 序列中的 1-based 序号，TocChapterCalibrator.kt:18）：先按 idx 升序收集 level1
   行得章序列，level2 行按 parentOrder 挂到第 parentOrder 个章下；parentOrder 越界或为 null 的
   level2 行（防御：理论不出现）挂到**它 idx 前面最近的 level1 章**，前面无章则挂首章——不丢行。
   树内章/节均保持 idx 升序。纯 JVM 可单测。
3. `ChapterTreeRow` 数据类放 Daos.kt（与 ChapterWithAsset 并列）。

### B. 章目录页树形化 + 校准徽标（commit 2）

1. `ChapterListScreen` 改用 `byBookTreeFlow` + `buildChapterTree`：
   - 章行 = 现 ChapterRow 原样（粗读按钮/徽标/点击进阅读全保留）；
   - 章行尾部加展开/收起箭头（展开态 `rememberSaveable` 按 chapter id 存，默认展开）；
   - **节行=目录锚，不是导航目的地**（评审 P1-2 定案：P3b-2 设计下节行段落恒空
     （CalibratedChapter KDoc「节行恒空，段落归属按 level1 章页区间」），直接进节行页会
     开出空页面）：节行缩进 20dp、bodyMedium 字号、无粗读按钮/无徽标，**点击 =
     `onOpenChapter(父章id, highlight=节标题)`**——跳父章阅读页并滚动定位到节标题段：
     `Routes.READ` 加可选参数 `highlight`（Url encoded，缺省无）；ReadScreen 收到 highlight
     后在 paragraphs 里找「归一化（剥空白与〔标题〕前缀）后等于或以 highlight 归一化形态
     开头」的首个段落，`LazyListState.animateScrollToItem` 到该项；匹配失败（标题在正文中
     被改写等）静默退化为仅打开父章顶部。v1 只滚动不做高亮底色（记遗留可选项）；
   - 节行不参与「全书总览入口门控」计数（沿用章行口径，与现状一致）；
   - **树→LazyColumn 展开策略**（评审 P2-1）：`remember(tree, expandedIds)` 内把树拍平为
     `List<TreeItem>`（Item.Chapter(row) / Item.Section(row, parentChapterId)），章按展开态
     决定是否跟自己节行；item key 用 `row.id`（chapters 表主键，章+节天然唯一），删除/恢复
     靠 `deleted` 集过滤拍平前的行；
   - 树构建防御分支**必须 Log.w**（评审 P2-2）：parentOrder 越界/null 落兜底挂接时输出
     `Log.w(TAG, "parentOrder=$po out of range ... fallback ...")`——P3b-2 数据写入 bug
     不得被静默掩盖（纯函数里日志经可注入的 `onFallback: (String) -> Unit = {}` 回调，
     生产传 Log.w lambda，单测断言回调被调）；
   - highlight 段落匹配**排除 TOC/FOOTNOTE 段**（评审 P2-3）：节标题在正文 TOC 区常有同名
     目录条目段，`startsWith` 会误滚到目录区；匹配域=非 TOC/FOOTNOTE 段。
2. 校准徽标：列表顶部（总览入口按钮下方）当 `rows.any { it.calibrated }` 时显示一行
   「✓ 已按目录校准」（labelMedium + tertiary 色）；未校准书不显示。这是 calibrated 列的
   P5 语义定案：**任一章行 calibrated=true ⇔ 本书目录校准成功**（P3b-2 落库时全行统一置 true，
   语义自洽）。
3. 空态/加载态不变；LazyColumn key 沿用 chapter id（章+节同取自 chapters 表 id 唯一）。

### C. 确认页手删假章（commit 3）

1. ImportViewModel 增 `deleted: MutableSet<Int>`（**原列表下标**集）+ `deleteChapter(index)` +
   `isDeleted(index)`；**不物理删除 chapters 元素**——改名映射（editedTitles 按原始下标）与
   校准输入都基于原列表，物理删除会引起连锁重排。
2. TocConfirmScreen：每行尾加删除图标按钮 → AlertDialog 二次确认（文案含章名；**vm.tocState
   为 Done 时统一追加**「注意：本书已识别出目录，删除的章若在目录中，导入时可能被目录校准
   补回（段落按目录重排）」——不做逐章目录匹配（标题匹配易错），Done 态统一提示，可 ui_describe
   断言）→ 删除后该行显示删除态（划线灰字 +「已删除·点此恢复」），**已删行点击整行=恢复**
   （评审 P2-4：已删行不触发改名弹窗，防 editedTitles 写入永不消费的下标；改名入口只对
   未删行开放），可反悔恢复——防误删成本为零。
3. confirmImport 消费（**localIndex 语义已从源码钉死**：`localChapters[ch.localIndex!!]`
   （TocChapterCalibrator.kt:339）与 `IntArray(localChapters.size)`（:324）证明 localIndex =
   **调用方传入的 localChapters 列表的下标**，传什么列表就是什么列表的下标；现行调用传全量
   列表故恰为原始下标）：
   - 现状路径：`list.withIndex().filter { it.index !in deleted }`，chapterPair 用**原始下标**
     查 editedTitles（现状 `mapIndexed` 的位置下标在删除后不再等于原始下标，此处必须改）；
   - 校准路径：`localChapters` 传剔除后的列表 → localIndex = **剔除后列表的位置**，落库前经
     `keptIndices[localIndex]` 翻译回原始下标再查 editedTitles（keptIndices = 剔除后保留下标
     的有序列表，VM 持有）；**翻译带边界防御**（评审 P1-1）：`localIndex !in keptIndices.indices`
     时置 null 走「用校准输出 title」路径并 `Log.w`（校准器 bug 不得以 IndexOutOfBounds 崩溃
     导入）；配套把 Calibrator KDoc 的「BookParser 章下标」修正为「输入
     localChapters 列表的下标（当前调用方传全量时即 BookParser 原始下标；P5 起传剔除列表）」；
   - **keptIndices 翻译单测先行**（commit 3 内先于接线）：构造 3 章 + 删中间 1 章 + 改名
     首/尾章 + 模拟校准输出 localIndex，断言改名落点章名正确（含 localIndex=剔除后位置≠
     原始下标的错位场景）；
   - 剔除后为空 → 完成导入按钮禁用 + 提示文案。
4. reparse/loadFile/failRead/reset 与 editedTitles.clear() 同步清 deleted。

### D. 断网导航修复（commit 4）

1. 先复现定位：模拟器断网导入 sample_book ×3，logcat 埋点（confirmImport 起止/imported
   赋值/LaunchedEffect 触发/onDone 调用四点各一条 Log.i）统计导航失败率与卡点。
2. 导航触发改造（**imported 生命周期一并定案**：confirm 落库成功→置 true+发事件→消费时
   reset 清 false→导航；loadFile/failRead 亦补 `imported = false` 与新解析并轨，reset() 仍是
   FAB 重进的权威清理点）：
   - VM 增 `importDone = Channel<Unit>(CAPACITY_SINGLE)`；confirmImport 落库成功后先置
     `imported = true` 再 `importDone.trySend(Unit)`（先置位后发事件：消费方的 reset 不会
     抢在置位前跑；trySend 失败=已有未消费事件，`Log.e` 留痕不静默——评审 P2-5）；
   - **reset() 与 loadFile 入口排空 Channel**（评审 P2-6 的更彻底方案：`while
     (importDone.tryReceive().isSuccess) {}`）——防「A 书事件未消费→用户返回换 B 文件→
     进确认页时残留事件触发误导航」的跨文件泄漏；
   - TocConfirmScreen：`LaunchedEffect(Unit)` 首行守卫 `if (vm.imported) { vm.reset(); onDone();
     return@LaunchedEffect }`（兜底「imported=true 但 Channel 空」的重进场景，如进程重建后
     VM 存活而事件已丢——评审 P1-1 场景），随后 `for (_ in vm.importDone) { vm.reset(); onDone() }`
     —— **reset 放在消费循环内**，保证每条事件消费后 imported 归位 false，Channel 不留死锁态；
   - 消除现实现的时序耦合：现状 keyed `LaunchedEffect(imported)` 在效果体内先 reset（把 key
     改回 false）再导航，存在 keyed effect 取消窗口；事件化后导航触发与状态解耦。
3. 复现失败则如实记录「根因 UNMEASURED，防御性改造」，验收以改造后 3/3 成功为准。

### E. 同步视觉失败页入队（F3 修复，commit 5）

1. applyVision 同步路径逐页收集失败页：`t == null`（转写返回 null，含 API 非 200/空文本/
   长度守卫三种内部失败）或循环中途异常（catch 时剩余未转写页全部计入）→ 存
   `failedVisionItems: List<Pair<Int,Int>>`（pageNo to originChars，与 pendingVisionItems 同构）。
2. confirmImport：pendingVisionItems 与 failedVisionItems **合并**写 vision_queue + 调度
   （两集合理论上互斥——互斥依赖 PageSelector.Selection.TooMany 分支的 return（不同步转写），
   合并处加注释声明该依赖防后续重构破坏，并 `distinctBy { it.pageNo }` 兜底去重防重复页）；
   visionStatsNote 文案区分：
   全失败→「视觉转写未完成，N 页已转入后台自动增强」；部分成功→「视觉转写替换了 X/N 页，
   其余 M 页已转入后台自动增强」。
3. 判据沿用原总体计划案 P4：失败页 100% 出现在 vision_queue；0.5× 长度守卫常量不动。

### F. FRONT/BACK 次级样式（commit 5）

1. 新纯函数 `ReadStyles.isSecondaryRole(role: String): Boolean`（TOC/FOOTNOTE/FRONT/BACK
   四值 true）放 `ui/screens/ReadStyles.kt`，ParaRow 的次级样式分支与计数过滤
   （bodyParagraphs 仍只滤 TOC/FOOTNOTE——FRONT/BACK 参与粗读标注，行为与原总体计划案一致）
   都改调它；单测断言四值映射与 BODY 反例。
2. FRONT/BACK 的 AI 口径显式定案（评审 P2-2）：**视觉次级 + 保留 AI 参与**（不并入跳过集合）。
   理由：老板诉求 2 的原文是「非正文信息用特殊字号/字体**区分展示**」——只要求展示层降级；
   FRONT（总序/前言）与 BACK（后记）常含实质内容，值得讲解，静默移出 AI 管线反而丢功能。
   展示与加工解耦：isSecondaryRole 只管样式，粗读/总结过滤集合不动（TOC/FOOTNOTE 口径不变）。

## 3. 验收判据（全部可测）

| # | 判据 | 通过标准 |
|---|------|---------|
| J1 | mzzz 树展示 | 模拟器导入 mzzz：章目录页 16 章行 + 70 节行，抽 3 章（首/中/尾）节标题与 DB chapters 表一致、缩进层级正确；**节行点击走查**：抽 3 个节行点击 → 跳转父章阅读页且节标题段滚动至可见（匹配失败退化情形如实记录） |
| J2 | 未校准书形态 | sample_book（断网导入，无校准）：10 章行无节行、无校准徽标，与现状逐项一致 |
| J3 | 校准徽标 | book 含任一 calibrated 行显示徽标，否则不显示（UI 冒烟断言） |
| J4 | 删除假章 | J4a：确认页删 1 章（配改名另 1 章）→ 落库章数 -1、被删章不在 chapters 表、改名落在对的章上（keptIndices 单测覆盖错位场景）；校准书删 1 章后校准仍成功（章数 = 目录数或含补回，如实记录）。**时序澄清：确认页阶段节行尚不存在（节是校准产物），删除无孤儿节路径**；树构建防御分支由 J7 单测覆盖。J4b：tocState=Done 时删除确认 Dialog **正文尾段**含「补回」提示字样（Robolectric onNodeWithText / ui_describe 断言）；非 Done 态 Dialog 无追加语（防误触发反向断言） |
| J5 | 断网导航 | **先复现后修复**（评审 P2-3 门槛）：断网导入 ×3，复现 ≥1/3 → 按根因修，修复后 0/3 复现即过；复现 0/3 → 降级为「防御性重构（症状未复现）」，落地事件化改造 + 四点 logcat 埋点保留，如实记录「根因 UNMEASURED」进 M2 决策 27-⑥ follow-up，**汇报口径区分「修复」与「防御加固」**；两种路径下断网导入 3/3 导航成功均作为回归护栏实测 |
| J6 | F3 入队 | 断网+视觉配置导 sample_book：同步失败页 100% 出现 vision_queue(PENDING)；恢复网络后 Worker 消化至 DONE/FAILED 终态 |
| J7 | 回归 | 存量单测全绿（现 446）+ 新增单测全绿：①buildChapterTree 正常挂接 + **三个防御分支**（parentOrder=null/0、parentOrder=章数+1 越界、level2 前置无 level1——断言不丢行、挂接落点正确、onFallback 回调被调）；②isSecondaryRole 四值映射 + BODY 反例；③keptIndices 翻译（删中章+改首尾章+localIndex 错位场景，断言改名落点）+ **localIndex 越界不崩溃走 title 兜底**；④J4b Dialog 文案双向断言；⑤节行点击回调参数断言（onOpenChapter 收到父章 id 与 highlight=节标题）；⑥highlight 匹配域排除 TOC/FOOTNOTE 的单测。TXT 导入章目录页无节行无徽标、行为不变；Robolectric UI 冒烟（M2 域新增树形用例）全绿 |
| J8 | 性能 | buildChapterTree 纯函数 1000 行合成数据（300 章+700 节）单测 <100ms；mzzz 86 行树形列表模拟器滚动流畅（E2E 走查无卡顿投诉级问题） |
| J9 | 模块总览 | M2-导入与解析.md + M4a 阅读总览更新（若涉及），含 P5 决策与遗留 |

## 4. 风险与红线

1. **不动 `byBookFlow`**：M6 总览门控/ChapterListScreen 现有摘要走旧查询，零回归面。
2. 节行进阅读页后 ActionBar/NoteBar/章末总结对节行可用（节行=章行，P3b-2 数据模型即如此），
   M5/M6 消费方按 chapterId 取数不受树形展示影响。
3. 删除×改名×校准三方映射以「原始下标」为唯一锚（C-3），实施时先写 keptIndices 翻译单测再接线。
4. Channel 事件与 Activity 重建：Channel 挂 VM（Activity scope）。imported 完整清除路径=
   消费循环内 reset（每条事件消费即清）/ FAB 重进 reset / loadFile 与 failRead 补清；重进
   确认页首行守卫兜底「imported=true 但 Channel 空」场景（评审 P1-1），不会出现禁用态死锁页。
5. 节行语义（评审 P1-2/P2-1 定案）：节行=目录锚非导航目的地（P3b-2 设计下节行段落恒空，
   段落全归 level1 章页区间），点击跳父章 + highlight 滚动定位到节标题段；节行自身不产生也不
   展示 readState/徽标/粗读按钮，粗读仍按章行整章进行（节标题段是章行段落的一部分）；
   M6 总览入口门控与进度走 `byBookFlow`（level=1 过滤）**不经过新查询 `byBookTreeFlow`**
   ——节行不混入总览门控计数（现状已如此，本计划案不改变）。
6. schema 不变性：本计划案**不动 DB schema**；byBookTreeFlow 依赖的 level/parentOrder/
   calibrated 三列由 P3b-2 的 Migration 3→4 提供（带迁移测试），Room 迁移链保证旧版本库
   升级后可查（评审 P2-3：无旧库崩溃路径，P5 无新 Migration）。
7. 删除×校准交互：删除真章（目录中有）→ 校准按目录补回（三档只增不删）→ Dialog 文案已
   声明该行为（C-2），用户可预期；删除的假章不在目录中 → 校准不补回。
8. 不丢字红线不涉及（本计划案不改任何解析/清洗代码）；E2E 前照例 DB 快照备份。

## 5. 明确不做

- `calibrateExistingBook`（存量书 re-import 补校准）实现：单人应用重导 PDF 已达同效、书量小，
  骨架与签名语义保留（BookRepository.kt:89-95），待真实覆盖率数据再立项。
- 章节树拖拽排序/手动改挂接（产品复杂度与收益不成比例，校准错了以删章+重导兜底）。
- ReadScreen 内嵌目录抽屉（上下章箭头已覆盖章间移动，树集中在章目录页）。
- P4 示意图保留（独立计划案，本计划案完成后立项）；P6 本地 OCR（独立线）。
- 后台视觉队列的重试策略调整（E 项只补「入队」，队列行为不动）。

## 6. 过评记录与实施备注（2026-10-03，四轮 84→87→87→92）

第 4 轮评审 92 分通过，无阻塞项；三条 P2 建议当场采纳为实施备注：

1. **highlight 匹配三级退化**：归一化后精确 `==` 优先 → 零命中再退化 `startsWith`
   （多命中取文本最短者——节标题段通常远短于正文段）→ 仍零命中不滚动。J1 走查含
   「结论」类短标题误命中场景。
2. **buildChapterTree 零 level1 边界**：全书只有 level2 行时返回空树前 `onFallback`
   上报（生产 Log.e 级），不静默丢行也不静默挂错。
3. **keptIndices 单测补三个边界**：删首章（keptIndices=[1,2]）、删尾章（[0,1]）、
   仅留 1 章（[1]）——off-by-one 最易发的三个位置。
