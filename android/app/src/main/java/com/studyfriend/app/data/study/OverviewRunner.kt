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

/** 全书总览生成四态（计划 M6 §2.6，与 SummaryRunner 同纪律）：Streaming.partial 为本次尝试累计 raw */
sealed interface OverviewRunState {
    data object Idle : OverviewRunState
    data class Streaming(val bookId: Long, val partial: String) : OverviewRunState
    data class Succeeded(val bookId: Long, val message: String) : OverviewRunState
    data class Failed(val bookId: Long, val message: String, val keyIssue: Boolean = false) : OverviewRunState
}

/**
 * 全书总览运行器：join 模式自替换 + 共享 AiGate（粗读/讲解/总结/总览四任务互斥）——
 * start 先取消旧 job 并 join 之后再抢 gate；stop 只取消不清 job 引用。
 * CancellationException/Exception 都只在本 job 仍是现任时覆盖状态。
 */
class OverviewRunner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatTextFn: ChatTextFn,
    private val scope: CoroutineScope,
    val gate: AiGate = AiGate(),
) {
    private val _state = MutableStateFlow<OverviewRunState>(OverviewRunState.Idle)
    val state: StateFlow<OverviewRunState> = _state.asStateFlow()
    private var job: Job? = null

    /** 生产便捷构造：默认注入 AiClient 流式实现（测试传假 ChatTextFn） */
    constructor(
        db: StudyDatabase,
        context: Context,
        settings: SettingsRepository,
        scope: CoroutineScope,
        gate: AiGate = AiGate(),
    ) : this(db, context, settings, AiClientChatTextFn, scope, gate)

    fun start(bookId: Long) {
        val old = job
        old?.cancel()
        job = scope.launch {
            old?.join()
            if (!gate.tryBegin("总览")) {
                _state.value = OverviewRunState.Failed(bookId, "另一项 AI 任务进行中，请等它完成")
                return@launch
            }
            try {
                val planner = OverviewPlanner(db, context, settings, chatTextFn)
                planner.run(bookId) { partial ->
                    _state.value = OverviewRunState.Streaming(bookId, partial)
                }
                _state.value = OverviewRunState.Succeeded(bookId, "全书总览已生成")
            } catch (e: CancellationException) {
                if (coroutineContext[Job] === job) _state.value = OverviewRunState.Idle
                throw e
            } catch (e: Exception) {
                // 只在本 job 仍是现任时覆盖状态：替换后旧 job 的迟到异常不得刷掉新状态
                if (coroutineContext[Job] === job) {
                    _state.value = OverviewRunState.Failed(bookId, e.message ?: "总览生成失败", keyIssue = e is MissingKeyException)
                }
            } finally {
                gate.end()
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.value = OverviewRunState.Idle
    }
}
