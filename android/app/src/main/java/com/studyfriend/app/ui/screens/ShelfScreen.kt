package com.studyfriend.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.BookRepository
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.VisionProgress

@Composable
fun ShelfScreen(onImport: () -> Unit, onOpenBook: (Long) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as StudyApp
    val repo = remember { BookRepository(app.database, app.filesDir) }
    val books by repo.shelfFlow().collectAsStateWithLifecycle(initialValue = null)
    // 书架复习徽标（M6 计划 §3.3）：N>0 行尾"N 章待复习"；now 进页固化
    val now = remember { System.currentTimeMillis() }
    val dueCounts by repo.dueCountByBookFlow(now).collectAsStateWithLifecycle(initialValue = emptyList())
    val dueByBook = remember(dueCounts) { dueCounts.associate { it.bookId to it.cnt } }
    // 视觉增强徽标（OPT-F）：后台转写进度 "视觉增强 x/y"，有队列的书才显示
    val visionProgress by repo.visionProgressFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val visionByBook = remember(visionProgress) { visionProgress.associate { it.bookId to it } }

    Box(Modifier.fillMaxSize()) {
        val list = books
        when {
            list == null -> Unit // 首帧加载中
            list.isEmpty() -> EmptyState(
                title = "书架还是空的",
                subtitle = "点右下角 + 导入 TXT，开始和搭子一起读书",
            )
            else -> BookList(list, dueByBook, visionByBook, onOpenBook)
        }

        FloatingActionButton(
            onClick = onImport,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "导入书籍")
        }
    }
}

@Composable
private fun BookList(
    books: List<BookEntity>,
    dueByBook: Map<Long, Int>,
    visionByBook: Map<Long, VisionProgress>,
    onOpenBook: (Long) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "书架",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        items(books, key = { it.id }) { book ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = book.status == DbValues.BOOK_READY,
                        onClick = { onOpenBook(book.id) },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.bodyLarge)
                    val subtitle = buildString {
                        if (book.author.isNotBlank()) append(book.author)
                        if (book.totalChapters > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${book.totalChapters} 章")
                        }
                        visionByBook[book.id]?.let { vp ->
                            if (isNotEmpty()) append(" · ")
                            append(if (vp.done >= vp.total) "视觉增强完成" else "视觉增强 ${vp.done}/${vp.total}")
                        }
                    }
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when {
                    book.status != DbValues.BOOK_READY -> Unit
                    (dueByBook[book.id] ?: 0) > 0 -> Text(
                        "${dueByBook[book.id]} 章待复习",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    else -> Text(
                        "已导入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}
