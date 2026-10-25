package com.studyfriend.app.ui.screens

/**
 * 划词边界纯函数（阅读页长按随手问）：长按正文取「词」，再按需扩展到整句/整段。
 * 词界自实现而非 TextLayoutResult.getWordBoundary：纯函数可单测，行为对中文可控——
 * 中文无空格分词，ICU 词界结果不可预期，这里定为「ASCII 连续段成一个词、汉字单字一词」。
 */
object SelectionBounds {

    /** 句末标点：向后扫描遇到即收句；'…' 是 U+2026 半个省略号，连续两个自然都在集内 */
    private val SENTENCE_ENDERS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '…', '：', ':')

    /** 右引号/右括号：紧跟句末标点时并入本句尾，出现在句首方向时归前一句 */
    private val CLOSERS = charArrayOf(
        '”', '’', '」', '』', '）', ')', ']', '】', '》', '〗', '"', '\'',
    )

    /**
     * 句首方向要跳过的右性标点（归前一句）：只收方向明确的中文右引号/右括号。
     * 英文直引号 " ' 开关两用，放在句首更可能是开引号，跳错会把引语开头切掉。
     */
    private val SKIP_AT_SENTENCE_HEAD = charArrayOf(
        '”', '’', '」', '』', '）', ')', ']', '】', '》', '〗',
    )

    /** 长按点取词：ASCII 字母数字（含下划线）连续段成一个词，汉字/标点/空白取单字符 */
    fun wordAt(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val n = text.length
        val o = offset.coerceIn(0, n - 1)
        if (!isAsciiWord(text[o])) return o..o
        var s = o
        var e = o
        while (s > 0 && isAsciiWord(text[s - 1])) s--
        while (e < n - 1 && isAsciiWord(text[e + 1])) e++
        return s..e
    }

    /**
     * 长按点取「词组」（划词默认整句前的定位）：汉字连续段（到标点/空白/非汉字停）
     * 成一个词组，ASCII 段沿用 wordAt，标点/空白取单字符。中文没有空格分词、词典
     * 分词不可依赖，词组只作扩句定位与把手拖拽的起点，精确范围交给把手与扩句/扩段。
     */
    fun phraseAt(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val n = text.length
        val o = offset.coerceIn(0, n - 1)
        if (isAsciiWord(text[o])) return wordAt(text, o)
        if (!isCjk(text[o])) return o..o
        var s = o
        var e = o
        while (s > 0 && isCjk(text[s - 1])) s--
        while (e < n - 1 && isCjk(text[e + 1])) e++
        return s..e
    }

    /** 汉字（CJK 统一表意文字基本区 + 扩展 A）：中文书正文主体字符 */
    private fun isCjk(c: Char): Boolean = c in '\u3400'..'\u9FFF'

    /**
     * 把选中范围扩展到所在整句（闭区间）：从选中尾部向后找最近句末标点（吸收其后的
     * 连续右引号），从选中头部向前找最近句末标点/换行（跳过其前应归前一句的右引号）；
     * 段内没有句末标点时扩到整段（换行收句，不跨段）。
     */
    fun expandToSentence(text: String, wordStart: Int, wordEndInclusive: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val n = text.length
        val ws = wordStart.coerceIn(0, n - 1)
        val we = wordEndInclusive.coerceIn(ws, n - 1)

        // 句尾：选中本身落在句末标点上时直接收在该标点（含其后的右引号）
        val end: Int
        if (text[ws] in SENTENCE_ENDERS) {
            var j = ws + 1
            while (j < n && text[j] in CLOSERS) j++
            end = j - 1
        } else {
            var i = we + 1
            var last = we
            while (i < n && text[i] != '\n') {
                last = i
                if (text[i] in SENTENCE_ENDERS) {
                    var j = i + 1
                    while (j < n && text[j] in CLOSERS) j++
                    last = j - 1
                    break
                }
                i++
            }
            end = last
        }

        // 句首：向前找最近句末标点/换行；标点与选中之间的右引号属于前一句，跳过
        var start = 0
        var k = ws - 1
        while (k >= 0) {
            val c = text[k]
            if (c == '\n') {
                start = k + 1
                break
            }
            if (c in SENTENCE_ENDERS) {
                start = k + 1
                while (start < ws && text[start] in SKIP_AT_SENTENCE_HEAD) start++
                break
            }
            k--
        }
        return start..end
    }

    private fun isAsciiWord(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
}
