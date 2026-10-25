package com.studyfriend.app.data.study

import com.studyfriend.app.data.ai.AiException
import kotlinx.coroutines.delay

/** 429（限流）退避档位（体验增量轮）：按重试次数递增 30→60→120s 封顶——
 *  固定 30s 对 rpm/tpm 很紧的 token 套餐端点不够，连续撞墙说明窗口没过，加倍等 */
internal val BACKOFF_429_STEPS_MS = longArrayOf(30_000L, 60_000L, 120_000L)

/** 退避时长纯函数（单测锚点）：429 按档位 30→60→120s 递增封顶，其余错误 2s */
internal fun backoffDelayMs(lastError: Exception?, attempt: Int): Long =
    if (lastError is AiException && lastError.httpCode == 429) {
        BACKOFF_429_STEPS_MS[attempt.coerceIn(0, BACKOFF_429_STEPS_MS.lastIndex)]
    } else {
        2_000L
    }

/** 重试前退避。[attempt]=当前是第几次重试前的等待（0 起） */
internal suspend fun plannerBackoff(lastError: Exception?, attempt: Int = 0) {
    delay(backoffDelayMs(lastError, attempt))
}

/** 流式 partial 的发射间隔：攒增量至多每 100ms 发一次状态。
 *  逐 delta 全量 toString 是 O(n²) 拷贝且每个 delta 触发一次下游重组，长输出越到后面越卡 */
internal const val STREAM_FLUSH_MS = 100L
