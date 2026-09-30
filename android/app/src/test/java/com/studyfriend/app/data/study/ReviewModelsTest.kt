package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.WrongAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M6 计划 §6：间隔推进 5 例 + 章级聚合 2 例 + 错题去重 2 例 */
class ReviewModelsTest {

    private val now = 1_000_000L
    private val day = 24 * 60 * 60 * 1000L

    // ---------- advanceReview（计划 §2.1） ----------

    @Test
    fun remember_advancesToNextInterval() {
        val a = advanceReview(0, ReviewVerdict.REMEMBER, now)
        assertEquals(1, a.intervalIdx)
        assertEquals(now + 3 * day, a.dueAt) // 第 1 档记得 → 3 天后再来
        assertFalse(a.done)
    }

    @Test
    fun remember_atCapMarksDone() {
        val a = advanceReview(4, ReviewVerdict.REMEMBER, now)
        assertEquals(4, a.intervalIdx)
        assertTrue(a.done)
    }

    @Test
    fun fuzzy_reschedulesSameInterval() {
        val a = advanceReview(2, ReviewVerdict.FUZZY, now)
        assertEquals(2, a.intervalIdx)
        assertEquals(now + 7 * day, a.dueAt)
        assertFalse(a.done)
    }

    @Test
    fun forgot_resetsToZero() {
        val a = advanceReview(3, ReviewVerdict.FORGOT, now)
        assertEquals(0, a.intervalIdx)
        assertEquals(now + day, a.dueAt)
        assertFalse(a.done)
    }

    @Test
    fun outOfRangeIdx_isCoercedInReturn() {
        // 历史脏数据 idx=5：返回值不得持久化越界档位（M6 评审 P2-4）
        val a = advanceReview(5, ReviewVerdict.FUZZY, now)
        assertEquals(4, a.intervalIdx)
        assertEquals(now + 30 * day, a.dueAt)
    }

    // ---------- aggregateVerdict（计划 §2.2） ----------

    @Test
    fun aggregate_allRememberIsRemember() {
        assertEquals(ReviewVerdict.REMEMBER, aggregateVerdict(listOf(ReviewVerdict.REMEMBER, ReviewVerdict.REMEMBER)))
    }

    @Test
    fun aggregate_anyForgotIsForgot_elseFuzzy() {
        assertEquals(ReviewVerdict.FORGOT, aggregateVerdict(listOf(ReviewVerdict.REMEMBER, ReviewVerdict.FORGOT, ReviewVerdict.REMEMBER)))
        assertEquals(ReviewVerdict.FUZZY, aggregateVerdict(listOf(ReviewVerdict.REMEMBER, ReviewVerdict.FUZZY)))
        assertEquals(ReviewVerdict.FUZZY, aggregateVerdict(emptyList())) // 无题退化单按钮容错
    }

    // ---------- latestWrongAttempts（M5 契约 #1：取最新再过滤 WRONG） ----------

    private fun wrong(ch: Long, q: Int, verdict: String, createdAt: Long) = WrongAttempt(
        id = createdAt, bookId = 1L, chapterId = ch, chapterTitle = "章$ch", bookTitle = "书",
        qIndex = q, answer = "答", verdict = verdict, feedback = null, createdAt = createdAt,
    )

    @Test
    fun dedup_takesLatestPerQuestion() {
        // 同题两次都错：取最新一条
        val out = latestWrongAttempts(
            listOf(
                wrong(1L, 0, "WRONG", 1L),
                wrong(1L, 0, "WRONG", 5L),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(5L, out[0].createdAt)
    }

    @Test
    fun dedup_laterCorrectRemovesFromWrongList() {
        // 先错后对：CORRECT 行必须在场（SQL 不过滤 verdict），最新是对 → 出列
        val out = latestWrongAttempts(
            listOf(
                wrong(1L, 0, "WRONG", 1L),
                wrong(1L, 0, "CORRECT", 9L),
                wrong(1L, 1, "WRONG", 2L), // 只错过一次的题保留
            ),
        )
        assertEquals(1, out.size)
        assertEquals(1, out[0].qIndex)
    }
}
