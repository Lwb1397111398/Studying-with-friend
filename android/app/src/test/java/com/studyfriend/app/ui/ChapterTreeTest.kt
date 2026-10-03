package com.studyfriend.app.ui

import com.studyfriend.app.data.db.ChapterTreeRow
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.ui.screens.buildChapterTree
import com.studyfriend.app.ui.screens.findHighlightIndex
import com.studyfriend.app.ui.screens.flattenTree
import com.studyfriend.app.ui.screens.normalizeHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章树纯函数单测（P5 计划案 J7①）：正常挂接 + 三个防御分支 + 零 level1 边界 +
 * 拍平展开态 + highlight 定位（含 TOC 排除与最短命中）。
 */
class ChapterTreeTest {

    private fun row(
        id: Long,
        idx: Int,
        level: Int = 1,
        parentOrder: Int? = null,
        title: String = "章$idx",
    ) = ChapterTreeRow(
        id = id, idx = idx, title = title, readState = "NOT_READ",
        gist = null, keyTermsJson = null, level = level, parentOrder = parentOrder,
        calibrated = false, hasAsset = false, assetPromptVersion = null,
        assetSummaryPresent = false,
    )

    private fun fallbacks() = mutableListOf<String>()

    // ---- buildChapterTree ----

    @Test
    fun `正常挂接 节按 parentOrder 归宿主章且序保持`() {
        val rows = listOf(
            row(1, 0, title = "第一章"),
            row(2, 1, level = 2, parentOrder = 1, title = "第一节"),
            row(3, 2, level = 2, parentOrder = 1, title = "第二节"),
            row(4, 3, title = "第二章"),
            row(5, 4, level = 2, parentOrder = 2, title = "第三节"),
        )
        val tree = buildChapterTree(rows)
        assertEquals(2, tree.size)
        assertEquals(listOf("第一节", "第二节"), tree[0].sections.map { it.title })
        assertEquals(listOf("第三节"), tree[1].sections.map { it.title })
    }

    @Test
    fun `输入乱序仍按 idx 排序后挂接`() {
        val rows = listOf(
            row(5, 4, level = 2, parentOrder = 2, title = "第三节"),
            row(4, 3, title = "第二章"),
            row(2, 1, level = 2, parentOrder = 1, title = "第一节"),
            row(1, 0, title = "第一章"),
        )
        val fb = fallbacks()
        val tree = buildChapterTree(rows) { fb += it }
        assertEquals(listOf("第一章", "第二章"), tree.map { it.row.title })
        assertEquals("第三节", tree[1].sections.single().title)
        assertTrue(fb.isEmpty())
    }

    @Test
    fun `防御 parentOrder 越界 挂 idx 前最近章并上报`() {
        val rows = listOf(
            row(1, 0, title = "第一章"),
            row(2, 1, title = "第二章"),
            row(3, 2, level = 2, parentOrder = 5, title = "孤儿节"),
        )
        val fb = fallbacks()
        val tree = buildChapterTree(rows) { fb += it }
        // 孤儿节挂到前最近章（第二章）名下
        assertEquals("孤儿节", tree[1].sections.single().title)
        assertEquals(1, fb.size)
        assertTrue(fb[0].contains("越界"))
    }

    @Test
    fun `防御 parentOrder null 挂前最近章`() {
        val rows = listOf(
            row(1, 0, title = "第一章"),
            row(2, 1, level = 2, parentOrder = null, title = "无主节"),
        )
        val tree = buildChapterTree(rows) { }
        // 无主节挂到前最近章（第一章）名下
        assertEquals("无主节", tree[0].sections.single().title)
    }

    @Test
    fun `防御 节前无任何章 挂首章不丢行`() {
        val rows = listOf(
            row(9, 0, level = 2, parentOrder = 3, title = "前置节"),
            row(1, 1, title = "第一章"),
        )
        val fb = fallbacks()
        val tree = buildChapterTree(rows) { fb += it }
        assertEquals(1, tree.size)
        assertEquals("前置节", tree[0].sections.single().title)
        assertEquals(1, fb.size)
    }

    @Test
    fun `防御 零 level1 全 level2 扁平自成树并上报`() {
        val rows = listOf(
            row(1, 0, level = 2, parentOrder = 1, title = "节甲"),
            row(2, 1, level = 2, parentOrder = 1, title = "节乙"),
        )
        val fb = fallbacks()
        val tree = buildChapterTree(rows) { fb += it }
        assertEquals(2, tree.size)
        assertTrue(tree.all { it.sections.isEmpty() })
        assertEquals(1, fb.size)
    }

    @Test
    fun `任何分支都不丢行`() {
        val rows = listOf(
            row(1, 0),
            row(2, 1, level = 2, parentOrder = 9),
            row(3, 2, level = 2, parentOrder = 1),
            row(4, 3, level = 2, parentOrder = null),
            row(5, 4),
        )
        val tree = buildChapterTree(rows) { }
        assertEquals(rows.size, tree.sumOf { 1 + it.sections.size })
    }

    // ---- flattenTree ----

    @Test
    fun `拍平 展开章跟节 收起章不跟`() {
        val tree = buildChapterTree(
            listOf(
                row(1, 0, title = "第一章"),
                row(2, 1, level = 2, parentOrder = 1, title = "第一节"),
                row(3, 2, title = "第二章"),
            ),
        )
        val expanded = flattenTree(tree, setOf(1L, 3L))
        assertTrue(expanded[0] is com.studyfriend.app.ui.screens.TreeItem.Chapter)
        assertTrue(expanded[1] is com.studyfriend.app.ui.screens.TreeItem.Section)
        assertEquals(3, expanded.size)
        val collapsed = flattenTree(tree, setOf(3L))
        assertEquals(2, collapsed.size)
        assertEquals(3L, collapsed[1].row.id) // 第二章（id=3）紧跟未展开的第一章
    }

    // ---- findHighlightIndex / normalizeHighlight ----

    private fun para(idx: Int, text: String, role: String = DbValues.ROLE_BODY) =
        ParagraphEntity(chapterId = 1, idx = idx, text = text, role = role)

    @Test
    fun `highlight 归一剥空白与全角空格`() {
        assertEquals("第一节甲", normalizeHighlight("第一节　甲 "))
        assertEquals("第一节甲", normalizeHighlight("〔标题〕第一节 甲"))
    }

    @Test
    fun `highlight 精确命中优先于前缀命中`() {
        val paras = listOf(
            para(0, "第一章 总则"),
            para(1, "第一节 甲"), // 精确
            para(2, "第一节 甲的目的在于……"), // 前缀（更长）
        )
        assertEquals(1, findHighlightIndex(paras, "第一节　甲"))
    }

    @Test
    fun `highlight 前缀多命中取最短`() {
        val paras = listOf(
            para(0, "结论是显然的，这一段是正文而不是标题，长度很长"),
            para(1, "结论"),
        )
        assertEquals(1, findHighlightIndex(paras, "结论"))
    }

    @Test
    fun `highlight 排除 TOC 与脚注段防误滚目录区`() {
        val paras = listOf(
            para(0, "第一节 甲", role = DbValues.ROLE_TOC), // 目录条目同名段
            para(1, "正文开场"),
            para(2, "第一节 甲"),
        )
        assertEquals(2, findHighlightIndex(paras, "第一节 甲"))
    }

    @Test
    fun `highlight 未命中返回 null`() {
        val paras = listOf(para(0, "开场正文"))
        assertEquals(null, findHighlightIndex(paras, "不存在的小节"))
        assertEquals(null, findHighlightIndex(paras, "   "))
    }
}
