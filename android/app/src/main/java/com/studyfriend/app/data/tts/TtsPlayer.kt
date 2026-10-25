package com.studyfriend.app.data.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 朗读引擎模式（TTS 双模式）：SYSTEM=手机自带系统引擎 / LOCAL=本地离线模型（sherpa kokoro） */
enum class TtsEngineMode { SYSTEM, LOCAL }

/** TTS 引擎抽象（计划 M5 §2.6）：逐句进度与错误都要有出口（评审 P1-2）。
 *  [prepare] 返回 null=可用；非 null=用户可读的不可用原因（错误文案分级由引擎决定） */
interface TtsEngine {
    suspend fun prepare(): String?

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
 * 朗读控制（TTS 双模式）：按 [mode] 选引擎，本地模型不可用时自动回落系统语音并经
 * [notice] 说明原因（「听的思维」回落链经验）；toggle 切换播放/停止；stop 回 Idle；
 * 引擎晚到的"用户停止引发 onError"依状态忽略（已回 Idle 即不报错）。
 */
class TtsPlayer(
    private val systemEngine: TtsEngine,
    private val scope: CoroutineScope,
    private val localEngine: TtsEngine? = null,
) {

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    /** 一次性提示（如「离线模型没下载，这次先用系统语音」）：朗读中显示，stop/播完/下次 toggle 清掉 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 当前引擎模式：设置读到后随时赋值，下次 toggle 生效 */
    @Volatile
    var mode: TtsEngineMode = TtsEngineMode.SYSTEM

    // 引擎回调线程与主线程并发读写，volatile 保证可见
    @Volatile
    private var job: Job? = null

    @Volatile
    private var activeEngine: TtsEngine? = null

    fun toggle(text: String) {
        if (job != null) {
            stop()
            return
        }
        job = scope.launch {
            _notice.value = null
            _state.value = TtsState.Preparing
            var engine = if (mode == TtsEngineMode.LOCAL) localEngine ?: systemEngine else systemEngine
            var err = engine.prepare()
            if (err != null && engine !== systemEngine) {
                // 本地模型不可用：自动回落系统语音，明说原因（回落文案留给 UI 朗读中显示）
                _notice.value = "离线朗读暂不可用（$err），这次先用系统语音"
                engine = systemEngine
                err = engine.prepare()
            }
            if (err != null) {
                _state.value = TtsState.Error(err)
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
            activeEngine = engine
            _state.value = TtsState.Speaking(sentenceIdx = 0, total = sentences.size)
            engine.speak(
                text,
                onSentence = { idx -> _state.update { if (it is TtsState.Speaking) TtsState.Speaking(idx, sentences.size) else it } },
                onDone = {
                    _state.value = TtsState.Idle
                    _notice.value = null
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
        (activeEngine ?: systemEngine).stop()
        _state.value = TtsState.Idle
        _notice.value = null
    }

    fun shutdown() {
        stop()
        systemEngine.shutdown()
        localEngine?.shutdown()
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

/**
 * 句→段定位（功能补齐轮正文听书）：朗读文本 = 多段顺序拼接，[paraSentenceCounts]
 * 是每段的分句数（splitSentences 同口径）；返回全局句序号 [sentenceIdx] 落在第几段
 * （0-based，paraSentenceCounts 的下标）。越界取首/末段。
 */
internal fun sentenceToParaIndex(paraSentenceCounts: List<Int>, sentenceIdx: Int): Int {
    if (paraSentenceCounts.isEmpty()) return 0
    var acc = 0
    for ((i, n) in paraSentenceCounts.withIndex()) {
        acc += n
        if (sentenceIdx < acc) return i
    }
    return paraSentenceCounts.size - 1
}

private const val MAX_LEN = 4000
