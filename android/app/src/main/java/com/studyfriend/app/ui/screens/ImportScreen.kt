package com.studyfriend.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.studyfriend.app.data.importer.TextLoader

private val encodingChoices = listOf(
    TextLoader.AUTO,
    "UTF-8",
    "GBK",
    "GB18030",
    "UTF-16LE",
    "UTF-16BE",
)

@Composable
fun ImportScreen(vm: ImportViewModel, onNext: () -> Unit) {
    // VM 属性直接由 mutableStateOf 支持，Composable 内读取即可订阅
    val busy = vm.busy
    val error = vm.error
    val parseNote = vm.parseNote
    val chapters = vm.chapters
    val bookTitle = vm.bookTitle
    val author = vm.author
    val encoding = vm.encoding
    val isPdf = vm.isPdf
    val phase = vm.phase
    val progress = vm.progress

    var showPaste by remember { mutableStateOf(false) }
    var encMenuOpen by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.loadFile(uri)
    }

    // 批B：导入页可见性上报——OCR 期间用户可离开（识别在应用级 VM 继续跑），
    // VM 据此在「识别完成且人不在」时发召回通知。屏幕常亮职责上移 AppNav
    // （离开导入页后识别仍在跑，常亮须跟到哪都有效）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> vm.uiVisible = false
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> vm.uiVisible = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 单列居中，平板上不无限拉宽（计划 §4：最大 720dp）
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
            Text("导入书籍", style = MaterialTheme.typography.headlineSmall)

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            OutlinedTextField(
                value = bookTitle,
                onValueChange = { vm.bookTitle = it },
                label = { Text("书名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = author,
                onValueChange = { vm.author = it },
                label = { Text("作者（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    pickFile.launch(
                        arrayOf("text/*", "application/pdf", "application/zip", "application/x-zip-compressed"),
                    )
                }) {
                    Text("选择 TXT / PDF / 图包")
                }
                OutlinedButton(onClick = { showPaste = true }) { Text("粘贴文本") }
            }

            if (!isPdf) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("编码：", style = MaterialTheme.typography.bodyMedium)
                    // busy 期间禁用：否则选中新编码后 loadFile 被守卫拦截，下拉与实际解码编码不一致
                    OutlinedButton(onClick = { encMenuOpen = true }, enabled = !busy) { Text(encoding) }
                    DropdownMenu(expanded = encMenuOpen, onDismissRequest = { encMenuOpen = false }) {
                        encodingChoices.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text(choice) },
                                onClick = {
                                    encMenuOpen = false
                                    vm.onEncodingChanged(choice)
                                },
                            )
                        }
                    }
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            parseNote?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }

            if (chapters.isNotEmpty()) {
                Text(
                    "识别到 ${chapters.size} 章，共 ${chapters.sumOf { it.paras.size }} 段",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text("下一步：确认目录")
                }
            }

            // 守卫用 phase/progress 而非 busy：落库阶段（busy=true 但两态皆空）不可中断事务，
            // 沿用顶部不定进度条、不渲染取消按钮；仅提取段（含复制段）取消即时可达检查点
            if (phase != null || progress != null) {
                val p = progress
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (p != null) {
                            LinearProgressIndicator(
                                progress = { p.first.toFloat() / p.second },
                                modifier = Modifier.weight(1f),
                            )
                            Text("提取 PDF ${p.first}/${p.second} 页", style = MaterialTheme.typography.bodySmall)
                        } else {
                            CircularProgressIndicator(Modifier.height(16.dp))
                            Text(phase ?: "", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.cancelImport() }) { Text("取消") }
                    }
                    // 批B：识别期间可以离开——VM 是应用级，回书架/逛别处识别继续，完成有通知
                    if (phase?.contains("本地文字识别") == true || phase?.contains("识别书的开头结构") == true) {
                        Text(
                            "识别要几十分钟：可以先去书架逛逛，识别会继续，完成后通知你（请保持应用在前台）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPaste) {
        PasteDialog(
            onDismiss = { showPaste = false },
            onConfirm = { text ->
                showPaste = false
                vm.loadPasted(text)
            },
        )
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴文本") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("把要读的内容粘贴到这里") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
