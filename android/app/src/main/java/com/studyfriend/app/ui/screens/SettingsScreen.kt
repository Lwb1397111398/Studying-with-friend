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
fun SettingsScreen(vm: SettingsViewModel) {
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
                            "转写失败的页自动回退文字层内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.visionEnabled, onCheckedChange = { vm.changeVisionEnabled(it) })
            }
            if (vm.visionEnabled) {
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
