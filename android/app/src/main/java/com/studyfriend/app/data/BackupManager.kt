package com.studyfriend.app.data

import androidx.room.withTransaction
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.BookmarkEntity
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.ParaMemoEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.QuizAttemptEntity
import com.studyfriend.app.data.db.QaHistoryEntity
import com.studyfriend.app.data.db.ReadingStatEntity
import com.studyfriend.app.data.db.ReviewItemEntity
import com.studyfriend.app.data.db.SettingEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 学习数据备份/恢复（数据安全轮）：全部学习表导出为一个 JSON 文件（带格式版本号），
 * 恢复 = 「整库替换」（校验通过后在单事务内清空+带 id 重插，失败回滚库保持原样）。
 *
 * 范围与边界（v1 拍板）：
 * - 备份 books/chapters/paragraphs（含正文——扫描书 OCR 成果就是段落文本）/
 *   para_notes/chapter_assets/quiz_attempts/review_items/bookmarks/qa_history/para_memos
 *   + settings 的非 Key 键值（`_enc` 后缀的加密键一律不导——换机重新填 Key）。
 * - figures 示意图**不备**（图片文件大，老板手里有原书；表行指向文件，无文件即无意义），
 *   恢复时 figures 表一并清空、filesDir/figures/ 目录清理。
 * - vision_queue 是导入期临时队列（恢复后 uri 读权限已失效），不备，恢复时清空。
 * - 恢复时 settings 只替换非 `_enc` 键：本机已填的 API Key / GitHub 令牌保持不动。
 */
class BackupManager(
    private val db: StudyDatabase,
    /** filesDir 根：恢复成功后清理孤儿 figures 文件目录；null=无文件操作（测试可省） */
    private val filesDir: File? = null,
) {

    private val dao get() = db.backupDao()

    /** 导出全部学习数据（单事务内读一致性快照）到输出流；返回统计 */
    suspend fun exportTo(out: OutputStream): BackupSummary {
        val payload = db.withTransaction {
            BackupPayload(
                exportedAt = System.currentTimeMillis(),
                books = dao.allBooks().map { it.toBackup() },
                chapters = dao.allChapters().map { it.toBackup() },
                paragraphs = dao.allParagraphs().map { it.toBackup() },
                paraNotes = dao.allParaNotes().map { it.toBackup() },
                chapterAssets = dao.allChapterAssets().map { it.toBackup() },
                quizAttempts = dao.allQuizAttempts().map { it.toBackup() },
                reviewItems = dao.allReviewItems().map { it.toBackup() },
                bookmarks = dao.allBookmarks().map { it.toBackup() },
                qaHistory = dao.allQaHistory().map { it.toBackup() },
                paraMemos = dao.allParaMemos().map { it.toBackup() },
                readingStats = dao.allReadingStats().map { it.toBackup() },
                settings = dao.allSettings()
                    .filterNot { it.key.endsWith(SETTING_ENC_SUFFIX) }
                    .map { it.toBackup() },
            )
        }
        val bytes = EXPORT_JSON.encodeToString(payload).encodeToByteArray()
        out.use { it.write(bytes) }
        return payload.summary()
    }

    /** 预览备份：读文件 + 校验格式/版本/引用完整性，**不动库**；恢复确认框的数据源 */
    fun peek(input: InputStream): BackupSummary = parse(input.readBytes()).summary()

    /**
     * 整库替换恢复：全部解析校验成功后才开事务（清空 12 表 + 带 id 重插）。
     * 解析/校验/插入任一失败都抛 [BackupException]，库不会被清一半（事务原子性）。
     */
    suspend fun restoreFrom(input: InputStream): BackupSummary {
        val payload = parse(input.readBytes())
        db.withTransaction {
            // 清空：子先父后（FK 即时约束）。settings 除外——只删非 _enc 键，本机 Key 不动。
            dao.clearParagraphs()
            dao.clearParaNotes()
            dao.clearChapterAssets()
            dao.clearQuizAttempts()
            dao.clearReviewItems()
            dao.clearBookmarks()
            dao.clearQaHistory()
            dao.clearParaMemos()
            dao.clearReadingStats()
            dao.clearFigures()
            dao.clearVisionQueue()
            dao.clearChapters()
            dao.clearBooks()
            dao.allSettings()
                .filterNot { it.key.endsWith(SETTING_ENC_SUFFIX) }
                .forEach { dao.deleteSetting(it.key) }

            // 重插：父先子后，带原 id（FK 关系原样成立）
            dao.insertBooks(payload.books.map { it.toEntity() })
            dao.insertChapters(payload.chapters.map { it.toEntity() })
            dao.insertParagraphs(payload.paragraphs.map { it.toEntity() })
            dao.insertParaNotes(payload.paraNotes.map { it.toEntity() })
            dao.insertChapterAssets(payload.chapterAssets.map { it.toEntity() })
            dao.insertQuizAttempts(payload.quizAttempts.map { it.toEntity() })
            dao.insertReviewItems(payload.reviewItems.map { it.toEntity() })
            dao.insertBookmarks(payload.bookmarks.map { it.toEntity() })
            dao.insertQaHistory(payload.qaHistory.map { it.toEntity() })
            dao.insertParaMemos(payload.paraMemos.map { it.toEntity() })
            dao.insertReadingStats(payload.readingStats.map { it.toEntity() })
            dao.insertSettings(payload.settings.map { it.toEntity() })
        }
        // 事务提交成功后清理孤儿图文件（figures 行已清空，磁盘文件成孤儿）；失败不致命
        filesDir?.let { runCatching { File(it, "figures").deleteRecursively() } }
        return payload.summary()
    }

    // ---- 解析与校验（纯函数，不动库） ----

    private fun parse(bytes: ByteArray): BackupPayload {
        val payload = try {
            IMPORT_JSON.decodeFromString<BackupPayload>(bytes.decodeToString())
        } catch (e: Exception) {
            throw BackupException("这不是有效的备份文件（内容解析失败）")
        }
        if (payload.format != FORMAT_NAME) {
            throw BackupException("文件格式不对：这不是读书搭子的备份文件")
        }
        if (payload.version > FORMAT_VERSION) {
            throw BackupException("备份来自更新版本的 App（格式 v${payload.version}），请先升级 App 再恢复")
        }
        if (payload.version < 1) {
            throw BackupException("备份文件版本号无效（v${payload.version}）")
        }
        validateRefs(payload)
        return payload
    }

    /** 引用完整性预检：给人话错误（比 FK 约束炸在事务里友好）；主键重复一并查出 */
    private fun validateRefs(p: BackupPayload) {
        checkUnique(p.books.map { it.id }, "书")
        checkUnique(p.chapters.map { it.id }, "章")
        checkUnique(p.paragraphs.map { it.id }, "段落")
        checkUnique(p.chapterAssets.map { it.chapterId }, "章末资产")
        checkUnique(p.settings.map { it.key }, "设置项")
        val bookIds = p.books.mapTo(HashSet()) { it.id }
        val chapterIds = p.chapters.mapTo(HashSet()) { it.id }
        p.chapters.forEach { c ->
            if (c.bookId !in bookIds) throw BackupException("备份损坏：章「${c.title}」引用了不存在的书")
        }
        p.paragraphs.forEach { x ->
            if (x.chapterId !in chapterIds) throw BackupException("备份损坏：有段落引用了不存在的章")
        }
        p.paraNotes.forEach { x ->
            if (x.chapterId !in chapterIds) throw BackupException("备份损坏：有讲解引用了不存在的章")
        }
        p.chapterAssets.forEach { x ->
            if (x.chapterId !in chapterIds) throw BackupException("备份损坏：有章末资产引用了不存在的章")
        }
        p.quizAttempts.forEach { x ->
            if (x.chapterId !in chapterIds) throw BackupException("备份损坏：有做题记录引用了不存在的章")
        }
        p.reviewItems.forEach { x ->
            if (x.bookId !in bookIds || x.chapterId !in chapterIds) {
                throw BackupException("备份损坏：有复习排期引用了不存在的书或章")
            }
        }
        p.bookmarks.forEach { x ->
            if (x.bookId !in bookIds || x.chapterId !in chapterIds) {
                throw BackupException("备份损坏：有书签引用了不存在的书或章")
            }
        }
        p.qaHistory.forEach { x ->
            if (x.bookId !in bookIds) throw BackupException("备份损坏：有问答历史引用了不存在的书")
        }
        p.paraMemos.forEach { x ->
            if (x.bookId !in bookIds || x.chapterId !in chapterIds) {
                throw BackupException("备份损坏：有批注引用了不存在的书或章")
            }
        }
        p.readingStats.forEach { x ->
            if (x.bookId !in bookIds) throw BackupException("备份损坏：有阅读统计引用了不存在的书")
        }
    }

    private fun <T> checkUnique(ids: List<T>, what: String) {
        if (ids.toSet().size != ids.size) throw BackupException("备份损坏：${what}主键重复")
    }

    companion object {
        const val FORMAT_NAME = "studyfriend-backup"
        const val FORMAT_VERSION = 1

        /** 加密键后缀（SettingsRepository 的 *_enc 四键）：导出排除、恢复不碰 */
        const val SETTING_ENC_SUFFIX = "_enc"

        /** 导出：默认值也要写进文件（format/version 才会出现） */
        private val EXPORT_JSON = Json { encodeDefaults = true }

        /** 导入：未来版本新增字段不炸旧逻辑（version 闸已挡更高版本） */
        private val IMPORT_JSON = Json { ignoreUnknownKeys = true }
    }
}

/** 备份/恢复相关的人话异常（UI 直接展示 message） */
class BackupException(message: String) : Exception(message)

/** 备份文件统计（预览确认框与完成提示共用） */
data class BackupSummary(
    val exportedAt: Long,
    val books: Int,
    val chapters: Int,
    val paragraphs: Int,
    val paraNotes: Int,
    val chapterAssets: Int,
    val quizAttempts: Int,
    val reviewItems: Int,
    val bookmarks: Int,
    val qaHistory: Int,
    val paraMemos: Int,
    val readingStats: Int,
)

// ---- DTO（@Serializable，字段与 Entity 一一对应；格式独立于 Room schema 演化） ----

@Serializable
private data class BackupPayload(
    val format: String = BackupManager.FORMAT_NAME,
    val version: Int = BackupManager.FORMAT_VERSION,
    val exportedAt: Long = 0,
    val books: List<BackupBook> = emptyList(),
    val chapters: List<BackupChapter> = emptyList(),
    val paragraphs: List<BackupParagraph> = emptyList(),
    val paraNotes: List<BackupParaNote> = emptyList(),
    val chapterAssets: List<BackupChapterAsset> = emptyList(),
    val quizAttempts: List<BackupQuizAttempt> = emptyList(),
    val reviewItems: List<BackupReviewItem> = emptyList(),
    val bookmarks: List<BackupBookmark> = emptyList(),
    val qaHistory: List<BackupQaHistory> = emptyList(),
    val paraMemos: List<BackupParaMemo> = emptyList(),
    val readingStats: List<BackupReadingStat> = emptyList(),
    val settings: List<BackupSetting> = emptyList(),
)

private fun BackupPayload.summary() = BackupSummary(
    exportedAt = exportedAt,
    books = books.size,
    chapters = chapters.size,
    paragraphs = paragraphs.size,
    paraNotes = paraNotes.size,
    chapterAssets = chapterAssets.size,
    quizAttempts = quizAttempts.size,
    reviewItems = reviewItems.size,
    bookmarks = bookmarks.size,
    qaHistory = qaHistory.size,
    paraMemos = paraMemos.size,
    readingStats = readingStats.size,
)

@Serializable
private data class BackupBook(
    val id: Long,
    val title: String,
    val author: String,
    val sourceType: String,
    val filePath: String,
    val status: String,
    val totalChapters: Int,
    val overviewJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastReadChapterId: Long? = null,
    val lastReadAt: Long? = null,
)

private fun BookEntity.toBackup() = BackupBook(
    id, title, author, sourceType, filePath, status, totalChapters, overviewJson,
    createdAt, updatedAt, lastReadChapterId, lastReadAt,
)

private fun BackupBook.toEntity() = BookEntity(
    id, title, author, sourceType, filePath, status, totalChapters, overviewJson,
    createdAt, updatedAt, lastReadChapterId, lastReadAt,
)

@Serializable
private data class BackupChapter(
    val id: Long,
    val bookId: Long,
    val idx: Int,
    val title: String,
    val readState: String,
    val gist: String? = null,
    val keyTermsJson: String? = null,
    val level: Int = 1,
    val parentOrder: Int? = null,
    val calibrated: Boolean = false,
    val lastReadParaIdx: Int? = null,
)

private fun ChapterEntity.toBackup() =
    BackupChapter(id, bookId, idx, title, readState, gist, keyTermsJson, level, parentOrder, calibrated, lastReadParaIdx)

private fun BackupChapter.toEntity() =
    ChapterEntity(id, bookId, idx, title, readState, gist, keyTermsJson, level, parentOrder, calibrated, lastReadParaIdx)

@Serializable
private data class BackupParagraph(
    val id: Long,
    val chapterId: Long,
    val idx: Int,
    val text: String,
    val role: String,
    val aiAction: String = "NONE",
    val groupId: Long? = null,
    val why: String? = null,
    val pageNo: Int? = null,
)

private fun ParagraphEntity.toBackup() =
    BackupParagraph(id, chapterId, idx, text, role, aiAction, groupId, why, pageNo)

private fun BackupParagraph.toEntity() =
    ParagraphEntity(id, chapterId, idx, text, role, aiAction, groupId, why, pageNo)

@Serializable
private data class BackupParaNote(
    val id: Long,
    val chapterId: Long,
    val paraIds: String,
    val title: String,
    val friendly: String,
    val analogy: String? = null,
    val keyPointsJson: String,
    val memoryHook: String? = null,
    val questionsJson: String? = null,
    val model: String,
    val promptVersion: String,
    val createdAt: Long,
)

private fun ParaNoteEntity.toBackup() =
    BackupParaNote(id, chapterId, paraIds, title, friendly, analogy, keyPointsJson, memoryHook, questionsJson, model, promptVersion, createdAt)

private fun BackupParaNote.toEntity() =
    ParaNoteEntity(id, chapterId, paraIds, title, friendly, analogy, keyPointsJson, memoryHook, questionsJson, model, promptVersion, createdAt)

@Serializable
private data class BackupChapterAsset(
    val chapterId: Long,
    val summaryMd: String,
    val mindmapTree: String,
    val mindmapJson: String? = null,
    val memoryMd: String,
    val chainMd: String,
    val quizJson: String,
    val model: String,
    val promptVersion: String,
    val createdAt: Long,
)

private fun ChapterAssetEntity.toBackup() =
    BackupChapterAsset(chapterId, summaryMd, mindmapTree, mindmapJson, memoryMd, chainMd, quizJson, model, promptVersion, createdAt)

private fun BackupChapterAsset.toEntity() =
    ChapterAssetEntity(chapterId, summaryMd, mindmapTree, mindmapJson, memoryMd, chainMd, quizJson, model, promptVersion, createdAt)

@Serializable
private data class BackupQuizAttempt(
    val id: Long,
    val chapterId: Long,
    val qIndex: Int,
    val answer: String,
    val verdict: String,
    val feedback: String? = null,
    val createdAt: Long,
)

private fun QuizAttemptEntity.toBackup() =
    BackupQuizAttempt(id, chapterId, qIndex, answer, verdict, feedback, createdAt)

private fun BackupQuizAttempt.toEntity() =
    QuizAttemptEntity(id, chapterId, qIndex, answer, verdict, feedback, createdAt)

@Serializable
private data class BackupReviewItem(
    val id: Long,
    val bookId: Long,
    val chapterId: Long,
    val title: String,
    val intervalIdx: Int,
    val dueAt: Long,
    val done: Boolean = false,
)

private fun ReviewItemEntity.toBackup() =
    BackupReviewItem(id, bookId, chapterId, title, intervalIdx, dueAt, done)

private fun BackupReviewItem.toEntity() =
    ReviewItemEntity(id, bookId, chapterId, title, intervalIdx, dueAt, done)

@Serializable
private data class BackupBookmark(
    val id: Long,
    val bookId: Long,
    val chapterId: Long,
    val paraIdx: Int,
    val preview: String,
    val createdAt: Long,
)

private fun BookmarkEntity.toBackup() = BackupBookmark(id, bookId, chapterId, paraIdx, preview, createdAt)

private fun BackupBookmark.toEntity() = BookmarkEntity(id, bookId, chapterId, paraIdx, preview, createdAt)

@Serializable
private data class BackupQaHistory(
    val id: Long,
    val bookId: Long,
    val chapterId: Long,
    val chapterTitle: String,
    val question: String,
    val answer: String,
    val createdAt: Long,
)

private fun QaHistoryEntity.toBackup() =
    BackupQaHistory(id, bookId, chapterId, chapterTitle, question, answer, createdAt)

private fun BackupQaHistory.toEntity() =
    QaHistoryEntity(id, bookId, chapterId, chapterTitle, question, answer, createdAt)

@Serializable
private data class BackupParaMemo(
    val id: Long,
    val bookId: Long,
    val chapterId: Long,
    val paraId: Long,
    val paraIdx: Int,
    val quote: String,
    val text: String,
    val createdAt: Long,
)

private fun ParaMemoEntity.toBackup() =
    BackupParaMemo(id, bookId, chapterId, paraId, paraIdx, quote, text, createdAt)

private fun BackupParaMemo.toEntity() =
    ParaMemoEntity(id, bookId, chapterId, paraId, paraIdx, quote, text, createdAt)

@Serializable
private data class BackupReadingStat(
    val bookId: Long,
    val day: String,
    val minutes: Int,
)

private fun ReadingStatEntity.toBackup() = BackupReadingStat(bookId, day, minutes)

private fun BackupReadingStat.toEntity() = ReadingStatEntity(bookId, day, minutes)

@Serializable
private data class BackupSetting(
    val key: String,
    val value: String,
)

private fun SettingEntity.toBackup() = BackupSetting(key, value)

private fun BackupSetting.toEntity() = SettingEntity(key, value)
