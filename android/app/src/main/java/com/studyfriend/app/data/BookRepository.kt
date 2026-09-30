package com.studyfriend.app.data

import androidx.room.withTransaction
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.QuizAttemptEntity
import com.studyfriend.app.data.db.ReviewItemEntity
import com.studyfriend.app.data.db.StudyDatabase

/** 纯 DB 操作封装（不含 AI/解析逻辑），UI 经此访问数据，不直捅 DAO */
class BookRepository(private val db: StudyDatabase) {

    /** 导入落库：事务内 书→章→段，返回 bookId。status 一律 READY（目录已在确认页过目）；
     *  章节与段落的 idx 由本方法按列表序回填（确认页传入顺序即最终顺序） */
    suspend fun importBook(
        book: BookEntity,
        chapters: List<Pair<ChapterEntity, List<ParagraphEntity>>>,
    ): Long = db.withTransaction {
        val ready = book.copy(status = DbValues.BOOK_READY, totalChapters = chapters.size)
        val bookId = db.bookDao().insert(ready)
        chapters.forEachIndexed { ci, (chapter, paras) ->
            val chapterId = db.chapterDao().insertAll(
                listOf(chapter.copy(bookId = bookId, idx = ci)),
            )[0]
            db.paragraphDao().insertAll(
                paras.mapIndexed { pi, p -> p.copy(chapterId = chapterId, idx = pi) },
            )
        }
        bookId
    }

    // ---- 书 ----
    fun shelfFlow() = db.bookDao().getAllFlow()
    fun bookProgressFlow(bookId: Long) = db.bookDao().getProgressFlow(bookId)
    suspend fun getBook(bookId: Long) = db.bookDao().get(bookId)
    suspend fun addBook(book: BookEntity) = db.bookDao().insert(book)
    suspend fun updateBook(book: BookEntity) = db.bookDao().update(book)
    suspend fun removeBook(book: BookEntity) = db.bookDao().delete(book)
    suspend fun overviewJson(bookId: Long) = db.bookDao().getOverviewJson(bookId)

    /** 调用方须保证书已存在（仅 UPDATE，bookId 无效时静默无操作） */
    suspend fun saveOverviewJson(bookId: Long, json: String, now: Long) =
        db.bookDao().saveOverviewJson(bookId, json, now)

    // ---- 目录 ----
    fun chaptersFlow(bookId: Long) = db.chapterDao().byBookFlow(bookId)
    suspend fun chapters(bookId: Long) = db.chapterDao().byBook(bookId)

    /** 目录重切：事务内删旧章重插，段落/讲解/资产等子表靠 CASCADE 清空 */
    suspend fun replaceChapters(bookId: Long, chapters: List<ChapterEntity>) {
        db.withTransaction {
            db.chapterDao().deleteByBook(bookId)
            db.chapterDao().insertAll(chapters)
        }
    }

    suspend fun updateReadState(chapterId: Long, state: String) =
        db.chapterDao().updateReadState(chapterId, state)

    suspend fun updateChapterTitle(chapterId: Long, title: String) =
        db.chapterDao().updateTitle(chapterId, title)

    suspend fun updateAiGist(chapterId: Long, gist: String, keyTermsJson: String) =
        db.chapterDao().updateAiGist(chapterId, gist, keyTermsJson)

    // ---- 段落 ----
    fun paragraphsFlow(chapterId: Long) = db.paragraphDao().byChapterFlow(chapterId)
    suspend fun paragraphs(chapterId: Long) = db.paragraphDao().byChapter(chapterId)

    /** 重切段落：事务内删旧插新 */
    suspend fun replaceParagraphs(chapterId: Long, paragraphs: List<ParagraphEntity>) {
        db.withTransaction {
            db.paragraphDao().deleteByChapter(chapterId)
            db.paragraphDao().insertAll(paragraphs)
        }
    }

    suspend fun setAiAction(id: Long, action: String, groupId: Long?, why: String?) =
        db.paragraphDao().updateAiAction(id, action, groupId, why)

    // ---- 讲解 ----
    fun notesFlow(chapterId: Long) = db.paraNoteDao().byChapterFlow(chapterId)
    suspend fun addNote(note: ParaNoteEntity) = db.paraNoteDao().insert(note)
    suspend fun clearNotes(chapterId: Long) = db.paraNoteDao().deleteForChapter(chapterId)

    // ---- 章末资产 ----
    suspend fun asset(chapterId: Long) = db.chapterAssetDao().byChapter(chapterId)
    suspend fun saveAsset(asset: ChapterAssetEntity) = db.chapterAssetDao().upsert(asset)

    // ---- 做题 ----
    suspend fun attempts(chapterId: Long) = db.quizAttemptDao().byChapter(chapterId)
    suspend fun addAttempt(attempt: QuizAttemptEntity) = db.quizAttemptDao().insert(attempt)
    // wrongAttempts 已删（M6）：错题本走 QuizAttemptDao.wrongAll + latestWrongAttempts 去重（M6 计划 §2.3）

    // ---- 复习 ----
    /** now 在订阅时固化：角标不随时间自动刷新，进入书架/首页时需重新收集或用新 now 重查 */
    fun dueCountFlow(now: Long) = db.reviewItemDao().dueCountFlow(now)
    suspend fun dueReviews(now: Long) = db.reviewItemDao().dueList(now)
    fun dueListWithBookFlow(now: Long) = db.reviewItemDao().dueListWithBookFlow(now)
    fun dueCountByBookFlow(now: Long) = db.reviewItemDao().dueCountByBookFlow(now)
    suspend fun wrongAll() = db.quizAttemptDao().wrongAll()
    suspend fun scheduleReviews(items: List<ReviewItemEntity>) = db.reviewItemDao().insertAll(items)
    suspend fun markReviewResult(id: Long, intervalIdx: Int, dueAt: Long, done: Boolean) =
        db.reviewItemDao().markResult(id, intervalIdx, dueAt, done)
    suspend fun pendingReviews(bookId: Long) = db.reviewItemDao().pendingByBook(bookId)
}
