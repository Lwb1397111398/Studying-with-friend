package com.studyfriend.app.mindmap

import org.junit.Assert.assertEquals
import org.junit.Test

/** 移植自 tingsiwei 已验证逻辑，回归锁定（M5 计划 §5：TreeText 5 例） */
class TreeTextTest {

    @Test
    fun parse_tabIndentBuildsHierarchy() {
        val forest = TreeText.parse("民法概说\n\t能力分档\n\t\t无民事行为能力\n\t行为能力要件")
        assertEquals(1, forest.size)
        assertEquals("民法概说", forest[0].topic)
        assertEquals(2, forest[0].children.size)
        assertEquals("无民事行为能力", forest[0].children[0].children[0].topic)
        assertEquals(0, forest[0].children[1].children.size)
    }

    @Test
    fun parse_spaceIndentFallbackFourPerLevel() {
        val forest = TreeText.parse("根\n    子\n        孙")
        assertEquals("孙", forest[0].children[0].children[0].topic)
    }

    @Test
    fun parse_cleansMarkdownMarks() {
        val forest = TreeText.parse("# 标题\n- **要点一**\n* 要点二\n• 要点三")
        assertEquals(listOf("标题", "要点一", "要点二", "要点三"), forest.map { it.topic })
    }

    @Test
    fun serialize_roundtripPreservesStructure() {
        val text = "根\n\t子一\n\t\t孙\n\t子二"
        val again = TreeText.serialize(TreeText.parse(text))
        val forest = TreeText.parse(again)
        assertEquals("子一", forest[0].children[0].topic)
        assertEquals("孙", forest[0].children[0].children[0].topic)
        assertEquals("子二", forest[0].children[1].topic)
    }

    @Test
    fun toMapData_singleRootDirectAndMultiRootVirtual() {
        val single = TreeText.toMapData(TreeText.parse("章名\n\t要点"), "书名")
        assertEquals(false, single.second)
        org.junit.Assert.assertTrue("单根直用：${single.first}", single.first.contains("章名") && single.first.contains("me-root"))

        val multi = TreeText.toMapData(TreeText.parse("甲\n\t甲子\n乙"), "书名")
        assertEquals(true, multi.second)
        org.junit.Assert.assertTrue("多根包虚拟根：${multi.first}", multi.first.contains("书名"))
    }
}
