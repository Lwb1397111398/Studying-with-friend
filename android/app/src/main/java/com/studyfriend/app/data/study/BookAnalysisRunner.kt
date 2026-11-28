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
 * 「粗读 → 讲解 → 总结」串行推进，收尾生成全书总览——每步完成即落库。中断/停止后
 * 重新一键分析，已分析章走空转保护与讲解/总结缓存零调用跳过，天然断点续跑（老板
 * 诉求「自动一次弄完全书」2026-11-28：总结与总览并入）。经 AiGate 与粗读/讲解/
 * 总结/总览互斥。复用各 Planner（不经过各 Runner 的单章状态机，避免状态打架）。
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
        // 残章跳过（实景轮 2026-11-21）：按每章字数把 1 段几字的扉页残章剔出分析清单，
        // 粗读对这种章发分类请求纯烧 API 且产出无意义（0 段章 RoughReadPlanner 已兜）
        val charsById = db.paragraphDao().charsByBook(bookId).associate { it.chapterId to (it.chars ?: 0) }
        val plan = BookAnalysisScope.plan(
            chapters.map { it.title },
            chapters.map { charsById[it.id] ?: 0 },
        )
        if (plan.targets.isEmpty()) {
            return BookAnalysisState.Succeeded(bookId, "没有识别出正文章节（全部是开篇/目录/后置件），无需分析")
        }
        val roughPlanner = RoughReadPlanner(db, context, settings, chatJsonFn)
        val notePlanner = NotePlanner(db, context, settings, chatTextFn)
        val summaryPlanner = SummaryPlanner(db, context, settings, chatTextFn)
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
                val outcome = roughPlanner.run(chapter.id) { d, t, _ -> progress("粗读", d, t) }
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
            // 总结（老板诉求「一次弄完」2026-11-28）：已有当前版本总结的章零调用跳过
            // （续跑/重跑不重烧）；内容性问题（无标注/无正文）跳章继续，网络等意外中断整书
            progress("总结", 0, 0)
            val asset = db.chapterAssetDao().byChapter(chapter.id)
            val hasCurrentSummary = asset != null &&
                SummaryPlanner.isCurrentSummary(asset.promptVersion) &&
                asset.summaryMd.isNotBlank()
            if (!hasCurrentSummary) {
                try {
                    summaryPlanner.run(chapter.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MissingKeyException) {
                    throw e // launchTask 统一映射 Failed(keyIssue=true)，UI 引导去设置
                } catch (e: PlannerException) {
                    // 该章总结跳过（粗读零标注的空转章等），不整书中断
                } catch (e: Exception) {
                    return BookAnalysisState.Interrupted(
                        bookId,
                        "分析到「${chapter.title}」总结时出错：${e.message ?: "未知错误"}。" +
                            "已完成部分已保存，稍后重新一键分析会自动续跑",
                    )
                }
            }
            doneChapters++
        }
        // 收尾：全书总览（最后一步，best-effort）——已是当前版本零调用跳过；失败不推翻
        // 整书成果，人话写进结果行（总览页也可随时单独重新生成）
        setState(
            BookAnalysisState.Running(bookId, plan.targetCount, plan.targetCount, "全书总览", "总览", 0, 0),
        )
        val overviewNote = if (db.bookDao().get(bookId)?.overviewJson
                ?.let { OverviewCodec.decode(it)?.promptVersion == OverviewCodec.VERSION } == true
        ) {
            "全书总览已是最新"
        } else {
            try {
                OverviewPlanner(db, context, settings, chatTextFn).run(bookId)
                "全书总览已生成"
            } catch (e: CancellationException) {
                throw e
            } catch (e: MissingKeyException) {
                throw e // launchTask 统一映射 Failed(keyIssue=true)，UI 引导去设置
            } catch (e: PlannerException) {
                "全书总览未生成（${e.message ?: "内容不足"}），其余成果不受影响"
            } catch (e: Exception) {
                "全书总览未生成（${e.message ?: "未知错误"}），可在全书总览页单独重试"
            }
        }
        return BookAnalysisState.Succeeded(
            bookId,
            "全书分析完成：$doneChapters 章（跳过 ${plan.skippedCount} 章开篇/目录/后置件）；$overviewNote",
        )
    }
}
