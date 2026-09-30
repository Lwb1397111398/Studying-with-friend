package com.studyfriend.app.ui.screens

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.QuizAttemptEntity
import com.studyfriend.app.data.study.CheckQuestion
import com.studyfriend.app.data.study.QuizCodec
import com.studyfriend.app.data.study.QuizQuestion
import com.studyfriend.app.data.study.SummaryRunState
import com.studyfriend.app.data.study.buildExportMd
import com.studyfriend.app.data.study.decodeCheckQuestions
import com.studyfriend.app.data.tts.AndroidTtsEngine
import com.studyfriend.app.data.tts.TtsPlayer
import com.studyfriend.app.data.tts.TtsState
import com.studyfriend.app.mindmap.TreeText
import com.studyfriend.app.ui.components.MapController
import com.studyfriend.app.ui.components.MdText
import com.studyfriend.app.ui.components.MindMapPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** SAF 文件名清洗：替换 Windows/Android 文件系统非法字符 */
internal fun safeFileName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

/**
 * 章末总结页（计划 M5 §3.4）：无资产 → 生成面板（CTA / 流式增量 / 失败重试）；
 * 有资产 → 总结 / 导图 / 记忆 / 自测 四 Tab。重新生成会清空本章自测作答（提示确认）。
 * 导图 Tab 步骤④接入 MindMapPanel，自测 Tab 步骤⑥接入作答流。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterAssetsScreen(chapterId: Long, onBack: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as StudyApp
    val db = app.database
    val runner = app.summaryRunner

    val chapter by db.chapterDao().byIdFlow(chapterId).collectAsStateWithLifecycle(initialValue = null)
    val asset by db.chapterAssetDao().byChapterFlow(chapterId).collectAsStateWithLifecycle(initialValue = null)
    val paragraphs by db.paragraphDao().byChapterFlow(chapterId).collectAsStateWithLifecycle(initialValue = emptyList())
    // runner 状态由后台任务线程写入；快照写入必须落主线程（收集链在部分调度器上不切线程）
    val runState by produceState<SummaryRunState>(SummaryRunState.Idle) {
        runner.state.collect { withContext(Dispatchers.Main) { value = it } }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmRegen by rememberSaveable { mutableStateOf(false) }
    var exportContent by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val mdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val content = exportContent
        if (uri != null && content != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } }
                .onFailure { Log.w("ChapterAssets", "导出 Markdown 失败", it) }
        }
        exportContent = null
    }

    fun launchExport(currentAsset: ChapterAssetEntity, chapterTitle: String) {
        val ch = chapter ?: return
        scope.launch {
            val bookTitle = db.bookDao().get(ch.bookId)?.title.orEmpty()
            exportContent = buildExportMd(
                bookTitle = bookTitle,
                chapterTitle = chapterTitle,
                asset = currentAsset,
                questions = QuizCodec.decode(currentAsset.quizJson),
                generatedAt = LocalDate.now().toString(),
            )
            mdLauncher.launch("${safeFileName(chapterTitle.ifBlank { "总结包" })}-总结包.md")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        chapter?.title?.let { "$it · 总结" } ?: "章末总结",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    val a = asset
                    if (a != null) {
                        TextButton(onClick = { launchExport(a, chapter?.title.orEmpty()) }) { Text("导出") }
                        TextButton(onClick = { confirmRegen = true }) { Text("重新生成") }
                    }
                },
            )
        },
    ) { padding ->
        val a = asset
        if (a == null) {
            GeneratePane(
                state = runState,
                chapterId = chapterId,
                hasMarks = paragraphs.any { it.aiAction != "NONE" },
                onStart = { runner.start(chapterId) },
                onStop = { runner.stop() },
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(padding),
            )
        } else {
            // 重生成进行中给一条进度提示（低危评审项：有资产视图下 Streaming 不可见会让用户以为卡死）
            val regen = runState
            Column(Modifier.padding(padding)) {
                if (regen is SummaryRunState.Streaming && regen.chapterId == chapterId) {
                    Text(
                        "正在重新生成总结包…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                AssetTabs(
                    asset = a,
                    chapterId = chapterId,
                    chapterTitle = chapter?.title ?: "",
                    tab = tab,
                    onTab = { tab = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (confirmRegen) {
        AlertDialog(
            onDismissRequest = { confirmRegen = false },
            title = { Text("重新生成总结包？") },
            text = { Text("会覆盖现有总结、导图与自测题，本章已作答的自测记录将被清空。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRegen = false
                        runner.start(chapterId)
                    },
                ) { Text("重新生成") }
            },
            dismissButton = { TextButton(onClick = { confirmRegen = false }) { Text("取消") } },
        )
    }
}

/** 无资产面板：Idle → CTA（粗读未完成则禁用引导回去）；Streaming → 增量；Succeeded → 载入中；Failed → 原因 + 出路 */
@Composable
private fun GeneratePane(
    state: SummaryRunState,
    chapterId: Long,
    hasMarks: Boolean,
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
            is SummaryRunState.Streaming -> if (s.chapterId == chapterId) {
                Text("搭子正在总结本章…", style = MaterialTheme.typography.titleMedium)
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        s.partial.ifBlank { "…正在等待输出" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = onStop) { Text("停止") }
            }

            is SummaryRunState.Failed -> if (s.chapterId == chapterId) {
                Text("生成失败", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(s.message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                // Key 问题给出真出路（导航设置页），而不是原地重跑必然再失败
                Button(onClick = if (s.keyIssue) onOpenSettings else onStart) {
                    Text(if (s.keyIssue) "去填写 API Key" else "重试")
                }
            }

            is SummaryRunState.Succeeded -> if (s.chapterId == chapterId) {
                // 资产 Flow 发射前的短暂窗口：显示成功而非闪回"还没有总结包"
                Text("总结包已生成", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("正在载入…", style = MaterialTheme.typography.bodyMedium)
            }

            else -> {
                Text("本章还没有总结包", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (hasMarks) "读完正文后，让搭子把整章串起来：总结、导图、记忆思路、自测一套带走。"
                    else "本章还没有粗读标注，先回阅读页完成粗读，再来生成总结包。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onStart, enabled = hasMarks) { Text("让搭子总结本章") }
            }
        }
    }
}

/** 有资产视图：四 Tab（Succeeded 旧状态无需展示，asset Flow 已把页面切到此处） */
@Composable
private fun AssetTabs(
    asset: ChapterAssetEntity,
    chapterId: Long,
    chapterTitle: String,
    tab: Int,
    onTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val titles = listOf("总结", "导图", "记忆", "自测")
    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { MdText(asset.summaryMd) }

            1 -> MindmapPane(asset, chapterTitle)

            2 -> MemoryPane(asset)

            // 资产版本换代后题卡状态（展开/输入框）必须重建：rememberSaveable 键不含版本，用 key 隔离
            3 -> key(asset.createdAt) { QuizPane(asset, chapterId) }        }
    }
}

/** 自测 Tab（计划 M5 §3.5）：逐题作答自评落库（同版资产内追加历史）+ 底部讲解自问（只翻面不落库） */
@Composable
private fun QuizPane(asset: ChapterAssetEntity, chapterId: Long, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as StudyApp
    val db = app.database
    val scope = rememberCoroutineScope()
    val questions = remember(asset.quizJson) { QuizCodec.decode(asset.quizJson) }
    // 历史作答实时刷新：verdict 取每题最新一条（重生成清空后从新题重新积累）
    val attempts by db.quizAttemptDao().byChapterFlow(chapterId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val latestByIndex = remember(attempts) { attempts.groupBy { it.qIndex }.mapValues { it.value.last() } }
    // M4b 讲解卡的自测题（讲解自问，不落库）
    // lint 误报：lambda 内有显式 value 赋值，但规则对带 keys 重载的顶层赋值不识别（嵌套 collect 形态才识别）
    @Suppress("ProduceStateDoesNotAssignValue")
    val selfQuestions by produceState<List<CheckQuestion>>(emptyList(), chapterId) {
        val qs = db.paraNoteDao().byChapterOnce(chapterId).flatMap { decodeCheckQuestions(it.questionsJson) }
        value = qs
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (questions.isEmpty()) {
            Text("这套总结包没有自测题", style = MaterialTheme.typography.bodyMedium)
        }
        questions.forEachIndexed { qIndex, q ->
            QuizCard(
                qIndex = qIndex,
                question = q,
                latestVerdict = latestByIndex[qIndex]?.verdict,
                onAnswer = { answer, verdict ->
                    scope.launch {
                        db.quizAttemptDao().insert(
                            QuizAttemptEntity(
                                chapterId = chapterId, qIndex = qIndex, answer = answer,
                                verdict = verdict, feedback = q.explain.takeIf { it.isNotBlank() },
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
            )
        }
        if (selfQuestions.isNotEmpty()) {
            MdText("## 讲解自问")
            selfQuestions.forEachIndexed { i, sq -> SelfCheckCard(i, sq) }
        }
    }
}

private fun verdictLabel(verdict: String) = when (verdict) {
    "CORRECT" -> "答对"
    "PARTIAL" -> "模糊"
    else -> "答错"
}

private fun QuizQuestion.typeLabel() = when (type) {
    "TRUE_FALSE" -> "判断"
    "CASE" -> "案例"
    else -> "回想"
}

@Composable
private fun QuizCard(
    qIndex: Int,
    question: QuizQuestion,
    latestVerdict: String?,
    onAnswer: (answer: String, verdict: String) -> Unit,
) {
    var expanded by rememberSaveable("quiz", qIndex.toString()) { mutableStateOf(false) }
    var revealed by rememberSaveable("quiz", qIndex.toString(), "revealed") { mutableStateOf(false) }
    var answerText by rememberSaveable("quiz", qIndex.toString(), "answer") { mutableStateOf("") }

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(question.typeLabel(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (latestVerdict != null) {
                    Text("上次：${verdictLabel(latestVerdict)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                question.q,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            )
            if (expanded) {
                if (!revealed) {
                    OutlinedTextField(
                        value = answerText,
                        onValueChange = { answerText = it },
                        placeholder = { Text("先自己作答，再看答案") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { revealed = true }) { Text("对答案") }
                } else {
                    Text("参考答案：${question.a}", style = MaterialTheme.typography.bodyMedium)
                    if (question.explain.isNotBlank()) {
                        Text("解析：${question.explain}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("对照解析，给自己一个评价：", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAnswer(answerText, "CORRECT") }) { Text("答对") }
                        OutlinedButton(onClick = { onAnswer(answerText, "PARTIAL") }) { Text("模糊") }
                        OutlinedButton(onClick = { onAnswer(answerText, "WRONG") }) { Text("答错") }
                    }
                }
            }
        }
    }
}

/** 讲解自问卡：问题点击翻面显示答案，不落库（消费 M4b decodeCheckQuestions 存储） */
@Composable
private fun SelfCheckCard(index: Int, question: CheckQuestion) {
    var revealed by rememberSaveable("selfcheck", index.toString()) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "问：${question.q}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable { revealed = !revealed },
            )
            if (revealed) {
                Text("答：${question.a}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 记忆 Tab：记忆思路 + 串联，顶部 TTS 朗读（计划 M5 §3.4 记忆思路朗读） */
@Composable
private fun MemoryPane(asset: ChapterAssetEntity, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tts = remember { TtsPlayer(AndroidTtsEngine(context), scope) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    // 引擎回调来自服务线程：快照写统一落主线程（同 runState 纪律）
    val ttsState by produceState<TtsState>(TtsState.Idle) {
        tts.state.collect { withContext(Dispatchers.Main) { value = it } }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val busy = ttsState is TtsState.Speaking || ttsState is TtsState.Preparing
            OutlinedButton(onClick = { tts.toggle(asset.memoryMd) }) {
                Text(if (busy) "停止朗读" else "朗读记忆思路")
            }
            when (val s = ttsState) {
                is TtsState.Speaking -> Text("已读 ${s.sentenceIdx}/${s.total} 句", style = MaterialTheme.typography.bodySmall)
                is TtsState.Error -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> {}
            }
        }
        MdText(asset.memoryMd)
        MdText("## 串联成一条线")
        MdText(asset.chainMd)
    }
}

/** 导图 Tab（计划 M5 §3.4）：TAB 树现转 mind-elixir 数据只读渲染，底部导出 PNG（SAF） */
@Composable
private fun MindmapPane(asset: ChapterAssetEntity, chapterTitle: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MapController?>(null) }
    var pendingPng by remember { mutableStateOf<ByteArray?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pendingPng
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                .onFailure { Log.w("MindmapPane", "导出 PNG 失败", it) }
        }
        pendingPng = null
    }

    // TAB 树 → mind-elixir 数据，转换结果按树文本缓存（避免每次重组重解析）
    val mapJson = remember(asset.mindmapTree, chapterTitle) {
        runCatching { TreeText.toMapData(TreeText.parse(asset.mindmapTree), chapterTitle.ifBlank { "思维导图" }).first }
            .onFailure { Log.w("MindmapPane", "导图树解析失败", it) }
            .getOrNull()
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (mapJson == null) {
            PlaceholderPane("导图数据为空，请重新生成总结包")
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
                        val safeName = safeFileName(chapterTitle.ifBlank { "导图" })
                        mainHandler.post {
                            pendingPng = bytes
                            exportLauncher.launch("$safeName-导图.png")
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

@Composable
private fun PlaceholderPane(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
