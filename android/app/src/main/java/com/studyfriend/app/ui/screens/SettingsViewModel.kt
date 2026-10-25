package com.studyfriend.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.BackupException
import com.studyfriend.app.data.BackupManager
import com.studyfriend.app.data.BackupSummary
import com.studyfriend.app.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 设置页（瘦身版）：模型类配置全部移入「模型」页（ModelsScreen），这里只剩
 * 讲解详略档位 + 应用更新（UpdateViewModel）+ 模型页入口的状态摘要
 * + 备份与恢复（数据安全轮）。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository((app as StudyApp).database, (app as StudyApp).secretStore)

    /** 讲解详略三档：即选即存 */
    var detailLevel by mutableStateOf("标准")
        private set

    /** 外观主题三态（功能补齐轮）：即选即存，MainActivity watch 键即时生效 */
    var themeMode by mutableStateOf(SettingsRepository.THEME_SYSTEM)
        private set

    /** 朗读语速（功能补齐轮正文听书）：0.5~2.0 倍，即拖即存（松手才落库由 UI 控制） */
    var ttsRate by mutableStateOf(SettingsRepository.DEFAULT_TTS_RATE)
        private set

    /** 复习到期提醒（功能补齐轮）：开关即点即存 + 同步注册/取消每日任务 */
    var reviewReminder by mutableStateOf(true)
        private set

    // —— 备份与恢复（数据安全轮）——
    private val backupManager: BackupManager by lazy {
        val app = getApplication<StudyApp>()
        BackupManager(app.database, app.filesDir)
    }

    /** 备份进行中（导出/恢复共用，按钮防重入） */
    var backupBusy by mutableStateOf(false)
        private set

    /** 最近一次备份操作的结果提示；null=无提示 */
    var backupMessage by mutableStateOf<String?>(null)
        private set
    var backupIsError by mutableStateOf(false)
        private set

    /** 待确认的恢复（预览成功后非空，确认框展示；confirmRestore 执行整库替换） */
    var pendingRestore by mutableStateOf<PendingRestore?>(null)
        private set

    /** 导出备份到 SAF 选定的位置 */
    fun exportBackup(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        backupMessage = null
        viewModelScope.launch {
            try {
                val app = getApplication<StudyApp>()
                val summary = app.contentResolver.openOutputStream(uri)?.use { backupManager.exportTo(it) }
                if (summary == null) {
                    backupIsError = true
                    backupMessage = "导出失败：无法写入所选位置"
                } else {
                    backupIsError = false
                    backupMessage = "已导出 ${summary.books} 本书、${summary.chapters} 章、" +
                        "${summary.paragraphs} 段正文（讲解 ${summary.paraNotes} 条、批注 ${summary.paraMemos} 条）"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backupIsError = true
                backupMessage = "导出失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                backupBusy = false
            }
        }
    }

    /** 恢复第一步：选文件后只解析校验（不动库），成功弹确认框 */
    fun previewRestore(uri: Uri) {
        backupMessage = null
        viewModelScope.launch {
            try {
                val app = getApplication<StudyApp>()
                val summary = app.contentResolver.openInputStream(uri)?.use { backupManager.peek(it) }
                if (summary == null) {
                    backupIsError = true
                    backupMessage = "恢复失败：无法读取所选文件"
                } else {
                    pendingRestore = PendingRestore(uri, summary)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                backupIsError = true
                backupMessage = e.message ?: "备份文件无效"
            } catch (e: Exception) {
                backupIsError = true
                backupMessage = "读取备份失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** 恢复第二步：确认框点「覆盖并恢复」后执行整库替换 */
    fun confirmRestore() {
        val pending = pendingRestore ?: return
        if (backupBusy) return
        backupBusy = true
        pendingRestore = null
        backupMessage = null
        viewModelScope.launch {
            try {
                val app = getApplication<StudyApp>()
                val summary = app.contentResolver.openInputStream(pending.uri)?.use { backupManager.restoreFrom(it) }
                if (summary == null) {
                    backupIsError = true
                    backupMessage = "恢复失败：无法读取所选文件"
                } else {
                    backupIsError = false
                    backupMessage = "已恢复 ${summary.books} 本书、${summary.chapters} 章、" +
                        "${summary.paragraphs} 段正文；AI Key 未受影响，如模型配置有变请到模型页核对"
                    // 恢复换掉了整套学习数据，回读设置页自身状态防止显示残留旧值
                    themeMode = repo.themeMode()
                    ttsRate = repo.ttsRate()
                    detailLevel = repo.detailLevel()
                    reviewReminder = repo.reviewReminderEnabled()
                    ttsEngine = repo.ttsEngineMode()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                backupIsError = true
                backupMessage = "恢复失败：${e.message}（当前数据未被改动）"
            } catch (e: Exception) {
                backupIsError = true
                backupMessage = "恢复失败：${e.message ?: e.javaClass.simpleName}（当前数据未被改动）"
            } finally {
                backupBusy = false
            }
        }
    }

    /** 取消恢复（确认框 dismiss） */
    fun cancelRestore() {
        pendingRestore = null
    }

    /** 待确认恢复：所选文件 + 预览统计 */
    data class PendingRestore(val uri: Uri, val summary: BackupSummary)

    // —— 诊断日志导出（数据安全轮）——

    /** 导出诊断日志到 SAF 选定位置（最近 7 天合并为一个 txt，发给 AI 排障用） */
    fun exportDiag(uri: Uri) {
        viewModelScope.launch {
            try {
                val app = getApplication<StudyApp>()
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.studyfriend.app.data.DiagLog.export()
                }
                if (text.isBlank()) {
                    backupIsError = true
                    backupMessage = "还没有诊断日志可导出（正常使用中未发生崩溃或 AI 任务失败）"
                } else {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    backupIsError = false
                    backupMessage = "诊断日志已导出，可把文件发给 AI 帮你排障"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backupIsError = true
                backupMessage = "导出诊断日志失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** 朗读引擎（TTS 双模式）：即点即存，下次朗读生效 */
    var ttsEngine by mutableStateOf(SettingsRepository.TTS_ENGINE_SYSTEM)
        private set

    /** 离线朗读模型就绪与已下字节（设置页下载管理区展示用，refreshTtsModel 刷新） */
    var ttsModelReady by mutableStateOf(false)
        private set
    var ttsModelBytes by mutableStateOf(0L)
        private set

    /** 模型入口摘要：主力模型名与 Key/讲解/视觉的生效状态，进设置页时刷新 */
    var mainModel by mutableStateOf("")
        private set
    var keyReady by mutableStateOf(false)
        private set
    var noteSummary by mutableStateOf("跟随主力")
        private set
    var visionSummary by mutableStateOf("关")
        private set

    init {
        refreshSummary()
        viewModelScope.launch { detailLevel = repo.detailLevel() }
        viewModelScope.launch { themeMode = repo.themeMode() }
        viewModelScope.launch { ttsRate = repo.ttsRate() }
        viewModelScope.launch { reviewReminder = repo.reviewReminderEnabled() }
        viewModelScope.launch { ttsEngine = repo.ttsEngineMode() }
        refreshTtsModel()
        // 下载结束（成功/失败/中断）后刷新就绪状态与已下字节
        viewModelScope.launch {
            com.studyfriend.app.data.tts.TtsModelManager.state.collect {
                if (it !is com.studyfriend.app.data.tts.TtsModelManager.DownloadState.Running) refreshTtsModel()
            }
        }
    }

    /** 从模型页改完配置返回设置页时刷新摘要 */
    fun refreshSummary() {
        viewModelScope.launch {
            try {
                val s = repo.load()
                mainModel = s.model
                keyReady = s.hasKey
                noteSummary = if (s.noteModel.isBlank()) "跟随主力" else s.noteModel
                visionSummary = if (s.visionEnabled) s.visionModel else "已关闭"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 摘要只是展示，读失败静默保留旧值（正常路径 load 不会抛）
            }
        }
    }

    /** 讲解详略三档（总计划 §1 目标 7）：即点即存，独立于保存按钮 */
    fun chooseDetailLevel(level: String) {
        if (level !in SettingsRepository.DETAIL_LEVELS || level == detailLevel) return
        detailLevel = level
        viewModelScope.launch {
            try {
                repo.saveDetailLevel(level)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 档位保存失败不阻塞界面；下拉重进会回读库里的真实值
            }
        }
    }

    /** 外观主题三态：即点即存 */
    fun chooseThemeMode(mode: String) {
        if (mode !in SettingsRepository.THEME_MODES || mode == themeMode) return
        themeMode = mode
        viewModelScope.launch {
            try {
                repo.saveThemeMode(mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 同讲解详略：保存失败静默，重进回读真实值
            }
        }
    }

    /** 朗读语速：拖动结束落库（UI 侧 onChange 更新内存态、onValueChangeFinished 调本方法） */
    fun chooseTtsRate(rate: Float) {
        val clamped = rate.coerceIn(SettingsRepository.TTS_RATE_MIN, SettingsRepository.TTS_RATE_MAX)
        ttsRate = clamped
        viewModelScope.launch {
            try {
                repo.saveTtsRate(clamped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 保存失败静默，重进回读真实值
            }
        }
    }

    /** 朗读引擎：即点即存（手机自带 / 离线模型），下次朗读生效 */
    fun chooseTtsEngine(mode: String) {
        if (mode != SettingsRepository.TTS_ENGINE_SYSTEM && mode != SettingsRepository.TTS_ENGINE_LOCAL) return
        ttsEngine = mode
        viewModelScope.launch {
            try {
                repo.saveTtsEngineMode(mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 保存失败静默，重进回读真实值
            }
        }
    }

    /** 刷新离线朗读模型的就绪状态与已下字节 */
    fun refreshTtsModel() {
        val app = getApplication<StudyApp>()
        ttsModelReady = com.studyfriend.app.data.tts.TtsModelManager.isTtsReady(app)
        ttsModelBytes = com.studyfriend.app.data.tts.TtsModelManager.downloadedBytes(app)
    }

    /** 开始下载离线朗读模型（App 级任务，离开设置页继续；重复点幂等） */
    fun startModelDownload() {
        com.studyfriend.app.data.tts.TtsModelManager.startDownload(getApplication<StudyApp>())
    }

    /** 删除离线朗读模型（可再生数据，UI 二次确认后调用） */
    fun deleteTtsModel() {
        com.studyfriend.app.data.tts.TtsModelManager.deleteTtsModel(getApplication<StudyApp>())
        refreshTtsModel()
    }

    /** 复习到期提醒：开关即点即存 + 同步注册/取消每日查询任务 */
    fun chooseReviewReminder(enabled: Boolean) {
        if (enabled == reviewReminder) return
        reviewReminder = enabled
        val app = getApplication<StudyApp>()
        viewModelScope.launch {
            try {
                repo.saveReviewReminderEnabled(enabled)
                // runCatching：WorkManager 未初始化的环境（测试沙盒）静默跳过
                runCatching {
                    if (enabled) {
                        com.studyfriend.app.data.study.ReviewReminderWorker.schedule(app)
                    } else {
                        com.studyfriend.app.data.study.ReviewReminderWorker.cancel(app)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 保存失败静默，重进回读真实值
            }
        }
    }
}
