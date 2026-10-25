package com.studyfriend.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.ParaMemoEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 学习闭环轮 DAO 验证：段落批注 增删查 / 按章订阅 / 按书带章名投影 / 级联清理（内存库） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataV8Test {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun book(title: String) = BookEntity(
        title = title, author = "", sourceType = "TXT", filePath = "", status = "READY",
        totalChapters = 2, overviewJson = null, createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun chapter(bookId: Long, idx: Int, title: String = "第${idx}章") = ChapterEntity(
        bookId = bookId, idx = idx, title = title, readState = "NOT_READ",
        gist = null, keyTermsJson = null,
    )

    private fun paragraph(chapterId: Long, idx: Int, text: String) = ParagraphEntity(
        chapterId = chapterId, idx = idx, text = text, role = "BODY",
    )

    @Test
    fun memoInsertByChapterAndBookFlows() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book("民法典"))
            val chIds = db.chapterDao().insertAll(listOf(chapter(bookId, 0), chapter(bookId, 1)))
            val paras = db.paragraphDao().insertAll(
                listOf(paragraph(chIds[0], 0, "代理权授予他人"), paragraph(chIds[1], 0, "委托合同")),
            )
            db.paraMemoDao().insert(
                ParaMemoEntity(
                    bookId = bookId, chapterId = chIds[0], paraId = paras[0], paraIdx = 0,
                    quote = "代理权授予他人", text = "与委托合同的区别要记牢", createdAt = 100L,
                ),
            )
            db.paraMemoDao().insert(
                ParaMemoEntity(
                    bookId = bookId, chapterId = chIds[1], paraId = paras[1], paraIdx = 0,
                    quote = "", text = "这章整体偏难，回头重读", createdAt = 101L,
                ),
            )

            // 按章订阅：章内批注按段序排
            val ch0 = db.paraMemoDao().byChapterFlow(chIds[0]).first()
            assertEquals(1, ch0.size)
            assertEquals("代理权授予他人", ch0[0].quote)
            assertEquals(paras[0], ch0[0].paraId)

            // 按书投影：带章名/章序，最新在前（createdAt 降序）
            val all = db.paraMemoDao().byBookWithChapterFlow(bookId).first()
            assertEquals(2, all.size)
            assertEquals("第1章", all[0].chapterTitle)
            assertEquals(1, all[0].chapterIdx)
            assertEquals("这章整体偏难，回头重读", all[0].memo.text)
            assertEquals("第0章", all[1].chapterTitle)

            // 删除单条
            db.paraMemoDao().delete(all[0].memo.id)
            assertEquals(1, db.paraMemoDao().byBookWithChapterFlow(bookId).first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun memoCascadeOnChapterAndBookDelete() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book("书一"))
            val otherId = db.bookDao().insert(book("书二"))
            val chA = db.chapterDao().insertAll(listOf(chapter(bookId, 0)))[0]
            val chB = db.chapterDao().insertAll(listOf(chapter(otherId, 0)))[0]
            db.paraMemoDao().insert(
                ParaMemoEntity(
                    bookId = bookId, chapterId = chA, paraId = 99L, paraIdx = 0,
                    quote = "q", text = "t", createdAt = 1L,
                ),
            )
            db.paraMemoDao().insert(
                ParaMemoEntity(
                    bookId = otherId, chapterId = chB, paraId = 98L, paraIdx = 0,
                    quote = "q", text = "t", createdAt = 2L,
                ),
            )

            // 删章 CASCADE：只清该书批注，别书不动
            db.chapterDao().deleteByBook(bookId)
            assertTrue(db.paraMemoDao().byBookWithChapterFlow(bookId).first().isEmpty())
            assertEquals(1, db.paraMemoDao().byBookWithChapterFlow(otherId).first().size)
        } finally {
            db.close()
        }
    }

    // ---- 术语卡盒：全书有 keyTerms 的章投影 ----

    @Test
    fun chaptersWithTermsOnlyReturnsNonEmpty() = runBlocking {
        val db = db()
        try {
            val b1 = db.bookDao().insert(book("书一"))
            val b2 = db.bookDao().insert(book("书二"))
            // 书一：一章有术语、一章空（NULL）、一章空串——只应有第一章
            db.chapterDao().insertAll(
                listOf(
                    chapter(b1, 0, "有术语").let { it.copy(keyTermsJson = "[{\"term\":\"债\",\"plain\":\"欠\"}]") },
                    chapter(b1, 1, "无术语"),
                    chapter(b1, 2, "空串").let { it.copy(keyTermsJson = "") },
                ),
            )
            db.chapterDao().insertAll(
                listOf(chapter(b2, 0, "书二章").let { it.copy(keyTermsJson = "[{\"term\":\"物权\",\"plain\":\"支配\"}]") }),
            )

            val rows = db.chapterDao().chaptersWithTerms()
            assertEquals(2, rows.size)
            assertEquals("有术语", rows[0].chapterTitle)
            assertEquals("书一", rows[0].bookTitle)
            assertEquals("书二章", rows[1].chapterTitle)
            assertEquals("物权", rows[1].keyTermsJson.contains("物权").let { if (it) "物权" else "missing" })
        } finally {
            db.close()
        }
    }
}
