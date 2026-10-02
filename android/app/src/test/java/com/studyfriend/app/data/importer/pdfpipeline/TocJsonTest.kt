package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录 JSON 解码（P3b-1）：单条目容错 + 三守卫（≥3 条 / level=1 ≥1 / 页码非降序 ≥90%）。
 * 纯 JVM：kotlinx-serialization + JsonSlicer 均无 Android 依赖。
 */
class TocJsonTest {

    private fun toc(vararg triples: Triple<String, Int?, Int>): String =
        triples.joinToString(",", prefix = "{\"entries\":[", postfix = "]}") { (t, p, l) ->
            """{"title":"$t","page":${p ?: "null"},"level":$l}"""
        }

    private val standard = toc(
        Triple("第一章 总则", 1, 1),
        Triple("第一节 概述", 2, 2),
        Triple("第二章 分则", 10, 1),
        Triple("第二节 结尾", 12, 2),
    )

    @Test
    fun parse_standardEntries() {
        val r = TocJson.parse(standard)!!
        assertEquals(4, r.size)
        // v3 归一化：章号后单空格属排版空格，解析时删除
        assertEquals(TocEntry("第一章总则", 1, 1), r[0])
        assertEquals(TocEntry("第二节结尾", 12, 2), r[3])
    }

    @Test
    fun parse_stripsCodeFence() {
        val r = TocJson.parse("```json\n$standard\n```")
        assertEquals(4, r!!.size)
    }

    @Test
    fun parse_nullPageKept() {
        val r = TocJson.parse(
            toc(Triple("甲", 1, 1), Triple("乙", null, 2), Triple("丙", 3, 1)),
        )!!
        assertEquals(3, r.size)
        assertNull(r[1].page)
    }

    @Test
    fun parse_nonPositivePageDropsEntry() {
        // page=0 和 -5 的条目丢弃，其余 3 条保留且页码仍升序
        val r = TocJson.parse(
            toc(Triple("甲", 1, 1), Triple("坏1", 0, 2), Triple("丙", 2, 2), Triple("丁", 3, 1), Triple("坏2", -5, 2)),
        )
        assertEquals(listOf("甲", "丙", "丁"), r!!.map { it.title })
    }

    @Test
    fun parse_floatOrStringPageDropsEntry() {
        // 浮点 1.5 与字符串 "3" 都不是合法页码：6 条丢 2 剩 4
        val raw = """{"entries":[""" +
            """{"title":"甲","page":1,"level":1},{"title":"坏1","page":1.5,"level":2},""" +
            """{"title":"坏2","page":"3","level":2},{"title":"丙","page":2,"level":2},""" +
            """{"title":"丁","page":3,"level":1},{"title":"戊","page":4,"level":2}]}"""
        val r = TocJson.parse(raw)
        assertEquals(listOf("甲", "丙", "丁", "戊"), r!!.map { it.title })
    }

    @Test
    fun parse_levelThreeDropsEntry() {
        val r = TocJson.parse(
            toc(Triple("甲", 1, 1), Triple("坏", 2, 3), Triple("丙", 2, 2), Triple("丁", 3, 1), Triple("戊", 4, 2)),
        )
        assertEquals(listOf("甲", "丙", "丁", "戊"), r!!.map { it.title })
    }

    @Test
    fun parse_badTitleDropsEntry() {
        // 上限 80（v3）：81 字丢弃；41 字真书条目保留见 parse_realLongTitleKept
        val long = "超".repeat(81)
        val raw = """{"entries":[""" +
            """{"title":"$long","page":1,"level":1},""" +
            """{"title":"   ","page":2,"level":2},""" +
            """{"title":"丙","page":2,"level":2},""" +
            """{"title":"丁","page":3,"level":1},""" +
            """{"title":"戊","page":4,"level":2}]}"""
        val r = TocJson.parse(raw)
        assertEquals(listOf("丙", "丁", "戊"), r!!.map { it.title })
    }

    @Test
    fun parse_realLongTitleKept() {
        // 真书回归：不当得利 GT 实测 41 字条目（法条引注），旧上限 40 会静默丢弃
        val real41 = "第二节 因不可归责于当事人双方给付不能的效力与不当得利:“民法”第266条第2项规定"
        assertTrue("示例应超过旧上限 40", real41.length > 40)
        val r = TocJson.parse(toc(Triple(real41, 1, 1), Triple("乙", 2, 2), Triple("丙", 3, 1)))!!
        // v3 归一化删章号后空格，长度随之 -1，仍远超旧上限
        assertEquals(TocJson.normalizeTitle(real41), r[0].title)
        assertTrue(r[0].title.length > 40)
    }

    @Test
    fun normalizeTitle_removesTypoSpacesKeepsLatin() {
        // 目录页分散对齐空格（v3 归一）：字间/数字两侧删，纯拉丁词间留
        assertEquals("第一章绪论", TocJson.normalizeTitle("第一章 绪 论"))
        assertEquals("索引", TocJson.normalizeTitle("索　引")) // 全角 U+3000
        assertEquals("“司法院”释字第515号解释", TocJson.normalizeTitle("“司法院”释字第 515 号解释"))
        assertEquals("Appendix A", TocJson.normalizeTitle("Appendix A"))
        assertEquals("索引", TocJson.normalizeTitle(" 索 引 "))
    }

    @Test
    fun parse_fewerThanThreeEntriesIsNull() {
        assertNull(TocJson.parse(toc(Triple("甲", 1, 1), Triple("乙", 2, 2))))
    }

    @Test
    fun parse_overTenPercentOutOfOrderIsNull() {
        // 10 条 9 对，1 对逆序 (9,4) = 11.1% > 10% 上限
        val pages = listOf(1, 2, 3, 9, 4, 5, 6, 7, 8, 10)
        val raw = toc(*pages.mapIndexed { i, p -> Triple("t$i", p, if (i == 0) 1 else 2) }.toTypedArray())
        assertNull(TocJson.parse(raw))
    }

    @Test
    fun parse_fullyReversedIsNull() {
        val raw = toc(
            Triple("甲", 5, 1), Triple("乙", 4, 2), Triple("丙", 3, 2), Triple("丁", 2, 2), Triple("戊", 1, 2),
        )
        assertNull(TocJson.parse(raw))
    }

    @Test
    fun parse_noLevelOneIsNull() {
        val raw = toc(Triple("甲", 1, 2), Triple("乙", 2, 2), Triple("丙", 3, 2))
        assertNull(TocJson.parse(raw))
    }

    @Test
    fun parse_invalidJsonIsNull() {
        assertNull(TocJson.parse("这不是 JSON"))
        assertNull(TocJson.parse("{\"no_entries\":[]}"))
        assertNull(TocJson.parse("{\"entries\":[]}"))
    }

    @Test
    fun parse_toleratesSurroundingNoise() {
        val r = TocJson.parse("好的，以下是识别结果：\n$standard\n希望有帮助。")
        assertEquals(4, r!!.size)
    }

    @Test
    fun parse_allSameAndDensePagesPass() {
        // 页码全同（平级条目集中排版）与密集非降序都属正常目录形态，不误杀
        val same = toc(Triple("甲", 5, 1), Triple("乙", 5, 2), Triple("丙", 5, 2), Triple("丁", 5, 2))
        val dense = toc(Triple("甲", 1, 1), Triple("乙", 1, 2), Triple("丙", 2, 2), Triple("丁", 2, 2), Triple("戊", 3, 1), Triple("己", 3, 2))
        assertEquals(4, TocJson.parse(same)!!.size)
        assertEquals(6, TocJson.parse(dense)!!.size)
    }
}
