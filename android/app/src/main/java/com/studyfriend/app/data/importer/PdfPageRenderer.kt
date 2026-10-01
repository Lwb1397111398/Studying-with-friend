package com.studyfriend.app.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.Closeable

/**
 * 页面渲染（OPT-E）：android 自带 PdfRenderer（不走 pdfbox 渲染，省一份实现），
 * 把选中页渲成 140dpi PNG → Base64，交给视觉转写。
 *
 * 内存纪律：RGB_565（A4 一页约 4MB）+ 用完即 recycle + PNG 压缩后即释放，
 * 任意时刻只有一页位图驻留。
 */
class PdfPageRenderer(context: Context, uri: Uri) : Closeable {

    private val fd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw PdfImportException("读取文件失败")
    private val renderer = PdfRenderer(fd)

    val pageCount: Int get() = renderer.pageCount

    /** 0-based 页号 → PNG 的 Base64（NO_WRAP，可直接进 data URI） */
    fun renderPageBase64(index: Int): String {
        renderer.openPage(index).use { page ->
            val scale = DPI / 72f
            val bmp = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.RGB_565,
            )
            try {
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 0, out)
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } finally {
                bmp.recycle()
            }
        }
    }

    override fun close() {
        try {
            renderer.close()
        } finally {
            fd.close()
        }
    }

    private companion object {
        /** 经验帖校准：140dpi 在识别质量与传输体积间取平衡 */
        const val DPI = 140
    }
}
