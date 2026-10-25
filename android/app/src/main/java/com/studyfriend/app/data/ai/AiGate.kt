package com.studyfriend.app.data.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 全局 AI 任务互斥（计划 M4b §2.5）：粗读与讲解共用一把锁，低内存约束下
 * 任何时刻只允许一个 AI 管道运行。tryBegin 原子（Mutex.tryLock）；
 * end 仅由申请成功的持有者在 finally 调用（未持锁调用 unlock 会抛）。
 *
 * 体验增量轮：加 [awaitBegin] 排队语义——任务不再「立即拒绝」，而是挂起等
 * 前一个任务释放（UI 显示「排队等待中」）；等待期间协程被取消（stop）会直接
 * 抛 CancellationException 退出队列，锁不会被半获得。
 */
class AiGate {
    private val mutex = Mutex()

    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    /** 非阻塞申请：忙则 false；成功后 label 置位直到 end */
    fun tryBegin(tag: String): Boolean {
        val ok = mutex.tryLock()
        if (ok) {
            _label.value = tag
        }
        return ok
    }

    /**
     * 排队申请：挂起直到锁可得或超时；返回 false=超时（未获得锁，调用方走失败态）。
     * 挂起期间协程被取消（stop/替换）→ Mutex.lock 在挂起点抛 CancellationException，
     * 锁不会被半获得，无需补偿释放。
     */
    suspend fun awaitBegin(tag: String, timeoutMs: Long): Boolean {
        val ok = withTimeoutOrNull(timeoutMs) { mutex.lock() } != null
        if (ok) {
            _label.value = tag
        }
        return ok
    }

    /** 持有者释放（Runner 在 try/finally 中调用） */
    fun end() {
        _label.value = null
        mutex.unlock()
    }
}
