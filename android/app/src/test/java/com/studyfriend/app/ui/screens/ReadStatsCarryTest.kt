package com.studyfriend.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** 批A：阅读统计零头结转数学——碎片会话不再丢失，floor 取整不透支 */
class ReadStatsCarryTest {

    @Test
    fun `不足1分钟的碎片不丢零头`() {
        // 40 秒碎片：不入账，零头 40s 留给下次
        val (minutes, carry) = readingFlushMath(0L, 40_000L)
        assertEquals(0, minutes)
        assertEquals(40_000L, carry)
    }

    @Test
    fun `两次碎片攒成整分钟`() {
        // 40s + 35s = 75s → 1 分钟入账，余 15s
        val first = readingFlushMath(0L, 40_000L)
        val second = readingFlushMath(first.second, 35_000L)
        assertEquals(1, second.first)
        assertEquals(15_000L, second.second)
    }

    @Test
    fun `长会话整分钟入账余数结转`() {
        // 10 分 42 秒 → 10 分钟，余 42s
        val (minutes, carry) = readingFlushMath(0L, 642_000L)
        assertEquals(10, minutes)
        assertEquals(42_000L, carry)
    }

    @Test
    fun `零头跨次累计后再入账`() {
        // 59s 碎片 + 59s 碎片 = 118s → 1 分钟，余 58s
        val first = readingFlushMath(0L, 59_000L)
        assertEquals(0, first.first)
        val second = readingFlushMath(first.second, 59_000L)
        assertEquals(1, second.first)
        assertEquals(58_000L, second.second)
    }
}
