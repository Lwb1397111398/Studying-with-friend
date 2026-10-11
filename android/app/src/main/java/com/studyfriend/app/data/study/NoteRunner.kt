package com.studyfriend.app.data.study

import android.content.Context
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiGate
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 讲解运行状态（与 RoughRunState 同构；unitCount=本章讲解条数） */
sealed interface NoteRunState {
    data object Idle : NoteRunState

    data class Running(
        val chapterId: Long,
        val done: Int,
        val total: Int,
    ) : NoteRunState

    data class Succeeded(
        val chapterId: Long,
        val message: String,
        val unitCount: Int,
    ) : NoteRunState

    /** 部分单元完成即中断：已完成单元已落库，续跑按缓存命中跳过 */
    data class Interrupted(
        val chapterId: Long,
        val message: String,
    ) : NoteRunState

    /** 未跑起来（无 Key/另一任务占用等；keyIssue=true 时 UI 引导去设置） */
    data class Failed(
        val chapterId: Long,
        val message: String,
        val keyIssue: Boolean = false,
    ) : NoteRunState
}

/**
 * 讲解批量任务管理（计划 M4b §2.7）：应用级单例 + AiGate 全局互斥；
 * job/gate/迟到异常纪律单源见 [AiTaskRunner]。
 */
class NoteRunner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatTextFn: ChatTextFn,
    scope: CoroutineScope,
    gate: AiGate = AiGate(),
) : AiTaskRunner<NoteRunState>(gate, scope) {
    constructor(
        db: StudyDatabase,
        context: Context,
        settings: SettingsRepository,
    ) : this(db, context, settings, AiClientChatTextFn, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    override val gateLabel = "讲解"

    private val _state = MutableStateFlow<NoteRunState>(NoteRunState.Idle)
    val state: StateFlow<NoteRunState> = _state.asStateFlow()
    override fun setState(state: NoteRunState) {
        _state.value = state
    }
    override fun idleState(): NoteRunState = NoteRunState.Idle
    override fun failureState(id: Long, message: String, keyIssue: Boolean) =
        NoteRunState.Failed(id, message, keyIssue)

    /** 启动（或替换）一章的批量讲解。force=true 全部重生成（忽略缓存）。 */
    fun start(chapterId: Long, force: Boolean = false) = launchTask(chapterId, "讲解启动失败") {
        setState(NoteRunState.Running(chapterId, 0, 0))
        val outcome = NotePlanner(db, context, settings, chatTextFn)
            .run(chapterId, force = force) { done, total ->
                setState(NoteRunState.Running(chapterId, done, total))
            }
        finishState(chapterId, outcome)
    }

    private fun finishState(chapterId: Long, outcome: NoteOutcome): NoteRunState {
        val at = outcome.interruptedAtUnit
        if (at != null) {
            return NoteRunState.Interrupted(
                chapterId,
                "已完成 ${outcome.doneUnits}/${outcome.totalUnits} 条讲解，中断于第 $at 条：${outcome.interruptedReason ?: "未知错误"}。" +
                    "已完成部分已保存，稍后可继续",
            )
        }
        val message = if (outcome.generated == 0) {
            "本章讲解已是最新（共 ${outcome.totalUnits} 条）"
        } else {
            "讲解完成，新生成 ${outcome.generated}/${outcome.totalUnits} 条"
        }
        return NoteRunState.Succeeded(chapterId, message, outcome.totalUnits)
    }
}
