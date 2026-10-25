package com.studyfriend.app.ui.screens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.BookRepository
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.importer.BookParser
import com.studyfriend.app.data.importer.CancelledImportException
import com.studyfriend.app.data.importer.CustomRegexNoMatchException
import com.studyfriend.app.data.importer.DecodeException
import com.studyfriend.app.data.importer.KitBookParser
import com.studyfriend.app.data.importer.KitFigureMatcher
import com.studyfriend.app.data.importer.KitFigureMeta
import com.studyfriend.app.data.importer.KitMeta
import com.studyfriend.app.data.importer.KitZipReader
import com.studyfriend.app.data.importer.ParsedChapter
import com.studyfriend.app.data.importer.PdfExtractResult
import com.studyfriend.app.data.importer.PdfImportException
import com.studyfriend.app.data.importer.PdfLoader
import com.studyfriend.app.data.importer.PdfPageRenderer
import com.studyfriend.app.BuildConfig
import com.studyfriend.app.data.importer.TextLoader
import com.studyfriend.app.data.importer.ocr.OcrDiagnostics
import com.studyfriend.app.data.importer.ocr.OcrImportRunner
import com.studyfriend.app.data.importer.ocr.OcrModelStore
import com.studyfriend.app.data.importer.ocr.PpOcrEngine
import com.studyfriend.app.data.importer.pdfpipeline.TextSourceRow
import com.studyfriend.app.data.importer.pdfpipeline.ExtractedFigure
import com.studyfriend.app.data.importer.pdfpipeline.FigureExtractorStats
import com.studyfriend.app.data.importer.pdfpipeline.FigureParseNote
import com.studyfriend.app.data.importer.pdfpipeline.PageSelector
import com.studyfriend.app.data.importer.pdfpipeline.PageTranscription
import com.studyfriend.app.data.importer.pdfpipeline.Para
import com.studyfriend.app.data.importer.pdfpipeline.excludeFigurePages
import com.studyfriend.app.data.importer.pdfpipeline.CalibrateOutcome
import com.studyfriend.app.data.importer.pdfpipeline.FrontBlock
import com.studyfriend.app.data.importer.pdfpipeline.FrontKinds
import com.studyfriend.app.data.importer.pdfpipeline.FrontMatterBlocks
import com.studyfriend.app.data.importer.pdfpipeline.FrontPageVerdict
import com.studyfriend.app.data.importer.pdfpipeline.TocChapterCalibrator
import com.studyfriend.app.data.importer.pdfpipeline.TocEntry
import com.studyfriend.app.data.importer.pdfpipeline.TocPageGrouper
import com.studyfriend.app.data.importer.pdfpipeline.VisionTranscriber
import com.studyfriend.app.data.importer.pdfpipeline.joinTexts
import com.studyfriend.app.data.db.VisionQueueEntity
import com.studyfriend.app.data.vision.VisionCache
import com.studyfriend.app.data.vision.VisionScheduler
import java.io.File
import java.util.regex.PatternSyntaxException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 目录探针状态机（P3b-2 §3.5）：Idle=未开始/已清空，Running=视觉识别中，Done=过守卫的
 *  目录条目全集（等价原非 null），Failed=无 tocLike/视觉未配置/识别失败/异常（等价原 null）。 */
sealed class TocProbeState {
    data object Idle : TocProbeState()
    data object Running : TocProbeState()
    data class Done(val entries: List<TocEntry>) : TocProbeState()
    data object Failed : TocProbeState()
}

/** 导入流程状态：全文/解析结果活在这里，ImportScreen→TocConfirmScreen 共享，旋转不重读文件 */
class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BookRepository((app as StudyApp).database, app.filesDir)
    private val settings = SettingsRepository((app as StudyApp).database, (app as StudyApp).secretStore)

    var bookTitle by mutableStateOf("")
    var author by mutableStateOf("")

    var encoding by mutableStateOf(TextLoader.AUTO)
        private set
    var currentRegex by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var parseNote by mutableStateOf<String?>(null)
        private set

    /** P4 双层文案（r8-P2-4）：主文案已并入 parseNote；detail 供确认页「详情」展开区（commit D） */
    var figureNoteDetail by mutableStateOf<String?>(null)
        private set
    var chapters by mutableStateOf<List<ParsedChapter>>(emptyList())
        private set
    var imported by mutableStateOf(false)
        private set

    /**
     * 导入成功一次性事件（P5 防御加固）：替代「TocConfirmScreen 轮询 imported 布尔 +
     * effect 体内先 reset 再导航」——reset() 把 key 改回 false 存在协程取消窗口，
     * onDone 可能被取消导致断网导入后卡确认页（3/3 复现未触发，UNMEASURED，事件化消除竞态结构）。
     * 事件顺序：先置 imported=true 再 trySend；消费方在事件循环内 reset+导航。
     */
    val importDone = kotlinx.coroutines.channels.Channel<Unit>(1) // 单缓冲：最新一次成功事件
    var isPdf by mutableStateOf(false)
        private set

    /** P6b S4：本次导入走本地 OCR 路径 → parseNote 区显示「识别结果有误？」反馈入口 */
    var ocrImported by mutableStateOf(false)
        private set

    /** PDF 提取进度 (page,total)；null=非提取阶段 */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** 当前阶段文案：读取文件 / 提取 PDF 文字 / 解析章节结构；null=空闲 */
    var phase by mutableStateOf<String?>(null)
        private set

    private val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 用户取消：提取循环在下一个检查点中止（解析中点取消则解析完成后生效） */
    fun cancelImport() {
        if (busy) cancelFlag.set(true)
    }

    /**
     * OCR 识别质量反馈入口（P6b S4，parseNote 区「识别结果有误？」按钮）：
     * 全局+本书双计数（v1.2 P1-3）；累计 ≥3 次时下一次扫描书导入的备注里提示升级。
     */
    fun reportOcrFeedback() {
        if (!ocrImported) return
        viewModelScope.launch {
            val total = settings.addOcrFeedback(bookTitle)
            Log.i("P6b", "ocr feedback +1, total=$total")
        }
    }

    private var sourceText: String? = null

    /** KIT 成品书元数据（bookocr-kit pack 的 txt 头部协议块命中时非空）：
     *  parse() 据此分流 KitBookParser（电脑侧章界直入）；换文件/粘贴/失败时清空。
     *  mutableStateOf：isKitBook 供确认页观察（KIT 书隐藏「识别规则」入口） */
    private var kitMeta: KitMeta? by mutableStateOf<KitMeta?>(null)

    /** 当前文件是 bookocr-kit 成品书（KIT 直入通道）；确认页据此隐藏「识别规则」——
     *  KIT 书章界权威在电脑侧章表，reparse 的 regex 不参与，展示入口只会误导 */
    val isKitBook: Boolean get() = kitMeta != null

    /** KIT 图包（书名_Kit图包.zip）的图清单（KitZipReader 解出）；非 zip 导入恒空。
     *  parse() 后与 FIGURE 段配对成 pendingFigures（真图文混排），换文件/粘贴清空 */
    private var kitZipFigures: List<KitFigureMeta> = emptyList()
    private var sourceType = DbValues.SRC_PASTE
    private var sourceUri = ""
    /** 最近一次尝试读取的文件，手动改编码后据此重读 */
    private var lastUri: Uri? = null
    /** 视觉转写统计备注（OPT-E）；解析兜底提示不存在时在 parseNote 里展示 */
    private var visionStatsNote: String? = null

    /** 目录探针状态（P3b-2 §3.5）：裸 List<TocEntry>? → sealed class + var（calibrate 只
     *  同步读一次当前值，Done 供消费、其余等价原 null；响应式能力用不上，P3b-3 若需
     *  再升级 StateFlow）。 */
    var tocState: TocProbeState = TocProbeState.Idle
        private set

    /** 探针区段（每册 tocLike 页分组，P3b-2 §3.4）：confirmImport 校准时取各区段目录尾页 */
    private var probeSegments: List<List<Int>>? = null

    /** P8 前置探针页级判定（同书 reparse 复用不重烧）；换文件/粘贴/失败清空 */
    private var frontVerdicts: List<FrontPageVerdict>? = null

    /** P8 目录探针注记（确认页 parseNote 可见性：未发现目录页/视觉未配置/识别失败） */
    private var tocProbeNote: String? = null

    /** 批B：目录识别失败可重试（真失败才 true；未发现目录页/视觉未配置重试无意义） */
    var tocRetryable by mutableStateOf(false)
        private set

    /** 批B：最近一次 PDF/OCR 提取产物——目录重试要拿它重跑探针（extract 太贵不重提） */
    private var lastPdfResult: PdfExtractResult? = null

    /** P8 tocLike 最小页（前置探针圈页上限）；无 tocLike 时为 null */
    private var tocFirstPage: Int? = null

    /**
     * 超单次上限的可疑页暂存（OPT-F）：页号 to 清洗后字数。导入不再拒绝，
     * 文字层先行；确认落库后写 vision_queue 交 WorkManager 后台逐页转写。
     */
    private var pendingVisionItems: List<Pair<Int, Int>>? = null

    /** P4 幸存图（staging 文件在 confirmImport 经 importBook 挪入 filesDir）；TXT/粘贴恒空 */
    private var pendingFigures: List<ExtractedFigure> = emptyList()
    /** P4 提取统计（parseNote 主文案/详情生成源）；null=TXT/粘贴路径 */
    private var figureStats: FigureExtractorStats? = null
    /** P4 低质量放行页 → 该页幸存图数（第一道闸 bypassed，parseNote 消费） */
    private var figureBypassed: Map<Int, Int> = emptyMap()
    /** P5-E（F3 修复）：同步视觉转写失败的页（t==null / 异常中断时未处理页），页号 to 文字层字数。
     *  与 [pendingVisionItems] 合并去重后随导入落 vision_queue，避免失败页永久滞留文字层 */
    private var failedVisionItems: List<Pair<Int, Int>>? = null
    /** 改名按 index 记；换文件/重切会清空，避免错位串到别的章 */
    private val editedTitles = mutableStateMapOf<Int, String>()

    /** P5-C 删假章：用户标记删除的原始下标集合（列表不物理删除，已删行整行点击恢复）。
     *  与 editedTitles 同步清空（章列表重建处旧下标不可信）；确认导入时才过滤落库 */
    var deleted by mutableStateOf(setOf<Int>())
        private set

    /** 批B：导入页是否可见（ImportScreen ON_PAUSE/ON_RESUME 维护）——OCR 期间用户
     *  可离开导入页让识别后台继续，完成后据此决定要不要发召回通知 */
    var uiVisible by mutableStateOf(true)

    /** 批B：导入准备完成召回通知（离开导入页时才发）；渠道/图标与视觉转写通知同风格 */
    private fun notifyImportReady() {
        val ctx = getApplication<Application>()
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(IMPORT_CHANNEL, "导入进度", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
            val pi = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            nm.notify(
                IMPORT_NOTIF_ID,
                androidx.core.app.NotificationCompat.Builder(ctx, IMPORT_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_edit)
                    .setContentTitle("《${bookTitle.ifBlank { "新书" } }》准备完成")
                    .setContentText("识别完成，点击回到导入流程确认目录")
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build(),
            )
        }.onFailure { Log.w("ImportVm", "import notify failed: ${it.message}") }
    }

    fun chapterTitleAt(index: Int): String =
        editedTitles[index] ?: chapters.getOrNull(index)?.title.orEmpty()

    fun rename(index: Int, title: String) {
        if (title.isNotBlank()) editedTitles[index] = title.trim()
    }

    /** 删除一章；至少保留一章（删到只剩一章时静默拒绝，UI 按钮同态禁用） */
    fun deleteChapter(index: Int) {
        if (index !in chapters.indices || deleted.size >= chapters.size - 1) return
        deleted = deleted + index
    }

    fun restoreChapter(index: Int) {
        deleted = deleted - index
    }

    /** 粘贴文本导入（无文件，编码不参与） */
    fun loadPasted(text: String) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            cancelFlag.set(false)
            try {
                sourceText = text
                sourceType = DbValues.SRC_PASTE
                lastUri = null
                isPdf = false
                kitMeta = null // 粘贴不走 KIT 直入（几 MB 成品 txt 贴不进输入框，文件导入才是主链路）
                kitZipFigures = emptyList()
                currentRegex = null
                editedTitles.clear()
                deleted = emptySet()
                drainImportDone()
                visionStatsNote = null
                clearFigures()
                tocState = TocProbeState.Idle // 粘贴路径无探针；清掉上一本 PDF 可能残留的目录
                probeSegments = null
                clearFrontProbeState()
                if (bookTitle.isBlank()) bookTitle = "粘贴笔记"
                parse()
            } catch (e: Exception) {
                error = "解析失败：${e.message ?: "未知错误"}"
            } finally {
                busy = false
            }
        }
    }

    fun loadFile(uri: Uri) {
        if (busy) return // 防重复选择竞态
        val app = getApplication<StudyApp>()
        viewModelScope.launch {
            busy = true
            error = null
            cancelFlag.set(false)
            try {
                try {
                    app.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                    // 持久授权失败不阻断本次读取
                }
                // 先记住本次文件：解码失败（如编码不对）后改下拉，onEncodingChanged 据此重读
                lastUri = uri
                visionStatsNote = null // 新文件读取开始，旧书的视觉统计作废
                pendingVisionItems = null // 旧书的队列暂存同步作废（否则换书确认会错入队）
                failedVisionItems = null
                ocrImported = false
                kitMeta = null // 上一本的 KIT 元数据作废（txt/zip 分支命中时重设）
                kitZipFigures = emptyList() // 上一本图包清单与暂存图同步作废
                clearFigures() // P4：旧书幸存图同步作废
                // 新文件开始先清上一本的探针态（PDF 路径 runTocProbe 会重跑；TXT 保持 Idle）
                tocState = TocProbeState.Idle
                probeSegments = null
                clearFrontProbeState()
                // IO：文件名/mime 查询 + 解码/PDF 提取都在 IO 线程，结果回主线程赋值
                val (name, pdf, text) = withContext(Dispatchers.IO) {
                    val displayName = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
                    val isPdfFile = displayName.endsWith(".pdf", ignoreCase = true) ||
                        app.contentResolver.getType(uri) == "application/pdf"
                    val isZipFile = displayName.endsWith(".zip", ignoreCase = true) ||
                        app.contentResolver.getType(uri)?.contains("zip") == true
                    val content: String
                    if (isPdfFile) {
                        phase = "提取 PDF 文字"
                        val vision = settings.buildVisionTranscriber()
                        // P6b S4/S5：OCR 就绪（开关开 + 模型三件齐）时扫描书走本地识别主路径；
                        // 开关开但模型未下载时仍放行 extract（否则扫描书在 extract 内提前抛错，
                        // 拿不到 scanned 标志引导用户去设置页下载）
                        val ocrEnabled = settings.load().ocrEnabled
                        val modelsReady = OcrModelStore.modelsPresent(app)
                        val ocrReady = ocrEnabled && modelsReady
                        val result = PdfLoader.extract(
                            app, uri,
                            onProgress = { p, t -> progress = p to t },
                            isCancelled = { cancelFlag.get() },
                            allowScanned = vision != null || ocrReady || ocrEnabled,
                            // ocrMode 仅扫描书生效（extract 内判 scanned）：数字书照常提图
                            ocrMode = ocrReady,
                        )
                        if (result.scanned && ocrReady) {
                            // 本地 OCR 主路径：两遍法识别 → 兜底页交视觉队列；视觉不整书转写
                            val ocrResult = runOcrPath(app, uri)
                            lastPdfResult = ocrResult // 批B：目录重试复用，不重新提取
                            pendingFigures = emptyList() // ocrMode 下 figures 已空，防御性清
                            figureStats = null
                            figureNoteDetail = null
                            runTocProbe(ocrResult, uri)
                            content = ocrResult.assembleText(styleAware = true)
                        } else {
                            if (result.scanned && ocrEnabled && !modelsReady && vision == null) {
                                throw PdfImportException(
                                    "这本书是扫描版（没有文字层）；「扫描书本地识别」的模型还没下载，" +
                                        "请先到设置页点「下载识别模型」（约 21MB）再导入，或配置视觉模型",
                                )
                            }
                            pendingFigures = result.figures
                            figureStats = result.figureStats
                            figureNoteDetail = FigureParseNote.detail(
                                result.figureStats, result.landscapeRatio, result.doubleColumnSuspicion,
                            )
                            applyVision(result, uri, vision)
                            lastPdfResult = result // 批B：目录重试复用，不重新提取
                            runTocProbe(result, uri)
                            // P3a 字号证据链：PDF 路径打〔标题〕前缀，parse 侧按 isPdf 同步认标
                            content = result.assembleText(styleAware = true)
                        }
                    } else if (isZipFile) {
                        // KIT 图包（书名_Kit图包.zip）：kit.json 章表+图清单 + book.txt + 真图，
                        // 一个文件直入——章界照电脑侧章表，插图经 pendingFigures 原位落库
                        phase = "读取 KIT 图包"
                        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: ByteArray(0)
                        val staging = File(app.cacheDir, "kit_zip_staging")
                            .apply { deleteRecursively(); mkdirs() }
                        val zipData = KitZipReader.read(bytes, staging)
                            ?: throw PdfImportException(
                                "这个 zip 不是有效的 bookocr KIT 图包（缺 kit.json/book.txt 或已损坏）",
                            )
                        kitMeta = zipData.meta
                        kitZipFigures = zipData.figures
                        content = KitBookParser.stripMeta(zipData.text)
                    } else {
                        phase = "读取文件"
                        val decoded = TextLoader.decode(
                            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: ByteArray(0),
                            encoding,
                        ).text
                        // KIT 成品书检测：头部协议块命中 → 剥块、记元数据，parse 走电脑侧章界
                        kitMeta = KitBookParser.extract(decoded)
                        content = if (kitMeta != null) KitBookParser.stripMeta(decoded) else decoded
                    }
                    Triple(displayName, isPdfFile, content)
                }
                progress = null // 提取结束，进度条让位给解析阶段文案
                sourceText = text
                sourceType = if (pdf) DbValues.SRC_PDF else DbValues.SRC_TXT
                sourceUri = uri.toString()
                isPdf = pdf
                currentRegex = null
                editedTitles.clear()
                deleted = emptySet()
                drainImportDone()
                if (bookTitle.isBlank()) {
                    // KIT 书优先用电脑侧元数据书名（zip 图包文件名固定带「_Kit图包」尾巴，
                    // 直接取文件名会把尾巴带进书名）
                    bookTitle = KitBookParser.preferredTitle(kitMeta?.title, name)
                }
                parse()
                // 批B：大书 OCR/提取期间用户可离开导入页（识别在 viewModelScope 后台继续）；
                // 完成时人不在导入页 → 通知召回（通知权限被拒时静默，回来照常看到结果）
                if (chapters.isNotEmpty() && !uiVisible) notifyImportReady()
            } catch (e: DecodeException) {
                failRead(e.message)
            } catch (e: CancelledImportException) {
                // 取消≠失败：保留既有 chapters/sourceText，仅提示
                parseNote = "已取消导入"
            } catch (e: PdfImportException) {
                failRead(e.message)
            } catch (e: OutOfMemoryError) {
                // Error 不被 Exception 接住，不带这条超大 TXT 会直接崩进程（最终 QA P2-3，对齐 PdfLoader 兜底）
                failRead("这本书太大，内存装不下；建议拆分成几个文件或改用粘贴导入")
            } catch (e: Exception) {
                failRead("读取失败：${e.message ?: "未知错误"}")
            } finally {
                busy = false
                progress = null
                phase = null
            }
        }
    }

    /** P4 幸存图相关状态统一清（换文件/粘贴/失败/回书架） */
    private fun clearFigures() {
        pendingFigures = emptyList()
        figureStats = null
        figureBypassed = emptyMap()
        figureNoteDetail = null
    }

    /** 读取失败：清掉上一次文件的解析结果，避免把旧章节误导入 */
    private fun failRead(message: String?) {
        error = message
        sourceText = null
        kitMeta = null
        parseNote = null
        figureNoteDetail = null
        clearFigures()
        chapters = emptyList()
        deleted = emptySet()
        imported = false // 防御：残留 true 会让守卫误放行旧事件
        ocrImported = false
        drainImportDone()
        isPdf = false
        tocState = TocProbeState.Idle
        probeSegments = null
        clearFrontProbeState()
    }

    /**
     * 扫描书本地 OCR 主路径（P6b S4）：OcrImportRunner 两遍法识别 → PdfLoader.finalizeOcr
     * 复用数字路径 clean 链 → 兜底页交视觉队列（originChars=0，VisionWorker 长度守卫直接
     * 放行整页转写；视觉未配置则不入队只提示）→ 漂移告警/字数偏差进 visionStatsNote →
     * 离线校验快照 + debug 抽样落盘 → 记 ocrImported（parseNote 反馈入口标记）。
     * 引擎 close 由 Runner 的 finally 保证（v1.2 P2-3）。
     */
    private suspend fun runOcrPath(app: StudyApp, uri: Uri): PdfExtractResult {
        // S7 E2E 实录：提取段满格 progress 残留会让 ImportScreen 的 p!=null 分支盖掉
        // phase 文案，OCR 全程显示「提取 PDF 461/461 页」=卡死假象；先清让位 phase 进度
        progress = null
        phase = "本地文字识别"
        val outcome = OcrImportRunner(
            app, uri, PpOcrEngine(),
            onProgress = { p, t, etaSeconds ->
                // 实测均速 ETA（2026-10-11 修：原静态 1.24s/页在慢机上偏短数倍，老板按错误
                // 预期等待黑屏断连）；<1 分钟直说，不给凑整的假分钟数
                phase = "本地文字识别 第 $p/$t 页（" +
                    if (etaSeconds >= 60) "约剩 ${etaSeconds / 60} 分钟）" else "不到 1 分钟）"
            },
            isCancelled = { cancelFlag.get() },
            aiVerticalCheck = ::aiVerticalCheck,
        ).run()
        val result = PdfLoader.finalizeOcr(outcome)
        ocrImported = true
        val vision = settings.buildVisionTranscriber()
        // 批B：兜底页无论视觉是否配置都暂存——确认导入时照常落 vision_queue（PENDING），
        // 未配置视觉则不启动 Worker（配置保存后 reenqueueAllPending 自动补齐消化）
        pendingVisionItems = outcome.fallbackPages.map { it.pageNo to 0 }
        failedVisionItems = null
        val notes = buildList {
            if (outcome.fallbackPages.isNotEmpty()) {
                add(
                    if (vision != null) {
                        "本地识别完成；${outcome.fallbackPages.size} 页（图示或画质差）导入后自动视觉增强"
                    } else {
                        "本地识别完成；${outcome.fallbackPages.size} 页（图示或画质差）已挂起，" +
                            "配置视觉增强后会自动后台补齐"
                    },
                )
            }
            buildOcrQualityNote(outcome, result)?.let { add(it) }
            // 反馈升级提示（v1.2 P1-3）：历史累计反馈 ≥3 次时点出
            if (settings.ocrFeedbackTotal() >= 3) {
                add("你已多次反馈识别问题；如本书效果不佳，可在设置中关闭「扫描书本地识别」改用视觉增强")
            }
        }
        visionStatsNote = notes.joinToString("；").ifEmpty { null }
        writeOcrVerifySnapshot(outcome)
        writeOcrDebugSample(uri, outcome)
        settings.saveLastOcrVersion(TextSourceRow.PROD_OCR_V1)
        return result
    }

    /** 竖排 AI 目视确认：视觉未配置或任何一页判定失败 → null（放行，确认不了不拒） */
    private suspend fun aiVerticalCheck(
        pageNos: List<Int>,
        render: (Int) -> String,
    ): List<Boolean>? {
        val vision = settings.buildVisionTranscriber() ?: return null
        return pageNos.map { p -> vision.isVerticalPage(render(p)) ?: return null }
    }

    /**
     * OCR 质量备注委托 [OcrDiagnostics.qualityNote]（v1.4 P2-1 漂移两级告警 + v1.2 P1-1
     * 字数偏差代理）；null=无可提示。字数口径：每页正文段字符总数。
     */
    private fun buildOcrQualityNote(
        outcome: OcrImportRunner.Outcome,
        result: PdfExtractResult,
    ): String? = OcrDiagnostics.qualityNote(
        pageMeanConfs = outcome.pageMeanConfs,
        pageCharCounts = result.pages.map { p -> p.paras.sumOf { it.text.length } },
    )

    /**
     * 离线校验快照（v1.3 P1-2）：委托 [OcrDiagnostics.writeVerifySnapshot]，固定种子抽页
     * 落盘 filesDir/ocr_verify/<bookKey>/snapshot.json，滚动保留最近 2 本；失败只记 log。
     */
    private fun writeOcrVerifySnapshot(outcome: OcrImportRunner.Outcome) {
        OcrDiagnostics.writeVerifySnapshot(getApplication<StudyApp>().filesDir, bookTitle, outcome)
    }

    /**
     * Debug 抽样（v1.1 P1-4）：仅 DEBUG 构建（编译期常量短路 + R8 死代码消除，release
     * 无此路径也无 ocr_debug 目录）。草稿落盘委托 [OcrDiagnostics.writeFallbackDrafts]；
     * 每 20 页抽 1 页：页面 PNG + 识别文本成对落盘 filesDir/ocr_debug/<bookKey>/，
     * S7/S8 人工核查识别质量用（页图渲染依赖 PdfPageRenderer，留在此处编排）。
     */
    private fun writeOcrDebugSample(uri: Uri, outcome: OcrImportRunner.Outcome) {
        if (!BuildConfig.DEBUG) return
        try {
            val ctx = getApplication<StudyApp>()
            val dir = File(ctx.filesDir, "ocr_debug/${OcrDiagnostics.bookKey(bookTitle)}")
            dir.mkdirs()
            OcrDiagnostics.writeFallbackDrafts(dir, outcome)
            PdfPageRenderer(ctx, uri).use { renderer ->
                for (p in OcrDiagnostics.samplePageNos(outcome.pagesLines.size)) {
                    val bmp = renderer.renderPageBitmap(p - 1)
                    try {
                        File(dir, "p%03d.png".format(p)).outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    } finally {
                        bmp.recycle()
                    }
                    File(dir, "p%03d.txt".format(p)).writeText(
                        outcome.pagesLines[p - 1].joinToString("\n") { it.text },
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("P6b", "ocr debug sample: ${e.message}")
        }
    }

    /**
     * 视觉兜底主流程（OPT-F 起，双路）：
     * - 可疑页 ≤ 单次上限（60/80）：沿用同步转写——导入完成即增强完毕，小书体验不变。
     * - 超上限：不再拒绝导入。文字层先行照常出书，页级任务暂存 [pendingVisionItems]，
     *   确认落库后写 vision_queue 交 WorkManager 后台逐页消化（每页约 1 分钟，
     *   失败自动重试、进程被杀自动续跑）；同步转写的逐页落盘缓存两路共用，
     *   后台页完成前阅读看到的是文字层内容。
     */
    private suspend fun applyVision(result: PdfExtractResult, uri: Uri, vision: VisionTranscriber?) {
        // P4 第一道闸（计划案 §3-C，r6-P1-3）：幸存图页排除出视觉转写（整页替换会毁锚定），
        // 低质量图页（rawChars<100 或 pua>10%）放行并记 bypassed（parseNote 提示+入队标记）
        val figurePageNos = pendingFigures.map { it.pageNo }.toSet()
        val figureCountByPage = pendingFigures.groupingBy { it.pageNo }.eachCount()
        val selected = when (val sel = PageSelector.select(result.pages, scanned = result.scanned)) {
            is PageSelector.Selection.Pages -> {
                val g = excludeFigurePages(sel.pages, result.pages, figurePageNos, figureCountByPage)
                figureBypassed = g.bypassed
                g.filtered
            }
            is PageSelector.Selection.TooMany -> {
                if (sel.pages.size > VisionScheduler.MAX_QUEUE_PAGES) throw PdfImportException(
                    "需要视觉识别的页面太多（${sel.totalPages} 页，超过后台队列上限）；请把文件拆小后分批导入",
                )
                val g = excludeFigurePages(sel.pages, result.pages, figurePageNos, figureCountByPage)
                figureBypassed = g.bypassed
                pendingVisionItems = g.filtered.map { pageNo ->
                    val page = result.pages.first { it.pageNum == pageNo }
                    pageNo to page.paras.sumOf { it.text.length }
                }
                visionStatsNote =
                    "检测到 ${g.filtered.size} 页画质可疑，先用文字层内容导入；" +
                        "完成导入后 App 会在后台自动视觉增强这些页（每页约 1 分钟，可正常阅读，无需等待）"
                return
            }
            PageSelector.Selection.None -> {
                figureBypassed = emptyMap()
                return
            }
        }
        if (selected.isEmpty() || vision == null) return
        var replaced = 0
        // P5-E F3：同步转写失败页暂存（t==null 记单页；异常中断记剩余页），导入后落后台队列
        val failed = mutableListOf<Pair<Int, Int>>()
        var doneCount = 0 // 已收尾页数（成功或已记失败）；异常时 selected.drop(doneCount) 即未完成页
        phase = "视觉转写（每页约 1 分钟，请耐心等待）"
        try {
            PdfPageRenderer(getApplication(), uri).use { renderer ->
                for ((done, pageNo) in selected.withIndex()) {
                    if (cancelFlag.get()) throw CancelledImportException()
                    progress = (done + 1) to selected.size
                    val page = result.pages.first { it.pageNum == pageNo }
                    val originChars = page.paras.sumOf { it.text.length }
                    val t = cachedOrTranscribe(renderer, uri, pageNo, originChars, vision)
                    if (t != null) {
                        page.paras.clear()
                        page.paras.addAll(t.body.map { Para(it) })
                        if (t.footnotes.isNotEmpty()) {
                            page.paras.add(Para(joinTexts(t.footnotes), footnote = true))
                        }
                        replaced++
                    } else {
                        failed += pageNo to originChars
                    }
                    doneCount++
                }
            }
            failedVisionItems = failed
            visionStatsNote = if (failed.isEmpty()) {
                "视觉转写替换了 $replaced/${selected.size} 页"
            } else {
                "视觉转写替换了 $replaced/${selected.size} 页，${failed.size} 页转写失败将在导入后后台增强"
            }
        } catch (e: CancelledImportException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 用户取消不走这里（Cancelled/Cancellation 上抛，导入中止不入队）；此处为网络等真实失败
            val rest = selected.drop(doneCount).map { pageNo ->
                val page = result.pages.first { it.pageNum == pageNo }
                pageNo to page.paras.sumOf { it.text.length }
            }
            failedVisionItems = failed + rest
            visionStatsNote =
                "视觉转写未完成，已保留文字层内容：${e.message ?: "未知错误"}；" +
                    "${(failed + rest).size} 页将在导入后后台视觉增强"
        }
    }

    /**
     * 目录探针（纯产数据）：tocLike 页经 [TocPageGrouper] 区段化取样（按册聚类，
     * 每段取前 2 页、全组 ≤8 页；P3b-2 §3.4）后交视觉识别，结构化目录存入
     * [tocResult] 并记 logcat。不再限制前 200 页——mzzz 下册目录在 p557，200 上限
     * 会滤掉；调用量护栏由区段化上限 8 页承担。任何失败只让 tocResult 保持 null，
     * 导入行为（正文/章节）完全不变；P3b-2 calibrate 消费该数据。
     */
    private suspend fun runTocProbe(result: PdfExtractResult, uri: Uri) {
        // 连续导入时先清掉上一本的目录：无 tocLike/视觉未配置/异常三条早退路径都不得残留旧数据
        tocState = TocProbeState.Idle
        probeSegments = null
        tocRetryable = false
        clearFrontProbeState()
        try {
            val tocSegments = TocPageGrouper.group(result.pages.filter { it.tocLike }.map { it.pageNum })
            if (tocSegments.isEmpty()) {
                Log.i("P3b", "no tocLike pages")
                tocState = TocProbeState.Failed
                tocProbeNote = "未发现目录页，章节按标题识别"
                return
            }
            probeSegments = tocSegments
            tocFirstPage = tocSegments.minOf { seg -> seg.min() }
            Log.i("P3b", "toc probe segments=$tocSegments")
            tocState = TocProbeState.Running
            PdfPageRenderer(getApplication(), uri).use { renderer ->
                val parser = settings.buildTocVisionParser(
                    File(getApplication<Application>().cacheDir, "vision_cache/toc"),
                ) { pageNo -> renderer.renderPageBase64(pageNo - 1) }
                if (parser == null) {
                    Log.i("P3b", "vision unavailable, toc probe skipped")
                    tocState = TocProbeState.Failed
                    tocProbeNote = "视觉模型未配置，目录未识别；配置后重新导入可校准章节"
                    return
                }
                val entries = parser.parseSegments(tocSegments, VisionCache.uriHash(uri.toString()))
                if (entries == null) {
                    Log.i("P3b", "toc probe: no entries (failed, budget exhausted or cached failure)")
                    tocState = TocProbeState.Failed
                    tocProbeNote = "目录识别失败，章节按标题识别"
                    tocRetryable = true // 批B：真失败可重试（清熔断 marker 再烧一次）
                } else {
                    tocState = TocProbeState.Done(entries)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("P3b", "toc probe failed", e)
            tocState = TocProbeState.Failed
        }
    }

    /**
     * 批B：目录识别失败重试——清该书失败熔断 marker 后重跑探针 + 重新解析组章。
     * extract 产物复用（lastPdfResult），不重新提取；视觉未配置时 runTocProbe 会
     * 给出引导文案，marker 也不新增（没 parser 可清）。
     */
    fun retryTocProbe() {
        val result = lastPdfResult ?: return
        val uri = lastUri ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                File(getApplication<Application>().cacheDir, "vision_cache/toc").let { dir ->
                    settings.buildTocVisionParser(dir) { _ -> "" }?.let { p ->
                        p.clearFailureMarkers(VisionCache.uriHash(uri.toString()))
                    }
                }
                runTocProbe(result, uri)
                parse()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "重试失败：${e.message ?: e::class.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    /** 视觉缓存命中直接用；未命中渲染+转写并落盘（落盘失败不影响本次导入） */
    private suspend fun cachedOrTranscribe(
        renderer: PdfPageRenderer,
        uri: Uri,
        pageNo: Int,
        originChars: Int,
        vision: VisionTranscriber,
    ): PageTranscription? {
        VisionCache.read(getApplication(), uri.toString(), pageNo)?.let { return it }
        val t = vision.transcribePage(originChars, renderer.renderPageBase64(pageNo - 1))
        if (t != null) {
            VisionCache.write(getApplication(), uri.toString(), pageNo, t)
        }
        return t
    }

    /** 编码下拉选择；TXT 文件读取过即用新编码重读（手动兜底链路） */
    fun onEncodingChanged(choice: String) {
        encoding = choice
        if (!isPdf) lastUri?.let { loadFile(it) }
    }

    /** 换识别规则重切（§2.3）；null 恢复内置正则族。失败时保留当前解析结果。
     *  KIT 成品书不参与：章界权威在电脑侧，parse() 分流忽略 regex（重切结果不变） */
    fun reparse(regex: String?) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            cancelFlag.set(false)
            try {
                currentRegex = regex?.takeIf { it.isNotBlank() }
                editedTitles.clear() // 章节列表即将重建，旧 index 改名不可信
                deleted = emptySet()
                drainImportDone()
                parse()
            } catch (e: Exception) {
                error = "重新识别失败：${e.message ?: "未知错误"}"
            } finally {
                busy = false
            }
        }
    }

    fun confirmImport() {
        if (busy) return // 防止双击重复落库
        val list = chapters
        if (list.isEmpty()) return
        // P5-C 删假章：按确认页标记过滤幸存章（deleted 记原始下标）；UI 保证至少留一章，
        // 这里空集兜底拒绝（不落库空书）
        val kept = keptOriginalIndices(list.size, deleted)
        if (kept.isEmpty()) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val now = System.currentTimeMillis()
                val book = BookEntity(
                    title = bookTitle.ifBlank { "未命名" },
                    author = author.trim(),
                    sourceType = sourceType,
                    filePath = sourceUri,
                    status = DbValues.BOOK_READY, // importBook 内按列表统一回填
                    totalChapters = 0,
                    overviewJson = null,
                    createdAt = now,
                    updatedAt = now,
                )
                // P3b-2 目录驱动校准：探针 Done 时先校准（CPU 密集放 Default），成功用校准
                // 产物落库（含节行/重排段落），失败走现状零回归；改名查 editedTitles 须先经
                // keptOriginalIndex 把剔除列表下标翻译回原始下标（P5-C 删假章）
                val keptList = kept.map { list[it] }
                val outcome = withContext(Dispatchers.Default) { runCalibrate(keptList) }
                val (pairs, totalChapters) = when (outcome) {
                    null -> kept.map { oi -> chapterPair(oi, list[oi].title, list[oi]) } to kept.size
                    else -> {
                        val rows = outcome.chapters.map { ch ->
                            val originalIndex = ch.localIndex?.let { keptOriginalIndex(kept, it) }
                            if (ch.localIndex != null && originalIndex == null) {
                                Log.w(
                                    "P5Delete",
                                    "calibrate localIndex ${ch.localIndex} 越界（kept=${kept.size}），「${ch.title}」章名走目录兜底",
                                )
                            }
                            val title = originalIndex?.let { editedTitles[it] } ?: ch.title
                            ChapterEntity(
                                bookId = 0, idx = 0, title = title,
                                readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
                                level = ch.level, parentOrder = ch.parentOrder, calibrated = true,
                            ) to ch.paras.map { p ->
                                ParagraphEntity(
                                    chapterId = 0, idx = 0, text = p.text, role = p.role,
                                    pageNo = p.pageNo,
                                )
                            }
                        }
                        rows to outcome.chapters.count { it.level == 1 }
                    }
                }
                // P4：figures 同事务落库（r9-P2-1）；校准路径用 outcome 起点区间，
                // 现状路径 null（importBook 按每章首段 pageNo 推）
                val figureStartPages = outcome?.chapters?.map { it.startPage }
                val newBookId = repo.importBook(
                    book, pairs,
                    figures = pendingFigures,
                    chapterStartPages = figureStartPages,
                    totalChapters = totalChapters,
                )
                // OPT-F 后台视觉队列；P5-E F3：超上限暂存页 + 同步转写失败页合并去重入队。
                // P4：入队的幸存图页必是第一道闸放行的低质量页 → lowQuality 标记，
                // VisionWorker 第二道闸据此放行（非低质量图页已被闸滤除，防御性双保险）
                val figurePageNos = pendingFigures.map { it.pageNo }.toSet()
                (pendingVisionItems.orEmpty() + failedVisionItems.orEmpty())
                    .distinctBy { it.first }
                    .takeIf { it.isNotEmpty() }
                    ?.let { items ->
                        repo.addVisionQueue(
                            items.map { (pageNo, originChars) ->
                                VisionQueueEntity(
                                    bookId = newBookId,
                                    uri = sourceUri,
                                    pageNo = pageNo,
                                    originChars = originChars,
                                    status = DbValues.VQ_PENDING,
                                    lowQuality = pageNo in figurePageNos,
                                    updatedAt = now,
                                )
                            },
                        )
                        // 批B：未配置视觉时只入库不调度——页挂 PENDING 等「设置保存视觉配置
                        // → reenqueueAllPending」或下次长按重试；Worker 起来也是退避空转
                        if (settings.buildVisionTranscriber() != null) {
                            VisionScheduler.enqueue(getApplication(), newBookId)
                        }
                    }
                imported = true
                Log.i("P5Nav", "① confirm 成功 → imported=true + 事件入队")
                if (!importDone.trySend(Unit).isSuccess) {
                    Log.e("P5Nav", "importDone 事件投递失败（单缓冲满且被占）")
                }
            } catch (e: Exception) {
                error = "保存失败：${e.message ?: "未知错误"}"
            } finally {
                busy = false
            }
        }
    }

    /** 排空残留导入事件（防跨文件串导航：上一次事件未消费时重新解析不应触发旧导航） */
    private fun drainImportDone() {
        while (importDone.tryReceive().isSuccess) { /* 排空即弃 */ }
    }

    /** 回书架后清空流程状态 */
    fun reset() {
        drainImportDone()
        sourceText = null
        kitMeta = null
        sourceType = DbValues.SRC_PASTE
        sourceUri = ""
        lastUri = null
        bookTitle = ""
        author = ""
        encoding = TextLoader.AUTO
        busy = false
        error = null
        parseNote = null
        visionStatsNote = null
        pendingVisionItems = null
        failedVisionItems = null
        lastPdfResult = null
        tocRetryable = false
        clearFigures()
        chapters = emptyList()
        imported = false
        isPdf = false
        currentRegex = null
        progress = null
        phase = null
        cancelFlag.set(false)
        editedTitles.clear()
        deleted = emptySet()
        tocState = TocProbeState.Idle
        probeSegments = null
        clearFrontProbeState()
    }

    /** 现状路径的一章落库对（校准不可用时与 P3b-2 之前行为逐字段一致） */
    private fun chapterPair(
        localIndex: Int,
        title: String,
        ch: ParsedChapter,
    ): Pair<ChapterEntity, List<ParagraphEntity>> =
        ChapterEntity(
            bookId = 0, idx = 0, title = editedTitles[localIndex] ?: title,
            readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
        ) to ch.paras.map { p ->
            ParagraphEntity(
                chapterId = 0, idx = 0, text = p.text, role = p.role,
                pageNo = p.pageNo, // P3b-2 pageNo 链路终点（TXT 恒 null）
            )
        }

    /**
     * 目录驱动校准（P3b-2 方案 Z，confirmImport 时同步读一次状态）：
     * 探针 Done 且 PDF 路径才尝试，页内文本源由 sourceText 的〔页N〕独立行标记切分
     * （与 BookParser 剥标同协议，视觉替换后的内容天然包含在内）；任何前置不满足
     * 返回 null 走现状零回归。校准失败（守卫不过）同样 null。
     */
    private fun runCalibrate(list: List<ParsedChapter>): CalibrateOutcome? {
        val st = tocState as? TocProbeState.Done ?: return null
        val src = sourceText
        val segs = probeSegments
        if (!isPdf || src == null || segs == null) return null
        val re = Regex("^〔页(\\d+)〕$")
        val texts = HashMap<Int, StringBuilder>()
        var cur: Int? = null
        for (line in src.lineSequence()) {
            val m = re.matchEntire(line.trim())
            if (m != null) {
                cur = m.groupValues[1].toInt()
                texts.getOrPut(cur) { StringBuilder() }
            } else {
                cur?.let { texts.getValue(it).appendLine(line) }
            }
        }
        val pageMap = texts.mapValues { it.value.toString() }
        val maxPage = pageMap.keys.maxOrNull() ?: return null
        return TocChapterCalibrator.calibrate(
            entries = st.entries,
            localChapters = list,
            pageText = { p -> pageMap[p] },
            bookPageCount = maxPage,
            tocLastPages = segs.map { s -> s.max() },
        )
    }

    /** 解析 + 兜底提示；CPU 密集放 Default 线程。失败时保留既有 chapters */
    private suspend fun parse() {
        val text = sourceText ?: return
        error = null
        parseNote = null
        phase = "解析章节结构"
        try {
            val kit = kitMeta
            // 解析与锚点对账都是 CPU 密集（锚点要全书逐行正则扫描，几百页数秒级），一并放 Default
            val (result, anchors) = withContext(Dispatchers.Default) {
                if (kit != null) {
                    // KIT 成品书：章界权威在电脑侧章表（bookocr-kit chapters 对账产物），
                    // 正则切章/目录探针/前置分块/盲切全跳过，reparse 的 regex 同样不参与
                    KitBookParser.parse(text, kit) to 0
                } else {
                    // styleAware 与 assembleText 打标同源（isPdf）：PDF=true 认〔标题〕前缀并
                    // 启用字号门槛，TXT=false 完全保持 P2 行为
                    val parsed = BookParser.parse(text, currentRegex, styleAware = isPdf)
                    // P6b S4 章节锚点对账（v1.2 P1-3）：行首「第X章」锚数 vs 检出章节数，
                    // 锚 ≥3 且检出 <70% 追加提示。点线行（目录条目）不计锚，行首锚定滤段中引用；
                    // OCR 拆行致锚少报 → 判据趋保守（少提示），UNMEASURED 待 S7 真书复核。
                    val anchorCount = if (ocrImported) {
                        val anchorRe = Regex("^第[0-9零〇一二三四五六七八九十百千两]+章")
                        val dots = Regex("[…⋯·•‧]")
                        text.lineSequence()
                            .map { it.trim() }
                            .filter { anchorRe.containsMatchIn(it) && !dots.containsMatchIn(it) }
                            .count()
                    } else 0
                    parsed to anchorCount
                }
            }
            if (result.all { it.paras.isEmpty() }) {
                error = "未能解析出任何内容，请检查文件"
                chapters = emptyList()
                return
            }
            // P8 前置分块（仅 PDF 路径）：前置探针页级判定 → 「开篇」按内容拆成带名章
            // （封面/版权页/作者介绍/初版序/第二版序…）；失败/守卫不过零回归
            if (isPdf) phase = "识别书的开头结构"
            val (structured, frontNote) = if (isPdf) applyFrontMatter(result) else result to null
            chapters = structured
            // 兜底提示与视觉统计可同时成立（无标题盲切 + 部分页排队视觉转写），拼接而非互斥
            val blindNote = when {
                // 只凭 blindCut 标记判断兜底态，避免自定义规则命中"全文"时误报
                chapters.first().blindCut && chapters.size == 1 ->
                    "未识别到章节标题，已整本作为一章；可换识别规则重新识别"
                chapters.first().blindCut ->
                    "未识别到章节标题，已按每约 3000 字盲切为 ${chapters.size} 个部分"
                else -> null
            }
            // P4 双层文案（r8-P2-4）：主文案进 parseNote，技术明细走 figureNoteDetail
            val figureNote = FigureParseNote.main(figureStats, figureBypassed)
            // KIT 图包：图清单与 FIGURE 段配对成幸存图（真图文混排），与 PDF 抽图互斥
            val kitFigures = if (kit != null && kitZipFigures.isNotEmpty()) {
                KitFigureMatcher.match(structured, kitZipFigures).also { pendingFigures = it }
            } else {
                emptyList()
            }
            val kitNote = if (kit != null) {
                "bookocr 成品书：章界已由电脑侧确认" +
                    if (kitFigures.isNotEmpty()) "，${kitFigures.size} 张插图原位导入" else ""
            } else {
                null
            }
            parseNote = listOfNotNull(kitNote, blindNote, figureNote, visionStatsNote, frontNote, tocProbeNote)
                .joinToString("；")
                .ifEmpty { null }
            // P6b S4 章节锚点对账（v1.2 P1-3）：行首「第X章」锚数 vs 检出章节数，
            // 锚 ≥3 且检出 <70% 追加提示。点线行（目录条目）不计锚，行首锚定滤段中引用；
            // OCR 拆行致锚少报 → 判据趋保守（少提示），UNMEASURED 待 S7 真书复核。
            if (ocrImported && anchors >= 3 && chapters.size < anchors * 0.7) {
                parseNote = (parseNote?.plus("；") ?: "") +
                    "书中有 $anchors 处「第X章」字样但只解析出 ${chapters.size} 章，可到章节列表核对"
            }
        } catch (e: PatternSyntaxException) {
            error = "识别规则正则无效：${e.description ?: e.message ?: "语法错误"}"
        } catch (e: CustomRegexNoMatchException) {
            error = e.message
        } finally {
            phase = null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cr = getApplication<StudyApp>().contentResolver
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return null
    }

    // ---- P8 前置分块（老板「按内容分块」诉求） ----

    /** 前置探针三态清理（换文件/粘贴/失败/重置）；runTocProbe 入口也调 */
    private fun clearFrontProbeState() {
        frontVerdicts = null
        tocProbeNote = null
        tocFirstPage = null
    }

    /**
     * 前置分块主入口：圈前置页（目录首页/文本侧首个正文章起点之前，≤24 页）→ 视觉
     * 判定每页属于书的哪部分 → 守卫 → 「开篇」按块拆成带名 FRONT 章。
     * 探针失败/守卫不过 → 原样返回（零回归）；同书重切复用已缓存判定不重烧。
     */
    private suspend fun applyFrontMatter(parsed: List<ParsedChapter>): Pair<List<ParsedChapter>, String?> {
        val uri = lastUri ?: return parsed to null
        // 圈页：前置区 = 1..(frontEnd-1)。frontEnd 优先目录首页（最可靠的结构边界）；
        // 无目录取文本侧首个正文章起点；盲切书（标题全丢）兜底探前 24 页
        val blind = parsed.firstOrNull()?.blindCut == true
        val textBodyStart = if (blind) null else parsed
            .firstOrNull { ch -> ch.paras.any { it.role == DbValues.ROLE_BODY } }
            ?.paras?.firstNotNullOfOrNull { it.pageNo }
        val frontEnd = tocFirstPage ?: textBodyStart ?: 25
        val frontPages = (1 until frontEnd.coerceIn(2, 25)).toList()
        if (frontPages.size < 2) return parsed to null

        val verdicts = frontVerdicts ?: runFrontProbe(uri, frontPages)?.also { frontVerdicts = it }
        if (verdicts == null) return parsed to null

        // 守卫：探针判的正文起点 vs 文本侧首个正文章起点差 >2 页 = 选页/误判，整体弃用
        val probeBodyStart = verdicts.firstOrNull { it.kind == FrontKinds.BODY }?.page
        if (textBodyStart != null && probeBodyStart != null && abs(probeBodyStart - textBodyStart) > 2) {
            Log.w("P8", "front probe guard fail: probeBody=$probeBodyStart textBody=$textBodyStart")
            return parsed to null
        }
        // 目录漏判兜底：文本侧没发现目录页但探针看到目录页 → 补跑目录探针（P8）
        if (tocFirstPage == null && tocState is TocProbeState.Failed) {
            supplementTocProbe(uri, verdicts)
        }
        val boundary = verdicts.firstOrNull { it.kind == FrontKinds.TOC || it.kind == FrontKinds.BODY }?.page
            ?: frontEnd
        val blocks = FrontMatterBlocks.build(verdicts.filter { it.page < boundary })
        if (blocks.isEmpty()) return parsed to null
        return rebuildWithFrontBlocks(parsed, blocks, boundary) ?: (parsed to null)
    }

    /** 前置探针网络入口：视觉不可用/任何失败 → null（回退纯文本路线） */
    private suspend fun runFrontProbe(uri: Uri, pages: List<Int>): List<FrontPageVerdict>? {
        return try {
            PdfPageRenderer(getApplication(), uri).use { renderer ->
                val parser = settings.buildFrontMatterVisionParser(
                    File(getApplication<Application>().cacheDir, "vision_cache/frontmatter"),
                ) { pageNo -> renderer.renderPageBase64(pageNo - 1) } ?: return null
                parser.probe(pages, VisionCache.uriHash(uri.toString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("P8", "front probe failed: ${e.message}")
            null
        }
    }

    /**
     * 目录漏判兜底（P8）：前置探针发现目录页而 PdfCleaner 三判据全漏（tocState Failed）
     * → 用这些页补跑目录探针；成功则升级 tocState=Done（confirmImport 校准消费）。
     * 探针自带缓存/marker，同书重导不重烧；任何失败保持 Failed（原行为）。
     */
    private suspend fun supplementTocProbe(uri: Uri, verdicts: List<FrontPageVerdict>) {
        val tocPages = verdicts.mapNotNull { v -> v.page.takeIf { v.kind == FrontKinds.TOC } }
        if (tocPages.isEmpty()) return
        try {
            PdfPageRenderer(getApplication(), uri).use { renderer ->
                val parser = settings.buildTocVisionParser(
                    File(getApplication<Application>().cacheDir, "vision_cache/toc"),
                ) { pageNo -> renderer.renderPageBase64(pageNo - 1) } ?: return
                val entries = parser.parse(tocPages, VisionCache.uriHash(uri.toString())) ?: return
                tocState = TocProbeState.Done(entries)
                probeSegments = listOf(tocPages)
                tocFirstPage = tocPages.min()
                tocProbeNote = "已识别目录（开头结构探针发现目录页），导入时将校准章节"
                Log.i("P8", "toc supplement: ${entries.size} entries from pages $tocPages")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("P8", "toc supplement failed: ${e.message}")
        }
    }

    /**
     * 头部章（首段页号 < [boundary] 的章）段落按块页区间重新分箱成带名 FRONT 章；
     * 块盖不住的段（页码 null/块前杂页）落「开篇」。无可重组内容 → null（零回归）。
     * 原「开篇」/文本规则切出的序言章被块替代，不产生重复。
     */
    private fun rebuildWithFrontBlocks(
        parsed: List<ParsedChapter>,
        blocks: List<FrontBlock>,
        boundary: Int,
    ): Pair<List<ParsedChapter>, String?>? {
        val headChapters = mutableListOf<ParsedChapter>()
        val tailChapters = mutableListOf<ParsedChapter>()
        var tailStarted = false
        for (ch in parsed) {
            val firstPage = ch.paras.firstNotNullOfOrNull { it.pageNo }
            if (!tailStarted && firstPage != null && firstPage >= boundary) tailStarted = true
            if (tailStarted) tailChapters += ch else headChapters += ch
        }
        val headParas = headChapters.flatMap { it.paras }
        val blockChapters = blocks.mapNotNull { b ->
            val paras = headParas
                .filter { p -> p.pageNo != null && p.pageNo in b.startPage..b.endPage }
                .map { it.copy(role = DbValues.ROLE_FRONT) }
            if (paras.isEmpty()) null else ParsedChapter(b.title, paras)
        }
        if (blockChapters.isEmpty()) return null
        val leftover = headParas
            .filter { p -> p.pageNo == null || blocks.none { b -> p.pageNo in b.startPage..b.endPage } }
            .map { it.copy(role = DbValues.ROLE_FRONT) }
        val openChapter = if (leftover.any { it.text.isNotBlank() }) {
            listOf(ParsedChapter("开篇", leftover))
        } else emptyList()
        val out = openChapter + blockChapters + tailChapters
        val names = (openChapter + blockChapters).joinToString("、") { it.title }
        return out to "开头部分已按内容分块：${names.take(40)}"
    }

    companion object {
        private const val IMPORT_CHANNEL = "import_progress"
        private const val IMPORT_NOTIF_ID = 0x1AB1
    }
}
