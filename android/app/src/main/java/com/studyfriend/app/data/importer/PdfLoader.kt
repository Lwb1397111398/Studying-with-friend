package com.studyfriend.app.data.importer

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** PDF 打不开/加密/扫描版/超大，统一走这里，UI 直接展示 message */
class PdfImportException(message: String) : Exception(message)

/**
 * PDF 文本提取（§1/§4）：逐页提取控内存；加密抛友好提示；
 * 平均每页可提取字数 < 100 判定为扫描版（无文字层）；OOM 兜底。
 */
object PdfLoader {

    private const val SCANNED_CHARS_PER_PAGE = 100

    fun extract(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw PdfImportException("读取文件失败")
        return try {
            PDDocument.load(bytes).use { doc ->
                val pages = doc.numberOfPages
                if (pages <= 0) throw PdfImportException("这个 PDF 没有可读取的页面")
                val stripper = PDFTextStripper()
                val sb = StringBuilder()
                for (page in 1..pages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    sb.append(stripper.getText(doc))
                    sb.append('\n')
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
    }
}
