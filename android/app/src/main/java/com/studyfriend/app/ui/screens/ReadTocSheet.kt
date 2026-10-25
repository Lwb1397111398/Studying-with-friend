package com.studyfriend.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studyfriend.app.data.db.BookmarkWithChapter
import com.studyfriend.app.data.db.ChapterWithAsset

/**
 * 目录+书签抽屉（功能补齐轮）：上半=全书章列表（当前章高亮，点章跳章）；
 * 下半=书签区（「把当前位置加书签」按钮 + 本书书签列表：章名+段摘+删除，点书签跳段）。
 * 从阅读页顶栏目录按钮呼出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadTocSheet(
    chapters: List<ChapterWithAsset>,
    currentChapterId: Long,
    bookmarks: List<BookmarkWithChapter>,
    onOpenChapter: (chapterId: Long) -> Unit,
    onAddBookmark: () -> Unit,
    onOpenBookmark: (BookmarkWithChapter) -> Unit,
    onDeleteBookmark: (BookmarkWithChapter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // —— 目录（占上半，可滚）——
            Text(
                "目录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                items(chapters, key = { it.id }) { ch ->
                    val current = ch.id == currentChapterId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChapter(ch.id) }
                            .background(
                                if (current) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${ch.idx}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            ch.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // —— 书签 ——
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("书签", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = onAddBookmark,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("加书签")
                }
            }
            if (bookmarks.isEmpty()) {
                Text(
                    "还没有书签。读到哪段想回头，就点「加书签」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                ) {
                    items(bookmarks, key = { it.bookmark.id }) { bm ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenBookmark(bm) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    bm.chapterTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    bm.bookmark.preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onDeleteBookmark(bm) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "删除书签",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
