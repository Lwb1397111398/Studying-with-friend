package com.studyfriend.app.ui.screens

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
