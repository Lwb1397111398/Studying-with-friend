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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TocConfirmScreen(vm: ImportViewModel, onDone: () -> Unit) {
    val chapters = vm.chapters
    val busy = vm.busy
    val error = vm.error
    val parseNote = vm.parseNote
    val imported = vm.imported

    var renameIndex by remember { mutableStateOf<Int?>(null) }
    var showRules by remember { mutableStateOf(false) }

    LaunchedEffect(imported) {
        if (imported) {
            vm.reset()
            onDone()
        }
    }

    // 单列居中，平板上不无限拉宽（计划 §4：最大 720dp）
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxSize(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "确认目录",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showRules = true }) { Text("识别规则") }
            }

            Text(
                "共 ${chapters.size} 章，点章节名可改；确认后正式入库",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            parseNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(chapters) { index, chapter ->
                    ChapterRow(
                        index = index,
                        title = vm.chapterTitleAt(index),
                        paraCount = chapter.paras.size,
                        charCount = chapter.paras.sumOf { it.text.length },
                        onClick = { renameIndex = index },
                    )
                    HorizontalDivider()
                }
            }

            Button(
                onClick = { vm.confirmImport() },
                enabled = !busy && chapters.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) { Text("完成导入") }
        }
    }

    renameIndex?.let { idx ->
        var draft by remember(idx) { mutableStateOf(vm.chapterTitleAt(idx)) }
        AlertDialog(
            onDismissRequest = { renameIndex = null },
            title = { Text("重命名第 ${idx + 1} 章") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.rename(idx, draft)
                        renameIndex = null
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameIndex = null }) { Text("取消") } },
        )
    }

    if (showRules) {
        RulesDialog(
            initial = vm.currentRegex ?: "",
            onDismiss = { showRules = false },
            onApply = { regex ->
                showRules = false
                vm.reparse(regex)
            },
            onReset = {
                showRules = false
                vm.reparse(null)
            },
        )
    }
}

@Composable
private fun ChapterRow(
    index: Int,
    title: String,
    paraCount: Int,
    charCount: Int,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text("${index + 1}. $title", style = MaterialTheme.typography.bodyLarge)
        Text(
            "$paraCount 段 · $charCount 字",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RulesDialog(
    initial: String,
    onDismiss: () -> Unit,
    onApply: (String?) -> Unit,
    onReset: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("章节识别规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "默认已内置常见规则（第X章/第X节/一、 等）。若你的书没识别好，" +
                        "可填写一条正则，匹配到的行将视为章标题。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("例如：^\\[[0-9]+\\]") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(text.ifBlank { null }) },
                enabled = text.isNotBlank(),
            ) { Text("应用") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("恢复内置") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
