package com.studyfriend.app.data.study

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiGate
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.FakeSecretStore
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** M4b §5-5：AiGate 互斥（2）+ 自替换 join 后重获（1）。Robolectric 真内存库。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteRunnerGateTest {

    private lateinit var db: StudyDatabase
    private lateinit var scope: CoroutineScope
    private val gate = AiGate()
    private var ch: Long = 0

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyDatabase::class.java,
        ).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        db.bookDao().insert(
            BookEntity(
                title = "测试书", author = "佚名", sourceType = "TXT", filePath = "/tmp/t.txt",
                status = "READY", totalChapters = 0, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        ch = db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = 1L, idx = 1, title = "第1章", readState = "NOT_READ", gist = null, keyTermsJson = null)),
        )[0]
        db.paragraphDao().insertAll(
            listOf(ParagraphEntity(chapterId = ch, idx = 0, text = "正文0甲甲甲", role = "BODY", aiAction = "EXPLAIN")),
        )
        SettingsRepository(db, FakeSecretStore()).save("https://api.test/v1", "m", 0.3, "sk-test")
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun notePlan() = NotePlan(title = "讲", friendly = "大白话讲解")

    /** 讲解假 chat：entered 非空时在挂起前先报信（保证"第 1 次"已被旧 job 消费，
     *  消除 cancel 抢在第 1 次调用前落地、把挂住资格错移给新 job 的竞态）；
     *  hold 挂住第 1 次调用，被取消时从 await 抛 CancellationException */
    @Suppress("UNCHECKED_CAST")
    private fun noteChatFn(hold: CompletableDeferred<Unit>?, entered: CompletableDeferred<Unit>? = null) = object : ChatJsonFn {
        private val calls = AtomicInteger(0)
        override suspend fun <T> invoke(
            req: ChatRequest,
            deserializer: DeserializationStrategy<T>,
            onDelta: (String) -> Unit,
        ): T {
            val n = calls.incrementAndGet()
            entered?.complete(Unit)
            if (hold != null && n == 1) {
                hold.await() // 挂住模拟进行中
                throw IllegalStateException("应被取消，不应走到这里")
            }
            return notePlan() as T
        }
    }

    /** 粗读假 chat：hold 非空时挂住；否则按反序列化目标出 plan/归并 */
    @Suppress("UNCHECKED_CAST")
    private fun roughChatFn(hold: CompletableDeferred<Unit>?) = object : ChatJsonFn {
        override suspend fun <T> invoke(
            req: ChatRequest,
            deserializer: DeserializationStrategy<T>,
            onDelta: (String) -> Unit,
        ): T {
            if (hold != null && req.messages.any { it.content.contains("正文0") }) hold.await()
            return when (deserializer) {
                MergedOverview.serializer() -> MergedOverview("梗概") as T
                else -> RoughReadPlan(gist = "梗概", paragraphs = listOf(PlanEntry(0, "explain"))) as T
            }
        }
    }

    private suspend fun awaitNoteState(runner: NoteRunner, pred: (NoteRunState) -> Boolean): NoteRunState {
        withTimeout(5_000) {
            while (!pred(runner.state.value)) delay(20)
        }
        return runner.state.value
    }

    private suspend fun awaitRoughState(runner: RoughReadRunner, pred: (RoughRunState) -> Boolean): RoughRunState {
        withTimeout(5_000) {
            while (!pred(runner.state.value)) delay(20)
        }
        return runner.state.value
    }

    private fun noteRunner(chatFn: ChatJsonFn) = NoteRunner(db, ApplicationProvider.getApplicationContext(), SettingsRepository(db, FakeSecretStore()), chatFn, scope, gate)

    private fun roughRunner(chatFn: ChatJsonFn) = RoughReadRunner(db, ApplicationProvider.getApplicationContext(), SettingsRepository(db, FakeSecretStore()), chatFn, scope, gate)

    // ---------- 1. 讲解进行中 → 粗读被拒 ----------

    @Test
    fun noteRunning_rejectsRoughStart(): Unit = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val note = noteRunner(noteChatFn(hold))
        val rough = roughRunner(roughChatFn(null))

        note.start(ch)
        awaitNoteState(note) { it is NoteRunState.Running }
        rough.start(ch)
        val failed = awaitRoughState(rough) { it is RoughRunState.Failed } as RoughRunState.Failed

        assertTrue(failed.message.contains("另一项 AI 任务"))
        hold.complete(Unit)
        awaitNoteState(note) { it is NoteRunState.Succeeded }
    }

    // ---------- 2. 粗读进行中 → 讲解被拒 ----------

    @Test
    fun roughRunning_rejectsNoteStart(): Unit = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val rough = roughRunner(roughChatFn(hold))
        val note = noteRunner(noteChatFn(null))

        rough.start(ch)
        awaitRoughState(rough) { it is RoughRunState.Running }
        note.start(ch)
        val failed = awaitNoteState(note) { it is NoteRunState.Failed } as NoteRunState.Failed

        assertTrue(failed.message.contains("另一项 AI 任务"))
        hold.complete(Unit)
        awaitRoughState(rough) { it is RoughRunState.Succeeded }
    }

    // ---------- 3. 同 runner 自替换：join 后重获 gate 正常完成 ----------

    @Test
    fun selfReplace_joinsAndRetakesGate(): Unit = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val note = noteRunner(noteChatFn(hold, entered))

        note.start(ch)
        awaitNoteState(note) { it is NoteRunState.Running }
        withTimeout(5_000) { entered.await() } // 第 1 次 chat 已挂住，cancel 必打断它
        note.start(ch) // 自替换：取消挂住的调用 → join → 重获 gate
        val final = awaitNoteState(note) { it is NoteRunState.Succeeded || it is NoteRunState.Failed }

        assertTrue("自替换不应误报另一任务占用：$final", final is NoteRunState.Succeeded)
        hold.complete(Unit)
    }
}
