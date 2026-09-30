package com.studyfriend.app.ui

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun reviewItem(chapterId: Long, title: String, intervalIdx: Int = 1) = runBlocking {
        db.reviewItemDao().insertAll(
            listOf(
                ReviewItemEntity(
                    bookId = bookId, chapterId = chapterId, title = title,
                    intervalIdx = intervalIdx, dueAt = now - 1_000L, done = false,
                ),
            ),
        )
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

    private fun setContent() {
        compose.setContent { StudyFriendTheme { ReviewScreen() } }
    }

    // ---------- 1. 到期列表 → 评估流转 → 记得推进落库 + 行消失 ----------

    @Test
    fun review_dueFlowSessionTransitionAndMarkResult(): Unit = runBlocking {
        val chId = seedChapter("复习章")
        asset(chId, QuizQuestion("RECALL", "善意取得的构成要件？", "无权处分+善意+合理价格+登记", "按四要件记"))
        reviewItem(chId, "复习章", intervalIdx = 1)

        setContent()
        awaitText("复习章")
        compose.onNodeWithText("复习章").performClick()
        awaitText("善意取得的构成要件？")
        compose.onNodeWithText("看答案").performClick()
        awaitText("参考答案：无权处分+善意+合理价格+登记")
        compose.onNodeWithText("记得").performClick()

        // 行从 dueList Flow 消失（done=false 但 dueAt=+3d 不再到期 → 空态）
        awaitText("今天没有要复习的")
        // 落库断言：intervalIdx 1→2（3 天档 → 7 天档）
        val pending = db.reviewItemDao().pendingByBook(bookId).single()
        assertEquals(2, pending.intervalIdx)
        assertEquals(false, pending.done)
        assertTrue("dueAt 应推进到 7 天档", pending.dueAt in now + 6 * 86_400_000L..now + 8 * 86_400_000L)
    }

    // ---------- 2. 无题/无资产：退化单问，忘了归 0 ----------

    @Test
    fun review_noAssetFallbackSingleQuestion(): Unit = runBlocking {
        val chId = seedChapter("空资产章")
        reviewItem(chId, "空资产章", intervalIdx = 3)

        setContent()
        awaitText("空资产章")
        compose.onNodeWithText("空资产章").performClick()
        awaitText("这一章的内容还记得多少？")
        compose.onNodeWithText("忘了").performClick()

        awaitText("今天没有要复习的")
        val pending = db.reviewItemDao().pendingByBook(bookId).single()
        assertEquals(0, pending.intervalIdx) // 忘了 → 归 0
        assertEquals(false, pending.done)
        assertTrue("dueAt 应重排约 1 天", pending.dueAt in now..now + 2 * 86_400_000L)
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
        awaitText("只错过一次的题干（保留）")
        awaitText("另一章的题干（保留）")
        // "先错后对"的题已出列：题干不得出现
        compose.waitUntil(3_000) {
            compose.onAllNodesWithText("先错后对的题干（应出列）").fetchSemanticsNodes().isEmpty()
        }
        // 展开详情：正确答案与当时反馈可见（章一行展开不影响章二行）
        compose.onNodeWithText("只错过一次的题干（保留）").performClick()
        awaitText("参考答案：答案B")
        awaitText("当时反馈：别混淆两章概念")
    }
}
