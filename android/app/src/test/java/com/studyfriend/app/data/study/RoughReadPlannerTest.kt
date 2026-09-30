package com.studyfriend.app.data.study

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiException
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.FakeSecretStore
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M4a §5-4：粗读流水线（11 例 + runMergeOnly 1 例）。
 * 真 Room 内存库 + 假 ChatJsonFn（按反序列化目标区分块调用/归并调用），
 * 覆盖断点跳过、块级重试、中断落库、归并降级三态、force 覆盖、无 Key 前置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoughReadPlannerTest {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    private class FakeChat : ChatJsonFn {
        var onBlock: (callIndex: Int, req: ChatRequest) -> RoughReadPlan = { _, _ ->
            throw AiException("意外的块调用")
        }
        var onMerge: (callIndex: Int, req: ChatRequest) -> MergedOverview = { _, _ ->
            throw AiException("意外的归并调用")
        }
        var blockCalls = 0
        var mergeCalls = 0
        val requests = mutableListOf<ChatRequest>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> invoke(
            req: ChatRequest,
            deserializer: DeserializationStrategy<T>,
            onDelta: (String) -> Unit,
        ): T {
            requests += req
            return when (deserializer) {
                RoughReadPlan.serializer() -> onBlock(++blockCalls, req) as T
                MergedOverview.serializer() -> onMerge(++mergeCalls, req) as T
                else -> throw IllegalStateException("未预期的反序列化目标：$deserializer")
            }
        }
    }

    private fun settings(db: StudyDatabase) = SettingsRepository(db, FakeSecretStore())

    private fun planner(db: StudyDatabase, fn: ChatJsonFn) = RoughReadPlanner(
        db = db,
        context = ApplicationProvider.getApplicationContext(),
        settings = settings(db),
        chatJsonFn = fn,
    )

    /** 建一章 paras 段、每段 textLen 字；返回 chapterId（先补父书，chapters 有 FK） */
    private fun seedChapter(db: StudyDatabase, paras: Int, textLen: Int = 30, tag: String = "正文"): Long =
        runBlocking {
            if (db.bookDao().get(1L) == null) db.bookDao().insert(
                com.studyfriend.app.data.db.BookEntity(
                    title = "测试书", author = "佚名", sourceType = "TXT", filePath = "/tmp/t.txt",
                    status = "READY", totalChapters = 0, overviewJson = null, createdAt = 1L, updatedAt = 1L,
                ),
            )
            val chapterId = db.chapterDao().insertAll(
                listOf(ChapterEntity(bookId = 1L, idx = 1, title = "第1章", readState = "NOT_READ", gist = null, keyTermsJson = null)),
            )[0]
            db.paragraphDao().insertAll(
                (0 until paras).map { p ->
                    ParagraphEntity(chapterId = chapterId, idx = p, text = "$tag$p " + "甲".repeat(textLen), role = "BODY")
                },
            )
            chapterId
        }

    private fun fullPlan(idxs: List<Int>, gist: String = "块梗概") = RoughReadPlan(
        gist = gist,
        keyTerms = listOf(KeyTerm("要点", "通俗解释")),
        paragraphs = idxs.map { PlanEntry(id = it, action = "explain") },
    )

    private suspend fun withKey(db: StudyDatabase) = settings(db).apply {
        save("https://api.test/v1", "test-model", 0.3, "sk-test")
    }

    // planner 内 settings 是构造传入的；为让"无 Key"用例复用，直接构造带 Key 的 Planner
    private fun plannerWithKey(db: StudyDatabase, fn: ChatJsonFn): RoughReadPlanner =
        runBlocking {
            withKey(db)
            planner(db, fn)
        }

    // ---------- 1. 单块直用 gist（含 gist/terms/输入截断与占位符断言）----------

    @Test
    fun singleBlock_writesGistTermsAndMarks() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onBlock = { _, _ ->
            RoughReadPlan(
                gist = "G".repeat(300),
                keyTerms = (0 until 12).map { KeyTerm("术".repeat(40), "释".repeat(80)) },
                paragraphs = listOf(
                    PlanEntry(0, "explain"), PlanEntry(1, "skip"),
                    PlanEntry(2, "group", group = listOf(2, 3)), PlanEntry(3, "group", group = listOf(2, 3)),
                ),
            )
        }
        fake.onMerge = { _, _ ->
            MergedOverview("G".repeat(300), (0 until 12).map { KeyTerm("术".repeat(40), "释".repeat(80)) })
        }
        val chapterId = seedChapter(db, paras = 4, textLen = 300)
        val outcome = plannerWithKey(db, fake).run(chapterId)

        assertNull(outcome.interruptedAtBlock)
        assertEquals(1, outcome.totalBlocks)
        assertEquals(2, outcome.unitCount) // 1 explain + 1 group 组
        assertEquals("单块章直用块 gist，不应发归并调用", 0, fake.mergeCalls)

        val chapter = db.chapterDao().byIdOnce(chapterId)!!
        assertEquals(200, chapter.gist!!.length) // 截断 200
        val terms = KeyTermsCodec.decode(chapter.keyTermsJson)
        assertEquals(10, terms.size) // 截断 10 条
        assertEquals(30, terms[0].term.length)
        assertEquals(60, terms[0].plain.length)

        val paras = db.paragraphDao().byChapter(chapterId)
        assertEquals("EXPLAIN", paras[0].aiAction)
        assertEquals("SKIP", paras[1].aiAction)
        assertEquals("GROUP", paras[2].aiAction)
        assertEquals(2L, paras[2].groupId)
        assertEquals("GROUP", paras[3].aiAction)
        assertEquals(2L, paras[3].groupId)

        // 输入每段截 200 字；占位符全部替换
        val userMsg = fake.requests[0].messages.last { it.role == "user" }.content
        // 段文本 = "正文N " 前缀 + 300 个"甲"；take(200) 后前缀占 4 字，最长甲串 196
        assertTrue("应含截断后的甲串", userMsg.contains("甲".repeat(150)))
        assertTrue("原文 300 字不应完整出现", !userMsg.contains("甲".repeat(210)))
        // user 消息必须是合法 JSON（锁定 jsonEsc 引号语义，防 "text":正文 回归）
        Json.parseToJsonElement(userMsg)
        val systemMsg = fake.requests[0].messages.first { it.role == "system" }.content
        assertTrue("占位符应替换干净", !systemMsg.contains("{block_pos}") && !systemMsg.contains("{unit_budget}"))
        db.close()
    }

    // ---------- 2. 多块归并一次 ----------

    @Test
    fun multiBlocks_mergeRunsOnce() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onBlock = { call, _ -> fullPlan(listOf(call - 1), gist = "块$call") }
        fake.onMerge = { _, _ -> MergedOverview(gist = "MERGED") }
        val chapterId = seedChapter(db, paras = 2, textLen = 5000) // 2 块
        val outcome = plannerWithKey(db, fake).run(chapterId)

        assertNull(outcome.interruptedAtBlock)
        assertEquals(2, fake.blockCalls)
        assertEquals(1, fake.mergeCalls)
        assertEquals("MERGED", db.chapterDao().byIdOnce(chapterId)!!.gist)
        db.close()
    }

    // ---------- 3. 归并失败 + 旧 gist 非空 + 非 force → 保留旧值 ----------

    @Test
    fun mergeFail_keepOldGistWhenPresentAndNotForce() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onBlock = { call, _ -> fullPlan(listOf(call - 1), gist = "块$call") }
        fake.onMerge = { _, _ -> throw AiException("额度用尽") }
        val chapterId = seedChapter(db, paras = 2, textLen = 5000) // 2 块才会走归并
        db.chapterDao().updateAiGist(chapterId, "旧G", "[]")

        val outcome = plannerWithKey(db, fake).run(chapterId)

        assertTrue(outcome.mergeKeptOld)
        assertTrue(!outcome.mergeDegraded)
        assertEquals("旧G", db.chapterDao().byIdOnce(chapterId)!!.gist)
        db.close()
    }

    // ---------- 4. 归并失败 + 旧 gist 空 → 写降级值（首块 gist）----------

    @Test
    fun mergeFail_writesDegradedWhenNoOldGist() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onBlock = { _, _ -> fullPlan(listOf(0, 1), gist = "块级要点") }
        fake.onMerge = { _, _ -> throw AiException("网络断开") }
        val chapterId = seedChapter(db, paras = 2, textLen = 5000) // 2 块才会走归并

        val outcome = plannerWithKey(db, fake).run(chapterId)

        assertTrue(outcome.mergeDegraded)
        assertTrue(!outcome.mergeKeptOld)
        assertEquals("块级要点", db.chapterDao().byIdOnce(chapterId)!!.gist)
        db.close()
    }

    // ---------- 5. force 重标注归并失败 → 写降级新值（不保留旧 gist）----------

    @Test
    fun forceMergeFail_overwritesOldGistWithDegraded() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onBlock = { _, _ -> fullPlan(listOf(0, 1), gist = "新G") }
        fake.onMerge = { _, _ -> throw AiException("额度用尽") }
        val chapterId = seedChapter(db, paras = 2, textLen = 5000) // 2 块才会走归并
        db.chapterDao().updateAiGist(chapterId, "旧G", "[]")

        val outcome = plannerWithKey(db, fake).run(chapterId, force = true)

        assertTrue(outcome.mergeDegraded)
        assertTrue(!outcome.mergeKeptOld)
        assertEquals("新G", db.chapterDao().byIdOnce(chapterId)!!.gist)
        db.close()
    }

    // ---------- 6. 块 1 成功块 2 连续失败 → 中断 + 块 1 保留 ----------

    @Test
    fun blockFailAfterSuccess_interruptsAndKeepsFirstBlock() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onBlock = { call, _ ->
            if (call == 1) fullPlan(listOf(0)) else throw AiException("请求失败（HTTP 500）")
        }
        val chapterId = seedChapter(db, paras = 2, textLen = 5000)

        val outcome = plannerWithKey(db, fake).run(chapterId)

        assertEquals(2, outcome.interruptedAtBlock)
        assertEquals(1, outcome.doneBlocks)
        assertEquals(0, fake.mergeCalls) // 中断不归并
        val paras = db.paragraphDao().byChapter(chapterId)
        assertEquals("EXPLAIN", paras[0].aiAction)
        assertEquals("NONE", paras[1].aiAction)
        assertNull(db.chapterDao().byIdOnce(chapterId)!!.gist)
        db.close()
    }

    // ---------- 7. 缺标第 1 次失败第 2 次通过 → 块成功 ----------

    @Test
    fun missingMarkRetry_secondAttemptSucceeds() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onBlock = { call, _ ->
            if (call == 1) {
                // 只标 1/2 段 → 缺标 50% 抛 PlannerException
                RoughReadPlan(paragraphs = listOf(PlanEntry(0, "explain")))
            } else {
                fullPlan(listOf(0, 1))
            }
        }
        val chapterId = seedChapter(db, paras = 2)

        val outcome = plannerWithKey(db, fake).run(chapterId)

        assertNull(outcome.interruptedAtBlock)
        assertEquals(2, fake.blockCalls)
        val paras = db.paragraphDao().byChapter(chapterId)
        assertEquals("EXPLAIN", paras[0].aiAction)
        assertEquals("EXPLAIN", paras[1].aiAction)
        db.close()
    }

    // ---------- 8. 重跑跳过已完成块 + 失败块整块重跑 ----------

    @Test
    fun rerun_skipsCompletedBlocksRetriesFailed() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onBlock = { call, _ ->
            if (call == 1) fullPlan(listOf(0)) else throw AiException("超时")
        }
        val chapterId = seedChapter(db, paras = 2, textLen = 5000)
        plannerWithKey(db, fake).run(chapterId)
        // 块 1 成功 1 次 + 块 2 失败重试 3 次（ATTEMPTS=3）
        assertEquals(4, fake.blockCalls)

        // 第二次：块 1 已标 → 跳过；块 2 成功；归并正常出值（本例专测块级行为，归并不留噪音）
        val fake2CallsStart = fake.blockCalls
        fake.onBlock = { _, _ -> fullPlan(listOf(1), gist = "块2") }
        fake.onMerge = { _, _ -> MergedOverview("重读归并G") }
        val outcome = plannerWithKey(db, fake).run(chapterId)

        assertNull(outcome.interruptedAtBlock)
        assertEquals("块 1 应跳过，仅块 2 重跑", fake2CallsStart + 1, fake.blockCalls)
        val paras = db.paragraphDao().byChapter(chapterId)
        assertEquals("EXPLAIN", paras[0].aiAction)
        assertEquals("EXPLAIN", paras[1].aiAction)
        assertEquals("重读归并G", db.chapterDao().byIdOnce(chapterId)!!.gist)
        db.close()
    }

    // ---------- 9. force=true 覆盖全部块 ----------

    @Test
    fun force_rerunsAllBlocksAndOverwrites() = runBlocking {
        val db = db()
        val chapterId = seedChapter(db, paras = 2)
        // 预置全部已标 + 旧 gist
        db.paragraphDao().updateAiAction(
            db.paragraphDao().byChapter(chapterId)[0].id, "SKIP", null, null,
        )
        db.paragraphDao().updateAiAction(
            db.paragraphDao().byChapter(chapterId)[1].id, "SKIP", null, null,
        )
        db.chapterDao().updateAiGist(chapterId, "旧G", "[]")

        val fake = FakeChat()
        fake.onBlock = { _, _ -> fullPlan(listOf(0, 1), gist = "重读G") }

        val outcome = plannerWithKey(db, fake).run(chapterId, force = true)

        assertEquals(1, fake.blockCalls) // 单块章；已标块未被跳过 = force 生效
        assertEquals("单块直用块 gist", "重读G", db.chapterDao().byIdOnce(chapterId)!!.gist)
        assertEquals("EXPLAIN", db.paragraphDao().byChapter(chapterId)[0].aiAction)
        assertNull(outcome.interruptedAtBlock)
        db.close()
    }

    // ---------- 10. 取消中断后已落库块保留 ----------

    @Test
    fun cancellation_keepsCommittedBlocks() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onBlock = { call, _ ->
            if (call == 1) fullPlan(listOf(0)) else throw CancellationException("用户取消")
        }
        val chapterId = seedChapter(db, paras = 2, textLen = 5000)

        val result = runCatching { plannerWithKey(db, fake).run(chapterId) }
        assertTrue("取消应向上传播", result.isFailure)
        assertTrue(result.exceptionOrNull() is CancellationException)

        val paras = db.paragraphDao().byChapter(chapterId)
        assertEquals("块 1 已落库应保留", "EXPLAIN", paras[0].aiAction)
        assertEquals("NONE", paras[1].aiAction)
        assertNull("取消不归并", db.chapterDao().byIdOnce(chapterId)!!.gist)
        db.close()
    }

    // ---------- 11. 无 Key 前置检查 ----------

    @Test
    fun noKey_failsFastWithFriendlyMessage() = runBlocking {
        val db = db()
        val fake = FakeChat()
        val chapterId = seedChapter(db, paras = 1)

        val result = runCatching { planner(db, fake).run(chapterId) }

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message ?: ""
        assertTrue("应提示 Key 问题，实际：$message", message.contains("API Key"))
        assertEquals(0, fake.blockCalls)
        db.close()
    }

    // ---------- 质检 P1/P2 补测：401 直断不重试 ----------

    @Test
    fun unretryableHttp401_failsWithoutRetries() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onBlock = { call, _ ->
            if (call == 1) fullPlan(listOf(0)) else throw AiException("API Key 无效或无权限", httpCode = 401)
        }
        val chapterId = seedChapter(db, paras = 2, textLen = 5000)

        val outcome = plannerWithKey(db, fake).run(chapterId)

        assertEquals(2, outcome.interruptedAtBlock)
        // 块 1 成功 1 次 + 块 2 只打 1 次（401 不重试，ATTEMPTS 不生效）
        assertEquals(2, fake.blockCalls)
        assertEquals("EXPLAIN", db.paragraphDao().byChapter(chapterId)[0].aiAction)
        db.close()
    }

    // ---------- 质检 P2-5 补测：空转保护 ----------

    @Test
    fun idleGuard_fullyMarkedWithGist_makesZeroCalls() = runBlocking {
        val db = db()
        val chapterId = seedChapter(db, paras = 2)
        val paras = db.paragraphDao().byChapter(chapterId)
        db.paragraphDao().updateAiAction(paras[0].id, "EXPLAIN", null, null)
        db.paragraphDao().updateAiAction(paras[1].id, "SKIP", null, null)
        db.chapterDao().updateAiGist(chapterId, "已有要点", "[]")

        val fake = FakeChat()
        val outcome = plannerWithKey(db, fake).run(chapterId)

        assertNull(outcome.interruptedAtBlock)
        assertEquals("全标 + 有 gist + 非 force 应零 API 调用", 0, fake.blockCalls)
        assertEquals(0, fake.mergeCalls)
        assertEquals(1, outcome.doneBlocks)
        db.close()
    }

    // ---------- 12. runMergeOnly：只归并不跑块 ----------

    @Test
    fun mergeOnly_skipsBlocksRunsMerge() = runBlocking {
        val db = db()
        val chapterId = seedChapter(db, paras = 2)
        // 直接落库标注（模拟上次已完成块）
        val paras = db.paragraphDao().byChapter(chapterId)
        db.paragraphDao().updateAiAction(paras[0].id, "EXPLAIN", null, null)
        db.paragraphDao().updateAiAction(paras[1].id, "SKIP", null, null)

        val fake = FakeChat()
        fake.onMerge = { _, _ -> MergedOverview(gist = "只归并") }

        val outcome = plannerWithKey(db, fake).runMergeOnly(chapterId)

        assertEquals(0, fake.blockCalls)
        assertEquals(1, fake.mergeCalls)
        assertNull(outcome.interruptedAtBlock)
        assertEquals("只归并", db.chapterDao().byIdOnce(chapterId)!!.gist)
        db.close()
    }
}
