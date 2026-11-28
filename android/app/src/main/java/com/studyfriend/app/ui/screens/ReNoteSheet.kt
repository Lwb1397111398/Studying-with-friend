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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.PromptLoader
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.study.ReNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 换个讲法弹层（学习闭环轮）：打开即按旧讲解重新流式讲一遍（更简单、多比方）。
// 与随手问同口径：单请求轻任务不走 AiGate；结果临时展示不落库、不覆盖原讲解卡。

/** 流式刷新间隔：与 QuickQaSheet 同款打字机节流 */
private const val RENOTE_FLUSH_MS = 100L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReNoteSheet(
    note: ParaNoteEntity,
    paragraphText: String,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as StudyApp
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    // null = 请求中还没收到 delta；非空 = 流式正文；Done 包装 = 完成
    var streaming by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    // 思考过程（2026-11-28 老板诉求「思考亮出来、思考完折叠」，与随手问同款交互）
    var thinking by remember { mutableStateOf<String?>(null) }
    var thinkingDone by remember { mutableStateOf(false) }
    var thinkOpen by remember { mutableStateOf(false) }
    var thinkStartMs by remember { mutableStateOf(0L) }
    var thinkMs by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var noKey by remember { mutableStateOf(false) }

    fun ask() {
        job = scope.launch {
            error = null
            noKey = false
            streaming = ""
            thinking = null
            thinkingDone = false
            thinkOpen = false
            thinkMs = 0L
            try {
                val prepared = withContext(Dispatchers.IO) {
                    // 讲解角色档案（2026-11-15 多模型轮）：换个讲法是逐段讲解类任务，未指派跟随主力
                    val llm = app.settingsRepo.resolveLlm(com.studyfriend.app.data.LlmRole.NOTE)
                    if (llm == null) null else llm to PromptLoader.load(app, ReNote.PROMPT_RENOTE)
                }
                if (prepared == null) {
                    noKey = true
                    streaming = null
                    return@launch
                }
                val (llm, systemPrompt) = prepared
                val answer = StringBuilder()
                var lastFlush = 0L
                // 思考段同款节流：推理模型思考先流出（正文首字到达时折叠定格时长）
                val thinkBuf = StringBuilder()
                var lastThinkFlush = 0L
                val text = AiClient.chat(
                    ChatRequest(
                        baseUrl = llm.baseUrl,
                        apiKey = llm.apiKey,
                        // 换个讲法属逐段讲解类高频任务，走讲解角色档案（未指派跟随主力）
                        model = llm.model,
                        temperature = 0.6,
                        maxTokens = 4000,
                        source = "换个讲法",
                        reasoningEffort = llm.reasoningEffort.takeIf { it.isNotBlank() },
                        messages = ReNote.messages(systemPrompt, paragraphText, note),
                        onThink = { t ->
                            if (thinkBuf.isEmpty()) thinkStartMs = System.currentTimeMillis()
                            thinkBuf.append(t)
                            val now = System.currentTimeMillis()
                            if (now - lastThinkFlush >= RENOTE_FLUSH_MS) {
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
                    if (now - lastFlush >= RENOTE_FLUSH_MS) {
                        lastFlush = now
                        streaming = answer.toString()
                    }
                }
                streaming = null
                result = text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "请求失败"
            } finally {
                streaming = null
            }
        }
    }

    LaunchedEffect(Unit) { ask() }

    ModalBottomSheet(onDismissRequest = {
        job?.cancel()
        onDismiss()
    }) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .imePadding()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text("换个讲法", style = MaterialTheme.typography.titleMedium)
            Text(
                "「${note.title}」——同一句话，这次讲得更简单",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            // 思考过程区（2026-11-28）：推理模型思考实时上屏，正文首字到达后折叠成
            // 一行可点开；非推理模型 thinking 为 null 整块不出现
            if (thinking != null) {
                if (!thinkingDone) {
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
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                } else {
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
                            modifier = Modifier
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    (result ?: streaming ?: "搭子正在写…"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 340.dp),
                )
            }
            if (error != null) {
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { ask() }) { Text("重试") }
                }
            }
            if (noKey) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "还没有配置 AI，先去设置页填好 API 地址和 Key。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenSettings) { Text("去设置") }
                }
            }
        }
    }
}
