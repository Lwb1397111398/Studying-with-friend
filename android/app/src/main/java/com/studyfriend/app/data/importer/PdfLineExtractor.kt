package com.studyfriend.app.data.importer

import android.util.Log
import com.studyfriend.app.data.importer.pdfpipeline.PLine
import com.studyfriend.app.data.importer.pdfpipeline.RowNormalizer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * 带几何信息的逐行提取（OPT-E）：PDFTextStripper 的 TextPosition 里取
 * DirAdj 坐标——"旋转修正后的显示坐标"，y 自页顶向下递增；旋转页
 * （如《民法总则》全本 /Rotate 90）与正常页落在同一坐标系，清洗层无感知。
 *
 * 每行产出 PLine：text、x0（行首）、x1（行尾）、y0（行顶）、size（行内最大字号，
 * ≤0 回退字形高度，仍 ≤0 记 −1 = 字号未知）。无位置信息的行坐标全记 −1，
 * 由清洗/组装层按"字号未知、几何中性"兜底。
 */
object PdfLineExtractor {

    fun extractPage(doc: PDDocument, page: Int): List<PLine> {
        val collector = LineCollector()
        collector.setStartPage(page)
        collector.setEndPage(page)
        collector.getText(doc)
        collector.flushLine() // 末行可能没有行分隔符收尾
        return collector.lines
    }

    private class LineCollector : PDFTextStripper() {

        val lines = mutableListOf<PLine>()

        private val bufSpans = mutableListOf<RowNormalizer.SpanInfo>()

        init {
            setSortByPosition(true)
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            if (text.isBlank()) {
                // 空白 token 不产 span（会污染几何），但空格信息不丢：归前一 span 尾部
                if (textPositions.isNotEmpty()) {
                    bufSpans.lastOrNull()?.let { last ->
                        bufSpans[bufSpans.lastIndex] = last.copy(text = last.text + " ")
                    }
                }
                return
            }
            bufSpans.add(
                RowNormalizer.SpanInfo(
                    text = text,
                    x0 = textPositions.minOf { it.xDirAdj },
                    x1 = textPositions.maxOf { it.xDirAdj + it.widthDirAdj },
                    y0 = textPositions.minOf { it.yDirAdj },
                    // span 字号：fontSizeInPt 优先，≤0 回退字形高度，仍 ≤0 记 −1
                    size = textPositions.maxOf { it.fontSizeInPt }
                        .takeIf { s -> s > 0f }
                        ?: textPositions.maxOf { it.heightDir }.takeIf { s -> s > 0f }
                        ?: -1f,
                ),
            )
        }

        override fun writeWordSeparator() {
            // 词间空格归到前一个 span 尾部（行首空格无意义，忽略）
            bufSpans.lastOrNull()?.let { last ->
                bufSpans[bufSpans.lastIndex] = last.copy(text = last.text + " ")
            }
        }

        override fun writeLineSeparator() {
            flushLine()
        }

        fun flushLine() {
            val spans = ArrayList(bufSpans)
            bufSpans.clear()
            if (spans.isEmpty()) return
            val out = try {
                RowNormalizer.normalize(spans) { msg -> Log.w("OPTG", msg) } // OPT-G P1
            } catch (e: Exception) {
                Log.w("OPTG", "RowNormalizer 回退现行为: ${e.message}")
                fallbackLine(spans)
            }
            lines.addAll(out)
        }

        /** 现行为兜底：整行压平单 PLine（size 取行内最大字号） */
        private fun fallbackLine(spans: List<RowNormalizer.SpanInfo>): List<PLine> {
            val text = spans.joinToString("") { it.text }
            if (text.isBlank()) return emptyList()
            val pos = spans.any { it.x0 >= 0 }
            return listOf(
                if (!pos) {
                    PLine(text.trim(), -1f, -1f, -1f, -1f)
                } else {
                    PLine(
                        text = text.trim(),
                        x0 = spans.minOf { it.x0 },
                        x1 = spans.maxOf { it.x1 },
                        y0 = spans.minOf { it.y0 },
                        size = spans.maxOf { it.size },
                    )
                },
            )
        }
    }
}
