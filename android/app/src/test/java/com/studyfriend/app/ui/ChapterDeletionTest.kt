package com.studyfriend.app.ui

import com.studyfriend.app.ui.screens.keptOriginalIndex
import com.studyfriend.app.ui.screens.keptOriginalIndices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删假章纯函数单测（P5 计划案 §6 采纳项「keptIndices 三边界用例」）：
 * 删中/删首/删尾/仅留一章/全删/越界 deleted 防御 + localIndex 翻译的越界与负数兜底。
 */
class ChapterDeletionTest {

    @Test
    fun deleteMiddle_keepsOrder() {
        assertEquals(listOf(0, 2, 3), keptOriginalIndices(4, setOf(1)).toList())
    }

    @Test
    fun deleteFirst_keepsRest() {
        assertEquals(listOf(1, 2), keptOriginalIndices(3, setOf(0)).toList())
    }

    @Test
    fun deleteLast_keepsHead() {
        assertEquals(listOf(0, 1), keptOriginalIndices(3, setOf(2)).toList())
    }

    @Test
    fun onlyOneKept() {
        assertEquals(listOf(1), keptOriginalIndices(3, setOf(0, 2)).toList())
    }

    @Test
    fun deleteAll_yieldsEmpty() {
        assertTrue(keptOriginalIndices(2, setOf(0, 1)).isEmpty())
    }

    @Test
    fun outOfRangeDeletedIgnored() {
        assertEquals(listOf(0, 1), keptOriginalIndices(2, setOf(5, -1)).toList())
    }

    @Test
    fun emptyDeleted_allKept() {
        assertEquals(listOf(0, 1, 2), keptOriginalIndices(3, emptySet()).toList())
    }

    @Test
    fun translate_validLocalIndex() {
        assertEquals(2, keptOriginalIndex(intArrayOf(0, 2, 3), 1))
    }

    @Test
    fun translate_outOfRangeYieldsNull() {
        assertNull(keptOriginalIndex(intArrayOf(0, 2), 5))
    }

    @Test
    fun translate_negativeYieldsNull() {
        assertNull(keptOriginalIndex(intArrayOf(0, 2), -1))
    }

    @Test
    fun translate_emptyKeptYieldsNull() {
        assertNull(keptOriginalIndex(intArrayOf(), 0))
    }
}
