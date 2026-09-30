# OPT-C 子计划案：目录智能识别与特殊处理 + 页眉护栏（R3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans 逐任务执行。步骤用 `- [ ]` 勾选跟踪。

**Goal:** 真实书籍的目录页（裸条目行/裸标签行/•点线/跨行条目）不再拆出垃圾章节、不再当正文软合并；目录在阅读页以条目列表样式呈现并全链路跳过 AI；正文页眉"第一章 XXX 3"（标题+页码）不再逐页成章。

**Architecture:** BookParser 三步——①点线护栏字符类扩展；②A/B 档标题行加"不以数字结尾"护栏；③TOC 区域识别：`目录/目次` 锚点行 + 块级"目录性比率"划区，区内行不产标题命中、按条目重组为 ROLE_TOC 段落。下游：RoughRead/SummaryPlanner 在既有 `byChapter` 结果上过滤 ROLE_TOC（不新增注入点）；ReadScreen 对 ROLE_TOC 段渲染条目样式并隐藏 AI 操作条。

**Tech Stack:** 纯 Kotlin 解析（全量可单测，样本取自《民法总则》真实目录页文本）。

---

### Task C1: DbValues 增加 ROLE_TOC

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/data/db/DbValues.kt:20-22`
- [ ] **Step 1:** `const val ROLE_TOC = "TOC"` 加入 paragraphs.role 组，注释"目录条目（特殊展示 + AI 全链路跳过）"。
- [ ] **Step 2:** `compileDebugKotlin` 通过（无 schema 变更——role 是普通 TEXT 列，无需迁移）。

### Task C2: 点线护栏扩展 + 标题行数字尾护栏

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/data/importer/BookParser.kt`
- Test: `android/app/src/test/java/com/studyfriend/app/data/importer/BookParserTest.kt`（追加）

- [ ] **Step 1: 写失败测试**（先读既有 BookParserTest 的用例风格再落笔；追加）：

```kotlin
@Test
fun dotLeaderWithBulletChars_notATitle() {
    // 标题前缀 + •点线 + 页码：扩展前 RE_TOC_LINE 不命中 → RE_A_HAN 把整行当章标题（红：
    // title 是垃圾串）；扩展后被 RE_TOC_LINE 拦下 → 无标题命中 → 全文盲切（绿：title=全文）。
    // 注意只断言 size 判别力为零（扩展前后都是 1 章），必须断言标题才红得起来
    val text = "第一章 私法绪论·•··•··••·595\n\n正文内容段落。"
    val chapters = BookParser.parse(text)
    assertEquals(1, chapters.size)
    assertEquals(BookParser.WHOLE_BOOK_TITLE, chapters[0].title)
    assertTrue(chapters[0].blindCut)
}

@Test
fun trailingPageNumberHeaderLine_notATitle() {
    val text = "第一章 私法绪论 3\n这是第一章的正文，讨论民法总则的意义与体系构造。" +
        "\n\n第一章 私法绪论 5\n正文续：请求权基础的思考方法。" +
        "\n\n第二章 民法的法源 49\n第二章正文开始。"
    val chapters = BookParser.parse(text)
    // 三行页眉（含"第二章 民法的法源 49"）均以 CJK+数字结尾，全部降级为正文
    assertEquals(1, chapters.size) // 整书无有效标题 → 单章
    assertTrue(chapters[0].paras[0].text.contains("第一章 私法绪论 3")) // 页眉行保留为正文
}
```

- [ ] **Step 2: 跑测试确认失败。**
- [ ] **Step 3: 实现：**
  - `RE_TOC_LINE` 扩字符类：`Regex("[…·.•‧]{2,}\\s*\\d+\\s*$")`（新增 `•`U+2022、`‧`U+2027）。
  - 页眉护栏 `RE_TRAIL_PAGE = Regex("[\\u4e00-\\u9FFF]\\s*\\d{1,4}\\s*$")`（**数字前必须紧邻 CJK**）——**实现位置：挂在 `matchTitle` 的内置 A/B 档分支内**（GRADE_A/GRADE_B 命中后校验，命中则该行不作标题）；**勿挂 `isTitleCandidate`**（它在 `matchTitle` 之前对所有路径生效，挂那里会把 custom 分支一并拦掉）。页眉"第一章 私法绪论 3"命中被拦；英文"Chapter 12"/"Unit 3"（RE_A_LATIN 合法命中族，既有 `titleVariants_allGradeA` 覆盖）因数字前非 CJK **不受影响**。
  - `matchTitle(custom)` 自定义正则路径不经过护栏——保证 TocConfirmScreen 自定义重切兜底对数字尾行（页眉场景全部形态）仍然有效，不吞 `CustomRegexNoMatchException`。
- [ ] **Step 4: 跑测试确认通过 + 既有 BookParserTest 全绿。**

### Task C3: TOC 区域识别与条目重组（核心）

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/data/importer/BookParser.kt`
- Test: `android/app/src/test/java/com/studyfriend/app/data/importer/BookParserTest.kt`（追加真实样本用例）

**算法（总计划 §1 草案的落地版）：**
1. 行分类：`isDotLine`（点线+页码尾）、`isPageOnly`（`^\d{1,4}$`）、`isHeadingish`（`第X章/节/讲` 前缀或 FIXED_WORDS）。
2. 块比率 `tocRatio(b) = (dot+pageOnly+headingish) / 非空行数`；`isTocBlock = dotLines ≥ 1 && ratio ≥ 0.5`。
3. 锚点：首行（或任一行）trim 后等于"目录"/"目次"/"Contents"（ignoreCase）的块；从锚点块起连续 `isTocBlock` 划为 TOC 区（锚点块本身无条件划入）；遇首个非 TOC 块终止。**锚点缺失兜底（钉死）**：无锚点时，从**首个满足 `isTocBlock` 且自身 dot+pageOnly 行 ≥3 的块**起划区（单点 stray 点线行不触发；TOC 位于书首同样适用），区域同样 = 连续 `isTocBlock` 块——锚点路径与兜底路径统一语义。行为对无目录书保持向后兼容。
4. 区内行**除锚点行外**不产生标题命中；锚点行本身照常按 FIXED_WORDS 产 GRADE_A 命中并作为目录章标题（`chapters[0].title == "目录"` 依赖此）。区内段落重组：累积行直到出现 `isDotLine || isPageOnly` 行，**该触发行并入当前累积后 flush** 为一个条目段（页码是条目的一部分——断言 `contains("第一节") && contains("107")` 依赖此；跨行条目如"第四节\n请求权…107"因此并回一段），role=ROLE_TOC（用 `DbValues.ROLE_TOC` 常量，勿写字面量）。
5. `parse()` 主循环：块带 `blockRole`（TOC 区内=ROLE_TOC），TOC 块走条目重组路径（不走 softMerge），其余块维持现逻辑。**无锚点区域的条目归属（钉死，否则书首 TOC 会被现行 flush 结构甩成独立"开篇"章）**：TOC 重组段落不落入"开篇"兜底章——区域先于首个标题命中时，条目**顺延并入下一命中章的段落头部**（deferred attach）；锚点路径不受影响（"目录"章由锚点行命中开启，区段自然流入该章）。

- [ ] **Step 1: 写失败测试**（样本取自实测《民法总则》目录页，覆盖证据 4a/4b/4c + 页眉行 + 跨行条目；命名与常量用法对齐既有 BookParserTest 英文驼峰风格）：

```kotlin
@Test
fun realTocPage_recognizedAsTocRegion_noGarbageChapters() {
    val toc = """
        目录
        第一章 私法绪论
        一私法社会、私法秩序、私法原则.............................. 1
        第一节 法律的斗争......................................................... 1
        第二章 民法的法源及法律的适用
        第一节
        请求权、抗辩权及形成权...………………·…..……...
        107
        第七章条件与期限
        —一－法律行为的规划及风险管控…………·……...…..
        428
        主要参考书目
        ..................................................................
        591
        索弓
        1·•··•··•··•··•··•··•··•··•··•·•··••·•··•··•··•··•··•··•·••·••·••·••·••·•• 595
    """.trimIndent()
    val body = "\n第一章 私法绪论 3\n这是第一章页眉形态的正文行。\n" +
        "\n第一章 私法绪论\n\n第一节 法律的斗争\n\n这是第一章正文：法律的斗争是法律史上的常态。" +
        "\n\n第二章 民法的法源及法律的适用\n\n这是第二章正文：法源包括法律、习惯法与法理。"
    val chapters = BookParser.parse(toc + body)

    // 目录条目不得成章：只应有 目录、第一章、第二章 三个章（页眉行"第一章 私法绪论 3"被 C2 护栏降级为正文）
    assertEquals(3, chapters.size)
    assertEquals("目录", chapters[0].title)
    assertEquals("第一章 私法绪论", chapters[1].title)
    assertEquals("第二章 民法的法源及法律的适用", chapters[2].title)
    // 目录章段落全部 ROLE_TOC（含锚点行段），正文章段落为 BODY
    assertTrue(chapters[0].paras.isNotEmpty() && chapters[0].paras.all { it.role == DbValues.ROLE_TOC })
    assertTrue(chapters[1].paras.all { it.role == DbValues.ROLE_BODY })
    // 跨行条目重组："第一节"与"请求权…107"并回一段（触发行并入累积）
    assertTrue(chapters[0].paras.any { it.text.contains("第一节") && it.text.contains("107") })
}

@Test
fun tocRegionDetectedWithoutAnchor_byDensity() {
    // 无"目录"锚点：密度兜底把连续点线条目块划为 TOC 区（区内不产标题命中），后随正文正常成章
    val toc = """
        第一章 私法绪论.............................. 1
        第一节 法律的斗争..................................... 1
        第二章 民法的法源..................................... 9
    """.trimIndent()
    val body = "\n\n第一章 私法绪论\n\n这是第一章正文。"
    val chapters = BookParser.parse(toc + body)
    assertEquals(1, chapters.size)
    assertTrue(chapters[0].paras.any { it.role == DbValues.ROLE_TOC && it.text.contains("第一节") })
}

@Test
fun noAnchor_noTocRegion_backCompat() {
    val text = "第一章 私法绪论\n\n正文A。\n\n第二章 民法的法源\n\n正文B。"
    assertEquals(2, BookParser.parse(text).size)
}
```

- [ ] **Step 2: 跑测试确认失败 → 按算法实现 → 通过。**
- [ ] **Step 2b: 改写两条被本任务有意变更的既有用例**（锚点块无条件划入 TOC 区后，原断言 FRONT 的段落变 ROLE_TOC——不列明会让执行者误以为是自己写错）：
  - `tocEntryWithDotLeaderPageNo_notATitle`（BookParserTest.kt:171 附近）与 `roles_frontMatterAndBackMatter`（:214 附近）中，处于"目录"锚点块内段落的 `DbValues.ROLE_FRONT` 断言改为 `DbValues.ROLE_TOC`；**区外**的 FRONT 段（前言/版权等）断言保持不变。执行时先读两例上下文再改。
- [ ] **Step 3: 既有 BookParserTest 其余用例全绿。**
- [ ] **Step 4（旧数据口径）：** 已入库旧书的 TOC 垃圾段 role 仍是 BODY/FRONT，过滤不追溯——享受目录识别需删除重导；本包不做数据迁移。顺手把 `ParagraphEntity.role` 字段注释 "BODY / FRONT" 补为 "BODY / FRONT / TOC"。

### Task C4: AI 流水线与阅读页消费 ROLE_TOC

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/data/study/RoughReadPlanner.kt:108`（及 :193 二次取数处顺手复用过滤后列表——countUnits 只数 EXPLAIN/GROUP 虽无实际影响，但口径统一）
- Modify: `android/app/src/main/java/com/studyfriend/app/data/study/SummaryPlanner.kt:60`
- Modify: `android/app/src/main/java/com/studyfriend/app/ui/screens/ReadScreen.kt`
- Test: `RoughReadPlannerTest` 追加 1 例（**落点在 Planner 层**——`RoughReadChunks.split` 是不看 role 的纯分块函数，测试打在过滤点所在的 Planner 才有效；该测试文件已有真 Room 内存库 + FakeChat harness）

- [ ] **Step 1: 失败测试**（RoughReadPlannerTest，先读既有 harness 助手形态再落笔）：

```kotlin
@Test
fun tocParagraphs_excludedFromRoughRead() = runBlocking {
    // 插入混合章：1 段 BODY + 2 段 role=DbValues.ROLE_TOC
    // 断言一：run() 正常完成且 FakeChat 收到的分块不含 TOC 段 id
    // 断言二：纯 TOC 章（仅 2 段 TOC）run() 抛 PlannerException，文案含"目录"
}
```

- [ ] **Step 2: 跑测试确认失败 → 实现 → 通过**：RoughReadPlanner `run()` 段落加载后过滤 `val paragraphs = db.paragraphDao().byChapter(chapterId).filter { it.role != DbValues.ROLE_TOC }`；过滤后为空抛 `PlannerException("本章是目录或无正文内容，无需粗读")`；:193 二次取数复用过滤后列表。
- [ ] **Step 3:** SummaryPlanner 同点过滤（60 行）；过滤后为空的友好文案与 Step 2 同款（读该文件确认空段落既有处理，保持一致）。
- [ ] **Step 4:** ReadScreen 段落渲染：`if (p.role == DbValues.ROLE_TOC)` → 条目样式（`bodySmall` + `onSurfaceVariant` 色、无重点竖条、无 AI 操作条与徽标）；否则维持现渲染。（先读段落 item composable 现状再改，保证 SKIP/EXPLAIN 徽标逻辑不被误伤。）
- [ ] **Step 4b: ActionBar 计数过滤（防永久"部分标注"卡死）**：ReadScreen.kt:131-137 的 `markedCount`/`paragraphCount` 改用 `paragraphs.filter { it.role != DbValues.ROLE_TOC }` 的视图（LazyColumn 渲染仍用全量列表）。否则锚点缺失兜底划出的混合章里 TOC 段永远无法标注 → markedCount 永远追不上 paragraphCount → ActionBar 永久停在"上次粗读完成了一部分"。NoteBar/enumerateUnits 天然不受影响（TOC 段无 aiAction 标注）。**纯目录章文案**：paragraphCount==0 但渲染列表非空（全 TOC 段）时，allUnmarked 分支显示"本章是目录，无需粗读"而非"本章没有段落"（与验收 3 措辞对齐）。
- [ ] **Step 5:** 全量单测绿。

### Task C5: 包验证与提交

- [ ] `./gradlew.bat testDebugUnitTest lintDebug --no-daemon` 全绿。
- [ ] `git commit -m "feat(parser): 目录区智能识别（ROLE_TOC）+ 页眉护栏 + 阅读页条目样式与 AI 跳过"`

## 验收标准（包级）

1. 真实目录页样本：目录条目不成章、目录段全 ROLE_TOC、跨行条目重组、正文章 BODY；样本含页眉形态行（与母计划验证口径一致）。
2. 页眉行（CJK 标题+数字尾）保留为正文且不切章；•/‧ 点线行不入标题；英文标题（数字尾但非 CJK 紧邻）不受护栏影响；自定义正则路径不受护栏影响。
3. 粗读/总结对纯目录章给出友好文案（"本章是目录…"）而非"没有段落"类报错，阅读页 ActionBar 同文案；目录条目无 AI 操作条；ActionBar 计数不含 TOC 段（混合章不卡"部分标注"）。
4. 无"目录"锚点的书：无点线密度块时行为与现状完全一致（回归例保障）；有 ≥3 点线/页码行的连续块时按密度划区（新回归例保障）。
5. 既有 213 测试全数保持绿——其中两条 FRONT→TOC 断言按 C3 Step 2b 有意改写（`tocEntryWithDotLeaderPageNo_notATitle`、`roles_frontMatterAndBackMatter`）。
6. 旧书兼容口径：已入库书不追溯迁移，删书重导后享受目录识别（C3 Step 4 记录）。
