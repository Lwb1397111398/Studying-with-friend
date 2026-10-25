package com.studyfriend.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.study.NoteRunState
import com.studyfriend.app.data.study.RoughRunState
import kotlin.math.roundToInt


// 从 ReadScreen.kt 拆出（2026-10-11 结构批次）：工具 chips 行/状态行/字号弹层/划词浮条

/**
 * 章节「工具 chips 行」（P7 收纳，合并原 ActionBar + NoteBar + 总结按钮）：
 * 一行小 chip（粗读/讲解/总结/要点）常驻，只有「有事发生」（进行中/中断/失败/待归并/
 * 讲解中断/占用提示）时下方才出现状态行；稳态只剩「标记读完」一行小字。
 * 全部状态推导表达式与原七态 ActionBar/NoteBar 一致，仅布局压缩。
 */
@Composable
internal fun ChapterToolsRow(
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
    ttsActive: Boolean = false,
    ttsAvailable: Boolean = false,
    onToggleTts: () -> Unit = {},
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
            // v7 正文听书：有可读段才显示；朗读中变「停止朗读」
            if (ttsAvailable) {
                AssistChip(
                    onClick = onToggleTts,
                    label = { Text(if (ttsActive) "停止朗读" else "朗读") },
                )
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

/** 阅读字号/行距弹层（功能补齐轮）：无级 Slider 即拖即预览（onChange 由调用方改内存态），
 *  关弹层时由调用方统一存库；「恢复默认」= 18sp / 1.7 倍 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSizeSheet(
    fontSize: Int,
    lineHeightMult: Float,
    onChange: (Int, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("阅读字号", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    onChange(
                        SettingsRepository.DEFAULT_READ_FONT_SIZE,
                        SettingsRepository.DEFAULT_READ_LINE_HEIGHT,
                    )
                }) { Text("恢复默认") }
            }
            // 预览文字按当前字号/行距渲染，拖动即时看到效果
            Text(
                "示例文字：保理合同是应收账款债权人将现有的……",
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineHeightMult).toInt().sp,
            )
            Slider(
                value = fontSize.toFloat(),
                onValueChange = { onChange(it.roundToInt(), lineHeightMult) },
                valueRange = SettingsRepository.READ_FONT_MIN.toFloat()..SettingsRepository.READ_FONT_MAX.toFloat(),
                steps = SettingsRepository.READ_FONT_MAX - SettingsRepository.READ_FONT_MIN - 1,
            )
            Text(
                "字号 $fontSize sp",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text("行距", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = lineHeightMult,
                onValueChange = { onChange(fontSize, (it * 10).roundToInt() / 10f) },
                valueRange = SettingsRepository.READ_LINE_MIN..SettingsRepository.READ_LINE_MAX,
                steps = ((SettingsRepository.READ_LINE_MAX - SettingsRepository.READ_LINE_MIN) * 10).toInt() - 1,
            )
            Text(
                "行距 ${lineHeightMult} 倍",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 划词浮条（P7）：定位在选中文字上方，提供问含义/问整段/复制/关闭 */
@Composable
internal fun SelectionFloatBar(
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
