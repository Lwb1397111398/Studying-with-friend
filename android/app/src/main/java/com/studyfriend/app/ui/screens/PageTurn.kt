package com.studyfriend.app.ui.screens

import kotlin.math.ceil

/**
 * 翻页落点估算（第五波实景轮落地 spike/pager-demo「B 形态」，第 5 期拍板）：
 * 按行高估算从当前行首累计 ≥0.88 视口选出落点行，落点 animateScrollToItem 锚行首——
 * 页首恒为完整段开头（旧 0.88 视口平滑滚动落点常在段中），LazyColumn 只合成落点页
 * 内容（spike 实测 p50 帧 33ms→16ms）。估算误差只影响每页行数，不影响锚行首。
 */
internal object PageTurn {

    /**
     * 单行高度估算（px）。模型与产品排版口径一致：
     * 段行 = 行数×行高 + 段距（正文段距 16dp）；图行 = 显示宽/ratio + 上下边距。
     * @param charsPerLine 每行可容字符数（正文显示宽/字号 px）
     * @param lineHpx 单行行高 px（字号×行距倍数）
     * @param gapPx 行间距 px（段距/图行上下边距统一按 16dp 近似）
     * @param textW 正文显示宽 px（行宽减左右 16dp）
     */
    fun estimateRowHeightPx(
        row: ReadRow,
        charsPerLine: Float,
        lineHpx: Float,
        gapPx: Float,
        textW: Float,
    ): Float = when (row) {
        is ReadRow.Para ->
            ceil(row.p.text.length / charsPerLine).coerceAtLeast(1f) * lineHpx + gapPx
        is ReadRow.Fig -> {
            val ratio = if (row.f.width > 0 && row.f.height > 0) row.f.width.toFloat() / row.f.height else 4f / 3f
            textW / ratio + gapPx
        }
    }

    /**
     * 落点行选择（rows 下标系）。dir>0 向后：从 fromRows 起累计行高 ≥budget 的第一个
     * 放不下的行；dir<0 向前：从 fromRows-1 往回把预算填满，返回回退后的页首行。
     * 边界：向后最多 rows.size-1（正文末行；章尾卡不是落点），向前最少 0。
     */
    fun target(
        rows: List<ReadRow>,
        fromRows: Int,
        dir: Int,
        budgetPx: Float,
        charsPerLine: Float,
        lineHpx: Float,
        gapPx: Float,
        textW: Float,
    ): Int {
        if (rows.isEmpty()) return 0
        val from = fromRows.coerceIn(0, rows.size - 1)
        return if (dir > 0) {
            var acc = 0f
            var i = from
            while (i < rows.size && acc < budgetPx) {
                acc += estimateRowHeightPx(rows[i], charsPerLine, lineHpx, gapPx, textW)
                i++
            }
            i.coerceAtMost(rows.size - 1)
        } else {
            var acc = 0f
            var i = from - 1
            while (i > 0 && acc + estimateRowHeightPx(rows[i], charsPerLine, lineHpx, gapPx, textW) < budgetPx) {
                acc += estimateRowHeightPx(rows[i], charsPerLine, lineHpx, gapPx, textW)
                i--
            }
            i.coerceAtLeast(0)
        }
    }
}
