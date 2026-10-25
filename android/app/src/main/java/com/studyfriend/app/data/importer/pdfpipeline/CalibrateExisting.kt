package com.studyfriend.app.data.importer.pdfpipeline

import android.net.Uri
import android.util.Log
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.BookRepository
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.importer.BookParser
import com.studyfriend.app.data.importer.ParsedChapter
import com.studyfriend.app.data.importer.ParsedPara
import com.studyfriend.app.data.importer.PdfPageRenderer
import com.studyfriend.app.data.vision.VisionCache
import java.io.File

/**
 * 批B：已入库书事后目录校准（BookRepository.calibrateExistingBook P5 骨架转正）。
 *
 * 输入只要 bookId：现章/段落从 DB 组装（P3b-2 pageNo 链路），目录页候选由页文本
 * 形态启发式检出（行命中 [BookParser.RE_TOC_LINE] ≥3 行的页），探针走
 * [TocVisionParser]（同书同缓存/marker，与导入期探针零差异），校准产物经
 * [BookRepository.replaceBookContent] 重建（figures 四步重挂内置）。
 *
 * 守卫：有 AI 消费的书拒绝（讲解/标注/资产/排期任一存在即拒——重切章节会 CASCADE
 * 清掉这些学习记录）；阅读进度按章名对齐回填（title 归一化等值），匹配不上的章
 * 归 NOT_READ。extract/OCR 一概不重跑，只烧目录探针的视觉调用（有缓存）。
 */
object CalibrateExisting {

    private const val TAG = "CalibB"
    private const val SRC_PDF = "PDF"

    /** 目录候选页的行命中门槛：页内 RE_TOC_LINE 命中 ≥3 行才算目录形态页 */
    private const val TOC_PAGE_MIN_LINES = 3

    /** 段落长度守卫：重切产物字符量 < 原文 90% 拒绝落库（章界变化不应吞正文） */
    private const val CHARS_KEEP_RATIO = 0.9

    suspend fun run(app: StudyApp, bookId: Long): Result<String> = runCatching {
        val db = app.database
        val book = db.bookDao().get(bookId)
            ?: throw IllegalStateException("书不存在")
        if (book.sourceType != SRC_PDF) {
            throw IllegalStateException("仅 PDF 导入的书支持目录校准")
        }
        if (db.visionQueueDao().hasAnyAiConsumption(bookId)) {
            throw IllegalStateException("本书已有讲解/标注/总结/复习记录，重切章节会清掉这些学习记录，暂不支持事后校准")
        }

        // ① 现章 → ParsedChapter（段落带 pageNo，calibrate 的锚定链路）
        val chapters = db.chapterDao().byBook(bookId)
        if (chapters.isEmpty()) throw IllegalStateException("本书没有章节")
        val parasByChapter = HashMap<Long, List<ParagraphEntity>>()
        val localChapters = chapters.map { ch ->
            val paras = db.paragraphDao().byChapter(ch.id)
            parasByChapter[ch.id] = paras
            ParsedChapter(
                title = ch.title,
                paras = paras.map { ParsedPara(it.text, it.role, it.pageNo) },
            )
        }
        val originChars = localChapters.sumOf { c -> c.paras.sumOf { it.text.length } }

        // ② 页文本组装（按段落 pageNo 分组；页内多段按段序拼接）
        val pageText = HashMap<Int, StringBuilder>()
        for (ch in localChapters) {
            for (p in ch.paras) {
                val pn = p.pageNo ?: continue
                pageText.getOrPut(pn) { StringBuilder() }.appendLine(p.text)
            }
        }
        if (pageText.isEmpty()) {
            throw IllegalStateException("本书段落没有页码信息（可能经视觉重建），无法锚定校准")
        }
        val maxPage = pageText.keys.max()

        // ③ 目录候选页：行级 RE_TOC_LINE 命中 ≥3 → TocPageGrouper 连续分组
        val tocLikePages = pageText.entries.mapNotNull { (pn, sb) ->
            val hits = sb.lineSequence().count { BookParser.RE_TOC_LINE.containsMatchIn(it.trim()) }
            if (hits >= TOC_PAGE_MIN_LINES) pn else null
        }
        val segments = TocPageGrouper.group(tocLikePages)
        if (segments.isEmpty()) {
            throw IllegalStateException("书页文本中未检出目录页（需要带页码的目录），无法校准")
        }

        // ④ 目录探针（视觉；同书同缓存/marker 与导入期一致）
        val uri = Uri.parse(book.filePath)
        val entries = PdfPageRenderer(app, uri).use { renderer ->
            val parser = app.settingsRepo.buildTocVisionParser(
                File(app.cacheDir, "vision_cache/toc"),
            ) { pageNo -> renderer.renderPageBase64(pageNo - 1) }
                ?: throw IllegalStateException("视觉模型未配置：设置 → 模型页配置后再校准")
            parser.parseSegments(segments, VisionCache.uriHash(uri.toString()))
        } ?: throw IllegalStateException("目录识别失败（视觉转写失败或额度耗尽），稍后可再试")

        // ⑤ 校准 + 守卫
        val outcome = TocChapterCalibrator.calibrate(
            entries = entries,
            localChapters = localChapters,
            pageText = { pageText[it]?.toString() },
            bookPageCount = maxPage,
            tocLastPages = segments.map { s -> s.max() },
        ) ?: throw IllegalStateException("目录校准未通过守卫（目录与正文对不上），本书保持原样")

        // 段落字符守卫：校准只重排不删文，产物骤降说明重排出错，拒绝落库
        val newChars = outcome.chapters.sumOf { c -> c.paras.sumOf { it.text.length } }
        if (newChars.toLong() < originChars.toLong() * CHARS_KEEP_RATIO) {
            throw IllegalStateException("校准产物正文骤降（$originChars→$newChars 字），已拒绝写入，本书保持原样")
        }

        // ⑥ 落库：readState 按章名对齐回填（归一化等值），calibrated=1
        val norm = { t: String -> t.replace(Regex("\\s+"), "") }
        val oldReadByTitle = HashMap<String, String>()
        for (ch in chapters) {
            if (ch.readState != DbValues.READ_NOT) oldReadByTitle[norm(ch.title)] = ch.readState
        }
        val oldLastChapterTitle = book.lastReadChapterId
            ?.let { last -> chapters.firstOrNull { it.id == last }?.title }

        val pairs = outcome.chapters.map { cc ->
            ChapterEntity(
                bookId = bookId, idx = 0,
                title = cc.title,
                readState = oldReadByTitle[norm(cc.title)] ?: DbValues.READ_NOT,
                gist = null, keyTermsJson = null,
                level = cc.level, parentOrder = cc.parentOrder, calibrated = true,
            ) to cc.paras.map { p ->
                ParagraphEntity(chapterId = 0, idx = 0, text = p.text, role = p.role, pageNo = p.pageNo)
            }
        }
        BookRepository(db, app.filesDir).replaceBookContent(
            bookId,
            book.copy(lastReadChapterId = null), // 下面按章名重定向，先清防悬挂
            pairs,
        )

        // ⑦ 继续读位置重定向：旧 lastRead 章按名匹配新章
        if (oldLastChapterTitle != null) {
            val newId = db.chapterDao().byBook(bookId)
                .firstOrNull { it.level == 1 && norm(it.title) == norm(oldLastChapterTitle) }?.id
            if (newId != null) {
                db.bookDao().get(bookId)?.let { fresh ->
                    db.bookDao().update(fresh.copy(lastReadChapterId = newId))
                }
            }
        }
        val level1 = outcome.chapters.count { it.level == 1 }
        Log.i(TAG, "calibrated book=$bookId chapters=${outcome.chapters.size} level1=$level1")
        Result.success("已按目录重新切章：共 $level1 章（原 ${chapters.count { it.level == 1 }} 章），阅读进度按章名保留")
    }.getOrElse { e ->
        Log.w(TAG, "calibrate failed: ${e.message}")
        Result.failure(e)
    }
}
