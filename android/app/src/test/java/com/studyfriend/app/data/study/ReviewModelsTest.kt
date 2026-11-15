package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.WrongAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P0-① FSRS 化后本文件只剩「章级聚合 + 错题去重」两组纯函数；
 * 推进逻辑归 FsrsTest（真实 java-fsrs 调用），旧 advanceReview 五例随档位制一起退役。
 */
class ReviewModelsTest {

    // ---------- aggregateVerdict（P0-① 四档） ----------

    @Test
    fun aggregate_allRememberIsRemember() {
        assertEquals(ReviewVerdict.REMEMBER, aggregateVerdict(listOf(ReviewVerdict.REMEMBER, ReviewVerdict.REMEMBER)))
    }

    @Test
    fun aggregate_anyForgotBeatsEverything() {
        assertEquals(
            ReviewVerdict.FORGOT,
            aggregateVerdict(listOf(ReviewVerdict.REMEMBER, ReviewVerdict.FORGOT, ReviewVerdict.EASY)),
        )
    }

    @Test
    fun aggregate_anyFuzzyBeatsRemember() {
        assertEquals(ReviewVerdict.FUZZY, aggregateVerdict(listOf(ReviewVerdict.REMEMBER, ReviewVerdict.FUZZY)))
        assertEquals(ReviewVerdict.FUZZY, aggregateVerdict(emptyList())) // 无题退化单按钮容错
    }

    @Test
    fun aggregate_allEasyIsEasy() {
        assertEquals(ReviewVerdict.EASY, aggregateVerdict(listOf(ReviewVerdict.EASY, ReviewVerdict.EASY)))
    }

    @Test
    fun aggregate_mixedRememberEasy_staysConservativeRemember() {
        // 「记得+太简单」混评从保守：按 Good 档排期（Easy 只在全程秒懂时给）
        assertEquals(
            ReviewVerdict.REMEMBER,
            aggregateVerdict(listOf(ReviewVerdict.REMEMBER, ReviewVerdict.EASY)),
        )
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

    // ---- 激励统计里程碑（P1-⑤）----

    @Test
    fun streakMilestone_hitExactly() {
        assertEquals(7, streakMilestoneHit(7))
        assertEquals(30, streakMilestoneHit(30))
        assertEquals(100, streakMilestoneHit(100))
        assertEquals(365, streakMilestoneHit(365))
        assertNull(streakMilestoneHit(6))
        assertNull(streakMilestoneHit(8))
        assertNull(streakMilestoneHit(101))
    }

    @Test
    fun nextStreakMilestone_distance() {
        assertEquals(7, nextStreakMilestone(0))
        assertEquals(30, nextStreakMilestone(7))
        assertNull(nextStreakMilestone(365))
        assertNull(nextStreakMilestone(400))
    }
}
