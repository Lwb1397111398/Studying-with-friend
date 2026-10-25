package com.studyfriend.app.data.study

import android.content.Context
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiGate
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 总结包生成四态（计划 M5 §2.5）：Streaming.partial 为本次尝试的累计 raw 输出 */
sealed interface SummaryRunState {
    data object Idle : SummaryRunState
    data class Queued(val chapterId: Long) : SummaryRunState
    data class Streaming(val chapterId: Long, val partial: String) : SummaryRunState
    data class Succeeded(val chapterId: Long, val message: String) : SummaryRunState
    data class Failed(val chapterId: Long, val message: String, val keyIssue: Boolean = false) : SummaryRunState
}

/**
 * 总结包运行器：job/gate/迟到异常纪律单源见 [AiTaskRunner]（与讲解/粗读/总览同纪律）。
 */
class SummaryRunner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatTextFn: ChatTextFn,
    scope: CoroutineScope,
    gate: AiGate = AiGate(),
) : AiTaskRunner<SummaryRunState>(gate, scope) {
    /** 生产便捷构造：默认注入 AiClient 流式实现（测试传假 ChatTextFn） */
    constructor(
        db: StudyDatabase,
        context: Context,
        settings: SettingsRepository,
        scope: CoroutineScope,
        gate: AiGate = AiGate(),
    ) : this(db, context, settings, AiClientChatTextFn, scope, gate)

    override val gateLabel = "总结"

    private val _state = MutableStateFlow<SummaryRunState>(SummaryRunState.Idle)
    val state: StateFlow<SummaryRunState> = _state.asStateFlow()
    override fun setState(state: SummaryRunState) {
        _state.value = state
    }
    override fun idleState(): SummaryRunState = SummaryRunState.Idle
    override fun queuedState(id: Long): SummaryRunState = SummaryRunState.Queued(id)
    override fun failureState(id: Long, message: String, keyIssue: Boolean) =
        SummaryRunState.Failed(id, message, keyIssue)

    fun start(chapterId: Long) = launchTask(chapterId, "总结生成失败") {
        val planner = SummaryPlanner(db, context, settings, chatTextFn)
        // partial = 本次尝试累计 raw（planner 负责累积并节流，重试时归零）
        val outcome = planner.run(chapterId) { partial ->
            setState(SummaryRunState.Streaming(chapterId, partial))
        }
        SummaryRunState.Succeeded(chapterId, "总结包已生成（自测 ${outcome.questionCount} 题）")
    }
}
