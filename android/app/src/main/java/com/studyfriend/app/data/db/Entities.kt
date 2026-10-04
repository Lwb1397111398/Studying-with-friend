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
    /** P3b-2 节挂接产物：1=章 2=节（TocEntry.level 口径）。NOT NULL DEFAULT 1=未校准书
     *  自动同构（计划案 §3.3「level 全 1、旧查询零改动」），P3b-3/P5 消费 */
    val level: Int = 1,
    /** P3b-2 节挂接产物：父章在 level1 章序列中的 1-based 序号（TocChapterCalibrator 节挂接
     *  `parentOrder = level1IndexOf[host]`，以代码为准；本列旧注释「同级内的排序序号」系笔误已修正）。
     *  P5 章节树 UI 按此把节行挂到父章下 */
    val parentOrder: Int? = null,
    /** P3b-2 目录校准标志（false=未校准/目录不可用）。本期加列不消费，语义定义权归 P5 */
    val calibrated: Boolean = false,
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
    val role: String, // BODY / FRONT / TOC
    val aiAction: String = "NONE", // NONE / SKIP / EXPLAIN / GROUP
    val groupId: Long? = null, // GROUP 时同组合并号
    val why: String? = null, // AI 决定理由，界面"为什么讲这段"
    /** P3b-2：段首页码（1-based PDF 页，跨页并段取首页；TXT/粘贴=null）——目录驱动切章的锚定与段落重排依据 */
    val pageNo: Int? = null,
)

/**
 * 视觉后台队列（OPT-F）：超出单次同步转写上限的可疑页，导入时先用文字层内容
 * 入库，页级转写任务落此表交 WorkManager 慢慢消化（每页一任务，断点续跑）。
 * originChars 存该页清洗后段落字符合计（VisionTranscriber 长度守卫口径），
 * Worker 免重跑整书提取。
 */
@Entity(
    tableName = "vision_queue",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index(value = ["bookId", "pageNo"], unique = true)],
)
data class VisionQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val uri: String, // PDF 内容 uri（Worker 重渲染视觉输入用，导入时已持久化读权限）
    val pageNo: Int, // 1-based PDF 页号
    val originChars: Int,
    val status: String, // PENDING / DONE / FAILED
    val attempts: Int = 0,
    /** P4 低质量放行标记：该页是幸存图页且文字层烂（第一道闸放行），第二道闸据此放行转写 */
    val lowQuality: Boolean = false,
    val updatedAt: Long,
)

/**
 * 导入时提取的原生 PDF 示意图（P4）；与 chapter_assets（AI 产物）语义正交，不共享列。
 * file 为相对 filesDir 路径 `figures/{bookId}/p{pageNo}_f{seqNo}.{format}`——seqNo 全书唯一，
 * 根除同页同锚多图的文件名碰撞（计划案 r9-P0-1）。
 */
@Entity(
    tableName = "figures",
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
    indices = [Index("bookId"), Index("chapterId")],
)
data class FigureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    /** 1-based PDF 页号（与 paragraphs.pageNo 同口径） */
    val pageNo: Int,
    /** 页内锚定：插在该页第 N 段之后，0-based（页内全量段口径）；-1=页首前 */
    val ordAfterPara: Int,
    /** 同页多图的次级排序键：折算后显示空间图顶 y（pt） */
    val bboxY0: Float,
    /** 全书图序号：提取时按 pageNo/ordAfterPara/bboxY0 排序 1 起赋号（file 命名依赖，先于落盘算定） */
    val seqNo: Int,
    val file: String,
    /** 落盘文件的实际像素（aspectRatio 占位用） */
    val width: Int,
    val height: Int,
    val format: String, // png / jpg
    /** filesDir 最终落盘文件字节摘要（复制失败即不落库，无孤儿值） */
    val md5: String,
    /** 本期一次性写入，不预留 updatedAt（P6 caption 走独立表） */
    val createdAt: Long,
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
