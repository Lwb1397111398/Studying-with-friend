package com.studyfriend.app.data.study

import com.studyfriend.app.data.ai.AiException
import kotlinx.coroutines.delay

/** 重试前退避：429（限流）等一个窗口再试（token 套餐端点 rpm/tpm 很紧），其余小歇即可 */
internal suspend fun plannerBackoff(lastError: Exception?) {
    val delayMs = if (lastError is AiException && lastError.httpCode == 429) 30_000L else 2_000L
    delay(delayMs)
}
