package com.studyfriend.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.BookRepository
import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.VisionProgress
import kotlinx.coroutines.launch

/** 封面色板（上→下渐变）：低饱和书房色，与米白纸感主题协调；按 book.id 稳定取色，同一本书永远同色 */
private val COVER_PALETTES = listOf(
    Color(0xFF41608C) to Color(0xFF2C4465), // 藏青
    Color(0xFF56806F) to Color(0xFF3A5B4E), // 墨绿
    Color(0xFFA56868) to Color(0xFF7F4B4B), // 绛红
    Color(0xFF8D6E50) to Color(0xFF6B503A), // 茶棕
    Color(0xFF776089) to Color(0xFF584666), // 藕紫
    Color(0xFF5B7A8C) to Color(0xFF425B69), // 黛蓝
    Color(0xFFB08968) to Color(0xFF8C6A4A), // 驼色
    Color(0xFF7A8A70) to Color(0xFF5C6B54), // 橄榄
)

@Composable
fun ShelfScreen(onImport: () -> Unit, onOpenBook: (Long) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as StudyApp
    val repo = remember { BookRepository(app.database, app.filesDir) }
    val books by repo.shelfFlow().collectAsStateWithLifecycle(initialValue = null)
    // 书架复习徽标（M6 计划 §3.3）：待复习章数角标；now 进页固化
    val now = remember { System.currentTimeMillis() }
    val dueCounts by repo.dueCountByBookFlow(now).collectAsStateWithLifecycle(initialValue = emptyList())
    val dueByBook = remember(dueCounts) { dueCounts.associate { it.bookId to it.cnt } }
    // 视觉增强徽标（OPT-F）：后台转写进度 "视觉增强 x/y"，有队列的书才显示
    val visionProgress by repo.visionProgressFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val visionByBook = remember(visionProgress) { visionProgress.associate { it.bookId to it } }
    // 长按删除：数据层 removeBook 已有（CASCADE+磁盘图清理），此处只补 UI 入口
    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        val list = books
        when {
            list == null -> Unit // 首帧加载中
            list.isEmpty() -> EmptyState(
                title = "书架还是空的",
                subtitle = "点右下角 + 导入 TXT，开始和搭子一起读书",
            )
            else -> BookShelf(
                list, dueByBook, visionByBook, onOpenBook,
                onDeleteRequest = { pendingDelete = it },
            )
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

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除《${book.title}》？") },
            text = { Text("章节、复习记录和插图会一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { repo.removeBook(book) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 书架主体：封面网格。Adaptive 列宽——手机竖屏约 3 列、横屏/平板更多，随宽度自适应 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookShelf(
    books: List<BookEntity>,
    dueByBook: Map<Long, Int>,
    visionByBook: Map<Long, VisionProgress>,
    onOpenBook: (Long) -> Unit,
    onDeleteRequest: (BookEntity) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("书架", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(10.dp))
                Text(
                    "共 ${books.size} 本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        items(books, key = { it.id }) { book ->
            BookCard(
                book,
                due = dueByBook[book.id] ?: 0,
                vision = visionByBook[book.id],
                onClick = { onOpenBook(book.id) },
                onLongClick = { onDeleteRequest(book) },
            )
        }
    }
}

/** 一张书卡：实体书封面（渐变+书脊+厚度阴影+装饰框）+ 封面下状态行 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    due: Int,
    vision: VisionProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ready = book.status == DbValues.BOOK_READY
    val (top, bottom) = COVER_PALETTES[(book.id % COVER_PALETTES.size).toInt()]
    // 书脊在左侧：左缘窄圆角、右缘（翻口）稍大圆角
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 7.dp, bottomEnd = 7.dp)

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .shadow(6.dp, shape, spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(shape)
                .background(Brush.verticalGradient(listOf(top, bottom)))
                .combinedClickable(enabled = ready, onClick = onClick, onLongClick = onLongClick),
        ) {
            // 书脊：左缘向右渐隐的暗色，营造装订边的凹陷感
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.30f),
                            0.14f to Color.Transparent,
                        ),
                    ),
            )
            // 翻口：右缘厚度阴影，模拟纸页叠出来的立体感
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.86f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.28f),
                        ),
                    ),
            )
            // 经典精装书的细装饰框
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(9.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.32f), RectangleShape),
            )
            // 封面文字：书名居中，作者沉底
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .width(26.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.55f)),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    book.title,
                    color = Color.White.copy(alpha = 0.96f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 待复习角标（M6）：贴在封面右上角，像一枚小书签章
            if (ready && due > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 7.dp, y = (-7).dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        due.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // 解析未完成：整面蒙层，不可点
            if (!ready) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("导入中", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        // 封面下状态行：待复习 > 视觉增强进行中；都无则留白保持干净
        when {
            !ready -> Unit
            due > 0 -> Text(
                "$due 章待复习",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
            vision != null && vision.done < vision.total -> Text(
                "视觉增强 ${vision.done}/${vision.total}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            else -> Unit
        }
    }
}
