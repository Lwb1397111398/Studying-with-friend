package com.studyfriend.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ReviewItemEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.study.Fsrs
import com.studyfriend.app.data.study.ReviewVerdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** P1 复习两小招：一键撤销上次评分（AnkiDroid 思路）——恢复口径与日志清理的内存库验证 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UndoReviewTest {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun repo(db: StudyDatabase) = BookRepository(db, File("build/tmp/undo-test"))

    private suspend fun seed(db: StudyDatabase): Triple<Long, Long, Long> {
        val bookId = db.bookDao().insert(
            BookEntity(
                title = "书", author = "", sourceType = "TXT", filePath = "", status = "READY",
                totalChapters = 1, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        val chapterId = db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = bookId, idx = 0, title = "第一章", readState = "NOT_READ",
                    gist = null, keyTermsJson = null,
                ),
            ),
        )[0]
        val itemId = db.reviewItemDao().insertAll(
            listOf(
                ReviewItemEntity(
                    bookId = bookId, chapterId = chapterId, title = "第一章", intervalIdx = 0,
                    dueAt = 1_000L, done = false,
                ),
            ),
        )[0]
        return Triple(bookId, chapterId, itemId)
    }

    @Test
    fun undoFirstReview_restoresNewCard() = runBlocking {
        val db = db()
        try {
            val (_, _, itemId) = seed(db)
            val repo = repo(db)
            val item = db.reviewItemDao().byId(itemId)!!
            // 首评「记得」：卡进入 REVIEW 态、有 S/D
            repo.markReviewResult(itemId, Fsrs.review(item, ReviewVerdict.REMEMBER, 2_000L))
            assertTrue(db.reviewLogDao().countByItem(itemId) == 1)
            assertTrue(db.reviewItemDao().byId(itemId)!!.fsrsStability != null)

            // 撤销：恢复新卡（FSRS 列全空 + dueAt 回 dueBefore），日志清零
            assertTrue(repo.undoLastReview())
            val restored = db.reviewItemDao().byId(itemId)!!
            assertEquals(1_000L, restored.dueAt)
            assertEquals(DbValues.FSRS_STATE_LEARNING, restored.fsrsState)
            assertNull(restored.fsrsStability)
            assertNull(restored.fsrsDifficulty)
            assertNull(restored.fsrsLastReviewAt)
            assertEquals(0, db.reviewLogDao().countByItem(itemId))
        } finally {
            db.close()
        }
    }

    @Test
    fun undoSecondReview_restoresStateAfterFirstReview() = runBlocking {
        val db = db()
        try {
            val (_, _, itemId) = seed(db)
            val repo = repo(db)
            val first = db.reviewItemDao().byId(itemId)!!
            repo.markReviewResult(itemId, Fsrs.review(first, ReviewVerdict.REMEMBER, 2_000L))
            val afterFirst = db.reviewItemDao().byId(itemId)!!.copy()
            val second = db.reviewItemDao().byId(itemId)!!
            repo.markReviewResult(itemId, Fsrs.review(second, ReviewVerdict.REMEMBER, 3_000L))
            assertTrue(db.reviewItemDao().byId(itemId)!!.fsrsStability!! > afterFirst.fsrsStability!!)

            // 撤销第二轮：卡状态=第一轮评分后（即第二轮日志的 stateBefore），日志剩 1 条
            assertTrue(repo.undoLastReview())
            val restored = db.reviewItemDao().byId(itemId)!!
            assertEquals(afterFirst.dueAt, restored.dueAt)
            assertEquals(afterFirst.fsrsStability!!, restored.fsrsStability!!, 1e-9)
            assertEquals(afterFirst.fsrsDifficulty!!, restored.fsrsDifficulty!!, 1e-9)
            assertEquals(2_000L, restored.fsrsLastReviewAt)
            assertEquals(1, db.reviewLogDao().countByItem(itemId))
        } finally {
            db.close()
        }
    }

    @Test
    fun undoWithNoLog_returnsFalse() = runBlocking {
        val db = db()
        try {
            seed(db)
            assertFalse(repo(db).undoLastReview())
        } finally {
            db.close()
        }
    }
}
