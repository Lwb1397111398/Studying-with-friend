package com.studyfriend.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /** 单键监听（v7 主题三态用）：settings 表任何写入都会让 Flow 重发，改完即时生效 */
    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun watch(key: String): Flow<String?>
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
    // v7 排序：最近在读优先（lastReadAt DESC，NULL 视为最小自然沉底），从未读过的书按导入/更新时间排后
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, updatedAt DESC")
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

    /** v7 书架进度条批量源：全部书的已读章数投影（同 getProgressFlow 口径，去 WHERE） */
    @Query(
        """SELECT b.id AS id, b.title AS title, b.author AS author, b.status AS status,
               b.totalChapters AS totalChapters,
               (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id AND c.readState = 'DONE') AS readChapters
        FROM books b""",
    )
    fun progressAllFlow(): Flow<List<BookProgress>>

    /** v7 进章即记最近在读：书架「继续读」入口与排序的数据源 */
    @Query("UPDATE books SET lastReadChapterId = :chapterId, lastReadAt = :now WHERE id = :bookId")
    suspend fun markReading(bookId: Long, chapterId: Long, now: Long)

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

/**
 * 章树行投影（P5）：chapters 全行（不过滤 level）+ 资产徽标列，供章节树 UI 展示
 * （byBookTreeFlow）。level/parentOrder/calibrated 三列均在 chapters 表
 * （Entities.kt ChapterEntity，P3b-2 Migration 3→4 落地）。
 */
data class ChapterTreeRow(
    val id: Long,
    val idx: Int,
    val title: String,
    val readState: String,
    val gist: String?,
    val keyTermsJson: String?,
    val level: Int,
    val parentOrder: Int?,
    val calibrated: Boolean,
    val hasAsset: Boolean,
    val assetPromptVersion: String?,
    val assetSummaryPresent: Boolean,
)

@Dao
interface ChapterDao {
    // level=1 过滤：P3b-2 目录校准后表内有 level=2 节行（P5 章节树 UI 消费），阅读侧只看章；
    // 未校准书/旧数据 level 全 1（NOT NULL DEFAULT 1），过滤后与原结果逐条一致
    @Query(
        """SELECT c.id AS id, c.idx AS idx, c.title AS title, c.readState AS readState,
               c.gist AS gist, c.keyTermsJson AS keyTermsJson,
               (a.chapterId IS NOT NULL) AS hasAsset,
               a.promptVersion AS assetPromptVersion,
               (a.summaryMd IS NOT NULL AND a.summaryMd != '') AS assetSummaryPresent
        FROM chapters c LEFT JOIN chapter_assets a ON a.chapterId = c.id
        WHERE c.bookId = :bookId AND c.level = 1 ORDER BY c.idx""",
    )
    fun byBookFlow(bookId: Long): Flow<List<ChapterWithAsset>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx")
    suspend fun byBook(bookId: Long): List<ChapterEntity>

    // P5 章节树：不过滤 level，节行随章行返回（树形挂接在 UI 层 buildChapterTree 做，
    // 查询保持纯投影）；byBookFlow（level=1 过滤）不动，M6 门控等旧消费方零回归
    @Query(
        """SELECT c.id AS id, c.idx AS idx, c.title AS title, c.readState AS readState,
               c.gist AS gist, c.keyTermsJson AS keyTermsJson,
               c.level AS level, c.parentOrder AS parentOrder, c.calibrated AS calibrated,
               (a.chapterId IS NOT NULL) AS hasAsset,
               a.promptVersion AS assetPromptVersion,
               (a.summaryMd IS NOT NULL AND a.summaryMd != '') AS assetSummaryPresent
        FROM chapters c LEFT JOIN chapter_assets a ON a.chapterId = c.id
        WHERE c.bookId = :bookId ORDER BY c.idx""",
    )
    fun byBookTreeFlow(bookId: Long): Flow<List<ChapterTreeRow>>

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

    /** v7 阅读位置记忆：滚动停止后写读到哪段（0-based paragraphs.idx） */
    @Query("UPDATE chapters SET lastReadParaIdx = :paraIdx WHERE id = :id")
    suspend fun updateLastReadPara(id: Long, paraIdx: Int)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)

    /** 术语卡盒（学习闭环轮）：全书有 keyTerms 的章（复习中心「术语」Tab 数据源，一次性快照） */
    @Query(
        """SELECT c.id AS chapterId, c.idx AS chapterIdx, c.title AS chapterTitle,
               c.keyTermsJson AS keyTermsJson, b.id AS bookId, b.title AS bookTitle
        FROM chapters c JOIN books b ON c.bookId = b.id
        WHERE c.keyTermsJson IS NOT NULL AND c.keyTermsJson != ''
        ORDER BY b.id, c.idx""",
    )
    suspend fun chaptersWithTerms(): List<ChapterTermsRow>
}

/** 术语卡盒行投影（学习闭环轮）：章 + 术语 JSON + 书名 */
data class ChapterTermsRow(
    val bookId: Long,
    val bookTitle: String,
    val chapterId: Long,
    val chapterIdx: Int,
    val chapterTitle: String,
    val keyTermsJson: String,
)

@Dao
interface ParagraphDao {
    @Query("SELECT * FROM paragraphs WHERE chapterId = :chapterId ORDER BY idx")
    fun byChapterFlow(chapterId: Long): Flow<List<ParagraphEntity>>

    @Query("SELECT * FROM paragraphs WHERE chapterId = :chapterId ORDER BY idx")
    suspend fun byChapter(chapterId: Long): List<ParagraphEntity>

    /** v7 书签跳转定位：按段 idx 取单段（拿 paraId 给 READ 路由滚动锚） */
    @Query("SELECT * FROM paragraphs WHERE chapterId = :chapterId AND idx = :idx LIMIT 1")
    suspend fun byChapterAndIdx(chapterId: Long, idx: Int): ParagraphEntity?

    @Insert
    suspend fun insertAll(paragraphs: List<ParagraphEntity>): List<Long>

    @Query("UPDATE paragraphs SET aiAction = :action, groupId = :groupId, why = :why WHERE id = :id")
    suspend fun updateAiAction(id: Long, action: String, groupId: Long?, why: String?)

    @Query("DELETE FROM paragraphs WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: Long)

    /** 重建底稿（P6c C3a 立案修复）：按页取全书段落，页内顺序=章序+段序。
     *  SQLite ASC 排序 NULL 最前——TXT 书（pageNo 可空）的空页号行会排最前，
     *  调用方（VisionRebuilder）检测到空页号即放弃重建（视觉队列只来自 PDF 导入） */
    @Query(
        """SELECT p.* FROM paragraphs p JOIN chapters c ON p.chapterId = c.id
        WHERE c.bookId = :bookId ORDER BY p.pageNo, c.idx, p.idx""",
    )
    suspend fun byBookForRebuild(bookId: Long): List<ParagraphEntity>

    /** v7 全书搜索：pattern 由 [escapeLikePattern] 生成（通配符已转义），SQLite LIKE 对 ASCII 默认不区分大小写 */
    @Query(
        """SELECT p.id AS paraId, p.chapterId AS chapterId, c.idx AS chapterIdx,
               c.title AS chapterTitle, p.idx AS paraIdx, p.text AS text
        FROM paragraphs p JOIN chapters c ON p.chapterId = c.id
        WHERE c.bookId = :bookId AND p.text LIKE :pattern ESCAPE '\'
        ORDER BY c.idx, p.idx LIMIT :limit""",
    )
    suspend fun searchInBook(bookId: Long, pattern: String, limit: Int): List<SearchHit>
}

/** 搜索结果行（v7）：段命中 + 所属章信息（点结果跳章滚动到段） */
data class SearchHit(
    val paraId: Long,
    val chapterId: Long,
    val chapterIdx: Int,
    val chapterTitle: String,
    val paraIdx: Int,
    val text: String,
)

/** LIKE 模式串：转义用户输入里的 % _ \（ESCAPE '\' 口径），两端包 % 做包含匹配 */
fun escapeLikePattern(q: String): String =
    "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

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

    /** 错题本源（M6 优化 v2）：SQL 内先取每 (chapterId,qIndex) 最新一条（createdAt 降序、
     *  id 降序破平），作答历史不再整表过 JVM——quiz_attempts 随作答无限增长，不下推的话
     *  错题本进页随历史变慢。verdict 过滤仍归 latestWrongAttempts（"先错后对"的 CORRECT
     *  最新行必须在场才能让题目出列，M6 计划 §2.3） */
    @Query(
        """SELECT qa.id AS id, c.bookId AS bookId, qa.chapterId AS chapterId,
               c.title AS chapterTitle, b.title AS bookTitle, qa.qIndex AS qIndex,
               qa.answer AS answer, qa.verdict AS verdict, qa.feedback AS feedback,
               qa.createdAt AS createdAt
        FROM quiz_attempts qa JOIN chapters c ON c.id = qa.chapterId
        JOIN books b ON b.id = c.bookId
        WHERE qa.id = (
            SELECT q2.id FROM quiz_attempts q2
            WHERE q2.chapterId = qa.chapterId AND q2.qIndex = qa.qIndex
            ORDER BY q2.createdAt DESC, q2.id DESC LIMIT 1
        )
        ORDER BY qa.createdAt DESC""",
    )
    suspend fun wrongLatest(): List<WrongAttempt>
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

    /** FAILED 定格页重置回 PENDING 并清零 attempts：设置页保存视觉配置后调用，
     *  用户改 Key/开关/模型 = 明确的重试意图，给一个全新重试周期（防 FAILED 永久死局） */
    @Query("UPDATE vision_queue SET status = 'PENDING', attempts = 0, updatedAt = :now WHERE status = 'FAILED'")
    suspend fun resetFailedToPending(now: Long): Int

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

    /** 阅读页 FigureRow 错位定位（P4 v1.11，r11-P2-2）：该书已完成视觉替换的页号集合 */
    @Query("SELECT DISTINCT pageNo FROM vision_queue WHERE bookId = :bookId AND status = 'DONE'")
    suspend fun donePageNosByBook(bookId: Long): List<Int>
}

/** 书架徽标投影：视觉增强进度（OPT-F） */
data class VisionProgress(
    val bookId: Long,
    val done: Int,
    val total: Int,
)

@Dao
interface FigureDao {
    @Insert
    suspend fun insertAll(figures: List<FigureEntity>)

    /** 阅读页混排：页号 → 页内锚 → 同锚 y 序；同页同锚同 y 以 id ASC 末位定序（查询级确定性，r12-QC5） */
    @Query(
        "SELECT * FROM figures WHERE chapterId = :chapterId " +
            "ORDER BY pageNo ASC, ordAfterPara ASC, bboxY0 ASC, id ASC",
    )
    fun byChapterFlow(chapterId: Long): Flow<List<FigureEntity>>

    /** 重挂/清理对账用一次性全量读（§3-D：删章前读完整 Entity，重插时仅改 chapterId） */
    @Query("SELECT * FROM figures WHERE bookId = :bookId ORDER BY pageNo ASC, seqNo ASC")
    suspend fun byBookOnce(bookId: Long): List<FigureEntity>

    /** 第二道闸（VisionWorker）用：幸存图所在页号集合 */
    @Query("SELECT DISTINCT pageNo FROM figures WHERE bookId = :bookId")
    suspend fun pageNosByBook(bookId: Long): List<Int>

    @Query("DELETE FROM figures WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)
}

// ============ 书签（v7） ============

/** 书签行投影：书签 + 章名/章序（阅读页目录抽屉展示用） */
data class BookmarkWithChapter(
    @Embedded val bookmark: BookmarkEntity,
    val chapterTitle: String,
    val chapterIdx: Int,
)

@Dao
interface BookmarkDao {
    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """SELECT b.*, c.title AS chapterTitle, c.idx AS chapterIdx FROM bookmarks b
        JOIN chapters c ON c.id = b.chapterId
        WHERE b.bookId = :bookId ORDER BY b.createdAt DESC, b.id DESC""",
    )
    fun byBookWithChapterFlow(bookId: Long): Flow<List<BookmarkWithChapter>>
}

// ============ 随手问历史（v7） ============

@Dao
interface QaHistoryDao {
    @Insert
    suspend fun insert(entry: QaHistoryEntity)

    /** 每书最近 50 条（id 降序破同毫秒平票，口径同错题本 wrongLatest） */
    @Query(
        "SELECT * FROM qa_history WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC LIMIT 50",
    )
    suspend fun latestByBook(bookId: Long): List<QaHistoryEntity>

    /** 淘汰超出保留窗的旧行：append 后调用，keep=50 */
    @Query(
        """DELETE FROM qa_history WHERE bookId = :bookId AND id NOT IN (
            SELECT id FROM qa_history WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC LIMIT :keep
        )""",
    )
    suspend fun trim(bookId: Long, keep: Int)
}

// ============ 段落批注（学习闭环轮） ============

/** 批注行投影：批注 + 章名/章序（目录抽屉批注列表展示用，同 BookmarkWithChapter 模式） */
data class ParaMemoWithChapter(
    @Embedded val memo: ParaMemoEntity,
    val chapterTitle: String,
    val chapterIdx: Int,
)

@Dao
interface ParaMemoDao {
    @Insert
    suspend fun insert(memo: ParaMemoEntity): Long

    @Query("DELETE FROM para_memos WHERE id = :id")
    suspend fun delete(id: Long)

    /** 阅读页段落标记源：按段序排，UI 按 paraId 匹配段落行显示标记 */
    @Query("SELECT * FROM para_memos WHERE chapterId = :chapterId ORDER BY paraIdx, id")
    fun byChapterFlow(chapterId: Long): Flow<List<ParaMemoEntity>>

    /** 抽屉批注列表：全书批注带章名（最新在前，跳转走 paraId 段锚） */
    @Query(
        """SELECT m.*, c.title AS chapterTitle, c.idx AS chapterIdx FROM para_memos m
        JOIN chapters c ON c.id = m.chapterId
        WHERE m.bookId = :bookId ORDER BY m.createdAt DESC, m.id DESC""",
    )
    fun byBookWithChapterFlow(bookId: Long): Flow<List<ParaMemoWithChapter>>
}

// ============ 备份与恢复（数据安全轮） ============

/**
 * 备份导出/整库恢复专用：10 张学习表的全量读、清空与带 id 重插。
 * 只被 BackupManager 调用；普通业务路径继续走各自 DAO。
 * 清空顺序子先父后（FK 即时约束下先删子行才不炸）；带 id 插入靠 AUTOINCREMENT
 * 的 sequence 推进语义保证恢复后新自增 id 不与恢复行冲突。
 */
@Dao
interface BackupDao {

    // ---- 全量读（id 升序，导出文件内容稳定可 diff） ----
    @Query("SELECT * FROM books ORDER BY id")
    suspend fun allBooks(): List<BookEntity>

    @Query("SELECT * FROM chapters ORDER BY id")
    suspend fun allChapters(): List<ChapterEntity>

    @Query("SELECT * FROM paragraphs ORDER BY id")
    suspend fun allParagraphs(): List<ParagraphEntity>

    @Query("SELECT * FROM para_notes ORDER BY id")
    suspend fun allParaNotes(): List<ParaNoteEntity>

    @Query("SELECT * FROM chapter_assets ORDER BY chapterId")
    suspend fun allChapterAssets(): List<ChapterAssetEntity>

    @Query("SELECT * FROM quiz_attempts ORDER BY id")
    suspend fun allQuizAttempts(): List<QuizAttemptEntity>

    @Query("SELECT * FROM review_items ORDER BY id")
    suspend fun allReviewItems(): List<ReviewItemEntity>

    @Query("SELECT * FROM bookmarks ORDER BY id")
    suspend fun allBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM qa_history ORDER BY id")
    suspend fun allQaHistory(): List<QaHistoryEntity>

    @Query("SELECT * FROM para_memos ORDER BY id")
    suspend fun allParaMemos(): List<ParaMemoEntity>

    @Query("SELECT * FROM settings ORDER BY `key`")
    suspend fun allSettings(): List<SettingEntity>

    @Query("SELECT * FROM reading_stats ORDER BY bookId, day")
    suspend fun allReadingStats(): List<ReadingStatEntity>

    // ---- 清空（子先父后；figures/vision_queue 不在备份里，但同属「整库替换」语义一并清） ----
    @Query("DELETE FROM paragraphs")
    suspend fun clearParagraphs()

    @Query("DELETE FROM para_notes")
    suspend fun clearParaNotes()

    @Query("DELETE FROM chapter_assets")
    suspend fun clearChapterAssets()

    @Query("DELETE FROM quiz_attempts")
    suspend fun clearQuizAttempts()

    @Query("DELETE FROM review_items")
    suspend fun clearReviewItems()

    @Query("DELETE FROM bookmarks")
    suspend fun clearBookmarks()

    @Query("DELETE FROM qa_history")
    suspend fun clearQaHistory()

    @Query("DELETE FROM para_memos")
    suspend fun clearParaMemos()

    @Query("DELETE FROM reading_stats")
    suspend fun clearReadingStats()

    @Query("DELETE FROM figures")
    suspend fun clearFigures()

    @Query("DELETE FROM vision_queue")
    suspend fun clearVisionQueue()

    @Query("DELETE FROM chapters")
    suspend fun clearChapters()

    @Query("DELETE FROM books")
    suspend fun clearBooks()

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)

    // ---- 带 id 重插（恢复路径专用；表已清空，无冲突） ----
    @Insert
    suspend fun insertBooks(books: List<BookEntity>)

    @Insert
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert
    suspend fun insertParagraphs(paragraphs: List<ParagraphEntity>)

    @Insert
    suspend fun insertParaNotes(notes: List<ParaNoteEntity>)

    @Insert
    suspend fun insertChapterAssets(assets: List<ChapterAssetEntity>)

    @Insert
    suspend fun insertQuizAttempts(attempts: List<QuizAttemptEntity>)

    @Insert
    suspend fun insertReviewItems(items: List<ReviewItemEntity>)

    @Insert
    suspend fun insertBookmarks(bookmarks: List<BookmarkEntity>)

    @Insert
    suspend fun insertQaHistory(entries: List<QaHistoryEntity>)

    @Insert
    suspend fun insertParaMemos(memos: List<ParaMemoEntity>)

    @Insert
    suspend fun insertReadingStats(stats: List<ReadingStatEntity>)

    @Insert
    suspend fun insertSettings(settings: List<SettingEntity>)
}

// ============ 阅读统计（体验增量轮） ============

/** 按天合计行（统计卡数据源）：day yyyy-MM-dd + 当天全部书分钟合计 */
data class DayMinutes(
    val day: String,
    val minutes: Int,
)

@Dao
interface ReadingStatDao {

    /** 当前累计值（无行=null） */
    @Query("SELECT minutes FROM reading_stats WHERE bookId = :bookId AND day = :day")
    suspend fun minutesFor(bookId: Long, day: String): Int?

    @Upsert
    suspend fun upsert(stat: ReadingStatEntity)

    /** 分钟入账：同书同天读出旧值后递增写回（分钟粒度）。
     *  不用 SQL UPSERT（ON CONFLICT DO UPDATE）——Robolectric 的 legacy SQLite
     *  内核低于 3.24 不认该语法，@Transaction 默认方法 + @Upsert 全环境可跑 */
    @Transaction
    suspend fun addMinutes(bookId: Long, day: String, minutes: Int) {
        upsert(ReadingStatEntity(bookId, day, (minutesFor(bookId, day) ?: 0) + minutes))
    }

    /** 某天全部书分钟合计（无行=0） */
    @Query("SELECT COALESCE(SUM(minutes), 0) FROM reading_stats WHERE day = :day")
    suspend fun minutesOnDay(day: String): Int

    /** 全部天数行（day 降序）：今日/累计/连续天数一次查齐，复习中心统计卡用 */
    @Query("SELECT day AS day, SUM(minutes) AS minutes FROM reading_stats GROUP BY day ORDER BY day DESC")
    suspend fun minutesByDay(): List<DayMinutes>
}

/**
 * 连续阅读天数（体验增量轮）：从今天往回数有记录的连续日；今天还没读不打断
 * （从昨天起算），一旦断档即停。纯函数，方便单测。
 */
fun computeStreak(days: Set<String>, today: java.time.LocalDate): Int {
    var streak = 0
    var cursor = if (today.toString() in days) today else today.minusDays(1)
    while (cursor.toString() in days) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}
