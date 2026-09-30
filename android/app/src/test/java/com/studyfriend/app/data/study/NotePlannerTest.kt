package com.studyfriend.app.data.study

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiException
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.FakeSecretStore
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
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

/** M4b §5-4：讲解批量流水线（10 例）。真 Room 内存库 + 假 ChatJsonFn（按反序列化目标分流） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotePlannerTest {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    private class FakeChat : ChatJsonFn {
        var onNote: (callIndex: Int, req: ChatRequest) -> NotePlan = { _, _ ->
            throw AiException("意外的讲解调用")
        }
        var noteCalls = 0
        val requests = mutableListOf<ChatRequest>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> invoke(
            req: ChatRequest,
            deserializer: DeserializationStrategy<T>,
            onDelta: (String) -> Unit,
        ): T {
            requests += req
            return when (deserializer) {
                NotePlan.serializer() -> onNote(++noteCalls, req) as T
                else -> throw IllegalStateException("未预期的反序列化目标：$deserializer")
            }
        }
    }

    private fun notePlan() = NotePlan(
        title = "讲", friendly = "大白话讲解", analogy = "比方",
        keyPoints = listOf("要点"), memoryHook = "钩子",
        checkQuestions = listOf(CheckQuestion("问", "答")),
    )

    private suspend fun settings(db: StudyDatabase): SettingsRepository =
        SettingsRepository(db, FakeSecretStore()).apply {
            save("https://api.test/v1", "test-model", 0.3, "sk-test")
        }

    private fun planner(db: StudyDatabase, fn: ChatJsonFn) = NotePlanner(
        db = db,
        context = ApplicationProvider.getApplicationContext(),
        settings = runBlocking { settings(db) },
        chatJsonFn = fn,
    )

    /** 建章（先补父书过 FK）+ 按描述插段落；返回 chapterId */
    private suspend fun seed(
        db: StudyDatabase,
        paras: List<Triple<Int, String, Long?>>, // (idx, aiAction, groupId)
        why: String? = null,
    ): Long {
        if (db.bookDao().get(1L) == null) db.bookDao().insert(
            BookEntity(
                title = "测试书", author = "佚名", sourceType = "TXT", filePath = "/tmp/t.txt",
                status = "READY", totalChapters = 0, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        val chapterId = db.chapterDao().insertAll(
            listOf(ChapterEntity(bookId = 1L, idx = 1, title = "第1章", readState = "NOT_READ", gist = null, keyTermsJson = null)),
        )[0]
        db.paragraphDao().insertAll(
            paras.map { (idx, action, gid) ->
                ParagraphEntity(
                    chapterId = chapterId, idx = idx, text = "正文$idx" + "甲".repeat(60),
                    role = "BODY", aiAction = action, groupId = gid, why = why,
                )
            },
        )
        return chapterId
    }

    private suspend fun noteRow(
        chapterId: Long,
        paraIds: String,
        version: String = "explain-note-v0",
        friendly: String = "旧讲解",
    ) = ParaNoteEntity(
        chapterId = chapterId, paraIds = paraIds, title = "旧题", friendly = friendly,
        analogy = null, keyPointsJson = "[]", memoryHook = null, questionsJson = null,
        model = "old-model", promptVersion = version, createdAt = 1L,
    )

    // ---------- 1. 批量生成 + 落库字段映射 ----------

    @Test
    fun detailBrief_appendedToSystemPrompt(): Unit = runBlocking {
        val database = db()
        settings(database).saveDetailLevel("简略")
        val fake = FakeChat()
        fake.onNote = { _, _ -> notePlan() }
        val ch = seed(database, listOf(Triple(0, "EXPLAIN", null)))
        planner(database, fake).run(ch)
        assertTrue("简略指令应进 system 消息", fake.requests[0].messages[0].content.contains("简略"))
        database.close()
    }

    @Test
    fun batch_generatesAndPersists() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onNote = { _, _ -> notePlan() }
        val ch = seed(db, listOf(Triple(0, "EXPLAIN", null), Triple(1, "GROUP", 1L), Triple(2, "GROUP", 1L)))

        val outcome = planner(db, fake).run(ch)

        assertEquals(2, outcome.totalUnits)
        assertEquals(2, outcome.generated)
        assertNull(outcome.interruptedAtUnit)
        assertEquals(2, fake.noteCalls)
        // user 消息必须是合法 JSON（锁定 jsonEsc 引号语义，防 "gist":正文 回归）
        Json.parseToJsonElement(fake.requests[0].messages.last { it.role == "user" }.content)
        val ids = db.paragraphDao().byChapter(ch).map { it.id }
        val notes = db.paraNoteDao().byChapterOnce(ch)
        assertEquals(2, notes.size)
        val single = notes.first { it.paraIds == "[${ids[0]}]" }
        assertEquals("讲", single.title)
        assertEquals("大白话讲解", single.friendly)
        assertEquals("比方", single.analogy)
        assertEquals("钩子", single.memoryHook)
        assertEquals(listOf("要点"), decodeStringList(single.keyPointsJson))
        assertEquals(listOf(CheckQuestion("问", "答")), decodeCheckQuestions(single.questionsJson))
        assertEquals(NotePlanner.NOTE_VERSION, single.promptVersion)
        assertEquals("test-model", single.model)
        assertTrue(notes.any { it.paraIds == "[${ids[1]},${ids[2]}]" })
        db.close()
    }

    // ---------- 2. 缓存命中跳过 ----------

    @Test
    fun batch_cacheHitSkipsAll() = runBlocking {
        val db = db()
        val fake1 = FakeChat().apply { onNote = { _, _ -> notePlan() } }
        val ch = seed(db, listOf(Triple(0, "EXPLAIN", null), Triple(1, "EXPLAIN", null)))
        planner(db, fake1).run(ch)

        val fake2 = FakeChat()
        val outcome = planner(db, fake2).run(ch)

        assertEquals(0, fake2.noteCalls)
        assertEquals(2, outcome.doneUnits)
        assertEquals(0, outcome.generated)
        db.close()
    }

    // ---------- 3. promptVersion 过期 → 重生成 ----------

    @Test
    fun batch_expiredVersionRegenerates() = runBlocking {
        val db = db()
        val ch = seed(db, listOf(Triple(0, "EXPLAIN", null)))
        val pid = db.paragraphDao().byChapter(ch)[0].id
        db.paraNoteDao().insert(noteRow(ch, "[$pid]"))
        val fake = FakeChat().apply { onNote = { _, _ -> notePlan() } }

        val outcome = planner(db, fake).run(ch)

        assertEquals(1, fake.noteCalls)
        val notes = db.paraNoteDao().byChapterOnce(ch)
        assertEquals(1, notes.size)
        assertEquals(NotePlanner.NOTE_VERSION, notes[0].promptVersion)
        assertEquals("大白话讲解", notes[0].friendly)
        db.close()
    }

    // ---------- 4. 同单元重复生成不堆积 ----------

    @Test
    fun batch_regenerateDoesNotAccumulate() = runBlocking {
        val db = db()
        val ch = seed(db, listOf(Triple(0, "EXPLAIN", null)))
        val pid = db.paragraphDao().byChapter(ch)[0].id
        db.paraNoteDao().insert(noteRow(ch, "[$pid]"))
        db.paraNoteDao().insert(noteRow(ch, "[$pid]", version = "explain-note-v0b"))
        val fake = FakeChat().apply { onNote = { _, _ -> notePlan() } }

        planner(db, fake).run(ch)

        assertEquals(1, db.paraNoteDao().byChapterOnce(ch).size)
        db.close()
    }

    // ---------- 5. 中断保留已完成 + 重跑断点续跑 ----------

    @Test
    fun batch_interruptedKeepsDoneAndResumes() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onNote = { call, _ -> if (call == 1) notePlan() else throw AiException("超时") }
        val ch = seed(db, listOf(Triple(0, "EXPLAIN", null), Triple(1, "EXPLAIN", null)))

        val outcome = planner(db, fake).run(ch)

        assertEquals(2, outcome.interruptedAtUnit)
        assertEquals(1, outcome.doneUnits)
        assertEquals("单元 1 成功 1 次 + 单元 2 重试 3 次", 4, fake.noteCalls)
        assertEquals(1, db.paraNoteDao().byChapterOnce(ch).size)

        val fake2 = FakeChat().apply { onNote = { _, _ -> notePlan() } }
        val outcome2 = planner(db, fake2).run(ch)

        assertNull(outcome2.interruptedAtUnit)
        assertEquals("单元 1 缓存命中，仅单元 2 重跑", 1, fake2.noteCalls)
        assertEquals(2, db.paraNoteDao().byChapterOnce(ch).size)
        db.close()
    }

    // ---------- 6. 401 直断不重试 ----------

    @Test
    fun batch_401AbortsImmediately() = runBlocking {
        val db = db()
        val fake = FakeChat()
        fake.onNote = { _, _ -> throw AiException("无权限", 401) }
        val ch = seed(db, listOf(Triple(0, "EXPLAIN", null), Triple(1, "EXPLAIN", null)))

        val outcome = planner(db, fake).run(ch)

        assertEquals(1, outcome.interruptedAtUnit)
        assertEquals("401 不重试", 1, fake.noteCalls)
        db.close()
    }

    // ---------- 7. 无 Key 前置 ----------

    @Test
    fun batch_missingKeyThrows() = runBlocking {
        val db = db()
        val ch = seed(db, listOf(Triple(0, "EXPLAIN", null)))
        val fake = FakeChat()

        val e = runCatching {
            NotePlanner(db, ApplicationProvider.getApplicationContext(), SettingsRepository(db, FakeSecretStore()), fake).run(ch)
        }.exceptionOrNull()

        assertTrue(e is MissingKeyException)
        assertEquals(0, fake.noteCalls)
        db.close()
    }

    // ---------- 8. force 全部重生成 ----------

    @Test
    fun batch_forceRegeneratesAll() = runBlocking {
        val db = db()
        val fake1 = FakeChat().apply { onNote = { _, _ -> notePlan() } }
        val ch = seed(db, listOf(Triple(0, "EXPLAIN", null), Triple(1, "EXPLAIN", null)))
        planner(db, fake1).run(ch)

        val fake2 = FakeChat().apply { onNote = { _, _ -> notePlan() } }
        val outcome = planner(db, fake2).run(ch, force = true)

        assertEquals(2, fake2.noteCalls)
        assertEquals(2, outcome.generated)
        assertEquals("先删后插，不堆积", 2, db.paraNoteDao().byChapterOnce(ch).size)
        db.close()
    }

    // ---------- 9. 空单元直接成功 + 孤儿清理在早退前 ----------

    @Test
    fun batch_emptyUnitsSucceedsAndCleansOrphans() = runBlocking {
        val db = db()
        val ch = seed(db, listOf(Triple(0, "SKIP", null), Triple(1, "NONE", null)))
        db.paraNoteDao().insert(noteRow(ch, "[999]"))
        val fake = FakeChat()

        val outcome = planner(db, fake).run(ch)

        assertEquals(0, outcome.totalUnits)
        assertEquals(0, outcome.generated)
        assertEquals(0, fake.noteCalls)
        assertEquals("孤儿行被清理", 0, db.paraNoteDao().byChapterOnce(ch).size)
        db.close()
    }

    // ---------- 10. 孤儿清理不影响有效缓存 ----------

    @Test
    fun batch_orphanCleanupKeepsValid() = runBlocking {
        val db = db()
        val fake1 = FakeChat().apply { onNote = { _, _ -> notePlan() } }
        val ch = seed(db, listOf(Triple(0, "EXPLAIN", null)))
        planner(db, fake1).run(ch)
        db.paraNoteDao().insert(noteRow(ch, "[888]"))
        val fake2 = FakeChat()

        planner(db, fake2).run(ch)

        assertEquals(0, fake2.noteCalls)
        val notes = db.paraNoteDao().byChapterOnce(ch)
        assertEquals(1, notes.size)
        assertTrue(notes[0].promptVersion == NotePlanner.NOTE_VERSION)
        db.close()
    }
}
