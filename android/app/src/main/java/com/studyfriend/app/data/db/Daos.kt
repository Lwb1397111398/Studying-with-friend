package com.studyfriend.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingDao {
    @Upsert
    suspend fun upsert(entity: SettingEntity)

    @Query("SELECT * FROM settings WHERE `key` = :key")
    suspend fun get(key: String): SettingEntity?

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun delete(key: String)
}

/** 书架行投影：书信息 + 已读章数 */
data class BookProgress(
    val id: Long,
    val title: String,
    val author: String,
    val status: String,
    val totalChapters: Int,
    val readChapters: Int,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY updatedAt DESC")
    fun getAllFlow(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query(
        """SELECT b.id AS id, b.title AS title, b.author AS author, b.status AS status,
               b.totalChapters AS totalChapters,
               (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id AND c.readState = 'DONE') AS readChapters
        FROM books b WHERE b.id = :id""",
    )
    fun getProgressFlow(id: Long): Flow<BookProgress?>

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("SELECT overviewJson FROM books WHERE id = :id")
    suspend fun getOverviewJson(id: Long): String?

    @Query("UPDATE books SET overviewJson = :json, updatedAt = :now WHERE id = :id")
    suspend fun saveOverviewJson(id: Long, json: String, now: Long)
}

/** 目录行投影：章节信息 + 是否已有章末资产（promptVersion/summaryPresent 供 M6 总览入口门控） */
data class ChapterWithAsset(
    val id: Long,
    val idx: Int,
    val title: String,
    val readState: String,
    val gist: String?,
    val keyTermsJson: String?,
    val hasAsset: Boolean,
    val assetPromptVersion: String?,
    val assetSummaryPresent: Boolean,
)

@Dao
interface ChapterDao {
    @Query(
        """SELECT c.id AS id, c.idx AS idx, c.title AS title, c.readState AS readState,
               c.gist AS gist, c.keyTermsJson AS keyTermsJson,
               (a.chapterId IS NOT NULL) AS hasAsset,
               a.promptVersion AS assetPromptVersion,
               (a.summaryMd IS NOT NULL AND a.summaryMd != '') AS assetSummaryPresent
        FROM chapters c LEFT JOIN chapter_assets a ON a.chapterId = c.id
        WHERE c.bookId = :bookId ORDER BY c.idx""",
    )
    fun byBookFlow(bookId: Long): Flow<List<ChapterWithAsset>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx")
    suspend fun byBook(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun byIdOnce(chapterId: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    fun byIdFlow(chapterId: Long): Flow<ChapterEntity?>

    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    @Query("UPDATE chapters SET readState = :state WHERE id = :id")
    suspend fun updateReadState(id: Long, state: String)

    @Query("UPDATE chapters SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE chapters SET gist = :gist, keyTermsJson = :keyTermsJson WHERE id = :id")
    suspend fun updateAiGist(id: Long, gist: String, keyTermsJson: String)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)
}

@Dao
interface ParagraphDao {
    @Query("SELECT * FROM paragraphs WHERE chapterId = :chapterId ORDER BY idx")
    fun byChapterFlow(chapterId: Long): Flow<List<ParagraphEntity>>

    @Query("SELECT * FROM paragraphs WHERE chapterId = :chapterId ORDER BY idx")
    suspend fun byChapter(chapterId: Long): List<ParagraphEntity>

    @Insert
    suspend fun insertAll(paragraphs: List<ParagraphEntity>): List<Long>

    @Query("UPDATE paragraphs SET aiAction = :action, groupId = :groupId, why = :why WHERE id = :id")
    suspend fun updateAiAction(id: Long, action: String, groupId: Long?, why: String?)

    @Query("DELETE FROM paragraphs WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: Long)
}

@Dao
interface ParaNoteDao {
    @Query("SELECT * FROM para_notes WHERE chapterId = :chapterId ORDER BY id")
    fun byChapterFlow(chapterId: Long): Flow<List<ParaNoteEntity>>

    @Query("SELECT * FROM para_notes WHERE chapterId = :chapterId ORDER BY id")
    suspend fun byChapterOnce(chapterId: Long): List<ParaNoteEntity>

    @Insert
    suspend fun insert(note: ParaNoteEntity): Long

    @Query("DELETE FROM para_notes WHERE chapterId = :chapterId")
    suspend fun deleteForChapter(chapterId: Long)

    /** 同单元重生成/过期重生成：先删后插保证同 paraIds 唯一（计划 M4b §2.4） */
    @Query("DELETE FROM para_notes WHERE paraIds = :paraIds")
    suspend fun deleteByParaIds(paraIds: String)

    /** 孤儿清理：force 粗读改变组结构后旧 paraIds 不再匹配任何单元（计划 M4b §2.4） */
    @Query("DELETE FROM para_notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ChapterAssetDao {
    @Query("SELECT * FROM chapter_assets WHERE chapterId = :chapterId")
    suspend fun byChapter(chapterId: Long): ChapterAssetEntity?

    /** 总结页订阅：生成完成 upsert 后 UI 自动切换到资产视图 */
    @Query("SELECT * FROM chapter_assets WHERE chapterId = :chapterId")
    fun byChapterFlow(chapterId: Long): Flow<ChapterAssetEntity?>

    @Upsert
    suspend fun upsert(asset: ChapterAssetEntity)
}

/** 错题本投影：错题 + 所在章名/书名（M6）；去重语义见 latestWrongAttempts（取最新再过滤 WRONG） */
data class WrongAttempt(
    val id: Long,
    val bookId: Long,
    val chapterId: Long,
    val chapterTitle: String,
    val bookTitle: String,
    val qIndex: Int,
    val answer: String,
    val verdict: String,
    val feedback: String?,
    val createdAt: Long,
)

@Dao
interface QuizAttemptDao {
    @Query("SELECT * FROM quiz_attempts WHERE chapterId = :chapterId ORDER BY createdAt")
    suspend fun byChapter(chapterId: Long): List<QuizAttemptEntity>

    @Query("SELECT * FROM quiz_attempts WHERE chapterId = :chapterId ORDER BY createdAt")
    fun byChapterFlow(chapterId: Long): Flow<List<QuizAttemptEntity>>

    /** 总结包重生成后清空本章作答：题目身份随资产版本失效（M5 计划 §2.4 评审 P1-3） */
    @Query("DELETE FROM quiz_attempts WHERE chapterId = :chapterId")
    suspend fun deleteForChapter(chapterId: Long)

    @Insert
    suspend fun insert(attempt: QuizAttemptEntity): Long

    /** 错题本全量源（M6）：不过滤 verdict——"先错后对"的 CORRECT 行必须在场才能让题目出列（M6 计划 §2.3） */
    @Query(
        """SELECT qa.id AS id, c.bookId AS bookId, qa.chapterId AS chapterId,
               c.title AS chapterTitle, b.title AS bookTitle, qa.qIndex AS qIndex,
               qa.answer AS answer, qa.verdict AS verdict, qa.feedback AS feedback,
               qa.createdAt AS createdAt
        FROM quiz_attempts qa JOIN chapters c ON c.id = qa.chapterId
        JOIN books b ON b.id = c.bookId
        ORDER BY qa.createdAt DESC""",
    )
    suspend fun wrongAll(): List<WrongAttempt>
}

@Dao
interface ReviewItemDao {
    @Query("SELECT COUNT(*) FROM review_items WHERE done = 0 AND dueAt <= :now")
    fun dueCountFlow(now: Long): Flow<Int>

    @Query("SELECT * FROM review_items WHERE done = 0 AND dueAt <= :now ORDER BY dueAt")
    suspend fun dueList(now: Long): List<ReviewItemEntity>

    /** 复习中心到期列表（M6）：带书名；Flow 版——markResult 落库后 Room 失效通知自动刷新（M6 计划 §2.4） */
    @Query(
        """SELECT r.*, b.title AS bookTitle FROM review_items r
        JOIN books b ON b.id = r.bookId
        WHERE r.done = 0 AND r.dueAt <= :now ORDER BY r.dueAt""",
    )
    fun dueListWithBookFlow(now: Long): Flow<List<DueItem>>

    /** 书架徽标（M6）：按书数到期章数（DISTINCT 章防同章多行虚增）；now 订阅时固化，进页重新收集拿新值 */
    @Query(
        """SELECT c.bookId AS bookId, COUNT(DISTINCT r.chapterId) AS cnt FROM review_items r
        JOIN chapters c ON c.id = r.chapterId
        WHERE r.done = 0 AND r.dueAt <= :now GROUP BY c.bookId""",
    )
    fun dueCountByBookFlow(now: Long): Flow<List<BookDueCount>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ReviewItemEntity>)

    /** 记得→推进间隔；忘了→intervalIdx 归 0、重新排期，逻辑在调用方 */
    @Query("UPDATE review_items SET intervalIdx = :intervalIdx, dueAt = :dueAt, done = :done WHERE id = :id")
    suspend fun markResult(id: Long, intervalIdx: Int, dueAt: Long, done: Boolean)

    @Query("SELECT * FROM review_items WHERE bookId = :bookId AND done = 0 ORDER BY dueAt")
    suspend fun pendingByBook(bookId: Long): List<ReviewItemEntity>

    /** 重生成总结前清掉同章全部排期行（含已完成）：唯一键含 title，章改名后 IGNORE 挡不住；走完 30 天的 done 行也无读取方，
     *  重生成即开启新一轮 1/3/7/14/30 周期（最终 QA P2-6 + 复审补充项） */
    @Query("DELETE FROM review_items WHERE chapterId = :chapterId")
    suspend fun deleteForChapter(chapterId: Long)
}

/** 复习中心到期行投影：排期项 + 书名（M6 计划 §2.4） */
data class DueItem(
    @Embedded val item: ReviewItemEntity,
    val bookTitle: String,
)

/** 书架徽标投影：每本书的到期章数（M6 计划 §2.4） */
data class BookDueCount(
    val bookId: Long,
    val cnt: Int,
)

// ============ 视觉后台队列（OPT-F） ============

@Dao
interface VisionQueueDao {

    @Insert
    suspend fun insertAll(items: List<VisionQueueEntity>)

    @Query("SELECT * FROM vision_queue WHERE bookId = :bookId ORDER BY pageNo")
    suspend fun byBook(bookId: Long): List<VisionQueueEntity>

    /** 有未消化任务的书（含 FAILED 定格页的书不在此列——FAILED 不再自动重试防烧钱） */
    @Query("SELECT DISTINCT bookId FROM vision_queue WHERE status = 'PENDING'")
    suspend fun booksWithPending(): List<Long>

    @Query("UPDATE vision_queue SET status = :status, attempts = :attempts, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, attempts: Int, now: Long)

    /** 书架徽标：每本书的视觉增强进度（完成页数/总页数） */
    @Query("SELECT bookId, SUM(status = 'DONE') AS done, COUNT(*) AS total FROM vision_queue GROUP BY bookId")
    fun progressByBookFlow(): Flow<List<VisionProgress>>

    /** 守卫查询：该书是否有任何 AI 消费（标注/笔记/资产/排期任一存在即不可重建） */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM paragraphs p JOIN chapters c ON p.chapterId = c.id " +
            "WHERE c.bookId = :bookId AND p.aiAction != 'NONE') " +
            "OR EXISTS(SELECT 1 FROM para_notes n JOIN chapters c ON n.chapterId = c.id WHERE c.bookId = :bookId) " +
            "OR EXISTS(SELECT 1 FROM chapter_assets a JOIN chapters c ON a.chapterId = c.id WHERE c.bookId = :bookId) " +
            "OR EXISTS(SELECT 1 FROM review_items r WHERE r.bookId = :bookId)",
    )
    suspend fun hasAnyAiConsumption(bookId: Long): Boolean
}

/** 书架徽标投影：视觉增强进度（OPT-F） */
data class VisionProgress(
    val bookId: Long,
    val done: Int,
    val total: Int,
)
