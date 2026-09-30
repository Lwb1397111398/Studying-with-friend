package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.WrongAttempt

// ---------- 间隔复习推进（计划 M6 §2.1） ----------

/** 间隔档位（intervalIdx 0..4 对应天数）；推进规则=艾宾浩斯式 1/3/7/14/30 */
val INTERVAL_DAYS = listOf(1, 3, 7, 14, 30)

const val DAY_MS = 24 * 60 * 60 * 1000L

/** 复习自评三态（对齐 M5 自测三按钮：答对/模糊/答错） */
enum class ReviewVerdict { REMEMBER, FUZZY, FORGOT }

/** 一次推进的结果：落库走 ReviewItemDao.markResult(id, intervalIdx, dueAt, done) */
data class ReviewAdvance(val intervalIdx: Int, val dueAt: Long, val done: Boolean)

/**
 * 按自评推进排期：记得→下一档；封顶档记得→完成（done=true 保持 30 天档）；
 * 模糊→同档重排；忘了→归 0。入口先 coerce，历史脏数据（越界 idx）不持久化越界值。
 */
fun advanceReview(intervalIdx: Int, verdict: ReviewVerdict, nowMs: Long): ReviewAdvance {
    val idx = intervalIdx.coerceIn(0, INTERVAL_DAYS.lastIndex)
    return when (verdict) {
        ReviewVerdict.REMEMBER ->
            if (idx >= INTERVAL_DAYS.lastIndex) {
                ReviewAdvance(idx, nowMs + INTERVAL_DAYS.last() * DAY_MS, true)
            } else {
                ReviewAdvance(idx + 1, nowMs + INTERVAL_DAYS[idx + 1] * DAY_MS, false)
            }
        ReviewVerdict.FUZZY -> ReviewAdvance(idx, nowMs + INTERVAL_DAYS[idx] * DAY_MS, false)
        ReviewVerdict.FORGOT -> ReviewAdvance(0, nowMs + INTERVAL_DAYS[0] * DAY_MS, false)
    }
}

/**
 * 章级聚合（计划 M6 §2.2）：逐题自评后章级一次推进——
 * 全记得→REMEMBER；任一忘了→FORGOT；其余（含空列表退化的单按钮场景）→FUZZY。
 */
fun aggregateVerdict(verdicts: List<ReviewVerdict>): ReviewVerdict = when {
    verdicts.isEmpty() -> ReviewVerdict.FUZZY
    verdicts.all { it == ReviewVerdict.REMEMBER } -> ReviewVerdict.REMEMBER
    verdicts.any { it == ReviewVerdict.FORGOT } -> ReviewVerdict.FORGOT
    else -> ReviewVerdict.FUZZY
}

// ---------- 错题本去重（M5 契约 #1 落地） ----------

/**
 * 错题投影取最新再过滤（计划 M6 §2.3 两步走）：wrongAll() 返回全量作答历史
 * （SQL 不过滤 verdict，否则"先错后对"的 CORRECT 行被提前滤掉出不了列）；
 * 这里按 (chapterId, qIndex) 分组取 createdAt 最新一条，再只留 verdict=='WRONG'。
 */
fun latestWrongAttempts(all: List<WrongAttempt>): List<WrongAttempt> = all
    .groupBy { it.chapterId to it.qIndex }
    .map { it.value.maxBy { a -> a.createdAt } }
    .filter { it.verdict == "WRONG" }
    .sortedWith(compareBy({ it.bookId }, { it.chapterId }, { it.qIndex }))
