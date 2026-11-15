package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 简目假章判据测试（判据=《民法物权_Kit图包》模拟器 DB 实录数据同构）：
 * 纯简目假章整章剔除 / 混合章删简目段改名 / 法条章·真文章·附录零误杀。
 */
class PhantomTocFilterTest {

    private fun para(text: String, role: String = DbValues.ROLE_BODY) = ParsedPara(text, role)

    // ---- decide：纯简目假章 ----

    @Test
    fun purePhantom_dropped() {
        // book14 idx2「第六章　担保物权」实录形态：4 段全「第X节」目录条目
        val d = PhantomTocFilter.decide(
            "第六章　担保物权",
            listOf("第一节　概说", "第二节　抵押权", "第三节　质权", "第四节　留置权"),
        )
        assertEquals(PhantomTocFilter.Decision.DropWhole, d)
    }

    @Test
    fun purePhantomEightParas_dropped() {
        // book14 idx3「第七章　占有」实录形态：8 段全「第X节」（含 64 字拼接尾巴段，段首仍「第八节」）
        val d = PhantomTocFilter.decide(
            "第七章　占有",
            listOf(
                "第一节　概说",
                "第二节　占有的基本理论",
                "第三节　占有的分类",
                "第四节　占有的取得和消灭",
                "第五节　占有的效力",
                "第六节　占有人与恢复请求人的权利义务",
                "第七节　占有的保护",
                "第八节　准占有附录一　土地权状及登记誊本附录二　土地、建筑改良物抵押权设定契约书附录三　台湾现行“民法”（物权部分）主要",
            ),
        )
        assertEquals(PhantomTocFilter.Decision.DropWhole, d)
    }

    @Test
    fun purePhantomOverFifteenParas_kept() {
        // 简目特别长的极端书（>15 段）：判据保守放行，宁漏勿杀
        val nums = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二", "十三", "十四", "十五", "十六")
        val d = PhantomTocFilter.decide(
            "第八章　假想章",
            nums.mapIndexed { i, n -> "第${n}节　条目$i" },
        )
        assertEquals(PhantomTocFilter.Decision.Keep, d)
    }

    // ---- decide：混合章（简目+实质内容） ----

    @Test
    fun mixedChapter_entriesDroppedAndRenamed() {
        // book14 idx31「第七章　占有」实录形态：8 段简目 + 「注释」段 + 2 万字引注大段
        val bigNote = "1.（一）私法性质与公法规定 2.（二）私有财产制的伦理性" + "引注正文".repeat(700)
        val d = PhantomTocFilter.decide(
            "第七章　占有",
            listOf(
                "第一节　概说",
                "第二节　占有的基本理论",
                "第三节　占有的分类",
                "第八节　准占有附录一　土地权状及登记誊本",
                "注释",
                bigNote,
            ),
        )
        assertTrue("混合章应 DropEntries", d is PhantomTocFilter.Decision.DropEntries)
        assertEquals("注释", (d as PhantomTocFilter.Decision.DropEntries).newTitle)
    }

    // ---- decide：必须不误杀的邻居 ----

    @Test
    fun realChapter_kept() {
        // 真文章：第一段「第一节」但随后是大量非简目段（book14 idx4 294 段形态）
        val d = PhantomTocFilter.decide(
            "第一章　总　　论",
            listOf("第一节　物权法的意义和性质", "一、物权法的意义", "人类须利用物质以谋生活，物质有限。", "物权，顾名思义，系指对物的权利。"),
        )
        assertEquals(PhantomTocFilter.Decision.Keep, d)
    }

    @Test
    fun statuteChapter_kept() {
        // 法条章：「第X条」+ 条文正文，无「第X节」段（book14 idx14~24 形态）
        val d = PhantomTocFilter.decide(
            "第一章　通　　则",
            listOf("第757条　（物权法定主义）", "物权除依法律或习惯外，不得创设。", "第758条　（登记生效要件主义）", "不动产物权，依法律行为而取得、设定、丧失及变更者，非经登记，不生效力。"),
        )
        assertEquals(PhantomTocFilter.Decision.Keep, d)
    }

    @Test
    fun appendixTitle_kept() {
        val d = PhantomTocFilter.decide(
            "附录一　土地权状及登记誊本",
            listOf("〔图：土地所有权状〕这是一份土地所有权状文件", "〔图版页〕"),
        )
        assertEquals(PhantomTocFilter.Decision.Keep, d)
    }

    @Test
    fun nonChapterTitle_kept() {
        val d = PhantomTocFilter.decide("目录", listOf("第一节　概说", "第二节　抵押权"))
        assertEquals(PhantomTocFilter.Decision.Keep, d)
    }

    @Test
    fun singleSectionEntry_kept() {
        // 只有 1 段简目条目：证据不足不杀
        val d = PhantomTocFilter.decide("第三章　物权变动", listOf("第一节　基本理论"))
        assertEquals(PhantomTocFilter.Decision.Keep, d)
    }

    @Test
    fun mixedWithoutLongContent_kept() {
        // 简目段+少量非简目段但无超长实质段：不满足混合章守卫（防真章误入）——保守保留
        val d = PhantomTocFilter.decide(
            "第二章　物权通论",
            listOf("第一节　物权的意义", "第二节　物权的种类", "物权具有排他性。"),
        )
        assertEquals(PhantomTocFilter.Decision.Keep, d)
    }

    // ---- stripParsed：KIT 导入链路 ----

    @Test
    fun stripParsed_dropsWholeAndRenamesMixed() {
        val bigNote = "注释正文" + "引注".repeat(1000)
        val chapters = listOf(
            ParsedChapter("开篇", listOf(para("总序", DbValues.ROLE_FRONT))),
            ParsedChapter("目录", listOf(para("第一章 总论", DbValues.ROLE_TOC))),
            ParsedChapter(
                "第六章　担保物权",
                listOf(para("第一节　概说"), para("第二节　抵押权"), para("第三节　质权"), para("第四节　留置权")),
            ),
            ParsedChapter(
                "第一章　总　　论",
                listOf(para("第一节　物权法的意义和性质"), para("物权法旨在规范私人间关于财产上的权利义务，是为私法。"), para("物权，顾名思义，系指对物的权利。")),
            ),
            ParsedChapter(
                "第七章　占有",
                listOf(para("第一节　概说"), para("第二节　占有的基本理论"), para("注释"), para(bigNote)),
            ),
        )
        val r = PhantomTocFilter.stripParsed(chapters)
        assertEquals(4, r.chapters.size)
        assertEquals("开篇", r.chapters[0].title)
        assertEquals("目录", r.chapters[1].title)
        assertEquals("第一章　总　　论", r.chapters[2].title)
        // 混合章：简目段删净、改名
        assertEquals("注释", r.chapters[3].title)
        assertEquals(listOf("注释", bigNote), r.chapters[3].paras.map { it.text })
        assertEquals(1, r.droppedChapters)
        assertEquals(1, r.renamedChapters)
        assertEquals(6, r.droppedParas) // 假章 4 段 + 混合章 2 段简目
        assertTrue(r.note()?.contains("1 个目录页误切的假章") == true)
    }

    @Test
    fun stripParsed_cleanBook_returnsSameListAndNoNote() {
        val chapters = listOf(ParsedChapter("第一章", listOf(para("正文"))))
        val r = PhantomTocFilter.stripParsed(chapters)
        assertEquals(chapters, r.chapters)
        assertNull(r.note())
        assertEquals(0, r.droppedChapters)
    }
}
