package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 计划 §5：五标签解析 7 + QuizCodec 3 + 输入组装 3 = 13 例 */
class SummaryModelsTest {

    // ---------- TaggedBundleParser ----------

    private fun legalQuizJson(n: Int = 2): String = QuizCodec.encode(
        (1..n).map { QuizQuestion("RECALL", "问$it", "答$it", "解析$it") },
    )

    private fun legalRaw(): String = buildString {
        append("<总结>\n本章讲民事行为能力。\n</总结>\n")
        append("<导图>\n民事行为能力\n\t三档划分\n</导图>\n")
        append("<记忆思路>\n把三档想成孩子的成长。\n</记忆思路>\n")
        append("<串联>\n从胎儿到成年的一条成长线。\n</串联>\n")
        append("<自测>\n").append(legalQuizJson()).append("\n</自测>")
    }

    @Test
    fun parser_normalFiveTagsAllParsed() {
        val b = TaggedBundleParser.parse(legalRaw())!!
        assertEquals("本章讲民事行为能力。", b.summaryMd)
        assertTrue(b.mindmapTree.contains("三档划分"))
        assertEquals("把三档想成孩子的成长。", b.memoryMd)
        assertEquals(2, b.questions.size)
        assertEquals("问1", b.questions[0].q)
    }

    @Test
    fun parser_outOfOrderTagsStillParsed() {
        val raw = legalRaw()
            .replace(Regex("<总结>[\\s\\S]*?</总结>\\n?"), "")
            .replace("</串联>", "</串联>\n<总结>尾部出现的总结。</总结>")
        val b = TaggedBundleParser.parse(raw)!!
        assertEquals("尾部出现的总结。", b.summaryMd)
    }

    @Test
    fun parser_missingTagReturnsNull() {
        val raw = legalRaw().replace(Regex("<串联>[\\s\\S]*?</串联>"), "")
        assertNull(TaggedBundleParser.parse(raw))
    }

    @Test
    fun parser_quizFenceStripped() {
        val raw = legalRaw().replace(legalQuizJson(), "```json\n${legalQuizJson()}\n```")
        val b = TaggedBundleParser.parse(raw)!!
        assertEquals(2, b.questions.size)
    }

    @Test
    fun parser_blankTagReturnsNull() {
        val raw = legalRaw().replace(
            Regex("<记忆思路>[\\s\\S]*?</记忆思路>"),
            "<记忆思路>   </记忆思路>",
        )
        assertNull(TaggedBundleParser.parse(raw))
    }

    @Test
    fun parser_questionsCappedAt5() {
        val raw = legalRaw().replace(legalQuizJson(), legalQuizJson(7))
        assertEquals(5, TaggedBundleParser.parse(raw)!!.questions.size)
    }

    @Test
    fun parser_zeroQuestionsReturnsNull() {
        val raw = legalRaw().replace(legalQuizJson(), "[]")
        assertNull(TaggedBundleParser.parse(raw))
    }

    // ---------- QuizCodec ----------

    @Test
    fun quizCodec_roundtrip() {
        val qs = listOf(QuizQuestion("CASE", "场景题", "结论", "理由"))
        assertEquals(qs, QuizCodec.decode(QuizCodec.encode(qs)))
    }

    @Test
    fun quizCodec_badJsonFallsBackEmpty() {
        assertTrue(QuizCodec.decode("不是 JSON").isEmpty())
        assertTrue(QuizCodec.decode(null).isEmpty())
    }

    @Test
    fun quizCodec_typeNormalizedToWhitelist() {
        val qs = QuizCodec.decode("""[{"type":"case","q":"问","a":"答"},{"type":"胡说","q":"问2","a":"答2"}]""")
        assertEquals("CASE", qs[0].type)
        assertEquals("RECALL", qs[1].type) // 白名单外归 RECALL
    }

    // ---------- buildExportMd（导出拼接 2 例） ----------

    @Test
    fun exportMd_containsAllFiveSections() {
        val asset = ChapterAssetEntity(
            chapterId = 1L, summaryMd = "总结正文。", mindmapTree = "民事行为能力\n\t三档划分",
            mindmapJson = null, memoryMd = "记忆思路正文。", chainMd = "串联正文。",
            quizJson = QuizCodec.encode(
                listOf(QuizQuestion("RECALL", "三档分别是什么？", "无、限制、完全", "按年龄界限记")),
            ),
            model = "test-model", promptVersion = "summary-pack-v1", createdAt = 1L,
        )
        val md = buildExportMd(
            bookTitle = "民法入门", chapterTitle = "民事行为能力", asset = asset,
            questions = QuizCodec.decode(asset.quizJson), generatedAt = "2026-09-28",
        )
        assertTrue(md.contains("# 民事行为能力 · 总结包"))
        assertTrue(md.contains("《民法入门》"))
        assertTrue(md.contains("2026-09-28"))
        assertTrue(md.contains("## 总结"))
        assertTrue(md.contains("总结正文。"))
        assertTrue(md.contains("```text\n民事行为能力\n\t三档划分\n```"))
        assertTrue(md.contains("## 记忆思路"))
        assertTrue(md.contains("记忆思路正文。"))
        assertTrue(md.contains("## 串联"))
        assertTrue(md.contains("串联正文。"))
        assertTrue(md.contains("1. [回想] 三档分别是什么？"))
        assertTrue(md.contains("答案：无、限制、完全"))
        assertTrue(md.contains("解析：按年龄界限记"))
    }

    @Test
    fun exportMd_emptyQuizNotesPlaceholder() {
        val asset = ChapterAssetEntity(
            chapterId = 1L, summaryMd = "s", mindmapTree = "t", mindmapJson = null,
            memoryMd = "m", chainMd = "c", quizJson = "[]",
            model = "m", promptVersion = "v", createdAt = 1L,
        )
        val md = buildExportMd("书", "章", asset, emptyList(), "2026-09-28")
        assertTrue(md.contains("（本包无自测题）"))
    }

    // ---------- buildSummaryUserJson（预算与降级） ----------

    private fun para(idx: Int, action: String, textLen: Int, groupId: Long? = null) = ParagraphEntity(
        chapterId = 1L, idx = idx, text = "段".repeat(textLen), role = "BODY",
        aiAction = action, groupId = groupId,
    )

    @Test
    fun input_skipBudgetAtTier0AndNoteOnlyOnAnchor() {
        val paras = listOf(
            para(0, "EXPLAIN", 1000),
            para(1, "SKIP", 100),
            para(2, "GROUP", 800, groupId = 2L),
            para(3, "GROUP", 800, groupId = 2L), // 组员非锚：不带 note
            para(4, "NONE", 500), // NONE 不进输入
        )
        val anchorNote = ParaNoteEntity(
            chapterId = 1L, paraIds = "[2,3]", title = "能力三档讲", friendly = "友好的讲解",
            analogy = null, keyPointsJson = "[]", memoryHook = null, questionsJson = null,
            model = "m", promptVersion = "v", createdAt = 1L,
        )
        val json = buildSummaryUserJson("第一章", "梗概", listOf("行为能力"), paras, mapOf(2 to anchorNote))

        val skipText = Regex("\"id\":1,\"act\":\"skip\",\"text\":\"(段+)\"").find(json)!!.groupValues[1]
        assertEquals("SKIP 段只带 60 字", 60, skipText.length)
        val explainText = Regex("\"id\":0,\"act\":\"explain\",\"text\":\"(段+)\"").find(json)!!.groupValues[1]
        assertEquals("档 0 讲解段 500 字", 500, explainText.length)
        assertTrue("锚段带 note", json.contains("能力三档讲"))
        assertFalse("组员不带 note", json.substringAfter("能力三档讲").contains("\"note\""))
        assertFalse("NONE 段不进输入", json.contains("\"id\":4"))
    }

    @Test
    fun input_overlongChapterDowngradesWithinBudget() {
        // 100 段全 EXPLAIN、每段 400 字：档 0 直接爆预算，必须逐档降级到 ≤24K
        val paras = (0 until 100).map { para(it, "EXPLAIN", 400) }
        val json = buildSummaryUserJson("长章", null, emptyList(), paras, emptyMap())
        assertTrue("总字数 ${json.length} 应 ≤24000", json.length <= 24_000)
    }

    @Test
    fun input_smallChapterStaysTier0() {
        val paras = (0 until 5).map { para(it, "EXPLAIN", 100) }
        val json = buildSummaryUserJson("短章", "梗概", emptyList(), paras, emptyMap())
        assertTrue("小章不降级：每段全文 100 字保留", json.contains("段".repeat(100)))
        assertTrue(json.length < 24_000)
    }
}
