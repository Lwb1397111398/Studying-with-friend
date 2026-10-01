package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 页面清洗（OPT-E）：目录直通、页眉/页码删除、脚注收集、几何统计、跨页续接 */
class PdfCleanerTest {

    private val stats = DocStats(bodySize = 10f, left = 50f, right = 545f, pitchThreshold = 20f)
    private val dim = 595f to 842f

    private fun line(
        text: String,
        x0: Float = 50f,
        x1: Float = 540f,
        y0: Float = 100f,
        size: Float = 10f,
    ) = PLine(text, x0, x1, y0, size)

    // ---------------------------------------------------------------- docStats

    @Test
    fun docStats_bodySize_isCharWeightedSizeMode() {
        // 正文 9.5pt（多字符）+ 页眉 8pt（少量字符）→ 众数应落在 9.5
        val pages = listOf(
            listOf(line("正文行占绝大多数字符", size = 9.5f)) +
                List(5) { line("正文第 $it 行内容足够长", y0 = 120f + it * 16f, size = 9.5f) } +
                line("页眉民法总则", y0 = 30f, size = 8f),
        )
        val s = PdfCleaner.docStats(pages, listOf(dim))
        assertEquals(9.5f, s.bodySize, 0.26f)
    }

    @Test
    fun docStats_pitchValley_setsThresholdBetweenPeakAndValley() {
        // 10 个行距 14 + 2 个段间空隙 25 → 谷点 25，阈值 = min(1.5×14, 25) = 21
        val lines = List(12) { i ->
            line("第 $i 行内容", y0 = 100f + i * (if (i < 10) 14f else 25f))
        }
        val s = PdfCleaner.docStats(listOf(lines), listOf(dim))
        assertEquals(21f, s.pitchThreshold!!, 1.0f)
    }

    @Test
    fun docStats_fewSamplesNoValley_nullThreshold() {
        // 3 个相同行距、无谷点 → 样本 3~7 无信号
        val lines = List(4) { i -> line("第 $i 行内容", y0 = 100f + i * 15f) }
        val s = PdfCleaner.docStats(listOf(lines), listOf(dim))
        assertNull(s.pitchThreshold)
    }

    // ---------------------------------------------------------------- clean

    @Test
    fun clean_removesHeaderByBandAndSize() {
        val page = listOf(
            line("民法总则 · 第一章", y0 = 30f, size = 8f),   // 顶部条带 + 小字号 → 页眉
            line("第一节 民法的法源", y0 = 100f, x1 = 400f),
            line("正文内容继续往下写，讲的是法源问题。", y0 = 116f, x1 = 520f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        val texts = out[0].paras.map { it.text }.joinToString("\n")
        assertFalse(texts.contains("民法总则 · 第一章"))
        assertTrue(texts.contains("民法的法源"))
    }

    @Test
    fun clean_removesPageNumLine_anyPosition() {
        val page = listOf(
            line("正文一段话讲完了。", y0 = 100f, x1 = 500f),
            line("12", y0 = 420f, x0 = 290f, x1 = 305f),
            line("正文又一段话讲完了。", y0 = 500f, x1 = 500f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertFalse(out[0].paras.any { it.text == "12" })
        assertEquals(2, out[0].paras.size)
    }

    @Test
    fun clean_tocPage_passthroughKeepsEntriesAndPageNum() {
        val page = listOf(
            line("第一章 总论", y0 = 100f, x1 = 300f),
            line("第二章 民法的法源………………5", y0 = 130f, x1 = 400f),
            line("第三章 法律行为………………18", y0 = 160f, x1 = 400f),
            line("第四章 代理………………32", y0 = 190f, x1 = 400f),
            line("5", y0 = 220f, x0 = 290f, x1 = 305f), // 孤页码：目录条目触发器，保留
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertTrue(out[0].tocLike)
        assertEquals(0, out[0].lineCount) // PageSelector 据此跳过目录页
        assertEquals(0, out[0].shortLineCount)
        assertTrue(out[0].paras.any { it.text.contains("………………5") })
        assertTrue(out[0].paras.any { it.text == "5" })
    }

    @Test
    fun clean_footerSmallLines_collectedAsFootnotePara() {
        val page = listOf(
            line("正文大段内容讲完了。", y0 = 300f, x1 = 520f),
            line("① 参见王泽鉴《民法总则》第 12 页。", y0 = 700f, x1 = 500f, size = 7f),
            line("② 同上注。", y0 = 716f, x1 = 500f, size = 7f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        val fn = out[0].paras.filter { it.footnote }
        assertEquals(1, fn.size) // 连续脚注行合并成一条脚注段
        assertTrue(fn[0].text.contains("参见王泽鉴"))
        assertTrue(fn[0].text.contains("同上注"))
    }

    @Test
    fun clean_yiPunctuation_normalized_whenNoRealYiText() {
        val borrowed = "" + Char(0xA3AC) // 坏字体把"，"映射成彝文区 ꎬ
        val page = listOf(
            line("第一句说完了${borrowed}第二句也完了${Char(0xA3AE)}", y0 = 100f, x1 = 520f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        val t = out[0].paras.single().text
        assertTrue(t.contains("，"))
        assertFalse(t.contains(Char(0xA3AC)))
    }

    @Test
    fun clean_yiPunctuation_kept_whenRealYiTextPresent() {
        val borrowed = "" + Char(0xA3AC)
        val realYi = "" + Char(0xA000) // 真彝文音节出现 → 不许整页替换
        val page = listOf(line("混合${borrowed}内容${realYi}页", y0 = 100f, x1 = 520f))
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertTrue(out[0].paras.single().text.contains(borrowed))
    }

    @Test
    fun clean_countsPuaInRawText() {
        val page = listOf(line("坏字${Char(0xE000)}页", y0 = 100f, x1 = 300f))
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertEquals(1, out[0].puaCount)
    }

    // ---------------------------------------------------------------- crossPageMerge

    private fun pageOut(
        pageNum: Int,
        paras: List<Para>,
        lastLine: PLine?,
        firstLine: PLine? = null,
        tocLike: Boolean = false,
    ) = PageOut(
        pageNum, tocLike, rawChars = 100, puaCount = 0,
        lineCount = paras.size, shortLineCount = 0,
        paras = paras, firstLine = firstLine ?: paras.firstOrNull()?.let { PLine(it.text, 50f, 300f, 100f, 10f) },
        lastLine = lastLine,
    )

    @Test
    fun crossPageMerge_joinsWhenSentenceOpen_andLineReachesRightEdge() {
        val p1 = pageOut(
            1,
            listOf(Para("上一页末段话说了一半，还在继续说")),
            lastLine = PLine("上一页末段话说了一半，还在继续说", 50f, 540f, 700f, 10f),
        )
        val p2 = pageOut(
            2,
            listOf(Para("下一页开头的接续内容。"), Para("下一段另起。")),
            lastLine = null,
            firstLine = PLine("下一页开头的接续内容。", 50f, 400f, 100f, 10f),
        )
        PdfCleaner.crossPageMerge(listOf(p1, p2), stats)
        assertTrue(p1.paras[0].text.endsWith("接续内容。"))
        assertEquals(1, p2.paras.size) // 首段被并走，只剩第二段
        assertEquals("下一段另起。", p2.paras[0].text)
    }

    @Test
    fun crossPageMerge_indentStartOnNextPage_blocksMerge() {
        val p1 = pageOut(
            1,
            listOf(Para("上一页末段没有句末收尾")),
            lastLine = PLine("上一页末段没有句末收尾", 50f, 540f, 700f, 10f),
        )
        val p2 = pageOut(
            2,
            listOf(Para("下一页是缩进的新段落。")),
            lastLine = null,
            firstLine = PLine("下一页是缩进的新段落。", 70f, 400f, 100f, 10f), // 缩进反证
        )
        PdfCleaner.crossPageMerge(listOf(p1, p2), stats)
        assertEquals(1, p1.paras.size)
        assertEquals(1, p2.paras.size)
        assertTrue(p2.paras[0].text.startsWith("下一页"))
    }

    @Test
    fun crossPageMerge_titleSizeOnNextPage_blocksMerge() {
        val p1 = pageOut(
            1,
            listOf(Para("上一页末段话")),
            lastLine = PLine("上一页末段话", 50f, 540f, 700f, 10f),
        )
        val p2 = pageOut(
            2,
            listOf(Para("第二章 新的一章标题")),
            lastLine = null,
            firstLine = PLine("第二章 新的一章标题", 50f, 400f, 100f, 13f), // 标题字号反证
        )
        PdfCleaner.crossPageMerge(listOf(p1, p2), stats)
        assertTrue(p2.paras[0].text.startsWith("第二章"))
    }

    @Test
    fun crossPageMerge_tocPageOnEitherSide_neverMerges() {
        val toc = pageOut(
            1,
            listOf(Para("第三章 法律行为………………18")),
            lastLine = PLine("第三章 法律行为………………18", 50f, 540f, 700f, 10f),
            tocLike = true,
        )
        val body = pageOut(
            2,
            listOf(Para("正文开头。")),
            lastLine = null,
            firstLine = PLine("正文开头。", 50f, 400f, 100f, 10f),
        )
        PdfCleaner.crossPageMerge(listOf(toc, body), stats)
        assertEquals(1, toc.paras.size)
        assertEquals("正文开头。", body.paras.single().text)
    }

    @Test
    fun crossPageMerge_lastLineShortOfRightEdge_noMerge() {
        val p1 = pageOut(
            1,
            listOf(Para("上一页这段话没有收尾")),
            lastLine = PLine("上一页这段话没有收尾", 50f, 300f, 700f, 10f), // x1 距右边距 245pt
        )
        val p2 = pageOut(
            2,
            listOf(Para("下一页开头。")),
            lastLine = null,
            firstLine = PLine("下一页开头。", 50f, 400f, 100f, 10f),
        )
        PdfCleaner.crossPageMerge(listOf(p1, p2), stats)
        assertEquals("下一页开头。", p2.paras.single().text)
        assertEquals(1, p1.paras.size)
    }

    @Test
    fun clean_removesHeaderSameSizeWithChapNameAndTrailingPageNum() {
        // E2E 实证（《民法总则》偶数页）：页眉字号 pdfbox 报与正文同 → 字号判据漏，
        // 靠"行首章名样 + 行尾页码"文本特征兜底
        val page = listOf(
            line("笫一章私法绪论 7", y0 = 48f, x0 = 260f, x1 = 345f, size = 10f),
            line("正文从这一行开始，讲私法绪论的内容。", y0 = 110f, x1 = 520f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertFalse(out[0].paras.any { it.text.startsWith("笫一章") })
        assertTrue(out[0].paras.single().text.contains("正文从这一行开始"))
    }

    @Test
    fun clean_keepsBodyChapTitleInBandBelow() {
        // 反证：条带下方的真章标题不许被文本特征误删
        val page = listOf(
            line("第一章 私法绪论", y0 = 200f, x0 = 230f, x1 = 360f, size = 12f),
            line("正文内容跟在标题后面。", y0 = 230f, x1 = 520f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertTrue(out[0].paras.any { it.text.startsWith("第一章") })
    }
}
