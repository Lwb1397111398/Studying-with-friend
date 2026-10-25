package com.studyfriend.app.data.study

import com.studyfriend.app.data.DiagLog
import com.studyfriend.app.data.ai.AiGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * AI 任务 Runner 骨架（自替换纪律的单源实现，计划 M4b §2.6）：
 * start 先取消旧 job 并 join 之后再抢 AiGate；stop 只取消不清 job 引用；
 * CancellationException/异常都只在本 job 仍是现任时覆盖状态——替换后旧 job 的
 * 迟到异常不得刷掉新状态；gate 在 finally 必放。此前这套纪律在
 * NoteRunner/SummaryRunner/OverviewRunner/RoughReadRunner 各抄一份，改错一份
 * 就是并发事故，收敛到此处一次写对。
 *
 * 子类只提供：gate 占用标签、状态发射口与 Idle/排队/失败态构造，以及任务体
 * （返回成功终态；进度/流式经 [setState] 沿途发射）。
 *
 * 体验增量轮：抢锁从 tryLock 立即拒绝改为 [AiGate.awaitBegin] 排队等待
 * （发 [queuedState] 给 UI 显示「排队等待中」）；stop 在排队期间同样可取消。
 */
abstract class AiTaskRunner<S : Any>(
    private val gate: AiGate,
    private val scope: CoroutineScope,
) {

    /** gate 占用标签：讲解/粗读/总结/总览 */
    protected abstract val gateLabel: String

    private var job: Job? = null

    /** 状态写入口：进度/流式/终态统一经此发射（子类持有 StateFlow） */
    protected abstract fun setState(state: S)

    protected abstract fun idleState(): S

    /** 排队等待态（awaitBegin 挂起期间发射，UI 显示「排队等待中」） */
    protected abstract fun queuedState(id: Long): S

    protected abstract fun failureState(id: Long, message: String, keyIssue: Boolean): S

    /**
     * 启动（或替换）一个 AI 任务。[fallbackError] 是异常无 message 时的兜底
     * 文案（各入口不同：启动失败/归并失败/生成失败）。
     * 排队等待最长 [AWAIT_TIMEOUT_MS]：超时走失败态；失败/取消/占线原因都写
     * DiagLog（设置页可导出给 AI 排障，数据安全轮）。
     */
    protected fun launchTask(id: Long, fallbackError: String, body: suspend () -> S) {
        val old = job
        old?.cancel()
        job = scope.launch {
            old?.join() // 等旧 job 完全退出（含其 finally gate.end()），自替换才能重获 gate
            val acquired = try {
                setState(queuedState(id))
                gate.awaitBegin(gateLabel, AWAIT_TIMEOUT_MS)
            } catch (e: CancellationException) {
                // stop/替换发生在排队挂起期间：锁未被半获得，直接退出（stop 已写 Idle）
                throw e
            }
            if (!acquired) {
                DiagLog.w("AiTask", "$gateLabel#$id 排队等待超时（>${AWAIT_TIMEOUT_MS / 60000} 分钟）")
                setState(failureState(id, WAIT_TIMEOUT_MESSAGE, keyIssue = false))
                return@launch
            }
            try {
                setState(body())
            } catch (e: CancellationException) {
                // 被新 start 替换：新任务自己会写状态，这里不要把状态抹回
                DiagLog.i("AiTask", "$gateLabel#$id 被取消（stop 或被新任务替换）")
                if (coroutineContext[Job] === job) setState(idleState())
                throw e
            } catch (e: Exception) {
                // 只在本 job 仍是现任时覆盖状态：stop/start 替换后旧 job 的迟到异常不得刷掉新状态
                DiagLog.e("AiTask", "$gateLabel#$id 失败：${e.message ?: fallbackError}", e)
                if (coroutineContext[Job] === job) {
                    setState(failureState(id, e.message ?: fallbackError, keyIssue = e is MissingKeyException))
                }
            } finally {
                gate.end()
            }
        }
    }

    /** 用户主动停止：已完成部分保留（等于断点），状态回 Idle；job 引用保留供下次 start join */
    fun stop() {
        DiagLog.i("AiTask", "$gateLabel 用户主动停止")
        job?.cancel()
        setState(idleState())
    }

    companion object {
        /** 历史文案（占线立即拒绝时代的口径）；保留常量防旧测试断言文案失配 */
        const val BUSY_MESSAGE = "另一项 AI 任务进行中，请等它完成"

        /** 排队等待上限：前一个任务（如整本书粗读）可能跑很久，给足 10 分钟 */
        const val AWAIT_TIMEOUT_MS = 10 * 60 * 1000L
        const val WAIT_TIMEOUT_MESSAGE = "排队等待超时（超过 10 分钟），请稍后再试"
    }
}
