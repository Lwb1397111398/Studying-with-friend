package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
