package com.studyfriend.app.data.importer.pdfpipeline

/**
 * 视觉兜底选页（OPT-E）：规则优先，只把"规则救不了"的页送视觉转写。
 * 判据（命中任一即选）：
 * - V1 近空白：清洗前 rawChars < 5（提取失败/纯图页）
 * - V2 坏字体：PUA 计数 ≥ 2（视觉模型能认出映射坏的字形）
 * - V3 碎行页：行数 ≥5 且短行 ≥5 且占比 ≥ 0.4（复杂版式/残缺排版）
 * 目录页一律跳过（目录条目是章节资产，视觉替换会毁掉它）。
 */
object PageSelector {

    private const val MIN_CHARS = 5
    private const val MIN_PUA = 2
    private const val MIN_LINES = 5
    private const val MIN_SHORT_RATIO = 0.4f

    /** 混合模式单次视觉转写上限；超出直接拒绝（提示拆分） */
    const val NORMAL_MAX_PAGES = 60

    /** 扫描版整书转写上限；超出拒绝 */
    const val WHOLE_BOOK_MAX_PAGES = 80

    sealed class Selection {
        data class Pages(val pages: List<Int>) : Selection()
        data class TooMany(val totalPages: Int) : Selection()
        data object None : Selection()
    }

    fun select(pages: List<PageOut>, scanned: Boolean): Selection {
        if (scanned) {
            return if (pages.size <= WHOLE_BOOK_MAX_PAGES) {
                Selection.Pages(pages.map { it.pageNum })
            } else {
                Selection.TooMany(pages.size)
            }
        }
        val picked = pages.filter { p ->
            !p.tocLike && (
                p.rawChars < MIN_CHARS ||
                    p.puaCount >= MIN_PUA ||
                    (p.lineCount >= MIN_LINES && p.shortLineCount >= MIN_LINES &&
                        p.shortLineCount.toFloat() / p.lineCount >= MIN_SHORT_RATIO)
                )
        }.map { it.pageNum }
        return when {
            picked.isEmpty() -> Selection.None
            picked.size > NORMAL_MAX_PAGES -> Selection.TooMany(picked.size)
            else -> Selection.Pages(picked)
        }
    }
}
