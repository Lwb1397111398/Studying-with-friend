package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ParagraphEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4b §5-1/2/3：讲解单元枚举（5）+ prompt 截断（1）+ NoteParser（5）+ NoteTaggedParser（5） */
class NoteModelsTest {

    private fun para(idx: Int, action: String = "NONE", groupId: Long? = null, textLen: Int = 20): ParagraphEntity =
        ParagraphEntity(
            id = idx + 100L, chapterId = 1L, idx = idx, text = "段$idx" + "字".repeat(textLen),
            role = "BODY", aiAction = action, groupId = groupId,
        )

    // ---------- enumerateUnits ----------

    @Test
    fun enum_explainMakesSingleUnit() {
        val units = enumerateUnits(listOf(para(0, "EXPLAIN"), para(1, "SKIP")))
        assertEquals(1, units.size)
        assertEquals(0, units[0].anchor.idx)
        assertEquals(listOf(100L), units[0].members.map { it.id })
    }

    @Test
    fun enum_groupAggregatesByGroupIdValue() {
        val units = enumerateUnits(
            listOf(para(0, "GROUP", 0), para(1, "GROUP", 0), para(2, "SKIP")),
        )
        assertEquals(1, units.size)
        assertEquals(0, units[0].anchor.idx) // 锚 = idx==groupId 的成员
        assertEquals(listOf(100L, 101L), units[0].members.map { it.id })
    }

    @Test
    fun enum_orphanMembersStillFormUnitWithSmallestIdxAnchor() {
        // 组首被标 EXPLAIN 的孤儿数据（M4a 允许）：组员仍成组单元，锚取组内最小 idx
        val units = enumerateUnits(
            listOf(para(0, "EXPLAIN"), para(1, "GROUP", 0), para(2, "GROUP", 0)),
        )
        assertEquals(2, units.size)
        val group = units.first { it.members.size == 2 }
        assertEquals(1, group.anchor.idx)
        assertEquals(listOf(101L, 102L), group.members.map { it.id })
    }

    @Test
    fun enum_groupSizeBounds_degenerateAndCap() {
        // 单员组退化为单段单元
        val single = enumerateUnits(listOf(para(0, "GROUP", 0)))
        assertEquals(1, single.size)
        assertEquals(listOf(100L), single[0].members.map { it.id })
        // 超过 3 段截前 3
        val capped = enumerateUnits((0 until 5).map { para(it, "GROUP", 0) })
        assertEquals(1, capped.size)
        assertEquals(listOf(100L, 101L, 102L), capped[0].members.map { it.id })
    }

    @Test
    fun enum_skipNoneAndNullGroupIdNotUnits() {
        val units = enumerateUnits(
            listOf(
                para(0, "EXPLAIN"), para(1, "GROUP", 1), para(2, "GROUP", 1),
                para(3, "SKIP"), para(4, "NONE"), para(5, "GROUP", null),
            ),
        )
        assertEquals(2, units.size)
        assertEquals(setOf(0, 1), units.map { it.anchor.idx }.toSet())
    }

    // ---------- textsForPrompt ----------

    @Test
    fun textForPrompt_capsPerParagraphAndTotal() {
        val unit = ExplainUnit(
            para(0, "EXPLAIN", textLen = 10_000 - 2), // "段0" 占 2 字 → 全长 10000
            listOf(
                para(0, "EXPLAIN", textLen = 9998), // 10000 字
                para(1, "EXPLAIN", textLen = 9998), // 10000 字，累计 20000
                para(2, "EXPLAIN", textLen = 9998), // 10000 字，累计 30000
                para(3, "EXPLAIN", textLen = 8998), // 9000 字，累计 39000
                para(4, "EXPLAIN", textLen = 9998), // 只剩 1000 字余量
            ),
        )
        val texts = unit.textsForPrompt()
        assertEquals(5, texts.size)
        assertEquals(listOf(10_000, 10_000, 10_000, 9000, 1000), texts.map { it.length })
        assertTrue(texts.all { it.length <= 10_000 })
    }

    // ---------- NoteParser ----------

    private val legal = NotePlan(
        title = "标题",
        friendly = "大白话讲解",
        analogy = "类比",
        keyPoints = listOf("要点一", "要点二"),
        memoryHook = "钩子",
        checkQuestions = listOf(CheckQuestion("问", "答")),
    )

    @Test
    fun parse_legalFullKept() {
        val n = NoteParser.normalize(legal, "锚段")
        assertEquals("标题", n.title)
        assertEquals("大白话讲解", n.friendly)
        assertEquals(listOf("要点一", "要点二"), n.keyPoints)
        assertEquals(1, n.checkQuestions.size)
    }

    @Test
    fun parse_blankFriendlyThrows() {
        assertThrows(PlannerException::class.java) {
            NoteParser.normalize(legal.copy(friendly = "  "), "锚段")
        }
    }

    @Test
    fun parse_blankTitleFallsBackToAnchorHead() {
        val anchor = "这是一段作为锚点的原文，标题缺失时取前十六个字补位"
        val n = NoteParser.normalize(legal.copy(title = " "), anchor)
        assertEquals(anchor.take(16), n.title)
    }

    @Test
    fun parse_keyPointsFilteredAndCapped() {
        val long = "长".repeat(40)
        val n = NoteParser.normalize(
            legal.copy(keyPoints = listOf("", "  ", long, "三", "四", "五", "六", "七")),
            "锚段",
        )
        assertEquals(5, n.keyPoints.size)
        assertEquals(30, n.keyPoints[0].length) // 长条目截 30
        assertEquals("三", n.keyPoints[1]) // 空白条目先过滤再截 5：长条目后紧跟"三"
    }

    @Test
    fun parse_questionsFilteredAndCapped() {
        val qs = (1..4).map { CheckQuestion("问$it", "答$it") } + CheckQuestion(" ", "答空")
        val n = NoteParser.normalize(legal.copy(checkQuestions = qs), "锚段")
        assertEquals(3, n.checkQuestions.size)
        assertEquals("问1", n.checkQuestions[0].q)
    }

    // ---------- NoteTaggedParser（v2 标签文本） ----------

    private val legalTagged = """
        <标题>标题</标题>
        <讲解>
        大白话讲解，第一段。

        **加粗**关键概念，第二段。
        </讲解>
        <类比>类比</类比>
        <要点>
        - 要点一
        • 要点二
        3. 要点三
        </要点>
        <钩子>钩子</钩子>
        <自测>
        问: 第一问
        答: 第一答，多一句补充。
        Q: second question
        A: second answer
        </自测>
    """.trimIndent()

    @Test
    fun tagged_legalFullParsed() {
        val p = NoteTaggedParser.parse(legalTagged)!!
        assertEquals("标题", p.title)
        assertTrue(p.friendly.startsWith("大白话讲解"))
        assertTrue(p.friendly.contains("**加粗**"))
        assertEquals("类比", p.analogy)
        assertEquals(listOf("要点一", "要点二", "要点三"), p.keyPoints) // 三种列表前缀都剥掉
        assertEquals("钩子", p.memoryHook)
        assertEquals(2, p.checkQuestions.size)
        assertEquals("第一问", p.checkQuestions[0].q)
        assertEquals("第一答，多一句补充。", p.checkQuestions[0].a)
        assertEquals("second question", p.checkQuestions[1].q)
        assertEquals("second answer", p.checkQuestions[1].a)
    }

    @Test
    fun tagged_optionalTagsMayBeOmitted() {
        val p = NoteTaggedParser.parse("<标题>t</标题>\n<讲解>只有讲解主体</讲解>")!!
        assertEquals("只有讲解主体", p.friendly)
        assertEquals("", p.analogy)
        assertEquals(emptyList<String>(), p.keyPoints)
        assertEquals("", p.memoryHook)
        assertEquals(emptyList<CheckQuestion>(), p.checkQuestions)
    }

    @Test
    fun tagged_missingFriendlyReturnsNull() {
        assertNull(NoteTaggedParser.parse("<标题>t</标题>\n<类比>只有类比</类比>"))
    }

    @Test
    fun tagged_truncatedFriendlyStillParsed() {
        // 输出被 max_tokens 截断（finish=length）：<讲解> 没闭合也要拿到主体，不整卡报废
        val raw = "<标题>t</标题>\n<讲解>开头就讲，越讲越长，突然被截断"
        val p = NoteTaggedParser.parse(raw)!!
        assertEquals("开头就讲，越讲越长，突然被截断", p.friendly)
    }

    @Test
    fun tagged_questionWithoutAnswerDropped() {
        val raw = "<讲解>主体</讲解>\n<自测>\n问: 只有问没有答\n问: 有问也有答\n答: 答案\n</自测>"
        val p = NoteTaggedParser.parse(raw)!!
        assertEquals(1, p.checkQuestions.size)
        assertEquals("有问也有答", p.checkQuestions[0].q)
    }

    // ---------- ParaIdsCodec ----------

    @Test
    fun paraIdsCodec_deterministicRoundtrip() {
        val ids = listOf(3L, 1L, 2L)
        assertEquals("[3,1,2]", ParaIdsCodec.encode(ids))
        assertEquals(ids, ParaIdsCodec.decode(ParaIdsCodec.encode(ids)))
        assertEquals(emptyList<Long>(), ParaIdsCodec.decode(null))
        assertEquals(emptyList<Long>(), ParaIdsCodec.decode("不是JSON"))
    }
}
