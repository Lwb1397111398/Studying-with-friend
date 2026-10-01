package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 段落组装规则（OPT-E）：字号/行距/缩进/句末四类信号决定断段还是续接 */
class ParagraphAssemblerTest {

    private val stats = DocStats(bodySize = 10f, left = 50f, right = 545f, pitchThreshold = null)

    private fun line(
        text: String,
        x0: Float = 50f,
        x1: Float = 540f,
        y0: Float = 0f,
        size: Float = 10f,
    ) = PLine(text, x0, x1, y0, size)

    /** 连续正文行：等行距、满宽 */
    private fun bodyLines(vararg texts: String, startY: Float = 100f, dy: Float = 14f) =
        texts.mapIndexed { i, t -> line(t, y0 = startY + i * dy) }

    @Test
    fun continuousLines_mergeIntoOneParagraph() {
        val lines = bodyLines("第一行内容收尾。", "第二行接着说。", "第三行说完了。")
        val s = stats.copy(pitchThreshold = 20f) // dy=14 < 20 → 续接
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(1, paras.size)
        assertTrue(paras[0].text.contains("第一行") && paras[0].text.contains("第三行"))
    }

    @Test
    fun leadingPunctuation_mergesBackIntoPreviousParagraph() {
        val lines = bodyLines("他说了很多话", "，但是都没说完。", "然后继续。")
        // pitch=20 让"然后继续。"走行距续接，只验证禁则标点并回本身
        val paras = ParagraphAssembler.assemble(lines, stats.copy(pitchThreshold = 20f))
        assertEquals("他说了很多话，但是都没说完。然后继续。", paras.single().text)
    }

    @Test
    fun fontSizeJump_titleIsolatedAsOwnParagraph() {
        val lines = bodyLines("正文第一句讲完了。", "正文第二句也讲完了。") +
            line("第二章 民法的法源", size = 13f, y0 = 200f) +
            bodyLines("标题后的正文开始了。", startY = 230f)
        val s = stats.copy(pitchThreshold = 20f)
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(3, paras.size)
        assertEquals("第二章 民法的法源", paras[1].text)
        assertTrue(paras[2].text.startsWith("标题后"))
    }

    @Test
    fun twoTitleLines_closeTogether_foldIntoOneTitle() {
        val lines = bodyLines("正文收尾句。") +
            line("第二章 民法的法源及", size = 13f, y0 = 130f) +
            line("法律的适用", size = 13f, y0 = 156f) // dy=26 ≤ 2.2×13=28.6 → 折行合并
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
        assertEquals("第二章 民法的法源及法律的适用", paras[1].text)
    }

    @Test
    fun lineGapBeyondPitch_breaksParagraph() {
        val lines = bodyLines("上一段说完了。", startY = 100f) +
            bodyLines("下一段开始。", startY = 140f) // dy=40 > 20
        val s = stats.copy(pitchThreshold = 20f)
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(2, paras.size)
    }

    @Test
    fun firstLineIndent_breaksParagraph_atSentenceEnd() {
        val lines = bodyLines("上一段说完了。") +
            line("新的一段缩进起步，说完了。", x0 = 70f, y0 = 114f) // 70 ≥ 50+0.8×10=58
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
    }

    @Test
    fun indent_withoutSentenceEnd_keepsMerging() {
        val lines = bodyLines("上一段话还没有说完，") +
            line("缩进但上句未完，继续写。", x0 = 70f, y0 = 114f)
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(1, paras.size)
    }

    @Test
    fun shortLine_breaksParagraph_atSentenceEnd() {
        val lines = listOf(line("这一段收尾在短行。", x1 = 200f)) +
            line("新段从这里开始。", y0 = 114f) // 200 < 545−15 且上句句末
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
    }

    @Test
    fun noPitchSignal_sentenceEndAloneBreaksParagraph() {
        // pitchThreshold=null：满宽续接行也必须在句末断（兜底臂）
        val lines = bodyLines("上一段说完了。", "新一段紧接着排版。")
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
    }

    @Test
    fun endsSentence_variousClosings() {
        assertTrue(endsSentence("他说完了。"))
        assertTrue(endsSentence("他说完了？"))
        assertTrue(endsSentence("他引用道："))
        assertTrue(endsSentence("他说完！”"))
        assertTrue(endsSentence("参见第十二条〔3〕"))
        assertTrue(endsSentence("他主张[12]"))
        assertTrue(endsSentence("第一条　原则①"))
        assertFalse(endsSentence("话还没说完，"))
        assertFalse(endsSentence("王泽鉴说"))
        assertFalse(endsSentence(""))
    }

    @Test
    fun softJoin_cjkDirect_asciiSpaced() {
        assertEquals("中文直连", softJoin("中文", "直连"))
        assertEquals("has space", softJoin("has", "space"))
        assertEquals("混合 text", softJoin("混合", "text"))
    }

    @Test
    fun joinTexts_foldsListWithSoftJoin() {
        assertEquals("甲、乙、丙", joinTexts(listOf("甲、", "乙、", "丙")))
        assertEquals("a b", joinTexts(listOf("a", "b")))
    }
}
