package com.studyfriend.app.data.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图页消费闸纯函数单测（r12-QC3-P1：worker 注入成本高，闸判定抽 figureGateSkip
 * 供 JVM 直测；worker 内仅薄包日志与状态落库，分支语义以此为准）
 */
class VisionWorkerGateTest {

    private val figurePages = setOf(86, 482, 488, 492)

    @Test
    fun figurePage_skipped() {
        assertTrue(VisionWorker.figureGateSkip(482, figurePages, lowQuality = false))
    }

    @Test
    fun nonFigurePage_notSkipped() {
        assertFalse(VisionWorker.figureGateSkip(100, figurePages, lowQuality = false))
    }

    @Test
    fun figurePage_lowQualityBypass_stillTranscribed() {
        // 低质量放行页（第一道闸 isLowQualityPage 判定、lowQuality 标记）例外放行，
        // 否则整页转写需求（烂文字层图页）会被锚定保护误吞
        assertFalse(VisionWorker.figureGateSkip(482, figurePages, lowQuality = true))
    }

    @Test
    fun emptyFigureSet_neverSkips() {
        // 无图书（figures 表 0 行）：视觉队列任何页都不受闸影响（J5 零影响回归点）
        assertFalse(VisionWorker.figureGateSkip(86, emptySet(), lowQuality = false))
    }
}
