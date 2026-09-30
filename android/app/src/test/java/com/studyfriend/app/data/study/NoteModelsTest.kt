package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ParagraphEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4b §5-1/2/3：讲解单元枚举（5）+ prompt 截断（1）+ NoteParser（5） */
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
            para(0, "EXPLAIN", textLen = 2500 - 2), // "段0" 占 2 字 → 全长 2500
            listOf(
                para(0, "EXPLAIN", textLen = 2498),
                para(1, "EXPLAIN", textLen = 2498),
                para(2, "EXPLAIN", textLen = 2498),
            ),
        )
        val texts = unit.textsForPrompt()
        assertEquals(3, texts.size)
        assertEquals(2500, texts[0].length)
        assertEquals(2500, texts[1].length)
        assertEquals(1000, texts[2].length) // 合计 6000 封顶
        assertTrue(texts.all { it.length <= 2500 })
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
