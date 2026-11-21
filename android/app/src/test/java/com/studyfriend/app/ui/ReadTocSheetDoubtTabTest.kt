package com.studyfriend.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.ChapterWithAsset
import com.studyfriend.app.data.db.ParaMemoEntity
import com.studyfriend.app.data.db.ParaMemoWithChapter
import com.studyfriend.app.ui.screens.ReadTocSheetContent
import com.studyfriend.app.ui.theme.StudyFriendTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 实景体验轮第七波：抽屉「存疑」Tab（待弄懂清单）——三 Tab 渲染、批注/存疑按 kind 分流、
 *  存疑列表显示章名+引用。模拟器三键导航遮挡 Tab 触摸（UNMEASURED 目验限制），本类锁定逻辑。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StudyApp::class)
class ReadTocSheetDoubtTabTest {

    @get:Rule
    val compose = createComposeRule()

    // StudyApp.onCreate 的 VisionScheduler 协程会异步建库——先在测试线程把连接稳定建立，
    // 防「Illegal connection pointer」跨用例泄漏（M4aUiSmokeTest 同款先例）
    @Before
    fun warmUpDb(): Unit = runBlocking {
        (ApplicationProvider.getApplicationContext<StudyApp>()).database.bookDao().get(1)
    }

    private val chapter = ChapterWithAsset(
        id = 1, idx = 0, title = "第一章 债之关系", readState = "NOT_READ",
        gist = null, keyTermsJson = null, hasAsset = false, assetPromptVersion = null,
        assetSummaryPresent = false,
    )

    private val noteMemo = ParaMemoWithChapter(
        memo = ParaMemoEntity(
            id = 11, bookId = 1, chapterId = 1, paraId = 1, paraIdx = 0,
            quote = "代理权授予他人", text = "这里和委托合同的区别要记牢",
            createdAt = 15L, kind = ParaMemoEntity.KIND_NOTE,
        ),
        chapterTitle = "第一章 债之关系", chapterIdx = 0,
    )

    private val doubtMemo = ParaMemoWithChapter(
        memo = ParaMemoEntity(
            id = 12, bookId = 1, chapterId = 1, paraId = 2, paraIdx = 1,
            quote = "善意取得的例外情形", text = "",
            createdAt = 16L, kind = ParaMemoEntity.KIND_DOUBT,
        ),
        chapterTitle = "第一章 债之关系", chapterIdx = 0,
    )

    /** 直渲染抽屉内容（sheet 壳去掉）：tab 入参直控，免弹层 window 的语义树限制 */
    private fun setContent(memos: List<ParaMemoWithChapter>, tab: Int = 0) {
        compose.setContent {
            StudyFriendTheme {
                ReadTocSheetContent(
                    chapters = listOf(chapter),
                    currentChapterId = 1,
                    bookmarks = emptyList(),
                    memos = memos,
                    memoTab = tab,
                    onTabChange = {},
                    onOpenChapter = {},
                    onAddBookmark = {},
                    onOpenBookmark = {},
                    onDeleteBookmark = {},
                    onOpenMemo = {},
                    onDeleteMemo = {},
                )
            }
        }
    }

    @Test
    fun threeTabs_renderWithKindSplitCounts() {
        setContent(listOf(noteMemo, doubtMemo))
        // 三 Tab 计数：批注只算 note、存疑只算 doubt
        compose.onNodeWithText("书签 0").assertExists()
        compose.onNodeWithText("批注 1").assertExists()
        compose.onNodeWithText("存疑 1").assertExists()
    }

    @Test
    fun doubtTab_showsQuoteAndHidesNote() {
        setContent(listOf(noteMemo, doubtMemo), tab = 2)
        compose.waitForIdle()
        compose.onNodeWithText("「善意取得的例外情形」").assertExists()
        // note 的内容不出现在存疑列表
        compose.onNodeWithText("「代理权授予他人」这里和委托合同的区别要记牢", substring = true).assertDoesNotExist()
    }

    @Test
    fun noteTab_keepsLegacyBehavior() {
        setContent(listOf(noteMemo, doubtMemo), tab = 1)
        compose.waitForIdle()
        compose.onNodeWithText("「代理权授予他人」这里和委托合同的区别要记牢", substring = true).assertExists()
        // doubt 不出现在批注 Tab
        compose.onNodeWithText("「善意取得的例外情形」", substring = true).assertDoesNotExist()
    }

    @Test
    fun doubtTab_emptyStateGuidesUser() {
        setContent(listOf(noteMemo), tab = 2)
        compose.waitForIdle()
        compose.onNodeWithText("还没有存疑", substring = true).assertExists()
    }
}
