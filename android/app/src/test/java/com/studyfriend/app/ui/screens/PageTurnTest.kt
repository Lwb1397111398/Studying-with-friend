package com.studyfriend.app.ui.screens

import com.studyfriend.app.data.db.FigureEntity
import com.studyfriend.app.data.db.ParagraphEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B 形态翻页落点估算（第五波实景轮落地，spike 拍板逻辑回归锁定）：
 * 向后/向前落点、锚行首语义（预算内整行不跨界）、图行高、边界钳制。
 */
class PageTurnTest {

    private fun para(id: Long, idx: Int, text: String) = ParagraphEntity(
        id = id, chapterId = 1, idx = idx, text = text, role = "BODY",
    )

    private fun figure(id: Long, width: Int, height: Int) = FigureEntity(
        id = id, bookId = 1, chapterId = 1, pageNo = 1, ordAfterPara = 0, bboxY0 = 0f,
        seqNo = id.toInt(), file = "figures/1/f$id.png", width = width, height = height,
        format = "png", md5 = "m$id", createdAt = 0L,
    )

    // 估算口径：每行 10 字、行高 30px、段距 16px——一段 100 字 ≈ 10 行 ≈ 316px
    private val charsPerLine = 10f
    private val lineH = 30f
    private val gap = 16f
    private val textW = 1000f

    private fun paraH(textLen: Int): Float =
        PageTurn.estimateRowHeightPx(ReadRow.Para(para(1, 0, "x".repeat(textLen))), charsPerLine, lineH, gap, textW)

    private fun figH(w: Int, h: Int): Float =
        PageTurn.estimateRowHeightPx(ReadRow.Fig(figure(1, w, h)), charsPerLine, lineH, gap, textW)

    @Test
    fun paraHeight_rowsTimesLineHeightPlusGap() {
        // 100 字 / 10 字行 = 10 行 → 10×30+16 = 316；3 字 → 1 行 = 46
        assertEquals(316f, paraH(100), 0.01f)
        assertEquals(46f, paraH(3), 0.01f)
    }

    @Test
    fun figureHeight_widthOverRatioPlusGap() {
        // 800×600 图：显示宽 1000 → 高 750 + 16 = 766；无尺寸兜底 4:3
        assertEquals(766f, figH(800, 600), 0.01f)
        assertEquals(figH(800, 600), figH(0, 0), 0.01f)
    }

    @Test
    fun forward_targetAnchorsRowWithinBudget() {
        // 5 段各 316px 高，预算 700px：acc 316→632（两行装下）→ 948（第三行放不下）停
        // 落点 = 下标 3（第一个放不下的行是新一页页首，页首锚行首）
        val rows = (0 until 5).map { ReadRow.Para(para(it.toLong(), it, "x".repeat(100))) }
        assertEquals(3, PageTurn.target(rows, 0, 1, budgetPx = 700f, charsPerLine, lineH, gap, textW))
    }

    @Test
    fun forward_lastPageClampToLastRow() {
        val rows = (0 until 3).map { ReadRow.Para(para(it.toLong(), it, "x".repeat(100))) }
        // 预算巨大：越过末行钳到 rows.size-1，不越界进章尾卡区
        assertEquals(2, PageTurn.target(rows, 0, 1, budgetPx = 10_000f, charsPerLine, lineH, gap, textW))
    }

    @Test
    fun backward_fillsBudgetFromPreviousRow() {
        // 8 段各 316px，从下标 5 向前，预算 700：行4 装下 acc=316 → 行3 装下 acc=632 →
        // 行2 装不下（948≥700）停 → 新页页首 = 2
        val rows = (0 until 8).map { ReadRow.Para(para(it.toLong(), it, "x".repeat(100))) }
        assertEquals(2, PageTurn.target(rows, 5, -1, budgetPx = 700f, charsPerLine, lineH, gap, textW))
    }

    @Test
    fun backward_atTop_staysZero() {
        val rows = (0 until 3).map { ReadRow.Para(para(it.toLong(), it, "x".repeat(100))) }
        assertEquals(0, PageTurn.target(rows, 0, -1, budgetPx = 700f, charsPerLine, lineH, gap, textW))
        assertEquals(0, PageTurn.target(rows, 1, -1, budgetPx = 700f, charsPerLine, lineH, gap, textW))
    }

    @Test
    fun oversizedFigure_skippedAsPageStart() {
        // 竖长图高 3000px > 预算 700：向后翻从下标 0 起 acc 即 ≥budget → i=1，图不作为落点页首
        val rows = listOf(
            ReadRow.Fig(figure(1, 800, 3000)),
            ReadRow.Para(para(9, 0, "正文")),
        )
        assertEquals(1, PageTurn.target(rows, 0, 1, budgetPx = 700f, charsPerLine, lineH, gap, textW))
    }

    @Test
    fun emptyRows_returnsZero() {
        assertEquals(0, PageTurn.target(emptyList(), 0, 1, 700f, charsPerLine, lineH, gap, textW))
    }

    @Test
    fun fromRowsOutOfRange_clamped() {
        val rows = (0 until 3).map { ReadRow.Para(para(it.toLong(), it, "x".repeat(100))) }
        assertTrue(PageTurn.target(rows, 99, 1, 700f, charsPerLine, lineH, gap, textW) in 0..2)
    }
}
