package com.studyfriend.app.data.study

import android.content.Context
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiGate
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 全书总览生成四态（计划 M6 §2.6，与 SummaryRunState 同构）：Streaming.partial 为本次尝试累计 raw */
sealed interface OverviewRunState {
    data object Idle : OverviewRunState
    data class Streaming(val bookId: Long, val partial: String) : OverviewRunState
    data class Succeeded(val bookId: Long, val message: String) : OverviewRunState
    data class Failed(val bookId: Long, val message: String, val keyIssue: Boolean = false) : OverviewRunState
}

/**
 * 全书总览运行器：共享 AiGate（粗读/讲解/总结/总览四任务互斥）；
 * job/gate/迟到异常纪律单源见 [AiTaskRunner]。
 */
class OverviewRunner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatTextFn: ChatTextFn,
    scope: CoroutineScope,
    gate: AiGate = AiGate(),
) : AiTaskRunner<OverviewRunState>(gate, scope) {
    /** 生产便捷构造：默认注入 AiClient 流式实现（测试传假 ChatTextFn） */
    constructor(
        db: StudyDatabase,
        context: Context,
        settings: SettingsRepository,
        scope: CoroutineScope,
        gate: AiGate = AiGate(),
    ) : this(db, context, settings, AiClientChatTextFn, scope, gate)

    override val gateLabel = "总览"

    private val _state = MutableStateFlow<OverviewRunState>(OverviewRunState.Idle)
    val state: StateFlow<OverviewRunState> = _state.asStateFlow()
    override fun setState(state: OverviewRunState) {
        _state.value = state
    }
    override fun idleState(): OverviewRunState = OverviewRunState.Idle
    override fun failureState(id: Long, message: String, keyIssue: Boolean) =
        OverviewRunState.Failed(id, message, keyIssue)

    fun start(bookId: Long) = launchTask(bookId, "总览生成失败") {
        val planner = OverviewPlanner(db, context, settings, chatTextFn)
        planner.run(bookId) { partial ->
            setState(OverviewRunState.Streaming(bookId, partial))
        }
        OverviewRunState.Succeeded(bookId, "全书总览已生成")
    }
}
