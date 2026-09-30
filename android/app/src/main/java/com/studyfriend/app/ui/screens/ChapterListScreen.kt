package com.studyfriend.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.ChapterWithAsset
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.study.RoughRunState
import com.studyfriend.app.data.study.SummaryPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 章目录页（计划 M4a §3）：每章一行 = 序号/标题/一行摘要（视觉徽标延至 M7），
 * 行尾粗读操作（未粗读→粗读；中断→继续；已完成→重读；进行中→进度+停止）。
 * 点行进阅读页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    onOpenOverview: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as StudyApp
    val chapters by app.database.chapterDao().byBookFlow(bookId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // M5 线程纪律：runner.state 由后台线程写入，collect 后切主线程再写快照（M6 复审统一口径）
    val runState by produceState<RoughRunState>(RoughRunState.Idle) {
        app.roughReadRunner.state.collect { withContext(Dispatchers.Main) { value = it } }
    }
    var bookTitle by remember { mutableStateOf("") }
    LaunchedEffect(bookId) {
        bookTitle = app.database.bookDao().get(bookId)?.title.orEmpty()
    }
    // 全书总览入口门控（M6 计划 §3.3）：≥2 个已总结章才给入口（与 planner 前置同口径）
    val summarizedCount = chapters.count {
        it.assetPromptVersion == SummaryPlanner.SUMMARY_VERSION && it.assetSummaryPresent
    }
    // 覆盖重读会清掉全章标注并重烧 API，与阅读页同口径加二次确认（最终 QA P1）
    var confirmForceFor by remember { mutableStateOf<ChapterWithAsset?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(bookTitle.ifBlank { "章节" }) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        if (chapters.isEmpty()) {
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
            items(chapters, key = { it.id }) { ch ->
                ChapterRow(
                    ch = ch,
                    runState = runState,
                    onStart = { app.roughReadRunner.start(ch.id, force = false) },
                    onForce = { confirmForceFor = ch },
                    onStop = { app.roughReadRunner.stop() },
                    onOpen = { onOpenChapter(ch.id) },
                )
                HorizontalDivider()
            }
        }

        // 覆盖重读二次确认（文案与阅读页一致）
        confirmForceFor?.let { ch ->
            AlertDialog(
                onDismissRequest = { confirmForceFor = null },
                title = { Text("重新粗读这一章？") },
                text = { Text("搭子会重新阅读全部段落并覆盖现有标注，已生成的内容将被替换。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmForceFor = null
                        app.roughReadRunner.start(ch.id, force = true)
                    }) { Text("覆盖重读") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmForceFor = null }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun ChapterRow(
    ch: ChapterWithAsset,
    runState: RoughRunState,
    onStart: () -> Unit,
    onForce: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val runningThis = runState is RoughRunState.Running && runState.chapterId == ch.id
    val runningOther = runState is RoughRunState.Running && runState.chapterId != ch.id
    val interrupted = runState is RoughRunState.Interrupted && runState.chapterId == ch.id
    // 数据推导断点：READING 且没要点 = 上次粗读没跑完（进程重启后 Runner 瞬态丢失也成立）
    val resumeByData = ch.readState == DbValues.READ_READING && ch.gist.isNullOrBlank()
    val noMark = ch.gist.isNullOrBlank()

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
                    "${ch.idx}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(ch.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val subtitle = ch.gist?.take(50)
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
                val (label, color) = when (ch.readState) {
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
                if (ch.hasAsset) {
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
