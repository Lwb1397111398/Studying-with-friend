package com.studyfriend.app.data.importer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 竖排检测纯逻辑单测（P6b S4）：抽样/两段式判定/投影比值（IntArray 模拟像素） */
class OcrVerticalDetectorTest {

    // ================= samplePages：每 46 页抽 1 页至多 10 页 =================

    @Test
    fun `samplePages - small book takes every page`() {
        assertEquals(listOf(1, 2, 3, 4, 5), OcrVerticalDetector.samplePages(5))
    }

    @Test
    fun `samplePages - 460 page book yields 10 pages stride 46`() {
        val pages = OcrVerticalDetector.samplePages(460)
        assertEquals(10, pages.size)
        assertEquals(listOf(1, 47, 93, 139, 185, 231, 277, 323, 369, 415), pages)
    }

    @Test
    fun `samplePages - huge book stride capped at 46`() {
        // 1000 页：stride=min(46,100)=46，(1..1000 step 46) 取前 10 = 1,47,...,415
        assertEquals(
            listOf(1, 47, 93, 139, 185, 231, 277, 323, 369, 415),
            OcrVerticalDetector.samplePages(1000),
        )
    }

    @Test
    fun `samplePages - empty book`() {
        assertTrue(OcrVerticalDetector.samplePages(0).isEmpty())
    }

    // ================= needsFullScan / fullScanSuspicious =================

    @Test
    fun `sample hit at most one page passes without full scan`() {
        val horizontal = List(10) { 0.1f }
        assertFalse(OcrVerticalDetector.needsFullScan(horizontal))
        assertFalse(OcrVerticalDetector.needsFullScan(horizontal + listOf(0.5f)))
    }

    @Test
    fun `two sample hits trigger full scan`() {
        val ratios = List(8) { 0.1f } + listOf(0.5f, 0.6f)
        assertTrue(OcrVerticalDetector.needsFullScan(ratios))
    }

    @Test
    fun `full scan below twenty percent is not suspicious`() {
        // 9/46 = 19.6% < 20%
        val ratios = List(9) { 0.5f } + List(37) { 0.1f }
        assertFalse(OcrVerticalDetector.fullScanSuspicious(ratios))
    }

    @Test
    fun `full scan at twenty percent is suspicious`() {
        // 10/46 ≈ 21.7% ≥ 20%
        val ratios = List(10) { 0.5f } + List(36) { 0.1f }
        assertTrue(OcrVerticalDetector.fullScanSuspicious(ratios))
    }

    @Test
    fun `full scan of empty list is not suspicious`() {
        assertFalse(OcrVerticalDetector.fullScanSuspicious(emptyList()))
    }

    // ================= verdict：SUSPECT / VERTICAL 分带 =================

    @Test
    fun `verdict bands`() {
        assertEquals("HORIZONTAL", OcrVerticalDetector.verdict(0.30f))
        assertEquals("SUSPECT", OcrVerticalDetector.verdict(0.40f))
        assertEquals("VERTICAL", OcrVerticalDetector.verdict(0.50f))
    }

    // ================= pageRatioFromPixels：投影比值（纯数组模拟） =================

    @Test
    fun `blank page ratio is zero`() {
        val w = 100
        val h = 200
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        assertEquals(0f, OcrVerticalDetector.pageRatioFromPixels(px, w, h), 1e-6f)
    }

    @Test
    fun `vertical stripes yield huge ratio`() {
        // 竖墨条纹（每 8 列 2 条全高墨线）：列投影剧烈波动、行投影均匀 → vH≈0 → ratio 巨大
        val w = 160
        val h = 200
        val px = IntArray(w * h) { i ->
            val x = i % w
            if (x % 8 < 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val ratio = OcrVerticalDetector.pageRatioFromPixels(px, w, h)
        assertTrue("ratio=$ratio", ratio > OcrVerticalDetector.VERTICAL_RATIO)
    }

    @Test
    fun `horizontal stripes yield near zero ratio`() {
        // 横墨条纹：行投影剧烈波动、列投影均匀 → vV≈0 → ratio≈0
        val w = 200
        val h = 160
        val px = IntArray(w * h) { i ->
            val y = i / w
            if (y % 8 < 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val ratio = OcrVerticalDetector.pageRatioFromPixels(px, w, h)
        assertTrue("ratio=$ratio", ratio < OcrVerticalDetector.SUSPECT_RATIO)
    }
}
