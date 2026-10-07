package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.importer.pdfpipeline.DocStats
import com.studyfriend.app.data.importer.pdfpipeline.PageOut
import com.studyfriend.app.data.importer.pdfpipeline.PLine
import com.studyfriend.app.data.importer.pdfpipeline.Para
import com.studyfriend.app.data.importer.pdfpipeline.ParagraphAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** M2 计划 §5.1-§5.6：段落切分 / 标题档位 / 护栏 / 兜底 / 角色 */
class BookParserTest {

    // ---- 5.1 段落切分 ----

    @Test
    fun paragraphSplit_blankLinesAndSoftMerge() {
        val text = """
            第一段中文直接
            拼接换行。

            第二段。


            全角空格行如下：
            　
            第三段。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(1, chapters.size)
        assertEquals("全文", chapters[0].title)
        val paras = chapters[0].paras
        // 全角空格行本身就是空行（块分隔符），"如下：" 与 "第三段。" 各自成段
        assertEquals(4, paras.size)
        assertEquals("第一段中文直接拼接换行。", paras[0].text)
        assertEquals("第二段。", paras[1].text)
        assertEquals("全角空格行如下：", paras[2].text)
        assertEquals("第三段。", paras[3].text)
    }

    @Test
    fun softMerge_englishGetsSpace_chineseDoesNot() {
        val merged = BookParser.softMerge(listOf("Hello", "World"))
        assertEquals("Hello World", merged)
        val mergedCn = BookParser.softMerge(listOf("中文", "拼接"))
        assertEquals("中文拼接", mergedCn)
        val mergedMixed = BookParser.softMerge(listOf("数字123", "结尾"))
        assertEquals("数字123结尾", mergedMixed)
    }

    // ---- 5.2 标题样本与档位偏序 ----

    @Test
    fun titleVariants_allGradeA() {
        val text = """
            第一章 民法概述

            甲。

            第2单元 物权

            乙。

            Chapter 3 Something

            丙。

            前言

            丁。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(
            listOf("第一章 民法概述", "第2单元 物权", "Chapter 3 Something", "前言"),
            chapters.map { it.title },
        )
    }

    @Test
    fun gradeBias_hanListBeforeChapter_stillChapterWins() {
        // 档位偏序 A≥B≥C：C 档"一、"先出现也不抢占 1 级
        val text = """
            一、概述要点

            第一章 民法概述

            正文内容。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(2, chapters.size)
        assertEquals("开篇", chapters[0].title)
        assertEquals("一、概述要点", chapters[0].paras[0].text)
        assertEquals("第一章 民法概述", chapters[1].title)
        assertEquals("正文内容。", chapters[1].paras[0].text)
    }

    // ---- 5.3 小节不拆章（P0） ----

    @Test
    fun sectionsUnderUnit_stayInBody() {
        val text = """
            第1单元 民法总论

            第一节 民事主体

            民事主体包括自然人、法人和非法人组织。

            第二节 民事法律行为

            民事法律行为是民事主体通过意思表示设立民事法律关系的行为。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(1, chapters.size)
        assertEquals("第1单元 民法总论", chapters[0].title)
        val texts = chapters[0].paras.map { it.text }
        assertEquals(4, texts.size)
        assertTrue(texts.contains("第一节 民事主体"))
        assertTrue(texts.contains("第二节 民事法律行为"))
    }

    // ---- 5.4 仅节级升 1 级 ----

    @Test
    fun onlySections_becomeChapters() {
        val text = """
            第一节 民事主体

            正文甲。

            第二节 民事法律行为

            正文乙。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(listOf("第一节 民事主体", "第二节 民事法律行为"), chapters.map { it.title })
        assertEquals(2, chapters.size)
    }

    // ---- 5.5 护栏反例 ----

    @Test
    fun guardRejectsBodyLines() {
        val text = """
            第一章 真章

            去年GDP增长1.5 亿。

            共 3. 5 个测试点被排除。

            123. 这一条超长数字开头行明显超过四十个字符的标题上限因为它写了太多太多多余的描述性文字内容
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(1, chapters.size)
        assertEquals("第一章 真章", chapters[0].title)
        assertEquals(3, chapters[0].paras.size)
    }

    @Test
    fun tocEntryWithDotLeaderPageNo_notATitle() {
        val text = """
            目录

            第一章 导论……1

            第二章 物权……20

            第一章 导论

            法律是社会规则。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        // 目录条目（点线+页码）不是标题；"目录" 与 "第一章 导论" 是仅有的章
        assertEquals(listOf("目录", "第一章 导论"), chapters.map { it.title })
        // 锚点块划入 TOC 区后，区内条目段为 ROLE_TOC（C3 Step 2b 有意变更）
        assertEquals(DbValues.ROLE_TOC, chapters[0].paras[0].role)
    }

    // ---- OPT-C C2：目录点线变体 + 页眉护栏 ----

    @Test
    fun dotLeaderWithBulletChars_notATitle() {
        // 民法总则 PDF 实测：点线里混入实心圆点 • / ‧，旧正则认不出来导致整行被当成章标题
        val text = """
            第一章 私法绪论·•··•··••·595

            正文内容段落。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(1, chapters.size)
        assertEquals("全文", chapters[0].title)
    }

    @Test
    fun trailingPageNumberHeaderLine_notATitle() {
        // 页眉行"CJK 标题 + 尾部页码"不是章标题，应留在正文里
        val text = """
            第一章 私法绪论 3
            这是第一章的正文，讨论民法总则的意义与体系构造。

            第一章 私法绪论 5
            正文继续，仍在讨论私法的基础概念与体系。

            第二章 民法的法源 49
            第二章正文开始，法源是民法的根本问题之一。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(1, chapters.size)
        assertEquals("全文", chapters[0].title)
        val first = chapters[0].paras[0].text
        assertTrue("页眉行应保留为正文段落，实际首段：$first", first.contains("第一章 私法绪论 3"))
    }

    // ---- OPT-C C3：TOC 区域识别与条目重组 ----

    @Test
    fun realTocPage_recognizedAsTocRegion_noGarbageChapters() {
        // 民法总则（王泽鉴）实测目录页：跨行条目、孤立页码、点线变体混排
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
    fun ocrTocBlock_parenPageNumsAndFragments_tocRegion() {
        // P6c-D 扫描书实录（shpc p8-p16「简 目/详 目」）：锚点词不在 {目录,目次,Contents}，
        // 点线被 OCR 打碎成「..」「●●」、页码带括号「（1）」「(355)」——旧口径密度兜底
        // 0 命中 → 目录条目全混进「序言」章当 BODY（DB 实录 role=TOC 0 条）。
        // 放宽后：标题样+括号页码行 + 点线碎行 + 括号孤页码行须把块划进 TOC 区
        val toc = """
            简 目
            第一章 风险社会、保护国家与损害赔偿制度 ……………………（1）
            第一节 风险社会与保护国家 …………………………………… (1)
            第二节 风险的预防 ……………………………………………… (2)
            第二章 损害赔偿法的规范体系、目的、归责原则及发展趋势
            ..
            (21)
            第二节美国法上的惩罚性赔偿 (359)
        """.trimIndent()
        val body = "\n\n第一章 风险社会、保护国家与损害赔偿制度\n\n这是第一章正文。"
        val chapters = BookParser.parse(toc + body)
        assertEquals("简目块整体为 TOC 区（含简目锚点段），正文正常成章", 1, chapters.size)
        assertTrue(chapters[0].paras.any { it.role == DbValues.ROLE_TOC && it.text.contains("第二节美国法上的惩罚性赔偿") })
    }

    @Test
    fun noAnchor_noTocRegion_backCompat() {
        val text = "第一章 私法绪论\n\n正文A。\n\n第二章 民法的法源\n\n正文B。"
        assertEquals(2, BookParser.parse(text).size)
    }

    // ---- 5.6 整书兜底与盲切 ----

    @Test
    fun noTitles_wholeBookOneChapter() {
        val text = "这是第一段。\n\n这是第二段。\n\n这是第三段。"
        val chapters = BookParser.parse(text)
        assertEquals(1, chapters.size)
        assertEquals("全文", chapters[0].title)
        assertEquals(3, chapters[0].paras.size)
    }

    @Test
    fun noTitles_overlongTextBlindCut() {
        val para = "甲".repeat(2_900).let { "段$it" }
        val text = List(21) { para }.joinToString("\n\n") // ~61k 字
        val chapters = BookParser.parse(text)
        assertTrue(chapters.size > 1)
        assertEquals("第 1 部分", chapters[0].title)
        assertTrue(chapters.sumOf { it.paras.sumOf { p -> p.text.length } } >= 50_000)
    }

    // ---- 5.7 FRONT/BACK 角色 ----

    @Test
    fun roles_frontMatterAndBackMatter() {
        val text = """
            目录

            第一章 导论……1

            第一章 导论

            法律是社会规则。

            参考文献

            张三：《民法总论》，2020年。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(listOf("目录", "第一章 导论", "参考文献"), chapters.map { it.title })
        // 目录锚点块内条目为 ROLE_TOC（C3 Step 2b 有意变更）；正文章/文献章角色不变
        assertEquals(DbValues.ROLE_TOC, chapters[0].paras[0].role)
        assertEquals(DbValues.ROLE_BODY, chapters[1].paras[0].role)
        assertEquals(DbValues.ROLE_BACK, chapters[2].paras[0].role)
    }

    // ---- 5.9 自定义识别规则 ----

    @Test
    fun customRegex_replacesBuiltInRules() {
        // 内置规则不认 "【第X回】" 样式；自定义正则应接管并把 3 行切成 3 章
        val text = """
            【第一回】 开篇

            内容一。

            【第二回】 发展

            内容二。

            【第三回】 结局

            内容三。
        """.trimIndent()
        val noCustom = BookParser.parse(text)
        assertEquals(1, noCustom.size) // 未识别 → 整书单章

        val custom = BookParser.parse(text, "^【第.+回】")
        assertEquals(listOf("【第一回】 开篇", "【第二回】 发展", "【第三回】 结局"), custom.map { it.title })
    }

    @Test(expected = CustomRegexNoMatchException::class)
    fun customRegex_zeroMatch_throwsInsteadOfBlindCut() {
        // §2.3：自定义规则零命中要明确报错，不能静默滑进盲切
        BookParser.parse("正文一行。再一行。\n又一行。", "^完全不存在的标题样式$")
    }

    @Test(expected = java.util.regex.PatternSyntaxException::class)
    fun customRegex_invalidPattern_throws() {
        BookParser.parse("正文。", "^[未闭合")
    }

    // ---- 5.11 A 档实书回归护栏（E2E 真书《民法总则》发现：正文引用章节结构的句子被误判为章标题） ----

    @Test
    fun longCommaClauseAfterChapterPrefix_notATitle() {
        // 正文行"第二章婚姻规定，未使相同性别二人，……"是行文长句，不是章标题
        val text = """
            第一章 导论

            导论正文。

            第二章婚姻规定，未使相同性别二人，得为经营共同生活之目的，成立具有特别考量

            后续正文段落。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(listOf("第一章 导论"), chapters.map { it.title })
        assertTrue(chapters[0].paras.any { it.text.startsWith("第二章婚姻规定") })
    }

    @Test
    fun chapterPlusSectionSameLine_notATitle() {
        // "第四章：法律行为 第一节：权利能力"是正文引用的章+节合并行，不是章标题
        val text = """
            第一章 导论

            第四章：法律行为 第一节：权利能力

            正文继续。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(listOf("第一章 导论"), chapters.map { it.title })
        assertTrue(chapters[0].paras.any { it.text.contains("第一节") })
    }

    @Test
    fun overlyLongHanChapterLine_notATitle() {
        // 超过 24 字的"第X章"行是行文不是标题
        val longLine = "第二章这一章的标题长得实在离谱已经完全超出了正常书籍章节标题应有的长度"
        val text = """
            第一章 导论

            $longLine

            正文继续。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(listOf("第一章 导论"), chapters.map { it.title })
        assertTrue(chapters[0].paras.any { it.text.contains("应有的长度") })
    }

    @Test
    fun ideographCommaInRealStatuteTitle_stillAChapter() {
        // 顿号是法定书章名常态（《民法典》物权编第二章），不得因行中顿号误杀（评审 P2-1）
        val text = """
            第一章 导论

            导论正文。

            第二章 物权的设立、变更、转让和消灭

            物权正文。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(listOf("第一章 导论", "第二章 物权的设立、变更、转让和消灭"), chapters.map { it.title })
    }

    // ---- 5.12 破折号副标题救回（E2E 真书《民法总则》第一章实证） ----

    @Test
    fun subtitleDashLine_rescuedAsChapterTitle() {
        // "章名 ——副标题"25 字超长被护栏拦 → 破折号截断救回，标题取前半
        val text = """
            第一章 私法绪论 ——私法社会、私法秩序、私法原则

            绪论正文。

            第二章 民法的法源

            法源正文。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(listOf("第一章 私法绪论", "第二章 民法的法源"), chapters.map { it.title })
    }

    @Test
    fun subtitleDash_bodySentence_notRescued() {
        // 正文行破折号后带句末标点或行中句读 → 不救，维持拦截
        val text = """
            第一章 导论

            正文其一。

            第一章讲完——他走了。

            正文其二。

            第一章 侵权责任——违约与侵权的竞合，参见第 100 页

            正文其三。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(listOf("第一章 导论"), chapters.map { it.title })
    }

    // ---- 5.13 role 标记补全（E2E 真书整本实测：无用信息要有角色归属） ----

    @Test
    fun footnotePara_prefixStrippedAndMarked() {
        // PDF 管线协议：〔脚注〕前缀由 PdfLoader.assembleText 打上，解析器剥掉标 FOOTNOTE
        val text = """
            第一章 导论

            正文其一。

            ${BookParser.FOOTNOTE_MARK}① 参见王泽鉴《民法总则》第 12 页。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(1, chapters.size)
        val fn = chapters[0].paras.last()
        assertEquals(DbValues.ROLE_FOOTNOTE, fn.role)
        assertEquals("① 参见王泽鉴《民法总则》第 12 页。", fn.text)
    }

    @Test
    fun openingParas_roleFront() {
        // 第一个标题命中前的封面/版权/总序段归 FRONT
        val text = """
            民法总则：2022 年重排版

            王泽鉴 著

            北京大学出版社出版，版权所有，翻印必究。

            第一章 导论

            正文其一。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(listOf("开篇", "第一章 导论"), chapters.map { it.title })
        assertTrue(chapters[0].paras.isNotEmpty())
        assertTrue(chapters[0].paras.all { it.role == DbValues.ROLE_FRONT })
        assertEquals(DbValues.ROLE_BODY, chapters[1].paras[0].role)
    }

    @Test
    fun strayTocEntriesAfterChapter_roleToc() {
        // 目录尾散条目（没盖进 tocRegion、落在第一章后）：标 TOC 不混正文
        val text = """
            第一章 导论

            正文其一。

            第一节 民法的法源…………107

            第二节 权利主体…………208

            正文其二。
        """.trimIndent()
        val chapters = BookParser.parse(text)
        assertEquals(1, chapters.size)
        val roles = chapters[0].paras.map { it.role }
        assertEquals(listOf(DbValues.ROLE_BODY, DbValues.ROLE_TOC, DbValues.ROLE_TOC, DbValues.ROLE_BODY), roles)
    }

    // ---------------------------------------------------------------- P3a 字号证据链

    private val chainStats = DocStats(bodySize = 10f, left = 50f, right = 545f, pitchThreshold = null)

    /** 集成小链路：PLine → ParagraphAssembler → PageOut → assembleText(styleAware) */
    private fun assembleAwareText(
        lines: List<PLine>,
        stats: DocStats = chainStats,
        styleAware: Boolean = true,
    ): String {
        val paras = ParagraphAssembler.assemble(lines, stats)
        val page = PageOut(
            1, tocLike = false, rawChars = 100, puaCount = 0,
            lineCount = lines.size, shortLineCount = 0,
            paras = paras, firstLine = lines.firstOrNull(), lastLine = lines.lastOrNull(),
        )
        return PdfExtractResult(listOf(page), stats, scanned = false).assembleText(styleAware)
    }

    @Test
    fun p3a_prefixedTitle_strippedAndConfirmed() {
        // 单测 1：前缀剥除 + big 证据确认 A 档（styleAware=true）。标题行自身被
        // skipLine 排除不进段落，正文正常跟随
        val chapters = BookParser.parse("〔标题〕第一章 私法绪论\n\n正文内容。", styleAware = true)
        assertEquals(listOf("第一章 私法绪论"), chapters.map { it.title })
        assertEquals("正文内容。", chapters[0].paras[0].text)
    }

    @Test
    fun p3a_abGradeWithoutEvidence_demotedToBody() {
        // 单测 2（集成链）：A 档命中、字号 ≈ 正文（10.4pt 真书假章）→ 无证据降为正文。
        // 对照：styleAware=false 时同一文本成章——锁定拦截由字号门槛导致。
        // （直构 paras 绕过组装器续接，保持假章行独立成段）
        val page = PageOut(
            1, tocLike = false, rawChars = 100, puaCount = 0,
            lineCount = 2, shortLineCount = 0,
            paras = listOf(Para("第十二章权利的行使", size = 10.4f), Para("正文内容说完了。", size = 10f)),
            firstLine = null, lastLine = null,
        )
        val aware = BookParser.parse(
            PdfExtractResult(listOf(page), chainStats, scanned = false).assembleText(styleAware = true),
            styleAware = true,
        )
        assertEquals("假章应被拦截为整书单章", "全文", aware.single().title)
        val unaware = BookParser.parse(
            PdfExtractResult(listOf(page), chainStats, scanned = false).assembleText(styleAware = false),
            styleAware = false,
        )
        assertEquals("无字号门槛时成章（对照）", "第十二章权利的行使", unaware.single().title)
    }

    @Test
    fun p3a_thresholdBoundary_geSemantics() {
        // 单测 3：bodySize=10，size=11.5（=+1.5，二进制精确）→ 前缀；11.4 → 无前缀（>= 语义）
        val big = PLine("第一章 边界", x0 = 50f, x1 = 540f, y0 = 100f, size = 11.5f)
        val small = PLine("第一章 边界", x0 = 50f, x1 = 540f, y0 = 100f, size = 11.4f)
        // P3b-2 起文本以〔页N〕标记行开头，前缀断言针对标题行本身（第二行）
        assertTrue(assembleAwareText(listOf(big)).lineSequence().drop(1).first().startsWith("〔标题〕"))
        assertTrue(assembleAwareText(listOf(small)).lineSequence().none { it.startsWith("〔标题〕") })
    }

    @Test
    fun p3a_ocrPathThresholdRelaxed_digitalUnchanged() {
        // P6b S1 参数化（P6a 判据⑤触发条款，决策案「≥1.5/2pt 两档放宽」第一档）：
        // bodySize=9 → 数字阈值 10.5、OCR 阈值 min(10.5, 9×1.15=10.35)=10.35。
        // size=10.4：数字段不打标（零变化）、OCR 段打标（放宽生效）——同字号仅 sourceVersion 不同
        val stats9 = DocStats(bodySize = 9f, left = 50f, right = 545f, pitchThreshold = null)
        val digital = PLine("第一章 边界", x0 = 50f, x1 = 540f, y0 = 100f, size = 10.4f, sourceVersion = 0)
        val ocr = digital.copy(sourceVersion = 1)
        assertTrue(
            "数字段阈值零改动：10.4 < 10.5 不打标",
            assembleAwareText(listOf(digital), stats = stats9).lineSequence().none { it.startsWith("〔标题〕") },
        )
        assertTrue(
            "OCR 段放宽生效：10.4 ≥ 10.35 打标",
            assembleAwareText(listOf(ocr), stats = stats9).lineSequence().drop(1).first().startsWith("〔标题〕"),
        )
    }

    @Test
    fun p3a_ocrThresholdWiderArm_multiplicationVsAddition() {
        // 「取更宽者」=两臂取小。bodySize=12：加法臂 13.5 < 乘法臂 13.8 → OCR 阈值应为 13.5；
        // size=13.6 落在 (13.5, 13.8) 区间——若实现错取乘法臂（或 max），13.6<13.8 不打标，本测试失败
        val stats12 = DocStats(bodySize = 12f, left = 50f, right = 545f, pitchThreshold = null)
        val line = PLine("第一章 边界", x0 = 50f, x1 = 540f, y0 = 100f, size = 13.6f, sourceVersion = 1)
        assertTrue(
            "OCR 段阈值取加法臂 13.5（更宽者）：13.6 打标",
            assembleAwareText(listOf(line), stats = stats12).lineSequence().drop(1).first().startsWith("〔标题〕"),
        )
    }

    @Test
    fun p3a_cGrade_notGated() {
        // 单测 4：C 档序号小标题与正文同字号，无门槛照常成章（真书小节场景）
        val chapters = BookParser.parse("一、绪论\n\n正文内容。", styleAware = true)
        assertEquals(listOf("一、绪论"), chapters.map { it.title })
    }

    @Test
    fun p3a_styleAwareFalse_behavesAsP2() {
        // 单测 5：TXT 路径（styleAware=false）A 档无前缀照常生效 + assembleText 不打前缀
        val chapters = BookParser.parse("第一章 私法绪论\n\n正文内容。", styleAware = false)
        assertEquals(listOf("第一章 私法绪论"), chapters.map { it.title })
        val body = PLine("正文内容说完了。", x0 = 50f, x1 = 540f, y0 = 100f, size = 10f)
        assertTrue(assembleAwareText(listOf(body), styleAware = false).lineSequence().none { it.startsWith("〔标题〕") })
    }

    @Test
    fun p3a_customRegex_notGated() {
        // 单测 6：custom 手工重切优先，不受字号门槛拦截
        val chapters = BookParser.parse(
            "第十二章权利的行使\n\n正文内容。",
            customTitleRegex = "^第十二章.*",
            styleAware = true,
        )
        assertEquals(listOf("第十二章权利的行使"), chapters.map { it.title })
    }

    @Test
    fun p3a_tocAnchor_withEvidence_fullChain() {
        // 单测 7：锚点行「目录」16pt 带证据 → 目录成章 + ROLE_TOC 产出（目录区识别不回归）
        val text = """
            〔标题〕目录

            第一章 概述…………1

            第二章 发展…………5

            〔标题〕第一章 概述

            正文内容。
        """.trimIndent()
        val chapters = BookParser.parse(text, styleAware = true)
        assertEquals(listOf("目录", "第一章 概述"), chapters.map { it.title })
        assertTrue(chapters[0].paras.any { it.role == DbValues.ROLE_TOC })
    }

    @Test
    fun p3a_smallAnchor_withoutEvidence_tocRegionStillDetected() {
        // 单测 12（评审补充）：小字号锚点（9pt < 阈值）无前缀 → 锚点被拦（无「目录」章），
        // 但 detectTocRegion 独立于字号证据 → 区内条目仍重组为 ROLE_TOC
        val text = """
            目录

            第一章 概述…………1

            第二章 发展…………5

            〔标题〕第一章 概述

            正文内容。
        """.trimIndent()
        val chapters = BookParser.parse(text, styleAware = true)
        assertTrue("小字号锚点不应成章", chapters.none { it.title == "目录" })
        assertTrue("目录区条目仍应重组为 TOC", chapters.any { c -> c.paras.any { it.role == DbValues.ROLE_TOC } })
        assertEquals("正文章不受影响", "第一章 概述", chapters.last().title)
    }

    @Test
    fun p3a_prefixStrippedMidBlock_tocEntriesClean() {
        // 单测 8（parse 侧）：目录页 \n 连接多段成块 → 前缀可能在块中间行（预处理逐行
        // 剥除的理由）；剥除后 TOC 条目重组干净、无前缀残留。
        // （小字号锚点本身被门槛拦截，目录区重组靠 detectTocRegion 独立划区；正文章
        // 带前缀命中保证主流程不走盲切）
        val text = "目录\n〔标题〕第一章 概述…………1\n\n〔标题〕第一章 概述\n\n正文内容。"
        val chapters = BookParser.parse(text, styleAware = true)
        assertEquals("第一章 概述", chapters.last().title)
        val toc = chapters.flatMap { it.paras }.filter { it.role == DbValues.ROLE_TOC }
        assertTrue(toc.isNotEmpty())
        assertTrue(toc.none { it.text.contains("〔标题〕") })
    }

    @Test
    fun p3a_tocTailPage_suppressesTitleMark() {
        // 单测 13（P3a-hotfix）：OCR 目录尾页点线条目常丢页码、不足 RE_TOC_LINE 的
        // 3 条阈值 → 漏判 tocLike，残留条目「第十二章权利的行使」12pt ≥ 阈值拿到假
        // 字号证据成假章（真书 mfzz p31 E2E 实录）——页内点线尾部条目 ≥2 → 本页不发
        // 〔标题〕标 → A 档命中无证据被拦
        val page = PageOut(
            1, tocLike = false, rawChars = 200, puaCount = 0,
            lineCount = 4, shortLineCount = 0,
            paras = listOf(
                Para("第十二章权利的行使", size = 12f),
                Para("-—权利行使自由与限制·························", size = 5f),
                Para("主要参考书目 .....................", size = 10f),
                Para("索弓 ·•··•··•··•··•··•··•··•··•··•··", size = 9.9f),
            ),
            firstLine = null, lastLine = null,
        )
        val aware = BookParser.parse(
            PdfExtractResult(listOf(page), chainStats, scanned = false).assembleText(styleAware = true),
            styleAware = true,
        )
        assertEquals("目录残留假章应被拦截", "全文", aware.single().title)
    }

    @Test
    fun p3a_tocTailSingleEntry_keepsMark() {
        // 单测 14（P3a-hotfix 对照）：仅 1 条行尾点线（正文页省略号常态）→ 守卫不触发，
        // 大字真章照常拿证据成章——锁死守卫的误伤边界
        val page = PageOut(
            1, tocLike = false, rawChars = 200, puaCount = 0,
            lineCount = 2, shortLineCount = 0,
            paras = listOf(
                Para("第一章 私法绪论", size = 19f),
                Para("他说到此处………", size = 10f),
            ),
            firstLine = null, lastLine = null,
        )
        val aware = BookParser.parse(
            PdfExtractResult(listOf(page), chainStats, scanned = false).assembleText(styleAware = true),
            styleAware = true,
        )
        assertEquals("一章不受点线守卫误伤", "第一章 私法绪论", aware.single().title)
    }

    @Test
    fun p3a_bodySizeNaN_safeDegradation() {
        // 单测 15（质检建议）：bodySize=NaN → threshold=NaN → `size >= NaN` 恒 false
        // （IEEE 754）→ 不打标 → A 档命中无证据被拦。锁死注释声明的安全退化方向，
        // 防未来把 `>=` 重构成 `>`/`!=`/isNaN 分支后行为漂移而无报警
        val stats = chainStats.copy(bodySize = Float.NaN)
        val big = PLine("第一章 私法绪论", x0 = 50f, x1 = 540f, y0 = 100f, size = 19f)
        assertTrue(!assembleAwareText(listOf(big), stats = stats).startsWith("〔标题〕"))
        val chapters = BookParser.parse(
            PdfExtractResult(
                listOf(PageOut(1, tocLike = false, rawChars = 50, puaCount = 0, lineCount = 1,
                    shortLineCount = 0, paras = listOf(Para("第一章 私法绪论", size = 19f)),
                    firstLine = null, lastLine = null)),
                stats, scanned = false,
            ).assembleText(styleAware = true),
            styleAware = true,
        )
        assertEquals("NaN 无信号 → 命中降正文（整书单章）", "全文", chapters.single().title)
    }

    @Test
    fun p3a_mixedPage_tocTailGuardSuppressesRealTitleToo() {
        // 单测 16（质检建议）：混合页（≥2 条点线 + 真章大字）——守卫按页粒度，
        // 真章同样被抑制。锁定该设计取舍（真章首页无点线，此场景现实中=目录尾页
        // 残留夹真章命中的罕见形态；宁可漏章交给用户补，不可放假章）
        val page = PageOut(
            1, tocLike = false, rawChars = 200, puaCount = 0,
            lineCount = 3, shortLineCount = 0,
            paras = listOf(
                Para("第一章 私法绪论", size = 19f),
                Para("主要参考书目 .....................", size = 10f),
                Para("索弓 ·•··•··•··•··•··•··•··•··•··•··", size = 9.9f),
            ),
            firstLine = null, lastLine = null,
        )
        val aware = BookParser.parse(
            PdfExtractResult(listOf(page), chainStats, scanned = false).assembleText(styleAware = true),
            styleAware = true,
        )
        assertEquals("混合页按页抑制（设计取舍）", "全文", aware.single().title)
    }

    @Test
    fun p3a_prefixLeak_throwsIllegalState() {
        // 单测 11：post-condition 运行时强制（internal 直调——正常路径剥前缀在先，无法从
        // 公开入口构造泄漏）
        val bad = listOf(
            com.studyfriend.app.data.importer.ParsedChapter(
                "t",
                listOf(ParsedPara("a\n〔标题〕b", DbValues.ROLE_BODY)),
            ),
        )
        assertThrows(IllegalStateException::class.java) { BookParser.assertNoMarkLeak(bad) }
    }

    // ---- P3b-2 pageNo 链路（〔页N〕页边界标记） ----

    @Test
    fun p3b2_pageMarks_strippedAndPageNoAssigned() {
        // assembleText 真实形态：标记独立行 + 与本页首段同块；同页后续段落无标记顺延继承
        val text = listOf(
            "〔页3〕", "第一章 总则", "",
            "正文甲。", "",
            "〔页7〕", "第二章 分则", "",
            "正文乙一。", "",
            "正文乙二。",
        ).joinToString("\n")
        val chapters = BookParser.parse(text)
        assertEquals(2, chapters.size)
        assertEquals("第一章 总则", chapters[0].title)
        assertEquals("正文甲。", chapters[0].paras[0].text)
        assertEquals(3, chapters[0].paras[0].pageNo)
        assertEquals(7, chapters[1].paras[0].pageNo)
        assertEquals("无标记块顺延继承最近页号", 7, chapters[1].paras[1].pageNo)
    }

    @Test
    fun p3b2_noMarks_txtPath_pageNoNull() {
        val chapters = BookParser.parse("第一章 甲\n\n正文。")
        assertNull("TXT 路径无标记，pageNo 恒 null", chapters[0].paras[0].pageNo)
    }

    @Test
    fun p3b2_pageMark_onTocPage_entriesCarryPageNo() {
        // 目录页也插标记（剥标先于目录识别，不干扰 isTocBlock/锚点）
        val text = "〔页2〕\n目录\n条目甲……1\n条目乙……5\n\n〔页5〕\n第一章 总则\n\n正文。"
        val chapters = BookParser.parse(text)
        assertEquals(listOf("目录", "第一章 总则"), chapters.map { it.title })
        val toc = chapters[0].paras
        assertTrue(toc.all { it.role == DbValues.ROLE_TOC })
        assertEquals(2, toc[0].pageNo)
        assertEquals("目录页条目重组仍按触发行分段", listOf("条目甲……1", "条目乙……5"), toc.map { it.text })
        assertEquals(5, chapters[1].paras[0].pageNo)
    }

    @Test
    fun p3b2_pageMark_beforeFootnote_footnoteStillDetected() {
        // 回归护栏：页首段是脚注时，剥标后 firstLine 仍以〔脚注〕开头（不被标记挡住）
        val text = "〔页4〕\n〔脚注〕参见前注。\n\n〔页5〕\n第一章 甲\n\n正文。"
        val chapters = BookParser.parse(text)
        val all = chapters.flatMap { it.paras }
        assertEquals(DbValues.ROLE_FOOTNOTE, all[0].role)
        assertEquals("参见前注。", all[0].text)
        assertEquals(4, all[0].pageNo)
    }

    @Test
    fun p3b2_pageMark_customRegex_stillMatches() {
        // 目录重切（custom）路径：剥标先于 custom 匹配
        val text = "〔页2〕\n第一单元 民法\n\n正文。"
        val chapters = BookParser.parse(text, customTitleRegex = "^第一单元")
        assertEquals("第一单元 民法", chapters[0].title)
        assertEquals(2, chapters[0].paras[0].pageNo)
    }

    @Test
    fun p3b2_pageMark_blindCut_parasCarryPageNo() {
        val text = "〔页1〕\n段落甲。\n\n〔页9〕\n段落乙。"
        val chapters = BookParser.parse(text)
        assertEquals("全文", chapters[0].title)
        assertEquals(listOf(1, 9), chapters[0].paras.map { it.pageNo })
    }

    // ---- P6c-E 〔目录〕页级标记（tocLike 页直通条目重组） ----

    @Test
    fun p6cE_tocMarkPage_entriesRegrouped_noFakeChapter() {
        // shpc p20 E2E 实录形态：OCR 目录页点线漂移成行首「..」、页码独立成行、
        // 「第九章」误读「第力章」——isTocLikeLine 整页只命中孤页码行，isTocBlock
        // 0.5 占比必不过（实测 3 个 tocLike 页仅救出 2 个孤页码段）。〔目录〕标记
        // 让整块直通 tocEntries 累积重组，且目录条目不开假章。
        val tocPage = PageOut(
            20, tocLike = true, rawChars = 300, puaCount = 0,
            lineCount = 6, shortLineCount = 0,
            paras = listOf(
                Para("第力章惩罚性赔偿…"),
                Para("(341)"),
                Para("第一节填补性损害赔偿与惩罚性赔偿"),
                Para(".. (355)"),
                Para("第二节美国法上的惩罚性赔偿"),
                Para("(359)"),
            ),
            firstLine = null, lastLine = null,
        )
        val bodyPage = PageOut(
            31, tocLike = false, rawChars = 500, puaCount = 0,
            lineCount = 2, shortLineCount = 0,
            paras = listOf(Para("第一章 私法绪论", size = 19f), Para("正文内容。")),
            firstLine = null, lastLine = null,
        )
        val text = PdfExtractResult(listOf(tocPage, bodyPage), chainStats, scanned = false)
            .assembleText(styleAware = true)
        assertTrue("tocLike 页应打〔目录〕标记", text.contains(BookParser.TOC_BLOCK_MARK))
        val chapters = BookParser.parse(text, styleAware = true)
        // 无「第力章」假章：目录条目块不产标题命中。本例目录块 dotLike 恰达 3 行
        // 构成密度兜底 tocRegion，条目按既有设计顺延并入下一命中章段落头部
        assertEquals(listOf("第一章 私法绪论"), chapters.map { it.title })
        val paras = chapters[0].paras
        val tocParas = paras.filter { it.role == DbValues.ROLE_TOC }
        assertTrue(tocParas.isNotEmpty())
        // 目录页段落一律 ROLE_TOC（「第力章」误读行挂在条目段首，不丢内容）
        assertTrue(tocParas.all { it.pageNo == 20 })
        val joined = tocParas.joinToString("\n") { it.text }
        assertTrue(joined.contains("第力章") && joined.contains("341"))
        assertTrue(joined.contains("第一节") && joined.contains("355"))
        assertTrue(joined.contains("第二节") && joined.contains("359"))
        // 正文页不受影响
        assertEquals(DbValues.ROLE_BODY, paras.last().role)
    }

    @Test
    fun p6cE_nonTocPage_noMark_isTocBlockPathUnchanged() {
        // 非 tocLike 页不打〔目录〕标；tocRegion 外的散条目块（dots=2 不足密度兜底
        // 下限 3）仍走 isTocBlock 块级兜底（P6c-D 行为不变）。styleAware=false 口径：
        // TXT 路径同样不受〔目录〕标记影响
        val tocBlock = PageOut(
            5, tocLike = false, rawChars = 200, puaCount = 0,
            lineCount = 2, shortLineCount = 0,
            paras = listOf(Para("条目甲……3"), Para("条目乙……7")),
            firstLine = null, lastLine = null,
        )
        val bodyPage = PageOut(
            6, tocLike = false, rawChars = 100, puaCount = 0,
            lineCount = 2, shortLineCount = 0,
            paras = listOf(Para("第一章 甲"), Para("正文。")),
            firstLine = null, lastLine = null,
        )
        val text = PdfExtractResult(listOf(tocBlock, bodyPage), chainStats, scanned = false)
            .assembleText(styleAware = false)
        assertFalse(text.contains(BookParser.TOC_BLOCK_MARK))
        val chapters = BookParser.parse(text)
        // 散条目块在第一个标题命中前 → 落「开篇」章但 role=TOC 不混正文（P6c-D 既有行为）
        assertEquals(listOf("开篇", "第一章 甲"), chapters.map { it.title })
        assertEquals(DbValues.ROLE_TOC, chapters[0].paras[0].role)
        assertEquals(DbValues.ROLE_BODY, chapters[1].paras.last().role)
    }

    @Test
    fun p6cE_tocMarkLoneBlock_dropped_noLeak() {
        // 防御：〔目录〕孤块（剥标后无内容）被丢弃，标记不泄漏进段文本
        val chapters = BookParser.parse("〔页2〕\n〔目录〕\n\n〔页5〕\n第一章 甲\n\n正文。")
        assertEquals(listOf("第一章 甲"), chapters.map { it.title })
        assertTrue(chapters[0].paras.all { !it.text.contains(BookParser.TOC_BLOCK_MARK) })
    }
}
