package com.studyfriend.app.data.study

import android.content.Context
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiGate
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 全书一键分析运行状态（同 AiTaskRunner 家族五态；Running 携带当前章/阶段/子进度） */
sealed interface BookAnalysisState {
    data object Idle : BookAnalysisState

    /** 排队等待中：前面有别的 AI 任务在跑 */
    data class Queued(val bookId: Long) : BookAnalysisState

    /** doneChapters 从 0 计（当前章还没跑完）；phase=粗读/讲解；subDone/subTotal=块/单元进度 */
    data class Running(
        val bookId: Long,
        val doneChapters: Int,
        val totalChapters: Int,
        val chapterTitle: String,
        val phase: String,
        val subDone: Int,
        val subTotal: Int,
    ) : BookAnalysisState

    data class Succeeded(val bookId: Long, val message: String) : BookAnalysisState
    data class Interrupted(val bookId: Long, val message: String) : BookAnalysisState
    data class Failed(val bookId: Long, val message: String, val keyIssue: Boolean = false) : BookAnalysisState
}

/**
 * 一键分析全书（阅读体验轮 2026-11-21）：按 [BookAnalysisScope] 挑出正文章，逐章
 * 「粗读 → 讲解」串行推进，每章完成即落库——中断/停止后重新一键分析，已分析章走
 * 空转保护与讲解缓存零调用跳过，天然断点续跑。经 AiGate 与粗读/讲解/总结/总览互斥。
 * 复用 RoughReadPlanner/NotePlanner（不经过两个 Runner 的单章状态机，避免状态打架）。
 */
class BookAnalysisRunner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatJsonFn: ChatJsonFn,
    private val chatTextFn: ChatTextFn,
    scope: CoroutineScope,
    gate: AiGate = AiGate(),
) : AiTaskRunner<BookAnalysisState>(gate, scope) {

    override val gateLabel = "全书分析"

    private val _state = MutableStateFlow<BookAnalysisState>(BookAnalysisState.Idle)
    val state: StateFlow<BookAnalysisState> = _state.asStateFlow()
    override fun setState(state: BookAnalysisState) {
        _state.value = state
    }
    override fun idleState(): BookAnalysisState = BookAnalysisState.Idle
    override fun queuedState(id: Long): BookAnalysisState = BookAnalysisState.Queued(id)
    override fun failureState(id: Long, message: String, keyIssue: Boolean) =
        BookAnalysisState.Failed(id, message, keyIssue)

    /** 启动全书分析（force=false：已分析章零调用跳过，重跑即续跑不烧钱） */
    fun start(bookId: Long) = launchTask(bookId, "全书分析启动失败") { runBook(bookId) }

    /** 结果行「知道了」清掉（排队/运行中不动——状态是 UI 的唯一进度源） */
    fun clearResult() {
        when (_state.value) {
            is BookAnalysisState.Succeeded, is BookAnalysisState.Interrupted, is BookAnalysisState.Failed ->
                setState(idleState())
            else -> {}
        }
    }

    private suspend fun runBook(bookId: Long): BookAnalysisState {
        currentCoroutineContext().ensureActive()
        val chapters = db.chapterDao().byBook(bookId).filter { it.level == 1 }.sortedBy { it.idx }
        if (chapters.isEmpty()) {
            return BookAnalysisState.Succeeded(bookId, "这本书还没有章节")
        }
        val plan = BookAnalysisScope.plan(chapters.map { it.title })
        if (plan.targets.isEmpty()) {
            return BookAnalysisState.Succeeded(bookId, "没有识别出正文章节（全部是开篇/目录/后置件），无需分析")
        }
        val roughPlanner = RoughReadPlanner(db, context, settings, chatJsonFn)
        val notePlanner = NotePlanner(db, context, settings, chatTextFn)
        var doneChapters = 0
        for (ti in plan.targets) {
            currentCoroutineContext().ensureActive()
            val chapter = chapters[ti]
            fun progress(phase: String, subDone: Int, subTotal: Int) {
                setState(
                    BookAnalysisState.Running(
                        bookId, doneChapters, plan.targetCount, chapter.title, phase, subDone, subTotal,
                    ),
                )
            }
            // 粗读：无正文内容的漏网假章抛 PlannerException → 跳过继续，不整书中断
            try {
                progress("粗读", 0, 0)
                val outcome = roughPlanner.run(chapter.id) { d, t -> progress("粗读", d, t) }
                if (outcome.interruptedAtBlock != null) {
                    return BookAnalysisState.Interrupted(
                        bookId,
                        "分析到「${chapter.title}」粗读中断：${outcome.interruptedReason ?: "未知原因"}。" +
                            "已完成 $doneChapters 章已保存，稍后重新一键分析会自动续跑",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MissingKeyException) {
                throw e // launchTask 统一映射 Failed(keyIssue=true)，UI 引导去设置
            } catch (e: PlannerException) {
                doneChapters++
                continue
            } catch (e: Exception) {
                return BookAnalysisState.Interrupted(
                    bookId,
                    "分析到「${chapter.title}」粗读时出错：${e.message ?: "未知错误"}。已完成部分已保存",
                )
            }
            // 讲解：无讲解单元（全 SKIP/全 NONE）时 NotePlanner 直接成功零调用
            try {
                progress("讲解", 0, 0)
                val noteOutcome = notePlanner.run(chapter.id) { d, t -> progress("讲解", d, t) }
                if (noteOutcome.interruptedAtUnit != null) {
                    return BookAnalysisState.Interrupted(
                        bookId,
                        "分析到「${chapter.title}」讲解中断：${noteOutcome.interruptedReason ?: "未知原因"}。" +
                            "已完成部分已保存，稍后重新一键分析会自动续跑",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MissingKeyException) {
                throw e
            } catch (e: Exception) {
                return BookAnalysisState.Interrupted(
                    bookId,
                    "分析到「${chapter.title}」讲解时出错：${e.message ?: "未知错误"}。已完成部分已保存",
                )
            }
            doneChapters++
        }
        return BookAnalysisState.Succeeded(
            bookId,
            "全书分析完成：$doneChapters 章（跳过 ${plan.skippedCount} 章开篇/目录/后置件）",
        )
    }
}
