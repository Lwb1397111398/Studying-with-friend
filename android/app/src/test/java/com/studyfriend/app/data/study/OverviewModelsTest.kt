package com.studyfriend.app.data.study

import com.studyfriend.app.data.ai.AiBudgets
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M6 计划 §6：三标签解析 3 例 + 输入组装 2 例 */
class OverviewModelsTest {

    private fun legalRaw(): String = buildString {
        append("<总览>\n第一章立基础，第二章在其上演进。\n</总览>\n")
        append("<导图>\n民法入门\n\t第一章 民法概说\n\t\t三档划分\n\t第二章 民事主体</导图>\n")
        append("<主线>\n把各章串成一条线：概念→主体→行为。\n</主线>")
    }

    // ---------- OverviewParser ----------

    @Test
    fun parse_validBundle() {
        val b = OverviewParser.parse(legalRaw())!!
        assertTrue(b.overviewMd.contains("演进"))
        assertTrue(b.treeText.startsWith("民法入门"))
        assertTrue(b.mainlineMd.contains("一条线"))
    }

    @Test
    fun parse_missingTagReturnsNull() {
        val raw = legalRaw().replace("</主线>", "").substringBeforeLast("<主线>")
        assertNull(OverviewParser.parse(raw))
    }

    @Test
    fun parse_emptyTreeReturnsNull() {
        val raw = legalRaw().replace("民法入门\n\t第一章 民法概说\n\t\t三档划分\n\t第二章 民事主体", "   ")
        assertNull(OverviewParser.parse(raw))
    }

    // ---------- OverviewCodec ----------

    @Test
    fun codec_roundTripAndToleratesGarbage() {
        val payload = OverviewPayload("总览", "树", "主线", OverviewCodec.VERSION, "m", 1L)
        val back = OverviewCodec.decode(OverviewCodec.encode(payload))!!
        assertEquals("主线", back.mainlineMd)
        assertNull(OverviewCodec.decode(null))
        assertNull(OverviewCodec.decode("不是 JSON"))
    }

    // ---------- buildOverviewUserJson（预算与章数上限） ----------

    private fun chapter(id: Long, idx: Int) = ChapterEntity(
        id = id, bookId = 1L, idx = idx, title = "第${idx}章", readState = "READ",
        gist = null, keyTermsJson = null,
    )

    private fun asset(chapterId: Long) = ChapterAssetEntity(
        chapterId = chapterId, summaryMd = "总结".repeat(400), mindmapTree = "树",
        mindmapJson = null, memoryMd = "记", chainMd = "串".repeat(500),
        quizJson = "[]", model = "m", promptVersion = SummaryPlanner.SUMMARY_VERSION, createdAt = 1L,
    )

    @Test
    fun userJson_keepsOnlySummarizedChapters() {
        val chapters = listOf(chapter(1L, 0), chapter(2L, 1), chapter(3L, 2))
        val assets = mapOf(1L to asset(1L), 3L to asset(3L)) // 第 1 章未总结
        val json = buildOverviewUserJson("书", chapters, assets)
        assertTrue(json.contains("\"book\":"))
        assertTrue(json.contains("第0章") && json.contains("第2章"))
        assertFalse(json.contains("第1章")) // 未总结章不进输入
        kotlinx.serialization.json.Json.parseToJsonElement(json) // 合法 JSON
    }

    @Test
    fun userJson_capsChaptersAndBudgetTiers() {
        val chapters = (0L until 130L).map { chapter(it + 1, it.toInt()) }
        val assets = chapters.associate { it.id to asset(it.id) }
        val json = buildOverviewUserJson("书", chapters, assets)
        assertTrue("超 120 章取前 120", !json.contains("第120章") && json.contains("第119章"))
        assertTrue("预算硬顶 10 万", json.length <= AiBudgets.INPUT_CHARS_MAX)
        // 超预算触发降档：每章摘要/串联被截断到低于档 0 的 2000/1000 字
        assertTrue(!json.contains("总".repeat(400)))
        assertTrue(!json.contains("串".repeat(500)))
        kotlinx.serialization.json.Json.parseToJsonElement(json) // 合法 JSON
    }
}
