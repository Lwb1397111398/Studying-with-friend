package com.studyfriend.app.data.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** 系统 TextToSpeech 封装（TTS 双模式之「手机自带」）：prepare 分级诊断；utteranceId 编码句序号
 *  逐句回调；[rate] 语速倍率（0.5~2.0，prepare 时生效，可变属性——设置读到后随时赋值，下次 prepare 应用） */
class AndroidTtsEngine(private val context: Context) : TtsEngine {

    @Volatile
    var rate: Float = 1.0f

    // init 回调在 binder 线程读、主线程重建/置空，volatile 保证可见
    @Volatile
    private var tts: TextToSpeech? = null

    /**
     * 可用性检查 + 初始化，返回 null=可用，非 null=用户可读的原因。分级诊断（听的项目经验）：
     * 1. 包可见性预检：targetSdk 30+ 未声明 queries 时这里就查不到引擎（真机「没有可用中文语音」根因之一）
     * 2. 初始化挂 6 秒超时：裸机/无引擎时 TextToSpeech 回调可能永远不回来，卡 Preparing
     * 3. setLanguage 放宽：不少国产引擎返回 LANG_MISSING_DATA 但实际能读中文，一票否决会误判；
     *    真缺数据时 speak 阶段的 onError 会兜住
     */
    override suspend fun prepare(): String? {
        if (!hasSystemEngine()) {
            return "本机没有可用的系统朗读引擎，请检查系统设置的「文字转语音（TTS）」"
        }
        // 上次 prepare 留下的实例先拆掉，防重复初始化泄漏
        tts?.let { old -> runCatching { old.shutdown() } }
        tts = null
        val status = withTimeoutOrNull(INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val engine = TextToSpeech(context) { s ->
                    if (cont.isActive) cont.resume(s)
                }
                tts = engine
                cont.invokeOnCancellation { runCatching { engine.shutdown() } }
            }
        }
        if (status == null) {
            tts = null
            return "系统朗读引擎初始化超时，请到系统设置检查「文字转语音（TTS）」"
        }
        if (status != TextToSpeech.SUCCESS) {
            return "系统朗读引擎初始化失败（$status），请到系统设置检查「文字转语音（TTS）」"
        }
        val engine = tts
        engine?.setLanguage(Locale.SIMPLIFIED_CHINESE)
        engine?.setSpeechRate(rate)
        return null
    }

    /** 本机装没装 TTS 语音引擎（没有任何引擎时 TextToSpeech 的初始化回调可能永远不回来） */
    private fun hasSystemEngine(): Boolean =
        context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).addCategory(Intent.CATEGORY_DEFAULT),
            0,
        ).isNotEmpty()

    override fun speak(text: String, onSentence: (Int) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        val engine = tts ?: run {
            onError("TTS 引擎未初始化")
            return
        }
        val sentences = splitSentences(text)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                if (idx >= sentences.size - 1) onDone() else onSentence(idx + 1)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onError("TTS 合成失败（$errorCode），可在设置里切换离线朗读模型")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError("TTS 合成失败，可在设置里切换离线朗读模型")
            }
        })
        for ((i, s) in sentences.withIndex()) {
            val r = engine.speak(s, TextToSpeech.QUEUE_ADD, Bundle(), i.toString())
            if (r != TextToSpeech.SUCCESS) {
                // 入队中断时停掉已入队语句：不能"界面报错、喇叭还在响"
                engine.stop()
                onError("TTS 入队失败")
                return
            }
        }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private companion object {
        /** 初始化超时：部分机型无引擎时回调永不返回（听的项目同款 6 秒兜底） */
        const val INIT_TIMEOUT_MS = 6_000L
    }
}
