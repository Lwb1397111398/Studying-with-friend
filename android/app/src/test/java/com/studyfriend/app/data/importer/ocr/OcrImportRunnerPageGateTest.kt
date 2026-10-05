package com.studyfriend.app.data.importer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 页级分流判定单测（P6b S4）：置信度阈值/低置信行占比的通过与兜底边界 */
class OcrImportRunnerPageGateTest {

    private fun line(conf: Float) = OcrLine("文字", 0f, 0f, 100f, 14f, conf)

    // ================= decide =================

    @Test
    fun `empty page falls back as LOW_CONF`() {
        assertEquals("LOW_CONF", OcrImportRunner.PageGate.decide(emptyList()))
    }

    @Test
    fun `healthy page passes`() {
        val lines = List(5) { line(0.95f) }
        assertNull(OcrImportRunner.PageGate.decide(lines))
    }

    @Test
    fun `median below threshold falls back`() {
        // 5 行全 0.84：median=0.84 < 0.85 → 兜底
        assertEquals("LOW_CONF", OcrImportRunner.PageGate.decide(List(5) { line(0.84f) }))
    }

    @Test
    fun `median exactly at threshold passes`() {
        // 边界：0.85 不触发（<0.85 才触发）
        assertNull(OcrImportRunner.PageGate.decide(List(5) { line(0.85f) }))
    }

    @Test
    fun `low conf ratio above limit falls back`() {
        // 5 行中 2 行 0.3（40% > 20%），median=0.9 达标 → 低置信行占比兜底
        val lines = listOf(line(0.9f), line(0.9f), line(0.3f), line(0.3f), line(0.9f))
        assertEquals("LOW_CONF", OcrImportRunner.PageGate.decide(lines))
    }

    @Test
    fun `low conf ratio exactly at limit passes`() {
        // 边界：1/5 = 20% 不 >20%，median 达标 → 放行
        val lines = listOf(line(0.9f), line(0.9f), line(0.9f), line(0.9f), line(0.3f))
        assertNull(OcrImportRunner.PageGate.decide(lines))
    }

    // ================= meanConf =================

    @Test
    fun `meanConf excludes low conf lines`() {
        // 0.3 低于 0.5 剔除：(0.9+0.9)/2 = 0.9
        val lines = listOf(line(0.9f), line(0.9f), line(0.3f))
        assertEquals(0.9f, OcrImportRunner.PageGate.meanConf(lines), 1e-4f)
    }

    @Test
    fun `all low conf page records zero`() {
        assertEquals(0f, OcrImportRunner.PageGate.meanConf(listOf(line(0.3f), line(0.1f))), 1e-6f)
    }

    @Test
    fun `empty page records zero`() {
        assertEquals(0f, OcrImportRunner.PageGate.meanConf(emptyList()), 1e-6f)
    }

    // ================= Outcome 逐页列表同长不变量（S6） =================

    private fun outcome(
        nLines: Int = 2,
        nDims: Int = 2,
        nMeans: Int = 2,
        nConfs: Int = 2,
    ) = OcrImportRunner.Outcome(
        pagesLines = List(nLines) { emptyList() },
        dims = List(nDims) { 100f to 200f },
        fallbackPages = emptyList(),
        pageMeanConfs = List(nMeans) { 0.9f },
        pageLineConfs = List(nConfs) { emptyList() },
    )

    @Test
    fun `outcome accepts equal length page lists`() {
        assertEquals(2, outcome().pagesLines.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `outcome rejects mismatched lineConfs length`() {
        outcome(nConfs = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `outcome rejects mismatched dims length`() {
        outcome(nDims = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `outcome rejects mismatched meanConfs length`() {
        outcome(nMeans = 5)
    }
}
