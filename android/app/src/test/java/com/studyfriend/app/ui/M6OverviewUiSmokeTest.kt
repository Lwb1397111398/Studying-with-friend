package com.studyfriend.app.ui

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.study.OverviewCodec
import com.studyfriend.app.data.study.OverviewPayload
import com.studyfriend.app.data.study.SummaryPlanner
import com.studyfriend.app.ui.screens.BookOverviewScreen
import com.studyfriend.app.ui.screens.ChapterListScreen
import com.studyfriend.app.ui.theme.StudyFriendTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M6 计划 §6 步骤④：总览 UI 冒烟（2 例，Robolectric Compose）——
 * 例 1 = 无 payload CTA + 无 Key 失败路径 + 有 payload 三 Tab 内容；
 * 例 2 = ChapterList 入口门控（<2 章无入口，≥2 章出现）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StudyApp::class)
class M6OverviewUiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private val db: StudyDatabase by lazy {
        (ApplicationProvider.getApplicationContext<StudyApp>()).database
    }
    private var bookId: Long = 0

    @Before
    fun seed(): Unit = runBlocking {
        bookId = db.bookDao().insert(
            BookEntity(
                title = "总览测试书", author = "佚名", sourceType = "TXT", filePath = "/tmp/o.txt",
                status = "READY", totalChapters = 2, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
    }

    @After
    fun tearDown(): Unit = runBlocking {
        // 全局 runner 状态归 Idle，防 Failed 残留影响同 JVM 后续测试
        ApplicationProvider.getApplicationContext<StudyApp>().overviewRunner.stop()
        db.bookDao().delete(
            BookEntity(
                id = bookId, title = "总览测试书", author = "佚名", sourceType = "TXT", filePath = "/tmp/o.txt",
                status = "READY", totalChapters = 2, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
    }

    private suspend fun seedSummarizedChapter(idx: Int) {
        val chId = db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = bookId, idx = idx, title = "总览第$idx 章", readState = "READ", gist = null, keyTermsJson = null)),
        )[0]
        db.chapterAssetDao().upsert(
            ChapterAssetEntity(
                chapterId = chId, summaryMd = "第$idx 章总结。", mindmapTree = "根\n\t枝", mindmapJson = null,
                memoryMd = "记忆。", chainMd = "串联。", quizJson = "[]",
                model = "test-model", promptVersion = SummaryPlanner.SUMMARY_VERSION, createdAt = 1L,
            ),
        )
    }

    private fun awaitText(text: String) {
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            println("=== SEMANTIC TREE (waiting for '$text') ===")
            println(compose.onRoot().printToString())
            throw e
        }
        compose.onAllNodesWithText(text, substring = true)[0].assertExists()
    }

    // ---------- 1a. 无 payload：CTA + 无 Key 失败路径 ----------

    @Test
    fun overview_ctaAndMissingKeyFailure(): Unit = runBlocking {
        seedSummarizedChapter(1)
        seedSummarizedChapter(2)

        compose.setContent { StudyFriendTheme { BookOverviewScreen(bookId = bookId, onBack = {}) } }
        awaitText("让搭子通览全书")
        awaitText("让搭子通览已总结的 2 章")
        // 驱动全局 runner（无 Key → keyIssue 失败路径）
        ApplicationProvider.getApplicationContext<StudyApp>().overviewRunner.start(bookId)
        awaitText("生成失败")
        awaitText("去填写 API Key")
        ApplicationProvider.getApplicationContext<StudyApp>().overviewRunner.stop()
    }

    // ---------- 1b. 有 payload：三 Tab 内容 + 顶栏重新生成 ----------

    @Test
    fun overview_payloadShowsThreeTabsAndRegen(): Unit = runBlocking {
        seedSummarizedChapter(1)
        seedSummarizedChapter(2)
        db.bookDao().saveOverviewJson(
            bookId,
            OverviewCodec.encode(
                OverviewPayload(
                    overviewMd = "全书总览内容：从概念到制度。",
                    treeText = "总览测试书\n\t总则\n\t物权",
                    mainlineMd = "一条主线串全书。",
                    promptVersion = OverviewCodec.VERSION, model = "test-model", createdAt = 1L,
                ),
            ),
            1L,
        )

        compose.setContent { StudyFriendTheme { BookOverviewScreen(bookId = bookId, onBack = {}) } }
        awaitText("全书总览内容：从概念到制度。") // 总览 Tab 默认显示
        compose.onNodeWithText("主线").performClick()
        awaitText("一条主线串全书。")
        awaitText("重新生成") // 顶栏操作可见（点击弹确认，弹窗交互属 M7 手工验证项）
    }

    // ---------- 2. 章目录入口门控：<2 章无入口，≥2 章出现 ----------

    @Test
    fun chapterList_overviewEntryGatedByTwoSummarized(): Unit = runBlocking {
        seedSummarizedChapter(1)
        compose.setContent {
            StudyFriendTheme {
                ChapterListScreen(bookId = bookId, onBack = {}, onOpenChapter = { _, _ -> }, onOpenOverview = {})
            }
        }
        awaitText("总览第1 章") // Flow 首发射，列表已渲染
        compose.waitUntil(3_000) {
            compose.onAllNodesWithText("全书总览（已总结 1 章）").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("全书总览（已总结 2 章）").fetchSemanticsNodes().isEmpty()
        }

        seedSummarizedChapter(2) // 第 2 章总结完成 → Flow 重发射 → 入口出现
        awaitText("全书总览（已总结 2 章）")
    }
}
