package com.studyfriend.app.data.study

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.FakeSecretStore
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M4a §5-5：Runner 全局互斥（1 例）——start B 取消 A，B 正常跑完 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoughReadRunnerTest {

    private lateinit var db: StudyDatabase
    private lateinit var scope: CoroutineScope
    private var chA: Long = 0
    private var chB: Long = 0

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyDatabase::class.java,
        ).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        db.bookDao().insert(
            com.studyfriend.app.data.db.BookEntity(
                title = "测试书", author = "佚名", sourceType = "TXT", filePath = "/tmp/t.txt",
                status = "READY", totalChapters = 0, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        chA = seedChapter("甲章", "A 章正文")
        chB = seedChapter("乙章", "B 章正文")
        SettingsRepository(db, FakeSecretStore()).save("https://api.test/v1", "m", 0.3, "sk-test")
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun seedChapter(title: String, body: String): Long {
        val chapterId = db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = 1L, idx = title.hashCode(), title = title, readState = "NOT_READ", gist = null, keyTermsJson = null)),
        )[0]
        db.paragraphDao().insertAll(
            listOf(ParagraphEntity(chapterId = chapterId, idx = 0, text = body, role = "BODY")),
        )
        return chapterId
    }

    private fun plan() = RoughReadPlan(
        gist = "梗概",
        keyTerms = emptyList(),
        paragraphs = listOf(PlanEntry(0, "explain")),
    )

    @Suppress("UNCHECKED_CAST")
    private fun chatFn(gateA: CompletableDeferred<Unit>) = object : ChatJsonFn {
        override suspend fun <T> invoke(
            req: ChatRequest,
            deserializer: DeserializationStrategy<T>,
            onDelta: (String) -> Unit,
        ): T {
            if (req.messages.any { it.content.contains("A 章正文") }) {
                gateA.await() // 章 A 的调用挂住，模拟进行中
                throw IllegalStateException("A 应被取消，不应走到这里")
            }
            return when (deserializer) {
                MergedOverview.serializer() -> MergedOverview("梗概") as T
                else -> plan() as T
            }
        }
    }

    private suspend fun awaitState(runner: RoughReadRunner, timeoutMs: Long = 5_000, pred: (RoughRunState) -> Boolean) {
        withTimeout(timeoutMs) {
            while (!pred(runner.state.value)) kotlinx.coroutines.delay(20)
        }
    }

    @Test
    fun startB_cancelsA_andBCompletes() = runBlocking {
        val gateA = CompletableDeferred<Unit>()
        val runner = RoughReadRunner(db, ApplicationProvider.getApplicationContext(), settings(db), chatFn(gateA), scope)

        runner.start(chA)
        awaitState(runner) { it is RoughRunState.Running && it.chapterId == chA }

        runner.start(chB) // 互斥：替换 A
        // A 的 gate 释放后其协程应已取消（await 抛 CancellationException），不影响 B
        gateA.complete(Unit)

        awaitState(runner) { it is RoughRunState.Succeeded && it.chapterId == chB }
        assertTrue(runner.state.value is RoughRunState.Succeeded)
        assertEquals("EXPLAIN", db.paragraphDao().byChapter(chB)[0].aiAction)
    }

    private fun settings(db: StudyDatabase) = SettingsRepository(db, FakeSecretStore())
}
