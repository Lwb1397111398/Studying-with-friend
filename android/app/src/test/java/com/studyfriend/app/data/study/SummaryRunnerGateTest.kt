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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** M5 §5：总结 runner 与既有 runner 共用 AiGate——互斥双向（2）+ 自替换 join 后重获（1） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SummaryRunnerGateTest {

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

    /** 总结假 chat：先发一个 delta 让 Streaming 可观测；hold 挂住第 1 次调用；
     *  entered 在挂起前报信（同 NoteRunnerGateTest 的竞态消除纪律） */
    private fun summaryChatFn(hold: CompletableDeferred<Unit>?, entered: CompletableDeferred<Unit>? = null) = object : ChatTextFn {
        private val calls = AtomicInteger(0)
        override suspend fun invoke(req: ChatRequest, onDelta: (String) -> Unit): String {
            val n = calls.incrementAndGet()
            entered?.complete(Unit)
            onDelta("部分输出")
            if (hold != null && n == 1) hold.await() // 挂住模拟进行中；恢复后正常返回（总结无 chat 异常重试）
            return legalRaw()
        }
    }

    private fun legalRaw(): String = buildString {
        append("<总结>\n总结内容。\n</总结>\n")
        append("<导图>\n第1章\n\t要点</导图>\n")
        append("<记忆思路>\n记忆思路。\n</记忆思路>\n")
        append("<串联>\n串联成一条线。\n</串联>\n")
        append("<自测>\n[").append("""{"type":"RECALL","q":"问","a":"答","explain":"解"}""").append("]\n</自测>")
    }

    /** 讲解假 chat：hold 挂住第 1 次调用 */
    @Suppress("UNCHECKED_CAST")
    private fun noteChatFn(hold: CompletableDeferred<Unit>?) = object : ChatJsonFn {
        private val calls = AtomicInteger(0)
        override suspend fun <T> invoke(
            req: ChatRequest,
            deserializer: DeserializationStrategy<T>,
            onDelta: (String) -> Unit,
        ): T {
            val n = calls.incrementAndGet()
            if (hold != null && n == 1) hold.await()
            return NotePlan(title = "讲", friendly = "大白话讲解") as T
        }
    }

    private suspend fun awaitSummary(runner: SummaryRunner, pred: (SummaryRunState) -> Boolean): SummaryRunState {
        withTimeout(5_000) {
            while (!pred(runner.state.value)) delay(20)
        }
        return runner.state.value
    }

    private suspend fun awaitNote(runner: NoteRunner, pred: (NoteRunState) -> Boolean): NoteRunState {
        withTimeout(5_000) {
            while (!pred(runner.state.value)) delay(20)
        }
        return runner.state.value
    }

    private fun summaryRunner(chatFn: ChatTextFn) = SummaryRunner(
        db, ApplicationProvider.getApplicationContext(), SettingsRepository(db, FakeSecretStore()), chatFn, scope, gate,
    )

    private fun noteRunner(chatFn: ChatJsonFn) = NoteRunner(
        db, ApplicationProvider.getApplicationContext(), SettingsRepository(db, FakeSecretStore()), chatFn, scope, gate,
    )

    // ---------- 1. 总结进行中 → 讲解被拒 ----------

    @Test
    fun summaryRunning_rejectsNoteStart(): Unit = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val summary = summaryRunner(summaryChatFn(hold, entered))
        val note = noteRunner(noteChatFn(null))

        summary.start(ch)
        awaitSummary(summary) { it is SummaryRunState.Streaming }
        withTimeout(5_000) { entered.await() }
        note.start(ch)
        val failed = awaitNote(note) { it is NoteRunState.Failed } as NoteRunState.Failed

        assertTrue(failed.message.contains("另一项 AI 任务"))
        hold.complete(Unit)
        awaitSummary(summary) { it is SummaryRunState.Succeeded }
    }

    // ---------- 2. 讲解进行中 → 总结被拒 ----------

    @Test
    fun noteRunning_rejectsSummaryStart(): Unit = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val summary = summaryRunner(summaryChatFn(null))
        val note = noteRunner(noteChatFn(hold))

        note.start(ch)
        awaitNote(note) { it is NoteRunState.Running }
        summary.start(ch)
        val failed = awaitSummary(summary) { it is SummaryRunState.Failed } as SummaryRunState.Failed

        assertTrue(failed.message.contains("另一项 AI 任务"))
        hold.complete(Unit)
        awaitNote(note) { it is NoteRunState.Succeeded }
    }

    // ---------- 3. 总结自替换：join 后重获 gate 正常完成 ----------

    @Test
    fun selfReplace_joinsAndRetakesGate(): Unit = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val summary = summaryRunner(summaryChatFn(hold, entered))

        summary.start(ch)
        awaitSummary(summary) { it is SummaryRunState.Streaming }
        withTimeout(5_000) { entered.await() } // 第 1 次 chat 已挂住，cancel 必打断它
        summary.start(ch) // 自替换：取消挂住的调用 → join → 重获 gate
        val final = awaitSummary(summary) { it is SummaryRunState.Succeeded || it is SummaryRunState.Failed }

        assertTrue("自替换不应误报另一任务占用：$final", final is SummaryRunState.Succeeded)
        hold.complete(Unit)
    }

    // ---------- 4. 流式 partial：多次 delta 累计，重试归零（计划 §5 Runner 组） ----------

    @Test
    fun streaming_partialAccumulatesAndResetsOnRetry(): Unit = runBlocking {
        val hold1 = CompletableDeferred<Unit>()
        val hold2 = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var call = 0
        val chatFn = object : ChatTextFn {
            override suspend fun invoke(req: ChatRequest, onDelta: (String) -> Unit): String {
                call++
                entered.complete(Unit)
                return if (call == 1) {
                    onDelta("部分")
                    onDelta("输出")
                    hold1.await()
                    "格式不对" // 第一次解析失败 → 触发重试
                } else {
                    onDelta("重来")
                    hold2.await()
                    legalRaw()
                }
            }
        }
        val summary = summaryRunner(chatFn)

        summary.start(ch)
        withTimeout(5_000) { entered.await() }
        // 第一次尝试：partial 是累计串（非最后一个 delta）
        awaitSummary(summary) { it is SummaryRunState.Streaming && it.partial == "部分输出" }
        hold1.complete(Unit)
        // 重试归零：第二次 partial 不携带第一次内容
        awaitSummary(summary) { it is SummaryRunState.Streaming && it.partial == "重来" }
        hold2.complete(Unit)
        awaitSummary(summary) { it is SummaryRunState.Succeeded }
    }

    // ---------- 5. stop：回 Idle 且 gate 释放 ----------

    @Test
    fun stop_returnsIdleAndReleasesGate(): Unit = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val summary = summaryRunner(summaryChatFn(hold, entered))

        summary.start(ch)
        awaitSummary(summary) { it is SummaryRunState.Streaming }
        withTimeout(5_000) { entered.await() }

        summary.stop()

        assertEquals(SummaryRunState.Idle, summary.state.value)
        // 等取消传播到 finally gate.end()，随后新任务必须能立即抢到 gate
        withTimeout(5_000) { while (gate.label.value != null) delay(20) }
        assertTrue("gate 已释放", gate.tryBegin("自检"))
        gate.end()
        hold.complete(Unit)
    }
}
