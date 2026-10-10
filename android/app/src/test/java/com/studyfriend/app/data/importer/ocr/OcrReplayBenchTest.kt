package com.studyfriend.app.data.importer.ocr

import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.importer.BookParser
import com.studyfriend.app.data.importer.ParsedChapter
import com.studyfriend.app.data.importer.PdfLoader
import com.studyfriend.app.data.importer.PdfPageRenderer
import com.studyfriend.app.data.importer.pdfpipeline.DocStats
import com.studyfriend.app.data.importer.pdfpipeline.endsSentence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 扫描书 OCR **离线回放台**（P6c-F）：拿真书逐行几何数据（文本+框+置信度）在 JVM 里
 * 重跑生产整链——Pass1 像素→pt + 规则 a/b → [OcrImportRunner.pass2]（bodySize/规则 c/d/
 * 页级分流）→ [PdfLoader.finalizeOcr]（docStats/clean/跨页预演）→ assembleText →
 * [BookParser.parse]——并输出段落质量指标，用于「一段话被拆成多段」「目录识别出错」
 * 这类结构问题的快速归因与回归。
 *
 * 为什么需要它：改动段落组装规则后，真机验证要重装 APK → 导入 60 页 → 拉库取证，
 * 一轮数十分钟；本回放台一轮数秒，且跑的是生产函数本身（Pass2 单源，见 [OcrImportRunner.pass2]
 * 的 KDoc），不存在「实验台规则与生产规则漂移」。
 *
 * fixture 生成（PC 复刻实验台，与手机 PpOcrEngine 逐位同口径）：
 * ```
 * .e2e/p6a/venv/Scripts/python .e2e/p6c/replica_ocr.py --pdf .e2e/shpc_e2e_toc.pdf \
 *     --pages 1,...,60 --dpi 140 --det-limit 960 --unclip off --rec-width dyn --geom on \
 *     --out .e2e/p6c/replay_shpc60.json   # 再把 pages[].geom/dims_pt 拷进本 fixture
 * ```
 * fixture 缺失时本测试 skip（assumeTrue），不进常规套件断言。
 * 报告落 `android/app/build/replay_shpc60.txt`（build 目录不入库），逐段全文供人工判读。
 */
class OcrReplayBenchTest {

    private data class Fixture(
        val pagesOcrLines: List<List<OcrLine>>,
        val dims: List<Pair<Float, Float>>,
    )

    private fun load(): Fixture? {
        val stream = javaClass.getResourceAsStream(FIXTURE) ?: return null
        val json = stream.bufferedReader(Charsets.UTF_8)
            .use { Json.parseToJsonElement(it.readText()) }
            .jsonObject
        val pages = json["pages"]!!.jsonArray.map { it.jsonObject }
        require(pages.all { it.containsKey("geom") }) {
            "fixture 无 geom 段：用 replica_ocr.py --geom on 重新生成"
        }
        // Pass1 同款：像素 → pt（×72/dpi），再规则 a（标点归一）→ 规则 b（引注隐去）
        val s = 72f / PdfPageRenderer.DPI
        val linesPt = pages.map { p ->
            p["geom"]!!.jsonArray.map { g ->
                val o = g.jsonObject
                OcrLine(
                    text = o["t"]!!.jsonPrimitive.content,
                    x0 = o["x0"]!!.jsonPrimitive.content.toFloat() * s,
                    y0 = o["y0"]!!.jsonPrimitive.content.toFloat() * s,
                    x1 = o["x1"]!!.jsonPrimitive.content.toFloat() * s,
                    y1 = o["y1"]!!.jsonPrimitive.content.toFloat() * s,
                    confidence = o["c"]!!.jsonPrimitive.content.toFloat(),
                )
            }
        }
        return Fixture(
            pagesOcrLines = linesPt.map {
                OcrTextPostProcessor.stripCitations(OcrTextPostProcessor.normalizePunct(it))
            },
            dims = pages.map {
                val d = it["dims_pt"]!!.jsonArray
                d[0].jsonPrimitive.content.toFloat() to d[1].jsonPrimitive.content.toFloat()
            },
        )
    }

    @Test
    fun replay_runsProductionChainAndReportsQuality() {
        val f = load()
        assumeTrue("离线回放 fixture 缺失（$FIXTURE）：跳过。生成方法见类 KDoc", f != null)
        requireNotNull(f)

        val p2 = OcrImportRunner.pass2(f.pagesOcrLines, f.dims)
        val outcome = OcrImportRunner.Outcome(
            pagesLines = p2.pagesLines,
            dims = f.dims,
            fallbackPages = p2.fallbacks,
            pageMeanConfs = p2.pageMeanConfs,
            pageLineConfs = p2.pageLineConfs,
            pageDrafts = p2.pageDrafts,
        )

        // 同轮 A/B 阶梯：两个新判据各开一次，归因到规则本身。
        // 注意 S0 也不是「原始线上版」——句末判据补半角 : ; 与行尾脚注数字、行距臂的
        // 「上一行满行」前提、中西文拼接补白四处修正无开关，四档里恒为开（它们是判据修正，
        // 不是可选路径）。原始线上基线读手机 DB：BODY 段长中位 157、非句末收尾 55%、
        // role=TOC 94 段（.e2e/db_e2e_toc3.db bookId=9，60 页样本）。
        val ladder = listOf(
            "S0 标题路关/脚注路关" to (false to false),
            "S1 仅标题形态路" to (true to false),
            "S2 仅脚注形态路" to (false to true),
            "S3 两路全开(本次交付)" to (true to true),
        )
        val reports = LinkedHashMap<String, String>()
        try {
            ladder.forEach { (name, sw) ->
                reports[name] = runOnce(f, p2, outcome, headingRoute = sw.first, footnoteRoute = sw.second)
            }
        } finally {
            com.studyfriend.app.data.importer.pdfpipeline.PdfCleaner.ocrFootnoteLeadEnabled = true
            com.studyfriend.app.data.importer.pdfpipeline.ParagraphAssembler.headingTextRouteEnabled = true
        }
        val sb = StringBuilder("== A/B 阶梯（同一 fixture、同一 Pass2，只差两个新判据开关）==\n")
        reports.forEach { (k, v) -> sb.appendLine("----- $k -----").appendLine(headline(v)) }
        reports.forEach { (k, v) -> sb.appendLine("\n\n===== $k 全量报告 =====").appendLine(v) }
        val report = sb.toString()
        println(report)
        File("build/replay_shpc60.txt").apply { parentFile?.mkdirs() }.writeText(report)

        org.junit.Assert.assertEquals("fixture 页数与页尺寸数须一致", f.pagesOcrLines.size, f.dims.size)
    }

    /** 跑一遍整链并出报告 */
    private fun runOnce(
        f: Fixture,
        p2: OcrImportRunner.Pass2,
        outcome: OcrImportRunner.Outcome,
        headingRoute: Boolean,
        footnoteRoute: Boolean,
    ): String {
        com.studyfriend.app.data.importer.pdfpipeline.PdfCleaner.ocrFootnoteLeadEnabled = footnoteRoute
        com.studyfriend.app.data.importer.pdfpipeline.ParagraphAssembler.headingTextRouteEnabled = headingRoute
        val hits = LinkedHashMap<String, Int>()
        val result = PdfLoader.finalizeOcr(outcome, hits)
        val text = result.assembleText(styleAware = true)
        val chapters = BookParser.parse(text, null, true)
        return buildReport(f, p2, result.stats, chapters, hits) +
            "\n== 逐段全文 ==\n" + chapters.joinToString("\n") { ch ->
                "### ${ch.title}\n" + ch.paras.joinToString("\n") { "[${it.role} p${it.pageNo}] ${it.text}" }
            }
    }

    /** 抽 A/B 对照表要用的关键行 */
    private fun headline(report: String): String = report.lineSequence()
        .filter {
            it.startsWith("过断") || it.startsWith("BODY median") || it.startsWith("total=") ||
                it.startsWith("【") || it.startsWith("bodySize") || it.startsWith("arms=")
        }
        .joinToString("\n")

    /** 目录样残留（正文里仍带点线+页码尾巴）——错法 3 的量化口径 */
    private val RE_TOC_TAIL = Regex("[…·.•‧．●]{2,}\\s*[（(]?\\d{1,4}[）)]?\\s*$")

    /** 小节标题形态（与 ParagraphAssembler.headingLikeOcr 同判据的测试侧复写）：
     *  标题段本就该独立，不算「句子被斩」，故从断段嫌疑里剔除 */
    private val RE_HEADING = Regex(
        "^(第[零〇一二三四五六七八九十百千两]+[章节目篇编卷回款]|[一二三四五六七八九十]+[、,，]" +
            "|[（(][零〇一二三四五六七八九十]+[）)]|[0-9]{1,2}[^0-9.．])",
    )

    /** 段内「第X章/节/款」硬证据（漏断靶子；交叉引用「详见第二节」会计入，只作人工判读线索） */
    private val RE_CN_HEAD_IN = Regex("第[零〇一二三四五六七八九十百千两]+[章节目篇编卷回款]")

    /** 脚注样文本（引注特征）——错法 1「脚注混进正文」的量化口径 */
    private val RE_FOOTNOTEISH =
        Regex("参见|年度.{0,8}字第|\\(\\d{4}\\)|（\\d{4}）|[「“\"‘]最高法院")

    private fun buildReport(
        f: Fixture,
        p2: OcrImportRunner.Pass2,
        stats: DocStats,
        chapters: List<ParsedChapter>,
        hits: Map<String, Int>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("== 组装规则信号（ParagraphAssembler hitStats）==")
        sb.appendLine(
            "过断 q_breakPrevNotSentence=${hits["q_breakPrevNotSentence"] ?: 0} " +
                "漏断 q_joinSentThenIndent=${hits["q_joinSentThenIndent"] ?: 0}",
        )
        sb.appendLine("arms=${hits.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }}")
        sb.appendLine("== 回放页统计 ==")
        sb.appendLine(
            "pages=${f.pagesOcrLines.size} fallback=${p2.fallbacks.size} " +
                "${p2.fallbacks.groupingBy { it.reason }.eachCount()} " +
                "fallbackPages=${p2.fallbacks.joinToString(",") { it.pageNo.toString() }}",
        )
        sb.appendLine(
            "bodySize=%.2f left=%.1f right=%.1f pitch=%s fragmentLinesMerged=%d".format(
                stats.bodySize, stats.left, stats.right,
                stats.pitchThreshold?.let { "%.1f".format(it) } ?: "null",
                p2.fragmentLinesMerged,
            ),
        )

        val all = chapters.flatMap { it.paras }
        sb.appendLine("== 段落 ==")
        sb.appendLine(
            "total=${all.size} byRole=${all.groupingBy { it.role }.eachCount()} " +
                "chapters=${chapters.size}",
        )
        val body = all.filter { it.role == DbValues.ROLE_BODY }
        // 标题形态段（≤24 字且编号起头）本就该独立，不计入「正文段长」，否则认出越多标题、
        // 段长中位越难看——量具会把修复量成退化
        val headingOf = { t: String -> t.length <= 24 && RE_HEADING.containsMatchIn(t) }
        val prose = body.filterNot { headingOf(it.text) }
        val lens = prose.map { it.text.length }.sorted()
        if (lens.isNotEmpty() && prose.isNotEmpty()) {
            val short = prose.count { it.text.length < 25 }
            val notSent = prose.filter { !endsSentence(it.text) && it.text.length >= 8 }
            val p90 = lens[(lens.size * 0.9).toInt().coerceAtMost(lens.size - 1)]
            sb.appendLine(
                "BODY median=${lens[lens.size / 2]} mean=%.1f p90=$p90 "
                    .format(lens.average()) +
                    "<25字=$short(${short * 100 / prose.size}%)  [已剔除标题形态段 ${body.size - prose.size} 段]",
            )
            sb.appendLine(
                "【断段嫌疑】BODY 段非句末收尾且 ≥8 字 = ${notSent.size}/${prose.size} " +
                    "(${notSent.size * 100 / prose.size}%)",
            )
            notSent.take(40).forEach { sb.appendLine("   [p${it.pageNo}] …${it.text.takeLast(28)}") }
        }
        val footInBody = body.filter { RE_FOOTNOTEISH.containsMatchIn(it.text) }
        sb.appendLine("【脚注混入正文】${footInBody.size} 段")
        footInBody.take(15).forEach { sb.appendLine("   [p${it.pageNo} len=${it.text.length}] ${it.text.take(60)}") }
        // 小节标题被并进正文段：只数「第X章/节/款/目…」硬证据形态（数字/括注形态在段内
        // 会命中年份条号等正文片段，不作漏断证据）
        val headingInside = body.filter { RE_CN_HEAD_IN.containsMatchIn(it.text.substring(1)) }
        sb.appendLine("【标题混在正文段内】${headingInside.size} 段")
        headingInside.take(12).forEach { sb.appendLine("   [p${it.pageNo}] …${it.text.takeLast(40)}") }
        val tocInBody = body.filter { RE_TOC_TAIL.containsMatchIn(it.text.trimEnd()) }
        sb.appendLine("【目录残留混入正文】${tocInBody.size} 段")
        tocInBody.take(15).forEach { sb.appendLine("   [p${it.pageNo}] ${it.text.take(60)}") }

        sb.appendLine("== 章节树 ==")
        chapters.forEachIndexed { i, ch ->
            sb.appendLine("  #$i 「${ch.title}」 paras=${ch.paras.size} blindCut=${ch.blindCut}")
        }
        return sb.toString()
    }

    private companion object {
        const val FIXTURE = "/ocr/replay_shpc60.json"
    }
}
