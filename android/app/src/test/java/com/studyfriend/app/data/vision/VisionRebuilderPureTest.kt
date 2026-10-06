package com.studyfriend.app.data.vision

import com.studyfriend.app.data.importer.PdfExtractResult
import com.studyfriend.app.data.importer.pdfpipeline.DocStats
import com.studyfriend.app.data.importer.pdfpipeline.PageOut
import com.studyfriend.app.data.importer.pdfpipeline.Para
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重建纯函数（P6c C3a 立案修复）：目录页推断 + 覆盖守卫 + 组装路径格式锁定 */
class VisionRebuilderPureTest {

    @Test
    fun tocLike_sampleVisionToc_inferred() {
        val toc = listOf(
            "目 录", "第一章 担保法概述 4", "第二章 保证担保 6", "第三章 抵押权 8",
            "第四章 质权 10", "第五章 留置权 12", "第六章 定金 14",
            "第七章 非典型担保 16", "第八章 担保物权的实现程序 18",
        )
        assertTrue(VisionRebuilder.inferTocLike(toc))
    }

    @Test
    fun tocLike_dotLeaderEntries_inferred() {
        // 真书目录形态：点线被视觉转写保留时同样要认得
        assertTrue(
            VisionRebuilder.inferTocLike(
                listOf("目 录", "第一章 概述…………12", "第二章 保证…………34", "第三章 抵押…………56"),
            ),
        )
    }

    @Test
    fun tocLike_bodyParagraphs_notInferred() {
        val content = listOf(
            "担保法制度讲义",
            "当事人在订立抵押合同时,应当对抵押财产的权属状况进行审慎核查。",
            "担保物权的设立以主债权的有效存在为前提,主债权无效时担保合同亦不能发生效力。",
        )
        assertFalse(VisionRebuilder.inferTocLike(content))
    }

    @Test
    fun tocLike_belowThreshold_notInferred() {
        assertFalse(VisionRebuilder.inferTocLike(listOf("第一章 担保法概述 4", "第二章 保证担保 6")))
    }

    @Test
    fun tocLike_legalArticleNumbers_notInferred() {
        // 法条「第十条」不在 [章节回] 内，正文密集引法条不误判
        val content = listOf(
            "第十条 当事人订立合同后应当遵循诚信原则。",
            "第十一条 其他法律对民事关系另有规定的依照其规定。",
            "第十二条 民事活动的当事人应当遵循自愿原则。",
        )
        assertFalse(VisionRebuilder.inferTocLike(content))
    }

    @Test
    fun tocLike_emptyAndSingle_notInferred() {
        assertFalse(VisionRebuilder.inferTocLike(emptyList()))
        assertFalse(VisionRebuilder.inferTocLike(listOf("第一章 担保法概述 4")))
    }

    @Test
    fun coverage_c3aWipeShape_blocked() {
        // C3a 实录形态：现书 14772 字、产物 89 字 → 必须拦下
        assertFalse(VisionRebuilder.coverageOk(89, 14772))
    }

    @Test
    fun coverage_fullReplacement_equalChars_passes() {
        assertTrue(VisionRebuilder.coverageOk(14772, 14772))
    }

    @Test
    fun coverage_boundary_exactly80_passes_below80_blocks() {
        assertTrue(VisionRebuilder.coverageOk(80, 100))
        assertFalse(VisionRebuilder.coverageOk(79, 100))
    }

    @Test
    fun coverage_emptyBook_passes() {
        assertTrue(VisionRebuilder.coverageOk(0, 0))
        assertTrue(VisionRebuilder.coverageOk(100, 0))
    }

    @Test
    fun assemble_viaPdfExtractResult_formatLocked() {
        // 重建组装路径（PageOut+DocStats(NaN,0,0,null)+alreadyMerged=true）输出格式
        // 与 assembleText 本尊逐字节一致：〔页N〕页标、〔脚注〕前缀、目录页单 \n 连块、
        // 普通页 \n\n 分块、空页跳过（尾随 \n\n 为 assembleText 对空页的原生行为，
        // BookParser splitBlocks 已滤空块）；同时实证 DocStats NaN/null 参数安全
        // （评审第 1 轮意见 2）
        val pages = listOf(
            PageOut(1, true, 20, 0, 2, 0,
                mutableListOf(Para("目 录"), Para("第一章 担保法概述 4")), null, null),
            PageOut(2, false, 40, 0, 2, 0,
                mutableListOf(Para("正文第一段，讲担保物权。"), Para("脚注内容", footnote = true)), null, null),
            PageOut(3, false, 0, 0, 0, 0, mutableListOf(), null, null),
        )
        val text = PdfExtractResult(
            pages = pages, stats = DocStats(Float.NaN, 0f, 0f, null),
            scanned = false, alreadyMerged = true,
        ).assembleText()
        assertEquals(
            "〔页1〕\n目 录\n第一章 担保法概述 4\n\n" +
                "〔页2〕\n正文第一段，讲担保物权。\n\n〔脚注〕脚注内容\n\n",
            text,
        )
    }
}
