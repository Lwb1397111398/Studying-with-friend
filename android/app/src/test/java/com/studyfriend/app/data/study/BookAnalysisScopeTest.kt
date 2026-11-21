package com.studyfriend.app.data.study

import org.junit.Assert.assertEquals
import org.junit.Test

/** 阅读体验轮：一键分析跳章规则纯函数（8 例）——「跳过开篇/目录/参考文献，从第一章讲」 */
class BookAnalysisScopeTest {

    @Test
    fun typicalTextbook_skipsFrontAndBack() {
        val plan = BookAnalysisScope.plan(
            listOf("目录", "前言", "第一章 总论", "第二章 物权", "参考文献", "附录一 名词索引"),
        )
        assertEquals(listOf(2, 3), plan.targets)
        assertEquals(4, plan.skippedCount)
    }

    @Test
    fun prefaceBeforeFirstChapter_skipped() {
        // 开篇（封面/序言/凡例）在第一个「第X章」之前——整段跳过
        val plan = BookAnalysisScope.plan(listOf("版权信息", "作者介绍", "第三版序", "凡例", "第一章 民法概述", "第二章 民事法律关系"))
        assertEquals(listOf(4, 5), plan.targets)
        assertEquals(4, plan.skippedCount)
    }

    @Test
    fun midBookTocAndAppendix_removed() {
        // 中途出现的目录页（校准书常见）与书末后置件同样剔除
        val plan = BookAnalysisScope.plan(listOf("第一章 总论", "目录", "第二章 物权", "后记", "致谢"))
        assertEquals(listOf(0, 2), plan.targets)
        assertEquals(3, plan.skippedCount)
    }

    @Test
    fun noChapterFormat_startsFromZero() {
        // 散文集/短篇：全书没有「第X章」式标题——从 0 起全部分析（只剔明确后置件），不误杀
        val plan = BookAnalysisScope.plan(listOf("秋天的怀念", "我与地坛", "后记"))
        assertEquals(listOf(0, 1), plan.targets)
        assertEquals(1, plan.skippedCount)
    }

    @Test
    fun alternativeBodyFormats_recognized() {
        // 专题/单元/第N课/Chapter 同属正文章格式
        val plan = BookAnalysisScope.plan(listOf("目录", "专题一 物权", "Chapter 3 Ownership", "第2课 买卖合同"))
        assertEquals(listOf(1, 2, 3), plan.targets)
    }

    @Test
    fun sectionOnlyBook_startsFromFirstSection() {
        // 有些书章表被切到节级（全是「第X节」）——第一个「第X节」即正文起点，全部分析
        val plan = BookAnalysisScope.plan(listOf("第一节 概述", "第二节 展开"))
        assertEquals(listOf(0, 1), plan.targets)
        assertEquals(0, plan.skippedCount)
    }

    @Test
    fun chapterNumberedWithArabic_recognized() {
        val plan = BookAnalysisScope.plan(listOf("目录", "第1章 基础", "第2章 进阶"))
        assertEquals(listOf(1, 2), plan.targets)
    }

    @Test
    fun emptyBook_emptyPlan() {
        val plan = BookAnalysisScope.plan(emptyList())
        assertEquals(0, plan.targetCount)
        assertEquals(0, plan.skippedCount)
    }

    @Test
    fun tinyFragmentChapter_skippedByCharCount() {
        // 实景轮 2026-11-21：切章错误的 Kit 书带 1 段几字的扉页残章——<50 字不烧 API
        val plan = BookAnalysisScope.plan(
            listOf("第一章 民法基础", "第九章　代理", "第三章　法律行为的本质"),
            charCounts = listOf(34620, 5, 1153),
        )
        assertEquals(listOf(0, 2), plan.targets)
        assertEquals(1, plan.skippedCount)
        assertEquals("第九章　代理", plan.skipped[0].second)
    }

    @Test
    fun charCountNull_disablesFragmentSkip() {
        // 不传字数（旧调用方）行为不变：残章仍进分析清单
        val plan = BookAnalysisScope.plan(listOf("第一章 民法基础", "第九章　代理"))
        assertEquals(listOf(0, 1), plan.targets)
    }
}
