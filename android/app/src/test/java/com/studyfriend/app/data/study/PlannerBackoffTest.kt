package com.studyfriend.app.data.study

import com.studyfriend.app.data.ai.AiException
import org.junit.Assert.assertEquals
import org.junit.Test

/** 体验增量轮：429 退避按重试次数递增 30→60→120s 封顶；其余错误固定 2s */
class PlannerBackoffTest {

    @Test
    fun backoff429IncreasesByAttempt() {
        assertEquals(30_000L, backoffDelayMs(AiException("Too Many Requests", httpCode = 429), 0))
        assertEquals(60_000L, backoffDelayMs(AiException("Too Many Requests", httpCode = 429), 1))
        assertEquals(120_000L, backoffDelayMs(AiException("Too Many Requests", httpCode = 429), 2))
        // 超出档位数封顶 120s（防 attempt 越界）
        assertEquals(120_000L, backoffDelayMs(AiException("Too Many Requests", httpCode = 429), 5))
        // 负数防御：压回首档
        assertEquals(30_000L, backoffDelayMs(AiException("Too Many Requests", httpCode = 429), -3))
    }

    @Test
    fun otherErrorsStayFlat() {
        // 非 429（含网络异常/5xx/解析失败）维持 2s 小歇，不随 attempt 递增
        assertEquals(2_000L, backoffDelayMs(AiException("Key 无效", httpCode = 401), 0))
        assertEquals(2_000L, backoffDelayMs(IllegalStateException("解析失败"), 3))
        assertEquals(2_000L, backoffDelayMs(null, 1))
    }
}
