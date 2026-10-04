package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 页面清洗（OPT-E）：目录直通、页眉/页码删除、脚注收集、几何统计、跨页续接 */
class PdfCleanerTest {

    private val stats = DocStats(bodySize = 10f, left = 50f, right = 545f, pitchThreshold = 20f)
    private val dim = 595f to 842f

    private fun line(
        text: String,
        x0: Float = 50f,
        x1: Float = 540f,
        y0: Float = 100f,
        size: Float = 10f,
    ) = PLine(text, x0, x1, y0, size)

    // ---------------------------------------------------------------- docStats

    @Test
    fun docStats_bodySize_isCharWeightedSizeMode() {
        // 正文 9.5pt（多字符）+ 页眉 8pt（少量字符）→ 众数应落在 9.5
        val pages = listOf(
            listOf(line("正文行占绝大多数字符", size = 9.5f)) +
                List(5) { line("正文第 $it 行内容足够长", y0 = 120f + it * 16f, size = 9.5f) } +
                line("页眉民法总则", y0 = 30f, size = 8f),
        )
        val s = PdfCleaner.docStats(pages, listOf(dim))
        assertEquals(9.5f, s.bodySize, 0.26f)
    }

    @Test
    fun docStats_pitchValley_setsThresholdBetweenPeakAndValley() {
        // 10 个行距 14 + 2 个段间空隙 25 → 谷点 25，阈值 = min(1.5×14, 25) = 21
        val lines = List(12) { i ->
            line("第 $i 行内容", y0 = 100f + i * (if (i < 10) 14f else 25f))
        }
        val s = PdfCleaner.docStats(listOf(lines), listOf(dim))
        assertEquals(21f, s.pitchThreshold!!, 1.0f)
    }

    @Test
    fun docStats_fewSamplesNoValley_nullThreshold() {
        // 3 个相同行距、无谷点 → 样本 3~7 无信号
        val lines = List(4) { i -> line("第 $i 行内容", y0 = 100f + i * 15f) }
        val s = PdfCleaner.docStats(listOf(lines), listOf(dim))
        assertNull(s.pitchThreshold)
    }

    @Test
    fun docStats_leftRight_restoredAtFullScale_notDoubleHalved() {
        // E2E 实证（双重除 2 bug）：桶键 = x/2，还原必须 ×2。
        // 正文左边距 26（多字符加权）、右缘 354 → left≈26/right≈354，而非 6.5/88.5
        val lines = List(8) { i ->
            line("正文第 $i 行内容足够长一点", x0 = 26f, x1 = 354f, y0 = 100f + i * 16f)
        } + line("缩进段首行", x0 = 48f, x1 = 300f, y0 = 300f)
        val s = PdfCleaner.docStats(listOf(lines), listOf(dim))
        assertEquals(26f, s.left, 2.0f)
        assertEquals(354f, s.right, 2.0f)
    }

    // ---------------------------------------------------------------- clean

    @Test
    fun clean_removesHeaderByBandAndSize() {
        val page = listOf(
            line("民法总则 · 第一章", y0 = 30f, size = 8f),   // 顶部条带 + 小字号 → 页眉
            line("第一节 民法的法源", y0 = 100f, x1 = 400f),
            line("正文内容继续往下写，讲的是法源问题。", y0 = 116f, x1 = 520f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        val texts = out[0].paras.map { it.text }.joinToString("\n")
        assertFalse(texts.contains("民法总则 · 第一章"))
        assertTrue(texts.contains("民法的法源"))
    }

    @Test
    fun clean_removesPageNumLine_anyPosition() {
        val page = listOf(
            line("正文一段话讲完了。", y0 = 100f, x1 = 500f),
            line("12", y0 = 420f, x0 = 290f, x1 = 305f),
            line("正文又一段话讲完了。", y0 = 500f, x1 = 500f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertFalse(out[0].paras.any { it.text == "12" })
        assertEquals(2, out[0].paras.size)
    }

    @Test
    fun clean_tocPage_passthroughKeepsEntriesAndPageNum() {
        val page = listOf(
            line("第一章 总论", y0 = 100f, x1 = 300f),
            line("第二章 民法的法源………………5", y0 = 130f, x1 = 400f),
            line("第三章 法律行为………………18", y0 = 160f, x1 = 400f),
            line("第四章 代理………………32", y0 = 190f, x1 = 400f),
            line("5", y0 = 220f, x0 = 290f, x1 = 305f), // 孤页码：目录条目触发器，保留
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertTrue(out[0].tocLike)
        assertEquals(0, out[0].lineCount) // PageSelector 据此跳过目录页
        assertEquals(0, out[0].shortLineCount)
        assertTrue(out[0].paras.any { it.text.contains("………………5") })
        assertTrue(out[0].paras.any { it.text == "5" })
    }

    @Test
    fun clean_tocPage_dotsAndPageNumSplitByPdfBox_stillTocLike() {
        // 真书《民法总则》实证：pdfbox 行切分把点线与页码拆成两行（标题行点线结尾、
        // 页码独立成行），RE_TOC_LINE 整页 0 命中 → 原判定 no tocLike 探针空转；
        // 纯点线行 ≥3 判据兜住。行形态照抄 p31 实拍
        val page = listOf(
            line("4", y0 = 60f, x0 = 290f, x1 = 305f),          // 页眉孤页码
            line("民法总则", y0 = 70f, x1 = 320f),               // 页眉书名
            line("第七节", y0 = 100f, x1 = 150f),
            line("消灭时效完成的效力．．．．．．．．．．．．．．．．．．", y0 = 110f, x1 = 400f),
            line("557", y0 = 120f, x0 = 290f, x1 = 305f),        // 条目页码独立行
            line("第二节 代理的要件及法律效果...…...………………...… ...456", y0 = 135f, x1 = 400f),
            line("第十二章权利的行使", y0 = 150f, x1 = 300f),
            line("-—权利行使自由与限制····························", y0 = 160f, x1 = 400f),
            line("563", y0 = 170f, x0 = 290f, x1 = 305f),
            line("索 引", y0 = 200f, x1 = 250f),
            line("..................................................", y0 = 210f, x1 = 400f),
            line("595", y0 = 220f, x0 = 290f, x1 = 305f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertTrue("纯点线行 2 条 + 点线页码分离形态须判为目录页", out[0].tocLike)
        assertTrue(out[0].paras.any { it.text == "557" })
    }

    @Test
    fun clean_bodyPageWithEllipsisLines_notTocLike() {
        // 《民法总则》E2E 实证（p78/p333 正文例题页）：省略号行 ≥3 曾被纯点线判据
        // 误判 tocLike，视觉把正文当目录幻觉出 38 条。收紧后须有「点线+尾页码」同行
        // 命中兜底——正文省略号行尾不带页码，不误判
        val page = listOf(
            line("他说完便不再言语……", y0 = 100f, x1 = 400f),
            line("如此循环往复，无有穷尽…………", y0 = 130f, x1 = 400f),
            line("直至有一天大家都不再提起此事……", y0 = 160f, x1 = 400f),
            line("正文继续讲述民法总则的内容与体系构成。", y0 = 190f, x1 = 400f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertFalse(out[0].tocLike)
    }

    @Test
    fun clean_footerSmallLines_collectedAsFootnotePara() {
        val page = listOf(
            line("正文大段内容讲完了。", y0 = 300f, x1 = 520f),
            line("① 参见王泽鉴《民法总则》第 12 页。", y0 = 700f, x1 = 500f, size = 7f),
            line("② 同上注。", y0 = 716f, x1 = 500f, size = 7f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        val fn = out[0].paras.filter { it.footnote }
        assertEquals(1, fn.size) // 连续脚注行合并成一条脚注段
        assertTrue(fn[0].text.contains("参见王泽鉴"))
        assertTrue(fn[0].text.contains("同上注"))
    }

    @Test
    fun clean_yiPunctuation_normalized_whenNoRealYiText() {
        val borrowed = "" + Char(0xA3AC) // 坏字体把"，"映射成彝文区 ꎬ
        val page = listOf(
            line("第一句说完了${borrowed}第二句也完了${Char(0xA3AE)}", y0 = 100f, x1 = 520f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        val t = out[0].paras.single().text
        assertTrue(t.contains("，"))
        assertFalse(t.contains(Char(0xA3AC)))
    }

    @Test
    fun clean_yiPunctuation_kept_whenRealYiTextPresent() {
        val borrowed = "" + Char(0xA3AC)
        val realYi = "" + Char(0xA000) // 真彝文音节出现 → 不许整页替换
        val page = listOf(line("混合${borrowed}内容${realYi}页", y0 = 100f, x1 = 520f))
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertTrue(out[0].paras.single().text.contains(borrowed))
    }

    @Test
    fun clean_countsPuaInRawText() {
        val page = listOf(line("坏字${Char(0xE000)}页", y0 = 100f, x1 = 300f))
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertEquals(1, out[0].puaCount)
    }

    // ---------------------------------------------------------------- crossPageMerge

    private fun pageOut(
        pageNum: Int,
        paras: List<Para>,
        lastLine: PLine?,
        firstLine: PLine? = null,
        tocLike: Boolean = false,
    ) = PageOut(
        pageNum, tocLike, rawChars = 100, puaCount = 0,
        lineCount = paras.size, shortLineCount = 0,
        paras = paras, firstLine = firstLine ?: paras.firstOrNull()?.let { PLine(it.text, 50f, 300f, 100f, 10f) },
        lastLine = lastLine,
    )

    @Test
    fun crossPageMerge_joinsWhenSentenceOpen_andLineReachesRightEdge() {
        val p1 = pageOut(
            1,
            listOf(Para("上一页末段话说了一半，还在继续说")),
            lastLine = PLine("上一页末段话说了一半，还在继续说", 50f, 540f, 700f, 10f),
        )
        val p2 = pageOut(
            2,
            listOf(Para("下一页开头的接续内容。"), Para("下一段另起。")),
            lastLine = null,
            firstLine = PLine("下一页开头的接续内容。", 50f, 400f, 100f, 10f),
        )
        PdfCleaner.crossPageMerge(listOf(p1, p2), stats)
        assertTrue(p1.paras[0].text.endsWith("接续内容。"))
        assertEquals(1, p2.paras.size) // 首段被并走，只剩第二段
        assertEquals("下一段另起。", p2.paras[0].text)
    }

    @Test
    fun crossPageMerge_indentStartOnNextPage_blocksMerge() {
        val p1 = pageOut(
            1,
            listOf(Para("上一页末段没有句末收尾")),
            lastLine = PLine("上一页末段没有句末收尾", 50f, 540f, 700f, 10f),
        )
        val p2 = pageOut(
            2,
            listOf(Para("下一页是缩进的新段落。")),
            lastLine = null,
            firstLine = PLine("下一页是缩进的新段落。", 70f, 400f, 100f, 10f), // 缩进反证
        )
        PdfCleaner.crossPageMerge(listOf(p1, p2), stats)
        assertEquals(1, p1.paras.size)
        assertEquals(1, p2.paras.size)
        assertTrue(p2.paras[0].text.startsWith("下一页"))
    }

    @Test
    fun crossPageMerge_titleSizeOnNextPage_blocksMerge() {
        val p1 = pageOut(
            1,
            listOf(Para("上一页末段话")),
            lastLine = PLine("上一页末段话", 50f, 540f, 700f, 10f),
        )
        val p2 = pageOut(
            2,
            listOf(Para("第二章 新的一章标题")),
            lastLine = null,
            firstLine = PLine("第二章 新的一章标题", 50f, 400f, 100f, 13f), // 标题字号反证
        )
        PdfCleaner.crossPageMerge(listOf(p1, p2), stats)
        assertTrue(p2.paras[0].text.startsWith("第二章"))
    }

    @Test
    fun crossPageMerge_tocPageOnEitherSide_neverMerges() {
        val toc = pageOut(
            1,
            listOf(Para("第三章 法律行为………………18")),
            lastLine = PLine("第三章 法律行为………………18", 50f, 540f, 700f, 10f),
            tocLike = true,
        )
        val body = pageOut(
            2,
            listOf(Para("正文开头。")),
            lastLine = null,
            firstLine = PLine("正文开头。", 50f, 400f, 100f, 10f),
        )
        PdfCleaner.crossPageMerge(listOf(toc, body), stats)
        assertEquals(1, toc.paras.size)
        assertEquals("正文开头。", body.paras.single().text)
    }

    @Test
    fun crossPageMerge_lastLineShortOfRightEdge_noMerge() {
        val p1 = pageOut(
            1,
            listOf(Para("上一页这段话没有收尾")),
            lastLine = PLine("上一页这段话没有收尾", 50f, 300f, 700f, 10f), // x1 距右边距 245pt
        )
        val p2 = pageOut(
            2,
            listOf(Para("下一页开头。")),
            lastLine = null,
            firstLine = PLine("下一页开头。", 50f, 400f, 100f, 10f),
        )
        PdfCleaner.crossPageMerge(listOf(p1, p2), stats)
        assertEquals("下一页开头。", p2.paras.single().text)
        assertEquals(1, p1.paras.size)
    }

    @Test
    fun clean_removesHeaderSameSizeWithChapNameAndTrailingPageNum() {
        // E2E 实证（《民法总则》偶数页）：页眉字号 pdfbox 报与正文同 → 字号判据漏，
        // 靠"行首章名样 + 行尾页码"文本特征兜底
        val page = listOf(
            line("笫一章私法绪论 7", y0 = 48f, x0 = 260f, x1 = 345f, size = 10f),
            line("正文从这一行开始，讲私法绪论的内容。", y0 = 110f, x1 = 520f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertFalse(out[0].paras.any { it.text.startsWith("笫一章") })
        assertTrue(out[0].paras.single().text.contains("正文从这一行开始"))
    }

    @Test
    fun clean_keepsBodyChapTitleInBandBelow() {
        // 反证：条带下方的真章标题不许被文本特征误删
        val page = listOf(
            line("第一章 私法绪论", y0 = 200f, x0 = 230f, x1 = 360f, size = 12f),
            line("正文内容跟在标题后面。", y0 = 230f, x1 = 520f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertTrue(out[0].paras.any { it.text.startsWith("第一章") })
    }

    @Test
    fun clean_removesEvenPageHeader_numFirstBookNameLast() {
        // E2E 实证（偶数页页眉"236 民法总则"）：行首数字+纯 CJK 结尾、无页码在尾部，
        // headerLikeText 认不出；pdfbox 对部分页报 size=正文，字号判据也漏 → 文本第二路
        val page = listOf(
            line("236 民法总则", y0 = 48f, size = 10f),
            line("正文内容继续往下写，讲的是权利客体问题。", y0 = 110f, x1 = 520f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertFalse(out[0].paras.any { it.text.contains("民法总则") })
        assertEquals(1, out[0].paras.size)
    }

    @Test
    fun clean_keepsBodyLineStartingWithNumber_belowHeaderBand() {
        // 反证：条带下方"3 人以上"起头的正文行不能被数字开头文本路误删
        val page = listOf(
            line("3 人以上共同实施的侵权行为，适用连带责任规定。", y0 = 116f, x1 = 520f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertEquals(1, out[0].paras.size)
    }

    @Test
    fun clean_footerMarkedLine_collectedEvenAtBodySize() {
        // E2E 实证：脚注编号圈码坏映射成＠、pdfbox 报 size=正文 → 字号路漏，
        // 靠"行首脚注符"文本路兜底收进脚注段
        val page = listOf(
            line("正文大段内容讲完了。", y0 = 300f, x1 = 520f),
            line("＠ 参见王泽鉴《民法总则》第 12 页。", y0 = 700f, size = 10f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        val fn = out[0].paras.filter { it.footnote }
        assertEquals(1, fn.size)
        assertTrue(fn[0].text.contains("参见王泽鉴"))
        assertEquals(1, out[0].paras.count { !it.footnote })
    }

    // ---------------------------------------------------------------- P3a 字号证据

    @Test
    fun p3a_clean_tocPassthroughCarriesSize() {
        // 目录页直通段必须携带行字号：锚点行「目录」16pt ≥ bodySize+1.5 才能拿到
        // 〔标题〕前缀、不被 styleAware 字号门槛误拦（P3a 计划案单测 10）
        val page = listOf(
            line("目录", y0 = 100f, x1 = 120f, size = 16f),
            line("第一章 总论", y0 = 130f, x1 = 300f),
            line("第二章 民法的法源………………5", y0 = 160f, x1 = 400f),
            line("第三章 法律行为………………18", y0 = 190f, x1 = 400f),
            line("第四章 代理………………32", y0 = 220f, x1 = 400f),
            line("5", y0 = 250f, x0 = 290f, x1 = 305f),
        )
        val out = PdfCleaner.clean(listOf(page), listOf(dim), stats)
        assertTrue(out[0].tocLike)
        val anchor = out[0].paras.first { it.text == "目录" }
        assertEquals(16f, anchor.size, 0.01f)
    }

    @Test
    fun p3a_clean_crossPageMerge_carriesMaxSize() {
        // 跨页并段产物取两侧 max（防御：305 行标题守卫已拦大字首段，此处锁字号不丢；
        // 两侧字号须 < bodySize×1.15=11.5 否则并段本身被守卫拦下）
        val p1 = pageOut(
            1,
            listOf(Para("上一页末段话说了一半，还在继续说", size = 10f)),
            lastLine = PLine("上一页末段话说了一半，还在继续说", 50f, 540f, 700f, 10f),
        )
        val p2 = pageOut(
            2,
            listOf(Para("下一页开头的接续内容。", size = 10.5f), Para("下一段另起。", size = 10f)),
            lastLine = null,
            firstLine = PLine("下一页开头的接续内容。", 50f, 400f, 100f, 10.5f),
        )
        PdfCleaner.crossPageMerge(listOf(p1, p2), stats)
        assertEquals(1, p2.paras.size)
        assertEquals(10.5f, p1.paras[0].size, 0.01f)
    }

    @Test
    fun crossPageMergeY0Preview_matchesRealMergeAndLeavesInputUntouched() {
        val p1 = pageOut(
            1,
            listOf(Para("上一页末段话说了一半，还在继续说", y0 = 700f)),
            lastLine = PLine("上一页末段话说了一半，还在继续说", 50f, 540f, 700f, 10f),
        )
        val p2 = pageOut(
            2,
            listOf(Para("下一页开头的接续内容。", y0 = 100f), Para("下一段另起。", y0 = 140f)),
            lastLine = null,
            firstLine = PLine("下一页开头的接续内容。", 50f, 400f, 100f, 10f),
        )
        val pages = listOf(p1, p2)
        val preview = PdfCleaner.crossPageMergeY0Preview(pages, stats)
        // 预演不改对象（图页文本零风险的前提）
        assertEquals(2, p2.paras.size)
        assertEquals(listOf(700f), preview.getValue(1))
        assertEquals(listOf(140f), preview.getValue(2))
        // 与真 merge 后的段落 y0 口径逐页一致（同源判定防漂移）
        PdfCleaner.crossPageMerge(pages, stats)
        assertEquals(p1.paras.map { it.y0 }, preview.getValue(1))
        assertEquals(p2.paras.map { it.y0 }, preview.getValue(2))
    }

    @Test
    fun crossPageMergeY0Preview_noMerge_returnsOriginalY0Lists() {
        val p1 = pageOut(1, listOf(Para("第一页完整句。", y0 = 100f)), lastLine = null)
        val p2 = pageOut(2, listOf(Para("第二页独立段。", y0 = 100f)), lastLine = null)
        val preview = PdfCleaner.crossPageMergeY0Preview(listOf(p1, p2), stats)
        assertEquals(listOf(100f), preview.getValue(1))
        assertEquals(listOf(100f), preview.getValue(2))
    }

    @Test
    fun crossPageMergeY0Preview_y0MonotonicNonDecreasing_anchorContract() {
        // 锚定契约（r12-QC3-P2）：FigureCoordMath.anchorFigure 的 KDoc 要求 paraY0s 按 y
        // 递增，preview 是锚定的直接输入——产出口径必须单调非递减，含跨页并段页
        // （页首段被吃掉不影响单调性）也一样。契约在此锁定，防 preview 未来重构不保序
        val p1 = pageOut(
            1,
            listOf(Para("第一页首段。", y0 = 80f), Para("上一页末段话说了一半，还在继续说", y0 = 700f)),
            lastLine = PLine("上一页末段话说了一半，还在继续说", 50f, 540f, 700f, 10f),
        )
        val p2 = pageOut(
            2,
            listOf(
                Para("下一页开头的接续内容。", y0 = 100f),
                Para("中段独立句。", y0 = 300f),
                Para("末段独立句。", y0 = 600f),
            ),
            lastLine = null,
            firstLine = PLine("下一页开头的接续内容。", 50f, 400f, 100f, 10f),
        )
        val preview = PdfCleaner.crossPageMergeY0Preview(listOf(p1, p2), stats)
        preview.forEach { (pageNo, y0s) ->
            assertTrue("p$pageNo y0 单调非递减（锚定契约）", y0s.zipWithNext().all { (a, b) -> a <= b })
        }
        assertEquals(listOf(80f, 700f), preview.getValue(1))
        // 页首段并入上页：p2 预演只剩 2 段，仍单调
        assertEquals(listOf(300f, 600f), preview.getValue(2))
    }
}
