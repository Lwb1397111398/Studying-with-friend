package com.studyfriend.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.study.NoteRunState
import com.studyfriend.app.data.study.RoughRunState
import com.studyfriend.app.ui.screens.ChapterToolsRow
import com.studyfriend.app.ui.theme.StudyFriendTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 粗读/讲解规划态 UI 冒烟（2 例，Robolectric Compose，2026-11-28）：
 * 老板装机实录「点击继续或者讲解完全看不到进度」——根因是规划阶段（AI 通读本章拆讲解
 * 单元，块数还是 0）界面显示「粗读 0/0」死条不动。本轮改为「规划中」阶段文案 + 不定进度
 * 条。两例锁定：粗读规划态/讲解规划态都显示阶段文案而非 0/0。
 * @Before 先碰一次 database：防 StudyApp.onCreate 的 VisionScheduler 协程跨用例
 * 泄漏 Robolectric SQLite 连接（Wave9UiSmokeTest 同款做法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StudyApp::class)
class ReadToolsUiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun warmDatabase(): Unit = runBlocking {
        (ApplicationProvider.getApplicationContext<StudyApp>()).database.chapterDao().byBook(0)
    }

    @Composable
    private fun row(
        runState: RoughRunState,
        noteState: NoteRunState,
    ) = ChapterToolsRow(
        chapterId = 1L,
        hasGist = false,
        gistExpanded = false,
        onToggleGist = {},
        readDone = false,
        paragraphCount = 10,
        pureToc = false,
        markedCount = 0,
        runState = runState,
        noteState = noteState,
        gateLabel = null,
        noteUnitCount = 3,
        noteValidCount = 0,
        ttsActive = false,
        ttsPaused = false,
        ttsAvailable = false,
        onToggleTts = {},
        onStart = {},
        onForce = {},
        onMerge = {},
        onStop = {},
        onNoteGenerate = {},
        onNoteForce = {},
        onNoteStop = {},
        onMarkDone = {},
        onOpenSettings = {},
        onOpenSummary = {},
    )

    @Test
    fun roughPlanning_showsPhaseTextNotZeroSlashZero() {
        compose.setContent {
            StudyFriendTheme {
                row(RoughRunState.Running(chapterId = 1, done = 0, total = 0), NoteRunState.Idle)
            }
        }
        compose.onNodeWithText("搭子正在通读本章，规划讲解单元…").assertExists("规划态应显示阶段文案")
        compose.onNodeWithText("粗读规划中").assertExists("chip 应显示规划中而非 0/0")
        compose.onNodeWithText("停止").assertExists("规划态仍可停止")
    }

    @Test
    fun notePlanning_showsPhaseTextNotZeroSlashZero() {
        compose.setContent {
            StudyFriendTheme {
                row(RoughRunState.Idle, NoteRunState.Running(chapterId = 1, done = 0, total = 0))
            }
        }
        compose.onNodeWithText("搭子正在准备讲解，先规划讲解单元…").assertExists("讲解规划态应显示阶段文案")
        compose.onNodeWithText("讲解规划中").assertExists("chip 应显示规划中而非 0/0")
    }
}
