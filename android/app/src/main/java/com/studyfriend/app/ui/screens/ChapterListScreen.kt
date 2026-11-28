package com.studyfriend.app.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.ChapterTreeRow
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.study.BookAnalysisScope
import com.studyfriend.app.data.study.BookAnalysisState
import com.studyfriend.app.data.study.RoughRunState
import com.studyfriend.app.data.study.SummaryPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 章目录页（计划 M4a §3）：每章一行 = 序号/标题/一行摘要（视觉徽标延至 M7），
 * 行尾粗读操作（未粗读→粗读；中断→继续；已完成→重读；进行中→进度+停止）。
 * 点行进阅读页。
 * P5 章节树：章行下挂节行（目录锚——点击跳父章并滚动定位，节行无粗读按钮），
 * 已按目录校准的书显示校准徽标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenChapter: (Long, String?) -> Unit,
    onOpenOverview: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as StudyApp
    // P5：全行投影（章+节），树形挂接在 buildChapterTree（纯函数）；byBookFlow（仅章）
    // 不再被本页使用，M6 门控等其余消费方不受影响
    val treeRows by app.database.chapterDao().byBookTreeFlow(bookId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // M5 线程纪律：runner.state 由后台线程写入，collect 后切主线程再写快照（M6 复审统一口径）
    val runState by produceState<RoughRunState>(RoughRunState.Idle) {
        app.roughReadRunner.state.collect { withContext(Dispatchers.Main) { value = it } }
    }
    var bookTitle by remember { mutableStateOf("") }
    LaunchedEffect(bookId) {
        bookTitle = app.database.bookDao().get(bookId)?.title.orEmpty()
    }
    val nodes = remember(treeRows) {
        buildChapterTree(treeRows) { Log.w("P5Tree", it) }
    }
    // 收起态集合（默认全展开；进程重建丢失收起态=回到全展开，代价可忽略）
    var collapsedIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val items = remember(nodes, collapsedIds) {
        flattenTree(nodes, nodes.map { it.row.id }.toSet() - collapsedIds)
    }
    // 全书总览入口门控（M6 计划 §3.3）：≥2 个已总结章才给入口（与 planner 前置同口径；
    // 只数章行，节行不参与门控）
    val summarizedCount = treeRows.count {
        it.level == 1 && SummaryPlanner.isCurrentSummary(it.assetPromptVersion) && it.assetSummaryPresent
    }
    // 覆盖重读会清掉全章标注并重烧 API，与阅读页同口径加二次确认（最终 QA P1）
    var confirmForceFor by remember { mutableStateOf<Long?>(null) }

    // 一键分析全书（阅读体验轮）：入口卡 + 确认弹窗；状态是全局单例 Runner 的 StateFlow
    val analysisState by produceState<BookAnalysisState>(BookAnalysisState.Idle) {
        app.bookAnalysisRunner.state.collect { withContext(Dispatchers.Main) { value = it } }
    }
    var showAnalysisConfirm by remember { mutableStateOf(false) }
    val analysisBusy = analysisState is BookAnalysisState.Running || analysisState is BookAnalysisState.Queued
    // 确认弹窗里的预览：本书 level=1 章按序排出的分析计划（哪些分析、哪些跳过）
    val analysisPlan = remember(treeRows) {
        BookAnalysisScope.plan(treeRows.filter { it.level == 1 }.sortedBy { it.idx }.map { it.title })
    }

    // 批B：事后目录校准——按目录重新切章（CalibrateExisting；不重跑提取/OCR）
    var showCalibrate by remember { mutableStateOf(false) }
    var calibBusy by remember { mutableStateOf(false) }
    var calibResult by remember { mutableStateOf<String?>(null) }
    val calibScope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(bookTitle.ifBlank { "章节" }) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                TextButton(
                    onClick = { showCalibrate = true },
                    enabled = !calibBusy,
                ) { Text(if (calibBusy) "校准中…" else "校准目录") }
                // v7 全书搜索入口
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "全书搜索")
                }
            },
        )
        if (items.isEmpty()) {
            EmptyState(
                title = "没有章节",
                subtitle = "这本书还没有解析出章节",
                icon = Icons.Filled.List,
            )
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // 一键分析入口卡（阅读体验轮）：老板点名「AI 一键分析完我再自己去读」——
            // 放在总览入口之前的最显眼位置；空闲可点、运行中显示进度、结束时显示结果
            item(key = "analysis_entry") {
                BookAnalysisCard(
                    state = analysisState,
                    thisBookId = bookId,
                    onStart = { showAnalysisConfirm = true },
                    onStop = { app.bookAnalysisRunner.stop() },
                    onClear = { app.bookAnalysisRunner.clearResult() },
                )
            }
            if (summarizedCount >= 2) {
                item(key = "overview_entry") {
                    // 全书总览入口（2026-11-15 UI 打磨轮）：卡片行替代描边大按钮，入口更醒目
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable(onClick = onOpenOverview),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("全书总览", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "已总结 $summarizedCount 章 · 思维导图与要点都在这里",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "打开全书总览",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            // P5 校准徽标（calibrated 列语义定案：任一行 calibrated=true ⇔ 本书目录校准成功）
            if (treeRows.any { it.calibrated }) {
                item(key = "calibrated_badge") {
                    Text(
                        "✓ 已按目录校准",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }
            items(items, key = { it.row.id }) { item ->
                when (item) {
                    is TreeItem.Chapter -> {
                        val row = item.row
                        ChapterRow(
                            row = row,
                            runState = runState,
                            analysisBusy = analysisBusy,
                            hasSections = nodeSections(nodes, row.id).isNotEmpty(),
                            expanded = row.id !in collapsedIds,
                            onToggle = {
                                collapsedIds = if (row.id in collapsedIds) {
                                    collapsedIds - row.id
                                } else {
                                    collapsedIds + row.id
                                }
                            },
                            onStart = { app.roughReadRunner.start(row.id, force = false) },
                            onForce = { confirmForceFor = row.id },
                            onStop = { app.roughReadRunner.stop() },
                            onOpen = { onOpenChapter(row.id, null) },
                        )
                    }
                    is TreeItem.Section -> {
                        // 节行=目录锚：点击跳父章阅读页并滚动到节标题段（findHighlightIndex）；
                        // 节行段落恒空（P3b-2 段落归属按章页区间），不作为导航目的地
                        SectionRow(item.row) { onOpenChapter(item.parentChapterId, item.row.title) }
                    }
                }
            }
        }

        // 覆盖重读二次确认（文案与阅读页一致）
        confirmForceFor?.let { chapterId ->
            AlertDialog(
                onDismissRequest = { confirmForceFor = null },
                title = { Text("重新粗读这一章？") },
                text = { Text("搭子会重新阅读全部段落并覆盖现有标注，已生成的内容将被替换。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmForceFor = null
                        app.roughReadRunner.start(chapterId, force = true)
                    }) { Text("覆盖重读") }
                },
                dismissButton = { TextButton(onClick = { confirmForceFor = null }) { Text("取消") } },
            )
        }

        // 一键分析确认弹窗（阅读体验轮）：先报会分析多少章、跳过哪些章，老板点「开始分析」才动
        if (showAnalysisConfirm) {
            val skipPreview = analysisPlan.skipped.take(3).joinToString("、") { it.second }
            val skipSuffix = if (analysisPlan.skippedCount > 3) " 等 ${analysisPlan.skippedCount} 章" else ""
            AlertDialog(
                onDismissRequest = { showAnalysisConfirm = false },
                title = { Text("AI 一键分析全书？") },
                text = {
                    Text(
                        buildString {
                            append("搭子会逐章完成「粗读 → 讲解」，共 ${analysisPlan.targetCount} 章")
                            if (analysisPlan.skippedCount > 0) {
                                append("，跳过开篇/目录/后置件：$skipPreview$skipSuffix")
                            }
                            append("。已分析过的章自动跳过，不重复消耗 API；过程中可随时停止。")
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = analysisPlan.targetCount > 0,
                        onClick = {
                            showAnalysisConfirm = false
                            app.bookAnalysisRunner.start(bookId)
                        },
                    ) { Text("开始分析") }
                },
                dismissButton = { TextButton(onClick = { showAnalysisConfirm = false }) { Text("取消") } },
            )
        }

        // 批B：事后目录校准确认弹窗
        if (showCalibrate) {
            AlertDialog(
                onDismissRequest = { showCalibrate = false },
                title = { Text("按目录重新切章？") },
                text = {
                    Text(
                        "搭子会识别书里的目录页并重新划分章节（需要联网和视觉模型，不重新识别全书）。" +
                            "阅读进度按章名保留；已生成讲解/总结/复习的书会被拒绝，以防学习记录丢失。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showCalibrate = false
                        calibBusy = true
                        calibScope.launch {
                            val r = com.studyfriend.app.data.importer.pdfpipeline.CalibrateExisting.run(app, bookId)
                            calibBusy = false
                            calibResult = r.fold({ it }, { it.message ?: "校准失败（未知原因）" })
                        }
                    }) { Text("开始校准") }
                },
                dismissButton = { TextButton(onClick = { showCalibrate = false }) { Text("取消") } },
            )
        }
        calibResult?.let { msg ->
            AlertDialog(
                onDismissRequest = { calibResult = null },
                title = { Text("目录校准") },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = { calibResult = null }) { Text("知道了") } },
            )
        }
    }
}

/** [nodes] 里某章的节行（展开箭头显隐用）；查不到=无节 */
private fun nodeSections(nodes: List<ChapterNode>, chapterId: Long) =
    nodes.firstOrNull { it.row.id == chapterId }?.sections ?: emptyList()

/**
 * 一键分析全书卡片（阅读体验轮）：空闲=入口（点了弹确认）；本书运行中=进度条+停止；
 * 结束=结果行+「知道了」。全书分析是全局单例任务——其他书在跑时本书只显示忙碌，不给启动。
 */
@Composable
private fun BookAnalysisCard(
    state: BookAnalysisState,
    thisBookId: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        when {
            state is BookAnalysisState.Running && state.bookId == thisBookId -> {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "AI 一键分析中",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                if (state.subTotal > 0) {
                                    "第 ${state.doneChapters + 1}/${state.totalChapters} 章「${state.chapterTitle}」 · ${state.phase} ${state.subDone}/${state.subTotal}"
                                } else {
                                    "第 ${state.doneChapters + 1}/${state.totalChapters} 章「${state.chapterTitle}」 · ${state.phase}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "停止",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(onClick = onStop)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 章内子进度折算成章内小数，进度条平滑推进
                    val frac = if (state.totalChapters > 0) {
                        (state.doneChapters + if (state.subTotal > 0) state.subDone.toFloat() / state.subTotal else 0f) /
                            state.totalChapters
                    } else {
                        0f
                    }
                    LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }

            state is BookAnalysisState.Queued && state.bookId == thisBookId -> {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("AI 一键分析排队中", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "搭子正在忙别的 AI 任务，轮到后自动开始",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "停止",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onStop)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }

            state is BookAnalysisState.Running || state is BookAnalysisState.Queued -> {
                // 其他书在跑：全局单例被占，不给启动（启动会替换掉那本书的任务）
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("AI 一键分析全书", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "另一本书正在分析中，等它跑完再来",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state is BookAnalysisState.Succeeded || state is BookAnalysisState.Interrupted ||
                state is BookAnalysisState.Failed -> {
                // || 条件分支里无法智能转换：内层按具体类型提取文案
                val resultMessage = when (state) {
                    is BookAnalysisState.Succeeded -> state.message
                    is BookAnalysisState.Interrupted -> state.message
                    is BookAnalysisState.Failed -> state.message
                    else -> ""
                }
                val headline = when (state) {
                    is BookAnalysisState.Succeeded -> "分析完成"
                    is BookAnalysisState.Interrupted -> "分析中断"
                    else -> "分析失败"
                }
                val tone = if (state is BookAnalysisState.Succeeded) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                }
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(headline, style = MaterialTheme.typography.titleSmall, color = tone)
                            Text(
                                resultMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "知道了",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(onClick = onClear)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            else -> {
                // 空闲：入口态
                Row(
                    Modifier
                        .clickable(onClick = onStart)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "AI 一键分析全书",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "跳过开篇、目录、参考文献，从第一章开始逐章粗读并讲解",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "开始一键分析",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    row: ChapterTreeRow,
    runState: RoughRunState,
    analysisBusy: Boolean,
    hasSections: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onForce: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val runningThis = runState is RoughRunState.Running && runState.chapterId == row.id
    // 一键分析占着全局 AI 锁时单章粗读会排队 10 分钟后失败——直接禁用行尾按钮
    val runningOther = (runState is RoughRunState.Running && runState.chapterId != row.id) || analysisBusy
    val interrupted = runState is RoughRunState.Interrupted && runState.chapterId == row.id
    // 数据推导断点：READING 且没要点 = 上次粗读没跑完（进程重启后 Runner 瞬态丢失也成立）
    val resumeByData = row.readState == DbValues.READ_READING && row.gist.isNullOrBlank()
    val noMark = row.gist.isNullOrBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // 前置/后置件徽标（第六波）：开篇/目录/附录不再是裸序号——14 万字的「目录」
                // 章夹在正文中间毫无提示的实录痛点；真章照旧显示数字序号
                val kindBadge = ChapterKinds.badgeOf(row.title)
                if (kindBadge != null) {
                    Text(
                        kindBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                } else {
                    Text(
                        "${row.idx}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    // 前置/后置件标题弱化一档（还是可点可读，只是视觉上让位于真章）
                    color = if (kindBadge != null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val subtitle = row.gist?.take(50)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // readState 彩色徽标 + 本章包徽标（M4a §3.4 视觉规格）
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 3.dp),
            ) {
                val (label, color) = when (row.readState) {
                    DbValues.READ_READING -> "在读" to MaterialTheme.colorScheme.primary
                    DbValues.READ_DONE -> "读完" to MaterialTheme.colorScheme.tertiary
                    else -> "未读" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                if (row.hasAsset) {
                    Text(
                        "本章包",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }

        // 展开/收起节行（P5 章节树；无节行不显示）
        if (hasSections) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起小节" else "展开小节",
                )
            }
        }

        when {
            runningThis && runState is RoughRunState.Running -> {
                // 规划阶段（total 还是 0）显示「规划中」，不显示 0/0 死数（装机实录「看不到进度」）
                if (runState.total == 0) {
                    Text(
                        "规划中",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        "${runState.done}/${runState.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                MiniPill("停止", onStop, neutral = true)
            }
            interrupted || resumeByData -> MiniPill("继续", onStart, enabled = !runningOther)
            noMark -> MiniPill("粗读", onStart, enabled = !runningOther)
            else -> MiniPill("重读", onForce, enabled = !runningOther)
        }
    }
}

/** 行尾轻量操作胶囊（2026-11-15 UI 打磨轮）：替代原实心大按钮——章目录页 10 行 10 个
 *  大「粗读」又吵又挤；主色只作淡染，视觉让位给章名本身。 */
@Composable
private fun MiniPill(text: String, onClick: () -> Unit, enabled: Boolean = true, neutral: Boolean = false) {
    val tint = when {
        neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = tint.copy(alpha = if (enabled) 1f else 0.45f),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = if (enabled) 0.12f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** 节行（P5）：缩进次级样式，无粗读按钮/无徽标；点击=目录锚跳转（父章+滚动定位） */
@Composable
private fun SectionRow(row: ChapterTreeRow, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 36.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Text(
            row.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
