package com.studyfriend.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.SearchHit
import com.studyfriend.app.data.db.escapeLikePattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 全书搜索单次查询上限：超出只显示前 N 条（LIKE 全表扫，这个量级 UI 也不该再长） */
private const val SEARCH_LIMIT = 200

/** 命中片段前后各取的字符数 */
private const val SNIPPET_PAD = 24

/**
 * 全书搜索页（功能补齐轮）：输入防抖 300ms 后 LIKE 全书段落查询，
 * 结果 = 章名 + 命中片段高亮；点结果跳阅读页滚到该段（Routes.read paraId 参数）。
 * 入口在章目录页顶栏搜索图标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenHit: (chapterId: Long, paraId: Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as StudyApp
    var query by remember { mutableStateOf("") }
    // null=空闲（没搜过/空关键字）；非空=结果列表
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var searching by remember { mutableStateOf(false) }

    // 输入防抖 300ms：停顿后再查，避免每个字符打一次 LIKE
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            hits = null
            searching = false
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        val result = withContext(Dispatchers.IO) {
            app.database.paragraphDao().searchInBook(bookId, escapeLikePattern(q), limit = SEARCH_LIMIT)
        }
        // 防抖竞态：结果回来时输入已变则丢弃
        if (q == query.trim()) hits = result
        searching = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("全书搜索") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜书里的词或句子…") },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
        )
        val list = hits
        when {
            searching -> Text(
                "搜索中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            list == null -> Unit
            list.isEmpty() -> EmptyState(title = "没有找到", subtitle = "换个关键词试试")
            else -> {
                Text(
                    if (list.size >= SEARCH_LIMIT) "命中较多，只显示前 $SEARCH_LIMIT 处" else "共 ${list.size} 处",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.paraId }) { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenHit(hit.chapterId, hit.paraId) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                hit.chapterTitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                snippet(
                                    hit.text,
                                    query.trim(),
                                    MaterialTheme.colorScheme.primaryContainer,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/** 命中片段：第一个命中位置前后各 [SNIPPET_PAD] 字，范围内所有命中词染色（LIKE 口径 ASCII 不分大小写） */
internal fun snippet(text: String, q: String, highlightColor: Color) = buildAnnotatedString {
    if (q.isEmpty()) {
        append(text.take(SNIPPET_PAD * 2))
        return@buildAnnotatedString
    }
    val first = text.indexOf(q, ignoreCase = true)
    if (first < 0) {
        // LIKE 转义通配符的极端情形下定位失败：退化为段首
        append(text.take(SNIPPET_PAD * 2))
        return@buildAnnotatedString
    }
    val start = (first - SNIPPET_PAD).coerceAtLeast(0)
    val end = (first + q.length + SNIPPET_PAD).coerceAtMost(text.length)
    val prefixLen = if (start > 0) 1 else 0 // 前置省略号占位
    if (start > 0) append('…')
    append(text.substring(start, end))
    if (end < text.length) append('…')
    // 片段内全部命中词染色（偏移按片段起点+省略号平移）
    val hl = SpanStyle(background = highlightColor)
    var from = start
    while (true) {
        val at = text.indexOf(q, from, ignoreCase = true)
        if (at < 0 || at + q.length > end) break
        addStyle(hl, at - start + prefixLen, at + q.length - start + prefixLen)
        from = at + q.length
    }
}
