package com.studyfriend.app.data.importer.pdfpipeline

/**
 * PDF 识别管线共享类型（OPT-E）。
 * 坐标均为"旋转修正后的显示坐标"（页面带 /Rotate 时已折算），y 自顶向下，单位 pt。
 */

/** 提取层行结构。size ≤ 0 表示字号未知（罕见字体），清洗层按正文字号兜底 */
data class PLine(
    val text: String,
    val x0: Float,
    val x1: Float,
    val y0: Float,
    val size: Float,
)

/**
 * 段落；footnote=true 为页脚小字区段落（视觉转写的注释也归此）。
 * size = 段内最大字号，单位 pt（磅），与 PLine.size 同源同单位；0f=字号未知
 * （assembleText(styleAware=true) 不打〔标题〕前缀、不拦任何标题命中，安全方向）。
 * 内存数据类：持久化经 ParsedPara→Room Entity 转换，size 不入库、无 migration 问题。
 */
data class Para(val text: String, val footnote: Boolean = false, val size: Float = 0f)

/**
 * 单页清洗产物。paras 可变：视觉转写整页替换时由调用方改写。
 * firstLine/lastLine 是组装前页面首/末行几何（跨页续接判据用）；TOC 页与空页为 null。
 */
class PageOut(
    val pageNum: Int,
    val tocLike: Boolean,
    /** 清洗前文字层字符总数（视觉长度守卫/扫描版判定的口径） */
    val rawChars: Int,
    val puaCount: Int,
    val lineCount: Int,
    val shortLineCount: Int,
    paras: List<Para>,
    val firstLine: PLine?,
    val lastLine: PLine?,
) {
    val paras: MutableList<Para> = paras.toMutableList()
}

/** 视觉转写单页结果 */
class PageTranscription(
    val body: List<String>,
    val footnotes: List<String>,
) {
    val totalChars: Int get() = body.sumOf { it.length } + footnotes.sumOf { it.length }
}

/** 全书几何统计（PdfCleaner.docStats 产出，组装/跨页续接/段落判定共用） */
data class DocStats(
    /** 正文字号众数（pt）；未知字号行的兜底值。NaN 视为无信号（下游 >= 比较安全退化） */
    val bodySize: Float,
    /** 正文左边距众数（pt） */
    val left: Float,
    /** 正文右边界众数（pt） */
    val right: Float,
    /** 行距断段阈值（pt）；null = 行距无信号（样本太少且无谷点），退化为句末即断 */
    val pitchThreshold: Float?,
)
