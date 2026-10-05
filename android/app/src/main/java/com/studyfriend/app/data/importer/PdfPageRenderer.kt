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
 * 内存纪律：ARGB_8888（PdfRenderer.render 只认这一种，RGB_565 会抛
 * Unsupported pixel format；A4 一页 140dpi 约 9MB）+ 用完即 recycle +
 * PNG 压缩后即释放，任意时刻只有一页位图驻留。
 */
class PdfPageRenderer(context: Context, uri: Uri) : Closeable {

    private val fd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw PdfImportException("读取文件失败")
    private val renderer = PdfRenderer(fd)

    val pageCount: Int get() = renderer.pageCount

    /** 全部页的 pt 尺寸 (宽, 高)（OCR 管线清洗层按真实页宽算页眉/脚注条带，P6b S4） */
    fun pageDims(): List<Pair<Float, Float>> =
        (0 until renderer.pageCount).map { i ->
            renderer.openPage(i).use { it.width.toFloat() to it.height.toFloat() }
        }

    /** 0-based 页号 → 渲染位图（P6b S4 OCR 识别输入；调用方负责 recycle，单页驻留） */
    fun renderPageBitmap(index: Int): Bitmap {
        renderer.openPage(index).use { page ->
            val scale = DPI / 72f
            val bmp = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            return bmp
        }
    }

    /** 0-based 页号 → PNG 的 Base64（NO_WRAP，可直接进 data URI） */
    fun renderPageBase64(index: Int): String {
        renderer.openPage(index).use { page ->
            val scale = DPI / 72f
            val bmp = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
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

    companion object {
        /** 经验帖校准：140dpi 在识别质量与传输体积间取平衡（OCR 像素→pt 换算共用此值） */
        const val DPI = 140
    }
}
