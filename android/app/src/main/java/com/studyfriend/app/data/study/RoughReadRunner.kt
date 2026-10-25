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

/** 粗读运行状态（Runner 全局唯一；UI 订阅渲染按钮态/进度/结果条） */
sealed interface RoughRunState {
    data object Idle : RoughRunState

    /** 排队等待中（体验增量轮）：前面有别的 AI 任务在跑，等它释放全局锁 */
    data class Queued(val chapterId: Long) : RoughRunState

    data class Running(
        val chapterId: Long,
        val done: Int,
        val total: Int,
    ) : RoughRunState

    /** 全部块完成（含归并降级/保留旧值这两种"成功但有提示"形态；degraded=要点是降级值，可重试归并） */
    data class Succeeded(
        val chapterId: Long,
        val message: String,
        val unitCount: Int,
        val degraded: Boolean = false,
    ) : RoughRunState

    /** 部分块完成即中断：已完成块已落库，续跑只处理未标块 */
    data class Interrupted(
        val chapterId: Long,
        val message: String,
    ) : RoughRunState

    /** 未跑起来（章不存在/无段落/无 Key 等；keyIssue=true 时 UI 引导去设置） */
    data class Failed(
        val chapterId: Long,
        val message: String,
        val keyIssue: Boolean = false,
    ) : RoughRunState
}

/**
 * 粗读任务管理（计划 M4a §2.6）：应用级单例，一次只跑一章；任务跑在应用级
 * scope 里，退出页面、返回书架都不中断。结果写入 chapter/paragraphs 库。
 * job/gate/迟到异常纪律单源见 [AiTaskRunner]。
 */
class RoughReadRunner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatJsonFn: ChatJsonFn,
    scope: CoroutineScope,
    gate: AiGate = AiGate(),
) : AiTaskRunner<RoughRunState>(gate, scope) {
    constructor(
        db: StudyDatabase,
        context: Context,
        settings: SettingsRepository,
    ) : this(db, context, settings, AiClientChatJsonFn, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    override val gateLabel = "粗读"

    private val _state = MutableStateFlow<RoughRunState>(RoughRunState.Idle)
    val state: StateFlow<RoughRunState> = _state.asStateFlow()
    override fun setState(state: RoughRunState) {
        _state.value = state
    }
    override fun idleState(): RoughRunState = RoughRunState.Idle
    override fun queuedState(id: Long): RoughRunState = RoughRunState.Queued(id)
    override fun failureState(id: Long, message: String, keyIssue: Boolean) =
        RoughRunState.Failed(id, message, keyIssue)

    /**
     * 启动（或替换）一章的粗读。force=true 重标注全部块（忽略断点与既有标注）。
     */
    fun start(chapterId: Long, force: Boolean = false) = launchTask(chapterId, "粗读启动失败") {
        setState(RoughRunState.Running(chapterId, 0, 0))
        val outcome = RoughReadPlanner(db, context, settings, chatJsonFn)
            .run(chapterId, force = force) { done, total ->
                setState(RoughRunState.Running(chapterId, done, total))
            }
        finishState(chapterId, outcome)
    }

    /** 段落已全部标注但 gist 为空（上次归并前中断）：只补跑归并，不发块请求 */
    fun mergeOnly(chapterId: Long) = launchTask(chapterId, "归并失败") {
        setState(RoughRunState.Running(chapterId, 0, 0))
        val outcome = RoughReadPlanner(db, context, settings, chatJsonFn).runMergeOnly(chapterId)
        finishState(chapterId, outcome)
    }

    private fun finishState(chapterId: Long, outcome: RoughReadOutcome): RoughRunState {
        val at = outcome.interruptedAtBlock
        if (at != null) {
            return RoughRunState.Interrupted(
                chapterId,
                "已完成 ${outcome.doneBlocks}/${outcome.totalBlocks} 块，中断于第 $at 块：${outcome.interruptedReason ?: "未知错误"}。" +
                    "已完成部分已保存，稍后可从断点继续",
            )
        }
        val base = "粗读完成，共 ${outcome.unitCount} 个讲解单元"
        val message = when {
            outcome.mergeDegraded -> "$base；要点归并失败，已用分块要点拼接替代"
            outcome.mergeKeptOld -> "$base；归并失败，保留了原有本章要点"
            else -> base
        } + if (outcome.unitCount > 10) "。讲解单元较多，生成讲解会更久" else ""
        return RoughRunState.Succeeded(
            chapterId,
            message,
            outcome.unitCount,
            degraded = outcome.mergeDegraded || outcome.mergeKeptOld,
        )
    }
}
