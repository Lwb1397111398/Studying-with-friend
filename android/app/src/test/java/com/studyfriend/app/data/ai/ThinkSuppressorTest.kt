package com.studyfriend.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** 推理模型 <think> 抑制器（实景体验轮 2026-11-21：MiniMax-M2.5 随手问思考段上屏实录） */
class ThinkSuppressorTest {

    private fun feedAll(vararg deltas: String): Pair<String, String> {
        val s = ThinkSuppressor()
        val visible = deltas.map { s.feed(it) }.joinToString("")
        return visible to s.finish()
    }

    @Test
    fun wholeBlockInOneDelta_suppressed() {
        val (v, f) = feedAll("答案正文")
        assertEquals("答案正文", v + f)
    }

    @Test
    fun thinkBlock_suppressed() {
        val (v, f) = feedAll("<think>", "We need answer in Chinese.", "</think>", "答案是出生两要件。")
        assertEquals("答案是出生两要件。", v + f)
    }

    @Test
    fun splitTags_acrossDeltas_neitherLeakNorEarlyShow() {
        // MiniMax 实录形态：标签被 SSE 分片切断（<th|ink>…</thi|nk>）
        val (v, f) = feedAll("<th", "ink>英文草稿", "更多思考", "</thi", "nk>", "可见正文。")
        assertEquals("可见正文。", v + f)
    }

    @Test
    fun multipleThinkBlocks_allSuppressed() {
        val (v, f) = feedAll("<think>A</think>中", "间正文<think>B</think>", "结尾")
        assertEquals("中间正文结尾", v + f)
    }

    @Test
    fun unclosedThink_nothingLeaked() {
        val (v, f) = feedAll("<think>", "思考被截断", "永无闭合")
        assertEquals("", v + f)
    }

    @Test
    fun bodyEndingWithTagPrefix_flushedAtFinish() {
        // 正文恰以 "<thi" 收尾（不是标签）：先扣住防半截上屏，finish 放行不吞正文
        val (v, f) = feedAll("答案", " <thi")
        assertEquals("答案 <thi", v + f)
    }

    @Test
    fun mathLtSurvives() {
        // 普通数学/文本里的单个 < 不受牵连
        val (v, f) = feedAll("若 a<b 则", "成立。")
        assertEquals("若 a<b 则成立。", v + f)
    }

    // ---- onThink 思考回调（2026-11-28 老板诉求「思考亮出来、思考完折叠」） ----

    @Test
    fun onThink_streamsThinkingInOrder_bodyOnlyViaFeed() {
        // 分片 think：思考增量按产出顺序进 onThink，正文只走 feed 返回值
        val thinks = mutableListOf<String>()
        val s = ThinkSuppressor { thinks.add(it) }
        val visible = buildList {
            add(s.feed("<th")); add(s.feed("ink>英文草稿")); add(s.feed("更多思考"))
            add(s.feed("</thi")); add(s.feed("nk>")); add(s.feed("可见正文。"))
        }.joinToString("")
        assertEquals("可见正文。", visible + s.finish())
        assertEquals("英文草稿更多思考", thinks.joinToString(""))
    }

    @Test
    fun onThink_unclosed_flushesThinkTailAtFinish() {
        // 思考被截断（未闭合）：finish 时剩余缓冲全部吐给 onThink，正文零泄漏
        val thinks = mutableListOf<String>()
        val s = ThinkSuppressor { thinks.add(it) }
        val visible = s.feed("<think>") + s.feed("思考被截断") + s.feed("永无闭合") + s.finish()
        assertEquals("", visible)
        assertEquals("思考被截断永无闭合", thinks.joinToString(""))
    }

    @Test
    fun onThink_multipleBlocks_eachGoesToCallback() {
        // 正文夹两段思考：各归各——思考进 onThink，正文完整不被吃
        val thinks = mutableListOf<String>()
        val s = ThinkSuppressor { thinks.add(it) }
        val visible = buildList {
            add(s.feed("<think>A</think>中")); add(s.feed("间正文<think>B</think>")); add(s.feed("结尾"))
        }.joinToString("")
        assertEquals("中间正文结尾", visible + s.finish())
        assertEquals(listOf("A", "B"), thinks)
    }
}
