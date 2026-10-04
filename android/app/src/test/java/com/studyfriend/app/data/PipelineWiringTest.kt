package com.studyfriend.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.importer.pdfpipeline.ExtractedFigure
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * J4/J6 管线接线验证（P4 commit C）：figures 落库/归属/文件挪移（importBook 同事务）、
 * replaceBookContent 重挂保命（§3-D 四步）、removeBook 磁盘清理（r5-P0-2）、
 * 第二道闸数据源（FigureDao.pageNosByBook）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: StudyDatabase
    private lateinit var filesRoot: File
    private lateinit var repo: BookRepository
    private lateinit var stagingDir: File

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyDatabase::class.java,
        ).allowMainThreadQueries().build()
        filesRoot = tmp.newFolder("files")
        repo = BookRepository(db, filesRoot)
        stagingDir = tmp.newFolder("staging")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun book(title: String = "民法物权") = BookEntity(
        title = title, author = "王泽鉴", sourceType = "PDF", filePath = "uri://pdf",
        status = "IMPORTED", totalChapters = 0, overviewJson = null,
        createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun chapter(title: String = "第一章") = ChapterEntity(
        bookId = 0, idx = 0, title = title, readState = "NOT_READ", gist = null, keyTermsJson = null,
    )

    private fun para(idx: Int, pageNo: Int?) = ParagraphEntity(
        chapterId = 0, idx = 0, text = "第$idx 段正文内容", role = "body", pageNo = pageNo,
    )

    /** staging 假图：文件写真实字节，md5 为该字节摘要（J2 口径：figures.md5==最终文件字节 md5） */
    private fun stagedFigure(pageNo: Int, seqNo: Int, md5Seed: String = "seed-$seqNo"): ExtractedFigure {
        val bytes = md5Seed.toByteArray()
        val finalName = "p${pageNo}_f$seqNo.png"
        val file = File(stagingDir, finalName).apply { writeBytes(bytes) }
        return ExtractedFigure(
            pageNo = pageNo, ordAfterPara = 0, bboxY0 = 100f + seqNo, seqNo = seqNo,
            widthPx = 100, heightPx = 80, format = "png", stagingFile = file,
            finalName = finalName, md5 = md5Hex(bytes),
        )
    }

    private fun md5Hex(b: ByteArray) =
        MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    /** 章起点（figures 归属口径）：每章首段 pageNo */
    private suspend fun firstPageNo(chapterId: Long): Int? =
        db.paragraphDao().byChapter(chapterId).firstOrNull()?.pageNo

    /** 两章：A 首段 p3、B 首段 p10；figures 覆盖「早于首章 p1 / 恰首章起点 p3 / 恰末章起点 p10 / 晚于末章 p20」 */
    private fun chaptersWithFigures(): List<Pair<ChapterEntity, List<ParagraphEntity>>> = listOf(
        chapter("第一章 总则") to listOf(para(0, 3), para(1, 4)),
        chapter("第二章 物权变动") to listOf(para(0, 10), para(1, 11)),
    )

    private fun fourFigures() = listOf(
        stagedFigure(1, 1), stagedFigure(3, 2), stagedFigure(10, 3), stagedFigure(20, 4),
    )

    // ---- importBook：同事务落库 + 挪移 + 归属（J6①边界 + r9-P2-1 事务边界） ----

    @Test
    fun importBook_figuresLandAndAssignAcrossBoundaries() = runBlocking {
        val bookId = repo.importBook(book(), chaptersWithFigures(), figures = fourFigures())
        val rows = db.figureDao().byBookOnce(bookId)
        assertEquals(4, rows.size)
        val chapters = db.chapterDao().byBook(bookId).sortedBy { it.idx }
        val chA = chapters[0].id
        val chB = chapters[1].id
        // 归属四边界：p1 早于首章→首章；p3 恰首章 startPage→首章；p10 恰末章→末章；p20 晚于末章→末章 clamp
        assertEquals(chA, rows.first { it.seqNo == 1 }.chapterId)
        assertEquals(chA, rows.first { it.seqNo == 2 }.chapterId)
        assertEquals(chB, rows.first { it.seqNo == 3 }.chapterId)
        assertEquals(chB, rows.first { it.seqNo == 4 }.chapterId)
        rows.forEach { f ->
            // file 相对路径 + 最终文件存在 + md5 对账 + staging 已挪走
            assertEquals("figures/$bookId/p${f.pageNo}_f${f.seqNo}.png", f.file)
            val final = File(filesRoot, f.file)
            assertTrue("最终文件存在 ${f.file}", final.exists())
            assertEquals(f.md5, md5Hex(final.readBytes()))
            assertFalse("staging 已挪走 ${f.file}", File(stagingDir, f.file.substringAfterLast('/')).exists())
        }
    }

    @Test
    fun importBook_duplicateKeysDeduped() = runBlocking {
        // 同 (md5, pageNo, ordAfterPara) 全同键（r6-P2-6）：同内容不同 seqNo 命名也不落重复行
        val dup = ExtractedFigure(
            pageNo = 5, ordAfterPara = 0, bboxY0 = 100f, seqNo = 2,
            widthPx = 100, heightPx = 80, format = "png",
            stagingFile = File(stagingDir, "p5_f2.png").apply { writeBytes("same".toByteArray()) },
            finalName = "p5_f2.png", md5 = md5Hex("same".toByteArray()),
        )
        val figs = listOf(stagedFigure(5, 1, md5Seed = "same"), dup)
        val bookId = repo.importBook(book(), chaptersWithFigures(), figures = figs)
        assertEquals(1, db.figureDao().byBookOnce(bookId).size)
    }

    @Test
    fun importBook_calibrationPathUsesGivenStartPages() = runBlocking {
        // 校准路径：chapterStartPages 显式传入（与章节列表同序），覆盖「首段无页码」场景
        val chapters = listOf(
            chapter("第一章") to listOf(para(0, null), para(1, null)),
            chapter("第二章") to listOf(para(0, null)),
        )
        val bookId = repo.importBook(
            book(), chapters,
            figures = listOf(stagedFigure(50, 1), stagedFigure(99, 2)),
            chapterStartPages = listOf(40, 90),
        )
        val chapterRows = db.chapterDao().byBook(bookId).sortedBy { it.idx }
        val rows = db.figureDao().byBookOnce(bookId)
        assertEquals(chapterRows[0].id, rows.first { it.seqNo == 1 }.chapterId)
        assertEquals(chapterRows[1].id, rows.first { it.seqNo == 2 }.chapterId)
    }

    @Test
    fun importBook_textOnlyBookFiguresEmpty() = runBlocking {
        val bookId = repo.importBook(book(), chaptersWithFigures())
        assertTrue(db.figureDao().byBookOnce(bookId).isEmpty()) // J5：无图书零影响
    }

    // ---- replaceBookContent：figures 重挂保命（J6②，§3-D 四步） ----

    @Test
    fun replaceBookContent_figuresSurviveReattachAndStayColumnEqual() = runBlocking {
        val bookId = repo.importBook(book(), chaptersWithFigures(), figures = fourFigures())
        val before = db.figureDao().byBookOnce(bookId).sortedBy { it.seqNo }
        val beforeChapters = db.chapterDao().byBook(bookId).sortedBy { it.idx }
        val beforeStartPages = beforeChapters.associate { it.title to firstPageNo(it.id) }

        // 重挂：同 startPage 结构的新章（VisionRebuilder 语义——不重排不改起点），段落数可变
        val newChapters = listOf(
            chapter("第一章 总则") to listOf(para(0, 3), para(1, 4), para(2, 5)),
            chapter("第二章 物权变动") to listOf(para(0, 10)),
        )
        repo.replaceBookContent(bookId, book(), newChapters)

        val after = db.figureDao().byBookOnce(bookId).sortedBy { it.seqNo }
        val afterChapters = db.chapterDao().byBook(bookId).sortedBy { it.idx }
        assertEquals("figures 行数不变", before.size, after.size)
        // 章是全新行（新 id），身份对齐用 title；每章起点（首段 pageNo）逐章相等（r10-③）
        val titleToNew = afterChapters.associateBy { it.title }
        assertEquals("章数不变", beforeChapters.size, afterChapters.size)
        beforeStartPages.forEach { (title, oldStart) ->
            assertEquals("startPage 逐章相等 $title", oldStart, firstPageNo(titleToNew.getValue(title).id))
        }
        // figures.chapterId 归属映射一致：旧 chapterId 经 title 映射到新章 id
        val oldIdToTitle = beforeChapters.associate { it.id to it.title }
        after.zip(before).forEach { (n, o) ->
            val newId = titleToNew.getValue(oldIdToTitle.getValue(o.chapterId)).id
            assertEquals("归属映射一致 seq=${o.seqNo}", newId, n.chapterId)
        }
        // 逐列等值（r9-P1-2）：仅 id/chapterId 允许变化
        after.zip(before).forEach { (n, o) ->
            assertEquals(o.pageNo, n.pageNo)
            assertEquals(o.ordAfterPara, n.ordAfterPara)
            assertEquals(o.bboxY0, n.bboxY0, 0f)
            assertEquals(o.seqNo, n.seqNo)
            assertEquals(o.file, n.file)
            assertEquals(o.width, n.width)
            assertEquals(o.height, n.height)
            assertEquals(o.format, n.format)
            assertEquals(o.md5, n.md5)
            assertNotEquals("重插后是新行", o.id, n.id)
        }
    }

    @Test
    fun replaceBookContent_noFigures_staysZeroRows() = runBlocking {
        val bookId = repo.importBook(book(), chaptersWithFigures())
        repo.replaceBookContent(bookId, book(), chaptersWithFigures())
        assertTrue(db.figureDao().byBookOnce(bookId).isEmpty())
    }

    // ---- J3 走查修复：目录校准空段子节行混入 chapters，图必须挂有段父章 ----

    /** 父章A(p3/p4) + 空段子节 + 父章B(p10/p11)：子节 startPage 兜底 0 干扰区间归属 */
    private fun chaptersWithChildSection(): List<Pair<ChapterEntity, List<ParagraphEntity>>> = listOf(
        chapter("第一章 总则") to listOf(para(0, 3), para(1, 4)),
        chapter("第一节 子节行") to emptyList(),
        chapter("第二章 物权变动") to listOf(para(0, 10), para(1, 11)),
    )

    /** p5 落在子节(0)与 B(10) 之间——含子节候选时会挂子节（挂错层），是关键判别图 */
    private fun fiveFigures() = listOf(
        stagedFigure(1, 1), stagedFigure(3, 2), stagedFigure(5, 3),
        stagedFigure(10, 4), stagedFigure(20, 5),
    )

    @Test
    fun importBook_figuresAnchorToParagraphBearingChaptersNotChildSections() = runBlocking {
        val bookId = repo.importBook(book(), chaptersWithChildSection(), figures = fiveFigures())
        val rows = db.figureDao().byBookOnce(bookId).sortedBy { it.seqNo }
        assertEquals(5, rows.size)
        val chapters = db.chapterDao().byBook(bookId).sortedBy { it.idx }
        val childId = chapters.first { it.title == "第一节 子节行" }.id
        val chA = chapters.first { it.title == "第一章 总则" }.id
        val chB = chapters.first { it.title == "第二章 物权变动" }.id
        // 无一挂子节；p1 早于首章→A、p3 恰 A 起点→A、p5 区间内→A、p10 恰 B 起点→B、p20 晚于末章 clamp→B
        rows.forEach { f -> assertNotEquals("图不挂空段子节 seq=${f.seqNo}", childId, f.chapterId) }
        assertEquals(chA, rows.first { it.seqNo == 1 }.chapterId)
        assertEquals(chA, rows.first { it.seqNo == 2 }.chapterId)
        assertEquals(chA, rows.first { it.seqNo == 3 }.chapterId)
        assertEquals(chB, rows.first { it.seqNo == 4 }.chapterId)
        assertEquals(chB, rows.first { it.seqNo == 5 }.chapterId)
    }

    @Test
    fun replaceBookContent_figuresAnchorSkipsChildSectionsToo() = runBlocking {
        val bookId = repo.importBook(book(), chaptersWithFigures(), figures = fourFigures())
        // 重挂引入空段子节（目录校准后章表形态）——重挂归属同样只看有段章
        repo.replaceBookContent(bookId, book(), chaptersWithChildSection())
        val rows = db.figureDao().byBookOnce(bookId).sortedBy { it.seqNo }
        assertEquals(4, rows.size)
        val chapters = db.chapterDao().byBook(bookId).sortedBy { it.idx }
        val childId = chapters.first { it.title == "第一节 子节行" }.id
        val chA = chapters.first { it.title == "第一章 总则" }.id
        val chB = chapters.first { it.title == "第二章 物权变动" }.id
        rows.forEach { f -> assertNotEquals("重挂不落子节 seq=${f.seqNo}", childId, f.chapterId) }
        assertEquals(chA, rows.first { it.seqNo == 1 }.chapterId)
        assertEquals(chA, rows.first { it.seqNo == 2 }.chapterId)
        assertEquals(chB, rows.first { it.seqNo == 3 }.chapterId)
        assertEquals(chB, rows.first { it.seqNo == 4 }.chapterId)
    }

    // ---- removeBook：DB 行 + 磁盘目录（J4） ----

    @Test
    fun removeBook_deletesRowsAndFigureDirectory() = runBlocking {
        val bookId = repo.importBook(book(), chaptersWithFigures(), figures = fourFigures())
        val dir = File(filesRoot, "figures/$bookId")
        assertTrue(dir.exists() && dir.listFiles()!!.size == 4)
        val entity = db.bookDao().get(bookId)!!
        repo.removeBook(entity)
        assertTrue("figures 行随 CASCADE 清空", db.figureDao().byBookOnce(bookId).isEmpty())
        assertFalse("磁盘目录已清理", dir.exists())
    }

    // ---- 第二道闸数据源（VisionWorker 消费兜底，J6③） ----

    @Test
    fun pageNosByBook_distinctFigurePages() = runBlocking {
        val bookId = repo.importBook(
            book(), chaptersWithFigures(),
            figures = listOf(stagedFigure(5, 1), stagedFigure(5, 2), stagedFigure(9, 3)),
        )
        assertEquals(setOf(5, 9), db.figureDao().pageNosByBook(bookId).toSet())
        assertTrue(db.figureDao().pageNosByBook(bookId + 1).isEmpty())
    }

    // ---- r12-QC4-P2-4：insertFigures 文件系统故障注入（单图挪移失败剔除，J2 口径）----

    @Test
    fun importBook_missingStagingFile_figureDroppedNoOrphanRow() = runBlocking {
        // 场景①：staging 文件从未写出——renameTo false + copyTo 抛 NoSuchFileException→剔除
        val ghost = ExtractedFigure(
            pageNo = 5, ordAfterPara = 0, bboxY0 = 100f, seqNo = 1,
            widthPx = 100, heightPx = 80, format = "png",
            stagingFile = File(stagingDir, "p5_f1.png"), // 未写出
            finalName = "p5_f1.png", md5 = md5Hex("ghost".toByteArray()),
        )
        val bookId = repo.importBook(
            book(), chaptersWithFigures(),
            figures = listOf(ghost, stagedFigure(6, 2)),
        )
        val rows = db.figureDao().byBookOnce(bookId)
        assertEquals("坏图剔除、好图照常落库", 1, rows.size)
        assertEquals(6, rows[0].pageNo)
        assertFalse("无孤儿文件", File(filesRoot, "figures/$bookId/p5_f1.png").exists())
    }

    @Test
    fun importBook_renameFails_copyFallbackLandsSameBytes() = runBlocking {
        // 场景②：target 已被同名文件占用。Windows 上 renameTo 不覆盖已存在文件→false，
        // 走 copyTo(overwrite) 回退；Linux renameTo 直接覆盖成功——两平台最终状态一致，
        // 断言只锁「落库+字节对账+staging 清空」外部契约（J2），不绑定内部走哪条分支
        val prevId = repo.importBook(book(), chaptersWithFigures())
        val bookId = prevId + 1 // Room 自增连续（单线程 inMemory），预置下一本的冲突
        File(filesRoot, "figures/$bookId").mkdirs()
        File(filesRoot, "figures/$bookId/p7_f1.png").writeBytes("stale".toByteArray())
        val landedId = repo.importBook(
            book(title = "第二本"), chaptersWithFigures(),
            figures = listOf(stagedFigure(7, 1)),
        )
        assertEquals("bookId 预测自洽（预置打在正确的书上）", bookId, landedId)
        val rows = db.figureDao().byBookOnce(landedId)
        assertEquals(1, rows.size)
        val final = File(filesRoot, "figures/$landedId/p7_f1.png")
        assertTrue(final.exists())
        assertEquals("回退落盘字节与 staging 对账", md5Hex("seed-1".toByteArray()), md5Hex(final.readBytes()))
        assertFalse("staging 已清", File(stagingDir, "p7_f1.png").exists())
    }

    @Test
    fun importBook_moveAndCopyBothFail_figureDroppedNoOrphanRow() = runBlocking {
        // 场景③：target 位置被同名**非空目录**占用——renameTo(file→dir) 两平台皆败
        // （Windows MoveFile / Linux EISDIR）；copyTo 底层 Files.copy(REPLACE_EXISTING)
        // 对**空目录**目标会静默替换成功（JVM 探针实证），仅非空目录抛
        // DirectoryNotEmptyException→两处皆败→剔除，行无孤儿值
        val prevId = repo.importBook(book(), chaptersWithFigures())
        val bookId = prevId + 1
        val occupied = File(filesRoot, "figures/$bookId/p9_f1.png")
        occupied.mkdirs()
        File(occupied, ".occupied").writeBytes(byteArrayOf(1)) // 非空化：Files.copy 才会抛
        val evil = ExtractedFigure(
            pageNo = 9, ordAfterPara = 0, bboxY0 = 100f, seqNo = 1,
            widthPx = 100, heightPx = 80, format = "png",
            stagingFile = File(stagingDir, "p9_f1.png").apply { writeBytes("evil".toByteArray()) },
            finalName = "p9_f1.png", md5 = md5Hex("evil".toByteArray()),
        )
        val landedId = repo.importBook(
            book(title = "第三本"), chaptersWithFigures(),
            figures = listOf(evil, stagedFigure(10, 2)),
        )
        assertEquals(bookId, landedId)
        val rows = db.figureDao().byBookOnce(landedId)
        assertEquals("双失败图剔除、好图照常", 1, rows.size)
        assertEquals(10, rows[0].pageNo)
        assertFalse("无孤儿文件", File(filesRoot, "figures/$landedId/p9_f1.png").isFile)
    }

    @Test
    fun importBook_withoutFilesDir_throwsOnFigures() = runBlocking {
        val bare = BookRepository(db)
        var threw = false
        try {
            bare.importBook(book(), chaptersWithFigures(), figures = fourFigures())
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue("filesDir 未注入时带图导入应显式失败", threw)
    }
}
