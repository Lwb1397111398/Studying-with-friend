package com.studyfriend.app.data.importer.ocr

import com.studyfriend.app.data.importer.pdfpipeline.PLine
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** OCR 诊断包单测（自 ImportViewModel 抽出后的行为锁定）：质量备注两级告警/字数偏差、
 *  快照固定种子抽样+滚动保留 2 本、兜底页草稿落盘格式、书名目录键清洗。 */
class OcrDiagnosticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ================= bookKey =================

    @Test
    fun `bookKey keeps cjk and alnum`() {
        assertEquals("民法物权_插图版", OcrDiagnostics.bookKey("民法物权_插图版"))
        assertEquals("abc123", OcrDiagnostics.bookKey("abc123"))
    }

    @Test
    fun `bookKey replaces illegal chars and truncates to 40`() {
        assertEquals("a_b_c", OcrDiagnostics.bookKey("a/b\\c"))
        assertEquals(40, OcrDiagnostics.bookKey("长".repeat(50)).length)
    }

    @Test
    fun `bookKey blank falls back to book`() {
        assertEquals("___", OcrDiagnostics.bookKey("!!!"))
        assertEquals("book", OcrDiagnostics.bookKey(""))
    }

    // ================= qualityNote =================

    private fun outcomeConfs(vararg confs: Float) = confs.toList()

    @Test
    fun `no note when samples below 5`() {
        // 4 页全低置信度：样本不足不告警（避免小书误报）
        assertNull(OcrDiagnostics.qualityNote(outcomeConfs(0.5f, 0.5f, 0.5f, 0.5f), emptyList()))
        // 字数同理：<5 页不告警
        assertNull(OcrDiagnostics.qualityNote(emptyList(), listOf(10, 10, 10, 10)))
    }

    @Test
    fun `fallback zero confs excluded from median`() {
        // 兜底页记 0f：剔除后只剩 5 个 0.95 → 无告警
        val confs = listOf(0f) + List(5) { 0.95f }
        assertNull(OcrDiagnostics.qualityNote(confs, emptyList()))
    }

    @Test
    fun `median below hard threshold warns suggest disabling ocr`() {
        val note = OcrDiagnostics.qualityNote(List(5) { 0.80f }, emptyList())!!
        assertTrue(note.contains("全书识别质量偏低"))
        assertTrue(note.contains("0.80"))
        assertTrue(note.contains("关闭「扫描书本地识别」"))
    }

    @Test
    fun `median just below 0_90 warns softly`() {
        val note = OcrDiagnostics.qualityNote(List(6) { 0.88f }, emptyList())!!
        assertTrue(note.contains("识别质量略低"))
        assertTrue(!note.contains("关闭"))
    }

    @Test
    fun `median at 0_90 silent`() {
        assertNull(OcrDiagnostics.qualityNote(List(5) { 0.90f }, emptyList()))
    }

    @Test
    fun `char count deviation above ratio warns`() {
        // 5 页字数 [100,100,100,100,20]：中位 100，|20-100|/100=80%>60%，偏离页 1/5=20%>10%
        val note = OcrDiagnostics.qualityNote(emptyList(), listOf(100, 100, 100, 100, 20))!!
        assertTrue(note.contains("1 页文字量明显偏离"))
    }

    @Test
    fun `char count within ratio silent`() {
        // 偏差页占比 1/6 ≈16.7%>10% 但单页偏差 50%≤60% → 不告警
        assertNull(OcrDiagnostics.qualityNote(emptyList(), listOf(100, 100, 100, 100, 100, 50)))
    }

    @Test
    fun `zero char pages excluded from char stats`() {
        // 空页（0 字）不参与：剩下 5 页均匀 → 无告警
        assertNull(OcrDiagnostics.qualityNote(emptyList(), listOf(100, 100, 100, 100, 100, 0)))
    }

    @Test
    fun `conf and char notes joined with semicolon`() {
        val note = OcrDiagnostics.qualityNote(List(5) { 0.80f }, listOf(100, 100, 100, 100, 20))!!
        assertTrue(note.contains("；"))
    }

    // ================= writeVerifySnapshot =================

    /** Outcome 六方同长构造（dims/fallback/confs/drafts 按需给） */
    private fun outcome(
        pagesLines: List<List<PLine>>,
        pageLineConfs: List<List<Float>> = pagesLines.map { ls -> List(ls.size) { 0.95f } },
        fallbackPages: List<OcrImportRunner.FallbackPage> = emptyList(),
        pageDrafts: List<List<OcrLine>> = pagesLines.map { emptyList() },
        pageMeanConfs: List<Float> = pagesLines.map { if (it.isEmpty()) 0f else 0.95f },
    ) = OcrImportRunner.Outcome(
        pagesLines = pagesLines,
        dims = pagesLines.map { 100f to 200f },
        fallbackPages = fallbackPages,
        pageMeanConfs = pageMeanConfs,
        pageLineConfs = pageLineConfs,
        pageDrafts = pageDrafts,
    )

    private fun pline(text: String) = PLine(text, 0f, 100f, 10f, 14f)

    @Test
    fun `snapshot written with fixed seed sampling`() {
        val filesDir = tmp.newFolder("fd")
        val lines = List(30) { i -> List(3) { j -> pline("页${i + 1}行$j") } }
        OcrDiagnostics.writeVerifySnapshot(filesDir, "测试书", outcome(lines))
        val f = File(filesDir, "ocr_verify/测试书/snapshot.json")
        assertTrue(f.exists())
        val json = Json.parseToJsonElement(f.readText()).jsonObject
        assertEquals("测试书", json["bookTitle"]!!.jsonPrimitive.content)
        assertEquals(30, json["pageCount"]!!.jsonPrimitive.int)
        // 固定种子（书名 hash）→ 同书重跑抽同一页
        val firstPick = json["pages"]!!.jsonArray[0].jsonObject["page"]!!.jsonPrimitive.int
        OcrDiagnostics.writeVerifySnapshot(filesDir, "测试书", outcome(lines))
        val repick = Json.parseToJsonElement(File(filesDir, "ocr_verify/测试书/snapshot.json").readText())
            .jsonObject["pages"]!!.jsonArray[0].jsonObject["page"]!!.jsonPrimitive.int
        assertEquals(firstPick, repick)
        // 抽样页内容：文本行与置信度对齐
        val page0 = json["pages"]!!.jsonArray[0].jsonObject
        assertEquals(3, page0["lineCount"]!!.jsonPrimitive.int)
        assertEquals(0.95, page0["confs"]!!.jsonArray[0].jsonPrimitive.double, 1e-6)
    }

    @Test
    fun `big book picks two pages`() {
        val filesDir = tmp.newFolder("fd2")
        val lines = List(120) { i -> listOf(pline("第${i + 1}页")) }
        OcrDiagnostics.writeVerifySnapshot(filesDir, "大部头", outcome(lines))
        val json = Json.parseToJsonElement(File(filesDir, "ocr_verify/大部头/snapshot.json").readText()).jsonObject
        assertEquals(2, json["pages"]!!.jsonArray.size)
    }

    @Test
    fun `empty book writes nothing`() {
        val filesDir = tmp.newFolder("fd3")
        OcrDiagnostics.writeVerifySnapshot(filesDir, "空书", outcome(emptyList()))
        val root = File(filesDir, "ocr_verify/空书")
        // 目录建了但无 snapshot（candidates 为空早退）
        assertTrue(!File(root, "snapshot.json").exists())
    }

    @Test
    fun `rolling keeps newest two books`() {
        val filesDir = tmp.newFolder("fd4")
        val root = File(filesDir, "ocr_verify")
        val old = File(root, "旧书一").apply { mkdirs() }
        val mid = File(root, "旧书二").apply { mkdirs() }
        old.setLastModified(1_000L)
        mid.setLastModified(2_000L)
        val lines = List(5) { i -> listOf(pline("第${i + 1}页")) }
        OcrDiagnostics.writeVerifySnapshot(filesDir, "新书", outcome(lines))
        // 写完的 snapshot 让「新书」目录 mtime 最新；断言只剩 2 本且旧书一被滚掉
        assertEquals(setOf("旧书二", "新书"), root.listFiles()!!.map { it.name }.toSet())
    }

    // ================= writeFallbackDrafts =================

    @Test
    fun `fallback drafts written as text_conf lines`() {
        val dir = tmp.newFolder("dbg")
        val drafts = List(3) { i -> listOf(OcrLine("第${i}行 %车", 0f, 0f, 100f, 14f, 0.4f + i)) }
        val oc = outcome(
            pagesLines = List(3) { emptyList() },
            fallbackPages = listOf(OcrImportRunner.FallbackPage(2, "LOW_CONF")),
            pageDrafts = drafts,
        )
        OcrDiagnostics.writeFallbackDrafts(dir, oc)
        val f = File(dir, "d002.txt")
        assertTrue(f.exists())
        // 行文本只作 %s 参数：含 % 的 OCR 文本不炸 UnknownFormatConversion；
        // 兜底页=第 2 页 → 落 pageDrafts[1]（每页 1 行草稿）
        assertEquals("第1行 %车\t1.400", f.readText())
    }

    @Test
    fun `non fallback pages write no draft`() {
        val dir = tmp.newFolder("dbg2")
        val oc = outcome(pagesLines = List(2) { listOf(pline("正文")) })
        OcrDiagnostics.writeFallbackDrafts(dir, oc)
        assertEquals(0, dir.listFiles()!!.size)
    }

    // ================= samplePageNos =================

    @Test
    fun `sample every 20th page starting at 1`() {
        assertEquals(listOf(1, 21, 41), OcrDiagnostics.samplePageNos(50))
        assertEquals(listOf(1), OcrDiagnostics.samplePageNos(20))
        assertEquals(emptyList<Int>(), OcrDiagnostics.samplePageNos(0))
    }
}
