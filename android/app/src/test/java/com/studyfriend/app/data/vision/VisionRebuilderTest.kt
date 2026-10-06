package com.studyfriend.app.data.vision

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.BookRepository
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.db.VisionQueueEntity
import com.studyfriend.app.data.importer.pdfpipeline.PageTranscription
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 守卫式整书重建（OPT-F）：程序化 PDF + 落盘缓存回填。
 * 覆盖：无 AI 消费 → 重建把缓存内容换进书；有粗读标注 → 守卫拒绝、书不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisionRebuilderTest {

    private lateinit var context: Context
    private lateinit var db: StudyDatabase
    private lateinit var pdfFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        db = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java)
            .allowMainThreadQueries().build()
        pdfFile = File(context.cacheDir, "rebuild_test_${System.nanoTime()}.pdf")
        pdfFile.writeBytes(onePagePdf())
    }

    @After
    fun tearDown() {
        db.close()
        pdfFile.delete()
    }

    /** 单页 PDF：两行 ASCII 正文 */
    private fun onePagePdf(): ByteArray {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val cs = PDPageContentStream(doc, page)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA, 11f)
        cs.newLineAtOffset(50f, 750f)
        cs.showText("Original text layer paragraph one.")
        cs.endText()
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA, 11f)
        cs.newLineAtOffset(50f, 730f)
        cs.showText("Original text layer paragraph two.")
        cs.endText()
        cs.close()
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private suspend fun seedBook(): Long {
        val uri = Uri.fromFile(pdfFile).toString()
        val bookId = BookRepository(db).importBook(
            BookEntity(
                title = "测试书", author = "", sourceType = DbValues.SRC_PDF,
                filePath = uri, status = DbValues.BOOK_READY, totalChapters = 1,
                overviewJson = null, createdAt = 1, updatedAt = 1,
            ),
            listOf(
                ChapterEntity(
                    bookId = 0, idx = 0, title = "全文",
                    readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
                ) to listOf(
                    // pageNo=1（P6c C3a 修复适配）：真实 PDF 书段落带页码（P3b-2 起
                    // BookParser PAGE_MARK 链路），重建底稿按页重组依赖该字段
                    ParagraphEntity(chapterId = 0, idx = 0, text = "Original text layer paragraph one.",
                        role = DbValues.ROLE_BODY, pageNo = 1),
                ),
            ),
        )
        // 队列：页 1 已转写完成（DONE）——与真实 Worker 收尾状态一致
        db.visionQueueDao().insertAll(
            listOf(
                VisionQueueEntity(
                    bookId = bookId, uri = uri, pageNo = 1, originChars = 68,
                    status = DbValues.VQ_DONE, attempts = 0, updatedAt = 1,
                ),
            ),
        )
        return bookId
    }

    @Test
    fun rebuild_replacesBookContent_fromCache() = runBlocking {
        val uri = Uri.fromFile(pdfFile).toString()
        val bookId = seedBook()
        // 缓存里的视觉转写结果（Worker 转写完成后落盘的形态）
        VisionCache.write(
            context, uri, 1,
            PageTranscription(body = listOf("视觉替换后的正文，讲的是民事法律关系。"), footnotes = emptyList()),
        )
        val rebuilt = VisionRebuilder.rebuildIfSafe(db, context, bookId)
        assertTrue("无 AI 消费的书应重建", rebuilt)
        // 书内容已换成视觉文本；重建从库底稿重组+缓存回填，段落应来自缓存
        val chapterId = BookRepository(db).chapters(bookId)[0].id
        val paras = BookRepository(db).paragraphs(chapterId)
        assertTrue(
            "段落应含视觉转写文本",
            paras.any { it.text.contains("视觉替换后的正文") },
        )
        assertFalse("旧文字层文本不应残留为独立段", paras.any { it.text == "Original text layer paragraph one." })
        // 重建后队列不动（保留 DONE 供进度展示）
        assertEquals(1, db.visionQueueDao().byBook(bookId).size)
    }

    @Test
    fun rebuild_guarded_whenAnyAiConsumption() = runBlocking {
        val bookId = seedBook()
        val chapterId = BookRepository(db).chapters(bookId)[0].id
        BookRepository(db).setAiAction(
            BookRepository(db).paragraphs(chapterId)[0].id,
            DbValues.ACT_EXPLAIN, null, null,
        )
        VisionCache.write(
            context, Uri.fromFile(pdfFile).toString(), 1,
            PageTranscription(body = listOf("视觉替换后的正文。"), footnotes = emptyList()),
        )
        val rebuilt = VisionRebuilder.rebuildIfSafe(db, context, bookId)
        assertFalse("已有粗读标注的书不可重建", rebuilt)
        // 段落原样未动
        val paras = BookRepository(db).paragraphs(chapterId)
        assertEquals(listOf("Original text layer paragraph one."), paras.map { it.text })
    }

    @Test
    fun rebuild_noop_whenQueueEmptyOrNoCache() = runBlocking {
        // 队列空 → false
        val emptyBookId = BookRepository(db).importBook(
            BookEntity(
                title = "空书", author = "", sourceType = DbValues.SRC_PDF,
                filePath = "uri://none", status = DbValues.BOOK_READY, totalChapters = 0,
                overviewJson = null, createdAt = 1, updatedAt = 1,
            ),
            emptyList(),
        )
        assertFalse(VisionRebuilder.rebuildIfSafe(db, context, emptyBookId))
        // 队列 DONE 但缓存缺失（被系统清理）→ false，不误重建
        val bookId = seedBook()
        assertFalse(VisionRebuilder.rebuildIfSafe(db, context, bookId))
        // 缓存被系统清理（cacheDir 可回收）时不误重建：DONE 页无缓存 → false，书保持原样
        val paras = BookRepository(db).paragraphs(BookRepository(db).chapters(bookId)[0].id)
        assertEquals(listOf("Original text layer paragraph one."), paras.map { it.text })
    }

    /** 两章底稿（R1 方向 A 主用例）：甲章=页1 两段+页2 一段，乙章=页3 一段；
     *  每段 3 字便于核对字符守恒 */
    private suspend fun seedTwoChapterBook(): Pair<Long, String> {
        val uri = Uri.fromFile(pdfFile).toString()
        val bookId = BookRepository(db).importBook(
            BookEntity(
                title = "两章测试书", author = "", sourceType = DbValues.SRC_PDF,
                filePath = uri, status = DbValues.BOOK_READY, totalChapters = 2,
                overviewJson = null, createdAt = 1, updatedAt = 1,
            ),
            listOf(
                ChapterEntity(
                    bookId = 0, idx = 0, title = "甲章",
                    readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
                ) to listOf(
                    ParagraphEntity(chapterId = 0, idx = 0, text = "甲一一",
                        role = DbValues.ROLE_BODY, pageNo = 1),
                    ParagraphEntity(chapterId = 0, idx = 1, text = "甲一二",
                        role = DbValues.ROLE_BODY, pageNo = 1),
                    ParagraphEntity(chapterId = 0, idx = 2, text = "甲二一",
                        role = DbValues.ROLE_BODY, pageNo = 2),
                ),
                ChapterEntity(
                    bookId = 0, idx = 1, title = "乙章",
                    readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
                ) to listOf(
                    ParagraphEntity(chapterId = 0, idx = 0, text = "乙三一",
                        role = DbValues.ROLE_BODY, pageNo = 3),
                ),
            ),
        )
        db.visionQueueDao().insertAll(
            listOf(
                VisionQueueEntity(bookId = bookId, uri = uri, pageNo = 2,
                    originChars = 30, status = DbValues.VQ_DONE, attempts = 0, updatedAt = 1),
                VisionQueueEntity(bookId = bookId, uri = uri, pageNo = 3,
                    originChars = 30, status = DbValues.VQ_DONE, attempts = 0, updatedAt = 1),
            ),
        )
        return bookId to uri
    }

    @Test
    fun rebuild_preservesChapters_andPlacesVisionByOwner() = runBlocking {
        val (bookId, uri) = seedTwoChapterBook()
        // 页2 视觉转写：正文段+脚注；页3 视觉转写：目录页（3 条目 ≥ TOC_INFER_MIN）
        VisionCache.write(
            context, uri, 2,
            PageTranscription(body = listOf("甲二视觉段"), footnotes = listOf("甲二脚注")),
        )
        VisionCache.write(
            context, uri, 3,
            PageTranscription(
                // 「目 录」不匹配 RE_TOC_ENTRY；3 条章目 ≥ TOC_INFER_MIN 才判 tocLike
                body = listOf(
                    "目 录", "第一章 概述 .... 4", "第二章 保证 .... 6", "第三章 抵押 .... 8",
                ),
                footnotes = emptyList(),
            ),
        )
        val rebuilt = VisionRebuilder.rebuildIfSafe(db, context, bookId)
        assertTrue("无 AI 消费的两章书应重建", rebuilt)

        // 章结构保全：2 章原顺序原 title（replaceBookContent 按 idx 重编号，列表序=原序）
        val chapters = BookRepository(db).chapters(bookId)
        assertEquals(listOf("甲章", "乙章"), chapters.map { it.title })
        assertEquals(2, db.bookDao().get(bookId)!!.totalChapters)

        // D1 页归属：页2 视觉段归甲章（该页末行 甲二一 属甲章）；页1 底稿行原地保留
        val parasA = BookRepository(db).paragraphs(chapters[0].id)
        assertEquals(listOf("甲一一", "甲一二", "甲二视觉段", "甲二脚注"), parasA.map { it.text })
        assertEquals(
            listOf(DbValues.ROLE_BODY, DbValues.ROLE_BODY, DbValues.ROLE_BODY, DbValues.ROLE_FOOTNOTE),
            parasA.map { it.role },
        )
        assertEquals(listOf(1, 1, 2, 2), parasA.map { it.pageNo })

        // D2 页归属：页3 是孤儿页？否——底稿有 乙三一 → D1 归乙章；3 条章目判 tocLike → ROLE_TOC
        val parasB = BookRepository(db).paragraphs(chapters[1].id)
        assertEquals(
            listOf("目 录", "第一章 概述 .... 4", "第二章 保证 .... 6", "第三章 抵押 .... 8"),
            parasB.map { it.text },
        )
        assertEquals(
            listOf(DbValues.ROLE_TOC, DbValues.ROLE_TOC, DbValues.ROLE_TOC, DbValues.ROLE_TOC),
            parasB.map { it.role },
        )
        assertEquals(listOf(3, 3, 3, 3), parasB.map { it.pageNo })

        // 无空段、旧底稿行（被替换页）不残留
        val allParas = parasA + parasB
        assertTrue("不应有空段", allParas.none { it.text.isBlank() })
        assertFalse("被替换页底稿行不应残留", allParas.any { it.text == "甲二一" || it.text == "乙三一" })

        // 字符守恒台账（构造值必然成立）：底稿 12 − 替换页底稿 6 + 视觉 51(5+4+3+13+13+13) = 57
        assertEquals(57, allParas.sumOf { it.text.length })
        // 队列不动（保留 DONE 供进度展示）
        assertEquals(2, db.visionQueueDao().byBook(bookId).size)
    }
}
