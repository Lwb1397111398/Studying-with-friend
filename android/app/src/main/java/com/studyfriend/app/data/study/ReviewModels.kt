package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.WrongAttempt

// ---------- 间隔复习推进（P0-① FSRS 化；推进逻辑在 Fsrs.kt，本文件留聚合与常量） ----------

const val DAY_MS = 24 * 60 * 60 * 1000L

/** 复习自评四档（P0-①，映射 FSRS：忘了→Again/模糊→Hard/记得→Good/太简单→Easy） */
enum class ReviewVerdict { REMEMBER, FUZZY, FORGOT, EASY }

/**
 * 章级聚合（计划 M6 §2.2，P0-① 扩四档）：逐题自评后章级一次推进——
 * 任一忘了→FORGOT；任一模糊→FUZZY（「有没答好的题」就该拉近间隔）；
 * 全部太简单→EASY；记得与太简单混评→REMEMBER（从保守）；空列表退化→FUZZY。
 */
fun aggregateVerdict(verdicts: List<ReviewVerdict>): ReviewVerdict = when {
    verdicts.isEmpty() -> ReviewVerdict.FUZZY
    verdicts.any { it == ReviewVerdict.FORGOT } -> ReviewVerdict.FORGOT
    verdicts.any { it == ReviewVerdict.FUZZY } -> ReviewVerdict.FUZZY
    verdicts.all { it == ReviewVerdict.EASY } -> ReviewVerdict.EASY
    else -> ReviewVerdict.REMEMBER
}

// ---------- 错题本去重（M5 契约 #1 落地） ----------

/**
 * 错题投影过滤（计划 M6 §2.3；v2 起取最新已下推到 SQL——wrongLatest 每题只出
 * createdAt 最新一条，CORRECT 最新行照样在场）；本函数负责最后一步：只留
 * verdict=='WRONG' 并按 书/章/题 排序。对已去重输入是纯过滤，对全量输入仍可
 * 自行去重（保留分组取最新以维持纯函数测试契约）。
 */
fun latestWrongAttempts(all: List<WrongAttempt>): List<WrongAttempt> = all
    .groupBy { it.chapterId to it.qIndex }
    .map { it.value.maxBy { a -> a.createdAt } }
    .filter { it.verdict == "WRONG" }
    .sortedWith(compareBy({ it.bookId }, { it.chapterId }, { it.qIndex }))

// ---------- 激励统计（P1-⑤）：streak 里程碑 ----------

/** 连续阅读里程碑表：达成当天在复习统计卡上庆祝展示 */
val STREAK_MILESTONES = listOf(7, 30, 100, 365)

/** streak 恰好达成里程碑的天数（当天），非达成日返回 null */
fun streakMilestoneHit(streakDays: Int): Int? = STREAK_MILESTONES.firstOrNull { it == streakDays }

/** 下一个尚未达到的里程碑（已越过全部里程碑时返回 null） */
fun nextStreakMilestone(streakDays: Int): Int? = STREAK_MILESTONES.firstOrNull { it > streakDays }
