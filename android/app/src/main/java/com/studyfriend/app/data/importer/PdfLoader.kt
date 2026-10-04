package com.studyfriend.app.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlin.math.ceil
import com.studyfriend.app.data.importer.pdfpipeline.DocStats
import com.studyfriend.app.data.importer.pdfpipeline.ExtractedFigure
import com.studyfriend.app.data.importer.pdfpipeline.FigureExtractorStats
import com.studyfriend.app.data.importer.pdfpipeline.PageOut
import com.studyfriend.app.data.importer.pdfpipeline.PdfCleaner
import com.studyfriend.app.data.importer.pdfpipeline.PdfFigureExtractor
import com.studyfriend.app.data.importer.pdfpipeline.PageRenderer
import android.graphics.Rect
import android.util.Log
import com.studyfriend.app.data.importer.pdfpipeline.PLine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

/** PDF 打不开/加密/扫描版/超大，统一走这里，UI 直接展示 message（open：允许取消类型细分归因） */
open class PdfImportException(message: String) : Exception(message)

/** 用户主动取消（独立类型：VM 据此与真实失败区分，避免把延后发生的 OOM/扫描版误报成取消） */
class CancelledImportException(message: String = "已取消") : PdfImportException(message)

/**
 * 提取产物（OPT-E）：清洗后的逐页段落 + 全书几何统计 + 是否扫描版。
 * assembleText() 先做跨页续接合并（视觉整页替换发生在合并之前——VM 流程是
 * extract → 选页 → 视觉转写替换 → assembleText，合并标志防重复合并）。
 *
 * P4 扩展：figures（幸存图，staging 文件已落盘）+ figureStats（parseNote 对账）+
 * 横版占比/双栏嫌疑两个版式信号。TXT/粘贴路径不经过本类（figures 恒空）。
 */
class PdfExtractResult(
    val pages: List<PageOut>,
    val stats: DocStats,
    val scanned: Boolean,
    /** P4 幸存图（seqNo/md5 已算定）；staging 文件在 confirmImport 时挪入 filesDir/figures/{bookId}/ */
    val figures: List<ExtractedFigure> = emptyList(),
    /** P4 图片提取统计；null=TXT 路径或提取器未运行（异常时也非 null，fatalError=true） */
    val figureStats: FigureExtractorStats? = null,
    /** 横版（折算后 h>w）页占比 0..1；parseNote 横版提示阈值 30% */
    val landscapeRatio: Float = 0f,
    /** 行左边界双峰嫌疑（简化直方图，见 [PdfLoader.detectDoubleColumn]）；parseNote 多栏提示 */
    val doubleColumnSuspicion: Boolean = false,
    alreadyMerged: Boolean = false,
) {
    private var merged = alreadyMerged

    /**
     * [styleAware]=true 时给大字段落打〔标题〕前缀（P3a 字号证据链，PDF 路径专用）：
     * size ≥ bodySize+1.5pt 的非脚注段标为标题候选证据，BookParser 据此确认/拦截
     * A/B 档标题命中。TXT 路径不经过本方法；false 时输出与 P2 逐字节一致。
     * 页级守卫（tocish）：OCR 目录尾页点线条目不足 TOC_MIN_HITS 漏判 tocLike，
     * 其残留条目（点线常丢页码）本身声明大字号（真书 E2E：目录条目「第十二章
     * 权利的行使」12pt ≥ 阈值拿到假证据成假章）——页内点线尾部条目 ≥2 条时本页
     * 不发〔标题〕标（字号证据只对纯正文页可信；真章首页无点线，不受影响）。
     * 浮点策略：threshold 只计算一次（单次加法无双精度累加）；bodySize=NaN 时
     * `size >= NaN` 恒 false（IEEE 754）=安全退化，比较运算符不得改为 `>`/`!=`。
     */
    fun assembleText(styleAware: Boolean = false): String {
        if (!merged) {
            PdfCleaner.crossPageMerge(pages, stats)
            merged = true
        }
        // 段落间必须空行分隔：BookParser 按空行分块、每块至多一个标题，
        // 单个 \n 会把全书挤成一个块、只认出第一个章标题（DebugDump 实证）。
        // 例外：目录页条目用单 \n 连成整页一个块——条目一段一条会击穿
        // BookParser 的目录区密度判定，无点线条目会漏成假章（真书探针实证）。
        // 脚注段打〔脚注〕前缀（BookParser.FOOTNOTE_MARK）：解析器剥掉后标
        // ROLE_FOOTNOTE，脚注不混进正文流（真书 E2E：脚注误混正文约 300 段）。
        val threshold = stats.bodySize + 1.5f
        return pages.joinToString("\n\n") { page ->
            val sep = if (page.tocLike) "\n" else "\n\n"
            val tocish = styleAware &&
                page.paras.count { RE_TOC_TAIL.containsMatchIn(it.text) } >= TOC_TAIL_MIN
            val body = page.paras.joinToString(sep) {
                val marked = when {
                    it.footnote -> BookParser.FOOTNOTE_MARK
                    styleAware && !tocish && it.size >= threshold -> BookParser.TITLE_SIZE_MARK
                    else -> ""
                }
                marked + it.text
            }
            // P3b-2 pageNo 链路：有段落的页在正文前插独立行「〔页N〕」（N=1-based 页号，
            // BookParser.PAGE_MARK_PREFIX 协议）。BookParser 预处理剥标并把 N 记为随后
            // 段落的 pageNo（段首页码）。目录页也插（剥标发生在目录识别之前，不干扰
            // isTocBlock）；空页不插（无段落可归属，标记孤块反而成噪声）。
            if (body.isEmpty()) body else "${BookParser.PAGE_MARK_PREFIX}${page.pageNum}〕\n$body"
        }
    }

    companion object {
        /** 目录尾部条目：行尾点线，页码可缺（OCR 目录尾页常丢页码）——PdfCleaner.RE_TOC_LINE 的放宽形态 */
        private val RE_TOC_TAIL = Regex("[…⋯·•‧.]{3,}\\s*\\d{0,4}\\s*$")

        /** 点线条目数达此值即视为目录残留页（≥1 会误伤含省略号的正文页） */
        private const val TOC_TAIL_MIN = 2
    }
}

/**
 * PDF 文本提取（§1/§4 + OPT-E）：经 cacheDir 临时文件控内存；逐页提取带
 * DirAdj 几何的行结构 → 全书几何统计 → 页面清洗（删页眉/页码、收脚注、
 * 目录页直通、段落组装）。平均每页字数 < 100 判定扫描版（无文字层）：
 * allowScanned=false 时抛友好提示，true 时返回 scanned=true 交视觉转写兜底。
 * 加密抛友好提示；OOM 兜底；进度逐页推进；复制段分块即时可断。
 */
object PdfLoader {

    private const val TAG = "PdfLoader"

    private const val SCANNED_CHARS_PER_PAGE = 100

    /** P4 幸存图临时落盘目录（cacheDir 下）；confirmImport 时挪入 filesDir/figures/{bookId}/ */
    private const val FIGURES_STAGING_DIR = "figures_staging"

    /** 双栏嫌疑：行左边界最大相邻间隙超过此值（pt）视为两峰分界 */
    private const val DOUBLE_COL_MIN_GAP_PT = 30f

    /**
     * 双栏嫌疑检测（计划案 v1.11，r11-P2-3 简化实现）：全部行左边界 x0 排序后取最大
     * 相邻间隙，>30pt 且间隙两侧行数各 ≥15% 判双峰。「峰」以最大间隙近似直方图双峰，
     * 保守高召回（提示性文案，非判定）；行数 <20 不足以谈分布。
     */
    private fun detectDoubleColumn(pagesLines: List<List<PLine>>, stats: DocStats): Boolean {
        val xs = pagesLines.asSequence().flatten().map { it.x0 }
            .filter { it >= 0f && it < stats.right + stats.bodySize * 2 }
            .toList()
        if (xs.size < 20) return false
        val sorted = xs.sorted()
        var maxGap = 0f
        var split = -1
        for (i in 1 until sorted.size) {
            val gap = sorted[i] - sorted[i - 1]
            if (gap > maxGap) {
                maxGap = gap
                split = i
            }
        }
        if (maxGap <= DOUBLE_COL_MIN_GAP_PT) return false
        val leftN = split
        val rightN = sorted.size - split
        return leftN >= xs.size * 0.15f && rightN >= xs.size * 0.15f
    }

    /**
     * 系统 PdfRenderer（pdfium 内核）整页渲染兜底（r12-P1-1）：tom-roush 移植版
     * FilterFactory 未注册 JBIG2Filter，JBIG2 等编码的幸存图 pdfbox 解不出——pdfium
     * 支持全部 PDF 滤波器，整页渲染后由提取器按显示 bbox 裁剪。每次调用独立开
     * renderer（fd 与 pdfbox 的互不干扰），用完即关；异常一律转 null 由提取器记失败。
     * [scale] 为 pt→px 比例（提取器按目标宽与像素预算算定）；白底填充防透明区域成黑底。
     */
    private fun pdfPageRenderer(context: Context, uri: Uri): PageRenderer =
        PageRenderer { pageNo, _, scale ->
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val page = renderer.openPage(
                            (pageNo - 1).coerceIn(0, renderer.pageCount - 1),
                        )
                        val w = ceil(page.width * scale).toInt().coerceAtLeast(1)
                        val h = ceil(page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        val m = Matrix()
                        m.setScale(scale, scale)
                        page.render(bmp, null as Rect?, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        bmp
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pdf renderer fallback failed: ${e.message}")
                null
            }
        }

    fun extract(
        context: Context,
        uri: Uri,
        onProgress: (page: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        allowScanned: Boolean = false,
    ): PdfExtractResult {
        PDFBoxResourceLoader.init(context)
        val tmp = File(context.cacheDir, "import_${System.nanoTime()}.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    // 分块复制 + 检查点：大文件复制段（秒级）也即时可断，而不是等 copyTo 整体结束
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancelledImportException()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            } ?: throw PdfImportException("读取文件失败")
            return try {
                PDDocument.load(tmp).use { doc ->
                    val pages = doc.numberOfPages
                    if (pages <= 0) throw PdfImportException("这个 PDF 没有可读取的页面")
                    onProgress(0, pages)
                    val pagesLines = mutableListOf<List<PLine>>()
                    val dims = mutableListOf<Pair<Float, Float>>()
                    for (page in 1..pages) {
                        if (isCancelled()) throw CancelledImportException()
                        pagesLines.add(PdfLineExtractor.extractPage(doc, page))
                        // 旋转已折算的可视页宽高（清洗层算页眉/脚注条带用）
                        val pdPage = doc.getPage(page - 1)
                        val w = pdPage.cropBox.width
                        val h = pdPage.cropBox.height
                        dims.add(if (pdPage.rotation % 180 == 90) h to w else w to h)
                        onProgress(page, pages)
                    }
                    val totalChars = pagesLines.sumOf { lines -> lines.sumOf { it.text.length } }
                    val scanned = totalChars < pages * SCANNED_CHARS_PER_PAGE
                    if (scanned && !allowScanned) {
                        throw PdfImportException(
                            "这看起来是扫描版 PDF（没有文字层），暂时无法导入；" +
                                "请换成带文字的 PDF 或 TXT",
                        )
                    }
                    val stats = PdfCleaner.docStats(pagesLines, dims)
                    val pageOuts = PdfCleaner.clean(pagesLines, dims, stats)
                    // P4 图锚定口径（r9-P1-1）：锚定必须拿 merge 后的页内段序。真 merge 在
                    // assembleText（视觉转写之后）执行，这里只做不改对象的 y0 预演——提前
                    // 跑真 merge 会让「转写页→图页」续接段随整页替换蒸发（预演 KDoc 详述）
                    val pageParaY0s = PdfCleaner.crossPageMergeY0Preview(pageOuts, stats)
                    // P4 图片提取（r11-P1-1 异常隔离）：独立 try-catch，图片失败不拖死文本导入
                    var figures = emptyList<ExtractedFigure>()
                    var figureStats: FigureExtractorStats? = null
                    try {
                        // 进度契约（0..pages 各一次）归文字提取所有：图片阶段在全文之后，
                        // 复用页号回调会出现重复末值——保持 onProgress 缺省（no-op）
                        val r = PdfFigureExtractor(File(context.cacheDir, FIGURES_STAGING_DIR))
                            .extract(doc, pageParaY0s, pageRenderer = pdfPageRenderer(context, uri))
                        figures = r.figures
                        figureStats = r.stats
                    } catch (e: OutOfMemoryError) {
                        // Error 也必须隔离（r12-QC1）：外层 catch(OutOfMemoryError) 会把图片
                        // 阶段的 OOM 转成整书导入失败——违反「图片失败不拖死文本导入」承诺
                        Log.w(TAG, "figure extraction OOM: ${e.message}")
                        figureStats = FigureExtractorStats().apply { fatalError = true }
                        File(context.cacheDir, FIGURES_STAGING_DIR).deleteRecursively()
                    } catch (e: Exception) {
                        Log.w(TAG, "figure extraction failed: ${e.javaClass.simpleName}: ${e.message}")
                        figureStats = FigureExtractorStats().apply { fatalError = true }
                        // extract 抛异常=幸存图未产出（或产出中途断），staging 无可挪文件，即刻清
                        File(context.cacheDir, FIGURES_STAGING_DIR).deleteRecursively()
                    }
                    val landscapeRatio = if (dims.isEmpty()) 0f
                    else dims.count { it.second > it.first }.toFloat() / dims.size
                    PdfExtractResult(
                        pageOuts, stats, scanned, figures, figureStats,
                        landscapeRatio, detectDoubleColumn(pagesLines, stats),
                    )
                }
            } catch (e: InvalidPasswordException) {
                throw PdfImportException("PDF 已加密，请先解除密码保护再导入")
            } catch (e: OutOfMemoryError) {
                throw PdfImportException("这本书太大，内存装不下；建议拆分成几个文件分批导入")
            } catch (e: PdfImportException) {
                throw e
            } catch (e: Exception) {
                throw PdfImportException("PDF 解析失败：${e.message ?: "文件可能已损坏"}")
            }
        } finally {
            tmp.delete()
        }
    }
}
