package com.studyfriend.app.data.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-③ 引用溯源：〔p编号〕白名单软校验（纯函数，无 Android 依赖） */
class ParaRefTest {

    @Test
    fun strip_keepsValidAndDropsOutliers() {
        val out = ParaRef.stripInvalid("先看〔p0〕这段〔p5〕再收尾", isValid = { it < 1 })
        assertEquals("合法〔p0〕保留、越界〔p5〕剔除，其余原样", "先看〔p0〕这段再收尾", out)
    }

    @Test
    fun strip_noRefsUnchanged() {
        val t = "完全没有标记的正文，〔图：3〕这种提示也不动"
        assertEquals(t, ParaRef.stripInvalid(t) { true })
        assertEquals(t, ParaRef.stripInvalid(t) { false })
    }

    @Test
    fun strip_mixedAndAdjacent() {
        val out = ParaRef.stripInvalid("〔p2〕〔p3〕相连〔p99〕", isValid = { it in 0..3 })
        assertEquals("相邻合法标记都保留，仅剔越界", "〔p2〕〔p3〕相连", out)
    }

    @Test
    fun regex_onlyMatchesParaMarker() {
        // 正文常见形近字面（全角括号内容非 p数字）不误匹配
        assertFalse(ParaRef.REGEX.containsMatchIn("〔图：3〕〔注〕(p3)〔 P3 〕"))
        assertTrue(ParaRef.REGEX.containsMatchIn("句末〔p12〕。"))
    }

    @Test
    fun summaryPlannerStyle_setPredicate() {
        // 总结侧用法：白名单是 idx 集合
        val allow = setOf(0, 2)
        val out = ParaRef.stripInvalid("〔p0〕在〔p1〕不在〔p2〕在", isValid = { it in allow })
        assertEquals("〔p0〕在不在〔p2〕在", out)
    }
}
