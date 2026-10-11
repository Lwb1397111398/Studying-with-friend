package com.studyfriend.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 模型页：全部 AI 模型配置收拢在此——主力模型（总结/总览/思维导图）、讲解模型
 * （逐段高频任务，可选低成本）、扫描书视觉转写 + 备用视觉、本地 OCR 识别模型。
 * 「查找模型」从服务商拉列表点选，拉不动仍可手填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel, onBack: () -> Unit) {
    val busy = vm.busy
    val testing = vm.testing

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy || testing || vm.fetchingModels) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                // —— 主力模型：总结 / 总览 / 思维导图等中低频高质量任务 ——
                SectionTitle("主力模型")
                Text(
                    "章末总结、全书总览、思维导图等任务用它。填好地址和 Key 后点「查找」" +
                        "从服务商拉取模型列表选择；列表拉不到的个别服务商直接手填模型名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = vm.baseUrl,
                    onValueChange = { vm.baseUrl = it },
                    label = { Text("API 地址") },
                    placeholder = { Text("https://api.openai.com/v1（兼容 OpenAI 格式，一般以 /v1 结尾）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = vm.keyInput,
                    onValueChange = { vm.keyInput = it },
                    label = { Text("API Key") },
                    placeholder = { Text(if (vm.hasKey) "已保存（输入以更换）" else "sk-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (vm.hasKey) TextButton(onClick = { vm.clearKey() }) { Text("清除") }
                    },
                )
                ModelField(
                    label = "模型名",
                    value = vm.model,
                    onValueChange = { vm.model = it },
                    onFetch = { vm.fetchModels(ModelPickerTarget.MAIN) },
                    fetching = vm.fetchingModels,
                )
                OutlinedTextField(
                    value = vm.tempText,
                    onValueChange = { vm.tempText = it },
                    label = { Text("温度（0.0–1.0，非法值用 0.3）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { if (testing) vm.cancelTest() else vm.testConnection() },
                        enabled = !busy,
                    ) { Text(if (testing) "取消测试" else "测试连接") }
                }
                if (vm.liveDelta.isNotEmpty()) {
                    Text(
                        vm.liveDelta.take(200),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // —— 讲解模型：逐段高频任务，可选低成本模型省钱 ——
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("讲解单独用低成本模型", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "段落讲解和粗读批注是逐段调用、用量最大的任务，可以单独配一个" +
                                "便宜快速的模型省钱；出题、总结的质量要求高，始终用主力模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = !vm.noteFollowMain, onCheckedChange = { vm.changeNoteFollowMain(!it) })
                }
                if (!vm.noteFollowMain) {
                    ModelField(
                        label = "讲解模型名（跑在主力服务商上）",
                        value = vm.noteModel,
                        onValueChange = { vm.noteModel = it },
                        onFetch = { vm.fetchModels(ModelPickerTarget.NOTE) },
                        fetching = vm.fetchingModels,
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // —— 扫描书转写（视觉）：开关即点即存，其余随"保存" ——
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("扫描书转写（视觉模型）", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "扫描版 PDF 和识别困难的页面交给视觉模型整页转写，转写失败的页自动" +
                                "回退文字层内容。地址、Key 留空跟随主力配置；用另一家服务商时才填",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = vm.visionEnabled, onCheckedChange = { vm.changeVisionEnabled(it) })
                }
                if (vm.visionEnabled) {
                    ModelField(
                        label = "视觉模型名",
                        value = vm.visionModel,
                        onValueChange = { vm.visionModel = it },
                        onFetch = { vm.fetchModels(ModelPickerTarget.VISION) },
                        fetching = vm.fetchingModels,
                    )
                    OutlinedTextField(
                        value = vm.visionBaseUrl,
                        onValueChange = { vm.visionBaseUrl = it },
                        label = { Text("视觉 API 地址（保存后生效）") },
                        placeholder = { Text("留空用主力 API 地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = vm.visionKeyInput,
                        onValueChange = { vm.visionKeyInput = it },
                        label = { Text("视觉 API Key") },
                        placeholder = { Text(if (vm.hasVisionKey) "已保存（输入以更换）" else "留空用主力 API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (vm.hasVisionKey) TextButton(onClick = { vm.clearVisionKey() }) { Text("清除") }
                        },
                    )

                    // 备用视觉（R3 多模型轮转）：主模型限流时自动接力
                    Text(
                        "备用视觉模型（可选）：主模型限流/失败时自动接力转写。地址、模型名、" +
                            "Key 三项都填才启用（备用组不共用主力 Key）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = vm.vision2BaseUrl,
                        onValueChange = { vm.vision2BaseUrl = it },
                        label = { Text("备用视觉 API 地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    ModelField(
                        label = "备用视觉模型名",
                        value = vm.vision2Model,
                        onValueChange = { vm.vision2Model = it },
                        onFetch = { vm.fetchModels(ModelPickerTarget.VISION2) },
                        fetching = vm.fetchingModels,
                    )
                    OutlinedTextField(
                        value = vm.vision2KeyInput,
                        onValueChange = { vm.vision2KeyInput = it },
                        label = { Text("备用视觉 API Key") },
                        placeholder = { Text(if (vm.hasVision2Key) "已保存（输入以更换）" else "必填（备用组不共用主力 Key）") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (vm.hasVision2Key) TextButton(onClick = { vm.clearVision2Key() }) { Text("清除") }
                        },
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                // —— 扫描书本地识别（OCR）：手机离线引擎，模型就绪状态只读展示 ——
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("扫描书本地识别", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "扫描版 PDF 先用手机离线引擎识别文字，无需联网、逐页约 1 秒；识别不了的" +
                                "图示页自动转交上方视觉模型增强。关闭后扫描书只能靠视觉模型整本转写",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!vm.ocrModelsReady) {
                            Text(
                                "识别模型尚未下载（约 21MB，需联网一次，此后离线识别）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            val pct = vm.ocrDownloadPct
                            if (pct != null) {
                                LinearProgressIndicator(
                                    progress = { pct / 100f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                            } else {
                                Button(
                                    onClick = { vm.downloadOcrModels() },
                                    modifier = Modifier.padding(top = 4.dp),
                                ) { Text("下载识别模型") }
                            }
                        }
                    }
                    Switch(checked = vm.ocrEnabled, onCheckedChange = { vm.changeOcrEnabled(it) })
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.save() }, enabled = !busy && !testing) { Text("保存") }
                }

                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                vm.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    // 模型选择弹窗：顶部搜索 + 列表点选；null = 关闭
    vm.pickerModels?.let { models ->
        val filtered = remember(models, vm.pickerQuery) {
            if (vm.pickerQuery.isBlank()) models
            else models.filter { it.contains(vm.pickerQuery, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { vm.dismissPicker() },
            title = { Text(vm.pickerTitle) },
            text = {
                Column {
                    OutlinedTextField(
                        value = vm.pickerQuery,
                        onValueChange = { vm.pickerQuery = it },
                        label = { Text("搜索（${models.size} 个模型）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    LazyColumn(Modifier.fillMaxWidth().height(380.dp).padding(top = 8.dp)) {
                        items(filtered) { name ->
                            TextButton(onClick = { vm.pickModel(name) }) {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissPicker() }) { Text("取消") }
            },
        )
    }
}

/** 模型名输入行：输入框 + 「查找」按钮（拉取中显示进度态） */
@Composable
private fun ModelField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onFetch: () -> Unit,
    fetching: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        Button(onClick = onFetch, enabled = !fetching) {
            Text(if (fetching) "查找中…" else "查找")
        }
    }
}

/** 区块小标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}
