package com.studyfriend.app.data.study

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.FakeSecretStore
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M6 计划 §6 步骤③：全书总览流水线 6 例（落库/重试/两败/前置拒绝/预算截断/取消零落库） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverviewPlannerTest {

    private lateinit var db: StudyDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyDatabase::class.java,
        ).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        SettingsRepository(db, FakeSecretStore()).save("https://api.test/v1", "test-model", 0.3, "sk-test")
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun seedBook(title: String = "民法入门"): Long = db.bookDao().insert(
        BookEntity(
            title = title, author = "佚名", sourceType = "TXT", filePath = "/tmp/o.txt",
            status = "READY", totalChapters = 0, overviewJson = null, createdAt = 1L, updatedAt = 1L,
        ),
    )

    private suspend fun seedChapter(bookId: Long, idx: Int, title: String = "第$idx 章"): Long =
        db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = bookId, idx = idx, title = title, readState = "READ", gist = null, keyTermsJson = null)),
        )[0]

    private suspend fun seedSummarizedChapter(bookId: Long, idx: Int): Long {
        val chId = seedChapter(bookId, idx)
        db.chapterAssetDao().upsert(
            ChapterAssetEntity(
                chapterId = chId, summaryMd = "第$idx 章总结：民事行为能力三档。", mindmapTree = "根\n\t枝",
                mindmapJson = null, memoryMd = "记忆$idx", chainMd = "第$idx 章串联线",
                quizJson = "[]", model = "test-model", promptVersion = SummaryPlanner.SUMMARY_VERSION, createdAt = 1L,
            ),
        )
        return chId
    }

    private fun legalRaw(): String = buildString {
        append("<总览>\n全书从概念到制度层层推进。\n</总览>\n")
        append("<导图>\n民法入门\n\t总则\n\t\t行为能力\n\t物权\n</导图>\n")
        append("<主线>\n把各章串成制度演进的一条线。\n</主线>")
    }

    private class FakeChatText : ChatTextFn {
        val requests = mutableListOf<ChatRequest>()
        var onText: (Int) -> String = { _ -> LegalRaw.text }
        var hold: CompletableDeferred<Unit>? = null
        var entered: CompletableDeferred<Unit>? = null
        override suspend fun invoke(req: ChatRequest, onDelta: (String) -> Unit): String {
            requests.add(req)
            entered?.complete(Unit)
            hold?.await()
            onDelta("部分输出")
            return onText(requests.size)
        }
    }

    private object LegalRaw {
        val text: String = buildString {
            append("<总览>\n全书从概念到制度层层推进。\n</总览>\n")
            append("<导图>\n民法入门\n\t总则\n\t\t行为能力\n\t物权\n</导图>\n")
            append("<主线>\n把各章串成制度演进的一条线。\n</主线>")
        }
    }

    private fun planner(fake: FakeChatText) =
        OverviewPlanner(db, ApplicationProvider.getApplicationContext(), SettingsRepository(db, FakeSecretStore()), fake)

    // ---------- 1. 生成落库：overviewJson 编码 payload + 版本/模型 ----------

    @Test
    fun run_generatesAndPersistsPayload(): Unit = runBlocking {
        val bookId = seedBook()
        seedSummarizedChapter(bookId, 1)
        seedSummarizedChapter(bookId, 2)
        val fake = FakeChatText()

        val outcome = planner(fake).run(bookId)

        assertTrue(!outcome.retried)
        assertEquals(1, fake.requests.size)
        val userMsg = fake.requests[0].messages.last { it.role == "user" }.content
        Json.parseToJsonElement(userMsg)
        assertTrue("只收已总结章", userMsg.contains("第1 章总结"))
        assertEquals("temp 0.5", 0.5, fake.requests[0].temperature, 0.0)
        assertEquals("maxTokens 16000", 16_000, fake.requests[0].maxTokens)
        val payload = OverviewCodec.decode(db.bookDao().getOverviewJson(bookId))!!
        assertTrue(payload.overviewMd.contains("层层推进"))
        assertTrue(payload.treeText.contains("行为能力"))
        assertTrue(payload.mainlineMd.isNotBlank())
        assertEquals(OverviewCodec.VERSION, payload.promptVersion)
        assertEquals("test-model", payload.model)
    }

    // ---------- 2. 解析失败重试一次成功 ----------

    @Test
    fun parseFails_retriesOnceThenSucceeds(): Unit = runBlocking {
        val bookId = seedBook()
        seedSummarizedChapter(bookId, 1)
        seedSummarizedChapter(bookId, 2)
        val fake = FakeChatText()
        fake.onText = { n -> if (n == 1) "这不是三标签输出" else LegalRaw.text }

        val outcome = planner(fake).run(bookId)

        assertTrue(outcome.retried)
        assertEquals(2, fake.requests.size)
        val second = fake.requests[1].messages.last { it.role == "user" }.content
        assertTrue("重试 user 应追加格式纠正语", second.contains("严格按标签格式"))
        assertTrue(OverviewCodec.decode(db.bookDao().getOverviewJson(bookId)) != null)
    }

    // ---------- 3. 两次解析失败：抛错且旧总览不被覆盖 ----------

    @Test
    fun parseFailsTwice_throwsAndKeepsOldOverview(): Unit = runBlocking {
        val bookId = seedBook()
        seedSummarizedChapter(bookId, 1)
        seedSummarizedChapter(bookId, 2)
        db.bookDao().saveOverviewJson(bookId, "旧总览", 1L)
        val fake = FakeChatText()
        fake.onText = { "格式完全不对" }

        val err = runCatching { planner(fake).run(bookId) }.exceptionOrNull()

        assertTrue("应抛 PlannerException：$err", err is PlannerException && err.message!!.contains("解析失败"))
        assertEquals(2, fake.requests.size)
        assertEquals("旧总览不被覆盖", "旧总览", db.bookDao().getOverviewJson(bookId))
    }

    // ---------- 4. 已总结章 <2：前置拒绝且零调用 ----------

    @Test
    fun fewerThanTwoSummarized_throwsBeforeAnyCall(): Unit = runBlocking {
        val bookId = seedBook()
        seedSummarizedChapter(bookId, 1) // 只有 1 章已总结
        seedChapter(bookId, 2) // 第二章未总结，不进输入也不计门槛
        val fake = FakeChatText()

        val err = runCatching { planner(fake).run(bookId) }.exceptionOrNull()

        assertTrue("应拒绝：$err", err is PlannerException && err.message!!.contains("至少读完并总结 2 章"))
        assertEquals(0, fake.requests.size)
        assertNull(db.bookDao().getOverviewJson(bookId))
    }

    // ---------- 5. 预算截断：>120 章取前 120 + truncated 标记 ----------

    @Test
    fun moreThanChapterLimit_takesFirst120WithTruncatedFlag(): Unit = runBlocking {
        val bookId = seedBook()
        repeat(125) { i -> seedSummarizedChapter(bookId, i + 1) }
        val fake = FakeChatText()

        planner(fake).run(bookId)

        val userMsg = fake.requests[0].messages.last { it.role == "user" }.content
        assertEquals("只取前 120 章", 120, Regex("\\{\"idx\":").findAll(userMsg).count())
        assertTrue("附 truncated 标记", userMsg.contains("\"truncated\":true"))
        assertTrue(userMsg.contains("\"idx\":120"))
        assertTrue("第 121 章不进输入", !userMsg.contains("\"idx\":121"))
    }

    // ---------- 6. 取消中断：零落库 ----------

    @Test
    fun cancellation_leavesNothingBehind(): Unit = runBlocking {
        val bookId = seedBook()
        seedSummarizedChapter(bookId, 1)
        seedSummarizedChapter(bookId, 2)
        val fake = FakeChatText()
        val entered = CompletableDeferred<Unit>()
        fake.entered = entered
        fake.hold = CompletableDeferred()
        val job = launch { planner(fake).run(bookId) }
        withTimeout(5_000) { entered.await() }

        job.cancelAndJoin()

        assertNull("取消不落总览", db.bookDao().getOverviewJson(bookId))
        fake.hold?.complete(Unit)
    }
}
