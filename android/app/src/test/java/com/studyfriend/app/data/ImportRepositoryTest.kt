package com.studyfriend.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.importer.BookParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M2 计划 §5.10：导入落库往返（解析产物 → Entity 映射 → importBook → DAO 回读） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportRepositoryTest {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Test
    fun importBook_roundTrip() = runBlocking {
        val db = db()
        val repo = BookRepository(db)
        try {
            // 模拟 VM 侧流程：解析 → 映射 Entity → importBook
            val parsed = BookParser.parse(
                "第1单元 民法总论\n\n第一节 民事主体\n\n民事主体包括自然人。\n\n目录\n\n条目……1",
            )
            val now = 1_000L
            val book = BookEntity(
                title = "测试书", author = "作者", sourceType = DbValues.SRC_PASTE,
                filePath = "", status = DbValues.BOOK_IMPORTED, totalChapters = 0,
                overviewJson = null, createdAt = now, updatedAt = now,
            )
            val chapters = parsed.map { ch ->
                ChapterEntity(
                    bookId = 0, idx = 0, title = ch.title,
                    readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
                ) to ch.paras.map { p ->
                    ParagraphEntity(chapterId = 0, idx = 0, text = p.text, role = p.role)
                }
            }
            val bookId = repo.importBook(book, chapters)

            val saved = db.bookDao().get(bookId)!!
            assertEquals("测试书", saved.title)
            assertEquals(DbValues.BOOK_READY, saved.status)
            assertEquals(2, saved.totalChapters)

            val savedChapters = db.chapterDao().byBook(bookId)
            assertEquals(listOf("第1单元 民法总论", "目录"), savedChapters.map { it.title })
            assertEquals(listOf(0, 1), savedChapters.map { it.idx })

            val unitParas = db.paragraphDao().byChapter(savedChapters[0].id)
            assertEquals(2, unitParas.size)
            assertEquals(listOf(0, 1), unitParas.map { it.idx })
            assertEquals("第一节 民事主体", unitParas[0].text)
            assertEquals("民事主体包括自然人。", unitParas[1].text)
            assertEquals(DbValues.ROLE_BODY, unitParas[0].role)

            val tocParas = db.paragraphDao().byChapter(savedChapters[1].id)
            assertEquals(1, tocParas.size)
            assertEquals(0, tocParas[0].idx)
            assertEquals(DbValues.ROLE_FRONT, tocParas[0].role)

            assertEquals(1, db.bookDao().getAllFlow().first().size)
        } finally {
            db.close()
        }
    }
}
