package com.studyfriend.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studyfriend.app.data.SettingsRepository

/**
 * 设置页（瘦身版）：模型类配置收进「模型」页，这里只剩模型入口卡片（显示当前
 * 生效状态）、讲解详略、应用更新。原来平铺的快速填入/视觉三组已删除。
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    updateVm: UpdateViewModel,
    onOpenModels: () -> Unit,
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

            // —— 模型入口：AI 功能的地址/Key/模型都在「模型」页配置 ——
            Text("AI 模型", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenModels)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (vm.keyReady) "主力：${vm.mainModel}" else "主力：${vm.mainModel}（未配置 API Key）",
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
                        "点进去配置地址、Key、各任务的模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "进入模型页")
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // 外观主题三态（功能补齐轮）：即选即存，改完全 App 即时生效
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

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // 朗读语速（功能补齐轮正文听书）：阅读页「朗读」与本机朗读共用；拖完落库
            Text("朗读语速", style = MaterialTheme.typography.labelLarge)
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

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // 复习到期提醒（功能补齐轮）：每日本地通知；Android 13+ 打开时请求通知权限
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

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // 讲解详略三档（总计划 §1 目标 7）：即选即存
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

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // —— 应用更新（M7）：手机上直接升级，不用连电脑传 APK ——
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
    }
}
