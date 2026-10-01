package com.studyfriend.app.data.importer

import android.content.Context
import android.net.Uri
import com.studyfriend.app.data.importer.pdfpipeline.DocStats
import com.studyfriend.app.data.importer.pdfpipeline.PageOut
import com.studyfriend.app.data.importer.pdfpipeline.PdfCleaner
import com.studyfriend.app.data.importer.pdfpipeline.PLine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

/** PDF 打不开/加密/扫描版/超大，统一走这里，UI 直接展示 message（open：允许取消类型细分归因） */
open class PdfImportException(message: String) : Exception(message)

/** 用户主动取消（独立类型：VM 据此与真实失败区分，避免把延后发生的 OOM/扫描版误报成取消） */
class CancelledImportException(message: String = "已取消") : PdfImportException(message)

/**
 * 提取产物（OPT-E）：清洗后的逐页段落 + 全书几何统计 + 是否扫描版。
 * assembleText() 先做跨页续接合并（视觉整页替换发生在合并之前——VM 流程是
 * extract → 选页 → 视觉转写替换 → assembleText，合并标志防重复合并）。
 */
class PdfExtractResult(
    val pages: List<PageOut>,
    val stats: DocStats,
    val scanned: Boolean,
) {
    private var merged = false

    fun assembleText(): String {
        if (!merged) {
            PdfCleaner.crossPageMerge(pages, stats)
            merged = true
        }
        // 段落间必须空行分隔：BookParser 按空行分块、每块至多一个标题，
        // 单个 \n 会把全书挤成一个块、只认出第一个章标题（DebugDump 实证）。
        // 例外：目录页条目用单 \n 连成整页一个块——条目一段一条会击穿
        // BookParser 的目录区密度判定，无点线条目会漏成假章（真书探针实证）。
        return pages.joinToString("\n\n") { page ->
            val sep = if (page.tocLike) "\n" else "\n\n"
            page.paras.joinToString(sep) { it.text }
        }
    }
}

/**
 * PDF 文本提取（§1/§4 + OPT-E）：经 cacheDir 临时文件控内存；逐页提取带
 * DirAdj 几何的行结构 → 全书几何统计 → 页面清洗（删页眉/页码、收脚注、
 * 目录页直通、段落组装）。平均每页字数 < 100 判定扫描版（无文字层）：
 * allowScanned=false 时抛友好提示，true 时返回 scanned=true 交视觉转写兜底。
 * 加密抛友好提示；OOM 兜底；进度逐页推进；复制段分块即时可断。
 */
object PdfLoader {

    private const val SCANNED_CHARS_PER_PAGE = 100

    fun extract(
        context: Context,
        uri: Uri,
        onProgress: (page: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        allowScanned: Boolean = false,
    ): PdfExtractResult {
        PDFBoxResourceLoader.init(context)
        val tmp = File(context.cacheDir, "import_${System.nanoTime()}.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    // 分块复制 + 检查点：大文件复制段（秒级）也即时可断，而不是等 copyTo 整体结束
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancelledImportException()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            } ?: throw PdfImportException("读取文件失败")
            return try {
                PDDocument.load(tmp).use { doc ->
                    val pages = doc.numberOfPages
                    if (pages <= 0) throw PdfImportException("这个 PDF 没有可读取的页面")
                    onProgress(0, pages)
                    val pagesLines = mutableListOf<List<PLine>>()
                    val dims = mutableListOf<Pair<Float, Float>>()
                    for (page in 1..pages) {
                        if (isCancelled()) throw CancelledImportException()
                        pagesLines.add(PdfLineExtractor.extractPage(doc, page))
                        // 旋转已折算的可视页宽高（清洗层算页眉/脚注条带用）
                        val pdPage = doc.getPage(page - 1)
                        val w = pdPage.cropBox.width
                        val h = pdPage.cropBox.height
                        dims.add(if (pdPage.rotation % 180 == 90) h to w else w to h)
                        onProgress(page, pages)
                    }
                    val totalChars = pagesLines.sumOf { lines -> lines.sumOf { it.text.length } }
                    val scanned = totalChars < pages * SCANNED_CHARS_PER_PAGE
                    if (scanned && !allowScanned) {
                        throw PdfImportException(
                            "这看起来是扫描版 PDF（没有文字层），暂时无法导入；" +
                                "请换成带文字的 PDF 或 TXT",
                        )
                    }
                    val stats = PdfCleaner.docStats(pagesLines, dims)
                    PdfExtractResult(PdfCleaner.clean(pagesLines, dims, stats), stats, scanned)
                }
            } catch (e: InvalidPasswordException) {
                throw PdfImportException("PDF 已加密，请先解除密码保护再导入")
            } catch (e: OutOfMemoryError) {
                throw PdfImportException("这本书太大，内存装不下；建议拆分成几个文件分批导入")
            } catch (e: PdfImportException) {
                throw e
            } catch (e: Exception) {
                throw PdfImportException("PDF 解析失败：${e.message ?: "文件可能已损坏"}")
            }
        } finally {
            tmp.delete()
        }
    }
}
