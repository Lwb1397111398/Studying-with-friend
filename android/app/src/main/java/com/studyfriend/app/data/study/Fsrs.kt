package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ReviewItemEntity
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import java.time.Instant

/**
 * FSRS-6 复习调度封装（P0-①，库：io.github.open-spaced-repetition:fsrs 1.0.0，MIT）。
 *
 * 设计定案（调研评审后）：
 * - **学习步留空**：章/术语是「日」粒度的复习单元，不走 Anki 式 1 分钟/10 分钟同日小步；
 *   空学习步下任意评分都转 REVIEW 按天排期（库源码边界分支保证，无越界）。
 * - **关 fuzz**：间隔可预测——按钮预写间隔与实际排期严格一致，单测确定。
 * - **默认 21 参数不训练**：官方默认值在千万级复习记录上预训练过，个性化训练需每用户
 *   约 200 条复习记录才有收益；desiredRetention 保持 0.9。
 * - **状态存自己的列**（review_items.fsrsState/Stability/Difficulty/LastReviewAt + dueAt），
 *   不用库的 Card JSON 序列化（其依赖 Jackson，已从 APK 排除）。
 */
object Fsrs {

    /** 全局单例调度器：无可变全局状态（reviewCard 返回新卡不改入参），并发复用安全 */
    private val scheduler: Scheduler = Scheduler.builder()
        .learningSteps(emptyArray())
        .relearningSteps(emptyArray())
        .enableFuzzing(false)
        .build()

    /** 三档自评 + 太简单 → FSRS 四档评分（调研评审采纳：只留 Good 撑不住长间隔，补 Easy） */
    fun ratingOf(verdict: ReviewVerdict): Rating = when (verdict) {
        ReviewVerdict.FORGOT -> Rating.AGAIN
        ReviewVerdict.FUZZY -> Rating.HARD
        ReviewVerdict.REMEMBER -> Rating.GOOD
        ReviewVerdict.EASY -> Rating.EASY
    }

    /** 一次复习的推进结果：markResult 落库参数 + review_log 行参数（一次携带，落库在仓库层同事务完成） */
    data class FsrsAdvance(
        val intervalDays: Int,
        val dueAt: Long,
        val state: Int,
        val stability: Double?,
        val difficulty: Double?,
        val lastReviewAt: Long,
        val rating: Int,
        val stateBefore: Int,
        val stabilityBefore: Double?,
        val difficultyBefore: Double?,
        val dueBefore: Long,
    )

    /** 实体行 → 库卡片。stability/difficulty 均 null = 没复习过的新卡（只挂到期时间）；上次复习时间回填 elapsed 计算 */
    fun cardOf(item: ReviewItemEntity): Card {
        val builder = Card.builder().due(Instant.ofEpochMilli(item.dueAt))
        if (item.fsrsState != DbValues.FSRS_STATE_LEARNING || item.fsrsStability != null) {
            builder.state(intToState(item.fsrsState))
            item.fsrsStability?.let { builder.stability(it) }
            item.fsrsDifficulty?.let { builder.difficulty(it) }
            item.fsrsLastReviewAt?.let { builder.lastReview(Instant.ofEpochMilli(it)) }
        }
        return builder.build()
    }

    /** 评分一次：出下一张卡的状态（不落库）。nowMs 显式传入——预写间隔/测试/UI 时刻三方同源 */
    fun review(item: ReviewItemEntity, verdict: ReviewVerdict, nowMs: Long): FsrsAdvance {
        val before = cardOf(item)
        val rating = ratingOf(verdict)
        val result = scheduler.reviewCard(before, rating, Instant.ofEpochMilli(nowMs), null)
        val c = result.card()
        val dueMs = c.due.toEpochMilli()
        return FsrsAdvance(
            intervalDays = daysBetween(nowMs, dueMs),
            dueAt = dueMs,
            state = stateToInt(c.state),
            stability = c.stability,
            difficulty = c.difficulty,
            lastReviewAt = nowMs,
            rating = ratingInt(verdict), // 库定义 Rating 值 1..4，显式映射不赌枚举序
            stateBefore = item.fsrsState,
            stabilityBefore = item.fsrsStability,
            difficultyBefore = item.fsrsDifficulty,
            dueBefore = item.dueAt,
        )
    }

    /** java-fsrs Rating 值：1=Again(忘了) 2=Hard(模糊) 3=Good(记得) 4=Easy(太简单) */
    private fun ratingInt(verdict: ReviewVerdict): Int = when (verdict) {
        ReviewVerdict.FORGOT -> 1
        ReviewVerdict.FUZZY -> 2
        ReviewVerdict.REMEMBER -> 3
        ReviewVerdict.EASY -> 4
    }

    /** 按钮预写间隔：四档各模拟一次评分取下次天数（fuzz 已关，预写=实际） */
    fun previewDays(item: ReviewItemEntity, nowMs: Long): Map<ReviewVerdict, Int> =
        ReviewVerdict.entries.associateWith { v ->
            val c = scheduler.reviewCard(cardOf(item), ratingOf(v), Instant.ofEpochMilli(nowMs), null).card()
            daysBetween(nowMs, c.due.toEpochMilli())
        }

    /** 当前记住概率（0..1，desiredRetention=0.9 的语义）；没复习过的新卡库返回 0 */
    fun retrievability(item: ReviewItemEntity, nowMs: Long): Double =
        scheduler.getCardRetrievability(cardOf(item), Instant.ofEpochMilli(nowMs))

    /** 到期列表「最快要忘优先」排序键：记住概率升序；新卡(0.0)排最前，同概率按 dueAt 早者先 */
    fun riskOrder(list: List<ReviewItemEntity>, nowMs: Long): List<ReviewItemEntity> =
        list.sortedWith(compareBy({ Fsrs.retrievability(it, nowMs) }, { it.dueAt }))

    // ---- 内部 ----

    /** 天数向上取整、下限 1：FSRS 按天排期，排出 0 天没有意义（忘了=尽快再见，也是 1 天起） */
    private fun daysBetween(fromMs: Long, toMs: Long): Int {
        val days = (toMs - fromMs) / DAY_MS
        return if (days < 1) 1 else days.toInt()
    }

    private fun stateToInt(s: State): Int = when (s) {
        State.LEARNING -> DbValues.FSRS_STATE_LEARNING
        State.REVIEW -> DbValues.FSRS_STATE_REVIEW
        State.RELEARNING -> DbValues.FSRS_STATE_RELEARNING
    }

    private fun intToState(v: Int): State = when (v) {
        DbValues.FSRS_STATE_REVIEW -> State.REVIEW
        DbValues.FSRS_STATE_RELEARNING -> State.RELEARNING
        else -> State.LEARNING
    }
}
