package com.studyfriend.app.data.study

import android.content.Context
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiGate
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** 总结包生成四态（计划 M5 §2.5）：Streaming.partial 为本次尝试的累计 raw 输出 */
sealed interface SummaryRunState {
    data object Idle : SummaryRunState
    data class Streaming(val chapterId: Long, val partial: String) : SummaryRunState
    data class Succeeded(val chapterId: Long, val message: String) : SummaryRunState
    data class Failed(val chapterId: Long, val message: String, val keyIssue: Boolean = false) : SummaryRunState
}

/**
 * 总结包运行器：join 模式自替换（与 RoughReadRunner/NoteRunner 同纪律）——
 * start 先取消旧 job 并 join 之后再抢 AiGate；stop 只取消不清 job 引用。
 * CancellationException 只在本 job 仍是现任时清回 Idle。
 */
class SummaryRunner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatTextFn: ChatTextFn,
    private val scope: CoroutineScope,
    val gate: AiGate = AiGate(),
) {
    private val _state = MutableStateFlow<SummaryRunState>(SummaryRunState.Idle)
    val state: StateFlow<SummaryRunState> = _state.asStateFlow()
    private var job: Job? = null

    /** 生产便捷构造：默认注入 AiClient 流式实现（测试传假 ChatTextFn） */
    constructor(
        db: StudyDatabase,
        context: Context,
        settings: SettingsRepository,
        scope: CoroutineScope,
        gate: AiGate = AiGate(),
    ) : this(db, context, settings, AiClientChatTextFn, scope, gate)

    fun start(chapterId: Long) {
        val old = job
        old?.cancel()
        job = scope.launch {
            old?.join()
            if (!gate.tryBegin("总结")) {
                _state.value = SummaryRunState.Failed(chapterId, "另一项 AI 任务进行中，请等它完成")
                return@launch
            }
            try {
                val planner = SummaryPlanner(db, context, settings, chatTextFn)
                // partial = 本次尝试累计 raw（planner 负责累积，重试时归零）
                val outcome = planner.run(chapterId) { partial ->
                    _state.value = SummaryRunState.Streaming(chapterId, partial)
                }
                _state.value = SummaryRunState.Succeeded(chapterId, "总结包已生成（自测 ${outcome.questionCount} 题）")
            } catch (e: CancellationException) {
                if (coroutineContext[Job] === job) _state.value = SummaryRunState.Idle
                throw e
            } catch (e: Exception) {
                // 只在本 job 仍是现任时覆盖状态：stop/start 替换后旧 job 的迟到异常不得刷掉新状态
                if (coroutineContext[Job] === job) {
                    _state.value = SummaryRunState.Failed(chapterId, e.message ?: "总结生成失败", keyIssue = e is MissingKeyException)
                }
            } finally {
                gate.end()
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.value = SummaryRunState.Idle
    }
}
