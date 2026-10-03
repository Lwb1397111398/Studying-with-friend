package com.studyfriend.app.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.ChapterEntity
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
 * P5 树 UI 冒烟（Robolectric Compose）——
 * 树形渲染+校准徽标、节行点击=目录锚回调（父章 id+highlight=节标题）、
 * 收起隐藏节行、未校准书无徽标。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StudyApp::class)
class P5TreeUiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    // 屏幕内部经 StudyApp 拿库，冒烟必须 seed 同一实例（Robolectric 文件库落临时沙盒）
    private val db by lazy {
        (ApplicationProvider.getApplicationContext<StudyApp>()).database
    }
    private var bookId: Long = 0
    private var ch1Id: Long = 0

    @Before
    fun seed(): Unit = runBlocking {
        bookId = db.bookDao().insert(
            com.studyfriend.app.data.db.BookEntity(
                title = "树形测试之书", author = "佚名", sourceType = "PDF", filePath = "/tmp/x.pdf",
                status = "READY", totalChapters = 2, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        val ids = db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = bookId, idx = 0, title = "第一章 民法概说", readState = "NOT_READ",
                    gist = null, keyTermsJson = null, level = 1, calibrated = true,
                ),
                ChapterEntity(
                    bookId = bookId, idx = 1, title = "第一节 民法调整对象", readState = "NOT_READ",
                    gist = null, keyTermsJson = null, level = 2, parentOrder = 1, calibrated = true,
                ),
                ChapterEntity(
                    bookId = bookId, idx = 2, title = "第二章 民法基本原则", readState = "NOT_READ",
                    gist = null, keyTermsJson = null, level = 1, calibrated = true,
                ),
            ),
        )
        ch1Id = ids[0]
    }

    @After
    fun tearDown() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM books") // 章段随 CASCADE 清空
    }

    /** Room Flow 异步首发射 + Robolectric 视口小：等节点出现后断言存在即可 */
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertExists()
    }

    private fun setContent(onOpenChapter: (Long, String?) -> Unit = { _, _ -> }) {
        compose.setContent {
            StudyFriendTheme {
                ChapterListScreen(
                    bookId = bookId,
                    onBack = {},
                    onOpenChapter = onOpenChapter,
                    onOpenOverview = {},
                )
            }
        }
    }

    @Test
    fun tree_showsChaptersSectionsAndBadge() {
        setContent()
        awaitText("第一章 民法概说")
        awaitText("第二章 民法基本原则")
        awaitText("✓ 已按目录校准")
        // Robolectric 视口小：节行滚到才合成
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("第一节 民法调整对象"))
        awaitText("第一节 民法调整对象")
    }

    @Test
    fun sectionRow_clickEmitsParentIdAndHighlight() {
        var capturedId: Long? = null
        var capturedHighlight: String? = null
        setContent { id, hl ->
            capturedId = id
            capturedHighlight = hl
        }
        awaitText("第一章 民法概说")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("第一节 民法调整对象"))
        compose.onNodeWithText("第一节 民法调整对象").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(ch1Id, capturedId)
            org.junit.Assert.assertEquals("第一节 民法调整对象", capturedHighlight)
        }
    }

    @Test
    fun collapseHidesSection() {
        setContent()
        awaitText("第一章 民法概说")
        compose.onNodeWithContentDescription("收起小节").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("第一节 民法调整对象").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun uncalibratedBookShowsNoBadge() = runBlocking {
        val rawBookId = db.bookDao().insert(
            com.studyfriend.app.data.db.BookEntity(
                title = "未校准之书", author = "佚名", sourceType = "TXT", filePath = "/tmp/y.txt",
                status = "READY", totalChapters = 1, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        db.chapterDao().insertAll(
            listOf(
                ChapterEntity(
                    bookId = rawBookId, idx = 0, title = "未校准第一章", readState = "NOT_READ",
                    gist = null, keyTermsJson = null,
                ),
            ),
        )
        bookId = rawBookId
        setContent()
        awaitText("未校准第一章")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("✓ 已按目录校准").fetchSemanticsNodes().isEmpty()
        }
    }
}
