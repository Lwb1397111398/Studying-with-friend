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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
fun TocConfirmScreen(vm: ImportViewModel, onDone: () -> Unit) {
    val chapters = vm.chapters
    val busy = vm.busy
    val error = vm.error
    val parseNote = vm.parseNote

    var renameIndex by remember { mutableStateOf<Int?>(null) }
    var showRules by remember { mutableStateOf(false) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }

    // P5 防御加固：轮询 imported 布尔改为消费一次性事件。旧写法 effect 体内先 reset
    // （把 key 改回 false）再 onDone，存在协程取消窗口致断网导入卡确认页；
    // 事件循环内 reset+导航，首行守卫挡住残留事件误导航。
    LaunchedEffect(Unit) {
        for (event in vm.importDone) {
            if (!vm.imported) continue
            android.util.Log.i("P5Nav", "③ 事件循环：reset 前")
            vm.reset()
            android.util.Log.i("P5Nav", "③ 事件循环：reset 完成，调 onDone")
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
            // P5-C 删假章状态行
            if (vm.deleted.isNotEmpty()) {
                Text(
                    "已删 ${vm.deleted.size} 章（点已删行可恢复），实际导入 ${chapters.size - vm.deleted.size} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
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
                    val isDeleted = index in vm.deleted
                    ChapterRow(
                        index = index,
                        title = vm.chapterTitleAt(index),
                        paraCount = chapter.paras.size,
                        charCount = chapter.paras.sumOf { it.text.length },
                        deleted = isDeleted,
                        canDelete = !isDeleted && chapters.size - vm.deleted.size > 1,
                        onClick = {
                            if (isDeleted) vm.restoreChapter(index) else renameIndex = index
                        },
                        onDelete = { deleteIndex = index },
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

    // P5-C 删除二次确认（不可逆感弱化：可恢复，但误触面小）
    deleteIndex?.let { idx ->
        val calibrated = vm.tocState is TocProbeState.Done
        AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text("删除这一章？") },
            text = {
                Column {
                    Text("「${vm.chapterTitleAt(idx)}」不会导入。")
                    if (calibrated) {
                        // 目录校准下删的是假章：段不丢，按页码并回相邻章
                        Text(
                            "目录已校准：该章的段落会按页码归并入相邻章，内容不会丢失。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        "点已删行可随时恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChapter(idx)
                    deleteIndex = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteIndex = null }) { Text("取消") } },
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
    deleted: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${index + 1}. $title",
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (deleted) TextDecoration.LineThrough else null,
                color = if (deleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (deleted) {
                Text(
                    "已删除 · 点击恢复",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                Text(
                    "$paraCount 段 · $charCount 字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!deleted) {
            IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除本章",
                    tint = if (canDelete) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        }
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
