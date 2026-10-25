package com.studyfriend.app.ui

import com.studyfriend.app.ui.screens.SelectionBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长按随手问划词边界：中文单字词 / ASCII 连续词 / 句扩展的标点收束 */
class SelectionBoundsTest {

    // ---- wordAt ----

    @Test
    fun wordAt_cjkIsSingleChar() {
        // 中文无空格分词：每个汉字自成一词，不把整串连成一个「词」
        assertEquals(2..2, SelectionBounds.wordAt("今天天气好", 2))
    }

    @Test
    fun wordAt_asciiConsecutive() {
        assertEquals(0..4, SelectionBounds.wordAt("hello world", 3))
        assertEquals(6..10, SelectionBounds.wordAt("hello world", 10))
    }

    @Test
    fun wordAt_digitsInsideCjk() {
        // ASCII 数字段在汉字中间也连成一个词
        assertEquals(3..6, SelectionBounds.wordAt("民法典2026条", 5))
    }

    @Test
    fun wordAt_punctuationIsSingleChar() {
        assertEquals(1..1, SelectionBounds.wordAt("好。走", 1))
    }

    @Test
    fun wordAt_offsetClamped() {
        // "abc" 整串是一个连续词：尾越界与首越界都 coerce 后扩出全词
        assertEquals(0..2, SelectionBounds.wordAt("abc", 99))
        assertEquals(0..2, SelectionBounds.wordAt("abc", -5))
    }

    @Test
    fun wordAt_emptyText() {
        assertTrue(SelectionBounds.wordAt("", 0).isEmpty())
    }

    // ---- expandToSentence ----

    @Test
    fun sentence_basic() {
        val t = "第一句。第二句呢？第三句！"
        // 「呢」在 idx=7，句应扩到 4..8「第二句呢？」
        assertEquals(4..8, SelectionBounds.expandToSentence(t, 7, 7))
    }

    @Test
    fun sentence_closesAfterEnder() {
        // 句末标点后的右引号并入本句：她说："走吧。"然后走了
        val t = "她说：\"走吧。\"然后走了"
        // 「走」在 idx=4 → 句 3..7 = "走吧。" 含两侧引号
        assertEquals(3..7, SelectionBounds.expandToSentence(t, 4, 4))
    }

    @Test
    fun sentence_noEnderExpandsToWholeParagraph() {
        val t = "没有句末标点的段落"
        assertEquals(0..t.length - 1, SelectionBounds.expandToSentence(t, 3, 3))
    }

    @Test
    fun sentence_newlineStopsSentence() {
        val t = "第一行\n第二行呢"
        // 「二」idx=5：向前遇 \n，向后到段末
        assertEquals(4..7, SelectionBounds.expandToSentence(t, 5, 5))
    }

    @Test
    fun sentence_closerBeforeSelectionBelongsToPrevSentence() {
        // 选中之前的右弯引号属于前一句，句首跳过它：完。”走了
        val t = "完。”走了"
        // 「走」idx=3，句首应越过 idx=2 的右弯引号；句尾无标点收到段末
        assertEquals(3..4, SelectionBounds.expandToSentence(t, 3, 3))
    }

    @Test
    fun sentence_onEnderChar() {
        // 长按正好按在句末标点上：句收在该标点（含其后右引号）
        assertEquals(0..1, SelectionBounds.expandToSentence("好。走", 1, 1))
    }

    @Test
    fun sentence_colonOpensQuote() {
        // 全角冒号视为句界（引语前常见）：他说：走
        val t = "他说：走吧"
        assertEquals(3..4, SelectionBounds.expandToSentence(t, 4, 4))
    }

    @Test
    fun sentence_wordRangeRespected() {
        // 传入的是一个 ASCII 词的范围（start≠end），扩展仍按句走
        val t = "说 hello。下一句"
        // 词 hello = 2..6，句首无界标点扩到文首，句尾收在句号
        assertEquals(0..7, SelectionBounds.expandToSentence(t, 2, 6))
    }

    @Test
    fun sentence_offsetClampedAndEmpty() {
        assertTrue(SelectionBounds.expandToSentence("", 0, 0).isEmpty())
        // 越界不崩，coerce 到合法范围
        assertEquals(0..2, SelectionBounds.expandToSentence("abc", 10, 20))
    }

    // ---- phraseAt（划词扩选轮：长按默认选句前的词组定位） ----

    @Test
    fun phraseAt_cjkRunIsOnePhrase() {
        // 汉字连续段成一个词组，标点/空白是天然边界
        assertEquals(0..1, SelectionBounds.phraseAt("你好。世界", 1))
        assertEquals(3..4, SelectionBounds.phraseAt("你好。世界", 4))
        assertEquals(0..1, SelectionBounds.phraseAt("你好 世界", 1))
    }

    @Test
    fun phraseAt_asciiReusesWordAt() {
        assertEquals(0..4, SelectionBounds.phraseAt("hello 世界", 3))
        assertEquals(3..6, SelectionBounds.phraseAt("民法典2026条", 5))
    }

    @Test
    fun phraseAt_cjkAndDigitsAdjacentDoNotMerge() {
        // 汉字段与数字段相邻但互不吞并：民法典=0..2，2026=3..6，条=7
        assertEquals(0..2, SelectionBounds.phraseAt("民法典2026条", 1))
        assertEquals(3..6, SelectionBounds.phraseAt("民法典2026条", 5))
        assertEquals(7..7, SelectionBounds.phraseAt("民法典2026条", 7))
    }

    @Test
    fun phraseAt_punctuationAndSpaceSingleChar() {
        assertEquals(1..1, SelectionBounds.phraseAt("好。走", 1))  // 「。」标点本身取单字
        assertEquals(2..2, SelectionBounds.phraseAt("你 好", 2))  // 「好」左邻空格，单字词组
    }

    @Test
    fun phraseAt_clampedAndEmpty() {
        assertTrue(SelectionBounds.phraseAt("", 0).isEmpty())
        assertEquals(0..2, SelectionBounds.phraseAt("你好走", 99))
    }

    @Test
    fun longPress_phraseThenSentence() {
        // 长按链路：词组定位 → 扩到整句。按在「搭」上应选中整句「合同是保理合同。」
        val t = "他说了句话。合同是保理合同。所谓保理，指……"
        val phrase = SelectionBounds.phraseAt(t, 11)
        val sentence = SelectionBounds.expandToSentence(t, phrase.first, phrase.last)
        assertEquals("合同是保理合同。", t.substring(sentence.first, sentence.last + 1))
    }
}
