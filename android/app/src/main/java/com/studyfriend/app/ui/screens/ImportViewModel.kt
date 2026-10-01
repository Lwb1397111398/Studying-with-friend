package com.studyfriend.app.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import com.studyfriend.app.data.importer.pdfpipeline.VisionTranscriber
import com.studyfriend.app.data.importer.pdfpipeline.joinTexts
import com.studyfriend.app.data.db.VisionQueueEntity
import com.studyfriend.app.data.vision.VisionCache
import com.studyfriend.app.data.vision.VisionScheduler
import java.util.regex.PatternSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * 超单次上限的可疑页暂存（OPT-F）：页号 to 清洗后字数。导入不再拒绝，
     * 文字层先行；确认落库后写 vision_queue 交 WorkManager 后台逐页转写。
     */
    private var pendingVisionItems: List<Pair<Int, Int>>? = null
    /** 改名按 index 记；换文件/重切会清空，避免错位串到别的章 */
    private val editedTitles = mutableStateMapOf<Int, String>()

    fun chapterTitleAt(index: Int): String =
        editedTitles[index] ?: chapters.getOrNull(index)?.title.orEmpty()

    fun rename(index: Int, title: String) {
        if (title.isNotBlank()) editedTitles[index] = title.trim()
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
                visionStatsNote = null
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
                        content = result.assembleText()
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
        isPdf = false
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
                val pairs = list.mapIndexed { ci, ch ->
                    ChapterEntity(
                        bookId = 0, idx = 0, title = editedTitles[ci] ?: ch.title,
                        readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
                    ) to ch.paras.map { p ->
                        ParagraphEntity(
                            chapterId = 0, idx = 0, text = p.text, role = p.role,
                        )
                    }
                }
                val newBookId = repo.importBook(book, pairs)
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
    }

    /** 解析 + 兜底提示；CPU 密集放 Default 线程。失败时保留既有 chapters */
    private suspend fun parse() {
        val text = sourceText ?: return
        error = null
        parseNote = null
        phase = "解析章节结构"
        try {
            val result = withContext(Dispatchers.Default) {
                BookParser.parse(text, currentRegex)
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
