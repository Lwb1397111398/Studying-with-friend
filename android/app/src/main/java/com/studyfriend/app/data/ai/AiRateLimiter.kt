package com.studyfriend.app.data.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AI 请求全局限流器（2026-11-22 实景体验轮第九波，借鉴 anx-reader v1.14.0「AI 服务层全局
 * RPM 限流」思路，MIT 项目学思路自写实现）：滑动窗口内最多 [limitPerMinute] 次请求，
 * 超了就挂起等最早的请求滑出窗口——防「一键分析全书」这类批量链路把免费额度一分钟内
 * 打爆（撞 429 后退避重试反而更费）。所有 AI 请求收口在 AiClient.chat，入口 acquire 一次
 * 即全覆盖（粗读/讲解/总结/随手问/判卷/视觉转写）。
 *
 * nowMs/delayFn 注入：单测用假时钟推进窗口，不做真实等待。
 */
class AiRateLimiter(
    val limitPerMinute: Int = DEFAULT_LIMIT_PER_MINUTE,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(limitPerMinute >= 1) { "limitPerMinute 必须 ≥1" }
    }

    private val mutex = Mutex()
    private val starts = ArrayDeque<Long>()

    /** 窗口 60 秒（滑动窗口，从窗口内最早一次成功「领号」起算） */
    private companion object {
        const val WINDOW_MS = 60_000L
        /** 等待再加 50ms 余量，防时钟精度导致醒来后仍差几毫秒又睡一整轮 */
        const val SLACK_MS = 50L
        /** 默认每分钟请求数：正常单章链路（块串行，每块数秒）不受影响；批量链路自动排队 */
        const val DEFAULT_LIMIT_PER_MINUTE = 15
    }

    /**
     * 领一个请求名额：窗口未满立刻记一笔并返回；满了挂起到最早记录滑出窗口再试。
     * 取消安全：等待中被 cancel 直接向上抛，不占名额（名额只在锁内记录）。
     */
    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                val now = nowMs()
                while (starts.isNotEmpty() && now - starts.first() >= WINDOW_MS) {
                    starts.removeFirst()
                }
                if (starts.size < limitPerMinute) {
                    starts.addLast(now)
                    0L
                } else {
                    WINDOW_MS - (now - starts.first()) + SLACK_MS
                }
            }
            if (waitMs <= 0L) return
            delayFn(waitMs)
        }
    }

    /** 测试辅助：当前窗口内已领名额数（锁内快照） */
    suspend fun issuedInWindow(): Int = mutex.withLock { starts.size }
}
