package com.studyfriend.app.data.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** 系统 TextToSpeech 封装：prepare 确认简体中文可用；utteranceId 编码句序号逐句回调 */
class AndroidTtsEngine(private val context: Context) : TtsEngine {

    // init 回调在 binder 线程读、主线程重建/置空，volatile 保证可见
    @Volatile
    private var tts: TextToSpeech? = null

    override suspend fun prepare(): Boolean = suspendCancellableCoroutine { cont ->
        val engine = TextToSpeech(context) { status ->
            val ok = if (status == TextToSpeech.SUCCESS) {
                val r = engineSafe()?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                r == TextToSpeech.LANG_AVAILABLE || r == TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                false
            }
            if (cont.isActive) cont.resume(ok)
        }
        tts = engine
        cont.invokeOnCancellation { engine.shutdown() }
    }

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
                onError("TTS 合成失败（$errorCode）")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError("TTS 合成失败")
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

    private fun engineSafe(): TextToSpeech? = tts
}
