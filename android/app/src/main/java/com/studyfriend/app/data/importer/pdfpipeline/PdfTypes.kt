package com.studyfriend.app.data.importer.pdfpipeline

/**
 * PDF 识别管线共享类型（OPT-E）。
 * 坐标均为"旋转修正后的显示坐标"（页面带 /Rotate 时已折算），y 自顶向下，单位 pt。
 */

/** 提取层行结构。size ≤ 0 表示字号未知（罕见字体），清洗层按正文字号兜底。
 *  sourceVersion（P6b）：产出该行的管线版本——0=数字 pdfbox 确定性路径、1=OCR
 *  （TextSourceRow.PROD_OCR_V1）；行级属性，混合书各行可异，P3a 打标阈值按段内
 *  最大 sourceVersion 分支（OCR 段放宽、数字段零改动，P6a 判据⑤触发条款落地） */
data class PLine(
    val text: String,
    val x0: Float,
    val x1: Float,
    val y0: Float,
    val size: Float,
    val sourceVersion: Int = 0,
)

/**
 * 段落；footnote=true 为页脚小字区段落（视觉转写的注释也归此）。
 * size = 段内最大字号，单位 pt（磅），与 PLine.size 同源同单位；0f=字号未知
 * （assembleText(styleAware=true) 不打〔标题〕前缀、不拦任何标题命中，安全方向）。
 * sourceVersion（P6b）= 段内行 sourceVersion 的最大值（0=全数字、1=含 OCR 行），
 * assembleText 据此选打标阈值；不入库、无 migration 问题。
 * y0 = 段首行显示空间 y0（pt，y 自顶向下；crossPageMerge 时保留合并主体段的原 y0），
 * P4 示意图锚定 ordAfterPara 的数据源；-1f=未知（视觉转写整页替换等无几何来源的构造）。
 * 内存数据类：持久化经 ParsedPara→Room Entity 转换，size/y0 不入库、无 migration 问题。
 */
data class Para(
    val text: String,
    val footnote: Boolean = false,
    val size: Float = 0f,
    val y0: Float = -1f,
    val sourceVersion: Int = 0,
)

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

/** 目录条目（P3b-1 视觉识别产物）；page=条目标注页码（null=未见页码），level 1=章/篇/部/编/卷/回 2=节。
 *  level 维持两级：章节树 UI（P3b-3）的层级由标题前缀（第X章/第X节）推导，
 *  TocEntry.level 只承担粗分职责，三级及以下条目按 PROMPT 统一记 2。 */
data class TocEntry(val title: String, val page: Int?, val level: Int)
