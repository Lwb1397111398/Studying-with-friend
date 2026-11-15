package com.studyfriend.app.ui

import androidx.compose.ui.text.AnnotatedString
import com.studyfriend.app.ui.components.MdBlock
import com.studyfriend.app.ui.components.ParaRefSegment
import com.studyfriend.app.ui.components.buildInline
import com.studyfriend.app.ui.components.buildInlineWithRefs
import com.studyfriend.app.ui.components.parseMdBlocks
import com.studyfriend.app.ui.components.splitParaRefs
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

    // ---------- P1-③ 引用溯源行内渲染 ----------

    @Test
    fun paraRefs_splitInterleavesPlainAndRef() {
        val segs = splitParaRefs("前文〔p0〕中**粗**〔p12〕尾")
        assertEquals(5, segs.size)
        assertEquals(ParaRefSegment.Plain("前文"), segs[0])
        assertEquals(ParaRefSegment.Ref(0), segs[1])
        assertEquals(ParaRefSegment.Plain("中**粗**"), segs[2])
        assertEquals(ParaRefSegment.Ref(12), segs[3])
        assertEquals(ParaRefSegment.Plain("尾"), segs[4])
        // 无标记整段单 Plain（渲染端据此走原路径）
        assertEquals(listOf<ParaRefSegment>(ParaRefSegment.Plain("无标记")), splitParaRefs("无标记"))
    }

    @Test
    fun paraRefs_inlineKeepsBoldAndPlaceholders() {
        val s = buildInlineWithRefs("前〔p0〕**粗**")
        assertEquals("前[段1]粗", s.text) // 占位 fallback 文本 [段N]，真实胶囊由 inlineContent 替换
        val bold = s.getStringAnnotations("b", 0, s.length)
        assertEquals(1, bold.size)
    }
}
