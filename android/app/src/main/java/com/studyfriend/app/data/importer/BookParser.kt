package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues

/** 解析产物：段落带角色（BODY/FRONT/BACK），映射到 ParagraphEntity 在 VM 侧完成 */
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

    // 目录条目形态：点线/省略号 + 页码结尾（"第一章 导论……1"），不是标题
    private val RE_TOC_LINE = Regex("[…·.]{2,}\\s*\\d+\\s*$")

    private const val TITLE_MAX_LEN = 40
    private const val BLIND_CUT_THRESHOLD = 50_000
    private const val BLIND_CUT_CHAPTER_CHARS = 3_000

    /** 无标题且不超阈值时整书单章的章名 */
    const val WHOLE_BOOK_TITLE = "全文"

    private class TitleHit(val blockIdx: Int, val lineIdx: Int, val grade: Int, val style: String, val title: String)

    /**
     * 解析全书。[customTitleRegex] 非 null 时替换内置标题正则族（目录重切），
     * 非法正则抛 [java.util.regex.PatternSyntaxException] 由调用方转为友好提示。
     */
    fun parse(raw: String, customTitleRegex: String? = null): List<ParsedChapter> {
        val custom = customTitleRegex?.let { Regex(it) }
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = splitBlocks(text)
        val hits = findTitleHits(blocks, custom)
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

        for ((bi, lines) in blocks.withIndex()) {
            val hit = hitByBlock[bi]
            if (hit != null) {
                flush()
                curTitle = hit.title
                curRole = roleFor(hit.title)
                for (para in parasOf(lines.filterIndexed { li, _ -> li != hit.lineIdx }, curRole)) {
                    curParas.add(para)
                }
            } else {
                for (para in parasOf(lines, curRole)) curParas.add(para)
            }
        }
        flush()
        return chapters
    }

    // ---- 标题判定 ----

    private fun findTitleHits(blocks: List<List<String>>, custom: Regex?): List<TitleHit> {
        val hits = mutableListOf<TitleHit>()
        blocks.forEachIndexed { bi, lines ->
            for ((li, line) in lines.withIndex()) {
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
    private fun matchTitle(t: String, custom: Regex?): Pair<Int, String>? = when {
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
