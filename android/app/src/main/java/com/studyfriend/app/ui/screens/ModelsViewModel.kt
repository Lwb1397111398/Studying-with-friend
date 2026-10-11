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
import com.studyfriend.app.data.importer.ocr.OcrModelDownloader
import com.studyfriend.app.data.importer.ocr.OcrModelStore
import com.studyfriend.app.data.vision.VisionScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「查找模型」弹窗目标：点哪个字段的查找按钮，选中后回填谁 */
enum class ModelPickerTarget { MAIN, NOTE, VISION, VISION2 }

/**
 * 模型页（按用途选模型）：主力模型 + 讲解模型 + 视觉转写 + 备用视觉 + 本地 OCR，
 * 全部 AI 模型配置收拢在一页。「查找模型」调服务商 GET /models 拉列表供选择，
 * 拉取失败仍可手填。
 */
class ModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository((app as StudyApp).database, (app as StudyApp).secretStore)

    // ---- 主力模型：粗读批注（章级合并）、章末总结、全书总览、思维导图、测试连接 ----
    var baseUrl by mutableStateOf(SettingsRepository.DEFAULT_BASE)
    var model by mutableStateOf(SettingsRepository.DEFAULT_MODEL)
    var tempText by mutableStateOf("0.3")
    var keyInput by mutableStateOf("")
    var hasKey by mutableStateOf(false)
        private set

    // ---- 讲解模型（可选低成本）：段落讲解 + 粗读批注逐块调用；空 = 跟随主力 ----
    var noteFollowMain by mutableStateOf(true)
        private set
    var noteModel by mutableStateOf("")

    // ---- 视觉转写（OPT-E）：扫描页/坏字页整页转写；开关即点即存，其余随"保存" ----
    var visionEnabled by mutableStateOf(true)
        private set
    var visionModel by mutableStateOf(SettingsRepository.DEFAULT_VISION_MODEL)
    var visionBaseUrl by mutableStateOf("")
    var visionKeyInput by mutableStateOf("")
    var hasVisionKey by mutableStateOf(false)
        private set

    // ---- 备用视觉（R3 多模型轮转）：三键齐才参与轮转；不回退主 Key ----
    var vision2BaseUrl by mutableStateOf("")
    var vision2Model by mutableStateOf("")
    var vision2KeyInput by mutableStateOf("")
    var hasVision2Key by mutableStateOf(false)
        private set

    // ---- 扫描书本地识别（P6b）：开关即点即存；模型就绪与否只读展示 ----
    var ocrEnabled by mutableStateOf(true)
        private set
    var ocrModelsReady by mutableStateOf(false)
        private set
    var ocrDownloadPct by mutableStateOf<Int?>(null)
        private set  // null=未在下载；0-100=下载中

    // ---- 通用状态 ----
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

    // ---- 查找模型：非 null = 弹选择框 ----
    var pickerModels by mutableStateOf<List<String>?>(null)
        private set
    var pickerTitle by mutableStateOf("")
        private set
    var pickerTarget by mutableStateOf(ModelPickerTarget.MAIN)
        private set
    var pickerQuery by mutableStateOf("")
    var fetchingModels by mutableStateOf(false)
        private set

    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            val s = repo.load()
            baseUrl = s.baseUrl
            model = s.model
            tempText = s.temperature.toString()
            hasKey = s.hasKey
            noteFollowMain = s.noteModel.isBlank()
            noteModel = s.noteModel
            visionEnabled = s.visionEnabled
            visionModel = s.visionModel
            visionBaseUrl = s.visionBaseUrl
            hasVisionKey = s.hasVisionKey
            vision2BaseUrl = s.vision2BaseUrl
            vision2Model = s.vision2Model
            hasVision2Key = s.hasVision2Key
            ocrEnabled = s.ocrEnabled
            ocrModelsReady = OcrModelStore.modelsPresent(getApplication<StudyApp>())
        }
    }

    /** 保存主力 + 讲解 + 视觉三组配置（开关类即点即存不在此列） */
    fun save() {
        if (busy || testing) return
        viewModelScope.launch {
            busy = true
            error = null
            message = null
            try {
                val temp = AiClient.parseTemperature(tempText)
                repo.save(baseUrl, model, temp, keyInput.takeIf { it.isNotBlank() })
                repo.saveNoteModel(if (noteFollowMain) null else noteModel)
                repo.saveVision(visionEnabled, visionModel, visionBaseUrl, visionKeyInput.takeIf { it.isNotBlank() })
                repo.saveVision2(vision2BaseUrl, vision2Model, vision2KeyInput.takeIf { it.isNotBlank() })
                if (keyInput.isNotBlank()) {
                    hasKey = true
                    keyInput = ""
                }
                if (visionKeyInput.isNotBlank()) {
                    hasVisionKey = true
                    visionKeyInput = ""
                }
                if (vision2KeyInput.isNotBlank()) {
                    hasVision2Key = true
                    vision2KeyInput = ""
                }
                tempText = temp.toString()
                // OPT-F：视觉配置变了（开关/Key/模型），队列里有待转写页的书立即重调度
                val appCtx = getApplication<StudyApp>()
                VisionScheduler.reenqueueAllPending(appCtx.database, appCtx)
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

    /** 测试主力模型连通（流式 ping，实时预览） */
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

    fun cancelTest() {
        testJob?.cancel()
    }

    /**
     * 查找模型（按目标字段）：调服务商 GET /models，成功弹选择框，失败报人话
     * （仍可手填）。地址/Key 取各目标的生效值——输入框里没保存的新值优先。
     */
    fun fetchModels(target: ModelPickerTarget) {
        if (fetchingModels || busy || testing) return
        viewModelScope.launch {
            fetchingModels = true
            error = null
            try {
                val (base, key) = resolveEndpoint(target)
                if (base.isBlank()) {
                    error = "API 地址还没填"
                    return@launch
                }
                if (key == null) {
                    error = "API Key 还没保存，先填好 Key 点保存，或直接在输入框里填 Key"
                    return@launch
                }
                val models = AiClient.listModels(base, key)
                if (models.isEmpty()) {
                    error = "服务商没返回模型列表（可能不支持该接口），直接手填模型名即可"
                    return@launch
                }
                pickerTitle = when (target) {
                    ModelPickerTarget.MAIN -> "选择主力模型"
                    ModelPickerTarget.NOTE -> "选择讲解模型"
                    ModelPickerTarget.VISION -> "选择视觉模型"
                    ModelPickerTarget.VISION2 -> "选择备用视觉模型"
                }
                pickerTarget = target
                pickerQuery = ""
                pickerModels = models
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                error = "${e.message}（也可以直接手填模型名）"
            } catch (e: Exception) {
                error = "拉取模型列表失败：${e.message ?: "未知错误"}"
            } finally {
                fetchingModels = false
            }
        }
    }

    /** 查找目标生效的地址与 Key：输入框未保存的新值优先，其次已存密文 */
    private suspend fun resolveEndpoint(target: ModelPickerTarget): Pair<String, String?> = when (target) {
        ModelPickerTarget.MAIN, ModelPickerTarget.NOTE ->
            baseUrl to (keyInput.takeIf { it.isNotBlank() } ?: repo.decryptKeyOrNull())
        ModelPickerTarget.VISION ->
            (visionBaseUrl.ifBlank { baseUrl }) to when {
                visionKeyInput.isNotBlank() -> visionKeyInput
                else -> repo.resolveVisionKeyOrNull() // 专属 Key 优先，其次主力 Key
            }
        ModelPickerTarget.VISION2 ->
            vision2BaseUrl to (vision2KeyInput.takeIf { it.isNotBlank() }
                ?: repo.decryptVision2KeyOrNull())
    }

    /** 选择框点选后回填目标字段并关闭 */
    fun pickModel(name: String) {
        when (pickerTarget) {
            ModelPickerTarget.MAIN -> model = name
            ModelPickerTarget.NOTE -> {
                noteModel = name
                noteFollowMain = false
            }
            ModelPickerTarget.VISION -> visionModel = name
            ModelPickerTarget.VISION2 -> vision2Model = name
        }
        pickerModels = null
        message = "已选择「$name」，记得点保存"
    }

    fun dismissPicker() {
        pickerModels = null
    }

    /** 讲解模型开关：开=跟随主力（清掉专属模型），关=启用下方专属输入框 */
    fun changeNoteFollowMain(follow: Boolean) {
        noteFollowMain = follow
        if (follow) noteModel = ""
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

    /** 清除视觉专属 Key（之后视觉回退用主力 Key） */
    fun clearVisionKey() {
        if (busy || testing) return
        viewModelScope.launch {
            try {
                repo.clearVisionKey()
                hasVisionKey = false
                visionKeyInput = ""
                message = "已清除视觉专属 Key，视觉转写回退用主力 Key"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "清除视觉 Key 失败：" + (e.message ?: "未知错误")
            }
        }
    }

    /** 清除备用视觉 Key（三键缺一即退出轮转） */
    fun clearVision2Key() {
        if (busy || testing) return
        viewModelScope.launch {
            try {
                repo.clearVision2Key()
                hasVision2Key = false
                vision2KeyInput = ""
                message = "已清除备用视觉 Key，备用组退出轮转"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "清除备用视觉 Key 失败：" + (e.message ?: "未知错误")
            }
        }
    }

    /** 视觉转写开关（即点即存）：开了→队列立即拿到配置开跑；关了→Worker 空转退避，不烧钱 */
    fun changeVisionEnabled(enabled: Boolean) {
        if (enabled == visionEnabled) return
        visionEnabled = enabled
        viewModelScope.launch {
            try {
                repo.saveVision(enabled)
                val app = getApplication<StudyApp>()
                VisionScheduler.reenqueueAllPending(app.database, app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "保存视觉开关失败：${e.message ?: "未知错误"}"
            }
        }
    }

    /**
     * 下载 OCR 模型（P6b S5）：GitHub Release 三件套 ~21MB → filesDir/ocr_models/，
     * SHA-256 校验；令牌用设置页存的 GitHub 只读令牌。下载中禁止重复触发。
     */
    fun downloadOcrModels() {
        if (ocrDownloadPct != null) return
        ocrDownloadPct = 0
        viewModelScope.launch {
            try {
                val token = repo.decryptGithubTokenOrNull()
                withContext(Dispatchers.IO) {
                    OcrModelDownloader.downloadModels(
                        token = token,
                        modelsDir = OcrModelStore.modelsDir(getApplication<StudyApp>()),
                        onProgress = { done, total, pct ->
                            ocrDownloadPct = (((done - 1) * 100 + pct) / total).coerceIn(0, 100)
                        },
                    )
                }
                ocrModelsReady = OcrModelStore.modelsPresent(getApplication<StudyApp>())
                message = "识别模型下载完成，现在可以导入扫描书了"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "模型下载失败：${e.message ?: "未知错误"}"
            } finally {
                ocrDownloadPct = null
            }
        }
    }

    /** 扫描书本地识别开关（即点即存）：下一次导入 PDF 时生效 */
    fun changeOcrEnabled(enabled: Boolean) {
        if (enabled == ocrEnabled) return
        ocrEnabled = enabled
        viewModelScope.launch {
            try {
                repo.saveOcrEnabled(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "保存扫描识别开关失败：${e.message ?: "未知错误"}"
            }
        }
    }
}
