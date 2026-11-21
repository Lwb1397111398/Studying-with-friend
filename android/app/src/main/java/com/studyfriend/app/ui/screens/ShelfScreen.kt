package com.studyfriend.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
import com.studyfriend.app.data.db.BookProgress
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.VisionProgress
import com.studyfriend.app.data.vision.VisionScheduler
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

/**
 * 进程级书单缓存（体验优化轮旋转 Bug 修复）：旋转/主题切换等场景会重建 Activity，
 * Room Flow 重新收集前的首帧窗口期里 collectAsState 拿到的是 initialValue——真机大库
 * 慢盘时窗口期肉眼可见（书架空白）。进程存活期间静态字段不会丢，重建首帧直接用
 * 上一次的书单渲染，Room 重发后无缝刷新。
 */
private var cachedShelfBooks: List<BookEntity>? = null

@Composable
fun ShelfScreen(
    onImport: () -> Unit,
    // 体验优化轮：点书直达上次阅读位置——有阅读记忆（status=READY 且 lastReadChapterId
    // 非空）直接进阅读页并恢复到段；没读过的书才进章目录页挑选。整本 BookEntity 传出
    onOpenBook: (BookEntity) -> Unit = {},
    onContinueRead: (chapterId: Long) -> Unit = {},
    // 长按菜单「查看章节目录」：章目录页仍有全书搜索/总览入口
    onOpenChapters: (bookId: Long) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as StudyApp
    val repo = remember { BookRepository(app.database, app.filesDir) }
    // 旋转修复：initialValue 取进程缓存，重建首帧即有书；Room 重发后照常刷新并回写缓存
    val books by repo.shelfFlow().collectAsStateWithLifecycle(initialValue = cachedShelfBooks)
    LaunchedEffect(books) { books?.let { cachedShelfBooks = it } }
    // v7 全书架进度（已读 x/y 章）：书卡进度条数据源
    val progressAll by app.database.bookDao().progressAllFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val progressByBook = remember(progressAll) { progressAll.associateBy { it.id } }
    // v7 继续读：最近在读的书（getAllFlow 已按 lastReadAt DESC 排序）
    val continueBook = books?.firstOrNull { it.lastReadAt != null && it.lastReadChapterId != null }
    var continueChapterTitle by remember { mutableStateOf("") }
    LaunchedEffect(continueBook?.lastReadChapterId) {
        val cid = continueBook?.lastReadChapterId ?: return@LaunchedEffect
        continueChapterTitle = app.database.chapterDao().byIdOnce(cid)?.title.orEmpty()
    }
    // 书架复习徽标（M6 计划 §3.3）：待复习章数角标；now 进页固化
    val now = remember { System.currentTimeMillis() }
    val dueCounts by repo.dueCountByBookFlow(now).collectAsStateWithLifecycle(initialValue = emptyList())
    val dueByBook = remember(dueCounts) { dueCounts.associate { it.bookId to it.cnt } }
    // 视觉增强徽标（OPT-F）：后台转写进度 "视觉增强 x/y"，有队列的书才显示
    val visionProgress by repo.visionProgressFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val visionByBook = remember(visionProgress) { visionProgress.associate { it.bookId to it } }
    // 长按菜单（v7 重命名/删除）：数据层 removeBook 已有，重命名走 bookDao.update
    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }
    var pendingRename by remember { mutableStateOf<BookEntity?>(null) }
    var menuFor by remember { mutableStateOf<BookEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        val list = books
        when {
            // 首帧加载中且没有缓存可画：给个安静的加载圈，不再整屏空白
            list == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(
                title = "书架还是空的",
                // 实景体验轮：格式列全——手里是 Kit 图包/扫描 PDF 的新用户别被「导入 TXT」劝退
                subtitle = "点右下角 + 导入 TXT、PDF 或 Kit 图包，开始和搭子一起读书",
                icon = Icons.Filled.Add,
            )
            else -> BookShelf(
                list, dueByBook, visionByBook, progressByBook,
                continueBook = continueBook?.takeIf { it.status == DbValues.BOOK_READY },
                continueChapterTitle = continueChapterTitle,
                onContinueRead = onContinueRead,
                onOpenBook = onOpenBook,
                onMenu = { menuFor = it },
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

    // v7 长按书卡：章节目录 / 重命名 / 删除 / 视觉增强重试（批B） / 取消
    menuFor?.let { book ->
        val vstat = visionByBook[book.id]
        val failedPages = vstat?.failed ?: 0
        val pendingPages = (vstat?.total ?: 0) - (vstat?.done ?: 0) - failedPages
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text("《${book.title}》") },
            text = {
                // 2026-11-15 UI 打磨轮：去掉突兀的「书架共 N 本」计数行，操作按常用度纵排
                Column {
                    // 体验优化轮：点书直达阅读位置后，章目录页从这里进（保留全书搜索/总览入口）
                    if (book.status == DbValues.BOOK_READY) {
                        TextButton(onClick = {
                            menuFor = null
                            onOpenChapters(book.id)
                        }) { Text("查看章节目录") }
                    }
                    TextButton(onClick = {
                        pendingRename = book
                        renameInput = book.title
                        menuFor = null
                    }) { Text("重命名") }
                    if (failedPages > 0) {
                        Text(
                            "视觉增强 ${vstat!!.done}/${vstat.total}，${failedPages} 页失败",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (pendingPages > 0) {
                        Text(
                            "待视觉补识别 ${pendingPages} 页（配置视觉模型后自动后台补齐）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (failedPages > 0 || pendingPages > 0) {
                        TextButton(onClick = {
                            menuFor = null
                            scope.launch {
                                if (app.settingsRepo.buildVisionTranscriber() == null) {
                                    android.widget.Toast.makeText(
                                        ctx, "未配置视觉模型：设置 → 模型页配置后自动补齐", android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    VisionScheduler.retryFailedByBook(app.database, app, book.id)
                                }
                            }
                        }) { Text(if (failedPages > 0) "重试视觉增强（${failedPages} 页失败）" else "开始视觉补识别") }
                    }
                    TextButton(onClick = {
                        pendingDelete = book
                        menuFor = null
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { menuFor = null }) { Text("取消") }
            },
        )
    }

    // v7 重命名弹层：预填现名，确认即存（title + updatedAt，书架/章节页/总览同步刷新）
    pendingRename?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名书籍") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = renameInput.trim()
                        pendingRename = null
                        if (t.isNotEmpty() && t != book.title) {
                            scope.launch {
                                app.database.bookDao().update(book.copy(title = t, updatedAt = System.currentTimeMillis()))
                            }
                        }
                    },
                    enabled = renameInput.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("取消") }
            },
        )
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
    progressByBook: Map<Long, BookProgress>,
    continueBook: BookEntity?,
    continueChapterTitle: String,
    onContinueRead: (Long) -> Unit,
    onOpenBook: (BookEntity) -> Unit,
    onMenu: (BookEntity) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("书架", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "共 ${books.size} 本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                // 标题下的主色小横条：一点书签丝带的味道
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .width(36.dp)
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                            ),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
        // 继续读卡（体验优化轮重做）：小封面 + 书名章名 + 进度 + 箭头，primaryContainer 底更醒目
        if (continueBook != null && continueChapterTitle.isNotBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val (ctop, cbottom) = COVER_PALETTES[(continueBook.id % COVER_PALETTES.size).toInt()]
                val cprog = progressByBook[continueBook.id]
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { continueBook.lastReadChapterId?.let(onContinueRead) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 小封面缩略：同封面色板渐变 + 首字，一眼认出是哪本
                        Box(
                            Modifier
                                .size(width = 40.dp, height = 56.dp)
                                .shadow(3.dp, RoundedCornerShape(6.dp))
                                .clip(RoundedCornerShape(6.dp))
                                .background(Brush.verticalGradient(listOf(ctop, cbottom))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                continueBook.title.take(1).ifBlank { "书" },
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "继续读",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "《${continueBook.title}》· $continueChapterTitle",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (cprog != null && cprog.totalChapters > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "已读 ${cprog.readChapters}/${cprog.totalChapters} 章",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "继续阅读",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        items(books, key = { it.id }) { book ->
            BookCard(
                book,
                due = dueByBook[book.id] ?: 0,
                vision = visionByBook[book.id],
                progress = progressByBook[book.id],
                onClick = { onOpenBook(book) },
                onLongClick = { onMenu(book) },
            )
        }
    }
}

/** 一张书卡：实体书封面（渐变+书脊+纸页纹+厚度阴影+装饰框）+ 封面下状态行 + 阅读进度条（v7） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    due: Int,
    vision: VisionProgress?,
    progress: BookProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ready = book.status == DbValues.BOOK_READY
    val (top, bottom) = COVER_PALETTES[(book.id % COVER_PALETTES.size).toInt()]
    // 书脊在左侧：左缘窄圆角、右缘（翻口）稍大圆角
    val shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .shadow(5.dp, shape, spotColor = Color.Black.copy(alpha = 0.30f))
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
            // 纸页纹（体验优化轮）：右缘一组细白横线，封面立刻有「一本书」的辨识度
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val stripeW = size.width * 0.04f
                        val x0 = size.width - stripeW - size.width * 0.02f
                        val top = size.height * 0.07f
                        val bottom = size.height * 0.93f
                        val lines = 6
                        val gap = (bottom - top) / lines
                        for (i in 1 until lines) {
                            val y = top + gap * i
                            drawLine(
                                Color.White.copy(alpha = 0.20f),
                                Offset(x0, y),
                                Offset(x0 + stripeW, y),
                                strokeWidth = 1.2f,
                            )
                        }
                    },
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
        // 封面下状态行（2026-11-15 UI 打磨轮）：固定高度占位——同排卡片有没有状态文字，
        // 下面的进度条都齐平（原先「视觉增强 x/y」把进度条压低一截，同排错位）
        Box(Modifier.fillMaxWidth().height(22.dp)) {
            when {
                !ready -> Unit
                due > 0 -> Text(
                    "$due 章待复习",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                vision != null && vision.done < vision.total -> Text(
                    "视觉增强 ${vision.done}/${vision.total}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                else -> Unit
            }
        }
        // v7 阅读进度条：已读 x/y 章（总计划 §4.2 书卡进度条落地）
        if (ready && progress != null && progress.totalChapters > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LinearProgressIndicator(
                    progress = { progress.readChapters.toFloat() / progress.totalChapters },
                    modifier = Modifier.weight(1f).height(4.dp),
                )
                Text(
                    "${progress.readChapters}/${progress.totalChapters}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
