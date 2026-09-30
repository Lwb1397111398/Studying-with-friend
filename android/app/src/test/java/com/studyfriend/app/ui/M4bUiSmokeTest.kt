package com.studyfriend.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.study.NotePlanner
import com.studyfriend.app.data.study.ParaIdsCodec
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

/** M4b §5-6：讲解卡/讲解工具条冒烟（3 例，Robolectric Compose） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StudyApp::class)
class M4bUiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    // 屏幕内部经 StudyApp 拿库，冒烟必须 seed 同一实例（不 close，lazy 已固化）
    private val db: StudyDatabase by lazy {
        (ApplicationProvider.getApplicationContext<StudyApp>()).database
    }
    private var bookId: Long = 0
    private var chapterId: Long = 0
    private var explainParaId: Long = 0

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
                ParagraphEntity(
                    chapterId = chapterId, idx = 0, text = "重点段落", role = "BODY",
                    aiAction = "EXPLAIN", why = "核心概念首次出现",
                ),
                ParagraphEntity(chapterId = chapterId, idx = 1, text = "跳过段落", role = "BODY", aiAction = "SKIP"),
            ),
        )
        explainParaId = db.paragraphDao().byChapter(chapterId).first { it.aiAction == "EXPLAIN" }.id
    }

    @After
    fun tearDown() = runBlocking {
        db.bookDao().delete(
            com.studyfriend.app.data.db.BookEntity(
                id = bookId, title = "测试之书", author = "佚名", sourceType = "TXT", filePath = "/tmp/x.txt",
                status = "READY", totalChapters = 1, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
    }

    private suspend fun seedValidNote() {
        db.paraNoteDao().insert(
            ParaNoteEntity(
                chapterId = chapterId, paraIds = ParaIdsCodec.encode(listOf(explainParaId)),
                title = "民事能力小讲", friendly = "这段用大白话讲能力分档",
                analogy = "像孩子的成长", keyPointsJson = "[\"三档能力\"]",
                memoryHook = "从出生到成年", questionsJson = null,
                model = "m", promptVersion = NotePlanner.NOTE_VERSION, createdAt = 1L,
            ),
        )
    }

    /** 按钮等节点文本可能带计数后缀：用子串匹配 */
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text, substring = true).assertExists()
    }

    private fun setContent() {
        compose.setContent {
            StudyFriendTheme { ReadScreen(chapterId = chapterId, onBack = {}) }
        }
    }

    @Test
    fun notecard_rendersAllSections() = runBlocking {
        seedValidNote()
        setContent()
        awaitText("民事能力小讲")
        awaitText("这段用大白话讲能力分档")
        awaitText("打个比方：像孩子的成长")
        awaitText("三档能力")
        awaitText("记忆钩子：从出生到成年")
    }

    @Test
    fun notebar_showsGenerateWhenNoNotes() {
        setContent()
        awaitText("让搭子讲解本章")
        awaitText("重点段落") // 段落流正常渲染
    }

    @Test
    fun notebar_showsAllGeneratedWithRegenerate() = runBlocking {
        seedValidNote()
        setContent()
        awaitText("本章讲解已全部生成")
        awaitText("重新生成")
    }
}
