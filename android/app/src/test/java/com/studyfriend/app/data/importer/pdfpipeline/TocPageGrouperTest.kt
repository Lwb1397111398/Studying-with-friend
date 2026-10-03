package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录探针页区段化取样（P3b-2 §3.4）：按册聚类 + 段内全取 + 总量护栏。
 * 纯函数零依赖，普通 JUnit。
 */
class TocPageGrouperTest {

    @Test
    fun group_mzzzRealCase_twoVolumesSplitAndFullyTaken() {
        // mzzz 实况（GT）：上册目录 p28-31（4 页）、下册 p557+——v1.1「每段取前 2 页」
        // 漏掉两册尾页；v1.2 段内全取后两册目录全覆盖
        val r = TocPageGrouper.group(listOf(28, 29, 30, 31, 557, 558))
        assertEquals(listOf(listOf(28, 29, 30, 31), listOf(557, 558)), r)
    }

    @Test
    fun group_bddlRealCase_threePageTocFullyTaken() {
        // bddl 实况（GT）：目录占 p18-20 三页，v1.1 只探前 2 页漏尾页 12 条
        // （第八、九章等 4 个 level1），八九章仍并入第七章
        assertEquals(listOf(listOf(18, 19, 20)), TocPageGrouper.group(listOf(18, 19, 20)))
    }

    @Test
    fun group_gapExactlyThreshold_staysSameSegment() {
        // 间隔恰 =5 不断（阈值语义是 >5 才断）
        assertEquals(listOf(listOf(10, 15)), TocPageGrouper.group(listOf(10, 15)))
    }

    @Test
    fun group_gapSix_splits() {
        assertEquals(listOf(listOf(10), listOf(16)), TocPageGrouper.group(listOf(10, 16)))
    }

    @Test
    fun group_overMaxPages_lastSegmentDroppedWhole() {
        // 5 段 × 2 页 = 10 > 8：第 5 段整段丢弃（部分取样会让区段样本残缺）
        val r = TocPageGrouper.group(listOf(1, 2, 10, 11, 20, 21, 30, 31, 40, 41))
        assertEquals(
            listOf(listOf(1, 2), listOf(10, 11), listOf(20, 21), listOf(30, 31)),
            r,
        )
    }

    @Test
    fun group_singleSegmentUpToMax_allTaken() {
        // 单段 8 页顶格全取（MAX_PAGES 护栏刚好容纳）
        assertEquals(
            listOf(listOf(1, 2, 3, 4, 5, 6, 7, 8)),
            TocPageGrouper.group(listOf(1, 2, 3, 4, 5, 6, 7, 8)),
        )
    }

    @Test
    fun group_singleSegmentOverMax_droppedWhole() {
        // 单段 9 页 > 8：整段丢弃（保完整不保残缺——残缺样本无法做多锚互差校验）
        assertEquals(emptyList<List<Int>>(), TocPageGrouper.group(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
    }

    @Test
    fun group_emptyInput_emptyOutput() {
        assertEquals(emptyList<List<Int>>(), TocPageGrouper.group(emptyList()))
    }

    @Test
    fun group_singlePage_singleSegment() {
        assertEquals(listOf(listOf(7)), TocPageGrouper.group(listOf(7)))
    }

    @Test
    fun group_unsortedInput_sortedFirst() {
        // 上游 filter 保序不保证升序（防御性），组内排序后再聚类
        assertEquals(
            listOf(listOf(28, 29, 30), listOf(557, 558)),
            TocPageGrouper.group(listOf(558, 29, 557, 28, 30)),
        )
    }
}
