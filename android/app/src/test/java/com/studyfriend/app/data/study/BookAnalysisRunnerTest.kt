package com.studyfriend.app.data.study

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.FakeSecretStore
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 一键分析全书（阅读体验轮 4 例 + 总结总览并入 2026-11-28 共 5 例）：真 Room 内存库 +
 * 双假 chat（ChatJsonFn 块调用 / ChatTextFn 按 source 分流讲解·总结·总览），锁定——
 * 正文章逐章「粗读→讲解→总结」、收尾生成全书总览、非正文零调用、无 Key 结构化失败、
 * 已分析章续跑零调用、全部完成后重跑四层缓存全命中零调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookAnalysisRunnerTest {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    /** 粗读假 chat：块调用返回全 explain 计划；归并/其他目标视为意外 */
    private class FakeJson : ChatJsonFn {
        var blockCalls = 0
        var mergeCalls = 0

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> invoke(
            req: ChatRequest,
            deserializer: DeserializationStrategy<T>,
            onDelta: (String) -> Unit,
        ): T = when (deserializer) {
            RoughReadPlan.serializer() -> {
                blockCalls++
                fullPlan() as T
            }
            else -> throw IllegalStateException("未预期的反序列化目标：$deserializer")
        }

        private fun fullPlan() = RoughReadPlan(
            gist = "章梗概",
            keyTerms = listOf(KeyTerm("要点", "通俗解释")),
            paragraphs = listOf(
                PlanEntry(id = 0, action = "explain"),
                PlanEntry(id = 1, action = "explain"),
            ),
        )
    }

    /** 讲解/总结/总览假 chat：按 ChatRequest.source 分流返回对应标签文本。计数用原子——单元 2 路并发会真同时调用（NotePlannerTest 同坑） */
    private class FakeText : ChatTextFn {
        private val notes = java.util.concurrent.atomic.AtomicInteger(0)
        private val summaries = java.util.concurrent.atomic.AtomicInteger(0)
        private val overviews = java.util.concurrent.atomic.AtomicInteger(0)
        val noteCalls: Int get() = notes.get()
        val summaryCalls: Int get() = summaries.get()
        val overviewCalls: Int get() = overviews.get()

        override suspend fun invoke(req: ChatRequest, onDelta: (String) -> Unit): String = when (req.source) {
            "段落讲解" -> {
                notes.incrementAndGet()
                buildString {
                    append("<标题>讲解</标题>\n")
                    append("<讲解>大白话讲解〔p0〕</讲解>\n")
                    append("<要点>\n- 要点一\n</要点>\n")
                }
            }
            "章末总结" -> {
                summaries.incrementAndGet()
                buildString {
                    append("<总结>\n本章讲了两个要点。\n</总结>\n")
                    append("<导图>\n本章\n\t要点一\n\t要点二</导图>\n")
                    append("<记忆思路>\n先记要点一，再记要点二。\n</记忆思路>\n")
                    append("<串联>\n两个要点从概念推出规则。\n</串联>\n")
                    append("<自测>\n").append(
                        QuizCodec.encode(listOf(QuizQuestion("RECALL", "本章讲什么？", "两个要点", "按导图记"))),
                    ).append("\n</自测>")
                }
            }
            "全书总览" -> {
                overviews.incrementAndGet()
                buildString {
                    append("<总览>\n全书围绕物权展开。\n</总览>\n")
                    append("<导图>\n测试书\n\t第一章 总论\n\t第二章 物权</导图>\n")
                    append("<主线>\n概念到规则的推进线。\n</主线>")
                }
            }
            else -> throw IllegalStateException("未预期的 source：${req.source}")
        }
    }

    private suspend fun settingsWithKey(db: StudyDatabase): SettingsRepository =
        SettingsRepository(db, FakeSecretStore()).apply {
            seedMainProfile("https://api.test/v1", "test-model", 0.3, "sk-test")
        }

    /** 建书 + 四章（目录/第一章/第二章/参考文献），第一章每章 2 段短文（单块、无归并） */
    private suspend fun seedBook(db: StudyDatabase): Long {
        val bookId = db.bookDao().insert(
            BookEntity(
                title = "测试书", author = "佚名", sourceType = "TXT", filePath = "/tmp/t.txt",
                status = "READY", totalChapters = 0, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        val titles = listOf("目录", "第一章 总论", "第二章 物权", "参考文献")
        val ids = db.chapterDao().insertAll(
            titles.mapIndexed { i, t ->
                ChapterEntity(bookId = bookId, idx = i, title = t, readState = "NOT_READ", gist = null, keyTermsJson = null)
            },
        )
        // 给两章正文插段落；目录/参考文献章保持无段落（命中范围规则，永不进入分析）
        for (chapterId in listOf(ids[1], ids[2])) {
            db.paragraphDao().insertAll(
                (0..1).map { p -> ParagraphEntity(chapterId = chapterId, idx = p, text = "正文$p " + "甲".repeat(30), role = "BODY") },
            )
        }
        return bookId
    }

    private fun runner(db: StudyDatabase, json: FakeJson, text: FakeText, settings: SettingsRepository) =
        BookAnalysisRunner(
            db = db,
            context = ApplicationProvider.getApplicationContext(),
            settings = settings,
            chatJsonFn = json,
            chatTextFn = text,
            scope = CoroutineScope(Dispatchers.Default),
        )

    // ---------- 1. 主路径：正文章逐章粗读+讲解，非正文零调用 ----------

    @Test
    fun runBook_analyzesBodyChaptersSkipsNonBody() = runBlocking {
        val db = db()
        val json = FakeJson()
        val text = FakeText()
        val r = runner(db, json, text, settingsWithKey(db))
        val bookId = seedBook(db)
        r.start(bookId)
        val done = withTimeout(30_000) {
            r.state.first { it is BookAnalysisState.Succeeded }
        } as BookAnalysisState.Succeeded
        assertTrue(done.message.contains("跳过 2 章"))
        // 两章各 1 块（单块直用无归并）、各 2 个讲解单元
        assertEquals(2, json.blockCalls)
        assertEquals(0, json.mergeCalls)
        assertEquals(4, text.noteCalls)
        // 总结并入（2026-11-28）：两章各 1 次总结调用 + 收尾全书总览 1 次
        assertEquals(2, text.summaryCalls)
        assertEquals(1, text.overviewCalls)
        assertTrue(done.message.contains("全书总览已生成"))
        // 粗读落库：第一章 gist + 段落标注
        val chapters = db.chapterDao().byBook(bookId)
        assertEquals("章梗概", chapters.first { it.title == "第一章 总论" }.gist)
        val marks = db.paragraphDao().byChapter(chapters.first { it.title == "第二章 物权" }.id)
        assertTrue(marks.all { it.aiAction == "EXPLAIN" })
        // 总结落库：两章都有当前版本总结资产
        for (t in listOf("第一章 总论", "第二章 物权")) {
            val a = db.chapterAssetDao().byChapter(chapters.first { it.title == t }.id)
            assertNotNull(a)
            assertTrue(SummaryPlanner.isCurrentSummary(a!!.promptVersion) && a.summaryMd.isNotBlank())
        }
        // 收尾总览落库（当前版本 payload）
        val payload = OverviewCodec.decode(db.bookDao().get(bookId)?.overviewJson)
        assertNotNull(payload)
        assertEquals(OverviewCodec.VERSION, payload!!.promptVersion)
        // 非正文章无讲解
        assertEquals(0, db.paraNoteDao().byChapterOnce(chapters.first { it.title == "目录" }.id).size)
    }

    // ---------- 2. 全部被判跳过：成功且零 API 调用 ----------

    @Test
    fun runBook_allSkipped_succeedsWithoutCalls() = runBlocking {
        val db = db()
        val json = FakeJson()
        val text = FakeText()
        val r = runner(db, json, text, settingsWithKey(db))
        val bookId = db.bookDao().insert(
            BookEntity(
                title = "散页书", author = "佚名", sourceType = "TXT", filePath = "/tmp/t.txt",
                status = "READY", totalChapters = 0, overviewJson = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        db.chapterDao().insertAll(
            listOf(
                ChapterEntity(bookId = bookId, idx = 0, title = "目录", readState = "NOT_READ", gist = null, keyTermsJson = null),
                ChapterEntity(bookId = bookId, idx = 1, title = "参考文献", readState = "NOT_READ", gist = null, keyTermsJson = null),
            ),
        )
        r.start(bookId)
        val done = withTimeout(30_000) {
            r.state.first { it is BookAnalysisState.Succeeded }
        } as BookAnalysisState.Succeeded
        assertTrue(done.message.contains("没有识别出正文章节"))
        assertEquals(0, json.blockCalls)
        assertEquals(0, text.noteCalls)
    }

    // ---------- 3. 无 Key：结构化失败（keyIssue=true 引导去设置） ----------

    @Test
    fun runBook_missingKey_failsWithKeyIssue() = runBlocking {
        val db = db()
        val json = FakeJson()
        val text = FakeText()
        // 未 seed 档案 → resolveLlm 返回 null → MissingKeyException
        val r = runner(db, json, text, SettingsRepository(db, FakeSecretStore()))
        val bookId = seedBook(db)
        r.start(bookId)
        val failed = withTimeout(30_000) {
            r.state.first { it is BookAnalysisState.Failed }
        } as BookAnalysisState.Failed
        assertTrue(failed.keyIssue)
        assertEquals(0, json.blockCalls)
    }

    // ---------- 4. 续跑：已分析章零调用，只补未分析章 ----------

    @Test
    fun runBook_resumeSkipsAnalyzedChapters() = runBlocking {
        val db = db()
        val json = FakeJson()
        val text = FakeText()
        val settings = settingsWithKey(db)
        val bookId = seedBook(db)
        // 先用 Planner 直接分析完第一章（等价于上次一键分析跑完一章）
        val chapters = db.chapterDao().byBook(bookId)
        val ch1 = chapters.first { it.title == "第一章 总论" }
        RoughReadPlanner(db, ApplicationProvider.getApplicationContext(), settings, json).run(ch1.id)
        NotePlanner(db, ApplicationProvider.getApplicationContext(), settings, text).run(ch1.id)
        val jsonBefore = json.blockCalls
        val textBefore = text.noteCalls
        // 一键分析重跑：第一章走空转保护与讲解缓存，只补第二章
        val r = runner(db, json, text, settings)
        r.start(bookId)
        withTimeout(30_000) { r.state.first { it is BookAnalysisState.Succeeded } }
        assertEquals(jsonBefore + 1, json.blockCalls)
        assertEquals(textBefore + 2, text.noteCalls)
        // 第一章粗读/讲解已缓存但无总结 → 补 1 次；第二章全链各 1 次 = 2；总览收尾 1 次
        assertEquals(2, text.summaryCalls)
        assertEquals(1, text.overviewCalls)
        assertNotNull(chapters.first { it.title == "第二章 物权" })
    }

    // ---------- 5. 全部完成后再点一次：粗读空转/讲解缓存/总结资产/总览版本四层全命中，零新调用 ----------

    @Test
    fun runBook_repeatAfterComplete_zeroCalls(): Unit = runBlocking {
        val db = db()
        val json = FakeJson()
        val text = FakeText()
        val r = runner(db, json, text, settingsWithKey(db))
        val bookId = seedBook(db)
        r.start(bookId)
        withTimeout(30_000) { r.state.first { it is BookAnalysisState.Succeeded } }
        val jsonBefore = json.blockCalls
        val textBefore = text.noteCalls + text.summaryCalls + text.overviewCalls
        // 重跑一键分析：先等状态离开上一跑的 Succeeded（第二跑的排队/运行态），再等新 Succeeded
        r.start(bookId)
        withTimeout(30_000) { r.state.first { it !is BookAnalysisState.Succeeded } }
        val done = withTimeout(30_000) {
            r.state.first { it is BookAnalysisState.Succeeded }
        } as BookAnalysisState.Succeeded
        assertEquals(jsonBefore, json.blockCalls)
        assertEquals(textBefore, text.noteCalls + text.summaryCalls + text.overviewCalls)
        assertTrue(done.message.contains("全书总览已是最新"))
    }
}
