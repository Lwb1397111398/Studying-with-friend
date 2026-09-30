package com.studyfriend.app.ui

import androidx.compose.ui.text.AnnotatedString
import com.studyfriend.app.ui.components.MdBlock
import com.studyfriend.app.ui.components.buildInline
import com.studyfriend.app.ui.components.parseMdBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 计划 §5：MdText 3 例（块解析/空输入安全/行内记号 span） */
class MdTextTest {

    @Test
    fun parse_headingsBulletsAndParas() {
        val md = "# 大标题\n## 二级\n### 三级\n- 甲条\n* 乙条\n普通段落\n\n"
        val blocks = parseMdBlocks(md)
        assertEquals(6, blocks.size)
        assertEquals(MdBlock.Heading(1, "大标题"), blocks[0])
        assertEquals(MdBlock.Heading(2, "二级"), blocks[1])
        assertEquals(MdBlock.Heading(3, "三级"), blocks[2]) // 更深层级归 3
        assertEquals(MdBlock.Bullet("甲条"), blocks[3])
        assertEquals(MdBlock.Bullet("乙条"), blocks[4])
        assertEquals(MdBlock.Para("普通段落"), blocks[5])
    }

    @Test
    fun parse_blankAndNoisySafe() {
        assertEquals(0, parseMdBlocks("").size)
        assertEquals(0, parseMdBlocks("   \n\t\n").size)
        // 乱记号行归普通段落，不抛错
        assertEquals(MdBlock.Para("**未闭合"), parseMdBlocks("**未闭合")[0])
    }

    @Test
    fun inline_boldAndCodeAnnotationSpans() {
        val s: AnnotatedString = buildInline("**粗**与`码`结尾")
        assertEquals("粗与码结尾", s.text)
        val bold = s.getStringAnnotations("b", 0, s.length)
        assertEquals(1, bold.size)
        assertEquals(0, bold[0].start)
        assertEquals(1, bold[0].end)
        val code = s.getStringAnnotations("code", 0, s.length)
        assertEquals(1, code.size)
        assertEquals(2, code[0].start)
        assertEquals(3, code[0].end)
        assertTrue("无记号文本不加 span", buildInline("平平无奇").getStringAnnotations("b", 0, 4).isEmpty())
    }
}
