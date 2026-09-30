package com.studyfriend.app.data.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** TTS 引擎抽象（计划 M5 §2.6）：逐句进度与错误都要有出口（评审 P1-2） */
interface TtsEngine {
    /** 初始化并确认中文语音可用；不可用返回 false（UI 引导，不抛错） */
    suspend fun prepare(): Boolean

    /** 全文朗读：分句后逐句入队；onSentence(已播完句数) 推进进度，onDone 整体完成 */
    fun speak(text: String, onSentence: (Int) -> Unit, onDone: () -> Unit, onError: (String) -> Unit)

    fun stop()

    fun shutdown()
}

/** 朗读状态机：Idle / Preparing / Speaking(已播句数, 总句数) / Error */
sealed interface TtsState {
    data object Idle : TtsState
    data object Preparing : TtsState
    data class Speaking(val sentenceIdx: Int, val total: Int) : TtsState
    data class Error(val message: String) : TtsState
}

/**
 * 朗读控制：toggle 切换播放/停止；stop 回 Idle；
 * 引擎晚到的"用户停止引发 onError"依状态忽略（已回 Idle 即不报错）。
 */
class TtsPlayer(private val engine: TtsEngine, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    // 引擎回调线程（onDone/onError 置 null）与主线程（toggle/stop）并发读写，volatile 保证可见
    @Volatile
    private var job: Job? = null

    fun toggle(text: String) {
        if (job != null) {
            stop()
            return
        }
        job = scope.launch {
            _state.value = TtsState.Preparing
            if (!engine.prepare()) {
                _state.value = TtsState.Error("本机没有可用的中文语音，无法朗读")
                job = null
                return@launch
            }
            val sentences = splitSentences(text)
            if (sentences.isEmpty()) {
                _state.value = TtsState.Error("没有可朗读的内容")
                job = null
                return@launch
            }
            // prepare 与 speak 入队之间无挂起点，这里补一次取消检查：stop 已执行过就不再入队发声
            ensureActive()
            _state.value = TtsState.Speaking(sentenceIdx = 0, total = sentences.size)
            engine.speak(
                text,
                onSentence = { idx -> _state.update { if (it is TtsState.Speaking) TtsState.Speaking(idx, sentences.size) else it } },
                onDone = {
                    _state.value = TtsState.Idle
                    job = null
                },
                onError = { msg ->
                    // 用户 stop 已回 Idle：晚到的停止类错误不再覆盖成 Error
                    _state.update { if (it is TtsState.Speaking || it is TtsState.Preparing) TtsState.Error(msg) else it }
                    job = null
                },
            )
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        engine.stop()
        _state.value = TtsState.Idle
    }

    fun shutdown() {
        stop()
        engine.shutdown()
    }
}

/** 分句：句末标点/分号/换行切分；空白丢弃；超长句按 4000 字符硬切（TTS 单次入队上限） */
internal fun splitSentences(text: String): List<String> {
    val pieces = mutableListOf<String>()
    val buf = StringBuilder()
    for (ch in text) {
        buf.append(ch)
        if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '；' || ch == ';' || ch == '\n') {
            pieces.add(buf.toString())
            buf.clear()
        }
    }
    if (buf.isNotBlank()) pieces.add(buf.toString())

    val result = mutableListOf<String>()
    for (p in pieces) {
        val t = p.trim()
        if (t.isEmpty()) continue
        var i = 0
        while (i < t.length) {
            result.add(t.substring(i, minOf(i + MAX_LEN, t.length)))
            i += MAX_LEN
        }
    }
    return result
}

private const val MAX_LEN = 4000
