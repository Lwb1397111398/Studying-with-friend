package com.studyfriend.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 键值设置（AI 地址 / Key / 模型名等），key 为主键 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** 一本书；复杂 AI 产物存 JSON 字符串，时间戳一律 epoch ms */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val sourceType: String, // TXT / PDF / PASTE
    val filePath: String,
    val status: String, // IMPORTED / READY
    val totalChapters: Int,
    val overviewJson: String?, // M6 全书总览落点
    val createdAt: Long,
    val updatedAt: Long,
)

/** 一章/单元；gist、keyTermsJson 是粗读规划的章节级产物 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val idx: Int,
    val title: String,
    val readState: String, // NOT_READ / READING / DONE
    val gist: String?,
    val keyTermsJson: String?,
)

/** 一个自然段；aiAction 是粗读规划的三态决定（NONE 仅占位） */
@Entity(
    tableName = "paragraphs",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("chapterId"),
        Index(value = ["chapterId", "idx"], unique = true),
    ],
)
data class ParagraphEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val idx: Int,
    val text: String,
    val role: String, // BODY / FRONT
    val aiAction: String = "NONE", // NONE / SKIP / EXPLAIN / GROUP
    val groupId: Long? = null, // GROUP 时同组合并号
    val why: String? = null, // AI 决定理由，界面"为什么讲这段"
)

/** 一条段落讲解（可覆盖 1~N 个段落，paraIds 为 JSON 数组） */
@Entity(
    tableName = "para_notes",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chapterId")],
)
data class ParaNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val paraIds: String,
    val title: String,
    val friendly: String,
    val analogy: String?,
    val keyPointsJson: String,
    val memoryHook: String?,
    val questionsJson: String?,
    val model: String,
    val promptVersion: String,
    val createdAt: Long,
)

/** 章末资产：一章一份，chapterId 即主键（@Upsert 冲突必命中本行，无自增 id） */
@Entity(
    tableName = "chapter_assets",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChapterAssetEntity(
    @PrimaryKey val chapterId: Long,
    val summaryMd: String,
    val mindmapTree: String, // TAB 缩进树（听的思维格式）
    val mindmapJson: String?, // mind-elixir 节点 JSON
    val memoryMd: String,
    val chainMd: String,
    val quizJson: String,
    val model: String,
    val promptVersion: String,
    val createdAt: Long,
)

/** 一条做题记录 */
@Entity(
    tableName = "quiz_attempts",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chapterId")],
)
data class QuizAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val qIndex: Int,
    val answer: String,
    val verdict: String, // CORRECT / WRONG / PARTIAL
    val feedback: String?,
    val createdAt: Long,
)

/** 间隔复习排期项；unique(bookId,chapterId,title) 防章末包重生成时重复排期 */
@Entity(
    tableName = "review_items",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId", "chapterId", "title"], unique = true),
        Index("dueAt"),
        Index("chapterId"),
    ],
)
data class ReviewItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val title: String, // 章节名，到期列表展示用
    val intervalIdx: Int, // 0..4 对应 1/3/7/14/30 天
    val dueAt: Long,
    val done: Boolean = false,
)
