package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** J1（P4）：坐标数学纯函数——rotateRect 四方向折算、ctmToAffine 方向检测、锚定、章归属 */
class FigureCoordMathTest {

    private val a4 = PdfRect(0f, 0f, 612f, 792f)

    // ---- rotateRect：用户空间 → 显示空间（y 自顶向下） ----

    @Test
    fun rotateRect_rotation0_flipsYOnly() {
        // 用户空间顶部条（y 大 = 顶）→ 显示空间 y 小 = 顶
        val b = FigureCoordMath.rotateRect(0, PdfRect(100f, 700f, 300f, 750f), a4)
        assertEquals(100f, b.x, 0.01f)
        assertEquals(42f, b.y, 0.01f) // 792 - 750
        assertEquals(200f, b.w, 0.01f)
        assertEquals(50f, b.h, 0.01f)
    }

    @Test
    fun rotateRect_rotation90_swapsAxes() {
        // 顶部横条顺时针转 90° → 显示页（792×612）右侧竖条
        val b = FigureCoordMath.rotateRect(90, PdfRect(100f, 700f, 300f, 750f), a4)
        assertEquals(700f, b.x, 0.01f)
        assertEquals(100f, b.y, 0.01f)
        assertEquals(50f, b.w, 0.01f)
        assertEquals(200f, b.h, 0.01f)
        // 显示页宽高互换：图 x 最大 750 ≤ 792、y 最大 300 ≤ 612
        assertTrue(b.x + b.w <= 792f)
        assertTrue(b.y + b.h <= 612f)
    }

    @Test
    fun rotateRect_rotation180_flipsBoth() {
        val b = FigureCoordMath.rotateRect(180, PdfRect(100f, 700f, 300f, 750f), a4)
        assertEquals(312f, b.x, 0.01f) // 612 - 300
        assertEquals(42f, b.y, 0.01f)
        assertEquals(200f, b.w, 0.01f)
        assertEquals(50f, b.h, 0.01f)
    }

    @Test
    fun rotateRect_rotation270_swapsAxesOtherWay() {
        val b = FigureCoordMath.rotateRect(270, PdfRect(100f, 700f, 300f, 750f), a4)
        assertEquals(42f, b.x, 0.01f) // 792 - 750
        assertEquals(312f, b.y, 0.01f) // 612 - 300
        assertEquals(50f, b.w, 0.01f)
        assertEquals(200f, b.h, 0.01f)
    }

    @Test
    fun rotateRect_fullPageRect_coversDisplayPage() {
        val b0 = FigureCoordMath.rotateRect(0, a4, a4)
        assertEquals(DispBbox(0f, 0f, 612f, 792f), approx(b0))
        val b90 = FigureCoordMath.rotateRect(90, a4, a4)
        assertEquals(DispBbox(0f, 0f, 792f, 612f), approx(b90))
        val b270 = FigureCoordMath.rotateRect(270, a4, a4)
        assertEquals(DispBbox(0f, 0f, 792f, 612f), approx(b270))
    }

    @Test
    fun rotateRect_mediaBoxOriginNotZero_shiftsFirst() {
        val mb = PdfRect(10f, 20f, 622f, 812f) // 同尺寸 A4，原点 (10,20)
        val b = FigureCoordMath.rotateRect(0, PdfRect(110f, 720f, 310f, 770f), mb)
        assertEquals(100f, b.x, 0.01f)
        assertEquals(42f, b.y, 0.01f)
        assertEquals(200f, b.w, 0.01f)
        assertEquals(50f, b.h, 0.01f)
    }

    @Test
    fun rotateRect_negativeRotation_normalizes() {
        // -270 ≡ 90
        val b = FigureCoordMath.rotateRect(-270, PdfRect(100f, 700f, 300f, 750f), a4)
        val b90 = FigureCoordMath.rotateRect(90, PdfRect(100f, 700f, 300f, 750f), a4)
        assertEquals(approx(b90), approx(b))
    }

    private fun approx(b: DispBbox) = DispBbox(
        (b.x * 100).toInt() / 100f,
        (b.y * 100).toInt() / 100f,
        (b.w * 100).toInt() / 100f,
        (b.h * 100).toInt() / 100f,
    )

    // ---- ctmToAffine：90° 族方向检测（r8-P2-8） ----

    @Test
    fun ctmToAffine_identity() {
        val aff = FigureCoordMath.ctmToAffine(1f, 0f, 0f, 1f, 0f, 0f)
        assertNotNull(aff)
        assertEquals(1f, aff!!.scaleX, 0.01f)
        assertEquals(1f, aff.scaleY, 0.01f)
        assertFalse(aff.flip)
    }

    @Test
    fun ctmToAffine_rotation90() {
        // (x,y) → (y,-x)：数学坐标系顺时针 90°
        val aff = FigureCoordMath.ctmToAffine(0f, -1f, 1f, 0f, 0f, 0f)
        assertNotNull(aff)
        assertEquals(0f, aff!!.scaleX, 0.01f)
        assertEquals(1f, aff.skewX, 0.01f)
        assertEquals(-1f, aff.skewY, 0.01f)
        assertEquals(0f, aff.scaleY, 0.01f)
        assertFalse(aff.flip)
    }

    @Test
    fun ctmToAffine_rotation180() {
        val aff = FigureCoordMath.ctmToAffine(-1f, 0f, 0f, -1f, 0f, 0f)
        assertNotNull(aff)
        assertEquals(-1f, aff!!.scaleX, 0.01f)
        assertEquals(-1f, aff.scaleY, 0.01f)
        assertFalse(aff.flip)
    }

    @Test
    fun ctmToAffine_uniformScaleWithRotation_keepsScale() {
        val aff = FigureCoordMath.ctmToAffine(0f, -2f, 2f, 0f, 0f, 0f)
        assertNotNull(aff)
        assertEquals(2f, aff!!.skewX, 0.01f)
        assertEquals(-2f, aff.skewY, 0.01f)
    }

    @Test
    fun ctmToAffine_mirror_detectedAsFlip() {
        // y 轴翻转：行列式 -1
        val aff = FigureCoordMath.ctmToAffine(1f, 0f, 0f, -1f, 0f, 0f)
        assertNotNull(aff)
        assertTrue(aff!!.flip)
    }

    @Test
    fun ctmToAffine_shear_returnsNull() {
        assertNull(FigureCoordMath.ctmToAffine(1f, 0f, 1f, 1f, 0f, 0f))
    }

    @Test
    fun ctmToAffine_diagonalRotation_returnsNull() {
        // 45° 旋转：正交但非轴对齐
        val s = 0.70710678f
        assertNull(FigureCoordMath.ctmToAffine(s, s, -s, s, 0f, 0f))
    }

    @Test
    fun ctmToAffine_nonUniformScale_returnsNull() {
        assertNull(FigureCoordMath.ctmToAffine(2f, 0f, 0f, 1f, 0f, 0f))
    }

    @Test
    fun ctmToAffine_degenerate_returnsNull() {
        assertNull(FigureCoordMath.ctmToAffine(0f, 0f, 0f, 0f, 0f, 0f))
    }

    // ---- anchorFigure：段级锚定（全量段口径、0-based、-1=页首前） ----

    @Test
    fun anchorFigure_middleY_anchorsLastParaAbove() {
        assertEquals(1, FigureCoordMath.anchorFigure(250f, listOf(100f, 200f, 300f)))
    }

    @Test
    fun anchorFigure_aboveAllParas_returnsMinus1() {
        assertEquals(-1, FigureCoordMath.anchorFigure(50f, listOf(100f, 200f, 300f)))
    }

    @Test
    fun anchorFigure_emptyPage_returnsMinus1() {
        assertEquals(-1, FigureCoordMath.anchorFigure(100f, emptyList()))
    }

    @Test
    fun anchorFigure_boundaryEqualY0_hitsThatPara() {
        // 图顶 y 恰等于段 y0 → 锚该段（v1.7 边界收紧，r7-P2-2）
        assertEquals(1, FigureCoordMath.anchorFigure(200f, listOf(100f, 200f)))
    }

    @Test
    fun anchorFigure_belowAllParas_anchorsLast() {
        assertEquals(2, FigureCoordMath.anchorFigure(900f, listOf(100f, 200f, 300f)))
    }

    @Test
    fun anchorFigure_frontFigureFootnote_fullListOrd() {
        // 全量口径（r6-P1-2）：FRONT 标题 + 中部图 + 页脚 FOOTNOTE 都在列表里
        val paraY0s = listOf(40f, 120f, 700f) // 0=FRONT、1=正文、2=FOOTNOTE
        assertEquals(1, FigureCoordMath.anchorFigure(300f, paraY0s))
        assertEquals(-1, FigureCoordMath.anchorFigure(30f, paraY0s))
        assertEquals(2, FigureCoordMath.anchorFigure(720f, paraY0s))
    }

    // ---- assignChapter：区间映射边界（P2-1） ----

    private val starts = listOf(10, 50, 100)

    @Test
    fun assignChapter_insideRanges() {
        assertEquals(0, FigureCoordMath.assignChapter(10, starts))
        assertEquals(0, FigureCoordMath.assignChapter(49, starts))
        assertEquals(1, FigureCoordMath.assignChapter(50, starts))
        assertEquals(1, FigureCoordMath.assignChapter(99, starts))
        assertEquals(2, FigureCoordMath.assignChapter(100, starts))
    }

    @Test
    fun assignChapter_beforeFirst_clampsToFirst() {
        assertEquals(0, FigureCoordMath.assignChapter(5, starts))
    }

    @Test
    fun assignChapter_afterLast_clampsToLast() {
        assertEquals(2, FigureCoordMath.assignChapter(200, starts))
    }

    @Test
    fun assignChapter_emptyChapters_returnsMinus1() {
        assertEquals(-1, FigureCoordMath.assignChapter(50, emptyList()))
    }
}
