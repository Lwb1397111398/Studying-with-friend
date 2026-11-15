package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ReviewItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-① FSRS 调度封装测试（纯 JVM，java-fsrs 1.0.0 真实调用，不用假实现——
 * 这层测的就是「库的行为 + 我们的映射/取整」是否符合预期）。
 */
class FsrsTest {

    private val now = 1_700_000_000_000L
    private val day = DAY_MS

    private fun item(
        state: Int = DbValues.FSRS_STATE_LEARNING,
        stability: Double? = null,
        difficulty: Double? = null,
        lastReview: Long? = null,
        dueAt: Long = now - day,
    ) = ReviewItemEntity(
        bookId = 1L, chapterId = 1L, title = "第一章",
        intervalIdx = 0, dueAt = dueAt, done = false,
        fsrsState = state, fsrsStability = stability,
        fsrsDifficulty = difficulty, fsrsLastReviewAt = lastReview,
    )

    // ---------- 评分映射与首次复习 ----------

    @Test
    fun freshCard_good_turnsReviewStateWithDayInterval() {
        val adv = Fsrs.review(item(), ReviewVerdict.REMEMBER, now)
        // 空学习步：任意评分后转 REVIEW 按天排期；新卡 Good 初始稳定性 ≈ 3 天档
        assertEquals(DbValues.FSRS_STATE_REVIEW, adv.state)
        assertTrue("新卡记得应排 2~5 天，实际 ${adv.intervalDays}", adv.intervalDays in 2..5)
        assertEquals(now, adv.lastReviewAt)
        assertTrue("首次评分后 S/D 应被赋值", adv.stability != null && adv.difficulty != null)
        assertEquals(now - day, adv.dueBefore)
    }

    @Test
    fun freshCard_forgot_comesBackTomorrow() {
        val adv = Fsrs.review(item(), ReviewVerdict.FORGOT, now)
        // 新卡忘了：初始稳定性最短（ Again 档 < 1 天），天数下限钳到 1——尽快再见
        assertEquals(1, adv.intervalDays)
        assertEquals(DbValues.FSRS_STATE_REVIEW, adv.state)
    }

    @Test
    fun ratingInt_matchesFsrsValueScale() {
        // 日志 rating 列写库定义值 1..4，不赌枚举序
        assertEquals(1, Fsrs.review(item(), ReviewVerdict.FORGOT, now).rating)
        assertEquals(2, Fsrs.review(item(), ReviewVerdict.FUZZY, now).rating)
        assertEquals(3, Fsrs.review(item(), ReviewVerdict.REMEMBER, now).rating)
        assertEquals(4, Fsrs.review(item(), ReviewVerdict.EASY, now).rating)
    }

    // ---------- 旧档位折算卡（迁移回填形态） ----------

    @Test
    fun matureCard_good_growsInterval() {
        // 迁移回填：档 2（7 天）→ stability 7.0 / difficulty 5.0 / 上次复习 7 天前
        val legacy = item(
            state = DbValues.FSRS_STATE_REVIEW, stability = 7.0, difficulty = 5.0,
            lastReview = now - 7 * day,
        )
        val adv = Fsrs.review(legacy, ReviewVerdict.REMEMBER, now)
        assertTrue("记得应让间隔长于旧档 7 天，实际 ${adv.intervalDays}", adv.intervalDays > 7)
        assertTrue("稳定性应增长", adv.stability!! > 7.0)
    }

    @Test
    fun matureCard_forgot_shrinksInterval() {
        val legacy = item(
            state = DbValues.FSRS_STATE_REVIEW, stability = 30.0, difficulty = 5.0,
            lastReview = now - day, dueAt = now + 30 * day,
        )
        val adv = Fsrs.review(legacy, ReviewVerdict.FORGOT, now)
        assertTrue("忘了应大幅缩短间隔，实际 ${adv.intervalDays}", adv.intervalDays < 30)
        assertTrue("稳定性应下降", adv.stability!! < 30.0)
        assertEquals(1, adv.rating)
    }

    // ---------- 四档预写间隔（按钮预写=实际排期，fuzz 已关） ----------

    @Test
    fun previewDays_fourVerdictsMonotonic() {
        val previews = Fsrs.previewDays(item(), now)
        assertEquals(setOf(ReviewVerdict.FORGOT, ReviewVerdict.FUZZY, ReviewVerdict.REMEMBER, ReviewVerdict.EASY), previews.keys)
        // 档位越高间隔越长（忘了最短、太简单最长）
        assertTrue(previews.getValue(ReviewVerdict.FORGOT) < previews.getValue(ReviewVerdict.REMEMBER))
        assertTrue(previews.getValue(ReviewVerdict.REMEMBER) <= previews.getValue(ReviewVerdict.EASY))
        assertTrue(previews.getValue(ReviewVerdict.FORGOT) <= previews.getValue(ReviewVerdict.FUZZY))
        // 预写与实排一致：同卡同刻，记得档的预写天数 == review 的 intervalDays
        assertEquals(Fsrs.review(item(), ReviewVerdict.REMEMBER, now).intervalDays, previews.getValue(ReviewVerdict.REMEMBER))
    }

    // ---------- 记住概率（到期列表「最快要忘优先」排序键） ----------

    @Test
    fun retrievability_freshZero_matureInRange() {
        assertEquals(0.0, Fsrs.retrievability(item(), now), 1e-9)
        // 稳定性 30 天、昨天刚复习：记住概率应接近 1
        val fresh = item(
            state = DbValues.FSRS_STATE_REVIEW, stability = 30.0, difficulty = 5.0,
            lastReview = now - day,
        )
        assertTrue(Fsrs.retrievability(fresh, now) in 0.9..1.0)
        // 逾期卡（60 天没复习，稳定性 30 天）：概率明显衰减
        val overdue = item(
            state = DbValues.FSRS_STATE_REVIEW, stability = 30.0, difficulty = 5.0,
            lastReview = now - 60 * day,
        )
        assertTrue(Fsrs.retrievability(overdue, now) < 0.9)
    }

    @Test
    fun riskOrder_mostForgettableFirst() {
        val fresh = item(stability = null) // 新卡：概率 0
        val solid = item(
            state = DbValues.FSRS_STATE_REVIEW, stability = 30.0, difficulty = 5.0,
            lastReview = now - day, dueAt = now - 2 * day,
        )
        val ordered = Fsrs.riskOrder(listOf(solid, fresh), now)
        assertEquals(fresh.dueAt, ordered[0].dueAt) // 新卡排最前
    }
}
