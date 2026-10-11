package com.studyfriend.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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

/** 正文阅读字号：随「Aa」弹层四档切换（P7），行高 1.7 倍保持阅读节奏 */
private fun bodyTextStyle(fontSize: Int): TextStyle =
    TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.7f).roundToInt().sp)

/**
 * 段落行（计划 M4a §3 + 质检 P1-6 + §3.4 视觉 + P7 划词）：讲/合并讲徽标 + why；
 * SKIP 段全文半透明仍可读；EXPLAIN 左侧主题色竖条；正文长按取词随手问。
 */
@Composable
internal fun ParaRow(
    p: ParagraphEntity,
    fontSize: Int,
    selectedRange: IntRange?,
    onLongPress: (IntRange, IntOffset) -> Unit,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        style = bodyTextStyle(fontSize),
                        highlightRange = selectedRange,
                        onLongPress = onLongPress,
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
                        style = bodyTextStyle(fontSize),
                        highlightRange = selectedRange,
                        onLongPress = onLongPress,
                        modifier = Modifier.alpha(0.55f),
                    )
                }
                else -> SelectableText(
                    text = p.text,
                    style = bodyTextStyle(fontSize),
                    highlightRange = selectedRange,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

/**
 * 可长按的正文 Text（P7）：长按取词（SelectionBounds.wordAt）回报词区间 + 光标矩形
 * 的 window 坐标；被选中段渲染底色高亮（primaryContainer）。普通 tap 不消费，滚动不受影响。
 */
@Composable
private fun SelectableText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    highlightRange: IntRange?,
    onLongPress: (IntRange, IntOffset) -> Unit,
) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val windowBounds = remember { mutableStateOf<Rect?>(null) }
    val highlight = MaterialTheme.colorScheme.primaryContainer
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
    Text(
        text = annotated,
        style = style,
        color = color,
        onTextLayout = { layout.value = it },
        modifier = modifier
            .onGloballyPositioned { windowBounds.value = it.boundsInWindow() }
            .pointerInput(text) {
                detectTapGestures(onLongPress = { pos ->
                    val l = layout.value ?: return@detectTapGestures
                    val word = SelectionBounds.wordAt(text, l.getOffsetForPosition(pos))
                    if (word.isEmpty()) return@detectTapGestures
                    val cursor = l.getCursorRect(word.first)
                    val global = windowBounds.value?.let { it.topLeft + cursor.centerLeft } ?: Offset.Zero
                    onLongPress(word, IntOffset(global.x.roundToInt(), global.y.roundToInt()))
                })
            },
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

/** 讲解卡（计划 M4b §3.2）：标题+大白话+类比+要点+记忆钩子；生成中显示占位 */
@Composable
internal fun NoteCard(note: ParaNoteEntity?, generating: Boolean) {
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
