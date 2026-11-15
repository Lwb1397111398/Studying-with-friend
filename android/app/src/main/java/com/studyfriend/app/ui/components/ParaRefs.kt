package com.studyfriend.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.studyfriend.app.data.study.ParaRef

// 引用溯源渲染（P1-③）：〔p编号〕→ 可点小胶囊「段N」。
// 生成端（data/study/ParaRef）已剔除白名单外标记，这里只处理合法标记；
// 旧讲解/旧总结无标记走原渲染路径，零回归面。

/** 行内切分：标记与普通文本交替 */
internal sealed class ParaRefSegment {
    data class Plain(val text: String) : ParaRefSegment()
    data class Ref(val idx: Int) : ParaRefSegment()
}

internal fun splitParaRefs(text: String): List<ParaRefSegment> {
    if (!ParaRef.REGEX.containsMatchIn(text)) return listOf(ParaRefSegment.Plain(text))
    val out = ArrayList<ParaRefSegment>()
    var last = 0
    for (m in ParaRef.REGEX.findAll(text)) {
        if (m.range.first > last) out.add(ParaRefSegment.Plain(text.substring(last, m.range.first)))
        out.add(ParaRefSegment.Ref(m.groupValues[1].toInt()))
        last = m.range.last + 1
    }
    if (last < text.length) out.add(ParaRefSegment.Plain(text.substring(last)))
    return out
}

/** 行内组合：普通文本段走 buildInline（粗体/行内码保留），引用段用 inlineContent 占位 */
internal fun buildInlineWithRefs(text: String): AnnotatedString = buildAnnotatedString {
    for (seg in splitParaRefs(text)) {
        when (seg) {
            is ParaRefSegment.Plain -> append(buildInline(seg.text))
            is ParaRefSegment.Ref -> appendInlineContent("ref:${seg.idx}", "[段${seg.idx + 1}]")
        }
    }
}

/** inlineContent map：每个标记一颗胶囊，点击回调标记编号；无标记返回空 map（走原渲染） */
internal fun paraRefContents(text: String, onRef: (Int) -> Unit): Map<String, InlineTextContent> {
    val refs = splitParaRefs(text).filterIsInstance<ParaRefSegment.Ref>()
    if (refs.isEmpty()) return emptyMap()
    return refs.distinctBy { it.idx }.associate { seg ->
        "ref:${seg.idx}" to InlineTextContent(
            Placeholder(2.8.em, 1.3.em, PlaceholderVerticalAlign.TextCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.clickable { onRef(seg.idx) },
            ) {
                Text(
                    "段${seg.idx + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** 含引用感知的文本渲染：无回调或无标记走原路径（buildInline），否则带胶囊 */
@Composable
internal fun RefAwareText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    onParaRef: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onParaRef != null && ParaRef.REGEX.containsMatchIn(text)) {
        Text(
            buildInlineWithRefs(text),
            style = style,
            modifier = modifier,
            inlineContent = paraRefContents(text, onParaRef),
        )
    } else {
        Text(buildInline(text), style = style, modifier = modifier)
    }
}
