package com.studyfriend.app.data.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M5 计划 §5：TtsPlayer 5 例——播放推进/prepare 失败/停止回 Idle/分句计数/合成错误依状态忽略 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsPlayerTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class FakeEngine : TtsEngine {
        /** null=可用；非 null=不可用原因（新签名 String?） */
        var prepareResult: String? = null
        /** 非 null=prepare 直接抛异常（2026-11-28 防崩加固测试用） */
        var prepareThrows: Throwable? = null
        /** 非 null=stop 直接抛异常（少数 ROM binder 死形态） */
        var stopThrows: Throwable? = null
        var prepareCalled = false
        var stopCalled = false
        var shutdownCalled = false
        var onSentenceCb: ((Int) -> Unit)? = null
        var onDoneCb: (() -> Unit)? = null
        var onErrorCb: ((String) -> Unit)? = null
        var lastSpokenText: String? = null

        override suspend fun prepare(): String? {
            prepareCalled = true
            prepareThrows?.let { throw it }
            return prepareResult
        }

        override fun speak(text: String, onSentence: (Int) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
            lastSpokenText = text
            onSentenceCb = onSentence
            onDoneCb = onDone
            onErrorCb = onError
        }

        override fun stop() {
            stopCalled = true
            stopThrows?.let { throw it }
        }

        override fun shutdown() {
            shutdownCalled = true
        }
    }

    private suspend fun awaitState(player: TtsPlayer, pred: (TtsState) -> Boolean): TtsState {
        withTimeout(5_000) {
            while (!pred(player.state.value)) delay(20)
        }
        return player.state.value
    }

    // ---------- 1. Idle → Preparing → Speaking → 播完回 Idle ----------

    @Test
    fun toggle_speaksThroughSentencesThenIdle(): Unit = runBlocking {
        val engine = FakeEngine()
        val player = TtsPlayer(engine, scope)

        player.toggle("三句话。第一句。第二句！")
        awaitState(player) { it is TtsState.Speaking }
        assertTrue(engine.prepareCalled)
        assertEquals(3, splitSentences("三句话。第一句。第二句！").size)
        engine.onSentenceCb?.invoke(1)
        assertEquals(TtsState.Speaking(1, 3), player.state.value)
        engine.onDoneCb?.invoke()
        assertEquals(TtsState.Idle, player.state.value)
    }

    // ---------- 2. prepare 失败 → Error 且不 speak ----------

    @Test
    fun prepareFalse_showsErrorWithoutSpeaking(): Unit = runBlocking {
        val engine = FakeEngine()
        engine.prepareResult = "系统朗读引擎初始化失败（-7），请到系统设置检查「文字转语音（TTS）」"
        val player = TtsPlayer(engine, scope)

        player.toggle("内容")
        val s = awaitState(player) { it is TtsState.Error } as TtsState.Error

        assertEquals(engine.prepareResult, s.message)
        assertEquals(null, engine.onSentenceCb)
    }

    // ---------- 3. 朗读中 stop → Idle 且引擎停止 ----------

    @Test
    fun stop_returnsIdleAndStopsEngine(): Unit = runBlocking {
        val engine = FakeEngine()
        val player = TtsPlayer(engine, scope)

        player.toggle("慢慢读。")
        awaitState(player) { it is TtsState.Speaking }
        player.stop()

        assertEquals(TtsState.Idle, player.state.value)
        assertTrue(engine.stopCalled)
    }

    // ---------- 4. 分句计数：标点切分 / 空白过滤 / 超长硬切 ----------

    @Test
    fun splitSentences_countsCorrectly() {
        assertEquals(3, splitSentences("第一句。第二句！第三句？").size)
        assertEquals(1, splitSentences("没有结尾标点的一段").size)
        assertEquals(0, splitSentences("   \n  ").size)
        // 4500 字连句 → 4000 + 500 两段
        assertEquals(2, splitSentences("甲".repeat(4500)).size)
        // 换行也切
        assertEquals(2, splitSentences("上行。\n下行。").size)
        assertFalse(splitSentences("上行。\n下行。")[1].startsWith("\n"))
    }

    // ---------- 5. 合成错误 → Error；停止后晚到的错误不再覆盖 ----------

    @Test
    fun synthesisError_showsErrorAndIgnoresLateStopError(): Unit = runBlocking {
        val engine = FakeEngine()
        val player = TtsPlayer(engine, scope)

        player.toggle("内容。")
        awaitState(player) { it is TtsState.Speaking }
        engine.onErrorCb?.invoke("TTS 合成失败")
        assertEquals(TtsState.Error("TTS 合成失败"), player.state.value)

        // 用户 stop 回 Idle 后，引擎晚到的停止类错误不应把 Idle 覆盖成 Error
        player.stop()
        assertEquals(TtsState.Idle, player.state.value)
        engine.onErrorCb?.invoke("stop 引发的错误")
        assertEquals("晚到错误被依状态忽略", TtsState.Idle, player.state.value)
    }

    // ---------- 6. 句→段映射（功能补齐轮正文听书） ----------

    @Test
    fun sentenceToParaIndex_mapsGlobalSentenceToParagraph() {
        // 三段句数 2/1/3（与拼接文本 splitSentences 同口径：\n 连接在换行处断开）
        val counts = listOf(2, 1, 3)
        assertEquals(0, sentenceToParaIndex(counts, 0))
        assertEquals(0, sentenceToParaIndex(counts, 1))
        assertEquals(1, sentenceToParaIndex(counts, 2))
        assertEquals(2, sentenceToParaIndex(counts, 3))
        assertEquals(2, sentenceToParaIndex(counts, 5))
        // 越界：钳到末段
        assertEquals(2, sentenceToParaIndex(counts, 99))
        // 空列表/空段计数防御：句 0 落在第一个实际有句子的段
        assertEquals(0, sentenceToParaIndex(emptyList(), 0))
        assertEquals(2, sentenceToParaIndex(listOf(0, 0, 1), 0))
    }

    @Test
    fun joinedParagraphsSplitting_staysAlignedWithPerParaCounts() {
        // 拼接口径对齐验证：\n 连接的多段整体分句数 == 各段独立分句数之和
        val paras = listOf("第一段。有两句！第二段只有一句？", "第三段。也是两句。", "无标点结尾段")
        val joined = paras.joinToString("\n") { it }
        val counts = paras.map { splitSentences(it).size }
        assertEquals(splitSentences(joined).size, counts.sum())
        // 最后一段（无标点）作为当前句时映射回段 2
        assertEquals(2, sentenceToParaIndex(counts, counts.sum() - 1))
    }

    // ---------- 7. TTS 双模式回落：本地模型不可用 → 自动回落系统语音 + notice ----------

    @Test
    fun localUnavailable_fallsBackToSystemWithNotice(): Unit = runBlocking {
        val local = FakeEngine()
        val system = FakeEngine()
        local.prepareResult = "离线朗读模型还没下载，可到设置里下载（约 215MB）"
        val player = TtsPlayer(system, scope, local)
        player.mode = TtsEngineMode.LOCAL

        player.toggle("内容。")
        val s = awaitState(player) { it is TtsState.Speaking }

        assertTrue(local.prepareCalled)
        assertTrue(system.prepareCalled)
        assertTrue(player.notice.value?.contains("离线朗读暂不可用") == true)
        assertTrue(player.notice.value?.contains("先用系统语音") == true)
        assertEquals(TtsState.Speaking(0, 1), s)
        // 回落后发声的是系统引擎：它收到 onDone 回 Idle
        system.onDoneCb?.invoke()
        assertEquals(TtsState.Idle, player.state.value)
    }

    // ---------- 8. 双模式都不可用 → 报系统引擎的错误，不入队发声 ----------

    @Test
    fun bothUnavailable_showsSystemError(): Unit = runBlocking {
        val local = FakeEngine()
        val system = FakeEngine()
        local.prepareResult = "离线朗读模型还没下载"
        system.prepareResult = "本机没有可用的系统朗读引擎"
        val player = TtsPlayer(system, scope, local)
        player.mode = TtsEngineMode.LOCAL

        player.toggle("内容")
        val s = awaitState(player) { it is TtsState.Error } as TtsState.Error

        assertEquals("本机没有可用的系统朗读引擎", s.message)
        assertFalse(local.stopCalled)
    }

    // ---------- 9. 暂停/恢复（第九波实景轮） ----------

    @Test
    fun pause_fromSpeaking_recordsNextSentenceAndStopsEngine(): Unit = runBlocking {
        val engine = FakeEngine()
        val player = TtsPlayer(engine, scope)
        player.toggle("三句话。第一句。第二句！")
        awaitState(player) { it is TtsState.Speaking }
        engine.onSentenceCb?.invoke(1) // 已播完第 1 句（全文 3 句）

        player.pause()

        val s = player.state.value
        assertTrue("暂停态", s is TtsState.Paused)
        assertEquals("下一句=第 2 句（0-based）", 2, (s as TtsState.Paused).sentenceIdx)
        assertEquals(3, s.total)
        assertTrue(engine.stopCalled)
    }

    @Test
    fun resume_continuesFromPausedSentenceWithFullIndexing(): Unit = runBlocking {
        val engine = FakeEngine()
        val player = TtsPlayer(engine, scope)
        val text = "三句话。第一句。第二句！"
        player.toggle(text)
        awaitState(player) { it is TtsState.Speaking }
        engine.onSentenceCb?.invoke(1)
        player.pause()

        player.resume()
        awaitState(player) { it is TtsState.Speaking }

        // 引擎收到的恢复文本 = 从第 2 句「第一句。」起的子文本（分句起始偏移切分）
        assertEquals(text.substring(splitSentenceStarts(text)[2]), engine.lastSpokenText)
        // 全文句号口径：子文本里推进 0 句 = 全文第 2 句
        assertEquals(TtsState.Speaking(2, 3), player.state.value)
        engine.onSentenceCb?.invoke(1)
        assertEquals(TtsState.Speaking(3, 3), player.state.value)
        engine.onDoneCb?.invoke()
        assertEquals(TtsState.Idle, player.state.value)
    }

    @Test
    fun pause_onIdle_orStopClearsPause(): Unit = runBlocking {
        val engine = FakeEngine()
        val player = TtsPlayer(engine, scope)
        // Idle 时 pause 不动
        player.pause()
        assertEquals(TtsState.Idle, player.state.value)
        // 暂停后 stop → Idle 且恢复现场清空（resume 不再动）
        player.toggle("内容。")
        awaitState(player) { it is TtsState.Speaking }
        player.pause()
        assertTrue(player.state.value is TtsState.Paused)
        player.stop()
        assertEquals(TtsState.Idle, player.state.value)
        player.resume()
        assertEquals("stop 已清暂停现场，resume 不动", TtsState.Idle, player.state.value)
    }

    // ---------- 10. 分句起始偏移与 splitSentences 对齐（resume 切分的前提） ----------

    @Test
    fun splitSentenceStarts_alignsWithSplitSentences() {
        val text = "三句话。第一句。第二句！"
        val starts = splitSentenceStarts(text)
        val pieces = splitSentences(text)
        assertEquals(pieces.size, starts.size)
        // 每个起点截到句尾应包含对应句文本（trim 后相等）
        pieces.forEachIndexed { i, piece ->
            val end = if (i + 1 < starts.size) starts[i + 1] else text.length
            assertEquals(piece, text.substring(starts[i], end).trim())
        }
        // 空白句被丢时起点列表同步剔除（「。  。」中间空白句）
        assertEquals(splitSentences("有句。   。有句！").size, splitSentenceStarts("有句。   。有句！").size)
        // 超长硬切：4500 字 → 2 块，起点数一致
        assertEquals(2, splitSentenceStarts("甲".repeat(4500)).size)
    }

    // ---------- 11. EDGE 模式分流（2026-11-22 TTS 三模式）：走 Edge 引擎，prepare 失败不回落 ----------

    @Test
    fun edgeMode_speaksThroughEdgeEngineWithoutTouchingSystem(): Unit = runBlocking {
        val system = FakeEngine()
        val edge = FakeEngine()
        val player = TtsPlayer(system, scope, localEngine = null, edgeEngine = edge)
        player.mode = TtsEngineMode.EDGE

        player.toggle("内容。")
        awaitState(player) { it is TtsState.Speaking }

        assertTrue(edge.prepareCalled)
        assertFalse("EDGE 不走系统引擎", system.prepareCalled)
        edge.onDoneCb?.invoke()
        assertEquals(TtsState.Idle, player.state.value)
    }

    @Test
    fun edgeUnavailable_showsEdgeErrorWithoutFallback(): Unit = runBlocking {
        val system = FakeEngine()
        val edge = FakeEngine()
        edge.prepareResult = "Edge 在线朗读需要网络连接，请联网后使用（也可切回「手机自带」引擎）"
        val player = TtsPlayer(system, scope, localEngine = null, edgeEngine = edge)
        player.mode = TtsEngineMode.EDGE

        player.toggle("内容。")
        val s = awaitState(player) { it is TtsState.Error } as TtsState.Error

        assertEquals(edge.prepareResult, s.message)
        assertFalse("EDGE 失败不回落系统引擎（回落只会退回已不可用的路径）", system.prepareCalled)
    }

    @Test
    fun edgeMode_withoutEdgeEngine_fallsBackToSystem(): Unit = runBlocking {
        val system = FakeEngine()
        val player = TtsPlayer(system, scope, localEngine = null, edgeEngine = null)
        player.mode = TtsEngineMode.EDGE

        player.toggle("内容。")
        awaitState(player) { it is TtsState.Speaking }

        assertTrue(system.prepareCalled)
    }

    // ---------- 12. 防崩兜底（2026-11-28 真机「点朗读闪退」加固）：引擎异常转 Error 态不带崩 App ----------

    @Test
    fun enginePrepareThrows_turnsIntoErrorNotCrash(): Unit = runBlocking {
        val engine = FakeEngine()
        engine.prepareThrows = RuntimeException("ROM binder 崩了")
        val player = TtsPlayer(engine, scope)

        player.toggle("内容。")
        val s = awaitState(player) { it is TtsState.Error } as TtsState.Error

        assertTrue("防崩文案应人话且带原因，实际：${s.message}", s.message.contains("朗读出错"))
    }

    @Test
    fun engineStopThrows_stopStillReturnsIdle(): Unit = runBlocking {
        val engine = FakeEngine()
        val player = TtsPlayer(engine, scope)
        player.toggle("内容。")
        awaitState(player) { it is TtsState.Speaking }
        engine.stopThrows = RuntimeException("DeadObjectException 形态")

        player.stop()

        assertEquals("引擎 stop 抛异常时仍回 Idle 不闪退", TtsState.Idle, player.state.value)
    }
}
