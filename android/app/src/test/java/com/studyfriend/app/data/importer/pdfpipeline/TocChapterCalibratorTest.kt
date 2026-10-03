package com.studyfriend.app.data.importer.pdfpipeline

import com.studyfriend.app.data.importer.ParsedChapter
import com.studyfriend.app.data.importer.ParsedPara
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 目录驱动切章校准器（P3b-2 §3.1/§3.2/§3.3 单测清单）：注入假页文本源，覆盖 offset
 * 校准（中位数/互差/单锚/合理域/窗口边界）、锚定率与倒挂守卫、三档锚定（只增不删）、
 * 段落按页区间重排、节挂接、系统性偏移检测。Robolectric（android.util.Log）。
 *
 * 夹具约定：目录尾页 4（目录占 PDF p1-4）；条目标题传归一化形态（生产上 TocJson.parse
 * 已 normalizeTitle），页文本行带空格也可命中（匹配前再归一化）。
 */
@RunWith(RobolectricTestRunner::class)
class TocChapterCalibratorTest {

    // ---- 夹具 ----

    private fun entry(title: String, page: Int?, level: Int = 1) = TocEntry(title, page, level)

    private fun para(text: String, page: Int?) = ParsedPara(text, "BODY", page)

    private fun ch(title: String, paras: List<ParsedPara>) = ParsedChapter(title, paras)

    private fun calibrate(
        entries: List<TocEntry>,
        locals: List<ParsedChapter>,
        pages: Map<Int, String> = emptyMap(),
        bookPageCount: Int = 100,
        tocLastPages: List<Int> = listOf(4),
    ): CalibrateOutcome? = TocChapterCalibrator.calibrate(
        entries, locals, { p -> pages[p] }, bookPageCount, tocLastPages,
    )

    /** 标准三场景：目录页码 1/10/20 → 映射页 5/14/24，本地章完全对齐（三档①） */
    private fun standardEntries() = listOf(
        entry("第一章总则", 1),
        entry("第二章分则", 10),
        entry("第三章附则", 20),
    )

    private fun standardLocals() = listOf(
        ch("第一章 总则", listOf(para("甲一。", 5), para("甲二。", 6))),
        ch("第二章 分则", listOf(para("乙一。", 14), para("乙二。", 15))),
        ch("第三章 附则", listOf(para("丙一。", 24))),
    )

    private fun standardPages() = mapOf(
        5 to "第一章 总则\n正文甲。",
        14 to "第二章 分则\n正文乙。",
        24 to "第三章 附则\n正文丙。",
    )

    // ---- offset 校准 ----

    @Test
    fun happyPath_threeAnchors_alignedChapters() {
        val out = calibrate(standardEntries(), standardLocals(), standardPages())
        assertTrue(out != null)
        val chapters = out!!.chapters.filter { it.level == 1 }
        assertEquals(3, chapters.size)
        assertEquals(listOf(5, 14, 24), chapters.map { it.startPage })
        assertEquals(listOf("第一章 总则", "第二章 分则", "第三章 附则"), chapters.map { it.title })
        assertFalse(chapters.any { it.lowConfidence || it.titleFallback || it.fromLocalOnly })
        assertFalse(out.systematicShiftSuspect)
        assertEquals(listOf(2, 2, 1), chapters.map { it.paras.size })
    }

    @Test
    fun offset_medianOfThreeAnchors() {
        // 锚点 offset [1,0,0]：锚一实际页 6（offset 1），中位数取 0 → 章 B 映射 14 而非 15
        val entries = listOf(
            entry("第一章总则", 1),
            entry("第二章分则", 10),
            entry("第三章附则", 20),
        )
        val pages = mapOf(
            6 to "第一章 总则", // 锚一 offset 1
            14 to "第二章 分则", // 锚二 offset 0
            24 to "第三章 附则", // 锚三 offset 0
        )
        val locals = listOf(
            ch("第一章 总则", listOf(para("甲。", 5))),
            ch("第三章 附则", listOf(para("丙。", 24))),
        )
        val out = calibrate(entries, locals, pages)!!
        // 章 B 无本地章 → 三档③插入；中位数 0 → 插在 14（若误取首锚 offset 1 会插在 15）
        val inserted = out.chapters.first { it.title == "第二章 分则" }
        assertEquals(14, inserted.startPage)
        assertFalse(inserted.titleFallback) // 页 14 有真实标题行，章名从页面匹配到
    }

    @Test
    fun offset_inconsistencyDiscardsSegment_wholeNull() {
        // 锚点 offset [0,3,0] 互差 3 >1 → 区段弃用 → 锚定率 0 → 整书 null
        val pages = mapOf(
            5 to "第一章 总则",
            17 to "第二章 分则", // assumed 14 → offset 3
            24 to "第三章 附则",
        )
        val out = calibrate(standardEntries(), standardLocals(), pages)
        assertNull(out)
    }

    @Test
    fun offset_singleAnchor_lowConfidenceStillCalibrates() {
        // 2 条目仅锚一可锚定 → 单锚 offset 采纳、区段 low_confidence；章 B ③插入标 lowConfidence
        val entries = listOf(entry("第一章总则", 1), entry("孤章", 30))
        val pages = mapOf(5 to "第一章 总则\n正文甲。")
        val locals = listOf(ch("第一章 总则", listOf(para("甲。", 5))))
        val out = calibrate(entries, locals, pages)!!
        val aligned = out.chapters.first { it.level == 1 && it.localIndex == 0 }
        assertEquals(5, aligned.startPage)
        assertFalse(aligned.lowConfidence) // 档①对齐章不打标
        val inserted = out.chapters.first { it.title == "孤章" }
        assertEquals(34, inserted.startPage) // 4+30+0
        assertTrue(inserted.lowConfidence)
        assertTrue(inserted.titleFallback) // 页 34 无文本
    }

    // 注：计划案单测清单里的「offset=51 越界弃区段」经公开 API 不可达——锚定窗口把
    // offset 天然限制在 [-3,+20]，上界 50 只是 calibrator 内的纵深防御（见实现内注释）。
    // 下界 -3..-1 可达，见 offset_negative_discardsSegment。

    @Test
    fun offset_negative_discardsSegment() {
        // 标题只出现在 assumed−1（页 4）→ offset −1 越界下限 → 整书 null
        val pages = mapOf(
            4 to "第一章 总则",
            13 to "第二章 分则",
            23 to "第三章 附则",
        )
        val out = calibrate(standardEntries(), standardLocals(), pages)
        assertNull(out)
    }

    @Test
    fun offset_windowForwardEdge_plus20Hit() {
        // 标题在 assumed+20（窗口前沿）→ offset 20 合法 → 章 A 映射 4+1+20=25
        // （区段至少 2 条 level1 才启用，补一条标题无文本的条目占位）
        val entries = listOf(entry("第一章总则", 1), entry("第二章分则", 10))
        val pages = mapOf(25 to "第一章 总则\n正文甲。")
        val locals = listOf(ch("第一章 总则", listOf(para("甲。", 25))))
        val out = calibrate(entries, locals, pages)!!
        assertEquals(25, out.chapters.first { it.level == 1 }.startPage)
    }

    @Test
    fun offset_windowMiss_beyondPlus20_null() {
        // 标题在 assumed+21（窗外）→ 无锚点 → 区段弃用 → null
        val pages = mapOf(26 to "第一章 总则")
        val out = calibrate(
            listOf(entry("第一章总则", 1), entry("第二章分则", 10)),
            listOf(ch("第一章 总则", listOf(para("甲。", 26))), ch("第二章 分则", listOf(para("乙。", 40)))),
            pages,
        )
        assertNull(out)
    }

    @Test
    fun offset_dualAnchorMeanRounded() {
        // 恰 2 锚：offset [1,0] → 均值 0.5 四舍五入 1 → 章 B（③插入）应落 4+10+1=15
        val entries = listOf(entry("第一章总则", 1), entry("第二章分则", 10))
        val pages = mapOf(
            6 to "第一章 总则", // offset 1
            14 to "第二章 分则", // offset 0
        )
        val locals = listOf(ch("第一章 总则", listOf(para("甲。", 6))))
        val out = calibrate(entries, locals, pages)!!
        // 章 B 无本地章 → 三档③插入页 15；页面无文本 → 章名回落目录 title（归一化形态）
        val inserted = out.chapters.first { it.level == 1 && it.localIndex == null && !it.fromLocalOnly }
        assertEquals(15, inserted.startPage)
        assertEquals("第二章分则", inserted.title)
        assertTrue(inserted.titleFallback)
    }

    @Test
    fun segment_degenerateSingleEntry_discarded() {
        // 第三条目页码下降 → 新区段仅 1 条 level1 → 弃用；首区段正常 → 2/3 锚定率 0.67 过守卫，输出 2 章
        val entries = listOf(
            entry("第一章总则", 1),
            entry("第二章分则", 3),
            entry("第三章附则", 2), // 页码下降 → 新区段，仅 1 条
        )
        val pages = mapOf(5 to "第一章 总则", 7 to "第二章 分则")
        val locals = listOf(
            ch("第一章 总则", listOf(para("甲。", 5))),
            ch("第二章 分则", listOf(para("乙。", 7))),
        )
        val out = calibrate(entries, locals, pages)!!
        assertEquals(
            listOf("第一章 总则", "第二章 分则"),
            out.chapters.filter { it.level == 1 }.map { it.title },
        )
    }

    @Test
    fun anchor_shortTitleLowerHalf_positionCheckRejects() {
        // 短标题（≤8 字）只在页面上半部之外命中 → 位置校验拒绝 → 全锚失败 → 整书 null
        val entries = listOf(entry("总则", 1), entry("分则", 10))
        val pages = mapOf(
            5 to "正文甲。\n总则另有引用", // 命中行在下半部
            14 to "正文乙。\n分则另有引用",
        )
        val locals = listOf(
            ch("总则", listOf(para("甲。", 5))),
            ch("分则", listOf(para("乙。", 14))),
        )
        assertNull(calibrate(entries, locals, pages))
    }

    @Test
    fun anchor_dotLeaderLine_skippedNotFalseAnchor() {
        // 目录点线条目行（含标题前缀）不作为锚：真标题在 p5 → offset 0；
        // 若误用 p4 点线行会得 offset −1 并整书 null
        val pages = mapOf(
            4 to "目录\n第一章 总则……5",
            5 to "第一章 总则\n正文甲。",
        )
        val locals = listOf(ch("第一章 总则", listOf(para("甲。", 5))))
        val out = calibrate(listOf(entry("第一章总则", 1), entry("第二章分则", 3)), locals, pages)!!
        assertEquals(5, out.chapters.first { it.level == 1 }.startPage)
    }

    // ---- 锚定率与倒挂守卫 ----

    @Test
    fun guard_anchorRatioBelow60_null() {
        // 区段一（2 条，可锚）+ 区段二（3 条，标题全无文本层 → 弃用）→ 2/5=0.4 <0.6 → null
        val entries = listOf(
            entry("第一章总则", 1),
            entry("第二章分则", 3),
            entry("第四章甲", 2), // 页码下降 → 区段二（3 条，标题无文本层）
            entry("第五章乙", 4),
            entry("第六章丙", 6),
        )
        val pages = mapOf(5 to "第一章 总则", 7 to "第二章 分则")
        val locals = listOf(
            ch("第一章 总则", listOf(para("甲。", 5))),
            ch("第二章 分则", listOf(para("乙。", 7))),
        )
        assertNull(calibrate(entries, locals, pages))
    }

    @Test
    fun guard_anchorRatioExactly60_passes() {
        // 同上结构但区段二 2 条 → 3/5=0.6 恰达下限 → 通过
        val entries = listOf(
            entry("第一章总则", 1),
            entry("第二章分则", 3),
            entry("第三章附则", 5),
            entry("第四章甲", 2), // 页码下降 → 区段二（2 条，无文本层）
            entry("第五章乙", 4),
        )
        val pages = mapOf(5 to "第一章 总则", 7 to "第二章 分则", 9 to "第三章 附则")
        val locals = listOf(
            ch("第一章 总则", listOf(para("甲。", 5))),
            ch("第二章 分则", listOf(para("乙。", 7))),
            ch("第三章 附则", listOf(para("丙。", 9))),
        )
        val out = calibrate(entries, locals, pages)
        assertTrue(out != null)
        assertEquals(3, out!!.chapters.count { it.level == 1 })
    }

    @Test
    fun guard_samePageMapping_keepsFirstTocOrder() {
        // 两条目映射同页 → 保留 TOC 序号靠前者；2/3=0.67 过锚定率
        val entries = listOf(
            entry("第一章总则", 5),
            entry("第二章分则", 5), // 同页
            entry("第三章附则", 20),
        )
        val pages = mapOf(
            9 to "第一章 总则\n第二章 分则", // 两标题同页，锚点互差 0
            24 to "第三章 附则",
        )
        val locals = listOf(
            ch("第一章 总则", listOf(para("甲。", 9))),
            ch("第三章 附则", listOf(para("丙。", 24))),
        )
        val out = calibrate(entries, locals, pages)!!
        val titles = out.chapters.filter { it.level == 1 }.map { it.title }
        assertEquals(listOf("第一章 总则", "第三章 附则"), titles)
        assertFalse(titles.contains("第二章分则"))
    }

    @Test
    fun guard_inversionWithinThreshold_skipsAndContinues() {
        // 5 条目 1 处倒挂（≤min(5×20%,5)=1）→ 跳过继续；4/5=0.8 过锚定率 → 4 章
        // （本地章起点远离映射页，避免干扰三档匹配）
        val entries = listOf(
            entry("甲锚章", 1),
            entry("乙章", 3),
            entry("丙锚章", 5),
            entry("丁章", 4), // 映射 8 < 前条 9 → 倒挂跳过
            entry("戊锚章", 7),
        )
        val pages = mapOf(5 to "甲锚章", 9 to "丙锚章", 11 to "戊锚章")
        val locals = listOf(ch("前言", listOf(para("前言。", 100))))
        val out = calibrate(entries, locals, pages)!!
        val starts = out.chapters.filter { it.level == 1 && !it.fromLocalOnly }.map { it.startPage }
        assertEquals(listOf(5, 7, 9, 11), starts)
    }

    @Test
    fun guard_inversionOverMixedThreshold_null() {
        // 30 条目分两册（下册书内页码 1..15 重排、目录尾页 9）：上册映射 5..19、下册映射
        // 10..24 → 下册前 9 条与上册交叠成 9 处跨区段倒挂 > min(30×20%,5)=5 → 整书 null。
        // 倒挂只能来自跨区段（单区段内 offset 统一、映射单调），这是该守卫的真实触发形态
        val entries = mutableListOf<TocEntry>()
        for (i in 0 until 15) entries += entry("甲章$i", i + 1)
        for (i in 0 until 15) entries += entry("乙章$i", i + 1)
        val pages = mapOf(
            5 to "甲章0", 12 to "甲章7", 19 to "甲章14", // 上册锚（目录尾页 4，offset 0）
            10 to "乙章0", 17 to "乙章7", 24 to "乙章14", // 下册锚（目录尾页 9，offset 0）
        )
        val locals = listOf(ch("开篇", listOf(para("前言。", 90))))
        assertNull(calibrate(entries, locals, pages, tocLastPages = listOf(4, 9)))
    }

    // ---- 前置校验 ----

    @Test
    fun precheck_pageNoMissingOver5Percent_null() {
        val locals = listOf(
            ch("第一章", (1..20).map { para("段$it。", if (it <= 2) null else it) }), // 2/20=10%
        )
        assertNull(calibrate(standardEntries(), locals, standardPages()))
    }

    @Test
    fun precheck_pageNoMissingExactly5Percent_passes() {
        // 1/20 恰 5%：不大于阈值 → 放行（null 页号段跟随原属章）
        val locals = listOf(
            ch("第一章 总则", (5..13).map { para("甲$it。", it) } + para("无页号段。", null)),
            ch("第二章 分则", (14..23).map { para("乙$it。", it) }),
            ch("第三章 附则", (24..33).map { para("丙$it。", it) }),
        )
        val out = calibrate(standardEntries(), locals, standardPages())!!
        val first = out.chapters.first { it.localIndex == 0 }
        assertEquals(10, first.paras.size) // 9 段页区间 + 1 段 null 页号跟随
    }

    @Test
    fun precheck_emptyOrDegenerate_null() {
        assertNull(calibrate(emptyList(), standardLocals(), standardPages()))
        assertNull(calibrate(listOf(entry("第一节", 1, level = 2)), standardLocals(), standardPages()))
        assertNull(calibrate(standardEntries(), emptyList(), standardPages()))
    }

    // ---- 三档锚定（只增不删） ----

    @Test
    fun tier2_moveToNearestChapter_byDeviation() {
        // 映射 18：本地章 22（偏差 4）与 24（偏差 6）→ 取偏差最小者 22，章名重匹配页面文本
        // （区段至少 2 条 level1，补一条锚定条目）
        val entries = listOf(entry("第一章", 1), entry("第二章", 14))
        val pages = mapOf(5 to "第一章 甲", 18 to "第二章 物权", 22 to "第二章 物权")
        val locals = listOf(
            ch("第一章", listOf(para("甲。", 10))),
            ch("第二章 物权", listOf(para("乙。", 22), para("丙。", 23))),
            ch("第三章", listOf(para("丁。", 24))),
        )
        val out = calibrate(entries, locals, pages)!!
        val moved = out.chapters.first { it.localIndex == 1 }
        assertEquals(22, moved.startPage)
        assertEquals("第二章 物权", moved.title)
        assertFalse(moved.titleFallback)
    }

    @Test
    fun tier2_deviationExactly2_goesToMoveBranch() {
        // 映射 20/40，本地章起点 22/42（偏差恰 2）→ ②移动分支；位移 [2,2]：
        // 方差 0 <1 且 |中位数| 2 ≥2 → 系统性偏移疑点
        val entries = listOf(entry("第二章", 16), entry("第三章", 36))
        val pages = mapOf(
            20 to "第二章 物权", 40 to "第三章 占有", // 锚定页（offset 0）
            22 to "第二章 物权", 42 to "第三章 占有", // 移动后章名重匹配页
        )
        val locals = listOf(
            ch("第二章 物权", listOf(para("乙。", 22))),
            ch("第三章 占有", listOf(para("丙。", 42))),
        )
        val out = calibrate(entries, locals, pages)!!
        assertEquals(listOf(22, 42), out.chapters.filter { it.level == 1 }.map { it.startPage })
        assertTrue(out.systematicShiftSuspect)
    }

    @Test
    fun tier2_tie_smallerStartWins() {
        // 映射 20：两侧章 18/22 偏差同为 2 → 取起点页小者 18
        val entries = listOf(entry("第一章", 1), entry("第二章", 16))
        val pages = mapOf(5 to "第一章 甲", 20 to "第二章")
        val locals = listOf(
            ch("第二章 上", listOf(para("甲。", 18))),
            ch("第二章 下", listOf(para("乙。", 22))),
        )
        val out = calibrate(entries, locals, pages)!!
        assertEquals(18, out.chapters.first { it.localIndex == 0 }.startPage)
        assertTrue(out.chapters.first { it.localIndex == 1 }.fromLocalOnly) // 落选章只增不删
    }

    @Test
    fun tier2_nameMatchFails_fallsBackTocTitle() {
        // 新起点页面无标题行 → 章名回落目录 title 并标记 titleFallback
        val entries = listOf(entry("第一章", 1), entry("第二章", 14))
        val pages = mapOf(5 to "第一章 甲")
        val locals = listOf(ch("第二章 甲乙丙", listOf(para("乙。", 22))))
        val out = calibrate(entries, locals, pages)!!
        val moved = out.chapters.first { it.localIndex == 0 }
        assertEquals("第二章", moved.title)
        assertTrue(moved.titleFallback)
    }

    @Test
    fun tier3_insertNewChapter_atMappedPage() {
        val entries = listOf(entry("第一章", 1), entry("第二章物权", 11))
        val pages = mapOf(5 to "第一章 甲", 15 to "第二章 物权")
        val locals = listOf(
            ch("第一章", listOf(para("甲。", 5), para("乙。", 6))),
            ch("第三章", listOf(para("丙。", 30))),
        )
        val out = calibrate(entries, locals, pages)!!
        val inserted = out.chapters.first { it.localIndex == null && it.level == 1 && !it.fromLocalOnly }
        assertEquals(15, inserted.startPage)
        assertEquals("第二章 物权", inserted.title) // 页面真实文本优先
        assertFalse(inserted.titleFallback)
        assertFalse(inserted.lowConfidence)
    }

    @Test
    fun tier3_insertedChapter_splitsGiantLocalChapter() {
        // bddl 场景缩影：本地一章吞并二三章（页 5-30 共 26 段），目录驱动拆回 3 章
        val giantParas = (5..30).map { para("段$it。", it) }
        val entries = listOf(
            entry("第一章绪论", 1),
            entry("第二章物权", 11),
            entry("第三章占有", 21),
        )
        val pages = mapOf(
            5 to "第一章 绪论",
            15 to "第二章 物权",
            25 to "第三章 占有",
        )
        val locals = listOf(ch("第一章 绪论", giantParas))
        val out = calibrate(entries, locals, pages)!!
        val level1 = out.chapters.filter { it.level == 1 }
        assertEquals(listOf(5, 15, 25), level1.map { it.startPage })
        assertEquals(listOf(10, 10, 6), level1.map { it.paras.size }) // 按页区间重排
        assertEquals((5..14).toList(), level1[0].paras.map { it.pageNo })
        assertEquals((15..24).toList(), level1[1].paras.map { it.pageNo })
        assertEquals((25..30).toList(), level1[2].paras.map { it.pageNo })
    }

    @Test
    fun onlyAddNotDelete_fromLocalOnlyKept() {
        // 本地「附录」目录无对应 → 保留（fromLocalOnly）且段落不丢。
        // 末 TOC 章跨度延伸到书末，附录段落全落其中 → 按计划案 #2 记疑似冗余档（数据不删）
        val locals = standardLocals() + ch("附录", listOf(para("附一。", 90), para("附二。", 91)))
        val out = calibrate(standardEntries(), locals, standardPages())!!
        val appendix = out.chapters.first { it.fromLocalOnly }
        assertEquals("附录", appendix.title)
        assertEquals(90, appendix.startPage)
        assertEquals(2, appendix.paras.size)
        assertTrue(appendix.redundantSuspect)
    }

    @Test
    fun onlyAddNotDelete_fromLocalOnlyOverlappingFlaggedRedundant() {
        // 本地多出的「碎片」章与 TOC 章 A 同起点 5：其段落全落 A 的 TOC 跨度 [5,14) 内
        // → redundantSuspect；段落按页区间归排序靠前的 A（数据不丢，碎片仅保留章行）
        val locals = listOf(
            ch("第一章 总则", listOf(para("甲一。", 5), para("甲二。", 6))),
            ch("碎片", listOf(para("碎一。", 5), para("碎二。", 5), para("碎三。", 6))),
            ch("第二章 分则", listOf(para("乙一。", 14))),
            ch("第三章 附则", listOf(para("丙一。", 24))),
        )
        val out = calibrate(standardEntries(), locals, standardPages())!!
        val fragment = out.chapters.first { it.title == "碎片" }
        assertTrue(fragment.fromLocalOnly)
        assertTrue(fragment.redundantSuspect)
        assertEquals(0, fragment.paras.size) // 同起点段落归靠前的 TOC 章，文本仍在 A 内
    }

    // ---- 段落重排 ----

    @Test
    fun rearrange_lastChapterHoldsToBookPageCount() {
        val locals = listOf(
            ch("第一章 总则", listOf(para("甲。", 5))),
            ch("第二章 分则", listOf(para("乙。", 14))),
            ch("第三章 附则", listOf(para("丙一。", 24), para("丙二。", 100))),
        )
        val out = calibrate(standardEntries(), locals, standardPages())!!
        assertEquals(2, out.chapters.filter { it.level == 1 }.last().paras.size) // 页 100 归末章
    }

    @Test
    fun rearrange_sameStartChapters_parasGoToFirst() {
        // TOC 章 A 与 fromLocalOnly 章同起点 5 → 页 5 的两段（A 的+碎片的）都归排序靠前的 A
        val locals = listOf(
            ch("第一章 总则", listOf(para("甲。", 5))),
            ch("碎片", listOf(para("碎。", 5))),
            ch("第二章 分则", listOf(para("乙。", 14))),
            ch("第三章 附则", listOf(para("丙。", 24))),
        )
        val out = calibrate(standardEntries(), locals, standardPages())!!
        assertEquals(2, out.chapters.first { it.localIndex == 0 }.paras.size)
        assertEquals(0, out.chapters.first { it.title == "碎片" }.paras.size)
    }

    @Test
    fun rearrange_pureDeterministic_reimportSafe() {
        // calibrate 纯函数零状态：同输入两次结果全等（re-import 不串台的结构性保证）
        val a = calibrate(standardEntries(), standardLocals(), standardPages())
        val b = calibrate(standardEntries(), standardLocals(), standardPages())
        assertEquals(a!!.chapters, b!!.chapters)
        assertEquals(a.systematicShiftSuspect, b.systematicShiftSuspect)
    }

    // ---- 章行无页码继承（v1.2：mzzz 十二章/bddl 五六章章行无页码实证） ----

    @Test
    fun inherit_chapterWithoutPage_takesFirstChildPage() {
        // 章行 null + 名下首条带页码 → 章起点继承首条页码，照常锚定映射；
        // 继承章标 lowConfidence（起点系推断非页面实测），章名仍取页面真实文本
        val entries = listOf(
            entry("第一章总论", null),
            entry("第一节概说", 1, 2),
            entry("第二章分则", 10),
        )
        val pages = mapOf(
            5 to "第一章 总论\n正文甲。",
            14 to "第二章 分则\n正文乙。",
        )
        val locals = listOf(ch("杂项", listOf(para("杂。", 100))))
        val out = calibrate(entries, locals, pages)!!
        val chapters = out.chapters.filter { it.level == 1 && !it.fromLocalOnly }
        assertEquals(listOf(5, 14), chapters.map { it.startPage })
        // 章1 继承标低置信；章2 原生页码 + 双锚自洽 → 不标
        assertEquals(listOf(true, false), chapters.map { it.lowConfidence })
        assertFalse(chapters.any { it.titleFallback })
    }

    @Test
    fun inherit_consecutiveNullChapters_eachTakesOwnChild() {
        // bddl 实况同构：第五、六章章行连续无页码，各自继承自己名下首条页码
        // （五章→p318 副标题行、六章→p373），互不串台；未继承的章不标低置信
        val entries = listOf(
            entry("第四章多人关系", 276),
            entry("第五章请求权效果", null),
            entry("第五章效果内容与范围", 318, 2),
            entry("第六章准用规定", null),
            entry("第六章要件准用", 373, 2),
            entry("第七章关系", 383),
        )
        val pages = mapOf(
            280 to "第四章 多人关系\n正文。",
            322 to "第五章 请求权效果\n正文。",
            377 to "第六章 准用规定\n正文。",
            387 to "第七章 关系\n正文。",
        )
        val locals = listOf(ch("开篇", listOf(para("开。", 2))))
        val out = calibrate(entries, locals, pages, bookPageCount = 400)!!
        val chapters = out.chapters.filter { it.level == 1 && !it.fromLocalOnly }
        assertEquals(listOf(280, 322, 377, 387), chapters.map { it.startPage })
        assertEquals(listOf(false, true, true, false), chapters.map { it.lowConfidence })
    }

    @Test
    fun inherit_noFollowingEntry_staysSkipped() {
        // 段尾 null 章（其后无带页码条目）保持跳过；其余章锚定率 2/3 ≥60% 校准继续
        val entries = listOf(
            entry("第一章总论", 1),
            entry("第二章分则", 10),
            entry("第三章附则", null),
        )
        val pages = mapOf(
            5 to "第一章 总论\n正文甲。",
            14 to "第二章 分则\n正文乙。",
        )
        val locals = listOf(ch("杂项", listOf(para("杂。", 100))))
        val out = calibrate(entries, locals, pages)!!
        val chapters = out.chapters.filter { it.level == 1 && !it.fromLocalOnly }
        assertEquals(listOf(5, 14), chapters.map { it.startPage })
        assertTrue(chapters.none { it.lowConfidence })
    }

    // ---- 锚定假锚防护（真书 bddl 实证）----

    @Test
    fun anchor_windowSkipsTocPages_noFalseAnchorOnTocPage() {
        // bddl 实证：绪论条目 page=1、目录尾页 20，窗口下界原为 assumed-3=18，扫进目录页
        // p18「H 录第一章绪论…」拼接行（点线后无页码，RE_TOC_LINE 排不掉）→ 假锚 offset=-3。
        // 修复：窗口下界 ≥ tocLast+1，绪论锚定命中正文 p22 → offset=+1
        val entries = listOf(
            entry("第一章绪论", 1),
            entry("第二章不当得利", 53),
            entry("第九章体系构造", 410),
        )
        val pages = mapOf(
            // p18 目录页（若无窗口护栏，assumed=21、窗口 18..41，此页会假命中）
            18 to "H 录第一章绪论......................\n第二节 台湾地区民法 22",
            // p22 绪论正文起点
            22 to "第一章 绪论\n第一节 不当得利的意义",
            // p74 第二章正文起点（53+20+1）
            74 to "第二章 不当得利\n第一节 给付型",
            // p431 第九章正文起点（410+20+1）
            431 to "第九章 体系构造\n第一节 请求权基础",
        )
        val locals = listOf(ch("开篇", listOf(para("开。", 2))))
        val out = calibrate(
            entries, locals, pages, bookPageCount = 520, tocLastPages = listOf(20),
        )!!
        val chapters = out.chapters.filter { it.level == 1 && !it.fromLocalOnly }
        assertEquals(listOf(22, 74, 431), chapters.map { it.startPage })
    }

    @Test
    fun anchor_headerLineMergedWithBody_excluded() {
        // bddl 实证：p514 页眉「索引 493」与正文拼一行、行尾是正文 CJK，
        // RE_TRAIL_PAGE 排不掉 →「索引」锚假命中 p514（offset=+3）与绪论 offset=+1
        // 互差 2 → 区段弃用整书放弃。修复：标题后紧跟页码的拼接行排除 → 索引锚失败，
        // 区段单锚 +1 采纳（lowConfidence）校准继续
        val entries = listOf(
            entry("第一章绪论", 1),
            entry("索引", 491),
        )
        val pages = mapOf(
            22 to "第一章 绪论\n正文。",
            // 索引真实起点 p512 是烂 OCR「索 弓 l」匹配不上
            512 to "索 弓 l\n不当得利 383",
            // 页眉+正文拼行：标题后紧跟页码
            514 to "索引 493 第三人利益契约 297 十三画第三人的返还义务 359 第三人",
        )
        val locals = listOf(ch("开篇", listOf(para("开。", 2))))
        val out = calibrate(
            entries, locals, pages, bookPageCount = 515, tocLastPages = listOf(20),
        )!!
        val chapters = out.chapters.filter { it.level == 1 && !it.fromLocalOnly }
        assertEquals(listOf(22, 512), chapters.map { it.startPage })
        assertTrue(chapters.all { it.lowConfidence }) // 区段单锚 → lowConfidence
    }

    // ---- 系统性偏移检测 ----

    @Test
    fun shift_variedDisplacements_notFlagged() {
        // 锚点互差 0（offset [1,1] 区段自洽），位移 [2,4]：方差恰 1.0（不小于阈值）→ 不标疑点
        // （正向案例见 tier2_deviationExactly2：位移 [2,2] → suspect=true）
        val entries = listOf(entry("第二章", 16), entry("第三章", 36))
        val pages = mapOf(21 to "第二章 物权", 41 to "第三章 占有")
        val locals = listOf(
            ch("第二章 物权", listOf(para("乙。", 23))),
            ch("第三章 占有", listOf(para("丙。", 45))),
        )
        val out = calibrate(entries, locals, pages)!!
        assertEquals(listOf(23, 45), out.chapters.filter { it.level == 1 }.map { it.startPage })
        assertFalse(out.systematicShiftSuspect)
    }

    // ---- 多册书区段 ----

    @Test
    fun segments_descentSplit_twoVolumesEachOwnTocLastPage() {
        // 下册页码重排（1 重新开始）→ 页码下降分段；各区段配对各自目录尾页（4 / 560）
        val entries = listOf(
            entry("第一章甲", 1),
            entry("第二章乙", 3),
            entry("第三章丙", 1), // 下降 → 下册
            entry("第四章丁", 3),
        )
        val pages = mapOf(
            5 to "第一章 甲",
            7 to "第二章 乙",
            561 to "第三章 丙",
            563 to "第四章 丁",
        )
        val locals = listOf(
            ch("第一章 甲", listOf(para("甲。", 5))),
            ch("第二章 乙", listOf(para("乙。", 7))),
            ch("第三章 丙", listOf(para("丙。", 561))),
            ch("第四章 丁", listOf(para("丁。", 563))),
        )
        val out = calibrate(entries, locals, pages, bookPageCount = 630, tocLastPages = listOf(4, 560))!!
        assertEquals(listOf(5, 7, 561, 563), out.chapters.filter { it.level == 1 }.map { it.startPage })
    }

    @Test
    fun segments_entryGroupSingle_usesFirstProbeSegment() {
        // 条目未分段（页码连续）但探针 2 区段 → 取第一段目录尾页，offset 吸收偏差
        val entries = listOf(entry("第一章甲", 1), entry("第二章乙", 3))
        val pages = mapOf(5 to "第一章 甲", 7 to "第二章 乙")
        val locals = listOf(
            ch("第一章 甲", listOf(para("甲。", 5))),
            ch("第二章 乙", listOf(para("乙。", 7))),
        )
        val out = calibrate(entries, locals, pages, tocLastPages = listOf(4, 560))!!
        assertEquals(2, out.chapters.count { it.level == 1 })
    }

    // ---- 节挂接 ----

    @Test
    fun sections_attachedToHostChapterRange() {
        // level2 页 3 → 映射 7（章 A [5,14)）；页 12 → 映射 16（章 B）。
        // 条目按真实目录顺序（章节交错、书内页码递增，否则下降会误分段）
        val entries = listOf(
            entry("第一章总则", 1),
            entry("第一节甲", 3, level = 2),
            entry("第二章分则", 10),
            entry("第二节乙", 12, level = 2),
            entry("第三章附则", 20),
        )
        val out = calibrate(entries, standardLocals(), standardPages())!!
        val sections = out.chapters.filter { it.level == 2 }
        assertEquals(2, sections.size)
        assertEquals(listOf("第一节甲", "第二节乙"), sections.map { it.title })
        assertEquals(listOf(1, 2), sections.map { it.parentOrder }) // 父章序号（1-based）
        assertTrue(sections.all { it.paras.isEmpty() }) // 节行不带段落，阅读流不变
        // 节行紧跟父章
        assertEquals(
            listOf("第一章 总则", "第一节甲", "第二章 分则", "第二节乙", "第三章 附则"),
            out.chapters.map { it.title },
        )
    }

    @Test
    fun sections_outOfRange_dropped() {
        // 节映射页 204 > bookPageCount → 丢弃；章正常
        val entries = listOf(
            entry("第一章总则", 1),
            entry("第二章分则", 10),
            entry("越界节", 200, level = 2), // 映射 204 > bookPageCount
        )
        val locals = listOf(
            ch("第一章 总则", listOf(para("甲。", 5))),
            ch("第二章 分则", listOf(para("乙。", 14))),
        )
        val out = calibrate(entries, locals, standardPages())!!
        assertEquals(0, out.chapters.count { it.level == 2 })
        assertEquals(2, out.chapters.count { it.level == 1 })
    }

    // ---- 页内文本源边界 ----

    @Test
    fun pageText_nullLayers_anchorFailsGracefully() {
        // 全书无文本层（扫描件）→ 无锚点 → 区段弃用 → 整书 null（零回归）
        assertNull(calibrate(standardEntries(), standardLocals(), emptyMap()))
    }
}
