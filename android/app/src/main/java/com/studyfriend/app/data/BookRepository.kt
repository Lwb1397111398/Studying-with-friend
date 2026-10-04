package com.studyfriend.app.data

import androidx.room.withTransaction
import android.util.Log
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.FigureEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.QuizAttemptEntity
import com.studyfriend.app.data.db.ReviewItemEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.importer.pdfpipeline.ExtractedFigure
import com.studyfriend.app.data.importer.pdfpipeline.FigureCoordMath
import java.io.File

/** 纯 DB 操作封装（不含 AI/解析逻辑），UI 经此访问数据，不直捅 DAO。
 *  P4 例外：figures 的 staging 文件挪移属落库配套 IO，随 importBook 事务执行 */
private const val TAG = "BookRepository"

class BookRepository(
    private val db: StudyDatabase,
    /** filesDir 根：figures/{bookId}/ 最终目录与删书清理依赖；null=无图操作（测试可省） */
    private val filesDir: File? = null,
) {

    /** 导入落库：事务内 书→章→段（→图），返回 bookId。status 一律 READY（目录已在确认页过目）；
     *  章节与段落的 idx 由本方法按列表序回填（确认页传入顺序即最终顺序）。
     *  totalChapters 缺省=章节行数；P3b-2 目录校准传 level1 章数（节行混入列表不虚报章数）。
     *  P4（r9-P2-1 同事务边界）：figures 非空时在同一 withTransaction 内挪 staging 文件
     *  （filesDir/figures/{bookId}/）+ 区间归属 + insertAll——confirmImport 里另插会留下
     *  「有章无图」半成品书。TXT/粘贴路径 figures 恒空。
     *  [chapterStartPages]：校准路径传 outcome.chapters 的 startPage 列表（与 chapters 同序）；
     *  null=现状路径按每章首段 pageNo 推（章起点=章首段页码，ParsedChapter 无独立起点） */
    suspend fun importBook(
        book: BookEntity,
        chapters: List<Pair<ChapterEntity, List<ParagraphEntity>>>,
        figures: List<ExtractedFigure> = emptyList(),
        chapterStartPages: List<Int>? = null,
        totalChapters: Int? = null,
    ): Long = db.withTransaction {
        val ready = book.copy(
            status = DbValues.BOOK_READY,
            totalChapters = totalChapters ?: chapters.size,
        )
        val bookId = db.bookDao().insert(ready)
        val chapterIds = mutableListOf<Long>()
        chapters.forEachIndexed { ci, (chapter, paras) ->
            val chapterId = db.chapterDao().insertAll(
                listOf(chapter.copy(bookId = bookId, idx = ci)),
            )[0]
            chapterIds += chapterId
            db.paragraphDao().insertAll(
                paras.mapIndexed { pi, p -> p.copy(chapterId = chapterId, idx = pi) },
            )
        }
        if (figures.isNotEmpty()) {
            insertFigures(bookId, chapterIds, chapters, figures, chapterStartPages)
        }
        bookId
    }

    /**
     * figures 落库（importBook/replaceBookContent 共用归属规则 assignChapter）：
     * staging 文件挪入 filesDir/figures/{bookId}/，file 列记相对路径。单图挪移失败剔除
     * 该图（无行无孤儿值，J2 口径）；(md5,pageNo,ordAfterPara) 全同键内存去重（r6-P2-6，
     * 防复制残留重复行）。事务回滚时已挪文件成孤儿目录——导入失败级异常罕见，可接受。
     */
    private suspend fun insertFigures(
        bookId: Long,
        chapterIds: List<Long>,
        chapters: List<Pair<ChapterEntity, List<ParagraphEntity>>>,
        figures: List<ExtractedFigure>,
        chapterStartPages: List<Int>?,
    ) {
        val fd = filesDir ?: throw IllegalStateException("filesDir 未注入（figures 落库依赖）")
        val dir = File(fd, "figures/$bookId").apply { mkdirs() }
        val (anchoredIds, startPages) = anchoredChapters(chapterIds, chapters, chapterStartPages)
        val now = System.currentTimeMillis()
        val seen = HashSet<Triple<String, Int, Int>>()
        val entities = figures.mapNotNull { f ->
            val target = File(dir, f.finalName)
            val moved = try {
                f.stagingFile.renameTo(target) ||
                    (f.stagingFile.copyTo(target, overwrite = true).let {
                        f.stagingFile.delete()
                        true
                    })
            } catch (e: Exception) {
                Log.w(TAG, "figure move failed ${f.finalName}: ${e.message}")
                false
            }
            if (!moved) return@mapNotNull null
            val chIdx = FigureCoordMath.assignChapter(f.pageNo, startPages)
            if (chIdx < 0 || chIdx >= anchoredIds.size) {
                Log.w(TAG, "figure chapter assign failed p${f.pageNo}")
                return@mapNotNull null
            }
            if (!seen.add(Triple(f.md5, f.pageNo, f.ordAfterPara))) return@mapNotNull null
            FigureEntity(
                bookId = bookId,
                chapterId = anchoredIds[chIdx],
                pageNo = f.pageNo,
                ordAfterPara = f.ordAfterPara,
                bboxY0 = f.bboxY0,
                seqNo = f.seqNo,
                file = "figures/$bookId/${f.finalName}",
                width = f.widthPx,
                height = f.heightPx,
                format = f.format,
                md5 = f.md5,
                createdAt = now,
            )
        }
        db.figureDao().insertAll(entities)
    }

    // ---- 书 ----
    fun shelfFlow() = db.bookDao().getAllFlow()

    /**
     * 视觉增强完成后的整书内容替换（OPT-F）：事务内删旧章重插（段落/笔记/资产/
     * 排期随 CASCADE 清空），bookId 不变。守卫（无任何 AI 消费）由调用方
     * VisionRebuilder 保证；章节/段落 idx 按列表序回填，同 importBook。
     *
     * P4 figures 重挂（计划案 §3-D 四步，顺序严格）：① 删旧章**前** byBookOnce 读全量
     * → ② 删旧章（figures 行随 CASCADE 蒸发，数据已在内存）→ ③ 插新章 → ④ 按新章
     * startPage 区间 assignChapter 重插（只读完整 Entity、仅改 id/chapterId，其余逐列
     * 保留——r9-P1-2；文件在 filesDir/figures/{bookId}/ 原地不动，file 列不变）。
     *
     * 不变量（v1.10，r10-③ 代码级声明）：**chapters 逐章 startPage 与替换前相等（按章
     * id 对齐）是 figures 归属正确性的充要条件**——replaceBookContent 不得重排 chapters
     * 顺序、不得修改 startPage（r4-P1-2）；违反即须调用 reassignFiguresToChapters（P6）。
     */
    suspend fun replaceBookContent(
        bookId: Long,
        book: BookEntity,
        chapters: List<Pair<ChapterEntity, List<ParagraphEntity>>>,
    ) = db.withTransaction {
        val figures = db.figureDao().byBookOnce(bookId)
        db.chapterDao().deleteByBook(bookId)
        val chapterIds = mutableListOf<Long>()
        chapters.forEachIndexed { ci, (chapter, paras) ->
            val chapterId = db.chapterDao().insertAll(
                listOf(chapter.copy(bookId = bookId, idx = ci)),
            )[0]
            chapterIds += chapterId
            db.paragraphDao().insertAll(
                paras.mapIndexed { pi, p -> p.copy(chapterId = chapterId, idx = pi) },
            )
        }
        if (figures.isNotEmpty()) {
            val (anchoredIds, startPages) = anchoredChapters(chapterIds, chapters, null)
            db.figureDao().insertAll(
                figures.mapNotNull { f ->
                    val chIdx = FigureCoordMath.assignChapter(f.pageNo, startPages)
                    if (chIdx < 0 || chIdx >= anchoredIds.size) {
                        Log.w(TAG, "figure reassign skipped p${f.pageNo}")
                        return@mapNotNull null
                    }
                    f.copy(id = 0, chapterId = anchoredIds[chIdx])
                },
            )
        }
        db.bookDao().update(book.copy(totalChapters = chapters.size))
    }

    /**
     * figures 归属候选（J3 走查修复）：仅含**段落非空**的章。目录校准把 level=2 子节行
     * 混入 chapters 列表但其 paras 恒空（段落全挂 level=1 父章）——若候选含子节，
     * 空段章 startPage 兜底 0 会抢走区间归属，且 ReadScreen byChapterFlow(父章) 永远
     * 查不到挂子节的图（阅读页整书丢图）。返回与候选平行对齐的 (chapterIds, startPages)：
     * 校准路径 startPages 取 chapterStartPages 同下标，现状路径取候选章首段 pageNo。
     */
    private fun anchoredChapters(
        chapterIds: List<Long>,
        chapters: List<Pair<ChapterEntity, List<ParagraphEntity>>>,
        chapterStartPages: List<Int>?,
    ): Pair<List<Long>, List<Int>> {
        val idxs = chapters.indices.filter { chapters[it].second.isNotEmpty() }
        val ids = idxs.map { chapterIds[it] }
        val starts = if (chapterStartPages != null) idxs.map { chapterStartPages[it] }
        else idxs.map { i -> chapters[i].second.firstNotNullOfOrNull { it.pageNo } ?: 0 }
        return ids to starts
    }

    fun bookProgressFlow(bookId: Long) = db.bookDao().getProgressFlow(bookId)
    suspend fun getBook(bookId: Long) = db.bookDao().get(bookId)
    suspend fun addBook(book: BookEntity) = db.bookDao().insert(book)
    suspend fun updateBook(book: BookEntity) = db.bookDao().update(book)

    /** 删书：Room CASCADE 清 figures 行；磁盘 figures/{bookId}/ 在 DB 删除成功后清理
     *  （r5-P0-2，无此步磁盘永久泄漏）。filesDir 未注入（null）时仅删 DB 行 */
    suspend fun removeBook(book: BookEntity) {
        db.bookDao().delete(book)
        filesDir?.let { root ->
            File(root, "figures/${book.id}").deleteRecursively()
        }
    }
    suspend fun overviewJson(bookId: Long) = db.bookDao().getOverviewJson(bookId)

    /** 调用方须保证书已存在（仅 UPDATE，bookId 无效时静默无操作） */
    suspend fun saveOverviewJson(bookId: Long, json: String, now: Long) =
        db.bookDao().saveOverviewJson(bookId, json, now)

    // ---- 视觉后台队列（OPT-F） ----
    suspend fun addVisionQueue(items: List<com.studyfriend.app.data.db.VisionQueueEntity>) =
        db.visionQueueDao().insertAll(items)

    /** 书架徽标：每本书的视觉增强进度（无队列的书不出现在流里） */
    fun visionProgressFlow() = db.visionQueueDao().progressByBookFlow()

    // ---- 目录 ----
    fun chaptersFlow(bookId: Long) = db.chapterDao().byBookFlow(bookId)
    suspend fun chapters(bookId: Long) = db.chapterDao().byBook(bookId)

    /**
     * 已入库书重跑目录校准（P3b-2 方案 Z 预留骨架，re-import 补救路径）：本期不实现，
     * 接口先行稳定——P5 立项时按方案 Z 覆盖率数据决定接入方式，无需重新设计签名。
     * 调用恒返回 NotImplementedError failure；P5 实现语义=事务内重切该书章节并置
     * chapters.calibrated=1，不触碰其他书（E2E 判据「re-import 不触碰库中其他书」）。
     */
    suspend fun calibrateExistingBook(bookId: Long): Result<Unit> =
        Result.failure(NotImplementedError("calibrateExistingBook 本期仅预留骨架（P3b-2 方案 Z），P5 接入实现"))

    /**
     * 目录重切：事务内删旧章重插，段落/讲解/资产等子表靠 CASCADE 清空。
     * ⚠ figures 行随 chapters CASCADE 蒸发且本方法不做重挂（r12-QC4-P1 告警）：
     * 章结构变化后的图归属重挂必须走 [replaceBookContent]（四步重挂逐列保留）；
     * 保留本方法仅供不需要 figures 的既有调用方，新代码勿用。
     */
    suspend fun replaceChapters(bookId: Long, chapters: List<ChapterEntity>) {
        Log.w(TAG, "replaceChapters does NOT reassign figures; figures rows will be CASCADE-deleted. Use replaceBookContent if chapter structure changes")
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
