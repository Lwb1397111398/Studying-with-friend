package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ParagraphEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4a §5-1：确定性分块（5 例）——段不跨块、双上限、超长段独占、同输入同划分 */
class RoughReadChunksTest {

    private fun para(idx: Int, text: String) = ParagraphEntity(
        chapterId = 1L, idx = idx, text = text, role = "BODY",
    )

    private fun idxsOf(blocks: List<RoughReadBlock>) = blocks.map { b -> b.idxs.sorted() }

    @Test
    fun deterministic_sameInputSameSplit() {
        val paras = (0 until 95).map { para(it, "第$it 段内容" + "甲".repeat(it * 7)) }
        val a = RoughReadChunks.split(paras)
        val b = RoughReadChunks.split(paras)
        assertEquals(idxsOf(a), idxsOf(b))
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun paragraphNeverSplitsAcrossBlocks() {
        val paras = (0 until 120).map { para(it, "内容" + "乙".repeat(it % 500)) }
        val blocks = RoughReadChunks.split(paras)
        val seen = HashSet<Int>()
        for (b in blocks) {
            assertTrue("块大小 ${b.paragraphs.size} 超 ${RoughReadChunks.MAX_BLOCK_PARAS} 段上限", b.paragraphs.size <= RoughReadChunks.MAX_BLOCK_PARAS)
            assertTrue("块 ${b.chars} 字超 ${RoughReadChunks.MAX_BLOCK_CHARS} 上限", b.chars <= RoughReadChunks.MAX_BLOCK_CHARS)
            for (p in b.paragraphs) assertTrue("段 ${p.idx} 重复出现", seen.add(p.idx))
        }
        assertEquals("并集应覆盖全部段落", (0 until 120).toSet(), seen)
    }

    @Test
    fun charBoundary_exactlyLimitStaysTogether() {
        val half = "字".repeat(15_000)
        val paras = listOf(para(0, half), para(1, half), para(2, "字".repeat(15_000)))
        val blocks = RoughReadChunks.split(paras)
        // 段 0+1 恰好 30000 不触发 flush；段 2 加入会超 → 独立成块
        assertEquals(listOf(listOf(0, 1), listOf(2)), idxsOf(blocks))
    }

    @Test
    fun paragraphLimitTriggersFlush() {
        val paras = (0 until RoughReadChunks.MAX_BLOCK_PARAS + 1).map { para(it, "短段") }
        val blocks = RoughReadChunks.split(paras)
        assertEquals(
            listOf((0 until RoughReadChunks.MAX_BLOCK_PARAS).toList(), listOf(RoughReadChunks.MAX_BLOCK_PARAS)),
            idxsOf(blocks),
        )
    }

    @Test
    fun oversizedParagraphGetsOwnBlock() {
        val big = para(1, "巨".repeat(RoughReadChunks.MAX_BLOCK_CHARS + 1000))
        val paras = listOf(para(0, "短段"), big, para(2, "短段2"))
        val blocks = RoughReadChunks.split(paras)
        assertEquals(3, blocks.size)
        assertEquals(listOf(1), idxsOf(blocks)[1])
        assertEquals(RoughReadChunks.MAX_BLOCK_CHARS + 1000, blocks[1].chars)
    }
}
