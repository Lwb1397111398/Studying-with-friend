package com.studyfriend.app.data.tts

import com.studyfriend.app.data.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 朗读引擎模式（TTS 三模式）：SYSTEM=手机自带系统引擎 / LOCAL=本地离线模型（sherpa kokoro）/ EDGE=Edge 在线朗读 */
enum class TtsEngineMode { SYSTEM, LOCAL, EDGE }

/** TTS 引擎抽象（计划 M5 §2.6）：逐句进度与错误都要有出口（评审 P1-2）。
 *  [prepare] 返回 null=可用；非 null=用户可读的不可用原因（错误文案分级由引擎决定） */
interface TtsEngine {
    suspend fun prepare(): String?

    /** 全文朗读：分句后逐句入队；onSentence(已播完句数) 推进进度，onDone 整体完成 */
    fun speak(text: String, onSentence: (Int) -> Unit, onDone: () -> Unit, onError: (String) -> Unit)

    fun stop()

    fun shutdown()
}

/** 朗读状态机：Idle / Preparing / Speaking(已播句数, 总句数) / Paused(暂停在下一句序号, 总句数) / Error */
sealed interface TtsState {
    data object Idle : TtsState
    data object Preparing : TtsState
    data class Speaking(val sentenceIdx: Int, val total: Int) : TtsState
    /** 暂停（第九波实景轮）：sentenceIdx = 恢复时要从头重读的那一句序号（0-based） */
    data class Paused(val sentenceIdx: Int, val total: Int) : TtsState
    data class Error(val message: String) : TtsState
}

/**
 * 朗读控制（TTS 双模式）：按 [mode] 选引擎，本地模型不可用时自动回落系统语音并经
 * [notice] 说明原因（「听的思维」回落链经验）；toggle 切换播放/停止；stop 回 Idle；
 * 引擎晚到的"用户停止引发 onError"依状态忽略（已回 Idle 即不报错）。
 * 第九波实景轮加暂停/恢复（anx-reader v1.14.0 思路）：pause 停引擎记下一句序号，
 * resume 从该句在原文中的偏移重切文本继续——句→段映射仍按全文句号口径，进度不跳变。
 */
class TtsPlayer(
    private val systemEngine: TtsEngine,
    private val scope: CoroutineScope,
    private val localEngine: TtsEngine? = null,
    private val edgeEngine: TtsEngine? = null,
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

    /** toggle 入口记全文：pause 现场（含 Preparing 无进度）恢复时要能重切文本 */
    @Volatile
    private var lastToggleText: String? = null

    /** 暂停现场是否有效（stop/toggle 新播会清） */
    @Volatile
    private var pausedText: String? = null

    /**
     * 播放入口（toggle 与 resume 共用）：[fromSentence] > 0 时从该句在原文中的偏移
     * 重切子文本续播，句进度回调加回偏移保持全文口径；口径对不上回退整篇从头播。
     * [totalFull] 为全文句数（恢复场景），从头播传 0 由这里现算。
     */
    private suspend fun startSpeaking(text: String, fromSentence: Int, totalFull: Int) {
        _notice.value = null
        _state.value = TtsState.Preparing
        // 三模式分发：LOCAL 不可用自动回落系统语音；EDGE 不可用直接报错（回落系统语音只会
        // 退回已被证明不可用的路径，明说原因让用户自己选更清楚）
        var engine = when (mode) {
            TtsEngineMode.LOCAL -> localEngine ?: systemEngine
            TtsEngineMode.EDGE -> edgeEngine ?: systemEngine
            else -> systemEngine
        }
        var err = engine.prepare()
        if (err != null && engine !== systemEngine && mode == TtsEngineMode.LOCAL) {
            // 本地模型不可用：自动回落系统语音，明说原因（回落文案留给 UI 朗读中显示）
            _notice.value = "离线朗读暂不可用（$err），这次先用系统语音"
            engine = systemEngine
            err = engine.prepare()
        }
        if (err != null) {
            DiagLog.w("Tts", "prepare 失败 mode=$mode：$err（诊断埋点：定位真机闪退/报错用）")
            _state.value = TtsState.Error(err)
            job = null
            return
        }
        val fullTotal = if (totalFull > 0) totalFull else splitSentences(text).size
        // 恢复场景：从 fromSentence 的原文偏移切子文本续播；口径对不上（分句数不齐）回退整篇
        val start: Int? = if (fromSentence > 0) {
            val starts = splitSentenceStarts(text)
            if (starts.size == fullTotal) starts.getOrNull(fromSentence) else null
        } else {
            null
        }
        val textToSpeak = if (start != null) text.substring(start) else text
        val total = fullTotal
        if (splitSentences(textToSpeak).isEmpty()) {
            _state.value = TtsState.Error("没有可朗读的内容")
            job = null
            return
        }
        // prepare 与 speak 入队之间无挂起点，这里补一次取消检查：stop 已执行过就不再入队发声
        kotlin.coroutines.coroutineContext.ensureActive()
        activeEngine = engine
        _state.value = TtsState.Speaking(sentenceIdx = if (start != null) fromSentence else 0, total = total)
        val base = if (start != null) fromSentence else 0
        engine.speak(
            textToSpeak,
            onSentence = { idx ->
                // idx 相对子文本；加回 base 得全文句号（句→段映射/进度不跳变）
                _state.update {
                    if (it is TtsState.Speaking || it is TtsState.Preparing) {
                        TtsState.Speaking(base + idx, total)
                    } else {
                        it
                    }
                }
            },
            onDone = {
                _state.value = TtsState.Idle
                _notice.value = null
                pausedText = null
                job = null
            },
            onError = { msg ->
                // 用户 stop 已回 Idle：晚到的停止类错误不再覆盖成 Error
                DiagLog.w("Tts", "引擎报错 mode=$mode：$msg")
                _state.update {
                    if (it is TtsState.Speaking || it is TtsState.Preparing) TtsState.Error(msg) else it
                }
                job = null
            },
        )
    }

    fun toggle(text: String) {
        if (job != null) {
            stop()
            return
        }
        pausedText = null
        lastToggleText = text
        DiagLog.i("Tts", "toggle 开始朗读 mode=$mode")
        launchSpeak(text, fromSentence = 0, totalFull = 0)
    }

    /**
     * 播放入口的防崩包装（2026-11-28 真机「点朗读闪退」兜底网）：startSpeaking 与引擎
     * 回调里任何未捕获异常都不再带崩 App（rememberCoroutineScope 的协程异常会直接走
     * 默认崩溃处理器），统一转人话 Error 态 + 写诊断日志（设置页可导出抓栈定位根因）。
     */
    private fun launchSpeak(text: String, fromSentence: Int, totalFull: Int) {
        job = scope.launch {
            try {
                startSpeaking(text, fromSentence, totalFull)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                DiagLog.e("Tts", "朗读未捕获异常 mode=$mode", t)
                _state.update {
                    if (it is TtsState.Speaking || it is TtsState.Preparing) {
                        TtsState.Error("朗读出错：${t.message ?: t.javaClass.simpleName}，可换朗读引擎重试")
                    } else {
                        it
                    }
                }
                job = null
            }
        }
    }

    /**
     * 暂停（第九波）：停引擎记「下一句从哪继续」。只能从 Speaking/Preparing 暂停；
     * Preparing 阶段没有进度，按第 0 句恢复。恢复素材（全文）留存到 resume/stop 为止。
     */
    fun pause() {
        val job0 = job ?: return
        val s = _state.value
        if (s !is TtsState.Speaking && s !is TtsState.Preparing) return
        pausedText = lastToggleText
        job0.cancel()
        job = null
        // 个别 ROM 引擎 stop 抛异常（binder 死了）——兜住别闪退（2026-11-28 加固）
        runCatching { (activeEngine ?: systemEngine).stop() }
            .onFailure { DiagLog.e("Tts", "pause 时引擎 stop 异常", it) }
        val total = (s as? TtsState.Speaking)?.total ?: splitSentences(lastToggleText.orEmpty()).size
        val next = if (s is TtsState.Speaking) s.sentenceIdx + 1 else 0
        _state.value = TtsState.Paused(sentenceIdx = next, total = total)
    }

    /** 恢复：从暂停句起继续播；没暂停现场（Idle/Error/未播过）就不动 */
    fun resume() {
        if (job != null) return
        val text = pausedText ?: return
        val s = _state.value as? TtsState.Paused ?: return
        DiagLog.i("Tts", "resume 句级续播 mode=$mode from=$s")
        launchSpeak(text, fromSentence = s.sentenceIdx, totalFull = s.total)
    }

    fun stop() {
        job?.cancel()
        job = null
        // 个别 ROM 引擎 stop/shutdown 抛异常——兜住别闪退（2026-11-28 加固，主线程点击路径）
        runCatching { (activeEngine ?: systemEngine).stop() }
            .onFailure { DiagLog.e("Tts", "stop 时引擎 stop 异常", it) }
        _state.value = TtsState.Idle
        _notice.value = null
        pausedText = null
    }

    fun shutdown() {
        stop()
        runCatching { systemEngine.shutdown() }.onFailure { DiagLog.e("Tts", "systemEngine shutdown 异常", it) }
        runCatching { localEngine?.shutdown() }.onFailure { DiagLog.e("Tts", "localEngine shutdown 异常", it) }
        // 2026-11-28 补：Edge 引擎此前漏在 shutdown 清单里（无常驻资源影响小，补齐防泄漏）
        runCatching { edgeEngine?.shutdown() }.onFailure { DiagLog.e("Tts", "edgeEngine shutdown 异常", it) }
    }
}

/**
 * 分句起始偏移版（第九波，供 resume 从句偏移切文本）：返回每句在原文中的起始偏移，
 * 口径与 [splitSentences] 同（同分隔符/同去空白句/同超长硬切）。splitSentences 会丢弃
 * 空白句导致偏移列表与句数不齐——检测到不齐返回空表，调用方回退整篇重播（宁重播不错位）。
 */
internal fun splitSentenceStarts(text: String): List<Int> {
    val raw = mutableListOf<Int>()
    var bufStart = 0
    for ((i, ch) in text.withIndex()) {
        if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '；' || ch == ';' || ch == '\n') {
            raw.add(bufStart)
            bufStart = i + 1
        }
    }
    if (bufStart < text.length) raw.add(bufStart)
    // splitSentences 只丢「trim 后空白」的 piece；空白 piece 的起点从 raw 里剔除即对齐
    val pieces = mutableListOf<String>()
    var bufStart2 = 0
    for ((i, ch) in text.withIndex()) {
        if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '；' || ch == ';' || ch == '\n') {
            pieces.add(text.substring(bufStart2, i + 1))
            bufStart2 = i + 1
        }
    }
    if (bufStart2 < text.length) pieces.add(text.substring(bufStart2))
    val kept = raw.filterIndexed { idx, _ -> pieces.getOrNull(idx)?.trim()?.isNotEmpty() == true }
    // 超长硬切：splitSentences 对 trim 后的 piece 按 MAX_LEN 切块——切块只出现在句内，
    // 每个额外块的起始 = 前块起始 + MAX_LEN（在原文中的位置因 trim 前移量一致，resume
    // 只要求「从某块起播」，起点偏后几个空白字符无感知）
    val result = mutableListOf<Int>()
    for ((idx, start) in kept.withIndex()) {
        val piece = pieces.getOrNull(idx)?.trim() ?: continue
        var i = 0
        var first = true
        while (i < piece.length) {
            if (first) {
                result.add(start)
                first = false
            } else {
                result.add(start + i)
            }
            i += MAX_LEN
        }
    }
    return result
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
