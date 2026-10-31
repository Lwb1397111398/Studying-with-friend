package com.studyfriend.app.data.study

import org.junit.Assert.assertEquals
import org.junit.Test

/** 批D：术语跨章去重——同一概念多章出现只留首现章 */
class TermDedupeTest {

    private fun ch(title: String, vararg terms: Pair<String, String>) =
        TermChapter(title, terms.map { KeyTermsCodec.TermPlain(it.first, it.second) })

    @Test
    fun `跨章同术语只留首现章`() {
        val out = dedupeBookTerms(
            listOf(ch("第1章", "善意取得" to "白话1"), ch("第2章", "善意取得" to "白话2"), ch("第3章", "无因管理" to "白话3")),
            listOf(11L, 22L, 33L),
        )
        assertEquals(2, out.size)
        assertEquals("善意取得", out[0].term)
        assertEquals("白话1", out[0].plain)
        assertEquals(11L, out[0].chapterId)
        assertEquals("无因管理", out[1].term)
    }

    @Test
    fun `空白差异术语视为同词`() {
        val out = dedupeBookTerms(
            listOf(ch("第1章", "善意 取得" to "白话1"), ch("第2章", "善意取得" to "白话2")),
            listOf(1L, 2L),
        )
        assertEquals(1, out.size)
        assertEquals("善意 取得", out[0].term)
    }
}
