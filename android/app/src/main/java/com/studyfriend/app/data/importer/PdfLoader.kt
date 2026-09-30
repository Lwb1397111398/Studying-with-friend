package com.studyfriend.app.data.importer

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

/** PDF 打不开/加密/扫描版/超大，统一走这里，UI 直接展示 message（open：允许取消类型细分归因） */
open class PdfImportException(message: String) : Exception(message)

/** 用户主动取消（独立类型：VM 据此与真实失败区分，避免把延后发生的 OOM/扫描版误报成取消） */
class CancelledImportException(message: String = "已取消") : PdfImportException(message)

/**
 * PDF 文本提取（§1/§4）：经 cacheDir 临时文件逐页提取控内存（OPT-B：消除
 * "原始字节 + 解析结构"双驻留）；加密抛友好提示；平均每页可提取字数 < 100
 * 判定为扫描版（无文字层）；OOM 兜底；进度回调逐页推进；复制段分块即时可断、
 * load/解析段取消延后生效（秒级）。
 */
object PdfLoader {

    private const val SCANNED_CHARS_PER_PAGE = 100

    fun extract(
        context: Context,
        uri: Uri,
        onProgress: (page: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): String {
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
                    val stripper = PDFTextStripper()
                    val sb = StringBuilder()
                    onProgress(0, pages)
                    for (page in 1..pages) {
                        if (isCancelled()) throw CancelledImportException()
                        stripper.startPage = page
                        stripper.endPage = page
                        sb.append(stripper.getText(doc))
                        sb.append('\n')
                        onProgress(page, pages)
                    }
                    val text = sb.toString()
                    if (text.length < pages * SCANNED_CHARS_PER_PAGE) {
                        throw PdfImportException(
                            "这看起来是扫描版 PDF（没有文字层），暂时无法导入；" +
                                "请换成带文字的 PDF 或 TXT",
                        )
                    }
                    text
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
