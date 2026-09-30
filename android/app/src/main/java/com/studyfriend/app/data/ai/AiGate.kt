package com.studyfriend.app.data.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局 AI 任务互斥（计划 M4b §2.5）：粗读与讲解共用一把锁，低内存约束下
 * 任何时刻只允许一个 AI 管道运行。tryBegin 原子（Mutex.tryLock）；
 * end 仅由 tryBegin 成功的持有者在 finally 调用（未持锁调用 unlock 会抛）。
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

    /** 持有者释放（Runner 在 try/finally 中调用） */
    fun end() {
        _label.value = null
        mutex.unlock()
    }
}
