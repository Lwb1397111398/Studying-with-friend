package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** J1（P4）：三层过滤规则真值表与边界（R1 5 组 + R2 ±0.1pt 双向 + R3 同位/异位/内容型） */
class PdfFigureRulesTest {

    private val a4w = 612f
    private val a4h = 792f

    // ---- R1 整页扫描底图（真值表 5 组，计划案 J1） ----

    @Test
    fun r1_area85_1_filters() {
        // 面积 85.1% → 滤
        val side = kotlin.math.sqrt(0.851f * a4w * a4h)
        val b = DispBbox(0f, 0f, side, side)
        assertTrue(isR1FullPageBleed(b, a4w, a4h))
    }

    @Test
    fun r1_area84_9_passesR1_alone() {
        // 面积 84.9%、单边不超 → 过 R1（可能死在 R2 面积分支，但 R1 本身不滤）
        val side = kotlin.math.sqrt(0.849f * a4w * a4h)
        val b = DispBbox(0f, 0f, side, side)
        assertFalse(isR1FullPageBleed(b, a4w, a4h))
    }

    @Test
    fun r1_bothEdges91_filters() {
        val b = DispBbox(0f, 0f, 0.91f * a4w, 0.91f * a4h)
        assertTrue(isR1FullPageBleed(b, a4w, a4h))
    }

    @Test
    fun r1_oneEdge91Other84_keeps() {
        // 单边 91% 高 84% → 留（单边超限不成底图）
        val b = DispBbox(0f, 0f, 0.91f * a4w, 0.84f * a4h)
        assertFalse(isR1FullPageBleed(b, a4w, a4h))
    }

    @Test
    fun r1_area84_9_withBothEdges95_filtersByOrBranch() {
        // 84.9% 面积 + 宽高均 95% → 「或」第二分支命中（r6-P2-3c）
        val b = DispBbox(0f, 0f, 0.95f * a4w, 0.95f * a4h)
        assertTrue(isR1FullPageBleed(b, a4w, a4h))
    }

    @Test
    fun r1_degeneratePageSize_keeps() {
        // 防御：页尺寸非法时不误滤
        assertFalse(isR1FullPageBleed(DispBbox(0f, 0f, 612f, 792f), 0f, 0f))
    }

    // ---- R2 装饰元素（40pt 边界 ±0.1pt 双向 + 面积分支） ----

    @Test
    fun r2_edge39_9_filters() {
        assertTrue(isR2Decoration(DispBbox(0f, 0f, 300f, 39.9f), a4w, a4h))
    }

    @Test
    fun r2_edge40_1_keeps() {
        assertFalse(isR2Decoration(DispBbox(0f, 0f, 300f, 40.1f), a4w, a4h))
    }

    @Test
    fun r2_edgeExactly40_filters() {
        // <40pt 严格小于：40.0 恰好不滤
        assertFalse(isR2Decoration(DispBbox(0f, 0f, 300f, 40f), a4w, a4h))
    }

    @Test
    fun r2_smallArea_filters() {
        // 面积 1.9%（边长足够长但极窄）
        val w = 500f
        val h = 0.019f * a4w * a4h / w
        assertTrue(isR2Decoration(DispBbox(0f, 0f, w, h), a4w, a4h))
    }

    @Test
    fun r2_normalFigure_keeps() {
        // mfzz p86 真图 283×107pt（12.1% 页面积）：安全保留
        assertFalse(isR2Decoration(DispBbox(0f, 0f, 283f, 107f), 408f, 630f))
    }

    // ---- R3 同位重复（同位 vs 异位；内容型由调用方分组后走同一判定） ----

    private val header = NormPos(0.1f, 0.02f, 0.15f)

    @Test
    fun r3_samePosition3Pages_filters() {
        val positions = List(4) { header } // 重复页眉 logo ×4 页
        assertTrue(isR3RepeatingHeader(positions))
    }

    @Test
    fun r3_only2Pages_keeps() {
        assertFalse(isR3RepeatingHeader(listOf(header, header)))
    }

    @Test
    fun r3_positionDriftBeyond2pct_keeps() {
        // 页间位置漂移 >2% → 不是同位页眉（合法重复，如书末插图目录）
        val drifted = listOf(header, header, NormPos(0.13f, 0.02f, 0.15f))
        assertFalse(isR3RepeatingHeader(drifted))
    }

    @Test
    fun r3_tinyDriftWithin2pct_filters() {
        // 1% 抖动仍在容差内（不同页渲染取整）
        val jittered = listOf(header, NormPos(0.101f, 0.021f, 0.151f), header)
        assertTrue(isR3RepeatingHeader(jittered))
    }

    @Test
    fun r3_widthDriftBeyond2pct_keeps() {
        val drifted = listOf(header, header, NormPos(0.1f, 0.02f, 0.18f))
        assertFalse(isR3RepeatingHeader(drifted))
    }

    // ---- DispBbox 语义自检（与 J9 fixture 呼应） ----

    @Test
    fun r1_mfzzSurvivorSize_notFullPage() {
        // mfzz 4 张幸存图最大 25.8% 页面积 → R1 不滤
        val b = DispBbox(60f, 200f, 284f, 229f) // p488
        assertFalse(isR1FullPageBleed(b, 408f, 630f))
        assertFalse(isR2Decoration(b, 408f, 630f))
    }

    // ---- excludeFigurePages 第一道闸 + 低质量防御分支（J6③④，计划案 §3-C） ----

    private fun gatePage(pageNum: Int, rawChars: Int, puaCount: Int) =
        PageOut(
            pageNum, tocLike = false, rawChars = rawChars, puaCount = puaCount,
            lineCount = 5, shortLineCount = 0,
            paras = mutableListOf(Para("文本")), firstLine = null, lastLine = null,
        )

    @Test
    fun gate_figurePageExcluded_healthyPagesUntouched() {
        val pages = listOf(gatePage(3, 900, 0), gatePage(4, 900, 0), gatePage(5, 900, 0))
        val g = excludeFigurePages(listOf(3, 4, 5), pages, setOf(4), mapOf(4 to 2))
        assertEquals(listOf(3, 5), g.filtered)
        assertTrue(g.bypassed.isEmpty())
    }

    @Test
    fun gate_lowQualityFigurePageBypassed_withFigureCount() {
        // rawChars<100 的图页：第一道闸放行并记该页幸存图数（parseNote + 入队 lowQuality 消费）；
        // 健康图页（p3）静默排除——上游是优化，下游拒绝是保命
        val pages = listOf(gatePage(3, 900, 0), gatePage(4, 80, 0))
        val g = excludeFigurePages(listOf(3, 4), pages, setOf(3, 4), mapOf(3 to 1, 4 to 2))
        assertEquals(listOf(4), g.filtered)
        assertEquals(mapOf(4 to 2), g.bypassed)
    }

    @Test
    fun gate_lowQualityByPuaRatio_bypassed() {
        // pua 90 / rawChars 800 = 11.25% > 10% → 放行
        val pages = listOf(gatePage(9, 800, 90))
        val g = excludeFigurePages(listOf(9), pages, setOf(9), mapOf(9 to 1))
        assertEquals(listOf(9), g.filtered)
        assertEquals(mapOf(9 to 1), g.bypassed)
    }

    @Test
    fun gate_emptyFigureSet_identity() {
        val pages = listOf(gatePage(3, 900, 0))
        val g = excludeFigurePages(listOf(3), pages, emptySet())
        assertEquals(listOf(3), g.filtered)
        assertTrue(g.bypassed.isEmpty())
    }

    @Test
    fun lowQuality_rawCharsBoundary() {
        // rawChars=100 不低（<100 才低）；99 低
        assertFalse(isLowQualityPage(gatePage(1, 100, 0)))
        assertTrue(isLowQualityPage(gatePage(1, 99, 0)))
    }

    @Test
    fun lowQuality_puaRatioBoundary() {
        // pua 恰 10% 不低（>10% 才低）；10.1% 低；rawChars=0 走 rawChars 分支直接判低
        assertFalse(isLowQualityPage(gatePage(1, 100, 10)))
        assertTrue(isLowQualityPage(gatePage(1, 100, 11)))
        assertTrue(isLowQualityPage(gatePage(1, 0, 0)))
    }
}
