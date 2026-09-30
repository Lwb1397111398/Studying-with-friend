package com.studyfriend.app.data.study

import android.content.Context
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiGate
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

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
 * start 捕获旧 job 先 cancel、新协程 join 后再 tryBegin（§2.6 自替换竞态修复）。
 */
class NoteRunner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatTextFn: ChatTextFn,
    private val scope: CoroutineScope,
    private val gate: AiGate = AiGate(),
) {
    constructor(
        db: StudyDatabase,
        context: Context,
        settings: SettingsRepository,
    ) : this(db, context, settings, AiClientChatTextFn, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _state = MutableStateFlow<NoteRunState>(NoteRunState.Idle)
    val state: StateFlow<NoteRunState> = _state.asStateFlow()

    private var job: Job? = null

    /** 启动（或替换）一章的批量讲解。force=true 全部重生成（忽略缓存）。 */
    fun start(chapterId: Long, force: Boolean = false) {
        val old = job
        old?.cancel()
        job = scope.launch {
            old?.join() // 等旧 job 完全退出（含其 finally gate.end()），自替换才能重获 gate
            if (!gate.tryBegin("讲解")) {
                _state.value = NoteRunState.Failed(chapterId, "另一项 AI 任务进行中，请等待完成")
                return@launch
            }
            try {
                _state.value = NoteRunState.Running(chapterId, 0, 0)
                val outcome = NotePlanner(db, context, settings, chatTextFn)
                    .run(chapterId, force = force) { done, total ->
                        _state.value = NoteRunState.Running(chapterId, done, total)
                    }
                _state.value = finishState(chapterId, outcome)
            } catch (e: CancellationException) {
                // 被新 start 替换：新任务自己会写 Running，这里不要把状态抹成 Idle
                if (coroutineContext[Job] === job) _state.value = NoteRunState.Idle
                throw e
            } catch (e: Exception) {
                // 只在本 job 仍是现任时覆盖状态：stop/start 替换后旧 job 的迟到异常不得刷掉新状态
                if (coroutineContext[Job] === job) {
                    _state.value = NoteRunState.Failed(
                        chapterId,
                        e.message ?: "讲解启动失败",
                        keyIssue = e is MissingKeyException,
                    )
                }
            } finally {
                gate.end()
            }
        }
    }

    /** 用户主动停止：已完成单元保留（等于断点），状态回 Idle；job 引用保留供 start join */
    fun stop() {
        job?.cancel()
        _state.value = NoteRunState.Idle
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
