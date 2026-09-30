package com.studyfriend.app.data.study

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4a §5-2/3：PlanParser 容错归一（10 例）+ unit_budget（2 例）+ 宽松数字解析 */
class PlanParserTest {

    private fun entry(id: Int, action: String, group: List<Int> = emptyList(), why: String? = null) =
        PlanEntry(id = id, action = action, group = group, why = why)

    private val ten = (0 until 10).toSet()

    /** 块内未出现在 entries 里的段补 skip：保证不触发缺标阈值，聚焦被测分支 */
    private fun covered(entries: List<PlanEntry>, block: Set<Int> = ten) = RoughReadPlan(
        paragraphs = entries + block.filter { idx -> entries.none { it.id == idx } }
            .map { PlanEntry(id = it, action = "skip") },
    )

    @Test
    fun legalMixedPlan_mapsAllDecisions() {
        val plan = covered(
            listOf(
                entry(0, "skip"),
                entry(1, "explain"),
                entry(2, "group", group = listOf(2, 3, 4), why = "三段连贯"),
                entry(3, "group", group = listOf(2, 3, 4)),
                entry(4, "group", group = listOf(2, 3, 4)),
            ),
        )
        val result = PlanParser.parse(plan, ten)
        assertEquals(AiAction.SKIP, result[0]!!.action)
        assertEquals(AiAction.EXPLAIN, result[1]!!.action)
        // GROUP：每段都是 GROUP 且 groupId（groupStart）= 组首段 2
        for (i in 2..4) {
            assertEquals(AiAction.GROUP, result[i]!!.action)
            assertEquals(2, result[i]!!.groupStart)
        }
    }

    @Test
    fun hallucinatedIdDropped_butMissingStillUnderThreshold() {
        // 4 段块标 3 段 + 1 个幻觉编号：丢 99 后缺 1/4 = 25% 仍通过
        val plan = RoughReadPlan(
            paragraphs = listOf(
                entry(0, "explain"), entry(1, "explain"), entry(2, "explain"),
                entry(99, "explain"),
            ),
        )
        val result = PlanParser.parse(plan, (0 until 4).toSet())
        assertEquals(3, result.size)
        assertTrue(99 !in result)
    }

    @Test
    fun actionCaseAndWhitespaceNormalized() {
        val plan = covered(
            listOf(entry(0, "  Skip  "), entry(1, "EXPLAIN"), entry(2, "Group", group = listOf(2, 3))),
            block = (0 until 4).toSet(),
        )
        val result = PlanParser.parse(plan, (0 until 4).toSet())
        assertEquals(AiAction.SKIP, result[0]!!.action)
        assertEquals(AiAction.EXPLAIN, result[1]!!.action)
        assertEquals(AiAction.GROUP, result[2]!!.action)
    }

    @Test
    fun nonContiguousGroupDegradesToExplain() {
        val result = PlanParser.parse(covered(listOf(entry(1, "group", group = listOf(1, 3)))), ten)
        assertEquals(AiAction.EXPLAIN, result[1]!!.action)
    }

    @Test
    fun oversizedGroupDegradesToExplain() {
        val result = PlanParser.parse(covered(listOf(entry(0, "group", group = listOf(0, 1, 2, 3)))), ten)
        assertEquals(AiAction.EXPLAIN, result[0]!!.action)
    }

    @Test
    fun singleParagraphGroupDegradesToExplain() {
        val result = PlanParser.parse(covered(listOf(entry(2, "group", group = listOf(2)))), ten)
        assertEquals(AiAction.EXPLAIN, result[2]!!.action)
    }

    @Test
    fun duplicateIdLastWins() {
        val result = PlanParser.parse(
            covered(listOf(entry(5, "skip"), entry(5, "explain"))),
            ten,
        )
        assertEquals(AiAction.EXPLAIN, result[5]!!.action)
    }

    @Test
    fun whyTruncatedTo40Chars() {
        val result = PlanParser.parse(covered(listOf(entry(0, "explain", why = "理".repeat(50)))), ten)
        assertEquals(40, result[0]!!.why!!.length)
    }

    @Test
    fun missingExactly30PercentPasses() {
        // 10 段标 7：缺 3 = 30% 恰好不超阈值（>30% 才抛）
        val plan = RoughReadPlan(paragraphs = (0 until 7).map { entry(it, "explain") })
        val result = PlanParser.parse(plan, ten)
        assertEquals(7, result.size)
    }

    @Test(expected = PlannerException::class)
    fun missingOver30PercentThrows() {
        val plan = RoughReadPlan(
            paragraphs = listOf(
                entry(0, "explain"), entry(1, "explain"), entry(2, "explain"), entry(3, "skip"),
            ),
        )
        PlanParser.parse(plan, ten) // 10 段只标 4，缺 6/10
    }

    // ---------- unit_budget（计划 M4a §2.1）----------

    @Test
    fun unitBudget_twoToOneRatioClampsToFourAndThree() {
        // 全章 9000 字：块 A 6000、块 B 3000 → round(10×2/3)=7→4；round(10×1/3)=3
        assertEquals(4, PlanParser.unitBudget(6000, 9000, blockTotal = 2))
        assertEquals(3, PlanParser.unitBudget(3000, 9000, blockTotal = 2))
    }

    @Test
    fun unitBudget_singleBlockIsTen() {
        assertEquals(10, PlanParser.unitBudget(9000, 9000, blockTotal = 1))
    }

    // ---------- 宽松数字（LenientIntSerializer）----------

    @Test
    fun lenientParsing_acceptsStringNumbers() {
        val json = Json { ignoreUnknownKeys = true }
        val plan = json.decodeFromString(
            RoughReadPlan.serializer(),
            """{"paragraphs":[{"id":"0","action":"skip"},{"id":"1","action":"skip"},""" +
                """{"id":"2","action":"group","group":["2","3"]},{"id":"3","action":"explain"}]}""",
        )
        val result = PlanParser.parse(plan, (0 until 4).toSet())
        assertEquals(AiAction.GROUP, result[2]!!.action)
        assertEquals(2, result[2]!!.groupStart)
        assertEquals(AiAction.EXPLAIN, result[3]!!.action)
    }

    @Test
    fun lenientParsing_nullOrGarbageIdBecomesNegativeOne() {
        // id=null / 非数字 → -1：必不在任何块内，幻觉条目被静默丢弃而不是错标第 0 段
        val json = Json { ignoreUnknownKeys = true }
        val plan = json.decodeFromString(
            RoughReadPlan.serializer(),
            """{"paragraphs":[{"id":null,"action":"explain"},{"id":"abc","action":"explain"},""" +
                """{"id":1,"action":"skip"},{"id":2,"action":"skip"},{"id":3,"action":"skip"}]}""",
        )
        val result = PlanParser.parse(plan, (0 until 4).toSet())
        assertNull(result[-1])
        assertNull(result[0]) // 幻觉 id 不得落到真实段落上
        assertEquals(AiAction.SKIP, result[1]!!.action)
        assertEquals(AiAction.SKIP, result[2]!!.action)
        assertEquals(AiAction.SKIP, result[3]!!.action)
    }
}
