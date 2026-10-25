package com.studyfriend.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.study.KeyTermsCodec
import com.studyfriend.app.data.study.decodeStringList
import kotlin.math.roundToInt


// 从 ReadScreen.kt 拆出（2026-10-11 结构批次）：段落行/划词文本/概要卡/讲解卡的渲染

/** 正文阅读字号/行距：随「Aa」弹层调节（功能补齐轮起行距可调，默认 1.7 倍） */
private fun bodyTextStyle(fontSize: Int, lineMult: Float): TextStyle =
    TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * lineMult).roundToInt().sp)

/**
 * 段落行（计划 M4a §3 + 质检 P1-6 + §3.4 视觉 + P7 划词）：讲/合并讲徽标 + why；
 * SKIP 段全文半透明仍可读；EXPLAIN 左侧主题色竖条；正文长按取词随手问。
 */
@Composable
internal fun ParaRow(
    p: ParagraphEntity,
    fontSize: Int,
    lineHeightMult: Float,
    selectedRange: IntRange?,
    ttsActive: Boolean = false,
    hasMemo: Boolean = false,
    onOpenMemo: (() -> Unit)? = null,
    onLongPress: (IntRange, IntOffset) -> Unit,
    onSelectionChange: ((IntRange) -> Unit)? = null,
    onAnchorChange: ((IntOffset) -> Unit)? = null,
    onDragStateChange: ((Boolean) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
) {
    // OPT-C C4：目录条目特殊展示——小字次级色，无重点竖条/无 AI 徽标（TOC 段也不参与 AI 标注）
    // OPT-E：脚注段同款次级展示；P5-F：FRONT/BACK（前/后置版权页）纳入次级展示，
    // 但仅视觉降级，AI 加工口径不变（ReadStyles.isSecondaryRole 展示与加工解耦）
    if (ReadStyles.isSecondaryRole(p.role)) {
        SelectableText(
            text = p.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            highlightRange = selectedRange,
            onLongPress = onLongPress,
            onSelectionChange = onSelectionChange,
            onAnchorChange = onAnchorChange,
            onDragStateChange = onDragStateChange,
            onTap = onTap,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    // EXPLAIN 段左侧主题色竖条（M4a §3.4）：IntrinsicSize 让竖条随内容高度；
    // 学习闭环轮：有批注的段右上角小铅笔标记，点开看/删自己的笔记
    val explained = p.aiAction == DbValues.ACT_EXPLAIN
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                // v7 正文听书：当前朗读段淡染底色（跟随高亮）
                .background(
                    if (ttsActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                ),
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
                .padding(horizontal = if (explained) 12.dp else 16.dp, vertical = 12.dp),
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
                    SelectableText(
                        text = p.text,
                        style = bodyTextStyle(fontSize, lineHeightMult),
                        highlightRange = selectedRange,
                        onLongPress = onLongPress,
                        onSelectionChange = onSelectionChange,
                        onAnchorChange = onAnchorChange,
                        onDragStateChange = onDragStateChange,
                        onTap = onTap,
                    )
                }
                DbValues.ACT_SKIP -> {
                    Text(
                        "已跳过",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectableText(
                        text = p.text,
                        style = bodyTextStyle(fontSize, lineHeightMult),
                        highlightRange = selectedRange,
                        onLongPress = onLongPress,
                        onSelectionChange = onSelectionChange,
                        onAnchorChange = onAnchorChange,
                        onDragStateChange = onDragStateChange,
                        onTap = onTap,
                        modifier = Modifier.alpha(0.55f),
                    )
                }
                else -> SelectableText(
                    text = p.text,
                    style = bodyTextStyle(fontSize, lineHeightMult),
                    highlightRange = selectedRange,
                    onLongPress = onLongPress,
                    onSelectionChange = onSelectionChange,
                    onAnchorChange = onAnchorChange,
                    onDragStateChange = onDragStateChange,
                    onTap = onTap,
                )
            }
        }
        }
        if (hasMemo) {
            IconButton(
                onClick = { onOpenMemo?.invoke() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 4.dp)
                    .size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "查看批注",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 可长按的正文 Text（P7 + 划词扩选轮）：长按取词组并默认扩到整句（所见即所选），
 * 回报句区间 + 光标矩形的 window 坐标；被选中段渲染底色高亮（primaryContainer）+
 * 两端拖拽把手（SelectionHandle）自由调整范围。
 * detectTapGestures 挂上即消费 tap，单击切换沉浸栏必须经 onTap 参数从这里透传（v7），
 * 外层再挂 detectTapGestures 收不到事件。
 */
@Composable
private fun SelectableText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    highlightRange: IntRange?,
    onLongPress: (IntRange, IntOffset) -> Unit,
    onSelectionChange: ((IntRange) -> Unit)? = null,
    onAnchorChange: ((IntOffset) -> Unit)? = null,
    onDragStateChange: ((Boolean) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val windowBounds = remember { mutableStateOf<Rect?>(null) }
    var handleDragging by remember { mutableStateOf(false) }
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val halfPx = with(LocalDensity.current) { 24.dp.toPx() / 2 }
    val hitRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }
    // 拖动中 highlightRange/回调随重组变化，泵闭包（Unit key 不重启）经 rememberUpdatedState 取最新
    val curRange by rememberUpdatedState(highlightRange)
    val curOnSelection by rememberUpdatedState(onSelectionChange)
    val curOnDragState by rememberUpdatedState(onDragStateChange)

    fun handleCenter(range: IntRange, isStart: Boolean): Offset? {
        val l = layout.value ?: return null
        val cursor = if (isStart) l.getCursorRect(range.first)
        else l.getCursorRect((range.last + 1).coerceAtMost(text.length))
        val x = if (isStart) cursor.left - halfPx * 0.7f else cursor.right + halfPx * 0.7f
        return Offset(x, cursor.center.y)
    }
    val annotated = remember(text, highlightRange, highlight) {
        if (highlightRange == null || highlightRange.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                addStyle(
                    SpanStyle(background = highlight),
                    highlightRange.first,
                    (highlightRange.last + 1).coerceAtMost(text.length),
                )
            }
        }
    }

    fun anchorOf(range: IntRange): IntOffset? {
        val l = layout.value ?: return null
        val global = windowBounds.value?.let { it.topLeft + l.getCursorRect(range.first).centerLeft }
            ?: return null
        return IntOffset(global.x.roundToInt(), global.y.roundToInt())
    }

    // 选区区间变化（扩句/扩段/拖把手）后校准浮条锚点，浮条跟随新选区
    LaunchedEffect(highlightRange) {
        if (highlightRange != null && !highlightRange.isEmpty()) {
            anchorOf(highlightRange)?.let { onAnchorChange?.invoke(it) }
        }
    }

    Box(
        modifier.pointerInput(Unit) {
            // 拖动泵挂 Text 外层 + Initial pass：父节点须抢在子节点 Text 的 detectTapGestures
            // 之前消费（Main pass 子先父后，纯点按 up 未消费会被 detect 判成 tap 误切沉浸——实测踩坑）。
            // Press 时按「距把手中心 ≤ 把手半径」判命中；down/up 全消费：点把手不透传，
            // 拖动 move 消费后 LazyColumn 不滚动。
            awaitPointerEventScope {
                while (true) {
                    val first = awaitPointerEvent(PointerEventPass.Initial)
                    if (first.type != PointerEventType.Press) continue
                    val range = curRange ?: continue
                    val press = first.changes[0].position
                    val hitStart = handleCenter(range, isStart = true)
                        ?.let { (press - it).getDistance() <= hitRadiusPx } == true
                    val hitEnd = handleCenter(range, isStart = false)
                        ?.let { (press - it).getDistance() <= hitRadiusPx } == true
                    if (!hitStart && !hitEnd) continue
                    first.changes.forEach { it.consume() }
                    handleDragging = true
                    curOnDragState?.invoke(true) // 拖动中藏浮条，松手随新锚点重现
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull() ?: break
                        when (ev.type) {
                            PointerEventType.Move -> {
                                c.consume()
                                val l = layout.value ?: continue
                                val r = curRange ?: continue
                                val off = l.getOffsetForPosition(c.position)
                                    .coerceIn(if (hitStart) 0 else r.first, if (hitStart) r.last else text.length - 1)
                                curOnSelection?.invoke(if (hitStart) off..r.last else r.first..off)
                            }
                            PointerEventType.Release -> {
                                c.consume()
                                break
                            }
                            PointerEventType.Exit -> break
                            else -> {}
                        }
                    }
                    handleDragging = false
                    curOnDragState?.invoke(false)
                }
            }
        },
    ) {
        Text(
            text = annotated,
            style = style,
            color = color,
            onTextLayout = { layout.value = it },
            modifier = Modifier
                .onGloballyPositioned { windowBounds.value = it.boundsInWindow() }
                .pointerInput(text) {
                    detectTapGestures(
                        onTap = { onTap?.invoke() },
                        onLongPress = { pos ->
                            val l = layout.value ?: return@detectTapGestures
                            // 长按默认选整句：词组定位 → 扩句；查词场景拖首把手收缩
                            val phrase = SelectionBounds.phraseAt(text, l.getOffsetForPosition(pos))
                            if (phrase.isEmpty()) return@detectTapGestures
                            val sentence = SelectionBounds.expandToSentence(text, phrase.first, phrase.last)
                            onLongPress(sentence, anchorOf(sentence) ?: IntOffset.Zero)
                        },
                    )
                },
        )
        // 拖拽把手：选区两端各一颗（纯视觉）；拖动手势在 Box 外层事件泵统一处理
        val l = layout.value
        if (highlightRange != null && !highlightRange.isEmpty() && l != null) {
            SelectionHandle(
                cursor = l.getCursorRect(highlightRange.first),
                isStart = true,
                dragging = handleDragging,
            )
            SelectionHandle(
                cursor = l.getCursorRect((highlightRange.last + 1).coerceAtMost(text.length)),
                isStart = false,
                dragging = handleDragging,
            )
        }
    }
}

/**
 * 选区拖拽把手（划词扩选轮）：24dp 圆点悬在选区起/终点旁，纯视觉展示；
 * 拖动手势由 SelectableText 外层事件泵统一处理（按 Press 位置距把手中心判定命中）。
 * 不在把手上挂 pointerInput——子 Box 节点收不到 Press 后续事件（模拟器实测）。
 */
@Composable
private fun SelectionHandle(
    cursor: Rect,
    isStart: Boolean,
    dragging: Boolean,
) {
    val handleSize = 24.dp
    val half = with(LocalDensity.current) { handleSize.toPx() / 2 }
    // 首把手悬在选区起点左侧、尾把手悬在终点右侧，垂直居中于所在行
    val cx = if (isStart) cursor.left - half * 0.7f else cursor.right + half * 0.7f
    val cy = cursor.center.y
    Box(
        Modifier
            .offset { IntOffset((cx - half).roundToInt(), (cy - half).roundToInt()) }
            .size(handleSize)
            .background(
                if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                CircleShape,
            )
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
    )
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

/** 本章要点卡（P7 移入段落流可折叠）：gist + key_terms chips（M4a §3.4 视觉规格） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GistCard(gist: String, keyTermsJson: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(gist, style = MaterialTheme.typography.bodyMedium)
            val terms = KeyTermsCodec.decode(keyTermsJson)
            if (terms.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
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

/** 讲解卡（计划 M4b §3.2 + 学习闭环轮）：标题+大白话+类比+要点+记忆钩子；生成中显示占位；
 *  底部「换个讲法」由调用方接弹层（临时重讲不落库，见 ReNoteSheet） */
@Composable
internal fun NoteCard(note: ParaNoteEntity?, generating: Boolean, onReNote: ((ParaNoteEntity) -> Unit)? = null) {
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
                if (onReNote != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onReNote(note) }) { Text("没看懂？换个讲法") }
                    }
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
