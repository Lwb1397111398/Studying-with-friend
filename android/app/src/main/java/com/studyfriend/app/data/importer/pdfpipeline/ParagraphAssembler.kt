package com.studyfriend.app.data.importer.pdfpipeline

import kotlin.math.max

/**
 * 行→段落组装（OPT-E）。输入是清洗后的正文行（页眉/页码/脚注已摘除），
 * 按"字号、行距、缩进、句末标点"四类信号决定断段还是续接：
 * 1. 段首禁则标点：无条件并回（段落被误切的强信号）
 * 2. 标题行（字号 ≥ 正文×1.15）：独立成段；连续大字行且垂直间距紧 → 标题自身折行合并
 * 3. 标题后的正文：先把标题 flush 隔离
 * 4. y 回跳（dy < −1.5×字号）：新栏/页内结构变化
 * 5. 行距超过 pitchThreshold：空隙即分段（有行距信号时最可靠）
 * 6. 首行缩进（x0 ≥ 左边距+0.8×字号）且上一行句末
 * 7. 短行（x1 < 右边距−1.5×字号）且上一行句末
 * 8. 无行距信号（pitchThreshold==null）时句末即断（兜底臂）
 * 9. 默认续接
 */
object ParagraphAssembler {

    private const val TITLE_FACTOR = 1.15f
    private const val TITLE_FOLD_FACTOR = 2.2f
    private const val Y_JUMP_FACTOR = 1.5f
    private const val INDENT_FACTOR = 0.8f
    private const val SHORT_LINE_FACTOR = 1.5f

    /** 段首禁则标点：这些字符起头的行必须并回上一段 */
    private val LEADING_NO_BREAK = setOf('，', '。', '、', '；', '：', '）', '」', '』', '”', '！', '？', '…')

    fun assemble(lines: List<PLine>, stats: DocStats): List<Para> {
        val paras = mutableListOf<Para>()
        val cur = StringBuilder()
        var prev: PLine? = null

        fun flush() {
            val t = cur.toString().trim()
            if (t.isNotEmpty()) paras.add(Para(t))
            cur.setLength(0)
        }

        fun appendJoined(text: String) {
            if (cur.isEmpty()) {
                cur.append(text)
                return
            }
            val first = text.firstOrNull()
            val joiner = if (first != null && isCjkLike(cur.last()) && isCjkLike(first)) "" else " "
            cur.append(joiner).append(text)
        }

        for (line in lines) {
            val p = prev
            if (p == null) {
                cur.append(line.text)
                prev = line
                continue
            }
            val dy = if (p.y0 >= 0f && line.y0 >= 0f) line.y0 - p.y0 else Float.NaN
            val first = line.text.firstOrNull()
            val isTitle = line.size >= stats.bodySize * TITLE_FACTOR
            val prevTitle = p.size >= stats.bodySize * TITLE_FACTOR
            when {
                // 1. 段首禁则标点：无条件并回
                first != null && first in LEADING_NO_BREAK -> appendJoined(line.text)
                // 2. 标题行：折行合并或独立成段
                isTitle -> {
                    if (prevTitle && !dy.isNaN() && dy >= 0f && dy <= TITLE_FOLD_FACTOR * p.size) {
                        appendJoined(line.text) // 标题自身折行
                    } else {
                        flush()
                        cur.append(line.text)
                    }
                }
                // 3. 标题后的正文：标题独立成段
                prevTitle -> {
                    flush()
                    cur.append(line.text)
                }
                // 4. y 回跳
                !dy.isNaN() && dy < -Y_JUMP_FACTOR * max(p.size, line.size) -> {
                    flush()
                    cur.append(line.text)
                }
                // 5. 行距超过段落阈值
                !dy.isNaN() && stats.pitchThreshold != null && dy > stats.pitchThreshold -> {
                    flush()
                    cur.append(line.text)
                }
                // 6. 首行缩进 + 上一行句末
                line.x0 >= 0f && line.x0 >= stats.left + INDENT_FACTOR * stats.bodySize &&
                    endsSentence(p.text) -> {
                    flush()
                    cur.append(line.text)
                }
                // 7. 短行 + 上一行句末
                line.x0 >= 0f && line.x1 < stats.right - SHORT_LINE_FACTOR * line.size &&
                    endsSentence(p.text) -> {
                    flush()
                    cur.append(line.text)
                }
                // 8. 无行距信号兜底：句末即断
                stats.pitchThreshold == null && endsSentence(p.text) -> {
                    flush()
                    cur.append(line.text)
                }
                // 9. 默认续接
                else -> appendJoined(line.text)
            }
            prev = line
        }
        flush()
        return paras
    }
}

/** 段末判定：中英句末标点、引号书名号收尾、圈码序号（①–⑳）、引注 [12]/〔3〕 收尾 */
fun endsSentence(t: String): Boolean {
    val s = t.trimEnd()
    if (s.isEmpty()) return false
    val last = s.last()
    if (last in "。！？；：！?" || last == '…' || last == '”' || last == '』' || last == '」') return true
    if (last.code in 0x2460..0x2473) return true
    return RE_CITATION_END.containsMatchIn(s.takeLast(5))
}

private val RE_CITATION_END = Regex("[\\[〔]\\d{1,3}[\\]〕]$")

/** 段落拼接：CJK 直连，非 CJK 之间补空格（保 ASCII 单词不被粘死） */
fun softJoin(a: String, b: String): String {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val joiner = if (isCjkLike(a.last()) && isCjkLike(b.first())) "" else " "
    return a + joiner + b
}

fun joinTexts(texts: List<String>): String = texts.fold("") { acc, t -> softJoin(acc, t) }

private fun isCjkLike(c: Char): Boolean = c.code in 0x3000..0x9FFF || c.code in 0xFF00..0xFFEF
