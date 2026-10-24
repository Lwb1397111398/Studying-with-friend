package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KIT 成品书直入协议：元数据块提取/剥离、章表锚定切章、role/pageNo/FIGURE 落段 */
class KitBookParserTest {

    /** 模拟 bookocr-kit pack 产出：头部协议块 + 〔页N〕标记正文（含前置/目录/图/脚注） */
    private fun sampleTxt(): String {
        val meta = "{\"kit\":1,\"title\":\"测试书\",\"pages\":6,\"chapters\":[" +
            "{\"t\":\"开篇\",\"p\":1,\"lv\":1,\"r\":\"FRONT\",\"a\":\"开篇\"}," +
            "{\"t\":\"第二版序\",\"p\":2,\"lv\":1,\"r\":\"FRONT\",\"a\":\"第二版序\"}," +
            "{\"t\":\"目录\",\"p\":3,\"lv\":1,\"r\":\"TOC\",\"a\":\"目录\"}," +
            "{\"t\":\"第一章 总则\",\"p\":4,\"lv\":1,\"r\":\"BODY\",\"a\":\"第一章 总则\"}," +
            "{\"t\":\"第二章 分则\",\"p\":6,\"lv\":1,\"r\":\"BODY\",\"a\":\"第二章 分则\"}" +
            "],\"v\":\"bookocr-kit 1.1\"}"
        return listOf(
            "${KitBookParser.META_BEGIN}$meta${KitBookParser.META_END}",
            "〔页1〕",
            "版权页文字。",
            "",
            "〔页2〕",
            "第二版序",
            "",
            "序言正文。",
            "",
            "〔页3〕",
            "目 录",
            "",
            "第一章 总则……4",
            "第二章 分则……6",
            "",
            "〔页4〕",
            "第一章 总则",
            "",
            "总则正文一段。",
            "〔图：立法流程图〕",
            "",
            "〔页5〕",
            "〔脚注〕本条为2021年修正条文。",
            "",
            "〔页6〕",
            "第二章 分则",
            "",
            "分则正文。",
        ).joinToString("\n")
    }

    // ---- extract / stripMeta ----

    @Test
    fun extract_fullMeta() {
        val meta = KitBookParser.extract(sampleTxt())
        assertEquals("测试书", meta?.title)
        assertEquals(6, meta?.pages)
        assertEquals("bookocr-kit 1.1", meta?.version)
        assertEquals(5, meta?.chapters?.size)
        val ch4 = meta?.chapters?.get(3)
        assertEquals("第一章 总则", ch4?.title)
        assertEquals(4, ch4?.page)
        assertEquals(1, ch4?.level)
        assertEquals("BODY", ch4?.role)
        assertEquals("第一章 总则", ch4?.anchor)
    }

    @Test
    fun extract_failures() {
        // 无协议块（普通 txt）→ null，调用方回退通用解析
        assertNull(KitBookParser.extract("第一章 引论\n\n正文。"))
        // JSON 损坏 → null
        assertNull(KitBookParser.extract("${KitBookParser.META_BEGIN}{bad${KitBookParser.META_END}"))
        // 只有开始标记没有结束标记 → null
        assertNull(KitBookParser.extract("${KitBookParser.META_BEGIN}{\"kit\":1}"))
        // kit 版本不认（未来 v2）→ null
        assertNull(
            KitBookParser.extract(
                "${KitBookParser.META_BEGIN}{\"kit\":2,\"chapters\":[{\"t\":\"甲\",\"p\":1}]}${KitBookParser.META_END}",
            ),
        )
        // 章表为空 → null
        assertNull(
            KitBookParser.extract(
                "${KitBookParser.META_BEGIN}{\"kit\":1,\"chapters\":[]}${KitBookParser.META_END}",
            ),
        )
    }

    @Test
    fun stripMeta_removesBlockAndKeepsBody() {
        val stripped = KitBookParser.stripMeta(sampleTxt())
        assertTrue(!stripped.contains(KitBookParser.META_BEGIN))
        assertTrue(stripped.startsWith("〔页1〕"))
        // 元数据行连同换行一起剥，不残留头部空块
        assertEquals("〔页1〕", stripped.lineSequence().first())
    }

    @Test
    fun stripMeta_noBlock_returnsRaw() {
        assertEquals("正文", KitBookParser.stripMeta("正文"))
    }

    // ---- parse：章表锚定切章 ----

    @Test
    fun parse_chapterListAndRoles() {
        val meta = KitBookParser.extract(sampleTxt())!!
        val chapters = KitBookParser.parse(KitBookParser.stripMeta(sampleTxt()), meta)
        assertEquals(listOf("开篇", "第二版序", "目录", "第一章 总则", "第二章 分则"), chapters.map { it.title })
        // 开篇/第二版序=FRONT、目录=TOC、正文=BODY；锚行（标题行）从正文剔除
        val paras = chapters.associate { it.title to it.paras }
        assertEquals("版权页文字。", paras.getValue("开篇").single().text)
        assertEquals(DbValues.ROLE_FRONT, paras.getValue("开篇").single().role)
        assertEquals(DbValues.ROLE_TOC, paras.getValue("目录").single().role)
        // 「目 录」标题行带空格，锚「目录」靠去空白匹配命中并被剔除，条目行成 TOC 段
        assertEquals("第一章 总则……4第二章 分则……6", paras.getValue("目录").single().text)
        // 第一章段：锚行已剔（无段落文本等于章标题）、BODY 带页号
        val body = paras.getValue("第一章 总则").filter { it.role == DbValues.ROLE_BODY }
        assertTrue(body.isNotEmpty())
        assertTrue(body.none { it.text == "第一章 总则" })
        assertEquals(4, body.first().pageNo)
        assertEquals("分则正文。", paras.getValue("第二章 分则").single { it.role == DbValues.ROLE_BODY }.text)
    }

    @Test
    fun parse_tocEntryDoesNotHijackAnchor() {
        // 页3 目录条目「第一章 总则……4」与章锚同前缀，页号闸（blockPage≥p）拦住，
        // 第一章必须锚定到页4 的真标题行而非目录条目
        val meta = KitBookParser.extract(sampleTxt())!!
        val chapters = KitBookParser.parse(KitBookParser.stripMeta(sampleTxt()), meta)
        val ch1 = chapters.first { it.title == "第一章 总则" }
        // 目录条目归目录章；第一章内不得出现条目形态（……4 结尾）文本
        assertTrue(ch1.paras.none { it.text.endsWith("……4") })
        assertEquals(4, ch1.paras.firstNotNullOfOrNull { it.pageNo })
    }

    @Test
    fun parse_figureAndFootnoteParagraphs() {
        val meta = KitBookParser.extract(sampleTxt())!!
        val chapters = KitBookParser.parse(KitBookParser.stripMeta(sampleTxt()), meta)
        val ch1 = chapters.first { it.title == "第一章 总则" }
        // 〔图：…〕与正文同块：图行单独成段标 FIGURE，其余行 softMerge 成 BODY 段
        val figure = ch1.paras.single { it.role == DbValues.ROLE_FIGURE }
        assertEquals("〔图：立法流程图〕", figure.text)
        assertEquals(4, figure.pageNo)
        // 〔脚注〕协议行剥标标 FOOTNOTE（与 BookParser 同口径）
        val footnote = ch1.paras.single { it.role == DbValues.ROLE_FOOTNOTE }
        assertEquals("本条为2021年修正条文。", footnote.text)
        assertEquals(5, footnote.pageNo)
        assertEquals("总则正文一段。", ch1.paras.single { it.text == "总则正文一段。" }.text)
    }

    @Test
    fun parse_firstChapterAnchorMissFallsBackToHead() {
        // 首章（开篇 p=1）锚「开篇」在正文不存在 → 兜底从块 0 收，开头内容不丢
        val meta = KitBookParser.extract(sampleTxt())!!
        val chapters = KitBookParser.parse(KitBookParser.stripMeta(sampleTxt()), meta)
        assertEquals("版权页文字。", chapters.first().paras.single().text)
    }

    @Test
    fun parse_midChapterAnchorMissSkipsToPrevious() {
        // 第二章锚在正文缺失 → 跳章，其页内容归前一章（不按页号猜切）
        val meta = KitBookParser.extract(sampleTxt())!!
        val broken = meta.copy(
            chapters = meta.chapters.map {
                if (it.title == "第一章 总则") it.copy(anchor = "第八章 不存在") else it
            },
        )
        val chapters = KitBookParser.parse(KitBookParser.stripMeta(sampleTxt()), broken)
        assertEquals(listOf("开篇", "第二版序", "目录", "第二章 分则"), chapters.map { it.title })
        // 第一章的页4/页5 内容全部归入目录章之后的首个命中章之前——即目录章吞并
        // 页4-5（含图/脚注），内容不丢
        val toc = chapters.first { it.title == "目录" }
        assertTrue(toc.paras.any { it.text == "总则正文一段。" })
    }

    @Test
    fun parse_level2StaysInBody() {
        // lv=2 节条目不切章：标题行留正文，章表只收 1 级
        val meta = KitBookParser.extract(sampleTxt())!!
        val withSec = meta.copy(
            chapters = meta.chapters + KitChapter("第一节 基本原则", 7, 2, "BODY", "第一节 基本原"),
        )
        val text = KitBookParser.stripMeta(sampleTxt()) +
            "\n\n〔页7〕\n第一节 基本原则\n\n节内正文。\n"
        val chapters = KitBookParser.parse(text, withSec)
        assertTrue(chapters.none { it.title.startsWith("第一节") })
        // 节标题行作为正文段留在所属章（页7 归第二章范围）
        val ch2 = chapters.first { it.title == "第二章 分则" }
        assertTrue(ch2.paras.any { it.text == "第一节 基本原则" })
        assertTrue(ch2.paras.any { it.text == "节内正文。" })
    }

    @Test
    fun parse_pageMarkPageNoInheritance() {
        // 无〔页N〕标记的后续块顺延继承页号（BookParser.stripPageMarks 同协议）
        val meta = KitBookParser.extract(sampleTxt())!!
        val text = KitBookParser.stripMeta(sampleTxt()) +
            "\n\n页6 后续无标记块正文。\n"
        val chapters = KitBookParser.parse(text, meta)
        val ch2 = chapters.first { it.title == "第二章 分则" }
        assertEquals(6, ch2.paras.single { it.text == "页6 后续无标记块正文。" }.pageNo)
    }
}
