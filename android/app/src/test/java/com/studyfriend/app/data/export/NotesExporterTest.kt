package com.studyfriend.app.data.export

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParaMemoEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 读书笔记导出（实景体验轮第六波）：拼接结构 / 空书不生成 / 文件名清洗（内存库） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotesExporterTest {

    private fun db(): StudyDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        StudyDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun book(title: String) = BookEntity(
        title = title, author = "测试作者", sourceType = "TXT", filePath = "", status = "READY",
        totalChapters = 1, overviewJson = null, createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun chapter(bookId: Long, idx: Int, title: String) = ChapterEntity(
        bookId = bookId, idx = idx, title = title, readState = "NOT_READ", gist = null, keyTermsJson = null,
    )

    private fun para(chapterId: Long, idx: Int, text: String) = ParagraphEntity(
        chapterId = chapterId, idx = idx, text = text, role = "BODY",
    )

    @Test
    fun buildsMarkdownWithNotesAndMemos() {
        runBlocking {
        val d = db()
        val b = d.bookDao().insert(book("测试之书"))
        val c1 = d.chapterDao().insertAll(listOf(chapter(b, 0, "第一章 总则")))[0]
        d.paragraphDao().insertAll(listOf(para(c1, 0, "民事主体的合法权益受法律保护。")))
        d.paraNoteDao().insert(
            ParaNoteEntity(
                chapterId = c1, paraIds = "[0]", title = "〔第1段〕基本原则",
                friendly = "这条讲的是民法的基石：保护民事主体的权益。",
                analogy = "像小区门禁守着业主权益", keyPointsJson = "[]",
                memoryHook = null, questionsJson = null,
                model = "m", promptVersion = "v1", createdAt = 1_000L,
            ),
        )
        d.paraMemoDao().insert(
            ParaMemoEntity(
                bookId = b, chapterId = c1, paraId = 0L, paraIdx = 0,
                quote = "民事主体的合法权益", text = "划重点：权益保护", createdAt = 2_000L,
            ),
        )

        val r = NotesExporter.build(d, d.bookDao().get(b)!!, File(System.getProperty("java.io.tmpdir")!!))
        assertTrue(r.hasContent)
        val md = r.file!!.readText()
        assertTrue(md.contains("# 《测试之书》读书笔记"))
        assertTrue(md.contains("## 1. 第一章 总则"))
        assertTrue(md.contains("### 讲解"))
        assertTrue(md.contains("〔第1段〕基本原则"))
        assertTrue(md.contains("原文：民事主体的合法权益受法律保护"))
        assertTrue(md.contains("这条讲的是民法的基石"))
        assertTrue(md.contains("*类比：像小区门禁守着业主权益*"))
        assertTrue(md.contains("### 我的批注"))
        assertTrue(md.contains("> 民事主体的合法权益"))
        assertTrue(md.contains("划重点：权益保护"))
        // 统计文案在 Result.summary（Toast 用），不在文件正文里
        assertTrue(r.summary.contains("讲解 1 条 · 批注 1 条"))
        // 前置章（无笔记无批注）不进导出
        assertFalse(md.contains("开篇"))
        r.file!!.delete()
        }
    }

    @Test
    fun emptyBook_noFile() {
        runBlocking {
        val d = db()
        val b = d.bookDao().insert(book("空书"))
        val r = NotesExporter.build(d, d.bookDao().get(b)!!, File(System.getProperty("java.io.tmpdir")!!))
        assertFalse(r.hasContent)
        assertNull(r.file)
        assertTrue(r.summary.contains("还没有讲解或批注"))
        }
    }

    @Test
    fun sanitize_stripsIllegalChars() {
        assertEquals("a_b_c", NotesExporter.sanitize("a/b\\c"))
        assertEquals("民法_总论", NotesExporter.sanitize("民法:总论"))
        assertEquals("书", NotesExporter.sanitize(""))
    }
}
