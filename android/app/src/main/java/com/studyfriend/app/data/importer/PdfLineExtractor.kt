package com.studyfriend.app.data.importer

import com.studyfriend.app.data.importer.pdfpipeline.PLine
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

        private var bufText = StringBuilder()
        private val bufPos = mutableListOf<TextPosition>()

        init {
            setSortByPosition(true)
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            bufText.append(text)
            bufPos.addAll(textPositions)
        }

        override fun writeWordSeparator() {
            bufText.append(' ')
        }

        override fun writeLineSeparator() {
            flushLine()
        }

        fun flushLine() {
            val text = bufText.toString()
            if (text.isNotBlank()) {
                lines.add(if (bufPos.isEmpty()) {
                    PLine(text.trim(), -1f, -1f, -1f, -1f)
                } else {
                    PLine(
                        text = text.trim(),
                        x0 = bufPos.minOf { it.xDirAdj },
                        x1 = bufPos.maxOf { it.xDirAdj + it.widthDirAdj },
                        y0 = bufPos.minOf { it.yDirAdj },
                        size = bufPos.maxOf { it.fontSizeInPt }
                            .takeIf { s -> s > 0f }
                            ?: bufPos.maxOf { it.heightDir }.takeIf { s -> s > 0f }
                            ?: -1f,
                    )
                })
            }
            bufText = StringBuilder()
            bufPos.clear()
        }
    }
}
