package com.studyfriend.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.StudyApp
import com.studyfriend.app.ui.screens.ChapterHero
import com.studyfriend.app.ui.screens.FlipCard
import com.studyfriend.app.ui.theme.StudyFriendTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 第九波实景轮 UI 冒烟（2 例，Robolectric Compose）：
 * ① FlipCard 翻卡——问题面点卡翻面后答案面出现、问题面消失（90° 处换面）；
 * ② ChapterHero 章扉——编号眉+章名/无编号眉两态渲染。
 * @Before 先碰一次 database：防 StudyApp.onCreate 的 VisionScheduler 协程跨用例
 * 泄漏 Robolectric SQLite 连接（第七波实录坑，M4aUiSmokeTest 同款做法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StudyApp::class)
class Wave9UiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun warmDatabase(): Unit = runBlocking {
        // 仅建立连接跑一次轻查询（单例库不 close，见 M4a 总览坑 7）
        (ApplicationProvider.getApplicationContext<StudyApp>()).database.chapterDao().byBook(0)
    }

    // ---------- 1. FlipCard：点卡翻面出答案 ----------

    @Test
    fun flipCard_flipShowsAnswerHidesQuestion() {
        compose.setContent {
            StudyFriendTheme {
                var flipped by remember { mutableStateOf(false) }
                FlipCard(
                    flipped = flipped,
                    onFlip = { flipped = true },
                    front = { Text("甲问题面") },
                    back = { Text("乙答案面") },
                )
            }
        }
        compose.onNodeWithText("甲问题面").assertExists("未翻面应显示问题")
        compose.onNodeWithText("乙答案面").assertDoesNotExist()

        compose.onNodeWithText("甲问题面").performClick()
        // 翻转 420ms + 答案渐显 180ms 延迟 + 320ms：推满时钟让换面完成
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()

        compose.onNodeWithText("乙答案面").assertExists("翻面后应显示答案")
        compose.onNodeWithText("甲问题面").assertDoesNotExist()
    }

    // ---------- 2. ChapterHero：编号眉/无编号眉两态渲染 ----------

    @Test
    fun chapterHero_rendersKickerAndTitle() {
        compose.setContent {
            StudyFriendTheme {
                Column {
                    ChapterHero(kicker = "第一章", title = "法律思维")
                    ChapterHero(kicker = null, title = "主要参考书目")
                }
            }
        }
        compose.onNodeWithText("第一章").assertExists()
        compose.onNodeWithText("法律思维").assertExists()
        compose.onNodeWithText("主要参考书目").assertExists()
    }
}
