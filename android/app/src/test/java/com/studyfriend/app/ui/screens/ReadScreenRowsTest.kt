package com.studyfriend.app.ui.screens

import com.studyfriend.app.data.db.FigureEntity
import com.studyfriend.app.data.db.ParagraphEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 §E 混排单测（J3 的逻辑层前置）：buildReadRows 三态口径 + clamp + 同锚稳定序；
 * calcInSampleSize 采样真值表。
 */
class ReadScreenRowsTest {

    private fun para(id: Long, idx: Int, pageNo: Int?) = ParagraphEntity(
        id = id, chapterId = 1, idx = idx, text = "段$idx", role = "BODY", pageNo = pageNo,
    )

    private fun figure(
        id: Long,
        pageNo: Int,
        ord: Int,
        seqNo: Int = 1,
    ) = FigureEntity(
        id = id, bookId = 1, chapterId = 1, pageNo = pageNo, ordAfterPara = ord, bboxY0 = 0f,
        seqNo = seqNo, file = "figures/1/p${pageNo}_f$seqNo.png", width = 800, height = 600,
        format = "png", md5 = "m$seqNo", createdAt = 0L,
    )

    // p1(p2) p2(p3) p3(p3) p4(p4)：渲染列表下标 0..3
    private val paras = listOf(
        para(11, 0, 2), para(12, 1, 3), para(13, 2, 3), para(14, 3, 4),
    )

    @Test
    fun noFigures_identity() {
        val rows = buildReadRows(paras, emptyList())
        assertEquals(4, rows.size)
        assertTrue(rows.all { it is ReadRow.Para })
    }

    @Test
    fun figureAfterAnchorPara() {
        // ord=0：插在 p3 页第 0 段（p12，渲染下标 1）之后
        val rows = buildReadRows(paras, listOf(figure(1, pageNo = 3, ord = 0)))
        assertEquals(
            listOf<ReadRow>(
                ReadRow.Para(paras[0]), ReadRow.Para(paras[1]),
                ReadRow.Fig(figure(1, 3, 0)),
                ReadRow.Para(paras[2]), ReadRow.Para(paras[3]),
            ),
            rows,
        )
    }

    @Test
    fun ordNegative_pageHasParas_beforeFirst() {
        val rows = buildReadRows(paras, listOf(figure(1, pageNo = 3, ord = -1)))
        assertEquals(1, rows.indexOfFirst { it is ReadRow.Fig }) // p12 之前
    }

    @Test
    fun pageWithoutParas_afterLastEarlierPage() {
        // p5 无段：插在页码小于 5 的最后一段（p14，下标 3）之后 → 章末
        val rows = buildReadRows(paras, listOf(figure(1, pageNo = 5, ord = -1)))
        assertEquals(4, rows.indexOfFirst { it is ReadRow.Fig })
    }

    @Test
    fun beforeAllChapters_chapterHead() {
        // 首段页码 2 > 图页码 1：插章首（下标 0）
        val rows = buildReadRows(paras, listOf(figure(1, pageNo = 1, ord = -1)))
        assertEquals(0, rows.indexOfFirst { it is ReadRow.Fig })
    }

    @Test
    fun ordClamp_beyondPageParas_bottomOfPage() {
        // ord=9 clamp 到页内最后一段（p13，下标 2）之后
        val rows = buildReadRows(paras, listOf(figure(1, pageNo = 3, ord = 9)))
        assertEquals(3, rows.indexOfFirst { it is ReadRow.Fig })
    }

    @Test
    fun sameAnchorTwoFigures_daoOrderStable() {
        val rows = buildReadRows(
            paras,
            listOf(figure(1, pageNo = 3, ord = 0, seqNo = 1), figure(2, pageNo = 3, ord = 0, seqNo = 2)),
        )
        val figs = rows.filterIsInstance<ReadRow.Fig>()
        assertEquals(listOf(1L, 2L), figs.map { it.f.id }) // byChapterFlow 序保持
    }

    @Test
    fun txtParasNullPageNo_fallToChapterHeadOrEarlierPage() {
        // 全 null 页码（TXT 书）：无「页码更小」的段可比 → 章首
        val txtParas = listOf(para(21, 0, null), para(22, 1, null))
        assertEquals(0, buildReadRows(txtParas, listOf(figure(1, pageNo = 3, ord = 0)))
            .indexOfFirst { it is ReadRow.Fig })
        // 混合：前一段带页码 2 → 落其后（下标 1）；null 页码段不参与比较
        val mixed = listOf(para(21, 0, 2), para(22, 1, null))
        assertEquals(1, buildReadRows(mixed, listOf(figure(1, pageNo = 3, ord = 0)))
            .indexOfFirst { it is ReadRow.Fig })
    }

    @Test
    fun inSampleSize_truthTable() {
        // 小图不采样
        assertEquals(1, calcInSampleSize(1200, 900, 1080, 2340))
        // 双维都超预算才翻倍；断在半宽不足的一侧
        assertEquals(4, calcInSampleSize(2000, 1000, 500, 200))
        // 恰好一半：不再向下
        assertEquals(2, calcInSampleSize(2000, 1000, 999, 499))
    }
}
