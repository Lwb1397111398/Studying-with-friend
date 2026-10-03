package com.studyfriend.app.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.ChapterTreeRow
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.study.RoughRunState
import com.studyfriend.app.data.study.SummaryPlanner
import kotlinx.coroutines.Dispatchers
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
        it.level == 1 && it.assetPromptVersion == SummaryPlanner.SUMMARY_VERSION && it.assetSummaryPresent
    }
    // 覆盖重读会清掉全章标注并重烧 API，与阅读页同口径加二次确认（最终 QA P1）
    var confirmForceFor by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(bookTitle.ifBlank { "章节" }) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        if (items.isEmpty()) {
            EmptyState(title = "没有章节", subtitle = "这本书还没有解析出章节")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (summarizedCount >= 2) {
                item(key = "overview_entry") {
                    OutlinedButton(
                        onClick = onOpenOverview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text("全书总览（已总结 $summarizedCount 章）") }
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
                HorizontalDivider()
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
    }
}

/** [nodes] 里某章的节行（展开箭头显隐用）；查不到=无节 */
private fun nodeSections(nodes: List<ChapterNode>, chapterId: Long) =
    nodes.firstOrNull { it.row.id == chapterId }?.sections ?: emptyList()

@Composable
private fun ChapterRow(
    row: ChapterTreeRow,
    runState: RoughRunState,
    hasSections: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onForce: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val runningThis = runState is RoughRunState.Running && runState.chapterId == row.id
    val runningOther = runState is RoughRunState.Running && runState.chapterId != row.id
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
                Text(
                    "${row.idx}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Text(
                    "${runState.done}/${runState.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onStop) { Text("停止") }
            }
            interrupted || resumeByData -> TextButton(onClick = onStart, enabled = !runningOther) { Text("继续") }
            noMark -> Button(onClick = onStart, enabled = !runningOther) { Text("粗读") }
            else -> OutlinedButton(onClick = onForce, enabled = !runningOther) { Text("重读") }
        }
    }
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
