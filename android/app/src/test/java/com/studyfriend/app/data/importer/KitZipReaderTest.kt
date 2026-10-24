package com.studyfriend.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KitZipReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun buildZip(
        kitJson: String?,
        bookTxt: String? = "〔KIT-书 开始〕\n{\"kit\":1}\n〔KIT-书 结束〕\n正文。",
        figs: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun entry(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
            if (kitJson != null) entry("kit.json", kitJson.toByteArray())
            if (bookTxt != null) entry("book.txt", bookTxt.toByteArray())
            figs.forEach { (name, bytes) -> entry(name, bytes) }
        }
        return bos.toByteArray()
    }

    private val goodKitJson = """
        {"kit": 1, "v": "bookocr-kit 1.2", "title": "测试书", "pages": 9,
         "chapters": [
           {"t": "第一章 总则", "p": 4, "lv": 1, "r": "BODY", "a": "第一章 总"},
           {"t": "第二章 分则", "p": 8, "lv": 1, "r": "BODY", "a": "第二章 分"}
         ],
         "figures": [
           {"page": 5, "rect": [0.2, 0.3, 0.8, 0.6], "kind": "photo", "title": "图1-1 物权变动",
            "desc": "物权变动流程示意", "file": "figures/f001_p005.png", "w": 640, "h": 480},
           {"page": 6, "rect": [0.1, 0.5, 0.9, 0.8], "kind": "table", "title": "表2-1 登记对抗",
            "desc": "登记对抗主义对照表", "file": "figures/f002_p006.png", "w": 800, "h": 300}
         ]}
    """.trimIndent()

    @Test
    fun read_validPack() {
        val bytes = buildZip(
            goodKitJson,
            figs = mapOf(
                "figures/f001_p005.png" to ByteArray(16) { 1 },
                "figures/f002_p006.png" to ByteArray(16) { 2 },
            ),
        )
        val staging = tmp.newFolder("staging")
        val data = KitZipReader.read(bytes, staging)
        assertNotNull(data)
        data!!
        assertEquals("测试书", data.meta.title)
        assertEquals(2, data.meta.chapters.size)
        assertEquals("第一章 总则", data.meta.chapters[0].title)
        assertTrue(data.text.contains("正文"))
        assertEquals(2, data.figures.size)
        val f1 = data.figures[0]
        assertEquals(5, f1.pageNo)
        assertEquals("photo", f1.kind)
        assertEquals(640, f1.widthPx)
        assertNotNull(f1.stagingFile)
        assertTrue(f1.stagingFile!!.exists())
        assertEquals(32, f1.md5.length) // md5 hex
        assertEquals(listOf(0.2f, 0.3f, 0.8f, 0.6f), f1.rect)
    }

    @Test
    fun read_missingKitJsonOrBook_returnsNull() {
        assertNull(KitZipReader.read(buildZip(null), tmp.newFolder()))
        // 有 kit.json 没 book.txt 也拒
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("kit.json")); z.write(goodKitJson.toByteArray()); z.closeEntry()
        }
        assertNull(KitZipReader.read(bos.toByteArray(), tmp.newFolder()))
    }

    @Test
    fun read_badProtocol_returnsNull() {
        // kit:0 或章表空 → 协议不认
        assertNull(KitZipReader.read(buildZip("""{"kit": 0, "chapters": []}"""), tmp.newFolder()))
        assertNull(KitZipReader.read(buildZip("""{"kit": 1, "chapters": []}"""), tmp.newFolder()))
        assertNull(KitZipReader.read(buildZip("""not json"""), tmp.newFolder()))
    }

    @Test
    fun read_figureFileMissing_dropsThatFigureOnly() {
        // 清单 2 条但图文件只有 1 个：缺图的剔除，其余照常（半包容错）
        val bytes = buildZip(
            goodKitJson,
            figs = mapOf("figures/f001_p005.png" to ByteArray(8) { 7 }),
        )
        val data = KitZipReader.read(bytes, tmp.newFolder())
        assertNotNull(data)
        data!!
        assertEquals(1, data.figures.size)
        assertEquals(5, data.figures[0].pageNo)
    }

    @Test
    fun read_pathTraversalEntries_notWrittenOutside() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun entry(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
            entry("kit.json", goodKitJson.toByteArray())
            entry("book.txt", "正文".toByteArray())
            entry("figures/f001_p005.png", ByteArray(4))
            entry("../evil.txt", "pwn".toByteArray())
        }
        val staging = tmp.newFolder()
        val data = KitZipReader.read(bos.toByteArray(), staging)
        assertNotNull(data)
        assertEquals(1, data!!.figures.size)
        // 穿越 entry 不得落盘到 staging 目录之外
        assertTrue(File(staging.parentFile, "evil.txt").exists().not())
    }
}
