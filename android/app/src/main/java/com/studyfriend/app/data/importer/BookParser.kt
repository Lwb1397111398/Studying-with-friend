package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues

/** 解析产物：段落带角色（BODY/FRONT/BACK/TOC），映射到 ParagraphEntity 在 VM 侧完成 */
data class ParsedPara(val text: String, val role: String)

data class ParsedChapter(
    val title: String,
    val paras: List<ParsedPara>,
    /** 无标题兜底（盲切/整书单章）时为 true，UI 据此提示 */
    val blindCut: Boolean = false,
)

/** 自定义识别规则一行标题都没匹配到时抛出 */
class CustomRegexNoMatchException(message: String) : Exception(message)

/** 纯 Kotlin 书籍解析：无 Android 依赖，全量可单测。规则见 docs/plans/M2-导入与解析.md §2 */
object BookParser {

    private const val GRADE_A = 1
    private const val GRADE_B = 2
    private const val GRADE_C = 3

    // A 档（章级）
    private val RE_A_HAN = Regex("^第[一二三四五六七八九十百千0-9零〇两]+[章单元篇部卷回]")
    private val RE_A_LATIN = Regex("^(Chapter|Unit)\\s+\\d+", RegexOption.IGNORE_CASE)
    private val FIXED_WORDS = setOf("前言", "序言", "引言", "目录", "附录", "后记", "参考文献")

    // B 档（节级）
    private val RE_B = Regex("^第[一二三四五六七八九十百千0-9零〇两]+[节讲]")

    // C 档（小样式，序号类一律行首锚定）
    private val RE_C_HAN = Regex("^[一二三四五六七八九十]+[、.，]")
    private val RE_C_PAREN = Regex("^（[一二三四五六七八九十]+）")
    private val RE_C_NUM = Regex("^\\d{1,3}(\\.\\d{1,3})+[、.．]?\\s*\\S")
    private val RE_C_NUM1 = Regex("^\\d{1,3}[、.．]\\s*\\S")

    // 标题不以句读标点结尾（护栏）
    private val END_PUNCT = "。？！，、；,.?!;"

    // 目录条目形态：点线/省略号 + 页码结尾（"第一章 导论……1"），不是标题。
    // 点线字符族含 … · . 以及 PDF 提取常见的实心圆点 •(U+2022)/‧(U+2027)（OPT-C C2）
    private val RE_TOC_LINE = Regex("[…·.•‧]{2,}\\s*\\d+\\s*$")

    // 页眉护栏（OPT-C C2）：行尾"CJK 字 + 页码"（如页眉"第一章 私法绪论 3"）不是标题。
    // 只拦内置 A/B 档命中（C 档样式数字内嵌不受影响；custom 路径 = 用户手工重切，不拦）
    private val RE_TRAIL_PAGE = Regex("[\\u4e00-\\u9FFF]\\s*\\d{1,4}\\s*$")

    private const val TITLE_MAX_LEN = 40
    private const val BLIND_CUT_THRESHOLD = 50_000
    private const val BLIND_CUT_CHAPTER_CHARS = 3_000

    // TOC 区域识别（OPT-C C3）：孤页码行与标题样式行
    private val RE_PAGE_ONLY = Regex("\\d{1,4}")
    private val RE_HEADINGISH = Regex("^第[一二三四五六七八九十百千0-9零〇两]+[章单元篇部卷回节讲]")

    /** 密度兜底：块内点线/孤页码行达到该数量才无锚点划区 */
    private const val TOC_FALLBACK_MIN_DOTS = 3

    /** 无标题且不超阈值时整书单章的章名 */
    const val WHOLE_BOOK_TITLE = "全文"

    /**
     * TOC 连续区（块下标闭区间）。锚点路径记录锚点行坐标（区内唯一可产标题命中的行）；
     * anchorLine = -1 表示无锚点、按密度兜底划区。
     */
    private class TocRegion(val start: Int, val end: Int, val anchorBlock: Int, val anchorLine: Int) {
        operator fun contains(bi: Int): Boolean = bi in start..end
    }

    private class TitleHit(val blockIdx: Int, val lineIdx: Int, val grade: Int, val style: String, val title: String)

    /**
     * 解析全书。[customTitleRegex] 非 null 时替换内置标题正则族（目录重切），
     * 非法正则抛 [java.util.regex.PatternSyntaxException] 由调用方转为友好提示。
     */
    fun parse(raw: String, customTitleRegex: String? = null): List<ParsedChapter> {
        val custom = customTitleRegex?.let { Regex(it) }
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = splitBlocks(text)
        val tocRegion = detectTocRegion(blocks, custom)
        val hits = findTitleHits(blocks, custom, tocRegion)
        if (hits.isEmpty()) {
            // §2.3：自定义规则零命中要明确报错，而不是静默滑进盲切兜底
            if (custom != null) {
                throw CustomRegexNoMatchException("识别规则没有命中任何标题行，请检查正则")
            }
            return blindChapters(blocks)
        }

        // 档位偏序 A≥B≥C：1 级 = 出现的最高档位；仅 C 时首个命中的 C 样式为 1 级
        val topGrade = hits.minOf { it.grade }
        val topHits = if (topGrade == GRADE_C) {
            val firstStyle = hits.first { it.grade == GRADE_C }.style
            hits.filter { it.style == firstStyle }
        } else {
            hits.filter { it.grade == topGrade }
        }
        val hitByBlock = topHits.associateBy { it.blockIdx }

        val chapters = mutableListOf<ParsedChapter>()
        var curTitle = "开篇"
        var curRole = DbValues.ROLE_BODY
        var curParas = mutableListOf<ParsedPara>()

        fun flush() {
            if (curParas.isNotEmpty()) chapters.add(ParsedChapter(curTitle, curParas.toList()))
            curParas = mutableListOf()
        }

        // 密度兜底区（无锚点）的条目：顺延并入下一命中章的段落头部，绝不落入"开篇"章
        val pendingToc = mutableListOf<ParsedPara>()

        for ((bi, lines) in blocks.withIndex()) {
            val hit = hitByBlock[bi]
            when {
                hit != null -> {
                    flush()
                    curTitle = hit.title
                    curRole = roleFor(hit.title)
                    if (pendingToc.isNotEmpty()) {
                        curParas.addAll(pendingToc)
                        pendingToc.clear()
                    }
                    if (tocRegion != null && bi in tocRegion) {
                        // 锚点章（"目录"）：区内条目重组为 ROLE_TOC 段，锚点行本身已作为章标题
                        curParas.addAll(tocEntries(lines, skipLine = hit.lineIdx))
                    } else {
                        for (para in parasOf(lines.filterIndexed { li, _ -> li != hit.lineIdx }, curRole)) {
                            curParas.add(para)
                        }
                    }
                }
                tocRegion != null && bi in tocRegion -> {
                    if (tocRegion.anchorLine >= 0) {
                        curParas.addAll(tocEntries(lines))
                    } else {
                        pendingToc.addAll(tocEntries(lines))
                    }
                }
                else -> {
                    for (para in parasOf(lines, curRole)) curParas.add(para)
                }
            }
        }
        if (pendingToc.isNotEmpty()) {
            // 兜底区之后没有更多命中章：条目并入最后一章尾部，避免丢内容
            curParas.addAll(pendingToc)
        }
        flush()
        return chapters
    }

    // ---- TOC 区域识别（OPT-C C3） ----

    /** 块级 TOC 判定：≥1 条点线条目，且 点线/孤页码/标题样 行占比 ≥ 0.5 */
    private fun isTocBlock(lines: List<String>): Boolean {
        var dot = 0
        var strong = 0
        var nonEmpty = 0
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            nonEmpty++
            val dotLike = RE_TOC_LINE.containsMatchIn(t) || RE_PAGE_ONLY.matches(t)
            if (dotLike) dot++
            if (dotLike || RE_HEADINGISH.containsMatchIn(t) || t in FIXED_WORDS) strong++
        }
        return dot >= 1 && nonEmpty > 0 && strong.toDouble() / nonEmpty >= 0.5
    }

    /** 从锚点行（目录/目次/Contents）或密度兜底起划区，连续 isTocBlock 块并入 */
    private fun detectTocRegion(blocks: List<List<String>>, custom: Regex?): TocRegion? {
        if (custom != null) return null // 目录重切由用户手工兜底，不再自动划区
        blocks.forEachIndexed { bi, lines ->
            lines.forEachIndexed { li, raw ->
                val t = raw.trim()
                if (t == "目录" || t == "目次" || t.equals("Contents", ignoreCase = true)) {
                    return TocRegion(bi, extendRegion(blocks, bi), bi, li)
                }
            }
        }
        for ((bi, lines) in blocks.withIndex()) {
            val dots = lines.count {
                RE_TOC_LINE.containsMatchIn(it.trim()) || RE_PAGE_ONLY.matches(it.trim())
            }
            if (isTocBlock(lines) && dots >= TOC_FALLBACK_MIN_DOTS) {
                return TocRegion(bi, extendRegion(blocks, bi), bi, -1)
            }
        }
        return null
    }

    private fun extendRegion(blocks: List<List<String>>, start: Int): Int {
        var end = start
        while (end + 1 < blocks.size && isTocBlock(blocks[end + 1])) end++
        return end
    }

    /**
     * 区内条目重组：累积行直到点线/孤页码触发行，触发行并入当前累积后成段
     * （跨行条目如"第一节\n请求权……107"并回一段），一律 ROLE_TOC。
     */
    private fun tocEntries(lines: List<String>, skipLine: Int = -1): List<ParsedPara> {
        val out = mutableListOf<ParsedPara>()
        val acc = mutableListOf<String>()
        fun flushAcc() {
            if (acc.isNotEmpty()) {
                val merged = softMerge(acc)
                if (merged.isNotBlank()) out.add(ParsedPara(merged, DbValues.ROLE_TOC))
                acc.clear()
            }
        }
        for ((li, raw) in lines.withIndex()) {
            if (li == skipLine) continue
            val t = raw.trim()
            if (t.isEmpty()) continue
            acc.add(t)
            if (RE_TOC_LINE.containsMatchIn(t) || RE_PAGE_ONLY.matches(t)) flushAcc()
        }
        flushAcc()
        return out
    }

    // ---- 标题判定 ----

    private fun findTitleHits(blocks: List<List<String>>, custom: Regex?, tocRegion: TocRegion?): List<TitleHit> {
        val hits = mutableListOf<TitleHit>()
        blocks.forEachIndexed { bi, lines ->
            for ((li, line) in lines.withIndex()) {
                // TOC 区内不产标题命中，仅锚点行本身例外（"目录"成章）
                if (tocRegion != null && bi in tocRegion && !(bi == tocRegion.anchorBlock && li == tocRegion.anchorLine)) {
                    continue
                }
                val t = line.trim()
                if (!isTitleCandidate(t)) continue
                val hit = matchTitle(t, custom) ?: continue
                hits.add(TitleHit(bi, li, hit.first, hit.second, t))
                break // 一个块最多一个标题
            }
        }
        return hits
    }

    /** 护栏：非空 + 行长 ≤40 + 不以句读标点结尾 + 非目录条目（点线页码） */
    private fun isTitleCandidate(t: String): Boolean =
        t.isNotEmpty() && t.length <= TITLE_MAX_LEN && t.last() !in END_PUNCT &&
            !RE_TOC_LINE.containsMatchIn(t)

    /** 返回 (档位, 样式名)；正则行首锚定，匹配即标题。custom 非空时替换内置正则族 */
    private fun matchTitle(t: String, custom: Regex?): Pair<Int, String>? {
        val hit = when {
            custom != null -> if (custom.containsMatchIn(t)) GRADE_A to "CUSTOM" else null
            RE_A_HAN.containsMatchIn(t) -> GRADE_A to "A_HAN"
            RE_A_LATIN.containsMatchIn(t) -> GRADE_A to "A_LATIN"
            t in FIXED_WORDS -> GRADE_A to "A_FIXED"
            RE_B.containsMatchIn(t) -> GRADE_B to "B"
            RE_C_HAN.containsMatchIn(t) -> GRADE_C to "C_HAN"
            RE_C_PAREN.containsMatchIn(t) -> GRADE_C to "C_PAREN"
            RE_C_NUM.containsMatchIn(t) -> GRADE_C to "C_NUM"
            RE_C_NUM1.containsMatchIn(t) -> GRADE_C to "C_NUM1"
            else -> null
        } ?: return null
        // 页眉护栏：内置 A/B 档命中但行尾是"CJK+页码"（PDF 页眉"第一章 私法绪论 3"）→ 降为正文。
        // 英文标题（Chapter 12 Something）不以 CJK 结尾，不受影响；C 档数字内嵌样式不受影响
        if (hit.second != "CUSTOM" && hit.first <= GRADE_B && RE_TRAIL_PAGE.containsMatchIn(t)) return null
        return hit
    }

    private fun roleFor(title: String): String = when (title) {
        "目录" -> DbValues.ROLE_FRONT
        "参考文献", "附录", "后记" -> DbValues.ROLE_BACK
        else -> DbValues.ROLE_BODY
    }

    // ---- 块与段落 ----

    /** 按空行分块（空行 = isBlank，含全角空格行）；返回每块的物理行列表 */
    private fun splitBlocks(text: String): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        for (line in text.split('\n')) {
            if (line.isBlank()) {
                if (cur.isNotEmpty()) {
                    blocks.add(cur)
                    cur = mutableListOf()
                }
            } else {
                cur.add(line)
            }
        }
        if (cur.isNotEmpty()) blocks.add(cur)
        return blocks
    }

    /** 块内行软合并成段（标题判定已先行完成，此处不再产生标题） */
    private fun parasOf(lines: List<String>, role: String): List<ParsedPara> {
        val merged = softMerge(lines)
        return if (merged.isBlank()) emptyList() else listOf(ParsedPara(merged, role))
    }

    /** 中文直接拼接；两侧都非 CJK 时补空格（英文单词边界） */
    internal fun softMerge(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty()) {
                val prev = sb.last()
                if (!isCjkLike(prev) && !isCjkLike(t.first())) sb.append(' ')
            }
            sb.append(t)
        }
        return sb.toString()
    }

    private fun isCjkLike(c: Char): Boolean =
        c in '\u3000'..'\u9FFF' || c in '\uFF00'..'\uFFEF'

    // ---- 兜底：无标题整书 / 超长盲切 ----

    private fun blindChapters(blocks: List<List<String>>): List<ParsedChapter> {
        val paras = blocks.map { softMerge(it) }.filter { it.isNotBlank() }
        val total = paras.sumOf { it.length }
        if (total <= BLIND_CUT_THRESHOLD) {
            return listOf(
                ParsedChapter(WHOLE_BOOK_TITLE, paras.map { ParsedPara(it, DbValues.ROLE_BODY) }, blindCut = true),
            )
        }
        val chapters = mutableListOf<ParsedChapter>()
        var acc = mutableListOf<ParsedPara>()
        var accLen = 0
        var n = 0
        for (p in paras) {
            acc.add(ParsedPara(p, DbValues.ROLE_BODY))
            accLen += p.length
            if (accLen >= BLIND_CUT_CHAPTER_CHARS) {
                n++
                chapters.add(ParsedChapter("第 $n 部分", acc.toList(), blindCut = true))
                acc = mutableListOf()
                accLen = 0
            }
        }
        if (acc.isNotEmpty()) {
            n++
            chapters.add(ParsedChapter("第 $n 部分", acc.toList(), blindCut = true))
        }
        return chapters
    }
}
