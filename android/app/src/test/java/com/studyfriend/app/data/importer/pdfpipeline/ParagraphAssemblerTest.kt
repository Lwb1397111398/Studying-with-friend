package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 段落组装规则（OPT-E）：字号/行距/缩进/句末四类信号决定断段还是续接 */
class ParagraphAssemblerTest {

    private val stats = DocStats(bodySize = 10f, left = 50f, right = 545f, pitchThreshold = null)

    private fun line(
        text: String,
        x0: Float = 50f,
        x1: Float = 540f,
        y0: Float = 0f,
        size: Float = 10f,
    ) = PLine(text, x0, x1, y0, size)

    /** 连续正文行：等行距、满宽 */
    private fun bodyLines(vararg texts: String, startY: Float = 100f, dy: Float = 14f) =
        texts.mapIndexed { i, t -> line(t, y0 = startY + i * dy) }

    @Test
    fun continuousLines_mergeIntoOneParagraph() {
        val lines = bodyLines("第一行内容收尾。", "第二行接着说。", "第三行说完了。")
        val s = stats.copy(pitchThreshold = 20f) // dy=14 < 20 → 续接
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(1, paras.size)
        assertTrue(paras[0].text.contains("第一行") && paras[0].text.contains("第三行"))
    }

    @Test
    fun leadingPunctuation_mergesBackIntoPreviousParagraph() {
        val lines = bodyLines("他说了很多话", "，但是都没说完。", "然后继续。")
        // pitch=20 让"然后继续。"走行距续接，只验证禁则标点并回本身
        val paras = ParagraphAssembler.assemble(lines, stats.copy(pitchThreshold = 20f))
        assertEquals("他说了很多话，但是都没说完。然后继续。", paras.single().text)
    }

    @Test
    fun fontSizeJump_titleIsolatedAsOwnParagraph() {
        val lines = bodyLines("正文第一句讲完了。", "正文第二句也讲完了。") +
            line("第二章 民法的法源", size = 13f, y0 = 200f) +
            bodyLines("标题后的正文开始了。", startY = 230f)
        val s = stats.copy(pitchThreshold = 20f)
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(3, paras.size)
        assertEquals("第二章 民法的法源", paras[1].text)
        assertTrue(paras[2].text.startsWith("标题后"))
    }

    @Test
    fun twoTitleLines_closeTogether_foldIntoOneTitle() {
        val lines = bodyLines("正文收尾句。") +
            line("第二章 民法的法源及", size = 13f, y0 = 130f) +
            line("法律的适用", size = 13f, y0 = 156f) // dy=26 ≤ 2.2×13=28.6 → 折行合并
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
        assertEquals("第二章 民法的法源及法律的适用", paras[1].text)
    }

    @Test
    fun lineGapBeyondPitch_breaksParagraph() {
        // 上一行用短行（真实段落末行几乎必然不满；满行会触发 P2 满行必接续接）
        val lines = listOf(line("上一段说完了。", x1 = 200f, y0 = 100f)) +
            bodyLines("下一段开始。", startY = 140f) // dy=40 > 20
        val s = stats.copy(pitchThreshold = 20f)
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(2, paras.size)
    }

    @Test
    fun lineGapBeyondPitch_openSentenceTopAligned_keepsMerging() {
        // E2E 实证：句中说一半的顶格续行遇行距抖动 → 不许行距断段（守卫）
        val lines = bodyLines("一段话还没有说完，", startY = 100f) +
            bodyLines("后面继续说。", startY = 140f) // dy=40 > 20，但上句未完且顶格
        val s = stats.copy(pitchThreshold = 20f)
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(1, paras.size)
    }

    @Test
    fun lineGapBeyondPitch_openSentenceIndented_breaks() {
        // 上句未完但本行缩进起新段（段中缩进强调句，罕见但存在）→ 缩进强信号仍断。
        // P6c-F 补一个前提：上一行必须是**不满行**（满行=该句被排版折断，缩进不足以立新段，
        // 见 lineGapFullPrev_openSentenceIndented_joins）
        val lines = listOf(line("一段话还没有说完，", x1 = 300f, y0 = 100f)) +
            line("缩进的新段说。", x0 = 70f, y0 = 140f) // 70 ≥ 58 缩进
        val s = stats.copy(pitchThreshold = 20f)
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(2, paras.size)
    }

    @Test
    fun lineGapFullPrev_openSentenceIndented_joins() {
        // P6c-F 扫描书引文块实录（shpc p36）：满行「…应自解释公布之日起,至迟于届满」+
        // 缩进短行「一年时失其效力。」是同一句被排版折断，行距 38.7>阈值 38 也不许断
        val lines = listOf(line("应自解释公布之日起,至迟于届满", y0 = 100f)) + // x1=540=right 满行
            line("一年时失其效力。", x0 = 70f, y0 = 140f)
        val paras = ParagraphAssembler.assemble(lines, stats.copy(pitchThreshold = 20f))
        assertEquals(1, paras.size)
        assertEquals("应自解释公布之日起,至迟于届满一年时失其效力。", paras[0].text)
    }

    @Test
    fun `ocrHeadingText_breaksOwnParagraph_evenAtBodyFontSize`() {
        // P6c-F 标题文本形态路：影印书小节标题与正文框高相同（字号路 1.4× 认不出），
        // 靠「第X节/款」+ ≤24 字 + 无句末标点独立成段（实录被并进上段的「第三节损害赔偿制度」）
        val ocr = TextSourceRow.PROD_OCR_V1
        val lines = listOf(
            PLine("构成一个包括预防管制及救济的规范体系", 50f, 540f, 100f, 10f, ocr),
            PLine("第三节损害赔偿制度", 90f, 300f, 140f, 10f, ocr),
            PLine("须特别提出的是,私法亦具有保障人民安全的重要功能。", 50f, 540f, 180f, 10f, ocr),
        )
        val paras = ParagraphAssembler.assemble(lines, stats.copy(pitchThreshold = 200f))
        assertEquals(3, paras.size) // 正文段 / 标题段 / 正文段
        assertEquals("第三节损害赔偿制度", paras[1].text)
    }

    @Test
    fun `ocrTitleFold_wrappedChapterTitle_joinsIntoOneTitle`() {
        // P6c-F 章题折行（shpc p31）：大标题两行 dy=58.8pt=3.1×字号，旧 2.2× 判不成折行，
        // 后半截掉进正文、章名被截成「第一章风险社会保护国家与」
        val ocr = TextSourceRow.PROD_OCR_V1
        val lines = listOf(
            PLine("第一章风险社会、保护国家与", 100f, 700f, 331f, 19f, ocr),
            PLine("损害赔偿制度", 263f, 641f, 390f, 17.8f, ocr),
            PLine("第一节风险社会与保护国家", 138f, 743f, 549f, 12.5f, ocr),
        )
        val paras = ParagraphAssembler.assemble(lines, stats.copy(bodySize = 10.5f))
        assertEquals(2, paras.size)
        assertEquals("第一章风险社会、保护国家与损害赔偿制度", paras[0].text)
    }

    @Test
    fun `endsSentence_halfWidthColonAndGluedFootnoteDigit_countAsSentenceEnd`() {
        // P6c-F：规则 a 把 ：； 转半角后句末判据须认半角（否则该断的段不断）；
        // 脚注上标被认成正文数字粘在行尾（'…保障人民安全2'）同理
        assertTrue(endsSentence("兹举两个解释,以供参照:"))
        assertTrue(endsSentence("以防治犯罪保障人民安全2"))
        assertTrue(endsSentence("分六项简述如下；"))
        assertFalse(endsSentence("共计30"))      // 数字串 2 位且前非 CJK：不剔
        assertFalse(endsSentence("工厂不依照规定申请设立登记,")) // 逗号不是句末
    }

    @Test
    fun firstLineIndent_breaksParagraph_atSentenceEnd() {
        val lines = bodyLines("上一段说完了。") +
            line("新的一段缩进起步，说完了。", x0 = 70f, y0 = 114f) // 70 ≥ 50+0.8×10=58
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
    }

    @Test
    fun indent_withoutSentenceEnd_keepsMerging() {
        val lines = bodyLines("上一段话还没有说完，") +
            line("缩进但上句未完，继续写。", x0 = 70f, y0 = 114f)
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(1, paras.size)
    }

    @Test
    fun shortLine_breaksParagraph_atSentenceEnd() {
        val lines = listOf(line("这一段收尾在短行。", x1 = 200f)) +
            line("新段从这里开始。", y0 = 114f) // 200 < 545−15 且上句句末
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
    }

    @Test
    fun noPitchSignal_sentenceEndAloneBreaksParagraph() {
        // pitchThreshold=null 兜底臂：中宽行（不满行、不近满、不短行：[530,535) 区间）句末即断
        // 满行+句末+顶格续行已被 P2 满行必接接走（见 fullLine... 用例）
        val lines = listOf(line("上一段说完了。", x1 = 532f, y0 = 100f)) +
            bodyLines("新一段紧接着排版。", startY = 114f)
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
    }

    @Test
    fun endsSentence_variousClosings() {
        assertTrue(endsSentence("他说完了。"))
        assertTrue(endsSentence("他说完了？"))
        assertTrue(endsSentence("他引用道："))
        assertTrue(endsSentence("他说完！”"))
        assertTrue(endsSentence("参见第十二条〔3〕"))
        assertTrue(endsSentence("他主张[12]"))
        assertTrue(endsSentence("第一条　原则①"))
        assertFalse(endsSentence("话还没说完，"))
        assertFalse(endsSentence("王泽鉴说"))
        assertFalse(endsSentence(""))
    }

    @Test
    fun softJoin_cjkDirect_asciiSpaced() {
        assertEquals("中文直连", softJoin("中文", "直连"))
        assertEquals("has space", softJoin("has", "space"))
        assertEquals("混合 text", softJoin("混合", "text"))
    }

    @Test
    fun joinTexts_foldsListWithSoftJoin() {
        assertEquals("甲、乙、丙", joinTexts(listOf("甲、", "乙、", "丙")))
        assertEquals("a b", joinTexts(listOf("a", "b")))
    }

    // ===== OPT-G P2：满行必接 / 近满行接 / 深缩进标签块 =====
    // 几何参数（bodySize=10, left=50, right=545）：满行线 540、近满线 535、短行线 530、
    // 普通缩进线 58、深缩进线 66

    @Test
    fun p2_fullLine_sentenceEnd_topAlignedNextLine_joins() {
        // 核心场景：段落中句号落在满行上 + 顶格续行 → 未完，续接（原兜底臂会误断）
        val lines = bodyLines("正文在满行处讲完了一句。", "下文继续展开论述内容。")
        val paras = ParagraphAssembler.assemble(lines, stats) // pitch null
        assertEquals(1, paras.size)
    }

    @Test
    fun p2_fullLine_sentenceEnd_indentedNextLine_breaks() {
        // 守卫：本行缩进（x0=62 ∈ [58,66) 普通缩进、长行）= 新段意图 → 满行必接让位
        val lines = bodyLines("上一段在满行处讲完了。") +
            line("缩进的新段说。", x0 = 62f, y0 = 114f)
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
    }

    @Test
    fun p2_fullLine_sentenceEnd_numberedShortLine_breaks() {
        // 强新段短路守卫：句末+「2.」序号起头 → 满行必接让位，落短行断段臂
        val lines = bodyLines("上一段在满行处讲完了。") +
            line("2. 下一项内容", y0 = 114f, x1 = 200f)
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
    }

    @Test
    fun p2_fullLine_sentenceEnd_cjkNumberedShortLine_breaks() {
        // 强新段短路守卫：「一、」汉字序号形态，同上
        val lines = bodyLines("第一章到此结束了。") +
            line("一、总则概述", y0 = 114f, x1 = 200f)
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
    }

    @Test
    fun p2_nearFullLine_sentenceEnd_topAligned_joins() {
        // 近满行接：x1=538 ≥ 535 且句末+顶格续行 → 段中句号续接
        val lines = listOf(line("近满行的句号说完了。", x1 = 538f, y0 = 100f)) +
            bodyLines("下文继续展开论述内容。", startY = 114f)
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(1, paras.size)
    }

    @Test
    fun p2_nearFullLine_sentenceEnd_indented_breaks() {
        val lines = listOf(line("近满行的句号说完了。", x1 = 538f, y0 = 100f)) +
            line("缩进的新段说。", x0 = 70f, y0 = 114f)
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
    }

    @Test
    fun p2_deepIndentedShortLine_ownParagraph_evenWhenSentenceOpen() {
        // 跨规则交互：上一行满行且**句中**（无断段信号）+ 本行深缩进短行 → 标签块臂独立断段
        // （规则 6/7 都要求上句末；深缩进臂插在满行必接之前，不被续接臂吞掉）
        ParagraphAssembler.deepIndentBlockEnabled = true
        try {
            val lines = bodyLines("一段话还没有说完，") +
                line("（一）标签项内容", x0 = 80f, x1 = 200f, y0 = 114f)
            val paras = ParagraphAssembler.assemble(lines, stats)
            assertEquals(2, paras.size)
        } finally {
            ParagraphAssembler.deepIndentBlockEnabled = false
        }
    }

    @Test
    fun p2_normalIndentShortLine_notDeepIndentBlock_keepsMerging() {
        // 深缩进边界：x0=62 < 66 不算标签块；上句未完+缩进 → 无断段臂触发 → 续接
        // （若深缩进线误设 0.8×，本用例会误断 → 抓边界回归）
        val lines = bodyLines("一段话还没有说完，") +
            line("缩进的短行内容", x0 = 62f, x1 = 200f, y0 = 114f)
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(1, paras.size)
    }

    @Test
    fun p2_fullLineBeforeTitle_stillIsolated() {
        // 优先级不回归：满行+句末+下一行是标题 → 标题臂先命中，标题仍独立
        val lines = bodyLines("正文内容说完了。") +
            line("第二章 民法的法源", size = 13f, y0 = 114f)
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
        assertEquals("第二章 民法的法源", paras[1].text)
    }

    @Test
    fun p2_twoDeepIndentedShortLines_separateParagraphs() {
        // 连续深缩进短行各自独立成段（flush 语义覆盖，不合并）
        ParagraphAssembler.deepIndentBlockEnabled = true
        try {
            val lines = bodyLines("正文收尾说完了。") +
                line("一、第一项标签", x0 = 80f, x1 = 200f, y0 = 114f) +
                line("二、第二项标签", x0 = 80f, x1 = 200f, y0 = 128f)
            val paras = ParagraphAssembler.assemble(lines, stats)
            assertEquals(3, paras.size)
            assertEquals("一、第一项标签", paras[1].text)
            assertEquals("二、第二项标签", paras[2].text)
        } finally {
            ParagraphAssembler.deepIndentBlockEnabled = false
        }
    }

    // 注：fullLineJoinEnabled 回退开关不设单测——满行臂关闭后「满行+句末」场景由近满行接
    // （条件仅多句末判定，x1≥540 必然 ≥535）兜住、「满行+句中」由默认续接臂兜住，
    // 行为无可见差异；开关仅为部署期最后保险（真书验收不达标且调阈值无效时使用）。

    @Test
    fun p2_boundary_nearFullLine_atExactly535_joins() {
        // 近满线下边界：x1=535 恰好命中（≥ 语义），续接
        val lines = listOf(line("边界行恰好近满。", x1 = 535f, y0 = 100f)) +
            bodyLines("下文继续展开论述内容。", startY = 114f)
        assertEquals(1, ParagraphAssembler.assemble(lines, stats).size)
    }

    @Test
    fun p2_boundary_belowNearFullLine_534_breaksAtFallback() {
        // 近满线下界之下：x1=534 < 535 不续接，且不短行（≥530）→ 落兜底臂句末即断
        val lines = listOf(line("边界行差一点近满。", x1 = 534f, y0 = 100f)) +
            bodyLines("下一段从这里开始。", startY = 114f)
        assertEquals(2, ParagraphAssembler.assemble(lines, stats).size)
    }

    @Test
    fun p2_boundary_deepIndent_atExactly66_ownParagraph() {
        // 深缩进线下边界：x0=66 恰好命中（≥ 语义），标签块独立
        ParagraphAssembler.deepIndentBlockEnabled = true
        try {
            val lines = bodyLines("正文收尾说完了。") +
                line("标签项内容", x0 = 66f, x1 = 200f, y0 = 114f)
            assertEquals(2, ParagraphAssembler.assemble(lines, stats).size)
        } finally {
            ParagraphAssembler.deepIndentBlockEnabled = false
        }
    }

    @Test
    fun p2_deepIndent_disabledByDefault_keepsMerging() {
        // 默认关闭：真书排版下深缩进+短行占 10% 行会拆出碎片段，默认不触发——
        // 深缩进短行落入兜底臂（上句末 → 断），此处上句句中 → 续接
        val lines = bodyLines("一段话还没有说完，") +
            line("（一）标签项内容", x0 = 80f, x1 = 200f, y0 = 114f)
        assertEquals(1, ParagraphAssembler.assemble(lines, stats).size)
    }

    @Test
    fun p2_boundary_topAligned_atExactly58_breaksAtIndentArm() {
        // topAligned 上边界：x0=58 恰好不算顶格（< 语义）→ 满行必接不命中，
        // 走缩进断段臂（若误写成 ≤ 则被满行必接接走 → 1 段，本测试抓到）
        val lines = bodyLines("上一段在满行处讲完了。") +
            line("缩进新段说完了。", x0 = 58f, y0 = 114f)
        assertEquals(2, ParagraphAssembler.assemble(lines, stats).size)
    }

    @Test
    fun p2_nearFullLine_numberedShortLine_breaks() {
        // 对称守卫：近满行（非满行）+句末+序号起头短行 → 近满行接让位，落短行断段臂
        val lines = listOf(line("近满行的句号说完了。", x1 = 538f, y0 = 100f)) +
            line("3. 下一项内容", y0 = 114f, x1 = 200f)
        assertEquals(2, ParagraphAssembler.assemble(lines, stats).size)
    }

    // ---------------------------------------------------------------- P3a 字号证据

    @Test
    fun p3a_multiTitleLines_foldKeepsMaxSize() {
        // 两行大字标题折行合并成段：段 size = 标题字号（P3a 前缀打标依据）
        val lines = bodyLines("正文收尾句。") +
            line("第一章 私法绪论及", size = 19f, y0 = 130f) +
            line("权利体系", size = 19f, y0 = 156f) // dy=26 ≤ 2.2×19=41.8 → 折行合并
        val paras = ParagraphAssembler.assemble(lines, stats)
        assertEquals(2, paras.size)
        assertEquals("第一章 私法绪论及权利体系", paras[1].text)
        assertEquals(19f, paras[1].size, 0.01f)
    }

    @Test
    fun p3a_titleFollowedByBody_sizesCarried() {
        // 字号跳变断段：标题段与正文段各自携带真实字号（对抗误接丢证据的显式断言）
        val lines = bodyLines("正文第一句讲完了。", "正文第二句也讲完了。") +
            line("第二章 民法的法源", size = 19f, y0 = 200f) +
            bodyLines("标题后的正文开始了。", startY = 230f)
        val s = stats.copy(pitchThreshold = 20f)
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(3, paras.size)
        assertEquals(19f, paras[1].size, 0.01f)
        assertEquals(10f, paras[2].size, 0.01f)
    }

    // ---------------------------------------------------------------- P6c-D OCR 标题容差

    private fun ocrLine(
        text: String,
        x0: Float = 50f,
        x1: Float = 540f,
        y0: Float = 0f,
        size: Float = 10f,
    ) = PLine(text, x0, x1, y0, size, sourceVersion = TextSourceRow.PROD_OCR_V1)

    @Test
    fun ocr_noiseHeightBodyLine_notTitle_stillJoined() {
        // P6c-D 断段主修复：OCR 行字号=框高×0.68 噪声 std≈3pt（P6a 实测），1.15×
        // （≈1.6pt）容差被击穿 → 正文行误判标题连环切（DB 实录「国家存⟂在的意义」）。
        // size=1.25×body 的 OCR 行不再判标题 → 连续 OCR 正文行续接成段
        val lines = listOf(
            ocrLine("处在一个风险社会人民最需要的是安全保障人民安全系国家存", y0 = 100f),
            ocrLine("在的意义及目的此不仅是政治哲学的理念更是宪法上的国家义务", y0 = 114f),
            ocrLine("宪法的任务在干保障人民的基本权利尤其是人身自由与生存权", y0 = 128f),
        )
        val s = stats.copy(pitchThreshold = 20f)
        val paras = ParagraphAssembler.assemble(
            lines.map { it.copy(size = 12.5f) }, // 1.25×bodySize：旧口径必误判标题
            s,
        )
        assertEquals("OCR 行高噪声 1.25×body 须续接成一段（不误判标题）", 1, paras.size)
    }

    @Test
    fun ocr_realChapterTitle_stillSplit() {
        // 1.4× 容差保留真章标题识别：影印书章标题 ≥1.5× 正文
        val lines = listOf(
            ocrLine("正文第一段讲完了。", y0 = 100f),
            ocrLine("第二章 损害赔偿法的规范体系", size = 15f, y0 = 130f), // 1.5×body
            ocrLine("标题后的正文开始了。", y0 = 160f),
        )
        val s = stats.copy(pitchThreshold = 20f)
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(3, paras.size)
        assertEquals("第二章 损害赔偿法的规范体系", paras[1].text)
    }

    @Test
    fun digital_noiseHeightBodyLine_stillTitle_locked() {
        // 数字路径行为零改动锁定：size=1.25×body 的数字行仍按旧 1.15× 口径判标题
        val lines = listOf(
            line("正文第一段讲完了。", y0 = 100f),
            line("第二章 民法的法源", size = 12.5f, y0 = 130f),
            line("标题后的正文开始了。", y0 = 160f),
        )
        val s = stats.copy(pitchThreshold = 20f)
        val paras = ParagraphAssembler.assemble(lines, s)
        assertEquals(3, paras.size)
        assertEquals("第二章 民法的法源", paras[1].text)
    }
}
