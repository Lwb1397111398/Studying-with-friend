package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import java.io.File

class KitFigureMatcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fig(page: Int, y: Float, name: String = "f.png"): KitFigureMeta = KitFigureMeta(
        pageNo = page, rect = listOf(0.1f, y, 0.9f, y + 0.2f), kind = "photo",
        title = "t", desc = "d", file = "figures/$name", widthPx = 100, heightPx = 80,
        stagingFile = File(tmp.root, name),
    )

    @Test
    fun match_figureSlotsAnchorTrueImages() {
        // 页1 段序：[正文, FIGURE, 正文, FIGURE]；页2 段序：[FIGURE]
        val chapters = listOf(
            ParsedChapter(
                "第一章", listOf(
                    ParsedPara("第一段", DbValues.ROLE_BODY, 1),
                    ParsedPara("〔图：图1-1〕说明一", DbValues.ROLE_FIGURE, 1),
                    ParsedPara("第二段", DbValues.ROLE_BODY, 1),
                    ParsedPara("〔图：图1-2〕说明二", DbValues.ROLE_FIGURE, 1),
                    ParsedPara("〔图：图2-1〕说明三", DbValues.ROLE_FIGURE, 2),
                ),
            ),
        )
        // 故意乱序传入：matcher 应按 (页号, y) 排序后配对
        val out = KitFigureMatcher.match(
            chapters,
            listOf(fig(2, 0.5f, "c.png"), fig(1, 0.7f, "b.png"), fig(1, 0.3f, "a.png")),
        )
        assertEquals(3, out.size)
        // 页1 第 1 图（y=0.3）→ 页1 第 1 个 FIGURE 段（页内下标 1）
        assertEquals(1, out[0].pageNo)
        assertEquals(1, out[0].ordAfterPara)
        // 页1 第 2 图（y=0.7）→ 页1 第 2 个 FIGURE 段（页内下标 3）
        assertEquals(1, out[1].pageNo)
        assertEquals(3, out[1].ordAfterPara)
        // 页2 唯一图 → 页2 第 1 个 FIGURE 段（页内下标 0）
        assertEquals(2, out[2].pageNo)
        assertEquals(0, out[2].ordAfterPara)
        // seqNo 全书唯一、finalName 防碰撞
        assertEquals(listOf(1, 2, 3), out.map { it.seqNo })
        assertEquals("p1_f1.png", out[0].finalName)
        assertEquals("p2_f3.png", out[2].finalName)
        assertEquals(0.3f, out[0].bboxY0)
    }

    @Test
    fun match_pageWithoutFigureSlot_anchorsFallback() {
        // 页3 有图但正文无 FIGURE 段（图版页 thin 处理等）→ ord=-1（buildReadRows 兜底页首/章首）
        val chapters = listOf(
            ParsedChapter("附录", listOf(ParsedPara("附录文字", DbValues.ROLE_BACK, 3))),
        )
        val out = KitFigureMatcher.match(chapters, listOf(fig(3, 0.4f)))
        assertEquals(1, out.size)
        assertEquals(-1, out[0].ordAfterPara)
    }

    @Test
    fun match_noStagingFile_skipped() {
        // staging 文件缺失（KitZipReader 已滤，防御性再验）：不产出该图
        val chapters = listOf(
            ParsedChapter("第一章", listOf(ParsedPara("〔图：x〕", DbValues.ROLE_FIGURE, 1))),
        )
        val noFile = fig(1, 0.2f).copy(stagingFile = null)
        assertTrue(KitFigureMatcher.match(chapters, listOf(noFile)).isEmpty())
    }
}
