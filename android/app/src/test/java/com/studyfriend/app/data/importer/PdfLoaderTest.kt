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
        assertTempFilesCleaned() // 损坏路径同样必须清理
    }

    @Test
    fun progressCallback_advancesPerPage_includingBoundaries() {
        val bytes = pdfBytes(listOf(fillerLines("Chapter 1 A", 4), fillerLines("Chapter 2 B", 4), fillerLines("Chapter 3 C", 4)))
        val seen = mutableListOf<Pair<Int, Int>>()
        // 尾随 lambda 会绑定到最后一个参数 isCancelled，进度回调必须用命名参数
        PdfLoader.extract(context, toUri(bytes), onProgress = { p, t -> seen.add(p to t) })
        assertEquals(0 to 3, seen.first())
        assertEquals(3 to 3, seen.last())
        assertEquals((0..3).toList(), seen.map { it.first }) // 单调不减且连续
    }

    @Test
    fun cancelCheckpoint_throwsCancelledImport_andCleansTempFile() {
        val bytes = pdfBytes(listOf(fillerLines("Chapter 1 A", 4), fillerLines("Chapter 2 B", 4)))
        var calls = 0
        try {
            PdfLoader.extract(context, toUri(bytes), isCancelled = { ++calls > 1 }) // 在某个检查点触发（复制段/逐页段均可）
            throw AssertionError("应当抛出取消")
        } catch (e: PdfImportException) {
            assertTrue(e.message!!.contains("取消"))
        }
        assertTempFilesCleaned()
    }

    @Test
    fun tempFile_removedAfterSuccess() {
        val bytes = pdfBytes(listOf(fillerLines("Chapter 1 A", 4)))
        PdfLoader.extract(context, toUri(bytes))
        assertTempFilesCleaned()
    }

    @Test
    fun pageLoopCheckpoint_cancelsAfterProgressStarted() {
        // 专项覆盖逐页检查点（上一例对小 fixture 实际触发在复制段）：
        // 若页循环无检查点，extract 会正常完成 → AssertionError，本例即红
        val bytes = pdfBytes(
            listOf(fillerLines("Chapter 1 A", 4), fillerLines("Chapter 2 B", 4), fillerLines("Chapter 3 C", 4)),
        )
        var progressed = 0
        try {
            PdfLoader.extract(
                context, toUri(bytes),
                onProgress = { _, _ -> progressed++ },
                isCancelled = { progressed >= 2 }, // (0,total) 与第 1 页进度已发后，在页循环内中止
            )
            throw AssertionError("应当抛出取消")
        } catch (e: PdfImportException) {
            assertTrue(e.message!!.contains("取消"))
            assertTrue("取消应发生在页循环推进之后", progressed >= 2)
        }
        assertTempFilesCleaned()
    }

    @Test
    fun copySectionCheckpoint_cancelsBeforePageLoop_onLargeFile() {
        // 闭合验收缺口：>64KB 多块文件，第 2 个 64KB 块的复制检查点即触发取消；
        // progressed==0 证明取消发生在复制段（若删掉复制循环内检查点，取消会
        // 延迟到页循环、progressed≥1，本例即红）
        val pages = List(200) { fillerLines("Chapter filler page $it", 50) }
        val bytes = pdfBytes(pages)
        assertTrue("fixture 必须 >64KB 才能跨多个复制块", bytes.size > 64 * 1024)
        var calls = 0
        var progressed = 0
        try {
            PdfLoader.extract(
                context, toUri(bytes),
                onProgress = { _, _ -> progressed++ },
                isCancelled = { ++calls > 1 },
            )
            throw AssertionError("应当抛出取消")
        } catch (e: PdfImportException) {
            assertTrue(e.message!!.contains("取消"))
            assertEquals("取消应发生在复制段（未进入页循环）", 0, progressed)
        }
        assertTempFilesCleaned()
    }

    /** 三路径（成功/取消/损坏）共用的清理断言 */
    private fun assertTempFilesCleaned() {
        val leftovers = context.cacheDir.listFiles { f -> f.name.startsWith("import_") } ?: emptyArray()
        assertTrue("不应残留临时文件：${leftovers.toList()}", leftovers.isEmpty())
    }
}
