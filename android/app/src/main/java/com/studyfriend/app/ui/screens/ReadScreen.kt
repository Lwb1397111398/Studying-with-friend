package com.studyfriend.app.ui.screens

import android.app.Activity
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.R
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
import com.studyfriend.app.data.tts.AndroidTtsEngine
import com.studyfriend.app.data.tts.SherpaTtsEngine
import com.studyfriend.app.data.tts.TtsEngineMode
import com.studyfriend.app.data.tts.TtsPlayer
import com.studyfriend.app.data.tts.TtsState
import com.studyfriend.app.data.tts.sentenceToParaIndex
import com.studyfriend.app.data.tts.splitSentences
import kotlinx.coroutines.flow.filter
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
    /** 跳章（v7 第二参 paraId：书签/搜索定位段锚，-1=章首） */
    onOpenChapter: (Long, Long) -> Unit = { _, _ -> },
    /** P5 章节树节行锚：非空时滚动定位到节标题段（匹配失败静默不滚动，见 findHighlightIndex） */
    highlight: String? = null,
    /** v7 全书搜索跳转锚：>=0 时直接滚到该段（优先级高于 highlight 与位置记忆） */
    scrollParaId: Long = -1L,
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

    // P7 阅读字号 + 功能补齐轮行距（settings 记忆）；字号弹层拖动实时预览、关闭存库
    var readFontSize by remember { mutableIntStateOf(SettingsRepository.DEFAULT_READ_FONT_SIZE) }
    var readLineHeight by remember { mutableFloatStateOf(SettingsRepository.DEFAULT_READ_LINE_HEIGHT) }
    var showFontSheet by remember { mutableStateOf(false) }
    // 翻页模式（体验优化轮）：true=点屏幕两侧/左右滑翻一屏；watch 设置键，Aa 弹层切换即时生效
    val pageMode by app.settingsRepo.readPageModeFlow().collectAsStateWithLifecycle(initialValue = false)
    // 阅读背景主题包（第六波）：watch 即时切换；follow=null 走 MaterialTheme 现状
    val readThemeId by app.settingsRepo.readThemeFlow().collectAsStateWithLifecycle(initialValue = SettingsRepository.KEY_READ_THEME_DEFAULT)
    val readingTheme = remember(readThemeId) { ReadingTheme.byId(readThemeId) }
    // 音量键翻页（第六波，借鉴 legado）：开=音量加/减翻上/下一页
    val volumeKey by app.settingsRepo.readVolumeKeyFlow().collectAsStateWithLifecycle(initialValue = false)
    // 阅读字体（第七波）：default=系统 / lxgw=霞鹜文楷 Lite（res/font，OFL-1.1）
    val readFontFamilyId by app.settingsRepo.readFontFamilyFlow().collectAsStateWithLifecycle(initialValue = SettingsRepository.READ_FONT_DEFAULT)
    val readFontFamily = remember(readFontFamilyId) {
        if (readFontFamilyId == SettingsRepository.READ_FONT_LXGW) {
            FontFamily(Font(R.font.lxgw_wenkai_lite_regular))
        } else null
    }
    // 阅读页亮度（第七波，借鉴 legado）：-1=跟随系统；0.15~1.0 只覆盖阅读页窗口，离页恢复
    val readBrightnessSaved by app.settingsRepo.readBrightnessFlow().collectAsStateWithLifecycle(initialValue = -1f)
    var readBrightness by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(Unit) {
        readFontSize = app.settingsRepo.readFontSize()
        readLineHeight = app.settingsRepo.readLineHeight()
        readBrightness = app.settingsRepo.readBrightness()
    }
    // 弹层拖动即时生效；关弹层（showFontSheet 变 false）把内存值落库
    LaunchedEffect(showFontSheet) {
        if (!showFontSheet && readBrightness >= 0f) app.settingsRepo.saveReadBrightness(readBrightness)
    }
    LaunchedEffect(readBrightnessSaved) {
        // 弹层外的恢复场景（进页/设置变更）同步内存态
        if (!showFontSheet) readBrightness = readBrightnessSaved
    }
    val scope = rememberCoroutineScope()

    // v7 屏幕常亮：读书时不熄屏（离开页面即恢复系统默认）
    val window = (LocalContext.current as? Activity)?.window
    // 阅读页亮度（第七波）：固定亮度只覆盖阅读页窗口，离页恢复系统值（screenBrightness=-1）
    DisposableEffect(readBrightness, window) {
        if (readBrightness in 0.15f..1.0f) {
            window?.attributes = window?.attributes?.apply { screenBrightness = readBrightness }
        }
        onDispose { window?.attributes = window?.attributes?.apply { screenBrightness = -1f } }
    }
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // v7 最近在读：进章即记（书架「继续读」入口 + 最近在读排序的数据源）
    val bookIdForMark = chapter?.bookId
    LaunchedEffect(chapterId, bookIdForMark) {
        if (bookIdForMark != null) {
            app.database.bookDao().markReading(bookIdForMark, chapterId, System.currentTimeMillis())
        }
    }

    // 体验增量轮：阅读时长统计——页面可见期间计时，离开阅读页/切后台把可视时长按
    // 分钟入账。批A改为零头结转：余数挂 app.readingPendingMs 留到下次合计（floor 取整
    // 不透支），频繁切 App 的碎片会话不再丢失；ON_PAUSE 已入账置哨兵防双计。
    // 写库走 appScope：离开页面协程不取消
    var readingSinceMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val readingLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(bookIdForMark, readingLifecycleOwner) {
        fun flush() {
            val bid = bookIdForMark ?: return
            val since = readingSinceMs
            if (since < 0) return
            val (minutes, carryMs) = readingFlushMath(app.readingPendingMs, System.currentTimeMillis() - since)
            app.readingPendingMs = carryMs
            readingSinceMs = -1L
            if (minutes > 0) {
                app.appScope.launch {
                    app.database.readingStatDao()
                        .addMinutes(bid, java.time.LocalDate.now().toString(), minutes)
                }
            }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> flush()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    readingSinceMs = System.currentTimeMillis()
                else -> {}
            }
        }
        readingLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            readingLifecycleOwner.lifecycle.removeObserver(observer)
            flush()
        }
    }

    // v7 目录+书签抽屉数据：全书章列表（level=1）与本书书签，跟随 bookId 就绪后收集
    @Suppress("ProduceStateDoesNotAssignValue")
    val tocChapters by produceState<List<com.studyfriend.app.data.db.ChapterWithAsset>>(emptyList(), bookIdForMark) {
        val bid = bookIdForMark ?: return@produceState
        app.database.chapterDao().byBookFlow(bid).collect { value = it }
    }
    @Suppress("ProduceStateDoesNotAssignValue")
    val bookmarks by produceState<List<com.studyfriend.app.data.db.BookmarkWithChapter>>(emptyList(), bookIdForMark) {
        val bid = bookIdForMark ?: return@produceState
        app.database.bookmarkDao().byBookWithChapterFlow(bid).collect { value = it }
    }
    // 学习闭环轮：本章批注（段落标记）与全书批注（抽屉列表）
    val memos by app.database.paraMemoDao().byChapterFlow(chapterId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val memosByParaId = remember(memos) { memos.groupBy { it.paraId } }
    @Suppress("ProduceStateDoesNotAssignValue")
    val bookMemos by produceState<List<com.studyfriend.app.data.db.ParaMemoWithChapter>>(emptyList(), bookIdForMark) {
        val bid = bookIdForMark ?: return@produceState
        app.database.paraMemoDao().byBookWithChapterFlow(bid).collect { value = it }
    }

    // v7 沉浸模式：单击正文切换顶栏/工具行显隐；返回键先退出沉浸
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    BackHandler(enabled = !chromeVisible) { chromeVisible = true }
    // v7 目录+书签抽屉开关
    var showTocSheet by remember { mutableStateOf(false) }

    // v7 正文听书（TTS 双模式）：系统引擎 + 离线模型引擎都构造好，模式按设置选；语速从设置读入
    val ttsScope = rememberCoroutineScope()
    val ttsEngine = remember { AndroidTtsEngine(app) }
    val sherpaEngine = remember { SherpaTtsEngine(app) }
    val ttsPlayer = remember { TtsPlayer(ttsEngine, ttsScope, sherpaEngine) }
    val ttsNotice by ttsPlayer.notice.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        val rate = app.settingsRepo.ttsRate()
        ttsEngine.rate = rate
        sherpaEngine.rate = rate
        ttsPlayer.mode = if (app.settingsRepo.ttsEngineMode() == SettingsRepository.TTS_ENGINE_LOCAL) {
            TtsEngineMode.LOCAL
        } else {
            TtsEngineMode.SYSTEM
        }
    }
    val ttsState by ttsPlayer.state.collectAsStateWithLifecycle()
    // 当前朗读段 id（null=没在读）：ParaRow 淡染底色 + 自动滚动跟随
    var ttsParaId by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(ttsPlayer) {
        onDispose { ttsPlayer.shutdown() }
    }
    // 换章即停：朗读内容是旧章拼接文本，跳章后继续读会错位
    LaunchedEffect(chapterId) {
        ttsPlayer.stop()
        ttsParaId = null
    }

    // P7 划词与随手问：selection 驱动高亮+把手+浮条；qaTarget 非空开问答弹层；
    // 划词扩选轮：拖把手时藏浮条（松手后随新锚点重现），selectionDragging 标记拖动中
    var selection by remember { mutableStateOf<TextSelection?>(null) }
    var selectionDragging by remember { mutableStateOf(false) }
    var qaTarget by remember { mutableStateOf<QaTarget?>(null) }
    // 学习闭环轮：记一笔草稿（划词选中态快照，弹 Dialog 时浮条已关）与批注详情目标段
    var memoDraft by remember { mutableStateOf<TextSelection?>(null) }
    var memoDetailParaId by remember { mutableStateOf<Long?>(null) }
    // 学习闭环轮：换个讲法目标（讲解卡 + 单元成员原文）
    var reNoteTarget by remember { mutableStateOf<Pair<com.studyfriend.app.data.db.ParaNoteEntity, String>?>(null) }
    // P7 要点卡折叠（默认收起，「要点」chip 开关）
    var gistExpanded by remember { mutableStateOf(false) }

    // P4 §E：图片文件根（filesDir/figures/{bookId}/…）与全屏预览状态。
    // 预览挂顶层（r5-P2-10）：Dialog 不进 LazyColumn，开关不重建列表，关闭即回原滚动位
    val filesRoot = app.filesDir
    var previewFigure by remember { mutableStateOf<FigureEntity?>(null) }
    val clipboard = LocalClipboardManager.current

    // 浮条 offset 相对根 Box；Text 上报的是 window 坐标，根 Box 原点也要记一份换算
    var rootOrigin by remember { mutableStateOf(IntOffset.Zero) }

    // 阅读主题包（第六波）：固定纸色时整页染主题底色；正文默认色走 LocalContentColor
    // （Text 无显式 color 时自动取它）；follow=null 全走现状。弹层保持主题 chrome 不染纸色
    // 阅读字体（第七波）：LocalReadFontFamily 下发正文段与标题段（null=系统字体）
    CompositionLocalProvider(
        LocalReadingTheme provides readingTheme,
        LocalReadFontFamily provides readFontFamily,
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(readingTheme?.bg ?: Color.Transparent)
            .onGloballyPositioned { rootOrigin = it.boundsInWindow().topLeft.round() },
    ) {
    CompositionLocalProvider(LocalContentColor provides (readingTheme?.text ?: LocalContentColor.current)) {
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

            // v7 顶栏进度小字：书名 + 已读 x/y 章（getProgressFlow 首个消费方，标记读完即时刷新）
            @Suppress("ProduceStateDoesNotAssignValue")
            val bookProgress by produceState<com.studyfriend.app.data.db.BookProgress?>(null, bookId) {
                if (bookId == null) return@produceState
                app.database.bookDao().getProgressFlow(bookId).collect { value = it }
            }
            @Suppress("ProduceStateDoesNotAssignValue")
            val bookTitle by produceState("", bookId) {
                value = if (bookId == null) "" else app.database.bookDao().get(bookId)?.title.orEmpty()
            }

            // v7 沉浸模式：顶栏随单击正文收起/展开
            AnimatedVisibility(visible = chromeVisible) {
            // 阅读主题包（第六波）：固定纸色时顶栏同染纸底，标题/图标走主题文字色，
            // 副行走主题次级色——否则墨玉/纯黑下顶栏还是浅底深字，正文区一页两色
            val topBarText = readingTheme?.text ?: LocalContentColor.current
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = readingTheme?.bg ?: MaterialTheme.colorScheme.surface,
                    titleContentColor = topBarText,
                    navigationIconContentColor = topBarText,
                    actionIconContentColor = topBarText,
                ),
                title = {
                    Column {
                        // 2026-11-15 UI 打磨轮：章名两行显示不再截断（「第一章 总 …」这种半截标题就是毛坯感）
                        Text(
                            chapter?.title.orEmpty().ifBlank { "阅读" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 委托属性不能 smart cast（M4a 坑 #15）：取局部变量再判空。
                        // 实景体验轮：进度放前面——长书名吃掉省略号时保住「粗读 x/y 章」
                        //（「不当得利-王泽鉴2023 · 粗读 0/…」装机实录，进度被截没了）；
                        // 「粗读」不说「已读」：口径是粗读完成章数，写已读像坏了（本轮实录）
                        val bp = bookProgress
                        if (bp != null && bp.totalChapters > 0) {
                            Text(
                                "粗读 ${bp.readChapters}/${bp.totalChapters} 章 · $bookTitle",
                                style = MaterialTheme.typography.labelSmall,
                                color = readingTheme?.secondary ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // v7 目录+书签抽屉入口
                    IconButton(onClick = { showTocSheet = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "目录与书签")
                    }
                    // 上一章/下一章：左右箭头更直觉（原上下箭头常被误读成滚动）
                    IconButton(
                        enabled = prevChapter != null,
                        onClick = { prevChapter?.let { onOpenChapter(it.id, -1L) } },
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一章") }
                    IconButton(
                        enabled = nextChapter != null,
                        onClick = { nextChapter?.let { onOpenChapter(it.id, -1L) } },
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一章") }
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
            }

            // OPT-C C4：计数用正文视图（TOC/脚注/图描述段永远无标注，混入计数会让"部分标注"态无法收敛）；
            // LazyColumn 渲染仍用全量列表（目录条目/脚注/图描述按次级样式展示）
            val bodyParagraphs = remember(paragraphs) {
                paragraphs.filter {
                    it.role != DbValues.ROLE_TOC && it.role != DbValues.ROLE_FOOTNOTE &&
                        it.role != DbValues.ROLE_FIGURE
                }
            }
            val markedCount = bodyParagraphs.count { it.aiAction != DbValues.ACT_NONE }

            // v7 正文听书：朗读文本 = 本章 BODY 段顺序拼接（\n 连接，分句在换行处断开，
            // 段边界与句→段映射对齐）；句→段映射数据在 toggle 时一次性算好
            val ttsParagraphs = remember(bodyParagraphs) { bodyParagraphs.filter { it.role == DbValues.ROLE_BODY } }
            var ttsParaCounts by remember { mutableStateOf(listOf<Int>()) }
            var ttsParaIds by remember { mutableStateOf(listOf<Long>()) }
            fun toggleTts() {
                if (ttsState is TtsState.Speaking || ttsState is TtsState.Preparing) {
                    ttsPlayer.stop()
                    ttsParaId = null
                } else {
                    if (ttsParagraphs.isEmpty()) return
                    ttsParaCounts = ttsParagraphs.map { splitSentences(it.text).size }
                    ttsParaIds = ttsParagraphs.map { it.id }
                    ttsPlayer.toggle(ttsParagraphs.joinToString("\n") { it.text })
                }
            }

            // 讲解单元与 note 映射（原 NoteBar 内部推导上提）：卡片只渲染在锚段下（M4b §3.2）
            val units = remember(paragraphs) { enumerateUnits(paragraphs) }
            val anchorById = remember(units) { units.associateBy { it.anchor.id } }
            val unitParaIds = remember(units) {
                units.map { ParaIdsCodec.encode(it.members.map { m -> m.id }) }.toSet()
            }
            val noteValidCount = notes.count {
                it.paraIds in unitParaIds && NotePlanner.isCurrentNote(it.promptVersion) && it.friendly.isNotBlank()
            }
            // 讲解进行中（本章）：讲解卡占位用；委托属性不能 smart cast，用 as? 判
            val noteRunningThis = (noteState as? NoteRunState.Running)?.chapterId == chapterId
            val notesByParaIds = remember(notes) { notes.associateBy { it.paraIds } }

            // v7 沉浸模式：工具行同样随单击收起/展开
            AnimatedVisibility(visible = chromeVisible) {
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
                ttsActive = ttsState is TtsState.Speaking || ttsState is TtsState.Preparing,
                ttsAvailable = ttsParagraphs.isNotEmpty(),
                onToggleTts = { toggleTts() },
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
            }

            // v7 TTS 错误提示行（朗读中引擎报错——无引擎/初始化失败/合成失败）
            (ttsState as? TtsState.Error)?.let { err ->
                Text(
                    err.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            // TTS 回落提示（离线模型没下载，这次先用系统语音）：朗读中低调显示
            if (ttsState is TtsState.Speaking || ttsState is TtsState.Preparing) {
                ttsNotice?.let { n ->
                    Text(
                        n,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }

            if (paragraphs.isEmpty()) {
                EmptyState(title = "本章还没有内容", subtitle = "段落解析可能没有完成")
                return@Column
            }

            // P4 §E：段落流混排——图行按 ordAfterPara 插进段序列（buildReadRows 三态口径）
            val rows = remember(paragraphs, figures) { buildReadRows(paragraphs, figures) }
            val listState = rememberLazyListState()
            // 滚动即取消划词：浮条锚点是 window 坐标，滚动后必然错位
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress) {
                    selection = null
                }
            }

            // 翻页模式（体验优化轮）：正文可视宽度 + 翻一屏 + 点按分区。
            // 左 1/3=上一屏、右 1/3=下一屏、中间=切沉浸（滚动模式整屏单击都是切沉浸）；
            // 左右滑超 1/6 屏宽翻页。
            // 翻页落点（第五波实景轮，spike/pager-demo B 形态落地）：按行高估算从当前
            // 行首累计 ≥0.88 视口选出落点行，animateScrollToItem 锚行首——页首恒为完整
            // 段落开头（旧 0.88 视口平滑滚动落点常在段中），LazyColumn 只合成落点页内容
            // （spike 实测 p50 帧 33ms→16ms）。估算误差只影响每页行数，不影响锚行首。
            var contentWidthPx by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            // 行高估算模型随字号/行距设置联动：段行=行数×行高+段距（正文段距 16dp），
            // 图行=显示宽/ratio+上下边距（FigureRow horizontal 16dp + vertical 8dp 口径）
            val estFontPx = with(density) { readFontSize.sp.toPx() }
            val estLineHpx = estFontPx * readLineHeight
            val estGapPx = with(density) { 16.dp.toPx() }
            val estHpadPx = with(density) { 16.dp.toPx() }
            // gist 卡展开时是 LazyColumn 第 0 个 item（rows 之外），rows 下标 ↔ item 下标要平移
            val extraHeadItems = if (gistExpanded && !chapter?.gist.isNullOrBlank()) 1 else 0
            fun turnPage(dir: Int) {
                val vh = listState.layoutInfo.viewportSize.height.toFloat()
                if (vh <= 0f || rows.isEmpty()) return
                scope.launch {
                    val textW = (contentWidthPx.toFloat() - estHpadPx * 2).coerceAtLeast(estFontPx)
                    val charsPerLine = (textW / estFontPx).coerceAtLeast(1f)
                    val fromRows = (listState.firstVisibleItemIndex - extraHeadItems).coerceIn(0, rows.size - 1)
                    val targetRows = PageTurn.target(
                        rows = rows,
                        fromRows = fromRows,
                        dir = dir,
                        budgetPx = vh * 0.88f,
                        charsPerLine = charsPerLine,
                        lineHpx = estLineHpx,
                        gapPx = estGapPx,
                        textW = textW,
                    )
                    listState.animateScrollToItem((targetRows + extraHeadItems).coerceAtLeast(0))
                }
            }
            // 音量键翻页（第六波，借鉴 legado）：开关打开时在 App 级注册 hook，MainActivity
            // 把音量加/减转成 turnPage(-1/+1)；离开页面注销（hook 引用比对防误删后注册的）
            DisposableEffect(volumeKey, chapterId) {
                val hook: (Int) -> Boolean = { dir -> turnPage(dir); true }
                if (volumeKey) {
                    app.volumeKeyTurnHook = hook
                }
                onDispose {
                    if (app.volumeKeyTurnHook === hook) app.volumeKeyTurnHook = null
                }
            }
            fun handleBodyTap(pos: Offset) {
                if (!pageMode) {
                    chromeVisible = !chromeVisible
                    return
                }
                val w = contentWidthPx
                when {
                    w <= 0 -> chromeVisible = !chromeVisible
                    pos.x < w / 3f -> turnPage(-1)
                    pos.x > w * 2f / 3f -> turnPage(1)
                    else -> chromeVisible = !chromeVisible
                }
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
            // v7 全书搜索跳转：直接滚到命中段（优先级最高，定位后随即被下方保存逻辑记账）
            LaunchedEffect(scrollParaId, rows) {
                if (scrollParaId >= 0 && rows.isNotEmpty()) {
                    rows.indexOfFirst { it is ReadRow.Para && it.p.id == scrollParaId }
                        .takeIf { it >= 0 }
                        ?.let { listState.scrollToItem(it) }
                }
            }
            // v7 位置记忆恢复：无显式跳转参数（搜索 paraId / 节锚 highlight）时滚回上次读到的段
            val savedParaIdx = chapter?.lastReadParaIdx
            LaunchedEffect(chapter?.id, rows) {
                if (scrollParaId < 0 && highlight.isNullOrBlank() && savedParaIdx != null && rows.isNotEmpty()) {
                    val pid = paragraphs.firstOrNull { it.idx == savedParaIdx }?.id
                    if (pid != null) {
                        rows.indexOfFirst { it is ReadRow.Para && it.p.id == pid }
                            .takeIf { it >= 0 }
                            ?.let { listState.scrollToItem(it) }
                    }
                }
            }
            // v7 位置记忆保存：滚动停止后把可视首段（经 itemKey "p{id}" 反查段 idx）写库；
            // 图行/要点卡不是位置锚，跳过找下一个可见段。应用级 scope：退页不取消写库
            val latestParagraphs by rememberUpdatedState(paragraphs)
            LaunchedEffect(listState, rows) {
                snapshotFlow { listState.isScrollInProgress }
                    .filter { !it }
                    .collect {
                        val paraIdx = listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
                            (info.key as? String)?.takeIf { k -> k.startsWith("p") }
                                ?.removePrefix("p")?.toLongOrNull()
                                ?.let { pid -> latestParagraphs.firstOrNull { p -> p.id == pid }?.idx }
                        } ?: return@collect
                        app.appScope.launch {
                            app.database.chapterDao().updateLastReadPara(chapterId, paraIdx)
                        }
                    }
            }
            // v7 TTS 跟随：句→段映射定位当前段，滚出视口才自动滚（用户手动翻走不打扰）
            LaunchedEffect(ttsState, ttsParaCounts) {
                val s = ttsState as? TtsState.Speaking ?: return@LaunchedEffect
                val paraPos = sentenceToParaIndex(ttsParaCounts, s.sentenceIdx)
                val pid = ttsParaIds.getOrNull(paraPos) ?: return@LaunchedEffect
                ttsParaId = pid
                val rowIdx = rows.indexOfFirst { it is ReadRow.Para && it.p.id == pid }
                if (rowIdx >= 0) {
                    val inView = listState.layoutInfo.visibleItemsInfo.any { it.index == rowIdx }
                    if (!inView) listState.animateScrollToItem(rowIdx)
                }
            }

            // v7 书签操作：取当前可视首段为锚；同章本地滚动、跨章导航带段锚。
            // 用局部 fun（非 val lambda）：inline lambda 里的 fun 声明才对外层作用域可见（ReadTocSheet 挂载要用）
            fun addBookmarkAtCurrent() {
                val para = listState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
                    (info.key as? String)?.takeIf { k -> k.startsWith("p") }
                        ?.removePrefix("p")?.toLongOrNull()
                        ?.let { pid -> paragraphs.firstOrNull { p -> p.id == pid } }
                } ?: return
                val bid = bookIdForMark ?: return
                app.appScope.launch {
                    app.database.bookmarkDao().insert(
                        com.studyfriend.app.data.db.BookmarkEntity(
                            bookId = bid,
                            chapterId = chapterId,
                            paraIdx = para.idx,
                            preview = para.text.take(40),
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            fun openBookmark(bm: com.studyfriend.app.data.db.BookmarkWithChapter) {
                showTocSheet = false
                scope.launch {
                    val pid = app.database.paragraphDao()
                        .byChapterAndIdx(bm.bookmark.chapterId, bm.bookmark.paraIdx)?.id ?: -1L
                    if (bm.bookmark.chapterId == chapterId) {
                        // 同章：不重复入栈，本地滚到锚段
                        if (pid >= 0) {
                            rows.indexOfFirst { it is ReadRow.Para && it.p.id == pid }
                                .takeIf { it >= 0 }
                                ?.let { listState.scrollToItem(it) }
                        }
                    } else {
                        onOpenChapter(bm.bookmark.chapterId, pid)
                    }
                }
            }
            // 学习闭环轮：批注跳段——批注直接存了 paraId，无需像书签那样按 idx 反查；
            // scrollToItem 是挂起函数，与 openBookmark 同款包 scope.launch
            fun openMemo(m: com.studyfriend.app.data.db.ParaMemoWithChapter) {
                showTocSheet = false
                scope.launch {
                    if (m.memo.chapterId == chapterId) {
                        rows.indexOfFirst { it is ReadRow.Para && it.p.id == m.memo.paraId }
                            .takeIf { it >= 0 }
                            ?.let { listState.scrollToItem(it) }
                    } else {
                        onOpenChapter(m.memo.chapterId, m.memo.paraId)
                    }
                }
            }

            // v7 目录+书签抽屉（ModalBottomSheet 是 window 级弹层，挂载位置不影响渲染，
            // 放这里为了让书签操作函数落在同一作用域）
            if (showTocSheet) {
                ReadTocSheet(
                    chapters = tocChapters,
                    currentChapterId = chapterId,
                    bookmarks = bookmarks,
                    memos = bookMemos,
                    onOpenChapter = { target ->
                        showTocSheet = false
                        onOpenChapter(target, -1L)
                    },
                    onAddBookmark = { addBookmarkAtCurrent() },
                    onOpenBookmark = { openBookmark(it) },
                    onDeleteBookmark = { bm ->
                        app.appScope.launch { app.database.bookmarkDao().delete(bm.bookmark.id) }
                    },
                    onOpenMemo = { openMemo(it) },
                    onDeleteMemo = { m ->
                        app.appScope.launch { app.database.paraMemoDao().delete(m.memo.id) }
                    },
                    onDismiss = { showTocSheet = false },
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { contentWidthPx = it.width }
                    // 翻页模式：左右滑翻页（水平拖动累积，松手超 1/6 屏宽翻一屏）；
                    // 竖向滚动归 LazyColumn 自身，互不抢占
                    .pointerInput(pageMode) {
                        if (!pageMode) return@pointerInput
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragEnd = {
                                val threshold = size.width / 6f
                                when {
                                    totalDrag < -threshold -> turnPage(1)
                                    totalDrag > threshold -> turnPage(-1)
                                }
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                        }
                    }
                    // 沉浸模式：单击正文切换顶栏/工具行（翻页模式经 handleBodyTap 分区）；
                    // 图行点击由 FigureRow 消费不触发；段间空隙的 tap 从这里兜底进同一入口
                    .pointerInput(Unit) { detectTapGestures(onTap = { handleBodyTap(it) }) },
            ) {
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
                                lineHeightMult = readLineHeight,
                                selectedRange = selection?.takeIf { it.paraId == row.p.id }?.range,
                                ttsActive = ttsParaId == row.p.id,
                                hasMemo = memosByParaId.containsKey(row.p.id),
                                onOpenMemo = { memoDetailParaId = row.p.id },
                                onLongPress = { range, anchor ->
                                    selection = TextSelection(row.p.id, range, anchor)
                                },
                                // 拖把手改选区（只动 range；锚点由 SelectableText 校准回调更新）
                                onSelectionChange = { range ->
                                    selection = selection?.takeIf { it.paraId == row.p.id }?.copy(range = range)
                                },
                                onAnchorChange = { anchor ->
                                    selection = selection?.copy(anchor = anchor)
                                },
                                onDragStateChange = { selectionDragging = it },
                                // 沉浸单击/翻页点按必须在文本手势层透传（SelectableText 的 detectTapGestures 消费 tap），
                                // 翻页模式带坐标分区（SelectableText 透传 Text 本地坐标）
                                onTap = { pos -> handleBodyTap(pos) },
                            )
                            val unit = anchorById[row.p.id]
                            if (unit != null) {
                                val note = notesByParaIds[ParaIdsCodec.encode(unit.members.map { it.id })]
                                    ?.takeIf { NotePlanner.isCurrentNote(it.promptVersion) && it.friendly.isNotBlank() }
                                NoteCard(
                                    note = note,
                                    generating = note == null && noteRunningThis,
                                    // 换个讲法：带上单元全部成员原文（临时重讲不落库）
                                    onReNote = { n ->
                                        reNoteTarget = n to unit.members.joinToString("\n") { it.text }
                                    },
                                    // P1-③ 引用溯源：胶囊点击跳段（paraIds[i] 即段 id；TTS 跟随同款 animate 滚动）
                                    onJumpPara = { pid ->
                                        scope.launch {
                                            rows.indexOfFirst { it is ReadRow.Para && it.p.id == pid }
                                                .takeIf { it >= 0 }
                                                ?.let { listState.animateScrollToItem(it) }
                                        }
                                    },
                                )
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
                // 阅读体验轮：章尾「下一章」卡片——读完整章直接点进下一章，不用回顶栏找箭头
                nextChapter?.let { nc ->
                    item(key = "next-chapter") {
                        NextChapterCard(title = nc.title, onClick = { onOpenChapter(nc.id, -1L) })
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }

            // 底部阅读进度细线（第五波实景：长章读到哪里没有感知）——可视首行占全部
            // item 的比例，滚到底约 1；沉浸模式随顶栏一起藏，不占正文空间。
            // derivedStateOf 把失效范围压到「跨 item 才变」，progress lambda 在 draw 阶段
            // 实时读取（不依赖 content 重组，避免 AnimatedVisibility 内首值冻结）
            val readFrac by remember {
                derivedStateOf {
                    val n = listState.layoutInfo.totalItemsCount
                    if (n > 1) {
                        (listState.firstVisibleItemIndex / (n - 1f)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
            AnimatedVisibility(visible = chromeVisible) {
                LinearProgressIndicator(
                    progress = { readFrac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    // 轨道留极淡主色：整条线可见，进度段更亮（全透明轨道的「进度感」弱）
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                )
            }
        }
    }

        // 划词浮条：选中段仍存在且区间合法才显示（段落 Flow 刷新后失效自动消失）；
        // 拖把手期间隐藏（拖完随新锚点重现）
        selection?.let { sel ->
            val selPara = paragraphs.firstOrNull { it.id == sel.paraId }
            if (selPara != null && selPara.text.isNotEmpty() &&
                sel.range.last < selPara.text.length && !selectionDragging
            ) {
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
                    onExpandSentence = {
                        val expanded = SelectionBounds.expandToSentence(selPara.text, sel.range.first, sel.range.last)
                        selection = sel.copy(range = expanded)
                    },
                    onExpandPara = {
                        selection = sel.copy(range = 0 until selPara.text.length)
                    },
                    onMemo = {
                        // 记一笔：选中态快照进草稿（Dialog 里取原文），浮条先关
                        memoDraft = sel
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
    }

    if (showFontSheet) {
        FontSizeSheet(
            fontSize = readFontSize,
            lineHeightMult = readLineHeight,
            pageMode = pageMode,
            onPageModeChange = { on ->
                // 开关即存（Flow watch 即时生效，阅读方式切换不用等关弹层）
                scope.launch { app.settingsRepo.saveReadPageMode(on) }
            },
            themeId = readThemeId,
            onThemeChange = { id -> scope.launch { app.settingsRepo.saveReadTheme(id) } },
            volumeKey = volumeKey,
            onVolumeKeyChange = { on -> scope.launch { app.settingsRepo.saveReadVolumeKey(on) } },
            fontFamilyId = readFontFamilyId,
            onFontFamilyChange = { id -> scope.launch { app.settingsRepo.saveReadFontFamily(id) } },
            brightness = readBrightness,
            onBrightnessChange = { v -> readBrightness = v },
            onChange = { size, line -> readFontSize = size; readLineHeight = line },
            onDismiss = {
                showFontSheet = false
                // 关弹层时统一存库（拖动过程只改内存态，避免高频写库）
                scope.launch {
                    app.settingsRepo.saveReadFontSize(readFontSize)
                    app.settingsRepo.saveReadLineHeight(readLineHeight)
                }
            },
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

    // 学习闭环轮：记一笔草稿弹窗（选中段还在才有效；段落 Flow 刷新后选区失效自动关）
    memoDraft?.let { draft ->
        val draftPara = paragraphs.firstOrNull { it.id == draft.paraId }
        if (draftPara == null || draft.range.last >= draftPara.text.length) {
            memoDraft = null
        } else {
            val quote = draftPara.text.substring(draft.range.first, draft.range.last + 1)
            MemoDraftDialog(
                quote = quote,
                onSave = { text, doubt ->
                    val bid = bookIdForMark
                    // 存疑（第七波）：允许感想留空，kind='doubt' 进抽屉「存疑」Tab；普通批注仍要求非空
                    if (bid != null && (doubt || text.isNotBlank())) {
                        app.appScope.launch {
                            app.database.paraMemoDao().insert(
                                com.studyfriend.app.data.db.ParaMemoEntity(
                                    bookId = bid,
                                    chapterId = chapterId,
                                    paraId = draft.paraId,
                                    paraIdx = draftPara.idx,
                                    quote = quote.take(100),
                                    text = text,
                                    createdAt = System.currentTimeMillis(),
                                    kind = if (doubt) com.studyfriend.app.data.db.ParaMemoEntity.KIND_DOUBT
                                    else com.studyfriend.app.data.db.ParaMemoEntity.KIND_NOTE,
                                ),
                            )
                        }
                    }
                    memoDraft = null
                },
                onDismiss = { memoDraft = null },
            )
        }
    }

    // 段内批注列表（点段落铅笔标记呼出；列表空了自动关）
    memoDetailParaId?.let { pid ->
        val list = memosByParaId[pid].orEmpty()
        if (list.isEmpty()) {
            memoDetailParaId = null
        } else {
            ParaMemosDialog(
                memos = list,
                onDelete = { id ->
                    app.appScope.launch { app.database.paraMemoDao().delete(id) }
                },
                onDismiss = { memoDetailParaId = null },
            )
        }
    }

    // 学习闭环轮：换个讲法弹层（流式重讲，临时展示不落库）
    reNoteTarget?.let { (n, text) ->
        ReNoteSheet(
            note = n,
            paragraphText = text,
            onDismiss = { reNoteTarget = null },
            onOpenSettings = onOpenSettings,
        )
    }
}

/** 随手问上下文组装：章标题/要点 + 选中文字 + 所在段全文 + 前后段（截断在 QuickQa 内统一做）；
 *  v7 补 bookId/chapterId——问答历史落库定位（不进 user 消息） */
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
        bookId = chapter?.bookId ?: -1L,
        chapterId = chapter?.id ?: -1L,
    ),
    defaultQuestion = question,
)

/** 阅读入账结转数学（批A）：(历史零头 + 本次时长) → (入账分钟数, 新零头)。floor 取整不透支 */
internal fun readingFlushMath(pendingMs: Long, elapsedMs: Long): Pair<Int, Long> {
    val total = pendingMs + elapsedMs
    require(total >= 0) { "计时时长为负" }
    return ((total / 60000L).toInt()) to (total % 60000L)
}
