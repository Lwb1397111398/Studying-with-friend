package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4 parseNote 双层文案（J5 对账口径；计划案 §3-B 产出段） */
class FigureParseNoteTest {

    private fun stats(
        totalObjects: Int = 0,
        kept: Int = 0,
        r1: Int = 0,
        r2: Int = 0,
        r3x: Int = 0,
        r3c: Int = 0,
        errored: Int = 0,
        erroredPerm: Int = 0,
        erroredOversize: Int = 0,
        inline: Int = 0,
        formDepth: Int = 0,
        permDenied: Boolean = false,
        fatalError: Boolean = false,
        nonOrtho: Set<Int> = emptySet(),
        keptPages: Set<Int> = emptySet(),
        biPages: Map<Int, Int> = emptyMap(),
    ) = FigureExtractorStats().apply {
        this.totalObjects = totalObjects
        this.kept = kept
        droppedR1 = r1
        droppedR2 = r2
        droppedR3Xref = r3x
        droppedR3Content = r3c
        this.errored = errored
        this.erroredPerm = erroredPerm
        this.erroredOversize = erroredOversize
        inlineImageCount = inline
        droppedFormDepth = formDepth
        this.permDenied = permDenied
        this.fatalError = fatalError
        this.nonOrthoCtmPages.addAll(nonOrtho)
        this.keptPages.addAll(keptPages)
        this.biPages.putAll(biPages)
    }

    // ---- 主文案 ----

    @Test
    fun main_nullStatsOrNullObjects_returnsNull() {
        assertNull(FigureParseNote.main(null, emptyMap()))
        assertNull(FigureParseNote.main(stats(), emptyMap())) // 无图书零影响（J5）
    }

    @Test
    fun main_fatalError_singleLine() {
        assertEquals("图片提取异常，本次仅导入文字内容", FigureParseNote.main(stats(fatalError = true), emptyMap()))
    }

    @Test
    fun main_permDenied_singleLine() {
        assertEquals("该书限制图片提取，未提取示意图", FigureParseNote.main(stats(permDenied = true), emptyMap()))
    }

    @Test
    fun main_normal_withKeptPagesAndDropped() {
        val note = FigureParseNote.main(
            stats(totalObjects = 25, kept = 3, r1 = 2, r2 = 3, r3x = 4, keptPages = setOf(4, 5, 6)),
            emptyMap(),
        )
        assertEquals(
            "检测到 25 张图，保留 3 张示意图（p4、p5、p6），已自动跳过 9 张背景/装饰图",
            note,
        )
    }

    @Test
    fun main_pageListFoldsAfterFive() {
        val note = FigureParseNote.main(
            stats(totalObjects = 10, kept = 7, keptPages = setOf(1, 2, 3, 4, 5, 6, 7)),
            emptyMap(),
        )
        assertTrue(note!!.contains("p1、p2、p3、p4、p5 等 7 张"))
    }

    @Test
    fun main_lowQualityBypassedAppended() {
        val note = FigureParseNote.main(
            stats(totalObjects = 10, kept = 2, keptPages = setOf(7, 8)),
            mapOf(7 to 1, 8 to 1),
        )
        assertTrue(note!!.endsWith("p7 的 1 张示意图因文字层质量低可能错位；p8 的 1 张示意图因文字层质量低可能错位"))
    }

    // ---- 详情 ----

    @Test
    fun detail_nullStats_returnsNull() {
        assertNull(FigureParseNote.detail(null, 0f, false))
    }

    @Test
    fun detail_zeroSignal_returnsNull() {
        assertNull(FigureParseNote.detail(stats(), 0.1f, false))
    }

    @Test
    fun detail_droppedBreakdownAndErrors() {
        val d = FigureParseNote.detail(
            stats(totalObjects = 25, kept = 14, r1 = 2, r2 = 3, r3x = 3, r3c = 1, errored = 1, erroredOversize = 1),
            0.1f, false,
        )!!
        assertTrue(d.contains("滤除 2 整页底图 / 3 装饰 / 4 同位页眉"))
        assertTrue(d.contains("⚠ 因 CMYK/格式问题跳过 1 张"))
        assertTrue(d.contains("⚠ 1 张图因尺寸过大未提取"))
    }

    @Test
    fun detail_inlineRatioOverTwentyPercent() {
        val d = FigureParseNote.detail(
            stats(totalObjects = 30, inline = 30, biPages = mapOf(1 to 15, 2 to 15)),
            0f, false,
        )!!
        assertTrue(d.contains("内联图（本版未提取）：p1、p2，共 30 处"))
        assertTrue(d.contains("⚠ 该书含大量内联图，本版未提取"))
    }

    @Test
    fun detail_inlineRatioAtThreshold_noWarn() {
        // 20% 恰在阈值内（>20% 才警告）
        val d = FigureParseNote.detail(stats(totalObjects = 100, inline = 20), 0f, false)
        assertTrue(!d!!.contains("大量内联图"))
    }

    @Test
    fun detail_nonOrthoPages_usePageUnit() {
        val d = FigureParseNote.detail(stats(nonOrtho = setOf(20)), 0f, false)!!
        assertTrue(d.contains("p20 含旋转/镜像图，按原始方向展示"))
    }

    @Test
    fun detail_permAndFormDepth() {
        val d = FigureParseNote.detail(stats(erroredPerm = 2, formDepth = 3), 0f, false)!!
        assertTrue(d.contains("⚠ 2 张图因版权权限限制未提取"))
        assertTrue(d.contains("⚠ 3 张图片因嵌套层级过深未提取"))
    }

    @Test
    fun detail_landscapeOverThirtyPercent_andDoubleColumn() {
        val d = FigureParseNote.detail(stats(kept = 1), 0.31f, true)!!
        assertTrue(d.contains("⚠ 该书为横排版式"))
        assertTrue(d.contains("⚠ 该书疑似多栏排版"))
    }

    @Test
    fun detail_landscapeAtThreshold_noWarn() {
        assertNull(FigureParseNote.detail(stats(), 0.3f, false))
    }
}
