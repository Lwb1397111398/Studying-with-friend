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

    var showPaste by remember { mutableStateOf(false) }
    var encMenuOpen by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.loadFile(uri)
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
                Button(onClick = { pickFile.launch(arrayOf("text/*", "application/pdf")) }) {
                    Text("选择 TXT / PDF 文件")
                }
                OutlinedButton(onClick = { showPaste = true }) { Text("粘贴文本") }
            }

            if (!isPdf) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("编码：", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { encMenuOpen = true }) { Text(encoding) }
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

            if (busy && chapters.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.height(16.dp))
                    Text("正在读取与解析…", style = MaterialTheme.typography.bodySmall)
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
