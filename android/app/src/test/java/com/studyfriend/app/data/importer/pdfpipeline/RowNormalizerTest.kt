package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPT-G P1：行内 span 聚类 + 字号重估单测（fixture 内嵌，不依赖 PDF）。
 * 覆盖 P1 计划案 7 场景：正文单组 / 标点乱值 / 上标缝合 / y 抖动 /
 * 跨组拆分 / 无坐标 / 空白守卫。
 */
class RowNormalizerTest {

    private fun span(
        text: String,
        x0: Float,
        x1: Float,
        y0: Float,
        size: Float,
    ) = RowNormalizer.SpanInfo(text, x0, x1, y0, size)

    /** 场景 1：纯正文单组行 → 单 PLine，size=字符加权均值（非最大值） */
    @Test
    fun `plain line - single group weighted size`() {
        val out = RowNormalizer.normalize(
            listOf(
                span("担保物权制度", 50f, 155f, 100f, 10.5f),
                span("研究", 155f, 176f, 100f, 10.0f),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("担保物权制度研究", out[0].text)
        assertEquals(50f, out[0].x0, 0.01f)
        assertEquals(176f, out[0].x1, 0.01f)
        assertEquals((10.5f * 6 + 10.0f * 2) / 8, out[0].size, 0.01f)
    }

    /** 场景 2：标点乱值行（1.5pt 标点 + 10.5pt 正文）→ size 不被拖垮 */
    @Test
    fun `punct outlier - size robust`() {
        val out = RowNormalizer.normalize(
            listOf(
                span("担保物权制度研究", 50f, 155f, 100f, 10.5f),
                span("。，", 155f, 162f, 100f, 1.5f),
            ),
        )
        assertEquals(1, out.size)
        // 1.5 < 0.55×10.5=5.775 被剔除，重估 = 正文 10.5
        assertEquals(10.5f, out[0].size, 0.01f)
        // 不丢字：标点仍在文本里
        assertEquals("担保物权制度研究。，", out[0].text)
    }

    /** 场景 3（M1 修正期望）：上标引注 dy=0.3×字号 → 缝入主行，PLine 数量=1，不丢字。
     *  上标 7pt 高于剔除线 5.775（论文神器原设计只剔标点乱值不剔上标），
     *  组字号=含上标的字符加权均值≈9.33——主字号判定由 docStats 全书众数兜底。 */
    @Test
    fun `superscript - stitched into main line`() {
        // 10.5pt 正文，上标 7pt dy=3.15（=0.3×10.5），容差 0.5×10.5+1=6.25 → 同组
        val out = RowNormalizer.normalize(
            listOf(
                span("合同效力依据见", 50f, 160f, 100f, 10.5f),
                span("[39]", 160f, 172f, 103.15f, 7f),
                span("。", 172f, 176f, 100f, 10.5f),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("合同效力依据见[39]。", out[0].text)
        assertEquals((10.5f * 8 + 7f * 4) / 12, out[0].size, 0.01f)
    }

    /** 场景 4：y 抖动同行 span（y0 差 4.6pt@9pt，容差内）→ 仍聚为一组 */
    @Test
    fun `y jitter within tolerance - one group`() {
        val out = RowNormalizer.normalize(
            listOf(
                span("左半行内容", 50f, 140f, 100f, 9f),
                span("右半行内容", 300f, 390f, 104.6f, 9f),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("左半行内容右半行内容", out[0].text)
    }

    /** 场景 5：跨组碎片（y0 差 > 容差）→ 拆 2 条 PLine，文本不丢 */
    @Test
    fun `beyond tolerance - split two lines keep text`() {
        val out = RowNormalizer.normalize(
            listOf(
                span("主行文本", 50f, 140f, 100f, 9f),
                span("下一行文本", 50f, 140f, 110f, 9f), // 差 10pt > 容差 5.5pt
            ),
        )
        assertEquals(2, out.size)
        assertEquals("主行文本", out[0].text)
        assertEquals("下一行文本", out[1].text)
        assertTrue(out[1].y0 > out[0].y0)
    }

    /** 场景 6：无坐标行 → 原样单 PLine，几何 −1 */
    @Test
    fun `no position - passthrough single line`() {
        val out = RowNormalizer.normalize(
            listOf(span("无坐标文本", -1f, -1f, -1f, -1f)),
        )
        assertEquals(1, out.size)
        assertEquals("无坐标文本", out[0].text)
        assertEquals(-1f, out[0].x0, 0.01f)
        assertEquals(-1f, out[0].size, 0.01f)
    }

    /** 场景 7：空白守卫 → 不产出 PLine */
    @Test
    fun `blank spans - empty output`() {
        assertTrue(RowNormalizer.normalize(emptyList()).isEmpty())
        assertTrue(
            RowNormalizer.normalize(listOf(span("   ", 50f, 60f, 100f, 10f))).isEmpty(),
        )
    }

    /** 补充：字号未知（−1）混行 → 重估不崩溃，不丢字 */
    @Test
    fun `unknown size span - no crash keep text`() {
        val out = RowNormalizer.normalize(
            listOf(
                span("正常文本", 50f, 120f, 100f, 10.5f),
                span("？?", 120f, 130f, 100f, -1f),
            ),
        )
        assertEquals(1, out.size)
        assertTrue(out[0].text.contains("正常文本"))
    }
}
