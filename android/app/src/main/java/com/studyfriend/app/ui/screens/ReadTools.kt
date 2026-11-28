package com.studyfriend.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.studyfriend.app.R
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
    /** 第九波：TTS 暂停中（chip 变「继续朗读」，点击恢复续播） */
    ttsPaused: Boolean = false,
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
    // 排队等待中（体验增量轮）：自己的任务在等全局锁（前一个任务没跑完）
    val queuedThis = runState is RoughRunState.Queued && runState.chapterId == chapterId

    val allMarked = paragraphCount > 0 && markedCount == paragraphCount
    val partiallyMarked = markedCount in 1 until paragraphCount

    val noteRunningThis = noteState is NoteRunState.Running && noteState.chapterId == chapterId
    val noteQueuedThis = noteState is NoteRunState.Queued && noteState.chapterId == chapterId
    val roughBusy = runState is RoughRunState.Running || runState is RoughRunState.Queued
    val noteBusy = (gateLabel != null && !noteRunningThis && !noteQueuedThis) || roughBusy

    // force 重读/重生成会覆盖既有标注：二次确认防误触（原 ActionBar/NoteBar 保留）
    var showRoughForceConfirm by remember { mutableStateOf(false) }
    var showNoteForceConfirm by remember { mutableStateOf(false) }

    // 阅读主题包（第六波）：固定暗纸色（墨玉/纯黑）下全局浅色主题的 chip 文字/描边
    // 对比不足（装机实录「粗读/朗读」暗琥珀压黑棕几乎不可见）——文字跟随纸色，
    // 描边用主题次级色弱化一档；跟随主题时 Color.Unspecified 回退 M3 现状
    val rt = LocalReadingTheme.current
    val chipText = rt?.text ?: Color.Unspecified
    val chipColors = AssistChipDefaults.assistChipColors(labelColor = chipText)
    val chipBorder = rt?.let { BorderStroke(1.dp, it.secondary.copy(alpha = 0.55f)) }

    Column(Modifier.fillMaxWidth()) {
        // 工具 chips 行：横向可滚（窄屏四个 chip 放得下，保险起见可滚）
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 粗读 chip：未开始→粗读；断点→继续 x/y；已标完→重读（force 确认）；进行中→灰显进度；
            // 规划阶段（total 还是 0，AI 正在通读本章拆讲解单元）显示「规划中」不显示 0/0（装机实录「看不到进度」）
            val roughRunning = runState as? RoughRunState.Running
            val roughLabel = when {
                pureToc || paragraphCount == 0 -> "目录章"
                runningThis -> if ((roughRunning?.total ?: 0) == 0) "粗读规划中" else "粗读 ${roughRunning!!.done}/${roughRunning.total}"
                queuedThis -> "粗读排队中"
                allMarked -> "重读"
                partiallyMarked -> "继续 $markedCount/$paragraphCount"
                else -> "粗读"
            }
            AssistChip(
                onClick = { if (allMarked) showRoughForceConfirm = true else onStart() },
                enabled = paragraphCount > 0 && !pureToc && !roughBusy && !runningOther,
                label = { Text(roughLabel) },
                colors = chipColors,
                border = chipBorder,
            )
            // 讲解 chip：无可讲解单元（纯目录章等）不显示；规划态同粗读（装机实录「看不到进度」）
            if (noteUnitCount > 0) {
                val noteRunning = noteState as? NoteRunState.Running
                val noteLabel = when {
                    noteRunningThis -> if ((noteRunning?.total ?: 0) == 0) "讲解规划中" else "讲解 ${noteRunning!!.done}/${noteRunning.total}"
                    noteQueuedThis -> "讲解排队中"
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
                    colors = chipColors,
                    border = chipBorder,
                )
            }
            // 章末总结入口：本章已粗读标注即可进入（M5 §3.7）
            if (markedCount > 0) {
                AssistChip(onClick = onOpenSummary, label = { Text("总结") }, colors = chipColors, border = chipBorder)
            }
            // 要点卡开关：本章要点存在才显示
            if (hasGist) {
                AssistChip(
                    onClick = onToggleGist,
                    label = { Text(if (gistExpanded) "收起要点" else "要点") },
                    colors = chipColors,
                    border = chipBorder,
                )
            }
            // v7 正文听书：有可读段才显示；朗读中变「停止朗读」，暂停中变「继续朗读」（第九波）
            if (ttsAvailable) {
                AssistChip(
                    onClick = onToggleTts,
                    label = {
                        Text(
                            when {
                                ttsActive && ttsPaused -> "继续朗读"
                                ttsActive -> "停止朗读"
                                else -> "朗读"
                            },
                        )
                    },
                    colors = chipColors,
                    border = chipBorder,
                )
            }
        }

        // 状态行：只在有事发生时出现（P7 收纳核心）
        when {
            runningThis -> {
                val r = runState as RoughRunState.Running
                if (r.total == 0) {
                    // 规划阶段（AI 正在通读本章拆讲解单元，还没有块数）：不定进度条+明说阶段
                    // （装机实录：原来显示「粗读 0/0」死条不动，老板看不到进度）
                    TaskProgressRow(label = "搭子正在通读本章，规划讲解单元…", progress = -1f, onStop = onStop)
                } else {
                    TaskProgressRow(
                        label = "搭子正在粗读 ${r.done}/${r.total}",
                        progress = r.done.toFloat() / r.total,
                        onStop = onStop,
                    )
                }
            }

            // 排队等待中（体验增量轮）：前一个任务释放全局锁后自动开跑，可取消
            queuedThis -> {
                TaskProgressRow(label = "排队等待中（搭子正忙别的 AI 任务）", progress = 0f, onStop = onStop)
            }

            noteQueuedThis -> {
                TaskProgressRow(label = "排队等待中（搭子正忙别的 AI 任务）", progress = 0f, onStop = onNoteStop)
            }

            noteRunningThis -> {
                val n = noteState as NoteRunState.Running
                if (n.total == 0) {
                    // 规划阶段：讲解单元还没拆出来，同粗读显示不定进度+阶段文案
                    TaskProgressRow(label = "搭子正在准备讲解，先规划讲解单元…", progress = -1f, onStop = onNoteStop)
                } else {
                    TaskProgressRow(
                        label = "搭子正在讲解 ${n.done}/${n.total}",
                        progress = n.done.toFloat() / n.total,
                        onStop = onNoteStop,
                    )
                }
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

/** 进行中状态条：文案 + 细进度条 + 停止。progress<0 = 规划阶段不定进度条（来回扫） */
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
        if (progress < 0f) {
            LinearProgressIndicator(modifier = Modifier.weight(1f))
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(onClick = onStop) { Text("停止") }
    }
}

/** 阅读字号/行距弹层（功能补齐轮）：无级 Slider 即拖即预览（onChange 由调用方改内存态），
 *  关弹层时由调用方统一存库；「恢复默认」= 18sp / 1.7 倍。
 *  体验优化轮：+翻页模式开关（点屏幕两侧/左右滑翻页，即时存库即时生效）。
 *  第六波：+阅读背景色卡（跟随书房/纸白/羊皮纸/护眼绿/墨玉/纯黑，即点即存即时生效）
 *  与音量键翻页开关（单手翻条文，借鉴 legado） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSizeSheet(
    fontSize: Int,
    lineHeightMult: Float,
    pageMode: Boolean,
    onPageModeChange: (Boolean) -> Unit,
    themeId: String,
    onThemeChange: (String) -> Unit,
    volumeKey: Boolean,
    onVolumeKeyChange: (Boolean) -> Unit,
    fontFamilyId: String,
    onFontFamilyChange: (String) -> Unit,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    onChange: (Int, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        onChange(
                            SettingsRepository.DEFAULT_READ_FONT_SIZE,
                            SettingsRepository.DEFAULT_READ_LINE_HEIGHT,
                        )
                    }) { Text("恢复默认") }
                    // 显式关闭按钮（第八波装机实录）：ModalBottomSheet 点 scrim/下拉关闭
                    // 手势在本机实测无效（BACK 是唯一途径），高频弹层给个可见出口
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
            }
            // 预览文字按当前字号/行距/字体渲染，拖动即时看到效果
            Text(
                "示例文字：保理合同是应收账款债权人将现有的……",
                fontSize = fontSize.sp,
                lineHeight = (fontSize * lineHeightMult).toInt().sp,
                fontFamily = if (fontFamilyId == SettingsRepository.READ_FONT_LXGW) {
                    FontFamily(Font(R.font.lxgw_wenkai_lite_regular))
                } else null,
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

            Spacer(Modifier.height(12.dp))
            // 翻页模式（体验优化轮）：开=点屏幕左/右 1/3 前后翻一屏 + 左右滑翻页；关=上下滚动
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("翻页模式", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "点屏幕两侧或左右滑动翻页；关闭则上下滚动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = pageMode, onCheckedChange = onPageModeChange)
            }

            Spacer(Modifier.height(12.dp))
            // 阅读背景（第六波）：色卡行即点即存——圆底色块 + 选中描边，首项是「跟随书房」
            Text("阅读背景", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // 「跟随书房」色卡：画一个 4 象限示意（浅暖纸 + 深暖墨各半）
                ThemeSwatch(
                    selected = themeId == SettingsRepository.KEY_READ_THEME_DEFAULT,
                    label = "跟随",
                    follow = true,
                    onClick = { onThemeChange(SettingsRepository.KEY_READ_THEME_DEFAULT) },
                )
                ReadingTheme.ALL.forEach { t ->
                    ThemeSwatch(selected = themeId == t.id, label = t.label, bg = t.bg, onClick = { onThemeChange(t.id) })
                }
            }
            Text(
                "护眼绿/纯黑适合长时间夜读；纯黑省电最明显（OLED）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(12.dp))
            // 音量键翻页（第六波，借鉴 legado）：开=音量加/减翻上/下一页，单手翻条文不摸屏幕
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("音量键翻页", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "音量加=上一页、音量减=下一页",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = volumeKey, onCheckedChange = onVolumeKeyChange)
            }

            Spacer(Modifier.height(12.dp))
            // 阅读字体（第七波）：默认系统字体 / 霞鹜文楷 Lite（OFL-1.1 随包再分发）——
            // 正文段与节标题即时切换，即点即存
            Text("阅读字体", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = fontFamilyId == SettingsRepository.READ_FONT_DEFAULT,
                    onClick = { onFontFamilyChange(SettingsRepository.READ_FONT_DEFAULT) },
                    label = { Text("系统默认") },
                )
                FilterChip(
                    selected = fontFamilyId == SettingsRepository.READ_FONT_LXGW,
                    onClick = { onFontFamilyChange(SettingsRepository.READ_FONT_LXGW) },
                    label = {
                        Text(
                            "霞鹜文楷",
                            fontFamily = if (fontFamilyId == SettingsRepository.READ_FONT_LXGW) {
                                FontFamily(Font(R.font.lxgw_wenkai_lite_regular))
                            } else null,
                        )
                    },
                )
            }
            Text(
                "楷体风格开源字体（OFL 许可），正文与节标题生效",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(12.dp))
            // 阅读页亮度（第七波，借鉴 legado）：拖动即生效（只覆盖阅读页窗口），
            // 关弹层统一存库；「跟随系统」恢复 -1
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("亮度", style = MaterialTheme.typography.titleMedium)
                if (brightness >= 0f) {
                    TextButton(onClick = { onBrightnessChange(-1f) }) { Text("跟随系统") }
                }
            }
            Slider(
                value = if (brightness >= 0f) brightness else 0.6f,
                onValueChange = { onBrightnessChange((it * 100).roundToInt() / 100f) },
                valueRange = SettingsRepository.READ_BRIGHTNESS_MIN..SettingsRepository.READ_BRIGHTNESS_MAX,
            )
            Text(
                if (brightness >= 0f) "亮度 ${(brightness * 100).roundToInt()}%（只对阅读页生效）" else "跟随系统亮度",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Aa 弹层阅读背景色卡（第六波）：40dp 圆形色块，选中=主色描边 + 底部小字。
 *  follow=true 时画 4 象限示意（浅暖纸+深暖墨），表达「跟随全局主题」的语义 */
@Composable
private fun ThemeSwatch(
    selected: Boolean,
    label: String,
    follow: Boolean = false,
    bg: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = bg,
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        ) {
            if (follow) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFFFAF4E2)))
                    Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFF2B2926)))
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 划词浮条（P7 + 划词扩选轮 + 学习闭环轮）：定位在选中文字上方，问含义问的就是当前高亮的整块；
 * 「扩句/扩段」一键改选区（拖拽把手之外的快捷路径），「记一笔」把选中原文带进批注草稿，复制取当前高亮；
 * 「朗读」从选中文字所在段落开始听书（起点朗读轮，2026-11-28）。
 */
@Composable
internal fun SelectionFloatBar(
    anchor: IntOffset,
    boxOrigin: IntOffset,
    onMeaning: () -> Unit,
    onExpandSentence: () -> Unit,
    onExpandPara: () -> Unit,
    onMemo: () -> Unit,
    onSpeakFrom: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val screenWpx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt()
    val barWpx = with(density) { 356.dp.toPx() }.toInt()
    val barHpx = with(density) { 52.dp.toPx() }.toInt()
    val gap = with(density) { 8.dp.toPx() }.toInt()
    // 水平方向留 16dp 屏幕安全边距（实景体验轮 2026-11-21：此前钳 0~屏宽，选中屏幕左端的
    // 文字时浮条「问含义」按钮顶死屏幕边，窄屏/全面屏手势区观感差）
    val edge = with(density) { 16.dp.toPx() }.toInt()
    val x = (anchor.x - boxOrigin.x - barWpx / 2)
        .coerceIn(edge, (screenWpx - barWpx - edge).coerceAtLeast(edge))
    val y = (anchor.y - boxOrigin.y - barHpx - gap).coerceAtLeast(0)
    // dismissOnClickOutside 必须显式关：部分 Compose 版本非 focusable Popup 也会把浮条外
    // 的按下判成 outside → onDismissRequest 清选区，拖把手 down 一落选区就没了（模拟器实测）
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(x, y),
        onDismissRequest = onDismiss,
        properties = PopupProperties(dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 8.dp,
            // 暗色主题下 surface 与页面背景同色系、阴影几乎不可见，浮条「文字叠文字」
            // 没有浮起感（《侵权行为》装机实录）——换 surfaceContainerHighest 提亮一层 +
            // outlineVariant 细描边，深浅两主题都浮得起来
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onMeaning,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("问含义", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(
                    onClick = onExpandSentence,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("扩句", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(
                    onClick = onExpandPara,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("扩段", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(
                    onClick = onMemo,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("记一笔", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(
                    onClick = onSpeakFrom,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("朗读", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("复制", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "取消划词")
                }
            }
        }
    }
}
