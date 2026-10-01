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
                    ParagraphEntity(chapterId = 0, idx = 0, text = "Original text layer paragraph one.", role = DbValues.ROLE_BODY),
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
        // 书内容已换成视觉文本；重建按同一 uri 重提取+缓存回填，段落应来自缓存
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
}
