package com.studyfriend.app.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.study.QuizCodec
import com.studyfriend.app.data.study.QuizQuestion
import com.studyfriend.app.ui.screens.ChapterAssetsScreen
import com.studyfriend.app.ui.theme.StudyFriendTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M5 计划 §5：章末总结页冒烟（3 例，Robolectric Compose）——
 * 无资产 CTA、有资产 Tab 切换（总结/记忆）、无 Key 触发生成的失败提示。
 * StudyApp.summaryRunner 未配 Key → MissingKeyException → Failed 文案，覆盖失败视图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StudyApp::class)
class M5UiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private val db: StudyDatabase by lazy {
        (ApplicationProvider.getApplicationContext<StudyApp>()).database
    }
    private var bookId: Long = 0
    private var chapterId: Long = 0

    @Before
    fun seed(): Unit = runBlocking {
        bookId = db.bookDao().insert(
            BookEntity(
                title = "测试之书", author = "佚名", sourceType = "TXT", filePath = "/tmp/x.txt",
                status = "READY", totalChapters = 1, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        chapterId = db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = bookId, idx = 1, title = "第一章 民法概说", readState = "READ", gist = null, keyTermsJson = null)),
        )[0]
        db.paragraphDao().insertAll(
            listOf(ParagraphEntity(chapterId = chapterId, idx = 0, text = "普通段落正文", role = "BODY", aiAction = "EXPLAIN")),
        )
    }

    @After
    fun tearDown() = runBlocking {
        // 全局 runner 状态归 Idle，防 Failed 残留改变后续测试的 CTA 视图
        ApplicationProvider.getApplicationContext<StudyApp>().summaryRunner.stop()
        // StudyApp 单例库不 close；删书级联清掉章节/段落/资产/排期，防污染同 JVM 后续测试
        db.bookDao().delete(
            BookEntity(
                id = bookId, title = "测试之书", author = "佚名", sourceType = "TXT", filePath = "/tmp/x.txt",
                status = "READY", totalChapters = 1, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
    }

    /** Room Flow 异步首发射：等节点出现后断言存在 */
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertExists()
    }

    private fun setContent() {
        compose.setContent {
            StudyFriendTheme { ChapterAssetsScreen(chapterId = chapterId, onBack = {}) }
        }
    }

    // ---------- 1. 无资产：CTA 视图 ----------

    @Test
    fun assets_emptyShowsCta(): Unit = runBlocking {
        setContent()
        awaitText("让搭子总结本章")
        awaitText("本章还没有总结包")
    }

    // ---------- 2. 有资产：四 Tab 与总结/记忆内容 ----------

    @Test
    fun assets_withAssetShowsTabsAndContent(): Unit = runBlocking {
        db.chapterAssetDao().upsert(
            ChapterAssetEntity(
                chapterId = chapterId,
                summaryMd = "## 本章总结\n民事行为能力分三档。",
                mindmapTree = "民事行为能力\n\t三档划分",
                mindmapJson = null,
                memoryMd = "先记三档数量。",
                chainMd = "孩子成长线一条主线。",
                quizJson = "[]",
                model = "test-model", promptVersion = "summary-pack-v1", createdAt = 1L,
            ),
        )
        setContent()
        awaitText("民事行为能力分三档。") // 总结 Tab 默认显示
        compose.onNodeWithText("记忆").performClick()
        awaitText("先记三档数量。")
        awaitText("孩子成长线一条主线。")
        compose.onNodeWithText("自测").performClick()
        awaitText("这套总结包没有自测题")
    }

    // ---------- 3. 无 Key 触发生成：失败提示可见 ----------

    @Test
    fun assets_missingKeyShowsFailure(): Unit = runBlocking {
        setContent()
        awaitText("让搭子总结本章")
        // 直接驱动全局 runner（未保存 Key → MissingKeyException）
        ApplicationProvider.getApplicationContext<StudyApp>().summaryRunner.start(chapterId)
        awaitText("生成失败")
        awaitText("API Key 已失效或未保存，请先到设置页填写")
    }

    // ---------- 4. 自测作答落库（计划 §5 UI 冒烟例 2） ----------

    @Test
    fun quiz_answerPersistsToAttempts(): Unit = runBlocking {
        db.chapterAssetDao().upsert(
            ChapterAssetEntity(
                chapterId = chapterId,
                summaryMd = "总结。",
                mindmapTree = "民事行为能力\n\t三档划分",
                mindmapJson = null,
                memoryMd = "记忆。",
                chainMd = "串联。",
                quizJson = QuizCodec.encode(
                    listOf(QuizQuestion("RECALL", "三档分别是什么？", "无、限制、完全", "按年龄界限记")),
                ),
                model = "test-model", promptVersion = "summary-pack-v1", createdAt = 1L,
            ),
        )
        setContent()
        awaitText("自测") // TabRow 由 Room Flow 首发射渲染，点击前先等节点（全量负载下偶发时序）
        compose.onNodeWithText("自测").performClick()
        awaitText("三档分别是什么？")
        compose.onNodeWithText("三档分别是什么？").performClick() // 展开题卡
        compose.onNode(hasSetTextAction()).performTextInput("无、限制、完全")
        compose.onNodeWithText("对答案").performClick()
        awaitText("参考答案：无、限制、完全")
        compose.onNodeWithText("答对").performClick()

        // 落库验证：attempts 出现一行 CORRECT
        withTimeout(5_000) {
            while (db.quizAttemptDao().byChapter(chapterId).isEmpty()) delay(50)
        }
        val attempt = db.quizAttemptDao().byChapter(chapterId).single()
        assertEquals("CORRECT", attempt.verdict)
        assertEquals("无、限制、完全", attempt.answer)
        assertEquals("按年龄界限记", attempt.feedback)
    }
}
