package com.studyfriend.app.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.FigureEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.study.NotePlanner
import com.studyfriend.app.data.study.NoteRunState
import com.studyfriend.app.data.study.ParaIdsCodec
import com.studyfriend.app.data.study.QuickQa
import com.studyfriend.app.data.study.enumerateUnits
import kotlinx.coroutines.launch


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
                // P8 修复：过滤 level=2 节行——校准书的表里混有节行（段落恒空），不过滤时
                // 「下一章」会跳到空节行（显示「本章还没有内容」）
                val list: List<ChapterEntity> =
                    if (bookId == null) {
                        emptyList()
                    } else {
                        app.database.chapterDao().byBook(bookId).filter { it.level == 1 }
                    }
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
