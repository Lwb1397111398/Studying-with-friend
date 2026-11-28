package com.studyfriend.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.tts.TtsModelManager
import com.studyfriend.app.ui.components.SectionCard

/**
 * 设置页（瘦身版）：模型类配置收进「模型」页，这里只剩模型入口卡片（显示当前
 * 生效状态）、讲解详略、应用更新。原来平铺的快速填入/视觉三组已删除。
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    updateVm: UpdateViewModel,
    onOpenModels: () -> Unit,
    onOpenAiLog: () -> Unit,
) {
    // 从模型页改完返回时刷新入口摘要
    LaunchedEffect(Unit) { vm.refreshSummary() }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.headlineSmall)

            // —— 模型入口：AI 档案与各任务指派都在「模型」页配置 ——
            SectionCard {
                Text("AI 模型", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenModels)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        // 实景体验轮：未填 Key 时不显示默认模型名（新装机「主力：gpt-4o-mini（未配置
                        // API Key）」是噪音——模型名还没意义，直接给动作引导）
                        Text(
                            if (vm.keyReady) "主力：${vm.mainModel}" else "主力：未配置 API Key，点进去填好就能用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (vm.keyReady) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "讲解：${vm.noteSummary} · 视觉转写：${vm.visionSummary}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "点进去管理模型档案、按任务指派",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "进入模型页")
                }
            }

            // —— AI 运行日志入口（2026-11-28）：实时看 AI 有没有在干活、流式输出与速度 ——
            SectionCard {
                val aiLogs by com.studyfriend.app.data.ai.AiLogBus.snapshots.collectAsState()
                val running = aiLogs.count {
                    it.state == com.studyfriend.app.data.ai.AiLogState.QUEUED ||
                        it.state == com.studyfriend.app.data.ai.AiLogState.STREAMING
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAiLog)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running > 0) "AI 运行日志（进行中 $running 项）" else "AI 运行日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (running > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (running > 0) "正在干活，点开实时看流式输出与速度"
                            else "点开看每次 AI 调用的流式输出、首字速度与耗时",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "进入 AI 运行日志")
                }
            }

            // 外观主题三态（功能补齐轮）：即选即存，改完全 App 即时生效
            SectionCard {
                Text("外观", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        SettingsRepository.THEME_SYSTEM to "跟随系统",
                        SettingsRepository.THEME_LIGHT to "浅色",
                        SettingsRepository.THEME_DARK to "深色",
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = vm.themeMode == mode,
                            onClick = { vm.chooseThemeMode(mode) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // 朗读：语速 + 引擎 + 离线模型管理（同一件事的三面，收进一张卡）
            SectionCard {
                Text("朗读", style = MaterialTheme.typography.labelLarge)
                // 朗读语速（功能补齐轮正文听书）：阅读页「朗读」与本机朗读共用；拖完落库
                var rateDragging by remember { mutableStateOf(vm.ttsRate) }
                Slider(
                    value = rateDragging,
                    onValueChange = { rateDragging = (it * 10).toInt() / 10f },
                    onValueChangeFinished = { vm.chooseTtsRate(rateDragging) },
                    valueRange = SettingsRepository.TTS_RATE_MIN..SettingsRepository.TTS_RATE_MAX,
                )
                Text(
                    "${rateDragging} 倍速（0.5 ~ 2.0）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 朗读引擎（TTS 三模式）：Edge 在线（默认，无需系统引擎/无需下载模型）/
                // 手机自带 / 本地离线模型（sherpa kokoro，模型在下方下载管理）。
                // 「听的思维」同机实测：系统引擎要先声明 queries 才绑得上（旧报错的根因）
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        SettingsRepository.TTS_ENGINE_EDGE to "Edge 在线",
                        SettingsRepository.TTS_ENGINE_SYSTEM to "手机自带",
                        SettingsRepository.TTS_ENGINE_LOCAL to "离线模型",
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = vm.ttsEngine == mode,
                            onClick = { vm.chooseTtsEngine(mode) },
                            label = { Text(label) },
                        )
                    }
                }
                // Edge 音色行（选「Edge 在线」时出现）：晓晓=Edge 浏览器中文默认声，云希=男声
                if (vm.ttsEngine == SettingsRepository.TTS_ENGINE_EDGE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            SettingsRepository.EDGE_VOICE_XIAOXIAO to "晓晓（女）",
                            SettingsRepository.EDGE_VOICE_YUNXI to "云希（男）",
                        ).forEach { (v, label) ->
                            FilterChip(
                                selected = vm.ttsEdgeVoice == v,
                                onClick = { vm.chooseTtsEdgeVoice(v) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "Edge 在线朗读走微软服务器在线合成，需要网络；不依赖手机自带的朗读引擎",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TtsModelSection(vm)
            }

            // 复习到期提醒（功能补齐轮）：每日本地通知；Android 13+ 打开时请求通知权限
            SectionCard {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("复习到期提醒", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "有章目复习到期时每天提醒一次，点击通知直达 App",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = vm.reviewReminder,
                        onCheckedChange = { enabled ->
                            if (enabled && android.os.Build.VERSION.SDK_INT >= 33) {
                                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                            vm.chooseReviewReminder(enabled)
                        },
                    )
                }
            }

            // 讲解详略三档（总计划 §1 目标 7）：即选即存
            SectionCard {
                Text("讲解详略", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsRepository.DETAIL_LEVELS.forEach { level ->
                        FilterChip(
                            selected = vm.detailLevel == level,
                            onClick = { vm.chooseDetailLevel(level) },
                            label = { Text(level) },
                        )
                    }
                }
            }

            // 每日阅读目标（P1-⑤）：复习统计卡的进度环与热力图色阶都以此为基准
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每日阅读目标", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "复习页统计卡会显示今日进度环，达成当天热力图格子会点亮",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { vm.changeDailyGoal(vm.dailyGoal - 5) }) { Text("−") }
                        Text(
                            "${vm.dailyGoal} 分钟",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                        OutlinedButton(onClick = { vm.changeDailyGoal(vm.dailyGoal + 5) }) { Text("+") }
                    }
                }
            }

            // —— 应用更新（M7）：手机上直接升级，不用连电脑传 APK ——
            SectionCard {
                Text("应用更新", style = MaterialTheme.typography.labelLarge)
                Text(
                    "当前版本 v${updateVm.currentVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动检查更新", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "每天最多联网检查一次，发现新版本弹窗提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = updateVm.autoCheck, onCheckedChange = { updateVm.changeAutoCheck(it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { updateVm.checkNow() },
                        enabled = !updateVm.checking && !updateVm.downloading,
                    ) { Text(if (updateVm.checking) "检查中…" else "检查更新") }
                }
                if (updateVm.checking || updateVm.downloading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                updateVm.status?.let {
                    Text(
                        it,
                        color = if (updateVm.statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            BackupSection(vm)
        }
    }
}

/** 备份与恢复（数据安全轮）：SAF 选位置导出 JSON / 选文件校验后确认整库替换 */
@Composable
private fun BackupSection(vm: SettingsViewModel) {
    // SAF 存新文件（导出）：系统文件选择器落到用户选的位置
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::exportBackup) }
    // SAF 选已有文件（恢复）：先只解析校验，确认框点了才动库
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::previewRestore) }
    // 诊断日志导出（数据安全轮）：最近 7 天日志合并成一个 txt
    val diagLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let(vm::exportDiag) }

    SectionCard {
        Text("备份与恢复", style = MaterialTheme.typography.labelLarge)
        Text(
            "导出全部学习数据（书、讲解、批注、进度）为一个 JSON 文件，换机/重装后可整库恢复。" +
                "API Key 不包含在备份里，恢复后需重新填写。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !vm.backupBusy,
                onClick = { exportLauncher.launch(backupFileName()) },
            ) { Text("导出备份") }
            OutlinedButton(
                enabled = !vm.backupBusy,
                onClick = { restoreLauncher.launch(arrayOf("*/*")) },
            ) { Text("从备份恢复") }
        }
        vm.backupMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.backupIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }

    // 诊断日志（数据安全轮）：崩溃与 AI 任务失败原因按天滚动记 7 天，这里合并导出
    SectionCard {
        Text("诊断", style = MaterialTheme.typography.labelLarge)
        Text(
            "App 崩溃与 AI 任务失败的原因会自动记录（保留最近 7 天）。遇到问题时导出发给 AI 排障。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { diagLauncher.launch(diagFileName()) }) { Text("导出诊断日志") }
    }

    // 恢复确认：明示覆盖语义（整库替换，无法撤销）
    vm.pendingRestore?.let { pending ->
        val s = pending.summary
        AlertDialog(
            onDismissRequest = { vm.cancelRestore() },
            title = { Text("恢复这个备份？") },
            text = {
                Text(
                    "备份内容：${s.books} 本书、${s.chapters} 章、${s.paragraphs} 段正文、" +
                        "讲解 ${s.paraNotes} 条、批注 ${s.paraMemos} 条。\n\n" +
                        "当前全部学习数据（书、讲解、批注、做题与复习记录）将被覆盖，此操作无法撤销。",
                )
            },
            confirmButton = {
                Button(onClick = { vm.confirmRestore() }) {
                    Text("覆盖并恢复", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { vm.cancelRestore() }) { Text("取消") }
            },
        )
    }
}

/** 导出文件默认名：studyfriend-backup-20261025-1530.json */
private fun backupFileName(): String {
    val stamp = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
    return "studyfriend-backup-$stamp.json"
}

/** 诊断日志导出默认名：studyfriend-diag-20261025-1530.txt */
private fun diagFileName(): String {
    val stamp = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
    return "studyfriend-diag-$stamp.txt"
}

/** 离线朗读模型下载管理区（TTS 双模式之「离线模型」）：状态/进度/下载/删除。
 *  下载是 App 级任务（TtsModelManager 自持 scope），离开设置页继续、回页看进度。 */
@Composable
private fun TtsModelSection(vm: SettingsViewModel) {
    val dl by TtsModelManager.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    // 非下载中状态变化后刷新就绪状态（下载完成/删除/失败都走这里）
    LaunchedEffect(dl) {
        if (dl !is TtsModelManager.DownloadState.Running) vm.refreshTtsModel()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val s = dl) {
            is TtsModelManager.DownloadState.Running -> {
                val pct = if (s.total > 0) (s.done * 100 / s.total).toInt() else 0
                Text(
                    "正在下载离线朗读模型：${mb(s.done)} / ${mb(s.total)} MB（$pct%，中断后可续传）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is TtsModelManager.DownloadState.Failed -> {
                Text(
                    "下载失败：${s.message}（已下内容保留，点下载从断点继续）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { vm.startModelDownload() }) { Text("继续下载") }
            }

            else -> {
                if (vm.ttsModelReady) {
                    Text(
                        "离线朗读模型已就绪（${mb(vm.ttsModelBytes)} MB）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("删除模型") }
                } else {
                    Text(
                        "离线朗读模型未下载（全套约 215MB，国内镜像直连，支持断点续传）" +
                            if (vm.ttsModelBytes > 0) "，已下 ${mb(vm.ttsModelBytes)} MB" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { vm.startModelDownload() }) { Text("下载模型") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除离线朗读模型？") },
            text = { Text("会释放约 ${mb(vm.ttsModelBytes)} MB 空间；下次想用需要重新下载（支持断点续传）。朗读引擎可随时切回「手机自带」。") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    vm.deleteTtsModel()
                }) { Text("删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1048576.0)
