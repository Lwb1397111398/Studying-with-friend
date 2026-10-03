package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录探针页区段化取样（P3b-2 §3.4）：按册聚类 + 每段取样 + 总量护栏。
 * 纯函数零依赖，普通 JUnit。
 */
class TocPageGrouperTest {

    @Test
    fun group_mzzzRealCase_twoVolumesSplitAndSampled() {
        // mzzz 实况：上册目录 p28-30、下册 p557+——P3b-1 取前 4 页只覆盖上册
        val r = TocPageGrouper.group(listOf(28, 29, 30, 557, 558))
        assertEquals(listOf(listOf(28, 29), listOf(557, 558)), r)
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
        // 5 段 × 2 页 = 10 > 8：第 5 段整段丢弃（部分取样会让区段只剩 1 页样本）
        val r = TocPageGrouper.group(listOf(1, 2, 10, 11, 20, 21, 30, 31, 40, 41))
        assertEquals(
            listOf(listOf(1, 2), listOf(10, 11), listOf(20, 21), listOf(30, 31)),
            r,
        )
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
        assertEquals(listOf(listOf(28, 29), listOf(557, 558)), TocPageGrouper.group(listOf(558, 29, 557, 28, 30)))
    }
}
