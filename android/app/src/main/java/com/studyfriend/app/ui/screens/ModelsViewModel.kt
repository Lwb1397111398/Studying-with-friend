package com.studyfriend.app.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.LlmAssign
import com.studyfriend.app.data.LlmProfile
import com.studyfriend.app.data.LlmRole
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.AiException
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.importer.ocr.OcrModelDownloader
import com.studyfriend.app.data.importer.ocr.OcrModelStore
import com.studyfriend.app.data.vision.VisionScheduler
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型页（2026-11-15 多模型轮）：档案（一家服务商一套地址/Key/模型）+ 按任务指派 +
 * 本地 OCR。档案可建多个，四个任务角色（主力/讲解/视觉/备用视觉）各自指派档案；
 * 讲解/视觉未指派跟随主力，备用视觉未指派不启用。
 */
class ModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository((app as StudyApp).database, (app as StudyApp).secretStore)

    // ---- 档案列表与分配（loadLlm 快照）----
    var profiles by mutableStateOf<List<LlmProfile>>(emptyList())
        private set
    var keyIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var assign by mutableStateOf(LlmAssign())
        private set

    // ---- 档案编辑弹层：null = 关闭 ----
    var editor by mutableStateOf<ProfileEditor?>(null)
        private set
    var editorBusy by mutableStateOf(false)
        private set
    var editorTesting by mutableStateOf(false)
        private set
    var editorLiveDelta by mutableStateOf("")
        private set
    var editorError by mutableStateOf<String?>(null)
        private set

    // ---- 「查找模型」弹窗（编辑器内）：非 null = 弹选择框 ----
    var pickerModels by mutableStateOf<List<String>?>(null)
        private set
    var pickerQuery by mutableStateOf("")
    var fetchingModels by mutableStateOf(false)
        private set

    // ---- 指派选择弹窗：非 null = 正在为该角色选档案 ----
    var rolePicker by mutableStateOf<LlmRole?>(null)
        private set

    fun openRolePicker(role: LlmRole) {
        rolePicker = role
    }

    fun closeRolePicker() {
        rolePicker = null
    }

    // ---- 视觉转写开关（即点即存）----
    var visionEnabled by mutableStateOf(true)
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
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var testJob: Job? = null

    init {
        refresh()
    }

    /** 重新读库（档案保存/删除/指派后调用；进页 init 也走这里） */
    fun refresh() {
        viewModelScope.launch {
            try {
                val s = repo.loadLlm()
                profiles = s.profiles
                keyIds = s.keyIds
                assign = s.assign
                visionEnabled = repo.load().visionEnabled
                ocrEnabled = repo.load().ocrEnabled
                ocrModelsReady = OcrModelStore.modelsPresent(getApplication<StudyApp>())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "读取模型配置失败：${e.message ?: "未知错误"}"
            }
        }
    }

    // ============ 档案编辑 ============

    /** 打开编辑弹层：p = null 新建（预填默认地址/模型），否则编辑既有档案 */
    fun openEditor(p: LlmProfile?) {
        editorError = null
        editorLiveDelta = ""
        editor = if (p == null) {
            ProfileEditor(
                id = "",
                name = "档案 ${profiles.size + 1}",
                baseUrl = SettingsRepository.DEFAULT_BASE,
                model = SettingsRepository.DEFAULT_MODEL,
                tempText = "0.3",
                keyInput = "",
                hasKey = false,
                isNew = true,
            )
        } else {
            ProfileEditor(
                id = p.id,
                name = p.name,
                baseUrl = p.baseUrl,
                model = p.model,
                tempText = p.temperature.toString(),
                effort = p.reasoningEffort,
                keyInput = "",
                hasKey = keyIds.contains(p.id),
                isNew = false,
            )
        }
    }

    fun closeEditor() {
        if (editorTesting) testJob?.cancel()
        editor = null
        editorError = null
        editorLiveDelta = ""
    }

    /** 编辑器字段统一更新入口（UI 输入框走这里） */
    fun updateEditor(transform: (ProfileEditor) -> ProfileEditor) {
        editor = editor?.let(transform)
    }

    /** 保存档案（新建或覆盖；Key 只在输入框非空时更新，不清旧值） */
    fun saveEditor() {
        val ed = editor ?: return
        if (editorBusy) return
        val base = ed.baseUrl.trim()
        val model = ed.model.trim()
        if (base.isBlank()) {
            editorError = "API 地址还没填"
            return
        }
        if (model.isBlank()) {
            editorError = "模型名还没填（可点「查找」从服务商拉列表）"
            return
        }
        viewModelScope.launch {
            editorBusy = true
            editorError = null
            try {
                val temp = AiClient.parseTemperature(ed.tempText)
                val profile = LlmProfile(
                    id = ed.id.ifBlank { UUID.randomUUID().toString().substring(0, 8) },
                    name = ed.name.trim().ifBlank { "档案" },
                    baseUrl = base,
                    model = model,
                    temperature = temp,
                    reasoningEffort = ed.effort,
                )
                repo.saveProfile(profile, ed.keyInput.takeIf { it.isNotBlank() })
                editor = null
                refresh()
                message = if (ed.isNew) "已添加档案「${profile.name}」" else "已保存「${profile.name}」"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editorError = "保存失败：${e.message ?: "未知错误"}"
            } finally {
                editorBusy = false
            }
        }
    }

    /** 删除档案（分配自动回收：主力回退第一档，讲解/视觉回跟主力，备用回不启用） */
    fun deleteEditorProfile() {
        val ed = editor ?: return
        if (editorBusy || ed.isNew) return
        if (profiles.size <= 1) {
            editorError = "至少保留一个档案"
            return
        }
        viewModelScope.launch {
            editorBusy = true
            try {
                repo.deleteProfile(ed.id)
                editor = null
                refresh()
                message = "已删除「${ed.name}」，原指派任务已回退默认"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editorError = "删除失败：${e.message ?: "未知错误"}"
            } finally {
                editorBusy = false
            }
        }
    }

    /** 清除档案已存的 Key（该档案的 AI 任务会提示未配置） */
    fun clearEditorKey() {
        val ed = editor ?: return
        if (editorBusy || !ed.hasKey) return
        viewModelScope.launch {
            try {
                repo.clearProfileKey(ed.id)
                updateEditor { it.copy(hasKey = false, keyInput = "") }
                refresh()
                message = "已清除「${ed.name}」的 Key"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editorError = "清除 Key 失败：${e.message ?: "未知错误"}"
            }
        }
    }

    /**
     * 编辑器内测试连接（流式 ping，实时预览）：优先用输入框里未保存的新 Key，
     * 避免拿旧 Key 配新地址误导。
     */
    fun testEditorConnection() {
        val ed = editor ?: return
        if (editorTesting || editorBusy) return
        testJob = viewModelScope.launch {
            editorTesting = true
            editorError = null
            editorLiveDelta = ""
            try {
                val key = ed.keyInput.takeIf { it.isNotBlank() }
                    ?: repo.decryptProfileKeyOrNull(ed.id).also { if (it == null) updateEditor { it.copy(hasKey = false) } }
                if (key == null) {
                    editorError = "API Key 已失效或还没保存，请填写后再测试"
                    return@launch
                }
                val start = System.currentTimeMillis()
                val reply = AiClient.chat(
                    ChatRequest(
                        baseUrl = ed.baseUrl.trim(),
                        apiKey = key,
                        model = ed.model.trim(),
                        temperature = AiClient.parseTemperature(ed.tempText),
                        // 推理型模型会先输出思考 token：预算太小只能收到空正文
                        maxTokens = 2048,
                        source = "连通测试",
                        reasoningEffort = ed.effort.takeIf { it.isNotBlank() },
                        messages = listOf(AiMessage("user", "ping")),
                    ),
                ) { delta -> editorLiveDelta += delta }
                editorError = null
                message = if (reply.isBlank()) {
                    "「${ed.name}」连接成功，但模型未返回正文（思考可能尚未结束）· ${System.currentTimeMillis() - start} ms"
                } else {
                    "「${ed.name}」连接成功 · ${reply.length} 字 · ${System.currentTimeMillis() - start} ms"
                }
            } catch (e: AiException) {
                editorError = e.message
            } catch (e: CancellationException) {
                // 用户取消，不算错误
            } catch (e: Exception) {
                editorError = "测试失败：${e.message ?: "未知错误"}"
            } finally {
                editorTesting = false
            }
        }
    }

    fun cancelTest() {
        testJob?.cancel()
    }

    // ============ 查找模型（编辑器内） ============

    /** 调服务商 GET /models 拉列表（地址/Key 用编辑器当前值，未保存的新 Key 优先），失败仍可手填 */
    fun fetchModels() {
        val ed = editor ?: return
        if (fetchingModels || editorBusy || editorTesting) return
        viewModelScope.launch {
            fetchingModels = true
            editorError = null
            try {
                val base = ed.baseUrl.trim()
                if (base.isBlank()) {
                    editorError = "API 地址还没填"
                    return@launch
                }
                val key = ed.keyInput.takeIf { it.isNotBlank() } ?: repo.decryptProfileKeyOrNull(ed.id)
                if (key == null) {
                    editorError = "API Key 还没保存，先填好 Key 点「保存」，或直接在输入框里填 Key"
                    return@launch
                }
                val models = AiClient.listModels(base, key)
                if (models.isEmpty()) {
                    editorError = "服务商没返回模型列表（可能不支持该接口），直接手填模型名即可"
                    return@launch
                }
                pickerQuery = ""
                pickerModels = models
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                editorError = "${e.message}（也可以直接手填模型名）"
            } catch (e: Exception) {
                editorError = "拉取模型列表失败：${e.message ?: "未知错误"}"
            } finally {
                fetchingModels = false
            }
        }
    }

    /** 选择框点选后回填编辑器模型名并关闭 */
    fun pickModel(name: String) {
        updateEditor { it.copy(model = name) }
        pickerModels = null
        message = "已选择「$name」，记得点保存"
    }

    fun dismissPicker() {
        pickerModels = null
    }

    // ============ 按任务指派（即点即存） ============

    /**
     * 指派档案给任务角色：profileId = null 表示「跟随主力」（VISION2 时 = 不启用）。
     * MAIN 不接受 null（回退第一个档案语义由 resolveLlm 兜底）。
     */
    fun assignRole(role: LlmRole, profileId: String?) {
        val newAssign = when (role) {
            LlmRole.MAIN -> assign.copy(main = profileId ?: "")
            LlmRole.NOTE -> assign.copy(note = profileId ?: "")
            LlmRole.VISION -> assign.copy(vision = profileId ?: "")
            LlmRole.VISION2 -> assign.copy(vision2 = profileId ?: "")
        }
        assign = newAssign
        viewModelScope.launch {
            try {
                repo.saveAssign(newAssign)
                message = "已更新指派，下次任务即生效"
                // 视觉指派变了：队列里待转写页立即按新配置重调度
                if (role == LlmRole.VISION || role == LlmRole.VISION2) {
                    val app = getApplication<StudyApp>()
                    VisionScheduler.reenqueueAllPending(app.database, app)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "保存指派失败：${e.message ?: "未知错误"}"
            }
        }
    }

    // ============ 视觉开关 / 本地 OCR ============

    /** 视觉转写开关（即点即存）：开了→队列立即拿到配置开跑；关了→Worker 空转退避，不烧钱 */
    fun changeVisionEnabled(enabled: Boolean) {
        if (enabled == visionEnabled) return
        visionEnabled = enabled
        viewModelScope.launch {
            try {
                repo.saveVisionEnabled(enabled)
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

/**
 * 档案编辑弹层状态（id 空 = 新建）。字段逐项 copy 更新（updateEditor），
 * keyInput 是输入框明文，只随「保存」加密落库。
 */
data class ProfileEditor(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val tempText: String,
    /** 思考强度："": 默认（不传）；low / medium / high（弹层四枚 chip 单选） */
    val effort: String = "",
    val keyInput: String,
    val hasKey: Boolean,
    val isNew: Boolean,
)

/** 角色中文标签（档案行徽标 + 指派弹层共用） */
fun LlmRole.label(): String = when (this) {
    LlmRole.MAIN -> "主力"
    LlmRole.NOTE -> "讲解"
    LlmRole.VISION -> "视觉"
    LlmRole.VISION2 -> "备用"
}

/**
 * 某档案当前被哪些角色使用（档案行徽标）：
 * MAIN 按「生效主力」算——assign.main 空/失效时第一档就是主力。
 */
fun rolesUsing(a: LlmAssign, profiles: List<LlmProfile>, profileId: String): List<LlmRole> {
    val mainEffective = a.main.takeIf { it.isNotBlank() && profiles.any { p -> p.id == it } }
        ?: profiles.firstOrNull()?.id
    return buildList {
        if (profileId == mainEffective) add(LlmRole.MAIN)
        if (a.note == profileId) add(LlmRole.NOTE)
        if (a.vision == profileId) add(LlmRole.VISION)
        if (a.vision2 == profileId) add(LlmRole.VISION2)
    }
}
