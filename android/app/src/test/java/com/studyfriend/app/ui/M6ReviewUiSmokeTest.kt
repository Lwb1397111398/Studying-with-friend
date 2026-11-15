package com.studyfriend.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ReviewItemEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.study.QuizCodec
import com.studyfriend.app.data.study.QuizQuestion
import com.studyfriend.app.ui.screens.ReviewScreen
import com.studyfriend.app.ui.theme.StudyFriendTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M6 计划 §6 步骤②：复习中心冒烟（3 例，Robolectric Compose）——
 * 到期列表→评估流转→markResult 落库与行消失 / 无题退化单问 / 错题本去重展示。
 * "复习推进落库（2）"断言并入例 1（记得推进）与例 2（忘了归 0）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StudyApp::class)
class M6ReviewUiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private val db: StudyDatabase by lazy {
        (ApplicationProvider.getApplicationContext<StudyApp>()).database
    }
    private var bookId: Long = 0
    private val chapterIds = mutableListOf<Long>()
    private val now = System.currentTimeMillis()

    private fun seedChapter(title: String): Long = runBlocking {
        db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = bookId, idx = chapterIds.size + 1, title = title, readState = "READ", gist = null, keyTermsJson = null)),
        )[0].also { chapterIds.add(it) }
    }

    private fun asset(chapterId: Long, vararg qa: QuizQuestion) = runBlocking {
        db.chapterAssetDao().upsert(
            ChapterAssetEntity(
                chapterId = chapterId,
                summaryMd = "总结。", mindmapTree = "根\n\t枝", mindmapJson = null,
                memoryMd = "记忆。", chainMd = "串联。",
                quizJson = QuizCodec.encode(qa.toList()),
                model = "test-model", promptVersion = "summary-pack-v1", createdAt = 1L,
            ),
        )
    }

    private fun reviewItem(
        chapterId: Long,
        title: String,
        intervalIdx: Int = 1,
        stability: Double? = null,
    ): Long = runBlocking {
        // P0-①：stability 非空 = 模拟复习中途的卡（迁移回填形态）；null = 新种子
        db.reviewItemDao().insertAll(
            listOf(
                ReviewItemEntity(
                    bookId = bookId, chapterId = chapterId, title = title,
                    intervalIdx = intervalIdx, dueAt = now - 1_000L, done = false,
                    fsrsState = if (stability != null) com.studyfriend.app.data.db.DbValues.FSRS_STATE_REVIEW
                    else com.studyfriend.app.data.db.DbValues.FSRS_STATE_LEARNING,
                    fsrsStability = stability, fsrsDifficulty = stability?.let { 5.0 },
                    fsrsLastReviewAt = stability?.let { now - 3 * 86_400_000L },
                ),
            ),
        )[0]
    }

    @Before
    fun seed(): Unit = runBlocking {
        bookId = db.bookDao().insert(
            BookEntity(
                title = "复习测试书", author = "佚名", sourceType = "TXT", filePath = "/tmp/r.txt",
                status = "READY", totalChapters = 2, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
    }

    @After
    fun tearDown(): Unit = runBlocking {
        // 单例库不 close；删书级联清章节/资产/排期/作答，防污染同 JVM 后续测试
        db.bookDao().delete(
            BookEntity(
                id = bookId, title = "复习测试书", author = "佚名", sourceType = "TXT", filePath = "/tmp/r.txt",
                status = "READY", totalChapters = 2, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
    }

    /** Room Flow 异步首发射：等节点出现后断言存在（substring 宽容副文案长句） */
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText(text, substring = true)[0].assertExists()
    }

    /** 把目标滚进视口（Robolectric 320×470 小视口：统计卡+热力图堆叠后列表区只剩几十 px，
     *  懒列表屏外内容不进语义树——M4b 总览决策 9 同款先例，断言/点击屏外节点前先滚） */
    private fun scrollTo(text: String, substring: Boolean = false) {
        // Room Flow 首发射前列表是空态（无滚动容器），先等滚动容器出现再滚
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasVerticalScrollAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasVerticalScrollAction()).performScrollToNode(hasText(text, substring = substring))
    }

    /** 只匹配带纵向滚动轴的容器（抄 M4aUiSmokeTest 同名匹配器） */
    private fun hasVerticalScrollAction() = SemanticsMatcher("vertical scroll") {
        it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
    }

    private fun setContent() {
        compose.setContent { StudyFriendTheme { ReviewScreen() } }
    }

    // ---------- 1. 到期列表 → 评估流转 → 记得推进落库 + 行消失 ----------

    @Test
    fun review_dueFlowSessionTransitionAndMarkResult(): Unit = runBlocking {
        val chId = seedChapter("复习章")
        asset(chId, QuizQuestion("RECALL", "善意取得的构成要件？", "无权处分+善意+合理价格+登记", "按四要件记"))
        val itemId = reviewItem(chId, "复习章", intervalIdx = 1, stability = 3.0)

        setContent()
        scrollTo("复习章")
        awaitText("复习章")
        compose.onNodeWithText("复习章").performClick()
        scrollTo("善意取得的构成要件？")
        awaitText("善意取得的构成要件？")
        compose.onNodeWithText("看答案").performClick()
        awaitText("参考答案：无权处分+善意+合理价格+登记")
        scrollTo("记得")
        compose.onNodeWithText("记得").performClick()

        // 行从 dueList Flow 消失（P0-①：FSRS 排出的间隔 > 0 天，不再到期 → 空态）
        awaitText("今天没有要复习的")
        // 落库断言（P0-①）：UI 时刻与测试时刻差几十毫秒——用 FSRS 在区间两端的预演夹住断言
        val seeded = ReviewItemEntity(
            bookId = bookId, chapterId = chId, title = "复习章",
            intervalIdx = 1, dueAt = now - 1_000L, done = false,
            fsrsState = com.studyfriend.app.data.db.DbValues.FSRS_STATE_REVIEW,
            fsrsStability = 3.0, fsrsDifficulty = 5.0, fsrsLastReviewAt = now - 3 * 86_400_000L,
        )
        val expectedLo = com.studyfriend.app.data.study.Fsrs.review(seeded, com.studyfriend.app.data.study.ReviewVerdict.REMEMBER, now)
        val expectedHi = com.studyfriend.app.data.study.Fsrs.review(seeded, com.studyfriend.app.data.study.ReviewVerdict.REMEMBER, now + 60_000L)
        val pending = db.reviewItemDao().pendingByBook(bookId).single()
        assertTrue(
            "intervalIdx 应等于 FSRS 排出天数（${expectedLo.intervalDays}..${expectedHi.intervalDays}），实际 ${pending.intervalIdx}",
            pending.intervalIdx in minOf(expectedLo.intervalDays, expectedHi.intervalDays)..maxOf(expectedLo.intervalDays, expectedHi.intervalDays),
        )
        assertTrue("dueAt 应推进到未来", pending.dueAt > now)
        assertEquals(false, pending.done)
        // 复习日志同事务落一行（P0-①）
        assertEquals(1, db.reviewLogDao().countByItem(itemId))
    }

    // ---------- 2. 无题/无资产：退化单问，忘了最快再见 ----------

    @Test
    fun review_noAssetFallbackSingleQuestion(): Unit = runBlocking {
        val chId = seedChapter("空资产章")
        val itemId = reviewItem(chId, "空资产章", intervalIdx = 3)

        setContent()
        scrollTo("空资产章")
        awaitText("空资产章")
        compose.onNodeWithText("空资产章").performClick()
        scrollTo("这一章的内容还记得多少？")
        awaitText("这一章的内容还记得多少？")
        scrollTo("忘了")
        compose.onNodeWithText("忘了").performClick()

        awaitText("今天没有要复习的")
        val pending = db.reviewItemDao().pendingByBook(bookId).single()
        assertEquals(false, pending.done)
        // P0-① FSRS：新卡忘了 → 初始稳定性最短，隔 1 天尽快再见（不再是旧档位制归 0）
        assertTrue(
            "忘了应在 2 天内再见，实际 dueAt 距今 ${(pending.dueAt - now) / 86_400_000L} 天",
            pending.dueAt in now..now + 2 * 86_400_000L + 60_000L,
        )
        // 空学习步：评分后转 REVIEW 态按天排期
        assertEquals(com.studyfriend.app.data.db.DbValues.FSRS_STATE_REVIEW, pending.fsrsState)
        assertEquals(1, db.reviewLogDao().countByItem(itemId))
    }

    // ---------- 2b. 同章双到期行：M5 契约防御去重只显示一条（评审 P1-1） ----------

    @Test
    fun review_duplicateDueRows_dedupedInView(): Unit = runBlocking {
        val chId = seedChapter("双排期章")
        // unique(bookId,chapterId,title) 下同章不同 title 可双行（重命名后再排期场景）
        db.reviewItemDao().insertAll(
            listOf(
                ReviewItemEntity(bookId = bookId, chapterId = chId, title = "双排期章", intervalIdx = 0, dueAt = now - 1_000L, done = false),
                ReviewItemEntity(bookId = bookId, chapterId = chId, title = "双排期章（旧）", intervalIdx = 0, dueAt = now - 2_000L, done = false),
            ),
        )

        setContent()
        // 去重按 dueAt 序保留首行（「双排期章（旧）」），精确匹配找不到——宽松滚到该行
        scrollTo("双排期章", substring = true)
        awaitText("双排期章")
        compose.waitUntil(3_000) {
            // distinctBy (bookId, chapterId) 后只渲染一行
            compose.onAllNodesWithText("双排期章", substring = true).fetchSemanticsNodes().size == 1
        }
    }

    // ---------- 3. 错题本去重展示（M5 契约 #1：取最新再过滤 WRONG） ----------

    @Test
    fun wrongbook_dedupShowsLatestOnly(): Unit = runBlocking {
        val ch1 = seedChapter("错题章一")
        val ch2 = seedChapter("错题章二")
        asset(
            ch1,
            QuizQuestion("RECALL", "先错后对的题干（应出列）", "答案A", ""),
            QuizQuestion("RECALL", "只错过一次的题干（保留）", "答案B", "解析B"),
        )
        asset(ch2, QuizQuestion("TRUE_FALSE", "另一章的题干（保留）", "对", ""))
        db.quizAttemptDao().insert(
            com.studyfriend.app.data.db.QuizAttemptEntity(chapterId = ch1, qIndex = 0, answer = "错答", verdict = "WRONG", feedback = "再想想", createdAt = 1L),
        )
        db.quizAttemptDao().insert(
            com.studyfriend.app.data.db.QuizAttemptEntity(chapterId = ch1, qIndex = 0, answer = "对答", verdict = "CORRECT", feedback = null, createdAt = 9L),
        )
        db.quizAttemptDao().insert(
            com.studyfriend.app.data.db.QuizAttemptEntity(chapterId = ch1, qIndex = 1, answer = "错答", verdict = "WRONG", feedback = "别混淆两章概念", createdAt = 2L),
        )
        db.quizAttemptDao().insert(
            com.studyfriend.app.data.db.QuizAttemptEntity(chapterId = ch2, qIndex = 0, answer = "错答", verdict = "WRONG", feedback = "注意细节", createdAt = 3L),
        )

        setContent()
        compose.onNodeWithText("错题本").performClick()
        scrollTo("只错过一次的题干（保留）")
        awaitText("只错过一次的题干（保留）")
        scrollTo("另一章的题干（保留）")
        awaitText("另一章的题干（保留）")
        // "先错后对"的题已出列：题干不得出现
        compose.waitUntil(3_000) {
            compose.onAllNodesWithText("先错后对的题干（应出列）").fetchSemanticsNodes().isEmpty()
        }
        // 展开详情：正确答案与当时反馈可见（章一行展开不影响章二行）
        scrollTo("只错过一次的题干（保留）")
        compose.onNodeWithText("只错过一次的题干（保留）").performClick()
        scrollTo("参考答案：答案B")
        awaitText("参考答案：答案B")
        awaitText("当时反馈：别混淆两章概念")
    }
}
