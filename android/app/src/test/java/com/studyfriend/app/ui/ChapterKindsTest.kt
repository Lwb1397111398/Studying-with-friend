package com.studyfriend.app.ui

import com.studyfriend.app.ui.screens.ChapterKinds
import com.studyfriend.app.ui.screens.ChapterKinds.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 前置/后置件判定纯函数（实景体验轮第六波）：《条文选注》14 万字「目录」章夹正文零提示实录 */
class ChapterKindsTest {

    @Test
    fun frontWords() {
        assertEquals(Kind.FRONT, ChapterKinds.kindOf("开篇"))
        assertEquals(Kind.FRONT, ChapterKinds.kindOf("目录"))
        assertEquals(Kind.FRONT, ChapterKinds.kindOf("目 录"))
        assertEquals(Kind.FRONT, ChapterKinds.kindOf("序言"))
        assertEquals(Kind.FRONT, ChapterKinds.kindOf("凡例"))
        assertEquals(Kind.FRONT, ChapterKinds.kindOf("编写说明"))
    }

    @Test
    fun backWords() {
        assertEquals(Kind.BACK, ChapterKinds.kindOf("附录一"))
        assertEquals(Kind.BACK, ChapterKinds.kindOf("附录二"))
        assertEquals(Kind.BACK, ChapterKinds.kindOf("参考文献"))
        assertEquals(Kind.BACK, ChapterKinds.kindOf("索 引"))
        assertEquals(Kind.BACK, ChapterKinds.kindOf("后记"))
        assertEquals(Kind.BACK, ChapterKinds.kindOf("条文索引"))
    }

    @Test
    fun bodyChapters() {
        // 真章不受词族影响（含编号或正文题名）
        assertEquals(Kind.BODY, ChapterKinds.kindOf("第一章 一般规定"))
        assertEquals(Kind.BODY, ChapterKinds.kindOf("第679条 自然人借款合同的成立"))
        assertEquals(Kind.BODY, ChapterKinds.kindOf("第二十九章 不当得利"))
        assertEquals(Kind.BODY, ChapterKinds.kindOf("债之发生"))
        // 前置词必须开头才算——「债之绪论」这种以「绪论」收尾的真章不误伤
        assertEquals(Kind.BODY, ChapterKinds.kindOf("债编总论绪论"))
    }

    @Test
    fun badgeMapping() {
        assertEquals("前置", ChapterKinds.badgeOf("目录"))
        assertEquals("附录", ChapterKinds.badgeOf("附录一"))
        assertNull(ChapterKinds.badgeOf("第一章 一般规定"))
    }
}
