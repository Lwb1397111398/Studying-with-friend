package com.studyfriend.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.study.KeyTermsCodec
import com.studyfriend.app.data.study.NotePlanner
import com.studyfriend.app.data.study.NoteRunState
import com.studyfriend.app.data.study.ParaIdsCodec
import com.studyfriend.app.data.study.RoughRunState
import com.studyfriend.app.data.study.decodeStringList
import com.studyfriend.app.data.study.enumerateUnits
import kotlinx.coroutines.launch

/**
 * 阅读页（计划 M4a §3）：操作条按数据推导七态（进行中/中断/成功/失败/待归并/
 * 未开始/断点续跑）+ 本章要点卡 + 段落流（标注徽标与 why；SKIP 段全文半透明仍可读）。
 * 讲解卡片属 M4b。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReadScreen(
    chapterId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSummary: () -> Unit = {},
    onOpenChapter: (Long) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as StudyApp
    val chapter by app.database.chapterDao().byIdFlow(chapterId)
        .collectAsStateWithLifecycle(initialValue = null)
    val paragraphs by app.database.paragraphDao().byChapterFlow(chapterId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val runState by app.roughReadRunner.state.collectAsStateWithLifecycle()
    val notes by app.database.paraNoteDao().byChapterFlow(chapterId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val noteState by app.noteRunner.state.collectAsStateWithLifecycle()
    val gateLabel by app.aiGate.label.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // 上/下章切换（M4a §3.4）：按同书章序跳转，边界章禁用
        val bookId = chapter?.bookId
        // lint 误报：lambda 内有显式 value 赋值，但规则对带 keys 重载的顶层赋值不识别（嵌套 collect 形态才识别）
        @Suppress("ProduceStateDoesNotAssignValue")
        val siblings by produceState<List<ChapterEntity>>(emptyList(), bookId) {
            val list: List<ChapterEntity> =
                if (bookId == null) emptyList() else app.database.chapterDao().byBook(bookId)
            value = list
        }
        val prevChapter = siblings.lastOrNull { it.idx < (chapter?.idx ?: 0) }
        val nextChapter = siblings.firstOrNull { it.idx > (chapter?.idx ?: 0) }

        TopAppBar(
            title = {
                Text(
                    chapter?.title.orEmpty().ifBlank { "阅读" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(
                    enabled = prevChapter != null,
                    onClick = { prevChapter?.let { onOpenChapter(it.id) } },
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上一章") }
                IconButton(
                    enabled = nextChapter != null,
                    onClick = { nextChapter?.let { onOpenChapter(it.id) } },
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下一章") }
            },
        )

        // OPT-C C4：计数用正文视图（TOC/脚注段永远无标注，混入计数会让"部分标注"态无法收敛）；
        // LazyColumn 渲染仍用全量列表（目录条目/脚注按次级样式展示）
        val bodyParagraphs = remember(paragraphs) {
            paragraphs.filter { it.role != DbValues.ROLE_TOC && it.role != DbValues.ROLE_FOOTNOTE }
        }
        val markedCount = bodyParagraphs.count { it.aiAction != DbValues.ACT_NONE }
        ActionBar(
            chapterId = chapterId,
            hasGist = !chapter?.gist.isNullOrBlank(),
            readDone = chapter?.readState == DbValues.READ_DONE,
            paragraphCount = bodyParagraphs.size,
            pureToc = bodyParagraphs.isEmpty() && paragraphs.isNotEmpty(),
            markedCount = markedCount,
            runState = runState,
            onStart = { app.roughReadRunner.start(chapterId, force = false) },
            onForce = { app.roughReadRunner.start(chapterId, force = true) },
            onMerge = { app.roughReadRunner.mergeOnly(chapterId) },
            onStop = { app.roughReadRunner.stop() },
            onMarkDone = {
                // 应用级 scope：用户点完立即返回上一页也不会取消写库
                app.appScope.launch { app.database.chapterDao().updateReadState(chapterId, DbValues.READ_DONE) }
            },
            onOpenSettings = onOpenSettings,
        )

        // 讲解工具条：单元数与有效 note 数按数据推导（M4b §3.1）
        NoteBar(
            chapterId = chapterId,
            paragraphs = paragraphs,
            notes = notes,
            noteState = noteState,
            gateLabel = gateLabel,
            roughState = runState,
            onGenerate = { app.noteRunner.start(chapterId, force = false) },
            onForce = { app.noteRunner.start(chapterId, force = true) },
            onStop = { app.noteRunner.stop() },
        )

        // 章末总结入口：本章已粗读标注即可进入（M5 §3.7；无资产时总结页自会引导生成）
        if (markedCount > 0) {
            OutlinedButton(
                onClick = onOpenSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text("章末总结 · 导图 · 自测") }
        }

        // 本章要点卡：粗读归并出的 gist + key_terms（RoughReadRunner 产出）
        val gist = chapter?.gist
        if (!gist.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(gist, style = MaterialTheme.typography.bodyMedium)
                    val terms = KeyTermsCodec.decode(chapter?.keyTermsJson)
                    if (terms.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        // key_terms chips（M4a §3.4 视觉规格）
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            terms.forEach { t ->
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(
                                        t.term,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (paragraphs.isEmpty()) {
            EmptyState(title = "本章还没有内容", subtitle = "段落解析可能没有完成")
            return@Column
        }
        // 讲解单元与 note 映射：卡片只渲染在锚段下（M4b §3.2）
        val noteStateV = noteState // 委托属性不能 smart cast，先取快照
        val units = remember(paragraphs) { enumerateUnits(paragraphs) }
        val anchorById = remember(units) { units.associateBy { it.anchor.id } }
        val notesByParaIds = remember(notes) { notes.associateBy { it.paraIds } }
        val noteRunningThis = noteStateV is NoteRunState.Running && noteStateV.chapterId == chapterId
        LazyColumn(Modifier.fillMaxSize()) {
            items(paragraphs, key = { it.id }) { p ->
                ParaRow(p)
                val unit = anchorById[p.id]
                if (unit != null) {
                    val note = notesByParaIds[ParaIdsCodec.encode(unit.members.map { it.id })]
                        ?.takeIf { it.promptVersion == NotePlanner.NOTE_VERSION && it.friendly.isNotBlank() }
                    NoteCard(note = note, generating = note == null && noteRunningThis)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/**
 * 操作条（计划 M4a §3 四态 + 质检 P1 补齐）：状态按 Runner 瞬态 + 段落标注数据双重推导，
 * 进程重启后瞬态丢失也能从数据恢复"待归并/断点续跑"入口。
 */
@Composable
private fun ActionBar(
    chapterId: Long,
    hasGist: Boolean,
    readDone: Boolean,
    paragraphCount: Int,
    pureToc: Boolean,
    markedCount: Int,
    runState: RoughRunState,
    onStart: () -> Unit,
    onForce: () -> Unit,
    onMerge: () -> Unit,
    onStop: () -> Unit,
    onMarkDone: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val runningThis = runState is RoughRunState.Running && runState.chapterId == chapterId
    val runningOther = runState is RoughRunState.Running && runState.chapterId != chapterId
    val succeeded = runState is RoughRunState.Succeeded && runState.chapterId == chapterId
    val interrupted = runState is RoughRunState.Interrupted && runState.chapterId == chapterId
    val failed = runState is RoughRunState.Failed && runState.chapterId == chapterId

    val allMarked = paragraphCount > 0 && markedCount == paragraphCount
    val partiallyMarked = markedCount in 1 until paragraphCount
    val allUnmarked = markedCount == 0

    // force 重读会覆盖全部既有标注（计划 §3.2）：二次确认防误触
    var showForceConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        when {
            runningThis && runState is RoughRunState.Running -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "搭子正在粗读 ${runState.done}/${runState.total}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (runState.total > 0) runState.done.toFloat() / runState.total else 0f
                        },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onStop) { Text("停止") }
                }
            }

            interrupted -> {
                Text(
                    (runState as RoughRunState.Interrupted).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart) { Text("从断点继续") }
                    OutlinedButton(onClick = { showForceConfirm = true }) { Text("全部重读") }
                }
            }

            succeeded -> {
                val success = runState as RoughRunState.Succeeded
                Text(
                    success.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (readDone) {
                        Text("已读完", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } else {
                        TextButton(onClick = onMarkDone) { Text("标记本章读完") }
                    }
                    if (success.degraded) {
                        // 降级要点可只补一次归并升级为真要点，不必全量重跑
                        OutlinedButton(onClick = onMerge, enabled = !runningOther) { Text("重试归并") }
                    }
                    OutlinedButton(onClick = { showForceConfirm = true }, enabled = !runningOther) { Text("重读") }
                }
            }

            failed -> {
                val failure = runState as RoughRunState.Failed
                Text(failure.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onStart) { Text("重试") }
                    if (failure.keyIssue) {
                        TextButton(onClick = onOpenSettings) { Text("去设置") }
                    }
                }
            }

            // 全部已标但没要点：只补一次归并，不必重跑块（质检 P1-2）
            allMarked && !hasGist -> {
                Text(
                    "段落标注已完成，还没有生成本章要点",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onMerge, enabled = !runningOther) { Text("完成归并") }
                    OutlinedButton(onClick = { showForceConfirm = true }, enabled = !runningOther) { Text("重读") }
                }
            }

            allUnmarked -> {
                Text(
                    when {
                        // 纯目录章：正文计数为 0 但渲染列表非空（OPT-C C4）
                        paragraphCount == 0 && pureToc -> "本章是目录，无需粗读"
                        paragraphCount == 0 -> "本章没有段落"
                        else -> "搭子还没有读这一章"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onStart, enabled = paragraphCount > 0 && !runningOther) {
                    Text("开始粗读")
                }
            }

            // 部分标注（进程重启后 Runner 瞬态丢失时从数据恢复断点入口，质检 P1-4）
            partiallyMarked -> {
                Text(
                    "上次粗读完成了一部分（$markedCount/$paragraphCount 段已标注）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart, enabled = !runningOther) { Text("从断点继续") }
                    OutlinedButton(onClick = { showForceConfirm = true }, enabled = !runningOther) { Text("全部重读") }
                }
            }

            else -> {
                // 全部已标且有要点（上次会话的稳定完成态）
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (readDone) {
                        Text("已读完", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } else {
                        TextButton(onClick = onMarkDone) { Text("标记本章读完") }
                    }
                    OutlinedButton(onClick = { showForceConfirm = true }, enabled = !runningOther) { Text("重读") }
                }
            }
        }

        if (showForceConfirm) {
            AlertDialog(
                onDismissRequest = { showForceConfirm = false },
                title = { Text("重新粗读这一章？") },
                text = { Text("搭子会重新阅读全部段落并覆盖现有标注，已生成的内容将被替换。") },
                confirmButton = {
                    TextButton(onClick = {
                        showForceConfirm = false
                        onForce()
                    }) { Text("覆盖重读") }
                },
                dismissButton = {
                    TextButton(onClick = { showForceConfirm = false }) { Text("取消") }
                },
            )
        }
    }
}

/** 正文阅读字号（M4a §3.4 视觉规格：16sp 宽行距） */
private val BodyStyle = TextStyle(fontSize = 16.sp, lineHeight = 26.sp)

/** 段落行（计划 M4a §3 + 质检 P1-6 + §3.4 视觉）：讲/合并讲徽标 + why；SKIP 段全文半透明仍可读；EXPLAIN 左侧主题色竖条 */
@Composable
private fun ParaRow(p: ParagraphEntity) {
    // OPT-C C4：目录条目特殊展示——小字次级色，无重点竖条/无 AI 徽标（TOC 段也不参与 AI 标注）
    // OPT-E：脚注段同款次级展示（真书 E2E 后新增 ROLE_FOOTNOTE，AI 全链路跳过）
    if (p.role == DbValues.ROLE_TOC || p.role == DbValues.ROLE_FOOTNOTE) {
        Text(
            p.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        return
    }
    // EXPLAIN 段左侧主题色竖条（M4a §3.4）：IntrinsicSize 让竖条随内容高度
    val explained = p.aiAction == DbValues.ACT_EXPLAIN
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        if (explained) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = if (explained) 12.dp else 16.dp, vertical = 8.dp),
        ) {
            when (p.aiAction) {
                DbValues.ACT_EXPLAIN, DbValues.ACT_GROUP -> {
                    ParaTag(if (explained) "★ 讲" else "合并讲")
                    if (!p.why.isNullOrBlank()) {
                        Text(
                            "为什么讲：${p.why}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(p.text, style = BodyStyle)
                }
                DbValues.ACT_SKIP -> {
                    Text(
                        "已跳过",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        p.text,
                        style = BodyStyle,
                        modifier = Modifier.alpha(0.55f),
                    )
                }
                else -> Text(p.text, style = BodyStyle)
            }
        }
    }
}

@Composable
private fun ParaTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 讲解工具条（计划 M4b §3.1）：状态按单元数/有效 note 数/Runner 态数据推导，
 * 进程重启后仍可恢复"继续生成"入口。粗读或另一任务占用时禁用（AiGate 双保险）。
 */
@Composable
private fun NoteBar(
    chapterId: Long,
    paragraphs: List<ParagraphEntity>,
    notes: List<ParaNoteEntity>,
    noteState: NoteRunState,
    gateLabel: String?,
    roughState: RoughRunState,
    onGenerate: () -> Unit,
    onForce: () -> Unit,
    onStop: () -> Unit,
) {
    val units = remember(paragraphs) { enumerateUnits(paragraphs) }
    if (units.isEmpty()) return
    val unitParaIds = remember(units) {
        units.map { ParaIdsCodec.encode(it.members.map { m -> m.id }) }.toSet()
    }
    val validCount = notes.count {
        it.paraIds in unitParaIds && it.promptVersion == NotePlanner.NOTE_VERSION && it.friendly.isNotBlank()
    }
    val total = units.size
    val runningThis = noteState is NoteRunState.Running && noteState.chapterId == chapterId
    val roughRunning = roughState is RoughRunState.Running
    val busyOther = (gateLabel != null && !runningThis) || roughRunning

    // force 重生成会替换全部现有讲解：二次确认防误触
    var showForceConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        when {
            runningThis -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "搭子正在讲解 ${(noteState as NoteRunState.Running).done}/${noteState.total}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (noteState.total > 0) noteState.done.toFloat() / noteState.total else 0f
                        },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onStop) { Text("停止") }
                }
            }

            busyOther -> {
                Text(
                    if (roughRunning) "粗读完成后可生成讲解" else "另一项 AI 任务进行中，请稍候",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            validCount == total -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "本章讲解已全部生成（$total 处）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { showForceConfirm = true }) { Text("重新生成") }
                }
            }

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGenerate) {
                        Text(
                            if (validCount == 0) "让搭子讲解本章（共 $total 处）"
                            else "继续生成讲解（已有 $validCount/$total）",
                        )
                    }
                    if (validCount > 0) {
                        OutlinedButton(onClick = { showForceConfirm = true }) { Text("重新生成") }
                    }
                }
                // 中断原因小字（质检 P2-4）：与继续生成入口同现，对齐 M4a ActionBar 的做法
                (noteState as? NoteRunState.Interrupted)?.takeIf { it.chapterId == chapterId }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showForceConfirm) {
        AlertDialog(
            onDismissRequest = { showForceConfirm = false },
            title = { Text("重新生成全部讲解？") },
            text = { Text("搭子会重新讲解本章全部段落，现有讲解将被替换。") },
            confirmButton = {
                TextButton(onClick = {
                    showForceConfirm = false
                    onForce()
                }) { Text("重新生成") }
            },
            dismissButton = {
                TextButton(onClick = { showForceConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 讲解卡（计划 M4b §3.2）：标题+大白话+类比+要点+记忆钩子；生成中显示占位 */
@Composable
private fun NoteCard(note: ParaNoteEntity?, generating: Boolean) {
    when {
        note != null -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(note.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(note.friendly, style = MaterialTheme.typography.bodyMedium)
                note.analogy?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "打个比方：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val points = decodeStringList(note.keyPointsJson)
                if (points.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    points.forEach { p ->
                        Text("• $p", style = MaterialTheme.typography.bodySmall)
                    }
                }
                note.memoryHook?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "记忆钩子：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        generating -> Text(
            "搭子正在讲这一段…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        else -> {}
    }
}