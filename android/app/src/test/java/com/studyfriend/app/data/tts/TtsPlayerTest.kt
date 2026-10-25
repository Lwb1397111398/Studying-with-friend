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
        var prepareCalled = false
        var stopCalled = false
        var shutdownCalled = false
        var onSentenceCb: ((Int) -> Unit)? = null
        var onDoneCb: (() -> Unit)? = null
        var onErrorCb: ((String) -> Unit)? = null

        override suspend fun prepare(): String? {
            prepareCalled = true
            return prepareResult
        }

        override fun speak(text: String, onSentence: (Int) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
            onSentenceCb = onSentence
            onDoneCb = onDone
            onErrorCb = onError
        }

        override fun stop() {
            stopCalled = true
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
}
