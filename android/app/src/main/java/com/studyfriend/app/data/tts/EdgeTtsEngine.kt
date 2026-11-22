package com.studyfriend.app.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edge 在线朗读引擎（TTS 三模式之「Edge 在线」）：[EdgeTtsClient] 走 Edge 浏览器同款协议
 * 在线合成 mp3，[MediaPlayer] 直接播内存字节（[ByteArrayMediaDataSource]，不落盘）。
 * 逐句预取：当前句播放期间后台合成下一句，句间停顿压到毫秒级；停止即掐声并解锁等待协程。
 * 语速沿用全局语速键（0.5~2.0 → SSML prosody rate 百分数），音色设置页可选（晓晓/云希）。
 */
class EdgeTtsEngine(
    private val context: Context,
    private val client: EdgeTtsClient = EdgeTtsClient(),
) : TtsEngine {

    /** 语速倍率（与 AndroidTtsEngine 同口径 0.5~2.0；每句合成时生效） */
    @Volatile
    var rate: Float = 1.0f

    /** 当前音色（EdgeVoices 常量；设置页读到后随时赋值，下一句生效） */
    @Volatile
    var voice: String = EdgeVoices.XIAOXIAO

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val playing = AtomicBoolean(false)

    // stop 时整根取消：预取中的 WebSocket 合成协程跟着取消（finally 里 ws.cancel 收口）
    @Volatile
    private var speakJob: Job? = null

    // 主线程写、speak 协程读，volatile 保证可见
    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var playerDone: CompletableDeferred<Boolean>? = null

    /** 可用性：有网即可用（真正的连接/合成失败在 speak 阶段人话报错） */
    override suspend fun prepare(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            return "Edge 在线朗读需要网络连接，请联网后使用（也可切回「手机自带」引擎）"
        }
        return null
    }

    override fun speak(text: String, onSentence: (Int) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        stop()
        playing.set(true)
        speakJob = scope.launch {
            try {
                coroutineScope {
                    val sentences = splitSentences(text)
                    // 预取：pending = 下一句的合成任务（当前句播放期间在后台跑）
                    var pending: kotlinx.coroutines.Deferred<ByteArray?>? = null
                    for ((i, s) in sentences.withIndex()) {
                        if (!playing.get()) return@coroutineScope
                        val bytes = run {
                            val d = pending ?: async { client.synthesize(s, voice, ratePercent()) }
                            d.await()
                        }
                        pending = if (i + 1 < sentences.size) {
                            async { client.synthesize(sentences[i + 1], voice, ratePercent()) }
                        } else {
                            null
                        }
                        if (bytes == null) {
                            if (playing.getAndSet(false)) onError("Edge 在线朗读合成失败，请检查网络后重试")
                            return@coroutineScope
                        }
                        if (!playing.get()) return@coroutineScope
                        val played = playBytes(bytes)
                        if (!played) {
                            // stop() 掐声走 playing=false 静默退出；没停过=播放器真出错
                            if (playing.getAndSet(false)) onError("Edge 在线朗读播放失败，请重试")
                            return@coroutineScope
                        }
                        onSentence(i + 1)
                    }
                    if (playing.getAndSet(false)) onDone()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                return@launch
            } catch (e: Throwable) {
                if (playing.getAndSet(false)) onError("Edge 在线朗读失败：${e.message ?: "未知错误"}")
            }
        }
    }

    override fun stop() {
        playing.set(false)
        speakJob?.cancel()
        speakJob = null
        runOnMain {
            // 解锁等待播放完成的协程（否则协程挂在 done.await() 永不返回），再立即掐声
            playerDone?.complete(false)
            releasePlayer()
        }
    }

    /** 页面销毁：只停播（client 无常驻资源，OkHttp 空闲连接自动回收） */
    override fun shutdown() {
        stop()
    }

    /**
     * 播一段 mp3 字节，返回 true=正常播完。播放器挂主线程（MediaPlayer 要求带 Looper 的线程），
     * 完成/出错经 CompletableDeferred 交回调用协程；stop() 主线程 complete(false)+release 掐声。
     */
    private suspend fun playBytes(bytes: ByteArray): Boolean {
        val done = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(ByteArrayMediaDataSource(bytes))
                mp.setOnCompletionListener { done.complete(true) }
                mp.setOnErrorListener { _, what, extra ->
                    android.util.Log.w("EdgeTts", "MediaPlayer error what=$what extra=$extra")
                    done.complete(false)
                    true
                }
                mp.prepare() // 内存数据源，prepare 毫秒级
                mp.start()
                player = mp
                playerDone = done
            } catch (t: Throwable) {
                android.util.Log.w("EdgeTts", "播放初始化失败：${t.message}")
                player = null
                playerDone = null
                done.complete(false)
            }
        }
        val ok = done.await()
        withContext(Dispatchers.Main) { releasePlayer() }
        return ok
    }

    private fun releasePlayer() {
        playerDone = null
        val p = player ?: return
        player = null
        runCatching { if (p.isPlaying) p.stop() }
        runCatching { p.release() }
    }

    /** 语速 0.5~2.0 → SSML 百分数：1.0→"+0%"，1.5→"+50%"，0.8→"-20%" */
    private fun ratePercent(): Int {
        val p = ((rate - 1f) * 100f).toInt()
        return p.coerceIn(-50, 100)
    }

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(block)
        }
    }
}

/**
 * 内存 mp3 直读（API 23+ MediaDataSource，minSdk 26 可用）：MediaPlayer 不落盘播放，
 * 免去缓存文件生命周期管理。readAt 语义：position 超出返回 -1（EOF），其余尽量填满。
 */
private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}
