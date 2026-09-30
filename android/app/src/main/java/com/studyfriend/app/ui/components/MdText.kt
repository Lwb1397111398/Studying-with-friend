package com.studyfriend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 轻量 markdown 块：只支持总结包约定内的记号（标题/无序列表/段落），其余按普通段落 */
internal sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Para(val text: String) : MdBlock()
}

private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val bulletRegex = Regex("^[-*•]\\s+(.+)$")

/** 逐行块解析：#/##/### 标题（更深层级归 3）、减号/星号/圆点列表、其余段落；空行跳过 */
internal fun parseMdBlocks(md: String): List<MdBlock> {
    if (md.isBlank()) return emptyList()
    val blocks = mutableListOf<MdBlock>()
    for (raw in md.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val h = headingRegex.find(line)
        val b = bulletRegex.find(line)
        when {
            h != null -> blocks.add(
                MdBlock.Heading(level = minOf(h.groupValues[1].length, 3), text = h.groupValues[2].trim()),
            )
            b != null -> blocks.add(MdBlock.Bullet(b.groupValues[1].trim()))
            else -> blocks.add(MdBlock.Para(line))
        }
    }
    return blocks
}

private val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
private val codeRegex = Regex("`([^`]+)`")

/** 行内记号：**粗体** 与 `代码`，span 用注解 tag "b"/"code" 标记（测试断言边界） */
internal fun buildInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i <= text.lastIndex) {
        val bold = boldRegex.find(text, i)
        val code = codeRegex.find(text, i)
        val next = listOfNotNull(bold, code).minByOrNull { it.range.first }
        if (next == null) {
            append(text.substring(i))
            break
        }
        append(text.substring(i, next.range.first))
        val isBold = next === bold
        pushStyle(if (isBold) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontFamily = FontFamily.Monospace))
        pushStringAnnotation(if (isBold) "b" else "code", next.groupValues[1])
        append(next.groupValues[1])
        pop()
        pop()
        i = next.range.last + 1
    }
}

/** 总结包 md 渲染（计划 M5 §3.3）：标题分三级加粗、列表带圆点、粗体/行内代码生效 */
@Composable
fun MdText(md: String, modifier: Modifier = Modifier) {
    val blocks = remember(md) { parseMdBlocks(md) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    buildInline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is MdBlock.Bullet -> Row {
                    Text("• ", style = MaterialTheme.typography.bodyLarge)
                    Text(buildInline(block.text), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Para -> Text(
                    buildInline(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
