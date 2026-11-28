package com.studyfriend.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** AI 运行日志中枢（2026-11-28）：状态流转、统计口径、截尾与上限 */
class AiLogBusTest {

    @Before
    fun reset() = AiLogBus.clear()

    @Test
    fun lifecycle_queuedStreamingDone() {
        val id = AiLogBus.begin("粗读", "test-model")
        assertEquals(AiLogState.QUEUED, AiLogBus.snapshots.value[0].state)
        AiLogBus.streaming(id)
        assertEquals(AiLogState.STREAMING, AiLogBus.snapshots.value[0].state)
        AiLogBus.delta(id, "你")
        AiLogBus.delta(id, "好")
        AiLogBus.finish(id)

        val s = AiLogBus.snapshots.value[0]
        assertEquals(AiLogState.DONE, s.state)
        assertEquals(2, s.chars)
        assertEquals("你好", s.text)
        assertEquals("粗读", s.source)
        assertNotNull(s.connectMs)
        assertNotNull(s.firstDeltaMs)
        assertNotNull(s.endMs)
        assertNull(s.error)
    }

    @Test
    fun cancel_marksCancelledWithPartialChars() {
        val id = AiLogBus.begin("随手问", "m")
        AiLogBus.delta(id, "半截")
        AiLogBus.cancel(id)
        val s = AiLogBus.snapshots.value[0]
        assertEquals(AiLogState.CANCELLED, s.state)
        assertEquals(2, s.chars)
        assertNotNull(s.endMs)
    }

    @Test
    fun finishWithError_recordsHumanMessage() {
        val id = AiLogBus.begin("章末总结", "m")
        AiLogBus.finish(id, error = "请求太频繁或额度用尽")
        val s = AiLogBus.snapshots.value[0]
        assertEquals(AiLogState.ERROR, s.state)
        assertEquals("请求太频繁或额度用尽", s.error)
    }

    @Test
    fun finishIsIdempotent_lateErrorCannotOverwriteDone() {
        val id = AiLogBus.begin("段落讲解", "m")
        AiLogBus.finish(id)
        AiLogBus.finish(id, error = "迟到的错误")
        val s = AiLogBus.snapshots.value[0]
        assertEquals(AiLogState.DONE, s.state)
        assertNull(s.error)
    }

    @Test
    fun blankSource_fallsBackToModelName() {
        AiLogBus.begin("", "gpt-x")
        assertEquals("gpt-x", AiLogBus.snapshots.value[0].source)
    }

    @Test
    fun textTail_truncatesKeepingTailWithEllipsis() {
        val id = AiLogBus.begin("粗读", "m")
        val long = "字".repeat(AiLogBus.TEXT_TAIL_MAX + 500)
        AiLogBus.delta(id, long)
        val s = AiLogBus.snapshots.value[0]
        // 字符统计是全量口径，不受截尾影响
        assertEquals(AiLogBus.TEXT_TAIL_MAX + 500, s.chars)
        assertEquals(AiLogBus.TEXT_TAIL_MAX + 1, s.text.length)
        assertTrue(s.text.startsWith("…"))
    }

    @Test
    fun sessionsCap_keepsNewestFifty() {
        repeat(AiLogBus.SESSIONS_MAX + 5) { AiLogBus.begin("任务$it", "m") }
        val all = AiLogBus.snapshots.value
        assertEquals(AiLogBus.SESSIONS_MAX, all.size)
        // index 0 = 最新；最旧的 5 条（任务0~4）被裁掉
        assertEquals("任务${AiLogBus.SESSIONS_MAX + 4}", all[0].source)
        assertEquals("任务5", all.last().source)
    }

    @Test
    fun unknownId_isIgnoredSilently() {
        AiLogBus.delta(999L, "x")
        AiLogBus.finish(999L)
        AiLogBus.cancel(999L)
        assertTrue(AiLogBus.snapshots.value.isEmpty())
    }

    // ---- think 埋点（2026-11-28 老板诉求「日志里也想看思考」） ----

    @Test
    fun think_accumulates_textAndCharsStayBodyOnly() {
        val id = AiLogBus.begin("随手问", "m")
        AiLogBus.think(id, "思考一")
        AiLogBus.think(id, "思考二")
        AiLogBus.delta(id, "正文")
        AiLogBus.finish(id)

        val s = AiLogBus.snapshots.value[0]
        assertEquals(AiLogState.DONE, s.state)
        // 思考独立累积，正文口径（text/chars）不混思考
        assertEquals("思考一思考二", s.thinking)
        assertEquals("正文", s.text)
        assertEquals(2, s.chars)
        assertNotNull(s.thinkFirstMs)
        assertNotNull(s.firstDeltaMs)
        // 正文首字晚于思考首段（同毫秒也满足 <=；上面已断言非空，!! 安全）
        assertTrue(s.thinkFirstMs!! <= s.firstDeltaMs!!)
    }

    @Test
    fun think_truncatesTailLikeBody() {
        val id = AiLogBus.begin("随手问", "m")
        AiLogBus.think(id, "想".repeat(AiLogBus.TEXT_TAIL_MAX + 500))
        val s = AiLogBus.snapshots.value[0]
        assertEquals(AiLogBus.TEXT_TAIL_MAX + 1, s.thinking.length)
        assertTrue(s.thinking.startsWith("…"))
        assertNotNull(s.thinkFirstMs)
    }

    @Test
    fun noThink_thinkFieldsStayNull() {
        val id = AiLogBus.begin("粗读", "m")
        AiLogBus.delta(id, "直接出正文")
        AiLogBus.finish(id)
        val s = AiLogBus.snapshots.value[0]
        assertEquals("", s.thinking)
        assertNull(s.thinkFirstMs)
    }

    @Test
    fun fmtSpeed_negativeOrZeroSpan_emDash() {
        assertEquals("—", AiLogBus.fmtSpeed(100, 0))
        assertEquals("—", AiLogBus.fmtSpeed(100, -1))
    }

    @Test
    fun fmtSpeed_positiveSpan_wordsPerSecond() {
        assertEquals("50字/秒", AiLogBus.fmtSpeed(100, 2000))
    }
}
