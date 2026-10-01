package com.studyfriend.app.data.vision

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.db.VisionQueueEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 视觉后台队列（OPT-F）：DAO 状态机 + 书架进度投影 + CASCADE + 重建守卫查询 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisionQueueDaoTest {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    private suspend fun seedBook(db: StudyDatabase, withMarkedPara: Boolean = false): Long {
        val bookId = db.bookDao().insert(
            BookEntity(
                title = "民法总则", author = "", sourceType = DbValues.SRC_PDF,
                filePath = "uri://x", status = DbValues.BOOK_READY, totalChapters = 1,
                overviewJson = null, createdAt = 1, updatedAt = 1,
            ),
        )
        val chapterId = db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = bookId, idx = 0, title = "第一章",
                    readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
                ),
            ),
        )[0]
        db.paragraphDao().insertAll(
            listOf(
                ParagraphEntity(chapterId = chapterId, idx = 0, text = "段落一", role = DbValues.ROLE_BODY),
                ParagraphEntity(chapterId = chapterId, idx = 1, text = "段落二", role = DbValues.ROLE_BODY),
            ),
        )
        if (withMarkedPara) {
            db.paragraphDao().updateAiAction(
                db.paragraphDao().byChapter(chapterId)[0].id,
                DbValues.ACT_EXPLAIN, null, null,
            )
        }
        return bookId
    }

    private fun item(bookId: Long, pageNo: Int, status: String = DbValues.VQ_PENDING, attempts: Int = 0) =
        VisionQueueEntity(
            bookId = bookId, uri = "uri://x", pageNo = pageNo, originChars = 500,
            status = status, attempts = attempts, updatedAt = 1,
        )

    @Test
    fun queue_crud_progressAndCascade() = runBlocking {
        val db = db()
        try {
            val bookId = seedBook(db)
            db.visionQueueDao().insertAll(
                listOf(
                    item(bookId, 5),
                    item(bookId, 9, status = DbValues.VQ_DONE),
                    item(bookId, 12, status = DbValues.VQ_FAILED, attempts = 3),
                ),
            )
            // 按书查（页序）
            assertEquals(listOf(5, 9, 12), db.visionQueueDao().byBook(bookId).map { it.pageNo })
            // 进度投影：DONE=1 / 总 3
            val progress = db.visionQueueDao().progressByBookFlow().first().single()
            assertEquals(bookId, progress.bookId)
            assertEquals(1, progress.done)
            assertEquals(3, progress.total)
            // PENDING 书清单：FAILED/DONE 不算
            assertEquals(listOf(bookId), db.visionQueueDao().booksWithPending())
            // CASCADE：删书带走队列（书架删书不留孤儿任务）
            db.bookDao().delete(db.bookDao().get(bookId)!!)
            assertEquals(0, db.visionQueueDao().byBook(bookId).size)
            assertTrue(db.visionQueueDao().progressByBookFlow().first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun guard_noConsumption_returnsFalse_markedParaOrAssets_returnsTrue() = runBlocking {
        val db = db()
        try {
            val clean = seedBook(db)
            assertFalse(db.visionQueueDao().hasAnyAiConsumption(clean))
            // 任一段落被粗读标注 → 不可重建
            val marked = seedBook(db, withMarkedPara = true)
            assertTrue(db.visionQueueDao().hasAnyAiConsumption(marked))
        } finally {
            db.close()
        }
    }
}
