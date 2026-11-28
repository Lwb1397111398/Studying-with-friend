package com.studyfriend.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.PromptLoader
import com.studyfriend.app.data.db.QaHistoryEntity
import com.studyfriend.app.data.study.QuickQa
import com.studyfriend.app.ui.components.MdText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


// 从 ReadScreen.kt 拆出（2026-10-11 结构批次）：随手问底部流式问答弹层


// ==================== P7 随手问问答弹层 ====================

/** 流式回答的打字机刷新间隔：攒增量至多每 100ms 刷一次状态，避免逐 delta 全卡重组 */
private const val QA_STREAM_FLUSH_MS = 100L

/** v7 问答历史每书保留条数（与 QaHistoryDao.trim 口径一致） */
private const val QA_HISTORY_KEEP = 50

/**
 * 随手问底部问答卡（P7）：打开即按默认问题自动发问（词/句/段粒度由浮条决定），
 * 流式回答（打字机效果），下方输入框可追问（多轮，历史裁最近 10 轮）。
 * 不走 AiGate 全局互斥（单请求轻量）；无 Key 时引导去设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickQaSheet(
    ctx: QuickQa.Ctx,
    defaultQuestion: String,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as StudyApp
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var history by remember { mutableStateOf(listOf<QuickQa.Turn>()) }
    // null = 空闲；"" = 请求中还没收到 delta；非空 = 流式正文
    var streaming by remember { mutableStateOf<String?>(null) }
    // 思考过程（2026-11-28 老板诉求「思考亮出来、思考完折叠」）：null=本轮无思考；
    // 非空=思考累积文本。thinkingDone=正文首字已到（折叠）；thinkOpen=折叠行点开
    var thinking by remember { mutableStateOf<String?>(null) }
    var thinkingDone by remember { mutableStateOf(false) }
    var thinkOpen by remember { mutableStateOf(false) }
    var thinkStartMs by remember { mutableStateOf(0L) }
    var thinkMs by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastQuestion by remember { mutableStateOf(defaultQuestion) }
    var noKey by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    // v7 问答历史弹层
    var showHistory by remember { mutableStateOf(false) }
    var historyRows by remember { mutableStateOf(listOf<QaHistoryEntity>()) }
    LaunchedEffect(showHistory, ctx.bookId) {
        if (showHistory && ctx.bookId >= 0) {
            historyRows = withContext(Dispatchers.IO) {
                app.database.qaHistoryDao().latestByBook(ctx.bookId)
            }
        }
    }
    val listState = rememberLazyListState()

    fun ask(question: String) {
        if (streaming != null) return
        lastQuestion = question
        job = scope.launch {
            error = null
            noKey = false
            streaming = ""
            thinking = null
            thinkingDone = false
            thinkOpen = false
            thinkMs = 0L
            try {
                // KeyStore 解密（binder IPC）+ 首次读 assets 提示词是同步重活，别在主线程做
                val prepared = withContext(Dispatchers.IO) {
                    // 讲解角色档案（2026-11-15 多模型轮）：随手问是高频轻任务，未指派跟随主力
                    val llm = app.settingsRepo.resolveLlm(com.studyfriend.app.data.LlmRole.NOTE)
                    if (llm == null) null else llm to PromptLoader.load(app, "quick_qa")
                }
                if (prepared == null) {
                    noKey = true
                    streaming = null
                    return@launch
                }
                val (llm, systemPrompt) = prepared
                // 打字机节流：StringBuilder 攒增量，至多每 QA_STREAM_FLUSH_MS 刷一次状态。
                // 逐 delta 全文重拼字符串是 O(n²) 拷贝且每个 delta 触发整卡重组+滚动，长回答越到后面越卡
                val answer = StringBuilder()
                var lastFlush = 0L
                // 思考段同款节流：推理模型思考先流出（正文首字到达时折叠定格时长）
                val thinkBuf = StringBuilder()
                var lastThinkFlush = 0L
                val result = AiClient.chat(
                    ChatRequest(
                        baseUrl = llm.baseUrl,
                        apiKey = llm.apiKey,
                        // 讲解角色档案：随手问是高频轻任务，未指派跟随主力
                        model = llm.model,
                        temperature = 0.5,
                        maxTokens = 4000,
                        source = "随手问",
                        messages = QuickQa.messages(systemPrompt, ctx, history, question),
                        onThink = { t ->
                            if (thinkBuf.isEmpty()) thinkStartMs = System.currentTimeMillis()
                            thinkBuf.append(t)
                            val now = System.currentTimeMillis()
                            if (now - lastThinkFlush >= QA_STREAM_FLUSH_MS) {
                                lastThinkFlush = now
                                thinking = thinkBuf.toString()
                            }
                        },
                    ),
                ) { delta ->
                    if (!thinkingDone && thinking != null) {
                        // 正文首字到达：思考折叠定格（补刷节流窗口里的思考尾巴）
                        thinkingDone = true
                        thinkMs = System.currentTimeMillis() - thinkStartMs
                        thinking = thinkBuf.toString()
                    }
                    answer.append(delta)
                    val now = System.currentTimeMillis()
                    if (now - lastFlush >= QA_STREAM_FLUSH_MS) {
                        lastFlush = now
                        streaming = answer.toString()
                    }
                }
                streaming = answer.toString() // 收尾补刷：节流窗口内的尾部增量上屏
                history = history + QuickQa.Turn(question, result)
                // v7 问答历史落库：应用级 scope（关弹层不取消写库）；每书保留最近 50 条
                if (ctx.bookId >= 0) {
                    app.appScope.launch {
                        val dao = app.database.qaHistoryDao()
                        dao.insert(
                            QaHistoryEntity(
                                bookId = ctx.bookId,
                                chapterId = ctx.chapterId,
                                chapterTitle = ctx.chapterTitle,
                                question = question,
                                answer = result,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        dao.trim(ctx.bookId, keep = QA_HISTORY_KEEP)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "请求失败"
            } finally {
                streaming = null
            }
        }
    }

    // 打开即自动发默认问题（老板诉求：选中直接问，不用再打字）
    LaunchedEffect(Unit) { ask(defaultQuestion) }
    // 新回答/流式增长/思考增长时滚到底（index 越界由列表自行 clamp；用当前条目数更稳）
    LaunchedEffect(history.size, streaming?.length, thinking?.length, error, noKey) {
        if (history.isNotEmpty() || streaming != null || thinking != null || error != null || noKey) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last)
        }
    }

    ModalBottomSheet(onDismissRequest = {
        job?.cancel()
        onDismiss()
    }) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .imePadding()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            // 引用块：正在问的文字
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "「${ctx.selection.take(120)}${if (ctx.selection.length > 120) "…" else ""}」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // v7 历史入口：本书最近 50 条问答
                    if (ctx.bookId >= 0) {
                        TextButton(onClick = { showHistory = true }) { Text("历史") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 问答流
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(history) { _, turn ->
                    Column {
                        Text(
                            turn.question,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp)) { MdText(md = turn.answer) }
                        }
                    }
                }
                // 思考过程区（2026-11-28）：推理模型思考实时上屏，正文首字到达后折叠
                // 成一行可点开；非推理模型 thinking 为 null 整块不出现，零影响
                if (thinking != null) {
                    item {
                        if (!thinkingDone) {
                            Column {
                                Text(
                                    "搭子正在想…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (!thinking.isNullOrBlank()) {
                                    Text(
                                        thinking!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        } else {
                            Column {
                                TextButton(onClick = { thinkOpen = !thinkOpen }) {
                                    Text(
                                        if (thinkOpen) "收起思考过程"
                                        else "已思考 ${com.studyfriend.app.data.ai.AiLogBus.fmtSec(thinkMs / 1000.0)} · 点开看思考过程",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                if (thinkOpen) {
                                    Text(
                                        thinking!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (streaming != null) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                streaming!!.ifBlank { "搭子正在写…" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
                if (error != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                error ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { ask(lastQuestion) }) { Text("重试") }
                        }
                    }
                }
                if (noKey) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "还没有配置 AI，先去设置页填好 API 地址和 Key。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onOpenSettings) { Text("去设置") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 追问输入行
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("继续问…") },
                    maxLines = 3,
                    shape = RoundedCornerShape(20.dp),
                )
                Button(
                    onClick = {
                        val q = input.trim()
                        if (q.isNotEmpty()) {
                            input = ""
                            ask(q)
                        }
                    },
                    enabled = streaming == null && input.isNotBlank(),
                ) { Text("发送") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // v7 问答历史二级弹层：本书最近 50 条（问题+答案+章名）
    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Text("随手问历史", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (historyRows.isEmpty()) {
                    Text(
                        "还没有历史。问过的每一条都会存在这里（本书最近 50 条）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(historyRows) { _, row ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        "${row.chapterTitle} · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(row.createdAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        row.question,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    MdText(md = row.answer)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
