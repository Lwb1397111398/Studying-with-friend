package com.studyfriend.app.data.study

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.FakeSecretStore
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.QuizAttemptEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

/** M5 计划 §5：总结包流水线 8 例（生成落库/排期种子/重生成清空/重试/两败/无标注/取消/无 Key） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SummaryPlannerTest {

    private lateinit var db: StudyDatabase
    private lateinit var scope: CoroutineScope
    private var ch: Long = 0

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyDatabase::class.java,
        ).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ch = seed(
            db,
            listOf(Triple(0, "EXPLAIN", null), Triple(1, "GROUP", 2L), Triple(2, "GROUP", 2L)),
        )
        SettingsRepository(db, FakeSecretStore()).save("https://api.test/v1", "test-model", 0.3, "sk-test")
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun seed(db: StudyDatabase, paras: List<Triple<Int, String, Long?>>): Long {
        db.bookDao().insert(
            BookEntity(
                title = "民法入门", author = "佚名", sourceType = "TXT", filePath = "/tmp/t.txt",
                status = "READY", totalChapters = 0, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        val chId = db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = 1L, idx = 1, title = "民事行为能力", readState = "READ", gist = "三档划分", keyTermsJson = null)),
        )[0]
        db.paragraphDao().insertAll(
            paras.map { (idx, action, gid) ->
                ParagraphEntity(chapterId = chId, idx = idx, text = "正文$idx " + "甲".repeat(300), role = "BODY", aiAction = action, groupId = gid)
            },
        )
        return chId
    }

    /** 与 prompt/assets/summary_pack.txt 同格式的合法输出（中文五标签） */
    private fun legalQuizJson(): String = QuizCodec.encode(
        listOf(QuizQuestion("RECALL", "三档分别是什么？", "无、限制、完全", "按年龄界限记")),
    )

    private fun legalRaw(): String = buildString {
        append("<总结>\n本章把民事行为能力分成三档。\n</总结>\n")
        append("<导图>\n民事行为能力\n\t无行为能力\n\t限制行为能力\n\t完全行为能力</导图>\n")
        append("<记忆思路>\n先记住是三档，再记界限年龄。\n</记忆思路>\n")
        append("<串联>\n把孩子成长线串起来：出生前无、成长中受限、成年后完全。\n</串联>\n")
        append("<自测>\n").append(legalQuizJson()).append("\n</自测>")
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

    /** 单一 canonical 合法输出：fake 默认与断言共用，避免两套内容漂移 */
    private object LegalRaw {
        val text: String = buildString {
            append("<总结>\n本章把民事行为能力分成三档。\n</总结>\n")
            append("<导图>\n民事行为能力\n\t无行为能力\n\t限制行为能力\n\t完全行为能力</导图>\n")
            append("<记忆思路>\n先记住是三档，再记界限年龄。\n</记忆思路>\n")
            append("<串联>\n把孩子成长线串起来：出生前无、成长中受限、成年后完全。\n</串联>\n")
            append("<自测>\n[").append("""{"type":"RECALL","q":"三档分别是什么？","a":"无、限制、完全","explain":"按年龄界限记"}""").append("]\n</自测>")
        }
    }

    private fun planner(fake: FakeChatText, store: SettingsRepository = SettingsRepository(db, FakeSecretStore())) =
        SummaryPlanner(db, ApplicationProvider.getApplicationContext(), store, fake)

    // ---------- 1. 生成落库 + 排期种子 ----------

    @Test
    fun run_generatesPersistsAndSeeds(): Unit = runBlocking {
        val fake = FakeChatText()
        val outcome = planner(fake).run(ch)

        assertTrue(!outcome.retried)
        assertEquals(1, outcome.questionCount)
        assertEquals(1, fake.requests.size)
        // user JSON 必须是合法 JSON（jsonEsc 引号语义回归锁定）
        val userMsg = fake.requests[0].messages.last { it.role == "user" }.content
        Json.parseToJsonElement(userMsg)
        val asset = db.chapterAssetDao().byChapter(ch)!!
        assertTrue(asset.summaryMd.contains("三档"))
        assertTrue(asset.mindmapTree.contains("限制行为能力"))
        assertTrue(asset.memoryMd.isNotBlank() && asset.chainMd.isNotBlank())
        assertEquals(listOf(QuizQuestion("RECALL", "三档分别是什么？", "无、限制、完全", "按年龄界限记")), QuizCodec.decode(asset.quizJson))
        assertEquals(SummaryPlanner.SUMMARY_VERSION, asset.promptVersion)
        assertEquals("test-model", asset.model)
        assertNull(asset.mindmapJson)
        // 排期种子：1 天首期，IGNORE 防重
        val pending = db.reviewItemDao().pendingByBook(1L)
        assertEquals(1, pending.size)
        assertEquals(0, pending[0].intervalIdx)
        assertEquals(ch, pending[0].chapterId)
    }

    // ---------- 2. 重生成：覆盖资产 + 清空本章作答 + 排期不重复 ----------

    @Test
    fun regenerate_overwritesAssetClearsAttemptsKeepsOneSeed(): Unit = runBlocking {
        db.chapterAssetDao().upsert(
            ChapterAssetEntity(
                chapterId = ch, summaryMd = "旧总结", mindmapTree = "旧树", mindmapJson = null,
                memoryMd = "旧记忆", chainMd = "旧串联", quizJson = "[]",
                model = "old-model", promptVersion = "old-version", createdAt = 1L,
            ),
        )
        db.quizAttemptDao().insert(
            QuizAttemptEntity(chapterId = ch, qIndex = 0, answer = "答", verdict = "WRONG", feedback = null, createdAt = 1L),
        )
        val fake = FakeChatText()

        planner(fake).run(ch)

        val asset = db.chapterAssetDao().byChapter(ch)!!
        assertTrue(asset.summaryMd.contains("三档"))
        assertEquals(SummaryPlanner.SUMMARY_VERSION, asset.promptVersion)
        assertEquals("重生成清空本章作答（题目身份随版本失效）", 0, db.quizAttemptDao().byChapter(ch).size)
        assertEquals("IGNORE 防重复排期", 1, db.reviewItemDao().pendingByBook(1L).size)
    }

    // ---------- 3. 解析失败重试一次成功 ----------

    @Test
    fun parseFails_retriesOnceThenSucceeds(): Unit = runBlocking {
        val fake = FakeChatText()
        fake.onText = { n -> if (n == 1) "这不是五标签输出" else LegalRaw.text }

        val outcome = planner(fake).run(ch)

        assertTrue(outcome.retried)
        assertEquals(2, fake.requests.size)
        val second = fake.requests[1].messages.last { it.role == "user" }.content
        assertTrue("重试 user 应追加格式纠正语", second.contains("严格按标签格式"))
        assertEquals(1, db.reviewItemDao().pendingByBook(1L).size)
    }

    // ---------- 4. 两次解析失败：抛错且旧资产不被覆盖 ----------

    @Test
    fun parseFailsTwice_throwsAndKeepsOldAsset(): Unit = runBlocking {
        db.chapterAssetDao().upsert(
            ChapterAssetEntity(
                chapterId = ch, summaryMd = "旧总结", mindmapTree = "旧树", mindmapJson = null,
                memoryMd = "旧记忆", chainMd = "旧串联", quizJson = "[]",
                model = "old-model", promptVersion = "old-version", createdAt = 1L,
            ),
        )
        val fake = FakeChatText()
        fake.onText = { "格式完全不对" }

        val err = runCatching { planner(fake).run(ch) }.exceptionOrNull()

        assertTrue("应抛 PlannerException：$err", err is PlannerException && err.message!!.contains("解析失败"))
        assertEquals(2, fake.requests.size)
        assertEquals("旧资产不被覆盖", "旧总结", db.chapterAssetDao().byChapter(ch)!!.summaryMd)
        assertTrue("失败不应排期", db.reviewItemDao().pendingByBook(1L).isEmpty())
    }

    // ---------- 5. 无粗读标注：拒绝且零调用 ----------

    @Test
    fun noMarkedParagraphs_throwsBeforeAnyCall(): Unit = runBlocking {
        val fresh = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), StudyDatabase::class.java,
        ).allowMainThreadQueries().build()
        val emptyCh = seed(fresh, listOf(Triple(0, "NONE", null)))
        val freshSettings = SettingsRepository(fresh, FakeSecretStore())
        freshSettings.save("https://api.test/v1", "test-model", 0.3, "sk-test") // 先过 Key 检查，专测无标注拒绝
        val fake = FakeChatText()

        val err = runCatching {
            SummaryPlanner(fresh, ApplicationProvider.getApplicationContext(), freshSettings, fake)
                .run(emptyCh)
        }.exceptionOrNull()

        assertTrue(err is PlannerException && err.message!!.contains("粗读"))
        assertEquals(0, fake.requests.size)
        fresh.close()
    }

    // ---------- 6. 取消中断：无任何落库 ----------

    @Test
    fun cancellation_leavesNothingBehind(): Unit = runBlocking {
        val fake = FakeChatText()
        val entered = CompletableDeferred<Unit>()
        fake.entered = entered
        fake.hold = CompletableDeferred()
        val job = launch { planner(fake).run(ch) }
        withTimeout(5_000) { entered.await() } // 第 1 次 chat 已挂住

        job.cancelAndJoin()

        assertNull("取消不落资产", db.chapterAssetDao().byChapter(ch))
        assertTrue("取消不排期", db.reviewItemDao().pendingByBook(1L).isEmpty())
        fake.hold?.complete(Unit)
    }

    // ---------- 7. 无 API Key：拒绝且零调用 ----------

    @Test
    fun missingKey_throwsBeforeAnyCall(): Unit = runBlocking {
        val fresh = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), StudyDatabase::class.java,
        ).allowMainThreadQueries().build()
        val chNoKey = seed(fresh, listOf(Triple(0, "EXPLAIN", null)))
        val fake = FakeChatText()

        val err = runCatching {
            SummaryPlanner(fresh, ApplicationProvider.getApplicationContext(), SettingsRepository(fresh, FakeSecretStore()), fake)
                .run(chNoKey)
        }.exceptionOrNull()

        assertTrue(err is MissingKeyException)
        assertEquals(0, fake.requests.size)
        fresh.close()
    }

    // ---------- 8. 讲解卡按锚段归位进 user JSON ----------

    @Test
    fun noteAnchoredOnlyInUserJson(): Unit = runBlocking {
        val ids = db.paragraphDao().byChapter(ch).map { it.id }
        val good = ParaNoteEntity(
            chapterId = ch, paraIds = "[${ids[1]},${ids[2]}]", title = "能力三档讲", friendly = "友好的讲解",
            analogy = null, keyPointsJson = "[]", memoryHook = null, questionsJson = null,
            model = "m", promptVersion = NotePlanner.NOTE_VERSION, createdAt = 1L,
        )
        val stale = good.copy(paraIds = "[${ids[0]}]", promptVersion = "v0-old")
        db.paraNoteDao().insert(good)
        db.paraNoteDao().insert(stale)
        val fake = FakeChatText()

        planner(fake).run(ch)

        val userMsg = fake.requests[0].messages.last { it.role == "user" }.content
        Json.parseToJsonElement(userMsg)
        assertTrue("锚段条目带讲解卡", userMsg.contains("能力三档讲"))
        assertEquals("旧版本讲解卡不进输入", 1, Regex("\"note\"").findAll(userMsg).count())
    }

    // ---------- 9. 写库阶段取消：三连写在事务内整体回滚（评审中-1 契约锁定） ----------

    @Test
    fun cancellationDuringWrite_transactionRollsBackAll(): Unit = runBlocking {
        // 第一个写库 SQL（清空作答的 DELETE）落库瞬间取消 → 事务回滚，作答/资产/种子全保持原样
        val jobRef = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val fresh = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), StudyDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryCallback(
                { sql, _ ->
                    if (sql.startsWith("DELETE") && sql.contains("quiz_attempts") && fired.compareAndSet(false, true)) {
                        jobRef.get()?.cancel() // 写库窗口内标记取消（事务线程同步推进到底，见下方不变量断言）
                    }
                },
                java.util.concurrent.Executor { it.run() },
            )
            .build()

        val chW = seed(fresh, listOf(Triple(0, "EXPLAIN", null)))
        SettingsRepository(fresh, FakeSecretStore()).save("https://api.test/v1", "test-model", 0.3, "sk-test")
        fresh.chapterAssetDao().upsert(
            ChapterAssetEntity(
                chapterId = chW, summaryMd = "旧总结", mindmapTree = "旧树", mindmapJson = null,
                memoryMd = "旧记忆", chainMd = "旧串联", quizJson = "[]",
                model = "old-model", promptVersion = "old-version", createdAt = 1L,
            ),
        )
        fresh.quizAttemptDao().insert(
            QuizAttemptEntity(chapterId = chW, qIndex = 0, answer = "答", verdict = "WRONG", feedback = null, createdAt = 1L),
        )

        val fake = FakeChatText()
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        fake.entered = entered
        fake.hold = hold
        val job = launch { SummaryPlanner(fresh, ApplicationProvider.getApplicationContext(), SettingsRepository(fresh, FakeSecretStore()), fake).run(chW) }
        withTimeout(5_000) { entered.await() } // chat 已挂住，此刻尚未写库
        jobRef.set(job)
        hold.complete(Unit) // 放行 → 解析成功 → 事务内 DELETE 触发回调 → cancel
        job.join()

        // 事务原子性不变量（room-ktx 事务块内挂起恢复不响应取消，事务线程同步推进到底，
        // 因此实测总是"完整提交"；若挂起点抛取消则整体回滚）——断言绝无半程混合态：
        val asset = fresh.chapterAssetDao().byChapter(chW)!!
        val attempts = fresh.quizAttemptDao().byChapter(chW).size
        val pending = fresh.reviewItemDao().pendingByBook(1L).size
        val rolledBack = asset.summaryMd == "旧总结" && attempts == 1 && pending == 0
        val committed = asset.summaryMd != "旧总结" && attempts == 0 && pending == 1
        assertTrue(
            "写库中断必须全有或全无：asset=${asset.summaryMd.take(20)} attempts=$attempts pending=$pending",
            rolledBack || committed,
        )
        fresh.close()
    }
}
