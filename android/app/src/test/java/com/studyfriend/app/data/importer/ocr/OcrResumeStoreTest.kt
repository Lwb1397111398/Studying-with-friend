package com.studyfriend.app.data.importer.ocr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pass1 断点续跑存储单测（2026-10-11 黑屏断连修复）：编解码 roundtrip / 原子落盘 /
 *  损坏容错 / 懒清理 / 目录键版本隔离 */
class OcrResumeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun line(i: Int) = OcrLine("第${i}行文字", i * 1.5f, i * 2f, i * 1.5f + 90f, i * 2f + 14f, 0.93f)

    // ================= encode/decode roundtrip =================

    @Test
    fun `roundtrip preserves lines and separatorY`() {
        val lines = List(3) { line(it) }
        val pair = OcrResumeStore.decode(OcrResumeStore.encode(lines, 0.42f))
        assertNotNull(pair)
        val (back, sepY) = pair!!
        assertEquals(3, back.size)
        assertEquals(lines, back)
        assertEquals(0.42f, sepY, 1e-6f)
    }

    @Test
    fun `nan separatorY survives roundtrip as nan`() {
        val pair = OcrResumeStore.decode(OcrResumeStore.encode(emptyList(), Float.NaN))
        assertNotNull(pair)
        val (lines, sepY) = pair!!
        assertTrue(sepY.isNaN())
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `decode rejects malformed json`() {
        assertNull(OcrResumeStore.decode("not json"))
        assertNull(OcrResumeStore.decode("""{"lines":[{"t":"缺坐标"}]}"""))
    }

    // ================= save/load/completedPages =================

    @Test
    fun `save then load returns same page data`() {
        val dir = tmp.newFolder("resume")
        val lines = List(4) { line(it) }
        assertTrue(OcrResumeStore.save(dir, 7, lines, Float.NaN))
        val cached = OcrResumeStore.load(dir, 7)
        assertNotNull(cached)
        assertEquals(lines, cached!!.first)
        assertTrue(cached.second.isNaN())
        assertEquals(setOf(7), OcrResumeStore.completedPages(dir))
    }

    @Test
    fun `load missing page returns null`() {
        assertNull(OcrResumeStore.load(tmp.newFolder("empty"), 1))
    }

    @Test
    fun `corrupt page file is deleted and reads null`() {
        val dir = tmp.newFolder("corrupt")
        File(dir, "page_0002.json").writeText("{broken")
        assertNull(OcrResumeStore.load(dir, 2))
        assertFalse(File(dir, "page_0002.json").exists()) // 损坏文件不残留，防每次重导都读坏
    }

    @Test
    fun `incomplete tmp file does not appear as completed`() {
        // 进程写一半被杀：只剩 .tmp（或 rename 半途），主文件不存在 → 该页视为未完成
        val dir = tmp.newFolder("halfwritten")
        File(dir, "page_0003.json.tmp").writeText(OcrResumeStore.encode(List(2) { line(it) }, 0.5f))
        assertEquals(emptySet<Int>(), OcrResumeStore.completedPages(dir))
        assertNull(OcrResumeStore.load(dir, 3))
    }

    // ================= pruneOther / clear =================

    @Test
    fun `pruneOther keeps only current dir`() {
        val filesDir = tmp.newFolder("fd")
        val keep = OcrResumeStore.dirFor(filesDir, "content://book_a")
        val other = OcrResumeStore.dirFor(filesDir, "content://book_b")
        keep.mkdirs()
        other.mkdirs()
        OcrResumeStore.pruneOther(filesDir, keep)
        assertTrue(keep.isDirectory)
        assertFalse(other.exists())
    }

    @Test
    fun `dirFor isolates different uris`() {
        val filesDir = tmp.newFolder("fd2")
        assertFalse(
            OcrResumeStore.dirFor(filesDir, "content://book_a").absolutePath ==
                OcrResumeStore.dirFor(filesDir, "content://book_b").absolutePath,
        )
    }

    @Test
    fun `clear removes whole dir`() {
        val dir = tmp.newFolder("bye")
        OcrResumeStore.save(dir, 1, listOf(line(0)), 0f)
        OcrResumeStore.clear(dir)
        assertFalse(dir.exists())
    }
}
