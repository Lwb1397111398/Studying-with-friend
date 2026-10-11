package com.studyfriend.app.data.study

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
 * 子类只提供：gate 占用标签、状态发射口与 Idle/失败态构造，以及任务体
 * （返回成功终态；进度/流式经 [setState] 沿途发射）。
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

    protected abstract fun failureState(id: Long, message: String, keyIssue: Boolean): S

    /**
     * 启动（或替换）一个 AI 任务。[fallbackError] 是异常无 message 时的兜底
     * 文案（各入口不同：启动失败/归并失败/生成失败）。
     */
    protected fun launchTask(id: Long, fallbackError: String, body: suspend () -> S) {
        val old = job
        old?.cancel()
        job = scope.launch {
            old?.join() // 等旧 job 完全退出（含其 finally gate.end()），自替换才能重获 gate
            if (!gate.tryBegin(gateLabel)) {
                setState(failureState(id, BUSY_MESSAGE, keyIssue = false))
                return@launch
            }
            try {
                setState(body())
            } catch (e: CancellationException) {
                // 被新 start 替换：新任务自己会写状态，这里不要把状态抹回
                if (coroutineContext[Job] === job) setState(idleState())
                throw e
            } catch (e: Exception) {
                // 只在本 job 仍是现任时覆盖状态：stop/start 替换后旧 job 的迟到异常不得刷掉新状态
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
        job?.cancel()
        setState(idleState())
    }

    companion object {
        /** gate 被占的统一文案（原四 Runner 各写一份、措辞还差两个字，测试按前缀断言） */
        const val BUSY_MESSAGE = "另一项 AI 任务进行中，请等它完成"
    }
}
