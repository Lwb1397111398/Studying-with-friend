package com.studyfriend.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.PromptLoader
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.FigureEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.study.KeyTermsCodec
import com.studyfriend.app.data.study.NotePlanner
import com.studyfriend.app.data.study.NoteRunState
import com.studyfriend.app.data.study.ParaIdsCodec
import com.studyfriend.app.data.study.QuickQa
import com.studyfriend.app.data.study.RoughRunState
import com.studyfriend.app.data.study.decodeStringList
import com.studyfriend.app.data.study.enumerateUnits
import com.studyfriend.app.ui.components.MdText
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 长按划词选中态：段 id + 字符区间（闭）+ 浮条锚点（window 坐标） */
internal data class TextSelection(val paraId: Long, val range: IntRange, val anchor: IntOffset)

/** 打开随手问弹层的输入：问答上下文 + 首问 */
internal data class QaTarget(val ctx: QuickQa.Ctx, val defaultQuestion: String)

/**
 * 阅读页（M4a 计划 §3 + 随手问轮 P7 重排）：顶部一章三件套（标题/章间切换/字号）+
 * 工具 chips 行（粗读/讲解/总结/要点，按需展开状态行）+ 段落流（无分隔线、留白分段、
 * 可调字号）；正文长按取词 → 浮条问含义/问整段/复制 → 底部流式问答卡（不走全局互斥）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReadScreen(
    chapterId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSummary: () -> Unit = {},
    onOpenChapter: (Long) -> Unit = {},
    /** P5 章节树节行锚：非空时滚动定位到节标题段（匹配失败静默不滚动，见 findHighlightIndex） */
    highlight: String? = null,
) {
    val app = LocalContext.current.applicationContext as StudyApp
    val chapter by app.database.chapterDao().byIdFlow(chapterId)
        .collectAsStateWithLifecycle(initialValue = null)
    val paragraphs by app.database.paragraphDao().byChapterFlow(chapterId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // P4 §E：本章幸存图（页号→页内锚→同锚 y 序）；无图书恒空列表，渲染零影响（J5）
    val figures by app.database.figureDao().byChapterFlow(chapterId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val runState by app.roughReadRunner.state.collectAsStateWithLifecycle()
    val notes by app.database.paraNoteDao().byChapterFlow(chapterId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val noteState by app.noteRunner.state.collectAsStateWithLifecycle()
    val gateLabel by app.aiGate.label.collectAsStateWithLifecycle()

    // P7 阅读字号（四档，settings 记忆）与字号弹层
    var readFontSize by remember { mutableIntStateOf(SettingsRepository.DEFAULT_READ_FONT_SIZE) }
    var showFontSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { readFontSize = app.settingsRepo.readFontSize() }
    val scope = rememberCoroutineScope()

    // P7 划词与随手问：selection 驱动高亮+浮条；qaTarget 非空开问答弹层
    var selection by remember { mutableStateOf<TextSelection?>(null) }
    var qaTarget by remember { mutableStateOf<QaTarget?>(null) }
    // P7 要点卡折叠（默认收起，「要点」chip 开关）
    var gistExpanded by remember { mutableStateOf(false) }

    // P4 §E：图片文件根（filesDir/figures/{bookId}/…）与全屏预览状态。
    // 预览挂顶层（r5-P2-10）：Dialog 不进 LazyColumn，开关不重建列表，关闭即回原滚动位
    val filesRoot = app.filesDir
    var previewFigure by remember { mutableStateOf<FigureEntity?>(null) }
    val clipboard = LocalClipboardManager.current

    // 浮条 offset 相对根 Box；Text 上报的是 window 坐标，根 Box 原点也要记一份换算
    var rootOrigin by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.boundsInWindow().topLeft.round() },
    ) {
        Column(Modifier.fillMaxSize()) {
            // 上/下章切换（M4a §3.4）：按同书章序跳转，边界章禁用
            val bookId = chapter?.bookId
            // lint 误报：lambda 内有显式 value 赋值，但规则对带 keys 重载的顶层赋值不识别（嵌套 collect 形态才识别）
            @Suppress("ProduceStateDoesNotAssignValue")
            val siblings by produceState<List<ChapterEntity>>(emptyList(), bookId) {
                val list: List<ChapterEntity> =
                    if (bookId == null) emptyList() else app.database.chapterDao().byBook(bookId)
                value = list
            }
            val prevChapter = siblings.lastOrNull { it.idx < (chapter?.idx ?: 0) }
            val nextChapter = siblings.firstOrNull { it.idx > (chapter?.idx ?: 0) }
            // P4 v1.11（r11-P2-2）：该书已完成 vision 替换的页号集合——FigureRow 命中时 Log.w 定位锚定偏差
            @Suppress("ProduceStateDoesNotAssignValue")
            val visionDonePages by produceState<Set<Int>>(emptySet(), bookId) {
                value = if (bookId == null) {
                    emptySet()
                } else {
                    app.database.visionQueueDao().donePageNosByBook(bookId).toSet()
                }
            }

            TopAppBar(
                title = {
                    Text(
                        chapter?.title.orEmpty().ifBlank { "阅读" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        enabled = prevChapter != null,
                        onClick = { prevChapter?.let { onOpenChapter(it.id) } },
                    ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上一章") }
                    IconButton(
                        enabled = nextChapter != null,
                        onClick = { nextChapter?.let { onOpenChapter(it.id) } },
                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下一章") }
                    // P7 阅读字号调节
                    IconButton(onClick = { showFontSheet = true }) {
                        Text(
                            "Aa",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )

            // OPT-C C4：计数用正文视图（TOC/脚注段永远无标注，混入计数会让"部分标注"态无法收敛）；
            // LazyColumn 渲染仍用全量列表（目录条目/脚注按次级样式展示）
            val bodyParagraphs = remember(paragraphs) {
                paragraphs.filter { it.role != DbValues.ROLE_TOC && it.role != DbValues.ROLE_FOOTNOTE }
            }
            val markedCount = bodyParagraphs.count { it.aiAction != DbValues.ACT_NONE }

            // 讲解单元与 note 映射（原 NoteBar 内部推导上提）：卡片只渲染在锚段下（M4b §3.2）
            val units = remember(paragraphs) { enumerateUnits(paragraphs) }
            val anchorById = remember(units) { units.associateBy { it.anchor.id } }
            val unitParaIds = remember(units) {
                units.map { ParaIdsCodec.encode(it.members.map { m -> m.id }) }.toSet()
            }
            val noteValidCount = notes.count {
                it.paraIds in unitParaIds && it.promptVersion == NotePlanner.NOTE_VERSION && it.friendly.isNotBlank()
            }
            // 讲解进行中（本章）：讲解卡占位用；委托属性不能 smart cast，用 as? 判
            val noteRunningThis = (noteState as? NoteRunState.Running)?.chapterId == chapterId
            val notesByParaIds = remember(notes) { notes.associateBy { it.paraIds } }

            ChapterToolsRow(
                chapterId = chapterId,
                hasGist = !chapter?.gist.isNullOrBlank(),
                gistExpanded = gistExpanded,
                onToggleGist = { gistExpanded = !gistExpanded },
                readDone = chapter?.readState == DbValues.READ_DONE,
                paragraphCount = bodyParagraphs.size,
                pureToc = bodyParagraphs.isEmpty() && paragraphs.isNotEmpty(),
                markedCount = markedCount,
                runState = runState,
                noteState = noteState,
                gateLabel = gateLabel,
                noteUnitCount = units.size,
                noteValidCount = noteValidCount,
                onStart = { app.roughReadRunner.start(chapterId, force = false) },
                onForce = { app.roughReadRunner.start(chapterId, force = true) },
                onMerge = { app.roughReadRunner.mergeOnly(chapterId) },
                onStop = { app.roughReadRunner.stop() },
                onNoteGenerate = { app.noteRunner.start(chapterId, force = false) },
                onNoteForce = { app.noteRunner.start(chapterId, force = true) },
                onNoteStop = { app.noteRunner.stop() },
                onMarkDone = {
                    // 应用级 scope：用户点完立即返回上一页也不会取消写库
                    app.appScope.launch { app.database.chapterDao().updateReadState(chapterId, DbValues.READ_DONE) }
                },
                onOpenSettings = onOpenSettings,
                onOpenSummary = onOpenSummary,
            )

            if (paragraphs.isEmpty()) {
                EmptyState(title = "本章还没有内容", subtitle = "段落解析可能没有完成")
                return@Column
            }

            // P4 §E：段落流混排——图行按 ordAfterPara 插进段序列（buildReadRows 三态口径）
            val rows = remember(paragraphs, figures) { buildReadRows(paragraphs, figures) }
            val listState = rememberLazyListState()
            // 滚动即取消划词：浮条锚点是 window 坐标，滚动后必然错位
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress) selection = null
            }
            // P5 highlight 定位：段落异步到达后滚到节标题段（v1 只滚动不做高亮底色）；
            // 混排后 LazyColumn 下标 ≠ 段落下标，经 paraId 反查行下标
            LaunchedEffect(highlight, rows) {
                if (highlight != null && rows.isNotEmpty()) {
                    findHighlightIndex(paragraphs, highlight)?.let { paraIdx ->
                        val pid = paragraphs.getOrNull(paraIdx)?.id
                        if (pid != null) {
                            rows.indexOfFirst { it is ReadRow.Para && it.p.id == pid }
                                .takeIf { it >= 0 }
                                ?.let { listState.animateScrollToItem(it) }
                        }
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // P7 要点卡（折叠）：默认收起，「要点」chip 展开；随正文滚动不占固定空间
                if (gistExpanded && !chapter?.gist.isNullOrBlank()) {
                    item(key = "gist-card") {
                        GistCard(
                            gist = chapter?.gist.orEmpty(),
                            keyTermsJson = chapter?.keyTermsJson,
                        )
                    }
                }
                itemsIndexed(rows, key = { _, row ->
                    when (row) {
                        // 图行 itemKey 用 figure.id（P1-5）：ord 拼 key 同页多图同锚会碰撞
                        is ReadRow.Para -> "p${row.p.id}"
                        is ReadRow.Fig -> "f${row.f.id}"
                    }
                }) { _, row ->
                    when (row) {
                        is ReadRow.Para -> {
                            ParaRow(
                                p = row.p,
                                fontSize = readFontSize,
                                selectedRange = selection?.takeIf { it.paraId == row.p.id }?.range,
                                onLongPress = { wordRange, anchor ->
                                    selection = TextSelection(row.p.id, wordRange, anchor)
                                },
                            )
                            val unit = anchorById[row.p.id]
                            if (unit != null) {
                                val note = notesByParaIds[ParaIdsCodec.encode(unit.members.map { it.id })]
                                    ?.takeIf { it.promptVersion == NotePlanner.NOTE_VERSION && it.friendly.isNotBlank() }
                                NoteCard(note = note, generating = note == null && noteRunningThis)
                            }
                            // P7：段落分隔线删除，靠段间距留白分段
                        }
                        is ReadRow.Fig -> FigureRow(
                            f = row.f,
                            filesRoot = filesRoot,
                            visionDonePages = visionDonePages,
                            onOpenPreview = { previewFigure = row.f },
                        )
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }

        // 划词浮条：选中段仍存在且区间合法才显示（段落 Flow 刷新后失效自动消失）
        selection?.let { sel ->
            val selPara = paragraphs.firstOrNull { it.id == sel.paraId }
            if (selPara != null && selPara.text.isNotEmpty() && sel.range.last < selPara.text.length) {
                SelectionFloatBar(
                    anchor = sel.anchor,
                    boxOrigin = rootOrigin,
                    onMeaning = {
                        val target = buildQaTarget(
                            chapter = chapter,
                            paragraphs = paragraphs,
                            para = selPara,
                            selectionText = selPara.text.substring(sel.range.first, sel.range.last + 1),
                            question = QuickQa.ASK_MEANING,
                        )
                        qaTarget = target
                        selection = null
                    },
                    onPara = {
                        val target = buildQaTarget(
                            chapter = chapter,
                            paragraphs = paragraphs,
                            para = selPara,
                            selectionText = selPara.text,
                            question = QuickQa.ASK_PARA,
                        )
                        qaTarget = target
                        selection = null
                    },
                    onCopy = {
                        clipboard.setText(AnnotatedString(selPara.text.substring(sel.range.first, sel.range.last + 1)))
                        selection = null
                    },
                    onDismiss = { selection = null },
                )
            }
        }
    }

    if (showFontSheet) {
        FontSizeSheet(
            current = readFontSize,
            onPick = { size ->
                readFontSize = size
                showFontSheet = false
                scope.launch { app.settingsRepo.saveReadFontSize(size) }
            },
            onDismiss = { showFontSheet = false },
        )
    }

    qaTarget?.let { target ->
        QuickQaSheet(
            ctx = target.ctx,
            defaultQuestion = target.defaultQuestion,
            onDismiss = { qaTarget = null },
            onOpenSettings = onOpenSettings,
        )
    }

    previewFigure?.let { f ->
        FigurePreviewDialog(f = f, filesRoot = filesRoot, onDismiss = { previewFigure = null })
    }
}

/** 随手问上下文组装：章标题/要点 + 选中文字 + 所在段全文 + 前后段（截断在 QuickQa 内统一做） */
internal fun buildQaTarget(
    chapter: ChapterEntity?,
    paragraphs: List<ParagraphEntity>,
    para: ParagraphEntity,
    selectionText: String,
    question: String,
): QaTarget = QaTarget(
    ctx = QuickQa.Ctx(
        chapterTitle = chapter?.title.orEmpty(),
        gist = chapter?.gist,
        selection = selectionText,
        paragraph = para.text,
        prev = paragraphs.firstOrNull { it.idx == para.idx - 1 }?.text,
        next = paragraphs.firstOrNull { it.idx == para.idx + 1 }?.text,
    ),
    defaultQuestion = question,
)

/**
 * 章节「工具 chips 行」（P7 收纳，合并原 ActionBar + NoteBar + 总结按钮）：
 * 一行小 chip（粗读/讲解/总结/要点）常驻，只有「有事发生」（进行中/中断/失败/待归并/
 * 讲解中断/占用提示）时下方才出现状态行；稳态只剩「标记读完」一行小字。
 * 全部状态推导表达式与原七态 ActionBar/NoteBar 一致，仅布局压缩。
 */
@Composable
private fun ChapterToolsRow(
    chapterId: Long,
    hasGist: Boolean,
    gistExpanded: Boolean,
    onToggleGist: () -> Unit,
    readDone: Boolean,
    paragraphCount: Int,
    pureToc: Boolean,
    markedCount: Int,
    runState: RoughRunState,
    noteState: NoteRunState,
    gateLabel: String?,
    noteUnitCount: Int,
    noteValidCount: Int,
    onStart: () -> Unit,
    onForce: () -> Unit,
    onMerge: () -> Unit,
    onStop: () -> Unit,
    onNoteGenerate: () -> Unit,
    onNoteForce: () -> Unit,
    onNoteStop: () -> Unit,
    onMarkDone: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSummary: () -> Unit,
) {
    val runningThis = runState is RoughRunState.Running && runState.chapterId == chapterId
    val runningOther = runState is RoughRunState.Running && runState.chapterId != chapterId
    val succeeded = runState is RoughRunState.Succeeded && runState.chapterId == chapterId
    val interrupted = runState is RoughRunState.Interrupted && runState.chapterId == chapterId
    val failed = runState is RoughRunState.Failed && runState.chapterId == chapterId

    val allMarked = paragraphCount > 0 && markedCount == paragraphCount
    val partiallyMarked = markedCount in 1 until paragraphCount

    val noteRunningThis = noteState is NoteRunState.Running && noteState.chapterId == chapterId
    val roughBusy = runState is RoughRunState.Running
    val noteBusy = (gateLabel != null && !noteRunningThis) || roughBusy

    // force 重读/重生成会覆盖既有标注：二次确认防误触（原 ActionBar/NoteBar 保留）
    var showRoughForceConfirm by remember { mutableStateOf(false) }
    var showNoteForceConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        // 工具 chips 行：横向可滚（窄屏四个 chip 放得下，保险起见可滚）
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 粗读 chip：未开始→粗读；断点→继续 x/y；已标完→重读（force 确认）；进行中→灰显进度
            val roughLabel = when {
                pureToc || paragraphCount == 0 -> "目录章"
                runningThis -> "粗读 ${(runState as RoughRunState.Running).done}/${runState.total}"
                allMarked -> "重读"
                partiallyMarked -> "继续 $markedCount/$paragraphCount"
                else -> "粗读"
            }
            AssistChip(
                onClick = { if (allMarked) showRoughForceConfirm = true else onStart() },
                enabled = paragraphCount > 0 && !pureToc && !roughBusy && !runningOther,
                label = { Text(roughLabel) },
            )
            // 讲解 chip：无可讲解单元（纯目录章等）不显示
            if (noteUnitCount > 0) {
                val noteLabel = when {
                    noteRunningThis -> "讲解 ${(noteState as NoteRunState.Running).done}/${noteState.total}"
                    noteValidCount == noteUnitCount -> "讲解 ✓"
                    noteValidCount > 0 -> "讲解 $noteValidCount/$noteUnitCount"
                    else -> "讲解 $noteUnitCount"
                }
                AssistChip(
                    onClick = {
                        if (noteValidCount == noteUnitCount) showNoteForceConfirm = true else onNoteGenerate()
                    },
                    enabled = !noteBusy,
                    label = { Text(noteLabel) },
                )
            }
            // 章末总结入口：本章已粗读标注即可进入（M5 §3.7）
            if (markedCount > 0) {
                AssistChip(onClick = onOpenSummary, label = { Text("总结") })
            }
            // 要点卡开关：本章要点存在才显示
            if (hasGist) {
                AssistChip(onClick = onToggleGist, label = { Text(if (gistExpanded) "收起要点" else "要点") })
            }
        }

        // 状态行：只在有事发生时出现（P7 收纳核心）
        when {
            runningThis -> {
                val r = runState as RoughRunState.Running
                TaskProgressRow(
                    label = "搭子正在粗读 ${r.done}/${r.total}",
                    progress = if (r.total > 0) r.done.toFloat() / r.total else 0f,
                    onStop = onStop,
                )
            }

            noteRunningThis -> {
                val n = noteState as NoteRunState.Running
                TaskProgressRow(
                    label = "搭子正在讲解 ${n.done}/${n.total}",
                    progress = if (n.total > 0) n.done.toFloat() / n.total else 0f,
                    onStop = onNoteStop,
                )
            }

            interrupted -> {
                ToolStatusRow {
                    Text(
                        (runState as RoughRunState.Interrupted).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart) { Text("从断点继续") }
                        OutlinedButton(onClick = { showRoughForceConfirm = true }) { Text("全部重读") }
                    }
                }
            }

            failed -> {
                val failure = runState as RoughRunState.Failed
                ToolStatusRow {
                    Text(failure.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onStart) { Text("重试") }
                        if (failure.keyIssue) {
                            TextButton(onClick = onOpenSettings) { Text("去设置") }
                        }
                    }
                }
            }

            succeeded -> {
                val success = runState as RoughRunState.Succeeded
                ToolStatusRow {
                    Text(
                        success.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (readDone) {
                            Text("已读完", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        } else {
                            TextButton(onClick = onMarkDone) { Text("标记本章读完") }
                        }
                        if (success.degraded) {
                            // 降级要点可只补一次归并升级为真要点，不必全量重跑
                            OutlinedButton(onClick = onMerge, enabled = !runningOther) { Text("重试归并") }
                        }
                    }
                }
            }

            // 全部已标但没要点：只补一次归并，不必重跑块（质检 P1-2）
            allMarked && !hasGist -> {
                ToolStatusRow {
                    Text(
                        "段落标注已完成，还没有生成本章要点",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onMerge, enabled = !runningOther) { Text("完成归并") }
                        OutlinedButton(onClick = { showRoughForceConfirm = true }, enabled = !runningOther) { Text("重读") }
                    }
                }
            }

            // 讲解中断原因小字（质检 P2-4 保留）：无粗读态抢占时显示
            noteState is NoteRunState.Interrupted && noteState.chapterId == chapterId -> {
                ToolStatusRow {
                    Text(
                        noteState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 另一项 AI 任务占用（总结/总览）：粗读进度行已在上面的分支覆盖粗读占用
            gateLabel != null && !noteRunningThis && !roughBusy -> {
                ToolStatusRow {
                    Text(
                        "另一项 AI 任务进行中，请稍候",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 稳态（全部已标且有要点）：只剩标记读完入口
            allMarked && hasGist -> {
                ToolStatusRow {
                    if (readDone) {
                        Text("已读完", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } else {
                        TextButton(onClick = onMarkDone) { Text("标记本章读完") }
                    }
                }
            }
        }
    }

    if (showRoughForceConfirm) {
        AlertDialog(
            onDismissRequest = { showRoughForceConfirm = false },
            title = { Text("重新粗读这一章？") },
            text = { Text("搭子会重新阅读全部段落并覆盖现有标注，已生成的内容将被替换。") },
            confirmButton = {
                TextButton(onClick = {
                    showRoughForceConfirm = false
                    onForce()
                }) { Text("覆盖重读") }
            },
            dismissButton = {
                TextButton(onClick = { showRoughForceConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showNoteForceConfirm) {
        AlertDialog(
            onDismissRequest = { showNoteForceConfirm = false },
            title = { Text("重新生成全部讲解？") },
            text = { Text("搭子会重新讲解本章全部段落，现有讲解将被替换。") },
            confirmButton = {
                TextButton(onClick = {
                    showNoteForceConfirm = false
                    onNoteForce()
                }) { Text("重新生成") }
            },
            dismissButton = {
                TextButton(onClick = { showNoteForceConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 工具行下的状态条：统一内边距的纵向容器 */
@Composable
private fun ToolStatusRow(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** 进行中状态条：文案 + 细进度条 + 停止 */
@Composable
private fun TaskProgressRow(label: String, progress: Float, onStop: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) { Text("停止") }
    }
}

/** 阅读字号弹层（P7）：四档即选即存（存库由调用方 onPick 回调处理） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontSizeSheet(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text("阅读字号", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            SettingsRepository.READ_FONT_SIZES.forEach { size ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(size) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "示例文字（$size 号）",
                        fontSize = size.sp,
                        lineHeight = (size * 1.6f).sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (current == size) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "当前字号",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 划词浮条（P7）：定位在选中文字上方，提供问含义/问整段/复制/关闭 */
@Composable
private fun SelectionFloatBar(
    anchor: IntOffset,
    boxOrigin: IntOffset,
    onMeaning: () -> Unit,
    onPara: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val screenWpx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt()
    val barWpx = with(density) { 300.dp.toPx() }.toInt()
    val barHpx = with(density) { 52.dp.toPx() }.toInt()
    val gap = with(density) { 8.dp.toPx() }.toInt()
    val x = (anchor.x - boxOrigin.x - barWpx / 2).coerceIn(0, (screenWpx - barWpx).coerceAtLeast(0))
    val y = (anchor.y - boxOrigin.y - barHpx - gap).coerceAtLeast(0)
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(x, y),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onMeaning) {
                    Text("问含义", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onPara) {
                    Text("问整段", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onCopy) {
                    Text("复制", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "取消划词")
                }
            }
        }
    }
}

/** 正文阅读字号：随「Aa」弹层四档切换（P7），行高 1.7 倍保持阅读节奏 */
private fun bodyTextStyle(fontSize: Int): TextStyle =
    TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.7f).roundToInt().sp)

/**
 * 段落行（计划 M4a §3 + 质检 P1-6 + §3.4 视觉 + P7 划词）：讲/合并讲徽标 + why；
 * SKIP 段全文半透明仍可读；EXPLAIN 左侧主题色竖条；正文长按取词随手问。
 */
@Composable
private fun ParaRow(
    p: ParagraphEntity,
    fontSize: Int,
    selectedRange: IntRange?,
    onLongPress: (IntRange, IntOffset) -> Unit,
) {
    // OPT-C C4：目录条目特殊展示——小字次级色，无重点竖条/无 AI 徽标（TOC 段也不参与 AI 标注）
    // OPT-E：脚注段同款次级展示；P5-F：FRONT/BACK（前/后置版权页）纳入次级展示，
    // 但仅视觉降级，AI 加工口径不变（ReadStyles.isSecondaryRole 展示与加工解耦）
    if (ReadStyles.isSecondaryRole(p.role)) {
        SelectableText(
            text = p.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            highlightRange = selectedRange,
            onLongPress = onLongPress,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    // EXPLAIN 段左侧主题色竖条（M4a §3.4）：IntrinsicSize 让竖条随内容高度
    val explained = p.aiAction == DbValues.ACT_EXPLAIN
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        if (explained) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = if (explained) 12.dp else 16.dp, vertical = 12.dp),
        ) {
            when (p.aiAction) {
                DbValues.ACT_EXPLAIN, DbValues.ACT_GROUP -> {
                    ParaTag(if (explained) "★ 讲" else "合并讲")
                    if (!p.why.isNullOrBlank()) {
                        Text(
                            "为什么讲：${p.why}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    SelectableText(
                        text = p.text,
                        style = bodyTextStyle(fontSize),
                        highlightRange = selectedRange,
                        onLongPress = onLongPress,
                    )
                }
                DbValues.ACT_SKIP -> {
                    Text(
                        "已跳过",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectableText(
                        text = p.text,
                        style = bodyTextStyle(fontSize),
                        highlightRange = selectedRange,
                        onLongPress = onLongPress,
                        modifier = Modifier.alpha(0.55f),
                    )
                }
                else -> SelectableText(
                    text = p.text,
                    style = bodyTextStyle(fontSize),
                    highlightRange = selectedRange,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

/**
 * 可长按的正文 Text（P7）：长按取词（SelectionBounds.wordAt）回报词区间 + 光标矩形
 * 的 window 坐标；被选中段渲染底色高亮（primaryContainer）。普通 tap 不消费，滚动不受影响。
 */
@Composable
private fun SelectableText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    highlightRange: IntRange?,
    onLongPress: (IntRange, IntOffset) -> Unit,
) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val windowBounds = remember { mutableStateOf<Rect?>(null) }
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val annotated = remember(text, highlightRange, highlight) {
        if (highlightRange == null || highlightRange.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                addStyle(
                    SpanStyle(background = highlight),
                    highlightRange.first,
                    (highlightRange.last + 1).coerceAtMost(text.length),
                )
            }
        }
    }
    Text(
        text = annotated,
        style = style,
        color = color,
        onTextLayout = { layout.value = it },
        modifier = modifier
            .onGloballyPositioned { windowBounds.value = it.boundsInWindow() }
            .pointerInput(text) {
                detectTapGestures(onLongPress = { pos ->
                    val l = layout.value ?: return@detectTapGestures
                    val word = SelectionBounds.wordAt(text, l.getOffsetForPosition(pos))
                    if (word.isEmpty()) return@detectTapGestures
                    val cursor = l.getCursorRect(word.first)
                    val global = windowBounds.value?.let { it.topLeft + cursor.centerLeft } ?: Offset.Zero
                    onLongPress(word, IntOffset(global.x.roundToInt(), global.y.roundToInt()))
                })
            },
    )
}

@Composable
private fun ParaTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 本章要点卡（P7 移入段落流可折叠）：gist + key_terms chips（M4a §3.4 视觉规格） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GistCard(gist: String, keyTermsJson: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(gist, style = MaterialTheme.typography.bodyMedium)
            val terms = KeyTermsCodec.decode(keyTermsJson)
            if (terms.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    terms.forEach { t ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                t.term,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 讲解卡（计划 M4b §3.2）：标题+大白话+类比+要点+记忆钩子；生成中显示占位 */
@Composable
private fun NoteCard(note: ParaNoteEntity?, generating: Boolean) {
    when {
        note != null -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(note.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(note.friendly, style = MaterialTheme.typography.bodyMedium)
                note.analogy?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "打个比方：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val points = decodeStringList(note.keyPointsJson)
                if (points.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    points.forEach { p ->
                        Text("• $p", style = MaterialTheme.typography.bodySmall)
                    }
                }
                note.memoryHook?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "记忆钩子：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        generating -> Text(
            "搭子正在讲这一段…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        else -> {}
    }
}

// ==================== P7 随手问问答弹层 ====================

/**
 * 随手问底部问答卡（P7）：打开即按默认问题自动发问（词/句/段粒度由浮条决定），
 * 流式回答（打字机效果），下方输入框可追问（多轮，历史裁最近 10 轮）。
 * 不走 AiGate 全局互斥（单请求轻量）；无 Key 时引导去设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickQaSheet(
    ctx: QuickQa.Ctx,
    defaultQuestion: String,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as StudyApp
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var history by remember { mutableStateOf(listOf<QuickQa.Turn>()) }
    // null = 空闲；"" = 请求中还没收到 delta；非空 = 流式正文
    var streaming by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastQuestion by remember { mutableStateOf(defaultQuestion) }
    var noKey by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun ask(question: String) {
        if (streaming != null) return
        lastQuestion = question
        job = scope.launch {
            error = null
            noKey = false
            streaming = ""
            try {
                val cfg = app.settingsRepo.load()
                val key = app.settingsRepo.decryptKeyOrNull()
                if (key == null) {
                    noKey = true
                    streaming = null
                    return@launch
                }
                val systemPrompt = PromptLoader.load(app, "quick_qa")
                val answer = AiClient.chat(
                    ChatRequest(
                        baseUrl = cfg.baseUrl,
                        apiKey = key,
                        // 讲解模型（按用途选模型）：随手问是高频轻任务，空则跟随主力
                        model = cfg.noteModel.ifBlank { cfg.model },
                        temperature = 0.5,
                        maxTokens = 4000,
                        messages = QuickQa.messages(systemPrompt, ctx, history, question),
                    ),
                ) { delta -> streaming += delta }
                history = history + QuickQa.Turn(question, answer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "请求失败"
            } finally {
                streaming = null
            }
        }
    }

    // 打开即自动发默认问题（老板诉求：选中直接问，不用再打字）
    LaunchedEffect(Unit) { ask(defaultQuestion) }
    // 新回答/流式增长时滚到底（index 越界由列表自行 clamp；用当前条目数更稳）
    LaunchedEffect(history.size, streaming?.length, error, noKey) {
        if (history.isNotEmpty() || streaming != null || error != null || noKey) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last)
        }
    }

    ModalBottomSheet(onDismissRequest = {
        job?.cancel()
        onDismiss()
    }) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .imePadding()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            // 引用块：正在问的文字
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "「${ctx.selection.take(120)}${if (ctx.selection.length > 120) "…" else ""}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            // 问答流
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(history) { _, turn ->
                    Column {
                        Text(
                            turn.question,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp)) { MdText(md = turn.answer) }
                        }
                    }
                }
                if (streaming != null) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                streaming!!.ifBlank { "搭子正在想…" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
                if (error != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                error ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { ask(lastQuestion) }) { Text("重试") }
                        }
                    }
                }
                if (noKey) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "还没有配置 AI，先去设置页填好 API 地址和 Key。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onOpenSettings) { Text("去设置") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 追问输入行
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("继续问…") },
                    maxLines = 3,
                    shape = RoundedCornerShape(20.dp),
                )
                Button(
                    onClick = {
                        val q = input.trim()
                        if (q.isNotEmpty()) {
                            input = ""
                            ask(q)
                        }
                    },
                    enabled = streaming == null && input.isNotBlank(),
                ) { Text("发送") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ==================== P4 示意图（§E） ====================

private const val TAG_FIG = "FigureRow"

/** 混排行：段行 / 图行 */
internal sealed class ReadRow {
    data class Para(val p: ParagraphEntity) : ReadRow()
    data class Fig(val f: FigureEntity) : ReadRow()
}

/**
 * 段落流混排（P4 §E）：图插在同 pageNo 的第 ordAfterPara 段之后——「第 N 段」=
 * 阅读页渲染列表（页内全量段，含 TOC/FOOTNOTE/FRONT 次级段）序号，与 §3-B 锚定
 * 基准同口径零换算。ordAfterPara=-1 三态（r7-P2-9）：该页有段 → 页首段之前；
 * 该页无段 → 页码小于它的最后一段之后；全章没有更早段 → 章首。
 * ord 越界 coerceAtMost(页段数-1) 兜底页末（r9-P2-5）；同一插入位多图保持
 * byChapterFlow 排序（pageNo/ord/bboxY0）稳定展开。
 */
internal fun buildReadRows(
    paragraphs: List<ParagraphEntity>,
    figures: List<FigureEntity>,
): List<ReadRow> {
    if (figures.isEmpty()) return paragraphs.map { ReadRow.Para(it) }
    // 页号 → 该页段落在渲染列表中的下标集合（按出现序）；TXT/粘贴段 pageNo=null 不参与
    val indicesByPage = HashMap<Int, MutableList<Int>>()
    paragraphs.forEachIndexed { i, p ->
        p.pageNo?.let { indicesByPage.getOrPut(it) { mutableListOf() }.add(i) }
    }
    val figsAt = HashMap<Int, MutableList<FigureEntity>>()
    for (f in figures) {
        val pageIdxs = indicesByPage[f.pageNo]
        val at = when {
            !pageIdxs.isNullOrEmpty() -> {
                val ord = if (f.ordAfterPara < 0) -1 else f.ordAfterPara.coerceAtMost(pageIdxs.size - 1)
                if (ord < 0) pageIdxs.first() else pageIdxs[ord] + 1
            }
            else -> {
                // 该页无段（图页文字全空/段被替换）：页码更小的最后一段之后；全章无更早段 → 章首
                val lastEarlier = paragraphs.indexOfLast { it.pageNo != null && it.pageNo < f.pageNo }
                if (lastEarlier >= 0) lastEarlier + 1 else 0
            }
        }
        figsAt.getOrPut(at) { mutableListOf() }.add(f)
    }
    val rows = mutableListOf<ReadRow>()
    paragraphs.forEachIndexed { i, p ->
        figsAt[i]?.forEach { rows.add(ReadRow.Fig(it)) }
        rows.add(ReadRow.Para(p))
    }
    figsAt[paragraphs.size]?.forEach { rows.add(ReadRow.Fig(it)) }
    return rows
}

/**
 * 图片行 Bitmap 缓存（r4-P2-3 + r7-P1-5 按字节）：上限 = maxMemory×15% clamp
 * [15MB, 50MB]——按字节计才与设备内存挂钩（按数量 20×4.5MB=90MB 低端机偏大）。
 */
object FigureBitmapCache {
    /** 解码调度器（r5-P2-2）：并发 2，单张 <100ms 下排队延迟减半，不挤占其他解码 */
    val decodeDispatcher = Dispatchers.Default.limitedParallelism(2)

    val lru = object : LruCache<Long, Bitmap>(
        run {
            val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            (maxKb * 15 / 100).coerceIn(15 * 1024, 50 * 1024)
        },
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
        // 驱逐即弃引用、不显式 recycle：API≥26 Bitmap 像素在 Java 堆，recycle 与
        // Compose 仍持有的引用有竞态；读取端 isRecycled 守卫兜底（计划「读前检查」）
    }
}

/** Android 官方采样算法：解出不超过目标宽高的最大 2^n 采样 */
internal fun calcInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
    var size = 1
    if (srcW > reqW || srcH > reqH) {
        val halfW = srcW / 2
        val halfH = srcH / 2
        while (halfW / size >= reqW && halfH / size >= reqH) size *= 2
    }
    return size
}

/**
 * 图片行（P4 §E）：DB 尺寸 aspectRatio 先占位（无布局跳动）→ IO 按屏宽预算算
 * inSampleSize 解码刷帧。失败双态（r7-P2-7）：文件不在 →「图片已丢失」整块可点
 * 重试（仍失败维持提示）；解码 null →「图片已损坏」+ Log.w 记文件。LRU 命中免解码，
 * 读前 isRecycled 防竞态。圆角+1dp 描边+「图 N（p…）」角标。
 */
@Composable
private fun FigureRow(
    f: FigureEntity,
    filesRoot: File,
    visionDonePages: Set<Int>,
    onOpenPreview: () -> Unit,
) {
    // r11-P2-2：vision 已替换页的锚定可能偏 1-2 段——logcat 直接定位错位图，替代逐页手工走查
    LaunchedEffect(f.id, visionDonePages) {
        if (f.pageNo in visionDonePages) {
            Log.w(TAG_FIG, "figure seq=${f.seqNo} page=${f.pageNo} 锚定可能偏 1-2 段（该页已 vision 替换）")
        }
    }
    val density = LocalDensity.current
    // 解码预算 = 屏宽物理像素（行内显示宽度上限；幸存图 ≤1200px 宽时采样恒 1）
    val budgetW = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }.toInt().coerceAtLeast(1)
    val ratio = if (f.width > 0 && f.height > 0) f.width.toFloat() / f.height else 4f / 3f

    var attempt by remember(f.id) { mutableIntStateOf(0) }
    var bitmap by remember(f.id) { mutableStateOf<Bitmap?>(null) }
    var lost by remember(f.id) { mutableStateOf(false) }
    var corrupt by remember(f.id) { mutableStateOf(false) }

    LaunchedEffect(f.id, attempt) {
        val cached = FigureBitmapCache.lru.get(f.id)
        if (cached != null) {
            if (!cached.isRecycled) {
                bitmap = cached
                lost = false
                corrupt = false
                return@LaunchedEffect
            }
            FigureBitmapCache.lru.remove(f.id)
        }
        bitmap = null
        val file = File(filesRoot, f.file)
        if (!file.exists()) {
            lost = true
            corrupt = false
            return@LaunchedEffect
        }
        lost = false
        val decoded = withContext(FigureBitmapCache.decodeDispatcher) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val reqH = (budgetW.toFloat() * bounds.outHeight / bounds.outWidth).toInt().coerceAtLeast(1)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, budgetW, reqH)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }
        if (decoded == null) {
            Log.w(TAG_FIG, "figure decode null file=${f.file}")
            corrupt = true
        } else {
            corrupt = false
            bitmap = decoded
            FigureBitmapCache.lru.put(f.id, decoded)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(enabled = bitmap != null, onClickLabel = "全屏查看示意图") { onOpenPreview() },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null && !bmp.isRecycled -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "示意图，第${f.pageNo}页", // TODO(P6): caption
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize(),
            )
            lost -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "图片已丢失",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "点击重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            corrupt -> Text(
                "图片已损坏",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 其余：解码中，灰底占位
        }
        // 失败态整块可点重试（r7-P2-7），盖在提示文案上
        if (lost || corrupt) {
            Box(Modifier.matchParentSize().clickable { attempt++ })
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
        ) {
            Text(
                "图 ${f.seqNo}（p${f.pageNo}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 全屏预览（P2-1）：独立解码——不复用行内已缩 Bitmap、不占 LRU 配额，按预览显示
 * 尺寸重算采样（幸存图 ≤1.2MP 时 inSampleSize=1 直解，≈4.5MB 安全），放大不模糊；
 * 用完即回收。挂 ReadScreen 顶层（r5-P2-10）：开关不重建 LazyColumn，关闭即回原滚动位。
 */
@Composable
private fun FigurePreviewDialog(f: FigureEntity, filesRoot: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var corrupt by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        val screenW = with(density) {
            LocalConfiguration.current.screenWidthDp.dp.toPx()
        }.toInt().coerceAtLeast(1)
        val screenH = with(density) {
            LocalConfiguration.current.screenHeightDp.dp.toPx()
        }.toInt().coerceAtLeast(1)
        LaunchedEffect(f.id) {
            val decoded = withContext(FigureBitmapCache.decodeDispatcher) {
                val file = File(filesRoot, f.file)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, screenW, screenH)
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
            if (decoded != null) bitmap = decoded else corrupt = true
        }
        DisposableEffect(Unit) {
            onDispose {
                bitmap?.let { if (!it.isRecycled) it.recycle() } // 用完即回收（P2-1）
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            when {
                bmp != null && !bmp.isRecycled -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "示意图全屏预览，第${f.pageNo}页",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                corrupt -> Text("图片已损坏", color = MaterialTheme.colorScheme.error)
                else -> CircularProgressIndicator()
            }
        }
    }
}
