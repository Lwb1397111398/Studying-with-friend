package com.studyfriend.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.BookProgress
import com.studyfriend.app.data.db.BookmarkEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.QaHistoryEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.db.escapeLikePattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v7 功能补齐轮 DAO 验证：书签 / 随手问历史 / 全书搜索 / 进度与最近在读 / 设置监听（内存库） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataV7Test {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun book(title: String) = BookEntity(
        title = title, author = "", sourceType = "TXT", filePath = "", status = "READY",
        totalChapters = 2, overviewJson = null, createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun chapter(bookId: Long, idx: Int, title: String = "第${idx}章") = ChapterEntity(
        bookId = bookId, idx = idx, title = title, readState = "NOT_READ",
        gist = null, keyTermsJson = null,
    )

    private fun paragraph(chapterId: Long, idx: Int, text: String) = ParagraphEntity(
        chapterId = chapterId, idx = idx, text = text, role = "BODY",
    )

    // ---- 书签 ----

    @Test
    fun bookmarkInsertFlowAndCascade() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book("民法典"))
            val chIds = db.chapterDao().insertAll(listOf(chapter(bookId, 0), chapter(bookId, 1)))
            db.bookmarkDao().insert(
                BookmarkEntity(bookId = bookId, chapterId = chIds[1], paraIdx = 3, preview = "代理权授予", createdAt = 100L),
            )

            val rows = db.bookmarkDao().byBookWithChapterFlow(bookId).first()
            assertEquals(1, rows.size)
            assertEquals("第1章", rows[0].chapterTitle)
            assertEquals(1, rows[0].chapterIdx)
            assertEquals(3, rows[0].bookmark.paraIdx)

            // 删除单条
            db.bookmarkDao().delete(rows[0].bookmark.id)
            assertTrue(db.bookmarkDao().byBookWithChapterFlow(bookId).first().isEmpty())

            // 删章 CASCADE 清书签（重插后再删章）
            db.bookmarkDao().insert(
                BookmarkEntity(bookId = bookId, chapterId = chIds[0], paraIdx = 0, preview = "x", createdAt = 101L),
            )
            db.chapterDao().deleteByBook(bookId)
            assertTrue(db.bookmarkDao().byBookWithChapterFlow(bookId).first().isEmpty())
        } finally {
            db.close()
        }
    }

    // ---- 随手问历史：追加 + 倒序 + 每书 50 条淘汰 ----

    @Test
    fun qaHistoryLatestAndTrim() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book("民法典"))
            repeat(55) { i ->
                db.qaHistoryDao().insert(
                    QaHistoryEntity(
                        bookId = bookId, chapterId = 1L, chapterTitle = "第${i}章",
                        question = "问题$i", answer = "回答$i", createdAt = 1_000L + i,
                    ),
                )
                db.qaHistoryDao().trim(bookId, keep = 50)
            }
            val latest = db.qaHistoryDao().latestByBook(bookId)
            assertEquals(50, latest.size)
            // 倒序：最新在前
            assertEquals("问题54", latest[0].question)
            assertEquals("问题5", latest[49].question)
        } finally {
            db.close()
        }
    }

    // ---- 全书搜索 ----

    @Test
    fun searchInBookHitsAcrossChapters() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book("民法典"))
            val otherId = db.bookDao().insert(book("别书"))
            val chA = db.chapterDao().insertAll(listOf(chapter(bookId, 0, "总则"), chapter(bookId, 1, "合同")))[0]
            val chB = db.chapterDao().insertAll(listOf(chapter(otherId, 0)))[0]
            val ids = db.paragraphDao().insertAll(
                listOf(
                    paragraph(chA, 0, "代理权是指代理人以被代理人名义实施的法律行为"),
                    paragraph(chA, 1, "本段没有关键词"),
                    paragraph(chA, 2, "委托代理与法定代理的分类"),
                    paragraph(chB, 0, "其他书的代理权不该被搜到"),
                ),
            )
            val hits = db.paragraphDao().searchInBook(bookId, escapeLikePattern("代理"), limit = 200)
            assertEquals(2, hits.size)
            assertEquals(ids[0], hits[0].paraId)
            assertEquals("总则", hits[0].chapterTitle)
            assertEquals(0, hits[0].chapterIdx)
            assertEquals(0, hits[0].paraIdx)
            // 第二个命中同在总则章（ORDER BY c.idx, p.idx：先段0后段2）
            assertEquals(ids[2], hits[1].paraId)
            assertEquals("总则", hits[1].chapterTitle)
            assertEquals(2, hits[1].paraIdx)
        } finally {
            db.close()
        }
    }

    @Test
    fun searchLikeWildcardIsLiteral() = runBlocking {
        val db = db()
        try {
            val bookId = db.bookDao().insert(book("符号书"))
            val chId = db.chapterDao().insertAll(listOf(chapter(bookId, 0)))[0]
            db.paragraphDao().insertAll(
                listOf(
                    paragraph(chId, 0, "百分之五十写作50%"),
                    paragraph(chId, 1, "下划线_是通配符"),
                    paragraph(chId, 2, "反斜杠\\测试"),
                ),
            )
            // % 作为字面量搜索：只命中第 0 段，不会把所有行当通配匹配
            assertEquals(1, db.paragraphDao().searchInBook(bookId, escapeLikePattern("50%"), limit = 200).size)
            assertEquals(1, db.paragraphDao().searchInBook(bookId, escapeLikePattern("_"), limit = 200).size)
            assertEquals(1, db.paragraphDao().searchInBook(bookId, escapeLikePattern("\\"), limit = 200).size)
            // limit=0 应查不到任何行
            assertTrue(db.paragraphDao().searchInBook(bookId, escapeLikePattern("写"), limit = 0).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun escapeLikePatternEscapesWildcards() {
        assertEquals("%50\\%%", escapeLikePattern("50%"))
        assertEquals("%a\\_b%", escapeLikePattern("a_b"))
        assertEquals("%x\\\\y%", escapeLikePattern("x\\y"))
        assertEquals("%普通%", escapeLikePattern("普通"))
    }

    // ---- 进度批量投影 + 最近在读标记 + 排序 ----

    @Test
    fun progressAllAndMarkReading() = runBlocking {
        val db = db()
        try {
            val b1 = db.bookDao().insert(book("书一"))
            val b2 = db.bookDao().insert(book("书二"))
            val chIds = db.chapterDao().insertAll(
                listOf(chapter(b2, 0), chapter(b2, 1)),
            )
            db.chapterDao().updateReadState(chIds[0], "DONE")

            val all = db.bookDao().progressAllFlow().first().associateBy { it.id }
            assertEquals(0, all.getValue(b1).readChapters)
            assertEquals(1, all.getValue(b2).readChapters)
            assertEquals(2, all.getValue(b2).totalChapters)

            // 书二后读（markReading 晚）→ 排序在未读的书一前
            db.bookDao().markReading(b2, chIds[1], now = 9_999L)
            val b2row = db.bookDao().get(b2)!!
            assertEquals(chIds[1], b2row.lastReadChapterId)
            assertEquals(9_999L, b2row.lastReadAt)
            val ordered = db.bookDao().getAllFlow().first()
            assertEquals(b2, ordered[0].id)
            assertEquals(b1, ordered[1].id)
        } finally {
            db.close()
        }
    }

    // ---- 设置键监听（主题三态即时生效） ----

    @Test
    fun settingWatchEmitsOnChange() = runBlocking {
        val db = db()
        try {
            val dao = db.settingDao()
            assertTrue(dao.watch("theme_mode").first() == null)
            dao.upsert(com.studyfriend.app.data.db.SettingEntity("theme_mode", "dark"))
            assertEquals("dark", dao.watch("theme_mode").first())
            dao.upsert(com.studyfriend.app.data.db.SettingEntity("theme_mode", "light"))
            assertEquals("light", dao.watch("theme_mode").first())
        } finally {
            db.close()
        }
    }
}
