package com.studyfriend.app.data.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第九波实景轮：AI 全局 RPM 限流器——窗口内放行 N 个、满了挂起等滑出（假时钟推进） */
class AiRateLimiterTest {

    /**
     * 假时钟：delayFn 直接推进时钟（模拟「睡醒时钟已走」），不真挂起——
     * 若 delayFn 不推进时钟，acquire 的 while 循环会热循环卡死（本轮实录）。
     */
    private class FakeClock {
        var now = 0L
        val waits = mutableListOf<Long>()

        fun limiter(limit: Int = 15) = AiRateLimiter(
            limitPerMinute = limit,
            nowMs = { now },
            delayFn = { ms ->
                waits.add(ms)
                now += ms
            },
        )
    }

    @Test
    fun underLimit_acquiresImmediately(): Unit = runBlocking {
        val clock = FakeClock()
        val lim = clock.limiter(limit = 3)
        lim.acquire()
        lim.acquire()
        assertEquals(2, lim.issuedInWindow())
        assertTrue(clock.waits.isEmpty())
    }

    @Test
    fun overLimit_waitsUntilOldestSlidesOut(): Unit = runBlocking {
        val clock = FakeClock()
        val lim = clock.limiter(limit = 2)
        lim.acquire() // t=0
        clock.now = 10_000
        lim.acquire() // t=10s
        clock.now = 20_000

        // 第 3 个领号：窗口满（2 条在案），等最早一条（t=0）滑出 → 需等 60s-20s+50ms
        val job = async { lim.acquire() }
        withTimeout(5_000) { job.join() }

        assertEquals(1, clock.waits.size)
        assertEquals(60_000L - 20_000L + 50L, clock.waits[0])
        assertEquals(2, lim.issuedInWindow())
    }

    @Test
    fun slidingWindow_releasesExpiredSlots(): Unit = runBlocking {
        val clock = FakeClock()
        val lim = clock.limiter(limit = 2)
        lim.acquire() // t=0
        clock.now = 61_000
        // 最早记录已滑出窗口：无需等待即可再领
        lim.acquire()
        lim.acquire()
        assertEquals(2, lim.issuedInWindow())
        assertTrue(clock.waits.isEmpty())
    }

    @Test
    fun negativeLimit_rejected() {
        try {
            AiRateLimiter(limitPerMinute = 0)
            throw AssertionError("limitPerMinute=0 应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("≥1"))
        }
    }
}
