package com.studyfriend.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studyfriend.app.data.ai.AiLogBus
import com.studyfriend.app.data.ai.AiLogSession
import com.studyfriend.app.data.ai.AiLogState
import kotlinx.coroutines.delay

/**
 * AI 运行日志页（2026-11-28）：老板诉求「以前完全不知道 AI 有没有运转、速度如何」——
 * 每次调用一条卡片：状态徽章 + 首字延迟/字数/速度统计 + 流式正文实时追加（活跃时
 * 250ms 跳动刷新）。最新在最上，进行中的永远在第一眼位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiLogScreen(onBack: () -> Unit) {
    val sessions by AiLogBus.snapshots.collectAsState()

    // 活跃调用存在时每 250ms 驱动一次重组：排队时长/已出字数/实时速度跟着跳，
    // 老板能直观看到「在动」。全部终态后停表不再空转
    val hasActive = sessions.any { it.state == AiLogState.QUEUED || it.state == AiLogState.STREAMING }
    val nowMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasActive) {
        while (hasActive) {
            delay(250)
            nowMs.value = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { AiLogBus.clear() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空日志")
                    }
                },
            )
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有 AI 调用记录。\n\n粗读、讲解、随手问、总结等触发后，" +
                        "这里会实时显示每次调用的流式输出、首字速度与耗时。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.id }) { s -> AiLogCard(s, nowMs.value) }
            }
        }
    }
}

/** 单次调用卡片：头行（来源+状态徽章）→ 统计行 → 流式正文（默认收 6 行，点击展开） */
@Composable
private fun AiLogCard(s: AiLogSession, nowMs: Long) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.source,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StateBadge(s.state)
            }
            Text(
                "${s.model} · ${timeLabel(s.startMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                statsLine(s, nowMs),
                style = MaterialTheme.typography.bodySmall,
                color = if (s.state == AiLogState.ERROR) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 思考过程区（2026-11-28 老板诉求「日志里也想看思考」）：推理模型思考实时
            // 追加；点卡片展开逻辑与正文共用（expanded 同一状态）
            if (s.thinking.isNotEmpty()) {
                Text(
                    "思考过程",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    s.thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (s.text.isNotEmpty()) {
                Text(
                    s.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!expanded && s.text.length >= 200) {
                    Text(
                        "点开看全文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateBadge(state: AiLogState) {
    val (label, color) = when (state) {
        AiLogState.QUEUED -> "排队中" to MaterialTheme.colorScheme.tertiary
        AiLogState.STREAMING -> "输出中" to MaterialTheme.colorScheme.primary
        AiLogState.DONE -> "完成" to MaterialTheme.colorScheme.primary
        AiLogState.ERROR -> "失败" to MaterialTheme.colorScheme.error
        AiLogState.CANCELLED -> "已取消" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.12f)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 统计行：按状态给不同口径——排队中看排队时长，输出中看实时速度，终态看均速/首字 */
private fun statsLine(s: AiLogSession, nowMs: Long): String = when (s.state) {
    AiLogState.QUEUED ->
        "已排队 ${AiLogBus.fmtSec((nowMs - s.startMs) / 1000.0)}（限流窗口排队，满 15 次/分钟自动放行）"
    AiLogState.STREAMING -> {
        val parts = mutableListOf<String>()
        val first = s.firstDeltaMs
        if (first == null) {
            if (s.thinking.isNotEmpty()) {
                // 推理模型思考流出中：跳动显示思考时长（正文首字前的等待不再是黑盒）
                s.thinkFirstMs?.let { t -> parts.add("思考中 ${AiLogBus.fmtSec((nowMs - t) / 1000.0)}") }
            } else {
                parts.add("已发出请求，等待首字… ${AiLogBus.fmtSec((nowMs - (s.connectMs ?: s.startMs)) / 1000.0)}")
            }
        } else {
            s.thinkFirstMs?.let { t -> parts.add("思考 ${AiLogBus.fmtSec((first - t) / 1000.0)}") }
            parts.add("首字 ${AiLogBus.fmtSec((first - (s.connectMs ?: s.startMs)) / 1000.0)}")
            parts.add("已出 ${s.chars} 字")
            val span = nowMs - first
            if (span > 300) parts.add(AiLogBus.fmtSpeed(s.chars, span))
        }
        s.connectMs?.let { q -> if (q - s.startMs >= 500) parts.add("排队 ${AiLogBus.fmtSec((q - s.startMs) / 1000.0)}") }
        parts.joinToString(" · ")
    }
    AiLogState.DONE -> {
        val parts = mutableListOf<String>()
        s.thinkFirstMs?.let { t ->
            // 思考时长=思考首段→正文首字（思考吃满预算无正文时→结束时刻）
            parts.add("思考 ${AiLogBus.fmtSec(((s.firstDeltaMs ?: s.endMs ?: 0L) - t) / 1000.0)}")
        }
        s.firstDeltaMs?.let { first ->
            parts.add("首字 ${AiLogBus.fmtSec((first - (s.connectMs ?: s.startMs)) / 1000.0)}")
            s.endMs?.let { end ->
                val span = end - first
                if (span > 0) parts.add(AiLogBus.fmtSpeed(s.chars, span))
            }
        }
        s.endMs?.let { end -> parts.add("共 ${s.chars} 字 / ${AiLogBus.fmtSec((end - s.startMs) / 1000.0)}") }
            ?: parts.add("共 ${s.chars} 字")
        parts.joinToString(" · ")
    }
    AiLogState.ERROR -> "失败：${s.error ?: "未知错误"}"
    AiLogState.CANCELLED -> "已取消（已出 ${s.chars} 字）"
}

private fun timeLabel(startMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(startMs))
