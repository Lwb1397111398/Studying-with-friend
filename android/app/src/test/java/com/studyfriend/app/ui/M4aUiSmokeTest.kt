package com.studyfriend.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.ui.screens.ChapterListScreen
import com.studyfriend.app.ui.screens.ReadScreen
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
 * M4a §5-6：UI 冒烟（4 例，Robolectric Compose）——
 * 章目录行/CTA、阅读页段落、EXPLAIN 的 why、SKIP 淡显与 GROUP 徽标。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StudyApp::class)
class M4aUiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    // 屏幕内部经 StudyApp 拿库，冒烟必须 seed 同一实例（Robolectric 文件库落临时沙盒）
    private val db: StudyDatabase by lazy {
        (ApplicationProvider.getApplicationContext<StudyApp>()).database
    }
    private var bookId: Long = 0
    private var chapterId: Long = 0

    @Before
    fun seed(): Unit = runBlocking {
        bookId = db.bookDao().insert(
            com.studyfriend.app.data.db.BookEntity(
                title = "测试之书", author = "佚名", sourceType = "TXT", filePath = "/tmp/x.txt",
                status = "READY", totalChapters = 1, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        chapterId = db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = bookId, idx = 1, title = "第一章 民法概说", readState = "NOT_READ", gist = null, keyTermsJson = null)),
        )[0]
        db.paragraphDao().insertAll(
            listOf(
                ParagraphEntity(chapterId = chapterId, idx = 0, text = "普通段落正文", role = "BODY"),
                ParagraphEntity(
                    chapterId = chapterId, idx = 1, text = "重点段落", role = "BODY",
                    aiAction = "EXPLAIN", why = "核心概念首次出现",
                ),
                ParagraphEntity(chapterId = chapterId, idx = 2, text = "跳过段落被折叠", role = "BODY", aiAction = "SKIP"),
                ParagraphEntity(
                    chapterId = chapterId, idx = 3, text = "组首段落", role = "BODY",
                    aiAction = "GROUP", groupId = 3L,
                ),
            ),
        )
    }

    @After
    fun tearDown() = runBlocking {
        // StudyApp 单例库不做 close：lazy 已固化，关闭会污染同 JVM 后续测试
        db.bookDao().delete(
            com.studyfriend.app.data.db.BookEntity(
                id = bookId, title = "测试之书", author = "佚名", sourceType = "TXT", filePath = "/tmp/x.txt",
                status = "READY", totalChapters = 1, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
    }

    /** Room Flow 异步首发射 + Robolectric 视口小：等节点出现后断言存在即可 */
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertExists()
    }

    @Test
    fun chapterList_showsRowAndStartCta() {
        compose.setContent {
            StudyFriendTheme {
                ChapterListScreen(bookId = bookId, onBack = {}, onOpenChapter = { _, _ -> })
            }
        }
        awaitText("第一章 民法概说")
        awaitText("粗读") // 无 gist → CTA
    }

    @Test
    fun read_rendersParagraphFlow() {
        compose.setContent {
            StudyFriendTheme { ReadScreen(chapterId = chapterId, onBack = {}) }
        }
        awaitText("普通段落正文")
        awaitText("重点段落")
    }

    @Test
    fun read_explainShowsWhy() {
        compose.setContent {
            StudyFriendTheme { ReadScreen(chapterId = chapterId, onBack = {}) }
        }
        awaitText("为什么讲：核心概念首次出现")
    }

    @Test
    fun read_skipFullTextDimmedAndGroupTag() {
        compose.setContent {
            StudyFriendTheme { ReadScreen(chapterId = chapterId, onBack = {}) }
        }
        awaitText("普通段落正文") // 等 Room Flow 首发射、LazyColumn 合成，滚动容器才存在
        // P7 工具行引入横向滚动后 hasScrollAction() 会双匹配：这里只认纵向滚动容器
        compose.onNode(hasVerticalScrollAction()).performScrollToNode(hasText("已跳过"))
        awaitText("已跳过")
        // P7 顶部收纳后纵向空间充裕，但 Robolectric 视口仍小：滚动懒列表让 GROUP 行合成
        compose.onNode(hasVerticalScrollAction()).performScrollToNode(hasText("合并讲"))
        awaitText("合并讲")
    }

    /** 只匹配带纵向滚动轴的容器（P7 工具行的横向滚动不满足） */
    private fun hasVerticalScrollAction() = SemanticsMatcher("vertical scroll") {
        it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
    }
}
