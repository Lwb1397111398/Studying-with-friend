package com.studyfriend.app.data.vision

import com.studyfriend.app.data.db.ParagraphEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重建纯函数（P6c R1 方向 A 结构保全）：目录页推断 + 覆盖守卫 + D1/D2 页归属 */
class VisionRebuilderPureTest {

    @Test
    fun tocLike_sampleVisionToc_inferred() {
        val toc = listOf(
            "目 录", "第一章 担保法概述 4", "第二章 保证担保 6", "第三章 抵押权 8",
            "第四章 质权 10", "第五章 留置权 12", "第六章 定金 14",
            "第七章 非典型担保 16", "第八章 担保物权的实现程序 18",
        )
        assertTrue(VisionRebuilder.inferTocLike(toc))
    }

    @Test
    fun tocLike_dotLeaderEntries_inferred() {
        // 真书目录形态：点线被视觉转写保留时同样要认得
        assertTrue(
            VisionRebuilder.inferTocLike(
                listOf("目 录", "第一章 概述…………12", "第二章 保证…………34", "第三章 抵押…………56"),
            ),
        )
    }

    @Test
    fun tocLike_bodyParagraphs_notInferred() {
        val content = listOf(
            "担保法制度讲义",
            "当事人在订立抵押合同时,应当对抵押财产的权属状况进行审慎核查。",
            "担保物权的设立以主债权的有效存在为前提,主债权无效时担保合同亦不能发生效力。",
        )
        assertFalse(VisionRebuilder.inferTocLike(content))
    }

    @Test
    fun tocLike_belowThreshold_notInferred() {
        assertFalse(VisionRebuilder.inferTocLike(listOf("第一章 担保法概述 4", "第二章 保证担保 6")))
    }

    @Test
    fun tocLike_legalArticleNumbers_notInferred() {
        // 法条「第十条」不在 [章节回] 内，正文密集引法条不误判
        val content = listOf(
            "第十条 当事人订立合同后应当遵循诚信原则。",
            "第十一条 其他法律对民事关系另有规定的依照其规定。",
            "第十二条 民事活动的当事人应当遵循自愿原则。",
        )
        assertFalse(VisionRebuilder.inferTocLike(content))
    }

    @Test
    fun tocLike_emptyAndSingle_notInferred() {
        assertFalse(VisionRebuilder.inferTocLike(emptyList()))
        assertFalse(VisionRebuilder.inferTocLike(listOf("第一章 担保法概述 4")))
    }

    @Test
    fun coverage_c3aWipeShape_blocked() {
        // C3a 实录形态：现书 14772 字、产物 89 字 → 必须拦下
        assertFalse(VisionRebuilder.coverageOk(89, 14772))
    }

    @Test
    fun coverage_fullReplacement_equalChars_passes() {
        assertTrue(VisionRebuilder.coverageOk(14772, 14772))
    }

    @Test
    fun coverage_boundary_exactly80_passes_below80_blocks() {
        assertTrue(VisionRebuilder.coverageOk(80, 100))
        assertFalse(VisionRebuilder.coverageOk(79, 100))
    }

    @Test
    fun coverage_emptyBook_passes() {
        assertTrue(VisionRebuilder.coverageOk(0, 0))
        assertTrue(VisionRebuilder.coverageOk(100, 0))
    }

    // ---- D1/D2 页归属（R1 方向 A 拍板值，计划案 §2.3）----

    private fun row(chapterId: Long, text: String, pageNo: Int) = ParagraphEntity(
        chapterId = chapterId, idx = 0, text = text,
        role = "BODY", pageNo = pageNo,
    )

    @Test
    fun pageOwnerLast_straddlePage_returnsLastRowChapter() {
        // D1：页内末行所在章吃下整页（章首跨页时后章不吃掉前章开头）
        val rows = listOf(
            row(10L, "前章在页内的最后一行", 5),
            row(11L, "后章在本页开头的行", 5),
        )
        assertEquals(11L, VisionRebuilder.pageOwnerLast(rows))
    }

    @Test
    fun orphanOwner_floor_thenCeil() {
        // D2：孤儿页（底稿无该页行，如视觉新见的目录页）floor 优先、ceil 兜底
        val owners = mapOf(2 to 10L, 6 to 11L)
        assertEquals(10L, VisionRebuilder.orphanOwner(owners, 3)) // floor：页3 → ≤3 最近页2
        assertEquals(10L, VisionRebuilder.orphanOwner(owners, 2)) // 恰等：页2 本身
        assertEquals(11L, VisionRebuilder.orphanOwner(owners, 7)) // ceil 兜底：页7 > 最大有字页6
    }

    @Test
    fun orphanOwner_emptyOwners_returnsNull() {
        // 不可达路径（rows 非空守卫先行），防御式兜底返回 null
        assertNull(VisionRebuilder.orphanOwner(emptyMap(), 1))
    }
}
