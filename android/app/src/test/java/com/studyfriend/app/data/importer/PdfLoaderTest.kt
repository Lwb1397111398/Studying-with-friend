package com.studyfriend.app.data.importer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.importer.BookParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PDF 提取：fixture 用 pdfbox 程序化生成，不依赖二进制资源 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfLoaderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    /** 生成多页 PDF：每页若干行 ASCII 文本 */
    private fun pdfBytes(pages: List<List<String>>): ByteArray {
        val doc = PDDocument()
        val font = PDType1Font.HELVETICA
        pages.forEach { lines ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val cs = PDPageContentStream(doc, page)
            var y = 750f
            lines.forEach { line ->
                cs.beginText()
                cs.setFont(font, 11f)
                cs.newLineAtOffset(50f, y)
                cs.showText(line)
                cs.endText()
                y -= 16f
            }
            cs.close()
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun toUri(bytes: ByteArray): Uri {
        val file = File(context.cacheDir, "fixture${System.nanoTime()}.pdf")
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun fillerLines(title: String, body: Int): List<String> {
        // 每行约 60 字符，body 行保证超过每页 100 字的扫描版判定线
        return listOf(title) + List(body) { "Line $it of body text for the fixture chapter page." }
    }

    @Test
    fun extractTwoPages_thenParserSplitsChapters() = runBlocking {
        val bytes = pdfBytes(
            listOf(
                fillerLines("Chapter 1 First Steps", 4),
                fillerLines("Chapter 2 Going Deeper", 4),
            ),
        )
        val text = PdfLoader.extract(context, toUri(bytes))
        assertTrue(text.contains("Chapter 1"))
        assertTrue(text.contains("Chapter 2"))

        val chapters = BookParser.parse(text)
        assertEquals(2, chapters.size)
        assertEquals("Chapter 1 First Steps", chapters[0].title)
        assertEquals("Chapter 2 Going Deeper", chapters[1].title)
    }

    @Test
    fun scannedPdf_throwsFriendlyError() {
        // 只有标题行，远低于每页 100 字 → 判定扫描版
        val bytes = pdfBytes(listOf(listOf("Chapter 1 Barely Anything")))
        try {
            PdfLoader.extract(context, toUri(bytes))
            throw AssertionError("应当抛出扫描版提示")
        } catch (e: PdfImportException) {
            assertTrue(e.message!!.contains("扫描版"))
        }
    }

    @Test
    fun corruptedPdf_throwsFriendlyError() {
        val file = File(context.cacheDir, "broken${System.nanoTime()}.pdf")
        file.writeBytes(ByteArray(512) { (it % 251).toByte() })
        try {
            PdfLoader.extract(context, Uri.fromFile(file))
            throw AssertionError("应当抛出解析失败提示")
        } catch (e: PdfImportException) {
            assertTrue(e.message!!.contains("PDF"))
        }
    }
}
