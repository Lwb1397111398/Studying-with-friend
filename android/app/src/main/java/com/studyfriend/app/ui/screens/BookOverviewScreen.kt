package com.studyfriend.app.ui.screens

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.study.OverviewCodec
import com.studyfriend.app.data.study.OverviewRunState
import com.studyfriend.app.data.study.SummaryPlanner
import com.studyfriend.app.mindmap.TreeText
import com.studyfriend.app.ui.components.MapController
import com.studyfriend.app.ui.components.MdText
import com.studyfriend.app.ui.components.MindMapPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全书总览页（M6 计划 §3.2）：三 Tab（总览 / 总导图 / 主线）只读渲染 overviewJson；
 * 无 payload 时生成面板（门控：已总结章 ≥2），Streaming 显示进度行；顶栏重新生成带确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookOverviewScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as StudyApp
    val db = app.database
    val runner = app.overviewRunner
    // M5 线程纪律：runner.state 由后台线程写入，collect 后切主线程再写快照
    // （collectAsStateWithLifecycle 在 Robolectric 恢复链不切线程会 CalledFromWrongThreadException）
    val runState by produceState<OverviewRunState>(OverviewRunState.Idle) {
        runner.state.collect { withContext(Dispatchers.Main) { value = it } }
    }

    var bookTitle by remember { mutableStateOf("") }
    LaunchedEffect(bookId) {
        bookTitle = db.bookDao().get(bookId)?.title.orEmpty()
    }

    // 门控统计：已总结章数（promptVersion 匹配且 summaryMd 非空，与 planner 前置同口径）
    val chapters by db.chapterDao().byBookFlow(bookId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val summarizedCount = chapters.count {
        it.assetPromptVersion == SummaryPlanner.SUMMARY_VERSION && it.assetSummaryPresent
    }

    // overviewJson 进页快照（loaded 区分"加载中/确认无"，加载中不闪 CTA）；Succeeded 后 reload++ 重读
    var reload by remember(bookId) { mutableIntStateOf(0) }
    var overviewLoaded by remember(bookId) { mutableStateOf(false) }
    // lint 误报：lambda 内有显式 value 赋值，但规则对带 keys 重载的顶层赋值不识别（嵌套 collect 形态才识别）
    @Suppress("ProduceStateDoesNotAssignValue")
    val overviewRaw by produceState<String?>(null, bookId, reload) {
        val raw = db.bookDao().getOverviewJson(bookId)
        value = raw
        overviewLoaded = true
    }
    val payload = OverviewCodec.decode(overviewRaw)

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showRegenConfirm by remember { mutableStateOf(false) }

    // Succeeded 瞬态消费：重读 overviewJson 让"正在载入…"切到内容
    LaunchedEffect(runState) {
        val s = runState
        if (s is OverviewRunState.Succeeded && s.bookId == bookId) reload++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bookTitle.ifBlank { "全书总览" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (payload != null) {
                        TextButton(onClick = { showRegenConfirm = true }) { Text("重新生成") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val st = runState
            if (st is OverviewRunState.Streaming && st.bookId == bookId) {
                Text(
                    "正在生成全书总览…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (st is OverviewRunState.Failed && st.bookId == bookId && payload != null) {
                // 有旧总览时重新生成失败：GeneratePane 不渲染，这里给对称反馈行
                Text(
                    "重新生成失败：${st.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            when {
                payload != null -> {
                    TabRow(selectedTabIndex = tab) {
                        listOf("总览", "总导图", "主线").forEachIndexed { i, t ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                        }
                    }
                    when (tab) {
                        0 -> MdText(payload.overviewMd, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                        1 -> OverviewMindmapPane(payload.treeText, bookTitle, Modifier.fillMaxSize())
                        else -> MdText(payload.mainlineMd, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                    }
                }
                !overviewLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> OverviewGeneratePane(
                    state = st,
                    bookId = bookId,
                    summarizedCount = summarizedCount,
                    onStart = { runner.start(bookId) },
                    onStop = { runner.stop() },
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showRegenConfirm) {
        AlertDialog(
            onDismissRequest = { showRegenConfirm = false },
            title = { Text("重新生成全书总览") },
            text = { Text("现有总览将被覆盖，确定重新生成吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showRegenConfirm = false
                    runner.start(bookId)
                }) { Text("重新生成") }
            },
            dismissButton = {
                TextButton(onClick = { showRegenConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun OverviewGeneratePane(
    state: OverviewRunState,
    bookId: Long,
    summarizedCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            is OverviewRunState.Streaming -> if (s.bookId == bookId) {
                Text("搭子正在通览全书…", style = MaterialTheme.typography.titleMedium)
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        s.partial.ifBlank { "…正在等待输出" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = onStop) { Text("停止") }
            }

            is OverviewRunState.Failed -> if (s.bookId == bookId) {
                Text("生成失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(s.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Button(onClick = if (s.keyIssue) onOpenSettings else onStart) {
                    Text(if (s.keyIssue) "去填写 API Key" else "重试")
                }
            }

            is OverviewRunState.Succeeded -> if (s.bookId == bookId) {
                // overviewJson 重读前的短暂窗口：显示成功而非闪回 CTA
                Text("全书总览已生成", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("正在载入…", style = MaterialTheme.typography.bodyMedium)
            }

            else -> {
                Text("还没有全书总览", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (summarizedCount >= 2) {
                        "让搭子通览已总结的 $summarizedCount 章：全书总览、总导图、记忆主线一次带走。"
                    } else {
                        "至少读完并总结 2 章，才能生成全书总览（已总结 $summarizedCount 章）。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onStart, enabled = summarizedCount >= 2) { Text("让搭子通览全书") }
            }
        }
    }
}

/** 总导图 Tab：书级 TAB 树只读渲染 + 导出 PNG（与章末导图同模式） */
@Composable
private fun OverviewMindmapPane(treeText: String, bookTitle: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MapController?>(null) }
    var pendingPng by remember { mutableStateOf<ByteArray?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pendingPng
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                .onFailure { Log.w("OverviewMindmapPane", "导出 PNG 失败", it) }
        }
        pendingPng = null
    }

    val mapJson = remember(treeText, bookTitle) {
        runCatching { TreeText.toMapData(TreeText.parse(treeText), bookTitle.ifBlank { "思维导图" }).first }
            .onFailure { Log.w("OverviewMindmapPane", "导图树解析失败", it) }
            .getOrNull()
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (mapJson == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("导图数据为空，请重新生成总览", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        } else {
            MindMapPanel(
                mapJson = mapJson,
                editable = false,
                onMapChanged = {},
                onController = { controller = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onPngReady = { base64 ->
                    // JS 桥回调在 WebView 线程：解码后转主线程再写状态 / 拉起 SAF
                    val b64 = if (base64.startsWith("data:")) base64.substringAfter("base64,") else base64
                    val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    if (bytes != null) {
                        val safeName = safeFileName(bookTitle.ifBlank { "导图" })
                        mainHandler.post {
                            pendingPng = bytes
                            exportLauncher.launch("$safeName-总导图.png")
                        }
                    }
                },
            )
            Button(
                onClick = { controller?.exportPng() },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) { Text("导出 PNG") }
        }
    }
}
