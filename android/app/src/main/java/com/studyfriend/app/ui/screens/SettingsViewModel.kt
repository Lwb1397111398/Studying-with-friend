package com.studyfriend.app.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.AiException
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.ChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 设置页：读写配置 + 测试连接（计划 M3 §1） */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository((app as StudyApp).database, (app as StudyApp).secretStore)

    var baseUrl by mutableStateOf(SettingsRepository.DEFAULT_BASE)
    var model by mutableStateOf(SettingsRepository.DEFAULT_MODEL)
    var tempText by mutableStateOf("0.3")
    var keyInput by mutableStateOf("")
    var hasKey by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var liveDelta by mutableStateOf("")
        private set
    var detailLevel by mutableStateOf("标准")
        private set

    /** 视觉兜底（OPT-E）：开关即点即存；模型/地址/Key 随"保存"按钮落库 */
    var visionEnabled by mutableStateOf(true)
        private set
    var visionModel by mutableStateOf(SettingsRepository.DEFAULT_VISION_MODEL)

    /** 视觉专属地址：空 = 跟主配置同一家（文本走 A 家、视觉兜底走 B 家时才填） */
    var visionBaseUrl by mutableStateOf("")
    var visionKeyInput by mutableStateOf("")
    var hasVisionKey by mutableStateOf(false)
        private set

    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            val s = repo.load()
            baseUrl = s.baseUrl
            model = s.model
            tempText = s.temperature.toString()
            hasKey = s.hasKey
            detailLevel = repo.detailLevel()
            visionEnabled = s.visionEnabled
            visionModel = s.visionModel
            visionBaseUrl = s.visionBaseUrl
            hasVisionKey = s.hasVisionKey
        }
    }

    fun save() {
        if (busy || testing) return
        viewModelScope.launch {
            busy = true
            error = null
            message = null
            try {
                val temp = AiClient.parseTemperature(tempText)
                repo.save(baseUrl, model, temp, keyInput.takeIf { it.isNotBlank() })
                repo.saveVision(visionEnabled, visionModel, visionBaseUrl, visionKeyInput.takeIf { it.isNotBlank() })
                if (keyInput.isNotBlank()) {
                    hasKey = true
                    keyInput = ""
                }
                if (visionKeyInput.isNotBlank()) {
                    hasVisionKey = true
                    visionKeyInput = ""
                }
                tempText = temp.toString()
                message = "已保存"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "保存失败：${e.message ?: "未知错误"}"
            } finally {
                busy = false
            }
        }
    }

    fun testConnection() {
        if (testing || busy) return
        testJob = viewModelScope.launch {
            testing = true
            error = null
            message = null
            liveDelta = ""
            try {
                // 用户改了 Key 还没保存时，优先用输入框里的新 Key 测试，避免拿旧 Key 配新地址误导
                val key = keyInput.takeIf { it.isNotBlank() } ?: repo.decryptKeyOrNull()
                if (key == null) {
                    hasKey = false
                    error = "API Key 已失效或未保存，请重新填写并保存"
                    return@launch
                }
                val start = System.currentTimeMillis()
                val reply = AiClient.chat(
                    ChatRequest(
                        baseUrl = baseUrl,
                        apiKey = key,
                        model = model,
                        temperature = AiClient.parseTemperature(tempText),
                        // 推理型模型会先输出思考 token：预算太小只能收到空正文
                        maxTokens = 2048,
                        messages = listOf(AiMessage("user", "ping")),
                    ),
                ) { delta -> liveDelta += delta }
                message = if (reply.isBlank()) {
                    "连接成功，但模型未返回正文（思考可能尚未结束），可正常使用 · ${System.currentTimeMillis() - start} ms"
                } else {
                    "连接成功 · ${reply.length} 字 · ${System.currentTimeMillis() - start} ms"
                }
            } catch (e: AiException) {
                error = e.message
            } catch (e: CancellationException) {
                // 用户取消，不算错误
            } catch (e: Exception) {
                error = "测试失败：${e.message ?: "未知错误"}"
            } finally {
                testing = false
            }
        }
    }

    /** 快捷预设：只填输入框不落库，用户确认后再点"保存" */
    fun applyPreset(baseUrlPreset: String, modelPreset: String) {
        if (busy || testing) return
        baseUrl = baseUrlPreset
        model = modelPreset
        message = "已填入「$modelPreset」预设，填好 Key 后点保存"
    }

    /** 视觉兜底开关（OPT-E）：即点即存，独立于"保存"按钮 */
    fun clearVisionKey() {
        if (busy || testing) return
        viewModelScope.launch {
            try {
                repo.clearVisionKey()
                hasVisionKey = false
                visionKeyInput = ""
                message = "已清除视觉专属 Key，视觉兜底回退用主 Key"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "清除视觉 Key 失败：" + (e.message ?: "未知错误")
            }
        }
    }

    fun changeVisionEnabled(enabled: Boolean) {
        if (enabled == visionEnabled) return
        visionEnabled = enabled
        viewModelScope.launch {
            try {
                repo.saveVision(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "保存视觉开关失败：${e.message ?: "未知错误"}"
            }
        }
    }

    /** 讲解详略三档（总计划 §1 目标 7）：即点即存，独立于"保存"按钮 */
    fun chooseDetailLevel(level: String) {
        if (level !in SettingsRepository.DETAIL_LEVELS || level == detailLevel) return
        detailLevel = level
        viewModelScope.launch {
            try {
                repo.saveDetailLevel(level)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "保存详略档失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun cancelTest() {
        testJob?.cancel()
    }

    fun clearKey() {
        if (busy || testing) return
        viewModelScope.launch {
            try {
                repo.clearKey()
                hasKey = false
                keyInput = ""
                message = "已清除 API Key"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "清除失败：${e.message ?: "未知错误"}"
            }
        }
    }
}
