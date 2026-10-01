package com.studyfriend.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.studyfriend.app.data.SettingsRepository

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, updateVm: UpdateViewModel) {
    val busy = vm.busy
    val testing = vm.testing
    val message = vm.message
    val error = vm.error
    val hasKey = vm.hasKey
    val liveDelta = vm.liveDelta

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

            if (busy || testing) LinearProgressIndicator(Modifier.fillMaxWidth())

            OutlinedTextField(
                value = vm.baseUrl,
                onValueChange = { vm.baseUrl = it },
                label = { Text("API 地址") },
                placeholder = { Text("https://api.openai.com/v1（兼容 OpenAI 格式，一般以 /v1 结尾）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // 快捷预设：一键填入服务商地址与推荐模型（保存前不落库）；FlowRow 换行防溢出
            Text("快速填入", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsRepository.PRESETS.forEach { (label, base, model) ->
                    FilterChip(
                        selected = vm.baseUrl == base && vm.model == model,
                        onClick = { vm.applyPreset(base, model) },
                        label = { Text(label) },
                    )
                }
            }

            OutlinedTextField(
                value = vm.keyInput,
                onValueChange = { vm.keyInput = it },
                label = { Text("API Key") },
                placeholder = { Text(if (hasKey) "已保存（输入以更换）" else "sk-…") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    if (hasKey) TextButton(onClick = { vm.clearKey() }) { Text("清除") }
                },
            )
            OutlinedTextField(
                value = vm.model,
                onValueChange = { vm.model = it },
                label = { Text("模型名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = vm.tempText,
                onValueChange = { vm.tempText = it },
                label = { Text("温度（0.0–1.0，非法值用 0.3）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // 视觉兜底（OPT-E）：扫描版/坏字页交给视觉模型整页转写；开关即点即存
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("识别困难的页用视觉模型转写", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "扫描版 PDF 和文字损坏的页面交给视觉模型识别；需服务商提供视觉模型，" +
                            "转写失败的页自动回退文字层内容。可单独配视觉服务商，留空跟随上方配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.visionEnabled, onCheckedChange = { vm.changeVisionEnabled(it) })
            }
            if (vm.visionEnabled) {
                OutlinedTextField(
                    value = vm.visionBaseUrl,
                    onValueChange = { vm.visionBaseUrl = it },
                    label = { Text("视觉 API 地址（保存后生效）") },
                    placeholder = { Text("留空用上方 API 地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = vm.visionKeyInput,
                    onValueChange = { vm.visionKeyInput = it },
                    label = { Text("视觉 API Key") },
                    placeholder = { Text(if (vm.hasVisionKey) "已保存（输入以更换）" else "留空用上方 API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (vm.hasVisionKey) TextButton(onClick = { vm.clearVisionKey() }) { Text("清除") }
                    },
                )
                OutlinedTextField(
                    value = vm.visionModel,
                    onValueChange = { vm.visionModel = it },
                    label = { Text("视觉模型名（保存后生效）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

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
                TextButton(onClick = { updateVm.openGuide() }) { Text("如何获取令牌？") }
            }
            OutlinedTextField(
                value = updateVm.tokenInput,
                onValueChange = { updateVm.tokenInput = it },
                label = { Text("GitHub 访问令牌（私有仓库更新用）") },
                placeholder = { Text(if (updateVm.hasToken) "已保存（输入以更换）" else "github_pat_…") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    if (updateVm.hasToken) TextButton(onClick = { updateVm.clearToken() }) { Text("清除") }
                },
            )
            OutlinedButton(
                onClick = { updateVm.saveToken() },
                enabled = updateVm.tokenInput.isNotBlank(),
            ) { Text("保存令牌") }
            updateVm.status?.let {
                Text(
                    it,
                    color = if (updateVm.statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save() }, enabled = !busy && !testing) { Text("保存") }
                OutlinedButton(
                    onClick = { if (testing) vm.cancelTest() else vm.testConnection() },
                    enabled = !busy,
                ) { Text(if (testing) "取消测试" else "测试连接") }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            if (liveDelta.isNotEmpty()) {
                Text(
                    liveDelta.take(200),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
