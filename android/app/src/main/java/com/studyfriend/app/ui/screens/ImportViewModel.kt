package com.studyfriend.app.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import com.studyfriend.app.data.importer.ParsedChapter
import com.studyfriend.app.data.importer.PdfExtractResult
import com.studyfriend.app.data.importer.PdfImportException
import com.studyfriend.app.data.importer.PdfLoader
import com.studyfriend.app.data.importer.PdfPageRenderer
import com.studyfriend.app.data.importer.TextLoader
import com.studyfriend.app.data.importer.pdfpipeline.PageSelector
import com.studyfriend.app.data.importer.pdfpipeline.PageTranscription
import com.studyfriend.app.data.importer.pdfpipeline.Para
import com.studyfriend.app.data.importer.pdfpipeline.CalibrateOutcome
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

    private val repo = BookRepository((app as StudyApp).database)
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
    var chapters by mutableStateOf<List<ParsedChapter>>(emptyList())
        private set
    var imported by mutableStateOf(false)
        private set
    var isPdf by mutableStateOf(false)
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

    private var sourceText: String? = null
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
    private var probeSegments: List<List<Int>>? = null    /**
     * 超单次上限的可疑页暂存（OPT-F）：页号 to 清洗后字数。导入不再拒绝，
     * 文字层先行；确认落库后写 vision_queue 交 WorkManager 后台逐页转写。
     */
    private var pendingVisionItems: List<Pair<Int, Int>>? = null
    /** 改名按 index 记；换文件/重切会清空，避免错位串到别的章 */
    private val editedTitles = mutableStateMapOf<Int, String>()

    /** P5-C 删假章：用户标记删除的原始下标集合（列表不物理删除，已删行整行点击恢复）。
     *  与 editedTitles 同步清空（章列表重建处旧下标不可信）；确认导入时才过滤落库 */
    var deleted by mutableStateOf(setOf<Int>())
        private set

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
                currentRegex = null
                editedTitles.clear()
                deleted = emptySet()
                visionStatsNote = null
                tocState = TocProbeState.Idle // 粘贴路径无探针；清掉上一本 PDF 可能残留的目录
                probeSegments = null
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
                // 新文件开始先清上一本的探针态（PDF 路径 runTocProbe 会重跑；TXT 保持 Idle）
                tocState = TocProbeState.Idle
                probeSegments = null
                // IO：文件名/mime 查询 + 解码/PDF 提取都在 IO 线程，结果回主线程赋值
                val (name, pdf, text) = withContext(Dispatchers.IO) {
                    val displayName = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
                    val isPdfFile = displayName.endsWith(".pdf", ignoreCase = true) ||
                        app.contentResolver.getType(uri) == "application/pdf"
                    val content: String
                    if (isPdfFile) {
                        phase = "提取 PDF 文字"
                        val vision = settings.buildVisionTranscriber()
                        val result = PdfLoader.extract(
                            app, uri,
                            onProgress = { p, t -> progress = p to t },
                            isCancelled = { cancelFlag.get() },
                            allowScanned = vision != null,
                        )
                        applyVision(result, uri, vision)
                        runTocProbe(result, uri)
                        // P3a 字号证据链：PDF 路径打〔标题〕前缀，parse 侧按 isPdf 同步认标
                        content = result.assembleText(styleAware = true)
                    } else {
                        phase = "读取文件"
                        content = TextLoader.decode(
                            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: ByteArray(0),
                            encoding,
                        ).text
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
                if (bookTitle.isBlank()) {
                    bookTitle = name.substringBeforeLast('.').ifBlank { "未命名" }
                }
                parse()
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

    /** 读取失败：清掉上一次文件的解析结果，避免把旧章节误导入 */
    private fun failRead(message: String?) {
        error = message
        sourceText = null
        parseNote = null
        chapters = emptyList()
        deleted = emptySet()
        isPdf = false
        tocState = TocProbeState.Idle
        probeSegments = null
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
        val selected = when (val sel = PageSelector.select(result.pages, scanned = result.scanned)) {
            is PageSelector.Selection.Pages -> sel.pages
            is PageSelector.Selection.TooMany -> {
                if (sel.pages.size > VisionScheduler.MAX_QUEUE_PAGES) throw PdfImportException(
                    "需要视觉识别的页面太多（${sel.totalPages} 页，超过后台队列上限）；请把文件拆小后分批导入",
                )
                pendingVisionItems = sel.pages.map { pageNo ->
                    val page = result.pages.first { it.pageNum == pageNo }
                    pageNo to page.paras.sumOf { it.text.length }
                }
                visionStatsNote =
                    "检测到 ${sel.pages.size} 页画质可疑，先用文字层内容导入；" +
                        "完成导入后 App 会在后台自动视觉增强这些页（每页约 1 分钟，可正常阅读，无需等待）"
                return
            }
            PageSelector.Selection.None -> return
        }
        if (selected.isEmpty() || vision == null) return
        var replaced = 0
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
                    }
                }
            }
            visionStatsNote = "视觉转写替换了 $replaced/${selected.size} 页"
        } catch (e: CancelledImportException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            visionStatsNote = "视觉转写未完成，已保留文字层内容：${e.message ?: "未知错误"}"
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
        try {
            val tocSegments = TocPageGrouper.group(result.pages.filter { it.tocLike }.map { it.pageNum })
            if (tocSegments.isEmpty()) {
                Log.i("P3b", "no tocLike pages")
                tocState = TocProbeState.Failed
                return
            }
            probeSegments = tocSegments
            Log.i("P3b", "toc probe segments=$tocSegments")
            tocState = TocProbeState.Running
            PdfPageRenderer(getApplication(), uri).use { renderer ->
                val parser = settings.buildTocVisionParser(
                    File(getApplication<Application>().cacheDir, "vision_cache/toc"),
                ) { pageNo -> renderer.renderPageBase64(pageNo - 1) }
                if (parser == null) {
                    Log.i("P3b", "vision unavailable, toc probe skipped")
                    tocState = TocProbeState.Failed
                    return
                }
                val entries = parser.parseSegments(tocSegments, VisionCache.uriHash(uri.toString()))
                if (entries == null) {
                    Log.i("P3b", "toc probe: no entries (failed, budget exhausted or cached failure)")
                    tocState = TocProbeState.Failed
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

    /** 换识别规则重切（§2.3）；null 恢复内置正则族。失败时保留当前解析结果 */
    fun reparse(regex: String?) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            cancelFlag.set(false)
            try {
                currentRegex = regex?.takeIf { it.isNotBlank() }
                editedTitles.clear() // 章节列表即将重建，旧 index 改名不可信
                deleted = emptySet()
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
                val newBookId = repo.importBook(book, pairs, totalChapters)
                // OPT-F：后台视觉队列——超上限页已文字层入库，这里落队列并交 WorkManager
                pendingVisionItems?.takeIf { it.isNotEmpty() }?.let { items ->
                    repo.addVisionQueue(
                        items.map { (pageNo, originChars) ->
                            VisionQueueEntity(
                                bookId = newBookId,
                                uri = sourceUri,
                                pageNo = pageNo,
                                originChars = originChars,
                                status = DbValues.VQ_PENDING,
                                updatedAt = now,
                            )
                        },
                    )
                    VisionScheduler.enqueue(getApplication(), newBookId)
                }
                imported = true
            } catch (e: Exception) {
                error = "保存失败：${e.message ?: "未知错误"}"
            } finally {
                busy = false
            }
        }
    }

    /** 回书架后清空流程状态 */
    fun reset() {
        sourceText = null
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
            val result = withContext(Dispatchers.Default) {
                // styleAware 与 assembleText 打标同源（isPdf）：PDF=true 认〔标题〕前缀并
                // 启用字号门槛，TXT=false 完全保持 P2 行为
                BookParser.parse(text, currentRegex, styleAware = isPdf)
            }
            if (result.all { it.paras.isEmpty() }) {
                error = "未能解析出任何内容，请检查文件"
                chapters = emptyList()
                return
            }
            chapters = result
            // 兜底提示与视觉统计可同时成立（无标题盲切 + 部分页排队视觉转写），拼接而非互斥
            val blindNote = when {
                // 只凭 blindCut 标记判断兜底态，避免自定义规则命中"全文"时误报
                result.first().blindCut && result.size == 1 ->
                    "未识别到章节标题，已整本作为一章；可换识别规则重新识别"
                result.first().blindCut ->
                    "未识别到章节标题，已按每约 3000 字盲切为 ${result.size} 个部分"
                else -> null
            }
            parseNote = when {
                blindNote != null && visionStatsNote != null -> "$blindNote；$visionStatsNote"
                else -> blindNote ?: visionStatsNote
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
}
