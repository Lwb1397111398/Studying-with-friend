package com.studyfriend.app.data.importer.pdfpipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.importer.PdfLineExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P4 commit B2：PdfFigureExtractor 两阶段行为验证（计划案 §4 J9 全链路单测版 + J8 内联图 + J10 加密）。
 * fixtures 由 .e2e/gen_p4_fixtures.py（pymupdf 1.28）生成，位于 test/resources/p4/。
 * 锚定输入 pageParaY0s 走真实文本管线（PdfLineExtractor→PdfCleaner.clean→crossPageMerge 后
 * 取 Para.y0）——与 commit C 将在 PdfLoader.extract 接线的口径完全一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfFigureExtractorTest {

    private lateinit var context: Context
    private lateinit var staging: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        staging = File(context.cacheDir, "figures_staging_test")
    }

    private fun loadFixture(name: String): PDDocument =
        PDDocument.load(javaClass.getResourceAsStream("/p4/$name")!!.readBytes())

    /** 与 PdfLoader.extract 相同的链路产出 merge 后全量段 y0（commit C 接线口径） */
    private fun buildParaY0s(doc: PDDocument): Map<Int, List<Float>> {
        val pagesLines = (1..doc.numberOfPages).map { page ->
            com.studyfriend.app.data.importer.PdfLineExtractor.extractPage(doc, page)
        }
        val dims = (0 until doc.numberOfPages).map { p ->
            val pd = doc.getPage(p)
            val w = pd.cropBox.width
            val h = pd.cropBox.height
            if (pd.rotation % 180 == 90) h to w else w to h
        }
        val stats = PdfCleaner.docStats(pagesLines, dims)
        val pages = PdfCleaner.clean(pagesLines, dims, stats)
        PdfCleaner.crossPageMerge(pages, stats)
        return pages.associate { it.pageNum to it.paras.map { para -> para.y0 } }
    }

    private fun extract(doc: PDDocument): PdfFigureExtractor.FigureExtractResult =
        PdfFigureExtractor(staging).extract(doc, buildParaY0s(doc))

    private fun md5Of(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** J9：R1/R2/R3 滤除计数、幸存张数、seqNo 唯一、staging 落盘与 md5、锚定单调、SMask/Form/CTM 行为 */
    @Test
    fun extract_figuresFixture_countsFiltersSeqAndFiles() {
        loadFixture("figures_fixture.pdf").use { doc ->
            val result = extract(doc)
            val stats = result.stats
            val figures = result.figures

            // 滤除对账（gen_p4_fixtures.py expected：25 图对象 = 15 幸存 + 6MP 1 + R1×2 + R2×3 + R3×4）
            assertEquals("图对象总数", 25, stats.totalObjects)
            assertEquals("R1 整页底图", 2, stats.droppedR1)
            assertEquals("R2 装饰", 3, stats.droppedR2)
            assertEquals("R3 页眉（xref 型或内容型，pymupdf 同 Pixmap 插 4 页）", 4, stats.droppedR3Xref + stats.droppedR3Content)
            assertEquals("6MP 超大图跳过", 1, stats.erroredOversize)
            // p12 SMask PNG：fork 图像解码器只支持 4 字节/像素位图，灰度蒙版（1 字节/像素）
            // 解码抛 Not implemented——计入 errored（parseNote「因 CMYK/格式问题跳过」覆盖）
            assertEquals("SMask 解码失败计 errored（fork 限制）", 1, stats.errored)
            assertEquals("无权限类失败", 0, stats.erroredPerm)
            assertEquals("幸存落库（15 过滤幸存 - 1 SMask 解码失败）", 14, figures.size)
            assertEquals("超大图不落库", 0, figures.count { it.pageNo == 21 })

            // seqNo 先赋号后解码：SMask 解码失败的幸存者序号被跳过（r9-P0-1 只求唯一防碰撞）
            assertEquals("seqNo 唯一", 14, figures.map { it.seqNo }.distinct().size)
            figures.forEach { f ->
                assertTrue(
                    "命名 ${f.finalName}",
                    Regex("p\\d+_f\\d+\\.(png|jpg)").matches(f.finalName),
                )
                assertTrue("staging 已落盘 ${f.stagingFile.name}", f.stagingFile.exists())
                assertEquals("md5 对账 ${f.stagingFile.name}", f.md5, md5Of(f.stagingFile.readBytes()))
                assertTrue("md5 非空", f.md5.isNotEmpty())
            }
            assertEquals(
                "seqNo 严格递增（跳号允许）",
                figures.map { it.seqNo },
                figures.map { it.seqNo }.sorted(),
            )

            // p4 同页双真图、p5 三图 y 递增 → 锚定序号非降（y0 大的图锚点不下移）
            assertEquals(2, figures.count { it.pageNo == 4 })
            val p5 = figures.filter { it.pageNo == 5 }.sortedBy { it.seqNo }
            assertEquals(3, p5.size)
            assertTrue("y 递增锚定非降", p5[0].ordAfterPara <= p5[1].ordAfterPara && p5[1].ordAfterPara <= p5[2].ordAfterPara)

            // p6 完全重叠双图：两张都保留、seqNo/文件互异、md5 互异（r9-P0-1 场景）
            val p6 = figures.filter { it.pageNo == 6 }.sortedBy { it.seqNo }
            assertEquals("重叠双图都保留", 2, p6.size)
            assertNotEquals(p6[0].seqNo, p6[1].seqNo)
            assertNotEquals("文件互不覆盖", p6[0].finalName, p6[1].finalName)
            assertNotEquals("内容互异", p6[0].md5, p6[1].md5)

            // p12 SMask PNG 不落库（fork 限制见上）。
            // png/jpg 由真机 alpha 像素判定（hasMeaningfulAlpha）；Robolectric ShadowBitmap
            // 像素语义不可信，此处只锁「后缀与 format 字段一致、合法」——真机口径 J2 E2E 复核
            assertTrue(
                figures.all { it.format in setOf("png", "jpg") && it.finalName.endsWith(".${it.format}") },
            )

            // p13 Form 嵌套 12 层可达（(d) 结论的真 PDF 验证）
            assertEquals("Form 嵌套 12 层图幸存", 1, figures.count { it.pageNo == 13 })

            // p19 横版大图、p9 /Rotate 90 页真图均幸存
            assertEquals(1, figures.count { it.pageNo == 19 })
            assertEquals(1, figures.count { it.pageNo == 9 })

            // p20 CTM 90° 旋转：ctmToAffine 捕捉并转正（源 300×200 → 输出 200×300，h>w）
            val p20 = figures.first { it.pageNo == 20 }
            assertTrue(
                "CTM 旋转图应转正（${p20.widthPx}x${p20.heightPx}）",
                p20.heightPx > p20.widthPx,
            )

            // p7 段交界 0.5pt、p8 FOOTNOTE 上方图均幸存且锚定不越界（-1 或页内段数内）
            assertEquals(1, figures.count { it.pageNo == 7 })
            assertEquals(1, figures.count { it.pageNo == 8 })
            figures.forEach { assertTrue("锚定序号合法", it.ordAfterPara >= -1) }

            // p10 无图页（并段素材首页）
            assertEquals(0, figures.count { it.pageNo == 10 })
        }
    }

    /** J8：内联图 BI/ID/EI 计数与页号分布（本版不提取，防 >20% 静默） */
    @Test
    fun extract_inlineFixture_countAndPageDistribution() {
        loadFixture("inline_fixture.pdf").use { doc ->
            val result = extract(doc)
            assertEquals("30 个内联图对象", 30, result.stats.inlineImageCount)
            assertEquals("无 xref 图", 0, result.stats.totalObjects - result.stats.inlineImageCount)
            assertEquals("内联图不提取", 0, result.figures.size)
            assertEquals("页号分布覆盖 2 页", 2, result.stats.biPages.size)
            assertEquals(15, result.stats.biPages[1])
            assertEquals(15, result.stats.biPages[2])
            assertTrue(
                "占比 100% > 20% 触发 ⚠（parseNote 组装用原始数据）",
                result.stats.inlineImageCount > result.stats.totalObjects * 20 / 100,
            )
        }
    }

    /** J10：加密且禁止提取的书入口跳过（零解码、零逐张撞权限） */
    @Test
    fun extract_encryptedFixture_permDeniedSkipAll() {
        loadFixture("encrypted_fixture.pdf").use { doc ->
            assertTrue("fixture 应带加密标志", doc.isEncrypted)
            assertTrue("权限位应禁止提取", !doc.currentAccessPermission.canExtractContent())
            val result = extract(doc)
            assertTrue("permDenied 置位", result.stats.permDenied)
            assertEquals("图零提取", 0, result.figures.size)
            assertEquals("逐张 erroredPerm 不累计（入口即跳过）", 0, result.stats.erroredPerm)
        }
    }

    /** J9 补充：双栏 fixture——页眉 R3 滤 + 窄栏图 R2 滤 + 一张真图幸存 */
    @Test
    fun extract_twoColFixture_headerNarrowAndRealFigure() {
        loadFixture("figures_fixture_two_col.pdf").use { doc ->
            val result = extract(doc)
            assertEquals("页眉 3 页同字节同位", 3, result.stats.droppedR3Xref + result.stats.droppedR3Content)
            assertEquals("窄栏图 50x150 面积 1.5% 走 R2", 1, result.stats.droppedR2)
            assertEquals("唯一真图幸存", 1, result.figures.size)
            assertEquals(3, result.figures[0].pageNo)
        }
    }

    /** Para.y0 数据源链路：组装段 y0=段首行 y0、目录页逐行、脚注段取首条 y0、merge 保留主体段 y0 */
    @Test
    fun paraY0_assemblyMergeAndFootnoteAnchors() {
        fun line(text: String, y: Float, size: Float = 11f, x1: Float = 500f) =
            PLine(text, 60f, x1, y, size)
        val pagesLines = listOf(
            listOf(line("第一章标题", 60f, size = 20f), line("第一段说完了。", 100f, x1 = 300f), line("第二段开始说", 140f)),
            listOf(line("着说完。", 60f), line("脚注一：文献注。", 780f, size = 7f), line("脚注二：注二。", 800f, size = 7f)),
        )
        val dims = listOf(595f to 842f, 595f to 842f)
        // 手写 DocStats：bodySize 11（行 1 size 20 ≥ ×1.15 标题独立段）、行距阈值 30（dy 40 断段）
        val stats = DocStats(bodySize = 11f, left = 60f, right = 500f, pitchThreshold = 30f)
        val pages = PdfCleaner.clean(pagesLines, dims, stats)
        PdfCleaner.crossPageMerge(pages, stats)

        val p1Y0s = pages[0].paras.map { it.y0 }
        assertEquals("页 1 三段 y0 与行对齐", listOf(60f, 100f, 140f), p1Y0s)
        // 页 2 首段被并入页 1 末段（"第二段开始说"未句末、"着说完。"顶格续行）
        // → 页 1 末段 y0 仍为原段首行 140；页 2 剩标题段? 无标题 → 只剩脚注段
        assertEquals("merge 后主体段 y0 不变", 140f, pages[0].paras.last().y0)
        assertEquals("页 2 剩脚注段", 1, pages[1].paras.size)
        assertEquals("脚注段 y0=首条脚注行", 780f, pages[1].paras[0].y0)
        assertTrue(pages[1].paras[0].footnote)

        // 锚定走查：页 2 图顶 y=770（脚注上方）→ 锚 -1（脚注 y0=780 在图下方）
        assertEquals(-1, FigureCoordMath.anchorFigure(770f, pages[1].paras.map { it.y0 }))
        // 图顶 y=790 → 锚脚注段 0
        assertEquals(0, FigureCoordMath.anchorFigure(790f, pages[1].paras.map { it.y0 }))
    }

}