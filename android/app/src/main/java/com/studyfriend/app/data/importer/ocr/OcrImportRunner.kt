package com.studyfriend.app.data.importer.ocr

import android.content.Context
import android.net.Uri
import com.studyfriend.app.data.importer.CancelledImportException
import com.studyfriend.app.data.importer.PdfImportException
import com.studyfriend.app.data.importer.PdfPageRenderer
import com.studyfriend.app.data.importer.pdfpipeline.PdfCleaner
import com.studyfriend.app.data.importer.pdfpipeline.PLine
import com.studyfriend.app.data.importer.pdfpipeline.TextSourceRow
import kotlinx.coroutines.sync.Mutex
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 扫描书本地 OCR 导入执行器（P6b S4）：
 * 两遍法（v1.1 P2-4 定死）——Pass1 逐页 140dpi 渲染 → OCR 引擎识别 → 规则 a/b 行内
 * 后处理（仅依赖单行文本，无需统计量）；Pass2 全本收齐后 docStats 预计算主字号 → 逐页
 * 规则 c（diagram 分类）/d（碎片行合并）→ 页级分流 → PLine（sourceVersion=PROD_OCR_V1）。
 * 产出 pagesLines/dims 交 PdfLoader.finalizeOcr 走与数字路径共用的 clean/figures 流水线。
 *
 * 页级分流（决策案 §3.1）：页置信度 ≥[PageGate.CONF_THRESHOLD] 且低置信行占比
 * ≤[PageGate.MAX_LOW_CONF_RATIO]（「缺行率」的生产代理口径，KDoc 记档）且非 diagram
 * → 进管线；否则该页入视觉兜底队列（OCR 草稿不入库，视觉完成后整页替换）。
 *
 * 并发防护（v1.2 P1-2）：[IMPORT_MUTEX] 伴生对象互斥，并发第二次扫描书导入直接提示
 * 不排队（双引擎叠加 ~416MB OOM 风险）；异常 close（v1.2 P2-3）：整轮 try-finally
 * 必调 engine.close()，异常/取消路径均释放 ~208MB arena。
 */
class OcrImportRunner(
    private val context: Context,
    private val uri: Uri,
    private val engine: OcrEngine,
    private val onProgress: (page: Int, total: Int, etaMinutes: Int) -> Unit = { _, _, _ -> },
    private val isCancelled: () -> Boolean = { false },
    /** AI 目视确认竖排（全本扫描疑似后触发）：返回各页「是否竖排」判定；null=视觉不可用（放行） */
    private val aiVerticalCheck: suspend (pageNos: List<Int>, render: (Int) -> String) -> List<Boolean>? =
        { _, _ -> null },
) {

    companion object {
        /** 双引擎叠加 OOM 防护：同一时刻只允许一轮 OCR 导入 */
        val IMPORT_MUTEX = Mutex()

        /** P6a 实测单页中位耗时（秒），ETA 估算基准 */
        const val SECONDS_PER_PAGE = 1.24

        /** 估字号系数：行框高 × 0.68（rapidocr 实测正文行框高≈1.47×字号；生产引擎 det
         *  扩张口径不同，S7 E2E 以真书 docStats 众数校准。系数只作整体缩放，众数不受影响） */
        const val SIZE_FROM_HEIGHT = 0.68f

        /** Pass2 完成诊断行（P6c 小计划 C 追加 fallbackPages 页码明细，闭环 B 报告 F7 缺口）。
         *  纯函数抽出，单测锁定格式（OcrImportRunnerPageGateTest）。 */
        internal fun pass2DoneLine(
            total: Int,
            fallbacks: List<FallbackPage>,
            fragmentLinesMerged: Int,
            pass2Ms: Long,
        ): String =
            "Pass2 done: pages=$total fallbacks=${fallbacks.size} " +
                "byReason=${fallbacks.groupingBy { it.reason }.eachCount()} " +
                "fallbackPages=${fallbacks.joinToString(",") { it.pageNo.toString() }} " +
                "fragmentLinesMerged=$fragmentLinesMerged " +
                "pass2Ms=$pass2Ms"

        /** DIAGRAM 页 tallBox 量化行（h/w 与 w 两个一级量化，补 bbox 缺口）。纯函数，单测锁定格式。 */
        internal fun tallBoxLine(pageNo: Int, hw: Float, w: Float): String =
            "tallBox page=$pageNo hw=${"%.2f".format(hw)} w=${"%.1f".format(w)}pt"

        /** OCR 行 → PLine：估字号 + sourceVersion=PROD_OCR_V1（P3a 阈值随版本分支的触发标记） */
        internal fun OcrLine.toPLine(): PLine = PLine(
            text = text,
            x0 = x0,
            x1 = x1,
            y0 = y0,
            size = max(1f, (y1 - y0) * SIZE_FROM_HEIGHT),
            sourceVersion = TextSourceRow.PROD_OCR_V1,
        )

        /** [pass2] 产物（Pass2 纯逻辑的返回值，字段与 [Outcome] 对齐） */
        /**
         * Pass2 单源：bodySize → 逐页规则 c（diagram 分类）→ 页级分流 → 规则 d（碎片合并）。
         * 生产 [runLocked] 与 JVM 离线回放台（OcrReplayBenchTest：拿 PC 复刻引擎导出的真书
         * 行序列重跑整链）共用本函数——回放台的全部价值在于「跑的是生产判定」，故规则不许复制粘贴。
         */
        internal fun pass2(
            pagesOcrLines: List<List<OcrLine>>,
            dims: List<Pair<Float, Float>>,
        ): Pass2 {
            // 估字号先转一轮求 bodySize（碎片合并的参照）；页型判定在合并前
            val bodySize = PdfCleaner.docStats(
                pagesOcrLines.map { page -> page.map { it.toPLine() } },
                dims,
            ).bodySize

            val fallbacks = mutableListOf<FallbackPage>()
            val pageMeanConfs = mutableListOf<Float>()
            val pageLineConfs = mutableListOf<List<Float>>()
            val pageDrafts = mutableListOf<List<OcrLine>>()
            var fragmentLinesMerged = 0 // 规则 d 合并掉的碎片行数（判据③c 生产可观测）
            val pagesLines = pagesOcrLines.mapIndexed { idx, lines ->
                val pageNo = idx + 1
                if (OcrTextPostProcessor.classifyPage(lines) == OcrTextPostProcessor.PageType.DIAGRAM) {
                    fallbacks.add(FallbackPage(pageNo, "DIAGRAM"))
                    pageMeanConfs.add(0f)
                    pageLineConfs.add(emptyList())
                    pageDrafts.add(lines)
                    emptyList() // diagram 页整页兜底，OCR 草稿不入库
                } else {
                    val reason = PageGate.decide(lines)
                    if (reason != null) {
                        fallbacks.add(FallbackPage(pageNo, reason))
                        pageMeanConfs.add(0f)
                        pageLineConfs.add(emptyList())
                        pageDrafts.add(lines)
                        emptyList()
                    } else {
                        pageMeanConfs.add(PageGate.meanConf(lines))
                        val merged = OcrTextPostProcessor.mergeFragments(lines, bodySize)
                        fragmentLinesMerged += lines.size - merged.size
                        pageLineConfs.add(merged.map { it.confidence })
                        pageDrafts.add(emptyList())
                        merged.map { it.toPLine() }
                    }
                }
            }
            return Pass2(
                pagesLines, fallbacks, pageMeanConfs, pageLineConfs, pageDrafts,
                bodySize, fragmentLinesMerged,
            )
        }
    }

    /** 兜底页清单条目：reason ∈ DIAGRAM / LOW_CONF */
    data class FallbackPage(val pageNo: Int, val reason: String)

    /** [pass2] 产物（Pass2 纯逻辑的返回值，字段与 [Outcome] 对齐） */
    internal data class Pass2(
        val pagesLines: List<List<PLine>>,
        val fallbacks: List<FallbackPage>,
        val pageMeanConfs: List<Float>,
        val pageLineConfs: List<List<Float>>,
        val pageDrafts: List<List<OcrLine>>,
        val bodySize: Float,
        val fragmentLinesMerged: Int,
    )

    data class Outcome(
        val pagesLines: List<List<PLine>>,
        val dims: List<Pair<Float, Float>>,
        val fallbackPages: List<FallbackPage>,
        /** 每页平均置信度（低置信行剔除后），供漂移监控两级告警；兜底页记 0f */
        val pageMeanConfs: List<Float>,
        /** 每页行级置信度（与 pagesLines 同序同长，碎片合并后 conf=min 口径）；
         *  兜底页空列表。离线校验快照（v1.3 P1-2）用 */
        val pageLineConfs: List<List<Float>>,
        /** 每页识别草稿（规则 a/b 后原始行，与 pagesLines 同序同长）；仅兜底页非空、
         *  过闸页恒空（草稿不入库）。生产管线不消费，供 DEBUG 抽样落盘做置信度-质量
         *  分层标定（P6b 落地报告 §7 选项 A 的画线依据） */
        val pageDrafts: List<List<OcrLine>>,
    ) {
        init {
            // 逐页列表五方同长不变量（S6）：快照/告警按页索引取值，错位会越界或张冠李戴
            require(
                pagesLines.size == dims.size &&
                    dims.size == pageMeanConfs.size &&
                    pageMeanConfs.size == pageLineConfs.size &&
                    pageLineConfs.size == pageDrafts.size,
            ) {
                "Outcome 逐页列表长度不一致：lines=${pagesLines.size} dims=${dims.size} " +
                    "meanConfs=${pageMeanConfs.size} lineConfs=${pageLineConfs.size} " +
                    "drafts=${pageDrafts.size}"
            }
        }
    }

    suspend fun run(): Outcome {
        // 并发互斥：tryLock 失败即提示，不排队（避免长时占内存）
        if (!IMPORT_MUTEX.tryLock()) {
            throw PdfImportException("正在本地识别中，请稍后再试")
        }
        try {
            return runLocked()
        } finally {
            IMPORT_MUTEX.unlock()
            try {
                engine.close()
            } catch (e: Exception) {
                android.util.Log.w("OcrImport", "engine close: ${e.message}")
            }
        }
    }

    private suspend fun runLocked(): Outcome {
        // open/close 在同一执行器内成对（S4 质检自查补：close 在 finally，open 缺失会让
        // recognize 抛「未 open」——JVM 单测测不到引擎真实加载，S7 E2E 才暴露，此处堵死）
        engine.open(OcrModelStore.modelsDir(context))
        PdfPageRenderer(context, uri).use { renderer ->
            val total = renderer.pageCount
            // ---- 竖排书两段式排除（引擎 open 前，纯渲染统计）----
            ensureNotVertical(renderer, total)

            // ---- Pass1：逐页渲染 + 识别 + 规则 a/b ----
            // 分段计时（S7 E2E 判据④：渲染/推理/行内后处理分离观测，logcat tag OcrImport）
            val pass1Start = System.currentTimeMillis()
            var renderMs = 0L
            var inferMs = 0L
            var postMs = 0L
            val pagesOcrLines = ArrayList<List<OcrLine>>(total)
            for (page in 1..total) {
                if (isCancelled()) throw CancelledImportException()
                var t = System.currentTimeMillis()
                val bmp = renderer.renderPageBitmap(page - 1)
                renderMs += System.currentTimeMillis() - t
                t = System.currentTimeMillis()
                val lines: List<OcrLine>
                try {
                    lines = engine.recognize(bmp)
                } finally {
                    bmp.recycle()
                }
                inferMs += System.currentTimeMillis() - t
                t = System.currentTimeMillis()
                // 像素 → pt（×72/dpi）+ 规则 a/b（行内，不依赖统计量）
                val s = 72f / PdfPageRenderer.DPI
                val inPt = lines.map {
                    it.copy(x0 = it.x0 * s, y0 = it.y0 * s, x1 = it.x1 * s, y1 = it.y1 * s)
                }
                pagesOcrLines.add(
                    OcrTextPostProcessor.stripCitations(OcrTextPostProcessor.normalizePunct(inPt)),
                )
                postMs += System.currentTimeMillis() - t
                val eta = ((total - page) * SECONDS_PER_PAGE / 60).roundToInt().coerceAtLeast(1)
                onProgress(page, total, eta)
            }
            android.util.Log.w(
                "OcrImport",
                "Pass1 done: pages=$total renderMs=$renderMs inferMs=$inferMs postMs=$postMs",
            )

            // ---- Pass2：docStats 主字号 → 规则 c/d → 页级分流 ----
            val allLines = pagesOcrLines.flatten()
            if (allLines.isEmpty()) {
                throw PdfImportException("扫描页面未能识别出任何文字，可能是拍摄/扫描质量太差")
            }
            val dims = renderer.pageDims()
            val pass2Start = System.currentTimeMillis()
            val p2 = pass2(pagesOcrLines, dims)
            android.util.Log.w(
                "OcrImport",
                pass2DoneLine(
                    total, p2.fallbacks, p2.fragmentLinesMerged,
                    System.currentTimeMillis() - pass2Start,
                ),
            )
            // DIAGRAM 页 tallBox 量化（P6c 小计划 C）：与 classifyPage 共用 findTallBox 单源判定
            p2.fallbacks.filter { it.reason == "DIAGRAM" }.forEach { f ->
                val box = OcrTextPostProcessor.tallBoxAspect(pagesOcrLines[f.pageNo - 1])
                if (box != null) android.util.Log.w(
                    "OcrImport",
                    tallBoxLine(f.pageNo, box.first, box.second),
                )
            }
            return Outcome(
                pagesLines = p2.pagesLines,
                dims = dims,
                fallbackPages = p2.fallbacks,
                pageMeanConfs = p2.pageMeanConfs,
                pageLineConfs = p2.pageLineConfs,
                pageDrafts = p2.pageDrafts,
            )
        }
    }

    /** 竖排两段式：初筛 10 页 → 全本扫描 → AI 目视 3 页 → 拒绝抛「暂不支持」 */
    private suspend fun ensureNotVertical(renderer: PdfPageRenderer, total: Int) {
        fun ratioOf(pageNo1: Int): Float {
            val bmp = renderer.renderPageBitmap(pageNo1 - 1)
            return try {
                OcrVerticalDetector.pageRatio(bmp)
            } finally {
                bmp.recycle()
            }
        }
        val sample = OcrVerticalDetector.samplePages(total)
        val sampleRatios = sample.map { ratioOf(it) }
        android.util.Log.w("OcrImport", "vertical prefilter: samplePages=$sample ratios=$sampleRatios")
        if (!OcrVerticalDetector.needsFullScan(sampleRatios)) return
        val allRatios = (1..total).map { p ->
            if (isCancelled()) throw CancelledImportException()
            ratioOf(p)
        }
        val fullSuspicious = OcrVerticalDetector.fullScanSuspicious(allRatios)
        android.util.Log.w(
            "OcrImport",
            "vertical full scan: pages=$total maxRatio=${allRatios.maxOrNull()} suspicious=$fullSuspicious",
        )
        if (!fullSuspicious) return
        // AI 目视抽 3 页确认（SUSPECT 带内前 3 页）；视觉不可用/失败 → 放行（确认不了不拒）
        val suspectPages = (1..total).filter { allRatios[it - 1] > OcrVerticalDetector.SUSPECT_RATIO }
        val verdicts = aiVerticalCheck(suspectPages.take(3)) { pageNo ->
            renderer.renderPageBase64(pageNo - 1)
        }
        if (verdicts != null && verdicts.count { it } >= 2) {
            throw PdfImportException(
                "这本书是竖排排版（如古籍/日文书），暂不支持扫描导入；" +
                    "如确认本书为横排，可在设置中关闭「扫描书本地识别」后重试",
            )
        }
    }

    /** 页级分流判定（纯逻辑供 JVM 单测；LOW_CONF 页的 OCR 草稿不入库，视觉整页替换兜底） */
    object PageGate {
        /** 页置信度阈值（决策案 85%；F9 实测 shpc 页均置信度远高于此，几乎不触发） */
        const val CONF_THRESHOLD = 0.85f

        /** 低置信行占比上限（「缺行率 ≤20%」的生产代理口径：conf<0.5 视为无效行） */
        const val MAX_LOW_CONF_RATIO = 0.20f
        private const val LOW_CONF_LINE = 0.5f

        /** null=进管线；非 null=兜底原因（LOW_CONF） */
        fun decide(lines: List<OcrLine>): String? {
            if (lines.isEmpty()) return "LOW_CONF"
            val median = lines.map { it.confidence }.sorted()[lines.size / 2]
            val lowRatio = lines.count { it.confidence < LOW_CONF_LINE }.toFloat() / lines.size
            return if (median < CONF_THRESHOLD || lowRatio > MAX_LOW_CONF_RATIO) "LOW_CONF" else null
        }

        /** 页均置信度（低置信行剔除后均值；全低置信页记 0f） */
        fun meanConf(lines: List<OcrLine>): Float {
            val kept = lines.filter { it.confidence >= LOW_CONF_LINE }
            return if (kept.isEmpty()) {
                0f
            } else {
                kept.sumOf { it.confidence.toDouble() }.toFloat() / kept.size
            }
        }
    }
}
