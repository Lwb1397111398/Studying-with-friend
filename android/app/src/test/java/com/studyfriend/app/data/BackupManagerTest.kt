package com.studyfriend.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.BookmarkEntity
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.FigureEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.ParaMemoEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.QuizAttemptEntity
import com.studyfriend.app.data.db.ReviewItemEntity
import com.studyfriend.app.data.db.SettingEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.db.VisionQueueEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 数据安全轮：备份导出内容完整性 / 整库替换 roundtrip / 坏文件不动库 / Key 不进不出（内存库） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun book(title: String) = BookEntity(
        title = title, author = "作者", sourceType = "TXT", filePath = "", status = "READY",
        totalChapters = 2, overviewJson = null, createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun chapter(bookId: Long, idx: Int, title: String = "第${idx}章") = ChapterEntity(
        bookId = bookId, idx = idx, title = title, readState = "NOT_READ",
        gist = "要点", keyTermsJson = "[{\"term\":\"债\",\"plain\":\"欠\"}]",
    )

    private fun paragraph(chapterId: Long, idx: Int, text: String) = ParagraphEntity(
        chapterId = chapterId, idx = idx, text = text, role = "BODY",
        aiAction = "EXPLAIN", why = "重要概念", pageNo = idx + 1,
    )

    /** 造一套覆盖全部备份表的完整数据，返回 db；各表行数均 >0 */
    private suspend fun seedFull(db: StudyDatabase): Pair<Long, Long> {
        val bookId = db.bookDao().insert(book("民法典"))
        val chIds = db.chapterDao().insertAll(listOf(chapter(bookId, 0), chapter(bookId, 1)))
        val paras = db.paragraphDao().insertAll(
            listOf(paragraph(chIds[0], 0, "代理权授予他人"), paragraph(chIds[0], 1, "委托合同")),
        )
        db.paraNoteDao().insert(
            ParaNoteEntity(
                chapterId = chIds[0], paraIds = "[${paras[0]}]", title = "代理",
                friendly = "通俗解释", analogy = "类比", keyPointsJson = "[]",
                memoryHook = "钩子", questionsJson = "[]", model = "gpt-test",
                promptVersion = "v1", createdAt = 10L,
            ),
        )
        db.chapterAssetDao().upsert(
            ChapterAssetEntity(
                chapterId = chIds[0], summaryMd = "# 总结", mindmapTree = "树",
                mindmapJson = "{}", memoryMd = "记忆", chainMd = "串联",
                quizJson = "[]", model = "gpt-test", promptVersion = "v1", createdAt = 11L,
            ),
        )
        db.quizAttemptDao().insert(
            QuizAttemptEntity(chapterId = chIds[0], qIndex = 0, answer = "答", verdict = "WRONG", feedback = "再想", createdAt = 12L),
        )
        db.reviewItemDao().insertAll(
            listOf(ReviewItemEntity(bookId = bookId, chapterId = chIds[0], title = "第0章", intervalIdx = 0, dueAt = 99L)),
        )
        db.bookmarkDao().insert(
            BookmarkEntity(bookId = bookId, chapterId = chIds[0], paraIdx = 0, preview = "代理权", createdAt = 13L),
        )
        db.qaHistoryDao().insert(
            com.studyfriend.app.data.db.QaHistoryEntity(
                bookId = bookId, chapterId = chIds[0], chapterTitle = "第0章",
                question = "什么是代理", answer = "答", createdAt = 14L,
            ),
        )
        db.paraMemoDao().insert(
            ParaMemoEntity(bookId = bookId, chapterId = chIds[0], paraId = paras[0], paraIdx = 0, quote = "代理权", text = "记一笔", createdAt = 15L),
        )
        db.settingDao().upsert(SettingEntity("api_base", "https://example.com/v1"))
        db.settingDao().upsert(SettingEntity(SettingsRepository.KEY_ENC, "k1:加密密文不该导出"))
        db.settingDao().upsert(SettingEntity(SettingsRepository.KEY_GITHUB_TOKEN_ENC, "k1:也不该导出"))
        db.settingDao().upsert(SettingEntity(SettingsRepository.KEY_THEME, "dark"))
        return bookId to chIds[0]
    }

    private fun exportBytes(m: BackupManager): ByteArray =
        ByteArrayOutputStream().use { out ->
            runBlocking { m.exportTo(out) }
            out.toByteArray()
        }

    @Test
    fun exportContainsAllTablesAndExcludesEncKeys() = runBlocking {
        val db = db()
        try {
            seedFull(db)
            val m = BackupManager(db)
            val bytes = exportBytes(m)
            val json = bytes.decodeToString()

            // 统计：各表行数如实
            val summary = m.peek(ByteArrayInputStream(bytes))
            assertEquals(1, summary.books)
            assertEquals(2, summary.chapters)
            assertEquals(2, summary.paragraphs)
            assertEquals(1, summary.paraNotes)
            assertEquals(1, summary.chapterAssets)
            assertEquals(1, summary.quizAttempts)
            assertEquals(1, summary.reviewItems)
            assertEquals(1, summary.bookmarks)
            assertEquals(1, summary.qaHistory)
            assertEquals(1, summary.paraMemos)

            // 加密键绝不进备份；普通设置键在场
            assertFalse("api_key_enc 不得出现在备份里", json.contains("api_key_enc"))
            assertFalse("github_token_enc 不得出现在备份里", json.contains("github_token_enc"))
            assertFalse("加密密文不得泄漏", json.contains("加密密文不该导出"))
            assertTrue(json.contains("api_base"))
            assertTrue(json.contains("theme_mode"))

            // 格式头
            assertTrue(json.contains("\"format\":\"studyfriend-backup\""))
            assertTrue(json.contains("\"version\":1"))
        } finally {
            db.close()
        }
    }

    @Test
    fun restoreRoundtripKeepsIdsAndContent() = runBlocking {
        val db = db()
        try {
            seedFull(db)
            val m = BackupManager(db)
            val bytes = exportBytes(m)

            // 导出后继续写脏数据（新书/新设置），恢复应回到导出时刻
            db.bookDao().insert(book("导出后的脏书"))
            db.settingDao().upsert(SettingEntity("theme_mode", "light"))

            val summary = m.restoreFrom(ByteArrayInputStream(bytes))
            assertEquals(1, summary.books)

            // 逐表快照对比：id 与全部列 roundtrip 一致
            val b = db.backupDao().allBooks()
            assertEquals(1, b.size)
            assertEquals("民法典", b[0].title)
            assertEquals(1L, b[0].id)
            assertEquals(2, db.backupDao().allChapters().size)
            val paras = db.backupDao().allParagraphs()
            assertEquals(2, paras.size)
            assertEquals("代理权授予他人", paras[0].text)
            assertEquals("EXPLAIN", paras[0].aiAction)
            assertEquals(1, paras[0].pageNo)
            assertEquals(1, db.backupDao().allParaNotes().size)
            assertEquals(1, db.backupDao().allChapterAssets().size)
            assertEquals(1, db.backupDao().allQuizAttempts().size)
            assertEquals(1, db.backupDao().allReviewItems().size)
            assertEquals(1, db.backupDao().allBookmarks().size)
            assertEquals(1, db.backupDao().allQaHistory().size)
            assertEquals(1, db.backupDao().allParaMemos().size)
            assertEquals("dark", db.settingDao().get("theme_mode")?.value)
        } finally {
            db.close()
        }
    }

    @Test
    fun restoreReplacesEntireLibraryAndClearsFiguresAndQueue() = runBlocking {
        val db = db()
        try {
            val (bookId, chId) = seedFull(db)
            val m = BackupManager(db)
            val bytes = exportBytes(m)

            // 恢复前库被整成另一套数据（旧书按主键删除 CASCADE 全部子行 + 新书 B 与 figures/vision_queue 残留）
            db.bookDao().delete(db.bookDao().get(bookId)!!)
            val otherId = db.bookDao().insert(book("另一本"))
            val otherCh = db.chapterDao().insertAll(listOf(chapter(otherId, 0)))[0]
            db.figureDao().insertAll(
                listOf(
                    FigureEntity(
                        bookId = otherId, chapterId = otherCh, pageNo = 1, ordAfterPara = 0,
                        bboxY0 = 10f, seqNo = 1, file = "figures/$otherId/p1_f1.png",
                        width = 100, height = 50, format = "png", md5 = "x", createdAt = 1L,
                    ),
                ),
            )
            db.visionQueueDao().insertAll(
                listOf(
                    VisionQueueEntity(
                        bookId = otherId, uri = "content://x", pageNo = 1,
                        originChars = 10, status = "PENDING", updatedAt = 1L,
                    ),
                ),
            )

            m.restoreFrom(ByteArrayInputStream(bytes))

            // 整库替换：另一本与 figures/vision_queue 全部消失，恢复出导出时刻的内容
            assertNull(db.bookDao().get(otherId))
            assertNotNull(db.bookDao().get(bookId))
            assertTrue(db.figureDao().pageNosByBook(otherId).isEmpty())
            assertTrue(db.visionQueueDao().byBook(otherId).isEmpty())
            assertEquals(1, db.backupDao().allBooks().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun restoreKeepsLocalEncKeysAndReplacesPlainKeys() = runBlocking {
        val db = db()
        try {
            seedFull(db)
            val m = BackupManager(db)
            val bytes = exportBytes(m)

            // 模拟换机：旧书按主键删除（CASCADE 清全部子行）后，本机已填 Key（enc 键）+ 一个备份里没有的本地设置
            db.bookDao().delete(db.backupDao().allBooks()[0])
            db.settingDao().upsert(SettingEntity(SettingsRepository.KEY_ENC, "k1:本机新Key"))
            db.settingDao().upsert(SettingEntity("only_local_key", "应被整库替换语义清掉"))

            m.restoreFrom(ByteArrayInputStream(bytes))

            // enc 键保持本机值不动；备份里的普通键生效；本地独有非 enc 键被替换删除
            assertEquals("k1:本机新Key", db.settingDao().get(SettingsRepository.KEY_ENC)?.value)
            assertEquals("https://example.com/v1", db.settingDao().get("api_base")?.value)
            assertEquals("dark", db.settingDao().get("theme_mode")?.value)
            assertNull(db.settingDao().get("only_local_key"))
        } finally {
            db.close()
        }
    }

    @Test
    fun badFilesLeaveLibraryUntouched() = runBlocking {
        val badFiles = listOf(
            "不是JSON".toByteArray(),
            "{\"format\":\"other-app\",\"version\":1}".toByteArray(),
            "{\"format\":\"studyfriend-backup\",\"version\":99}".toByteArray(),
            // 引用完整性：章引用了不存在的书
            ("{\"format\":\"studyfriend-backup\",\"version\":1,\"exportedAt\":1," +
                "\"chapters\":[{\"id\":1,\"bookId\":5,\"idx\":0,\"title\":\"坏章\",\"readState\":\"NOT_READ\"}]}").toByteArray(),
            // 主键重复
            ("{\"format\":\"studyfriend-backup\",\"version\":1,\"exportedAt\":1," +
                "\"books\":[{\"id\":1,\"title\":\"a\",\"author\":\"\",\"sourceType\":\"TXT\",\"filePath\":\"\",\"status\":\"READY\",\"totalChapters\":0,\"createdAt\":0,\"updatedAt\":0}," +
                "{\"id\":1,\"title\":\"b\",\"author\":\"\",\"sourceType\":\"TXT\",\"filePath\":\"\",\"status\":\"READY\",\"totalChapters\":0,\"createdAt\":0,\"updatedAt\":0}]}").toByteArray(),
        )
        badFiles.forEachIndexed { i, bytes ->
            val db = db()
            try {
                val (bookId, _) = seedFull(db)
                val m = BackupManager(db)
                try {
                    m.restoreFrom(ByteArrayInputStream(bytes))
                    fail("第 $i 个坏文件应抛 BackupException")
                } catch (e: BackupException) {
                    // 预期：人话错误 + 库未被清一半
                    assertTrue(e.message!!.isNotBlank())
                }
                assertEquals("库未被改动 #$i", 1, db.backupDao().allBooks().size.toLong())
                assertEquals(bookId, db.backupDao().allBooks()[0].id)
                assertEquals(2, db.backupDao().allParagraphs().size)
            } finally {
                db.close()
            }
        }
    }
}
