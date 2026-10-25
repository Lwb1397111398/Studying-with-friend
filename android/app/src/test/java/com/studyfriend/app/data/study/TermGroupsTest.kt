package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ChapterTermsRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 术语卡盒组装纯函数（学习闭环轮）：分组/空章剔除/空书剔除/书序保持 */
class TermGroupsTest {

    private fun row(bookId: Long, bookTitle: String, chapterId: Long, chapterIdx: Int, chapterTitle: String, json: String?) =
        ChapterTermsRow(
            bookId = bookId, bookTitle = bookTitle, chapterId = chapterId,
            chapterIdx = chapterIdx, chapterTitle = chapterTitle, keyTermsJson = json ?: "",
        )

    @Test
    fun groupsByBookAndKeepsOrder() {
        val groups = buildTermGroups(
            listOf(
                row(1, "书一", 10, 0, "第一章", KeyTermsCodec.encode(listOf(KeyTermsCodec.TermPlain("代理权", "替别人做事的权限")))),
                row(2, "书二", 20, 0, "第一章", KeyTermsCodec.encode(listOf(KeyTermsCodec.TermPlain("物权", "对物的支配权")))),
                row(1, "书一", 11, 1, "第二章", KeyTermsCodec.encode(listOf(KeyTermsCodec.TermPlain("表见代理", "看起来像有权限")))),
            ),
        )
        assertEquals(2, groups.size)
        assertEquals("书一", groups[0].bookTitle)
        assertEquals(listOf("第一章", "第二章"), groups[0].chapters.map { it.chapterTitle })
        assertEquals(1, groups[1].chapters.size)
        assertEquals("物权", groups[1].chapters[0].terms[0].term)
    }

    @Test
    fun dropsEmptyChaptersAndBooks() {
        val groups = buildTermGroups(
            listOf(
                // 解析为空列表的章 → 剔除
                row(1, "书一", 10, 0, "空章", "[]"),
                // JSON 脏数据 decode 兜底空列表 → 剔除
                row(1, "书一", 11, 1, "脏章", "not-json"),
                // term 全空白 → 过滤后空 → 剔除
                row(2, "书二", 20, 0, "空白章", KeyTermsCodec.encode(listOf(KeyTermsCodec.TermPlain("  ", "x")))),
                // 唯一有效章
                row(3, "书三", 30, 0, "好章", KeyTermsCodec.encode(listOf(KeyTermsCodec.TermPlain("债", "欠人东西")))),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals("书三", groups[0].bookTitle)
        assertEquals("好章", groups[0].chapters[0].chapterTitle)
    }

    @Test
    fun emptyInputGivesEmptyGroups() {
        assertTrue(buildTermGroups(emptyList()).isEmpty())
    }
}
