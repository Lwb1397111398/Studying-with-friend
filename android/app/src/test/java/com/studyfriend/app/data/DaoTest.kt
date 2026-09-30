package com.studyfriend.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.QuizAttemptEntity
import com.studyfriend.app.data.db.ReviewItemEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M1 DAO 层验证：CRUD / CASCADE / 投影 / 复习推进（内存库，Room 自动启用 FK 约束） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DaoTest {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun book(title: String = "民法典入门", totalChapters: Int = 0) = BookEntity(
        title = title, author = "佚名", sourceType = "TXT", filePath = "/tmp/x.txt",
        status = "IMPORTED", totalChapters = totalChapters, overviewJson = null,
        createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun chapter(bookId: Long, idx: Int, title: String = "第${idx}章") = ChapterEntity(
        bookId = bookId, idx = idx, title = title, readState = "NOT_READ",
        gist = null, keyTermsJson = null,
    )

    private fun paragraph(chapterId: Long, idx: Int) = ParagraphEntity(
        chapterId = chapterId, idx = idx, text = "段落$idx 内容", role = "BODY",
    )

    private fun asset(chapterId: Long, model: String, version: String, summary: String) =
        ChapterAssetEntity(
            chapterId = chapterId, summaryMd = summary, mindmapTree = "\t根", mindmapJson = null,
            memoryMd = "记忆", chainMd = "串联", quizJson = "[]",
            model = model, promptVersion = version, createdAt = 5_000L,
        )

    @Test
    fun crudAndCascade() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book())
            val chIds = db.chapterDao().insertAll(listOf(chapter(bookId, 0), chapter(bookId, 1)))
            val paraIds = db.paragraphDao().insertAll(
                listOf(paragraph(chIds[0], 0), paragraph(chIds[0], 1)),
            )
            db.paragraphDao().updateAiAction(paraIds[0], DbValues.ACT_EXPLAIN, null, "核心概念")
            val updated = db.paragraphDao().byChapter(chIds[0])
            assertEquals(DbValues.ACT_EXPLAIN, updated[0].aiAction)
            assertEquals("核心概念", updated[0].why)

            // 铺满全部子表，验证删书时 CASCADE 全链清空
            db.paraNoteDao().insert(
                ParaNoteEntity(
                    chapterId = chIds[0], paraIds = "[0]", title = "t", friendly = "f",
                    analogy = null, keyPointsJson = "[]", memoryHook = null, questionsJson = null,
                    model = "m", promptVersion = "v", createdAt = 1L,
                ),
            )
            db.chapterAssetDao().upsert(asset(chIds[0], "m", "v", "s"))
            db.quizAttemptDao().insert(
                QuizAttemptEntity(chapterId = chIds[0], qIndex = 0, answer = "A", verdict = "WRONG", feedback = null, createdAt = 1L),
            )
            db.reviewItemDao().insertAll(
                listOf(
                    ReviewItemEntity(
                        bookId = bookId, chapterId = chIds[0], title = "第0章",
                        intervalIdx = 0, dueAt = 1L, done = false,
                    ),
                ),
            )

            // 删书 → 章、段落、讲解、资产、做题、复习项全链 CASCADE 清空
            db.bookDao().delete(db.bookDao().get(bookId)!!)
            assertTrue(db.chapterDao().byBook(bookId).isEmpty())
            assertTrue(db.paragraphDao().byChapter(chIds[0]).isEmpty())
            assertTrue(db.paraNoteDao().byChapterFlow(chIds[0]).first().isEmpty())
            assertTrue(db.chapterAssetDao().byChapter(chIds[0]) == null)
            assertTrue(db.quizAttemptDao().byChapter(chIds[0]).isEmpty())
            assertTrue(db.reviewItemDao().pendingByBook(bookId).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun replaceChapters_rebuildsDirectoryAndClearsChildren() = runBlocking {
        val db = db()
        val repo = BookRepository(db)
        try {
            val bookId = repo.addBook(book())
            val chIds = db.chapterDao().insertAll(listOf(chapter(bookId, 0), chapter(bookId, 1)))
            db.paragraphDao().insertAll(listOf(paragraph(chIds[0], 0)))

            // 目录重切：旧章全删（段落等子表靠 CASCADE 清空），新目录生效
            repo.replaceChapters(bookId, listOf(chapter(bookId, 0, "重切章")))
            val chapters = repo.chapters(bookId)
            assertEquals(1, chapters.size)
            assertEquals("重切章", chapters[0].title)
            assertTrue(db.paragraphDao().byChapter(chIds[0]).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun progressProjection_countsDoneChapters() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book(totalChapters = 2))
            val chIds = db.chapterDao().insertAll(listOf(chapter(bookId, 0), chapter(bookId, 1)))
            db.chapterDao().updateReadState(chIds[0], DbValues.READ_DONE)
            val p = db.bookDao().getProgressFlow(bookId).first()
            assertEquals(2, p!!.totalChapters)
            assertEquals(1, p.readChapters)
        } finally {
            db.close()
        }
    }

    @Test
    fun review_dueListAndMarkResult() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book())
            val chId = db.chapterDao().insertAll(listOf(chapter(bookId, 0)))[0]
            val now = 1_000_000L
            val item = { dueAt: Long ->
                ReviewItemEntity(
                    bookId = bookId, chapterId = chId, title = "第0章",
                    intervalIdx = 0, dueAt = dueAt, done = false,
                )
            }
            // 同一 (bookId,chapterId,title) 重复排期被 IGNORE：
            // 若唯一约束失效第二条会落库，这里钉死总 pending 行数必须为 1
            db.reviewItemDao().insertAll(listOf(item(now - 1_000L)))
            db.reviewItemDao().insertAll(listOf(item(now + 9_999_999L)))
            assertEquals(1, db.reviewItemDao().pendingByBook(bookId).size)
            val due = db.reviewItemDao().dueList(now)
            assertEquals(1, due.size)
            assertEquals(now - 1_000L, due[0].dueAt)

            // 记得 → 推进间隔
            db.reviewItemDao().markResult(due[0].id, 2, now + 7L * 86_400_000, false)
            val pending = db.reviewItemDao().pendingByBook(bookId)
            assertEquals(2, pending[0].intervalIdx)

            // 忘了 → 归 0 重新排期
            db.reviewItemDao().markResult(pending[0].id, 0, now + 86_400_000, false)
            assertEquals(0, db.reviewItemDao().pendingByBook(bookId)[0].intervalIdx)
        } finally {
            db.close()
        }
    }

    @Test
    fun wrongAll_joinsBookTitleForDedup() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book())
            val chId = db.chapterDao().insertAll(listOf(chapter(bookId, 0)))[0]
            db.quizAttemptDao().insert(
                QuizAttemptEntity(chapterId = chId, qIndex = 0, answer = "A", verdict = DbValues.VERDICT_CORRECT, feedback = null, createdAt = 1L),
            )
            db.quizAttemptDao().insert(
                QuizAttemptEntity(chapterId = chId, qIndex = 1, answer = "B", verdict = DbValues.VERDICT_WRONG, feedback = "再想想", createdAt = 2L),
            )
            // M6：wrongAll 不过滤 verdict（去重契约靠 latestWrongAttempts 两步走），但必须 JOIN 出书名
            val all = db.quizAttemptDao().wrongAll()
            assertEquals(2, all.size)
            assertEquals("民法典入门", all[0].bookTitle)
            assertEquals("第0章", all[0].chapterTitle)
        } finally {
            db.close()
        }
    }

    @Test
    fun review_dueListWithBookFlowAndCountByBook() = runBlocking {
        val db = db()
        try {
            val bookA = db.bookDao().insert(book(title = "书A"))
            val bookB = db.bookDao().insert(book(title = "书B"))
            val chA1 = db.chapterDao().insertAll(listOf(chapter(bookA, 0)))[0]
            val chA2 = db.chapterDao().insertAll(listOf(chapter(bookA, 1)))[0]
            val chB1 = db.chapterDao().insertAll(listOf(chapter(bookB, 0)))[0]
            val now = 1_000_000L
            fun item(chapterId: Long, bookId: Long, title: String, dueAt: Long) = ReviewItemEntity(
                bookId = bookId, chapterId = chapterId, title = title,
                intervalIdx = 0, dueAt = dueAt, done = false,
            )
            db.reviewItemDao().insertAll(
                listOf(
                    item(chA1, bookA, "第0章", now - 1_000L),
                    item(chA2, bookA, "第1章", now - 2_000L),
                    item(chB1, bookB, "第0章", now - 3_000L),
                    item(chB1, bookB, "第0章", now + 9_999_999L), // 同章未来到期：不计入 due、不虚增计数
                ),
            )
            val due = db.reviewItemDao().dueListWithBookFlow(now).first()
            assertEquals(3, due.size)
            assertEquals("书B", due[0].bookTitle) // ORDER BY dueAt：now-3000 最先到期
            assertEquals("书A", due[1].bookTitle)
            assertTrue(due.all { it.item.dueAt <= now })

            val counts = db.reviewItemDao().dueCountByBookFlow(now).first()
            assertEquals(2, counts.size)
            assertEquals(bookA to 2L, counts.first { it.bookId == bookA }.let { it.bookId to it.cnt.toLong() })
            assertEquals(1L, counts.first { it.bookId == bookB }.cnt.toLong())
        } finally {
            db.close()
        }
    }

    @Test
    fun chapterWithAsset_projectionCarriesPromptVersion() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book())
            val chIds = db.chapterDao().insertAll(listOf(chapter(bookId, 0), chapter(bookId, 1)))
            db.chapterAssetDao().upsert(asset(chIds[0], "m", "summary-pack-v1", "总结"))
            val rows = db.chapterDao().byBookFlow(bookId).first()
            assertEquals("summary-pack-v1", rows[0].assetPromptVersion)
            assertTrue(rows[0].hasAsset)
            assertTrue("summaryMd 非空章门控放行", rows[0].assetSummaryPresent)
            assertEquals(null, rows[1].assetPromptVersion)
            assertFalse(rows[1].hasAsset)
            assertFalse("无资产章 summaryPresent 为假", rows[1].assetSummaryPresent)
        } finally {
            db.close()
        }
    }

    @Test
    fun assetUpsert_keepsPrimaryAndRefreshesAll() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book())
            val chId = db.chapterDao().insertAll(listOf(chapter(bookId, 0)))[0]
            db.chapterAssetDao().upsert(asset(chId, "model-a", "v1", "旧总结"))
            db.chapterAssetDao().upsert(asset(chId, "model-b", "v2", "新总结"))
            val a = db.chapterAssetDao().byChapter(chId)!!
            assertEquals(chId, a.chapterId)
            assertEquals("model-b", a.model)
            assertEquals("v2", a.promptVersion)
            assertEquals("新总结", a.summaryMd)
        } finally {
            db.close()
        }
    }

    @Test
    fun chapterWithAssetProjection_hasAssetFlag() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book())
            val chIds = db.chapterDao().insertAll(listOf(chapter(bookId, 0), chapter(bookId, 1)))
            db.chapterAssetDao().upsert(asset(chIds[0], "m", "v", "s"))
            val rows = db.chapterDao().byBookFlow(bookId).first()
            assertEquals(2, rows.size)
            val withAsset = rows.first { it.id == chIds[0] }
            val withoutAsset = rows.first { it.id == chIds[1] }
            assertTrue(withAsset.hasAsset)
            assertFalse(withoutAsset.hasAsset)
        } finally {
            db.close()
        }
    }
}
