package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 视觉选页判据（OPT-E）：V1 近空白 / V2 坏字体 / V3 碎行；目录页永不入选 */
class PageSelectorTest {

    private fun page(
        pageNum: Int,
        rawChars: Int = 500,
        pua: Int = 0,
        lines: Int = 20,
        shortLines: Int = 0,
        tocLike: Boolean = false,
    ) = PageOut(
        pageNum, tocLike, rawChars = rawChars, puaCount = pua,
        lineCount = lines, shortLineCount = shortLines,
        paras = listOf(Para("内容")), firstLine = null, lastLine = null,
    )

    @Test
    fun v1_blankPage_selected() {
        val sel = PageSelector.select(listOf(page(1, rawChars = 0), page(2)), scanned = false)
        val pages = (sel as PageSelector.Selection.Pages).pages
        assertEquals(listOf(1), pages)
    }

    @Test
    fun v2_puaPage_selected() {
        val sel = PageSelector.select(listOf(page(1, pua = 3), page(2, pua = 1)), scanned = false)
        assertEquals(listOf(1), (sel as PageSelector.Selection.Pages).pages)
    }

    @Test
    fun v3_shatteredPage_selected_byShortLineRatio() {
        val sel = PageSelector.select(
            listOf(page(1, lines = 10, shortLines = 5), page(2, lines = 10, shortLines = 4)),
            scanned = false,
        )
        assertEquals(listOf(1), (sel as PageSelector.Selection.Pages).pages)
    }

    @Test
    fun normalPage_neverSelected() {
        val sel = PageSelector.select(listOf(page(1), page(2)), scanned = false)
        assertTrue(sel is PageSelector.Selection.None)
    }

    @Test
    fun tocPage_neverSelected_evenWhenShattered() {
        // 目录页全是短行：若不豁免必然触发 V3，视觉替换会毁掉目录资产（评审 must_fix）
        val sel = PageSelector.select(
            listOf(page(1, tocLike = true, lines = 30, shortLines = 30)),
            scanned = false,
        )
        assertTrue(sel is PageSelector.Selection.None)
    }

    @Test
    fun mixedPages_skipToc_pickBroken() {
        val sel = PageSelector.select(
            listOf(
                page(1, tocLike = true, lines = 30, shortLines = 30),
                page(2, rawChars = 0),
                page(3, pua = 5),
                page(4),
            ),
            scanned = false,
        )
        assertEquals(listOf(2, 3), (sel as PageSelector.Selection.Pages).pages)
    }

    @Test
    fun tooManyPickedPages_rejected() {
        val pages = (1..61).map { page(it, rawChars = 0) }
        val sel = PageSelector.select(pages, scanned = false)
        assertEquals(61, (sel as PageSelector.Selection.TooMany).totalPages)
    }

    @Test
    fun scanned_wholeBookSelected_withinLimit() {
        val pages = (1..80).map { page(it) }
        val sel = PageSelector.select(pages, scanned = true)
        assertEquals((1..80).toList(), (sel as PageSelector.Selection.Pages).pages)
    }

    @Test
    fun scanned_beyondLimit_rejected() {
        val pages = (1..81).map { page(it) }
        val sel = PageSelector.select(pages, scanned = true)
        assertEquals(81, (sel as PageSelector.Selection.TooMany).totalPages)
    }
}
