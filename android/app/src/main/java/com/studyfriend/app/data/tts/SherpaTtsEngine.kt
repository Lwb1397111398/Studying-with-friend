package com.studyfriend.app.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sherpa-onnx 离线朗读引擎（TTS 双模式之「本地模型」，经验学自「听的思维」项目）。
 * 模型 kokoro-int8-multi-lang-v1_1（中英双语），由 [TtsModelManager] 下载管理；
 * 配置同款：lexicon 三件套（zh/us-en/gb-en）+ rule_fsts 三件套（phone/date/number-zh）
 * + jieba dict + espeak-ng-data，silenceScale 0.5 压住 kokoro 句读处拖长的停顿。
 *
 * native 引擎 App 级单例：加载 114MB 模型要数秒，随页面反复重建不可接受（听的同款设计）；
 * 本类自身轻量，随页面创建销毁，只管状态与 AudioTrack。
 * 注意：aar 已剔除自带 libonnxruntime.so（与 OCR 的微软 onnxruntime 同名冲突），
 * 运行时共享微软 1.22.0 —— 32 位机型无对应 so 时加载失败，prepare 会报错回落系统语音。
 */
class SherpaTtsEngine(private val context: Context) : TtsEngine {

    /** 语速倍率（与 AndroidTtsEngine 同口径 0.5~2.0；generateWithCallback 的 speed 参数，每句生效） */
    @Volatile
    var rate: Float = 1.0f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val playing = AtomicBoolean(false)

    // speak 线程写、stop（主线程）pause/flush，volatile 保证可见
    @Volatile
    private var track: AudioTrack? = null

    override suspend fun prepare(): String? = withContext(Dispatchers.IO) {
        if (!TtsModelManager.isTtsReady(context)) {
            return@withContext "离线朗读模型还没下载，可到设置里下载（约 215MB）"
        }
        try {
            obtain()
            null
        } catch (t: Throwable) {
            // 模型损坏 / 32 位机型缺配套 onnxruntime 等，统一人话报错（UI 层自动回落系统语音）
            "离线朗读模型加载失败，可到设置里删除模型后重新下载"
        }
    }

    override fun speak(text: String, onSentence: (Int) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        stop()
        playing.set(true)
        scope.launch {
            var local: AudioTrack? = null
            try {
                val engine = obtain()
                val sentences = splitSentences(text)
                val sampleRate = engine.sampleRate()
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    // 约 5 秒领先量（听的经验）：合成背靠背压进缓冲盖住句间启动耗时，句间不出缝
                    .setBufferSizeInBytes(maxOf(minBuf * 4, sampleRate * 2 * 5))
                    .build()
                local = t
                track = t
                t.play()
                var totalFrames = 0
                val chunkCb = TtsChunkCallback(object : TtsChunkCallback.Handler {
                    override fun onChunk(samples: FloatArray): Boolean {
                        if (!playing.get()) return false
                        if (samples.isNotEmpty()) {
                            val pcm = ShortArray(samples.size)
                            for (i in samples.indices) {
                                pcm[i] = ((samples[i] * 32767f).toInt().coerceIn(-32768, 32767)).toShort()
                            }
                            totalFrames += samples.size
                            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        }
                        return playing.get()
                    }
                })
                // 逐句合成、AudioTrack 跨句复用；每句写完推进句进度（领先实际播出约一个缓冲量，
                // 高亮跟随够用）；speed 用当前 rate，朗读中调速下一句生效
                for ((i, s) in sentences.withIndex()) {
                    if (!playing.get()) return@launch
                    engine.generateWithCallback(s, VOICE_ID, rate, chunkCb)
                    if (playing.get()) onSentence(i + 1)
                }
                if (!playing.get()) return@launch
                // 合成完了，等缓冲区里最后一段播出去再收尾
                while (playing.get() && t.playbackHeadPosition < totalFrames) delay(100)
            } catch (e: kotlinx.coroutines.CancellationException) {
                return@launch
            } catch (e: Throwable) {
                if (playing.getAndSet(false)) onError("离线朗读失败：${e.message ?: "未知错误"}")
                return@launch
            } finally {
                track = null
                try {
                    local?.stop()
                } catch (_: Exception) {
                }
                try {
                    local?.release()
                } catch (_: Exception) {
                }
            }
            if (playing.getAndSet(false)) onDone()
        }
    }

    override fun stop() {
        playing.set(false)
        // pause+flush 立即掐声；合成回调下一片返回 false，native 停止，job 自然退出
        track?.pause()
        track?.flush()
    }

    /** 页面销毁：只停播。native 引擎单例常驻，避免每次进页重载数秒 */
    override fun shutdown() {
        stop()
    }

    private fun obtain(): OfflineTts {
        shared?.let { return it }
        synchronized(Companion) {
            shared?.let { return it }
            val dir = TtsModelManager.ttsDir(context)
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        voices = File(dir, "voices.bin").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                        lexicon = listOf("lexicon-zh.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt")
                            .joinToString(",") { File(dir, it).absolutePath },
                        dictDir = File(dir, "dict").absolutePath,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst")
                    .joinToString(",") { File(dir, it).absolutePath },
                maxNumSentences = 1,
                // kokoro 爱在句读处拖长停顿，压一半明显更跟手；0.3 以下会显得喘不上气（听的经验值）
                silenceScale = 0.5f,
            )
            val t = OfflineTts(assetManager = null, config = config)
            shared = t
            return t
        }
    }

    companion object {
        /** 默认音色：kokoro v1.1 里 sid 0-2 是英文女声，3 起才是中文女声（zf_001）（听的经验值） */
        const val VOICE_ID = 3

        @Volatile
        private var shared: OfflineTts? = null
    }
}
