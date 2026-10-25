package com.studyfriend.app.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 设置页（瘦身版）：模型类配置全部移入「模型」页（ModelsScreen），这里只剩
 * 讲解详略档位 + 应用更新（UpdateViewModel）+ 模型页入口的状态摘要。
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
