package com.studyfriend.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.BookRepository
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.DueItem
import com.studyfriend.app.data.db.QuizAttemptEntity
import com.studyfriend.app.data.db.computeStreak
import com.studyfriend.app.data.study.DAY_MS
import com.studyfriend.app.data.study.CheckQuestion
import com.studyfriend.app.data.study.Fsrs
import com.studyfriend.app.data.study.KeyTermsCodec
import com.studyfriend.app.data.study.QuizCodec
import com.studyfriend.app.data.study.QuizQuestion
import com.studyfriend.app.data.study.ReviewVerdict
import com.studyfriend.app.data.study.TermBookGroup
import com.studyfriend.app.data.study.TermChapter
import com.studyfriend.app.data.study.decodeCheckQuestions
import com.studyfriend.app.data.study.aggregateVerdict
import com.studyfriend.app.data.study.DedupedTerm
import com.studyfriend.app.data.study.buildTermGroups
import com.studyfriend.app.data.study.dedupeBookTerms
import com.studyfriend.app.data.study.latestWrongAttempts
import com.studyfriend.app.data.study.nextStreakMilestone
import com.studyfriend.app.data.study.streakMilestoneHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 复习中心（M6 计划 §3.1 + 学习闭环轮）：Tab0 今日复习（到期列表 → 章内评估流）
 * + Tab1 错题本（去重按书分组，可整书重测）+ Tab2 术语卡盒（全书 keyTerms 按书翻看）。
 * 底栏 Tab 页，自行取 StudyApp 依赖；第八波加 onContinueRead（空态续读卡导航回调，
 * 缺省空实现保持旧无参行为）。
 */
@Composable
fun ReviewScreen(
    onContinueRead: (chapterId: Long) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as StudyApp
    val repo = remember { BookRepository(app.database, app.filesDir) }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 评估会话快照（计划 §2.2）：进入时读一次资产；复习中资产被重生成/清空不中途换题。
    // 会话不跨旋转恢复（统一 remember，与 M5 ChapterAssetsScreen 同口径）
    var session by remember { mutableStateOf<ReviewSession?>(null) }
    // 学习闭环轮：错题重测会话（null = 不在重测中）
    var wrongSession by remember { mutableStateOf<WrongSession?>(null) }

    if (session != null) {
        val s = session!!
        ReviewSessionPane(
            session = s,
            onDone = { adv ->
                // 落库挂 appScope：自评完成瞬间切 Tab/退页也不丢这次推进（P0-①：卡状态+复习日志同事务落库）
                app.appScope.launch {
                    repo.markReviewResult(s.due.item.id, adv)
                }
                session = null
            },
            onCancel = { session = null },
        )
        return
    }
    if (wrongSession != null) {
        val s = wrongSession!!
        WrongSessionPane(
            session = s,
            onAnswer = { row, answer, verdict ->
                // 逐题落 quiz_attempts：重测的 CORRECT 最新行使题目自动出列（wrongLatest 去重语义）
                app.appScope.launch {
                    repo.addAttempt(
                        QuizAttemptEntity(
                            chapterId = row.chapterId, qIndex = row.qIndex, answer = answer,
                            verdict = verdict, feedback = row.explain.takeIf { it.isNotBlank() },
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            },
            onDone = { wrongSession = null },
        )
        return
    }

        Column(Modifier.fillMaxSize()) {
        // P1-⑤ 激励统计：统计卡（今日目标环/连续/累计）+ 热力图卡，refreshKey 让撤销评分后重查
        var statsRefresh by remember { mutableIntStateOf(0) }
        val today = remember { java.time.LocalDate.now().toString() }
        val stats by produceState<ReadingSummary?>(initialValue = null, statsRefresh) {
            val byDay = app.database.readingStatDao().minutesByDay()
            value = ReadingSummary(
                todayMinutes = byDay.firstOrNull { it.day == today }?.minutes ?: 0,
                streakDays = computeStreak(byDay.map { it.day }.toSet(), java.time.LocalDate.now()),
                totalMinutes = byDay.sumOf { it.minutes },
                byDay = byDay,
            )
        }
        val goalMinutes by produceState(initialValue = DEFAULT_GOAL_MINUTES, statsRefresh) {
            value = app.settingsRepo.readDailyGoalMinutes()
        }
        val canUndo by produceState(initialValue = false, statsRefresh) {
            value = app.database.reviewLogDao().latest() != null
        }
        ReadingStatsCard(stats, goalMinutes)
        // P1 复习两小招：一键撤销上次评分（有评分日志才显示；撤销后 refreshKey 触发列表与统计重查）
        if (canUndo) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    app.appScope.launch {
                        val ok = repo.undoLastReview()
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                statsRefresh++
                                android.widget.Toast.makeText(
                                    app, "已撤销上次评分", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }) { Text("撤销上次评分") }
            }
        }
        HeatMapCard(summary = stats, goalMinutes = goalMinutes)
        ReviewTabs(selected = tab, onSelect = { tab = it })

        // P1 复习两小招：排序模式持久化（坏值回退 risk）
        var sortMode by remember { mutableStateOf("risk") }
        LaunchedEffect(Unit) { sortMode = app.settingsRepo.readReviewSortMode() }
        val setSortMode: (String) -> Unit = { mode ->
            sortMode = mode
            app.appScope.launch { app.settingsRepo.saveReviewSortMode(mode) }
        }

        // 列表区吃掉头部卡之外的剩余高度：头部堆叠（统计+热力图+页签）再高也压不扁列表，
        // Robolectric 小视口下 LazyColumn 不会落到 0 高不进语义树（2026-11-15 回归根因）
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> DuePane(
                    repo = repo,
                    onStart = { due ->
                        // 会话状态写回切主线程（与项目线程纪律一致）
                        app.appScope.launch {
                            val s = buildSession(repo, due)
                            withContext(Dispatchers.Main) { session = s }
                        }
                    },
                    sortMode = sortMode,
                    onSortModeChange = setSortMode,
                    onContinueRead = onContinueRead,
                )
                1 -> WrongBookPane(repo = repo, onRetest = { rows ->
                    wrongSession = WrongSession(rows.first().bookTitle, rows)
                })
                else -> TermsPane(repo = repo)
            }
        }
    }
}

/** 统计卡数据（体验增量轮）：minutesByDay 一次查出后内存汇总；P1-⑤ byDay 供热力图复用 */
private data class ReadingSummary(
    val todayMinutes: Int,
    val streakDays: Int,
    val totalMinutes: Int,
    val byDay: List<com.studyfriend.app.data.db.DayMinutes> = emptyList(),
)

private const val DEFAULT_GOAL_MINUTES = 30

/** 时长展示：< 60 分钟显示「x 分钟」，满一小时起显示「x.x 小时」（0.1 小时这种读不懂的数不出现） */
private fun formatMinutes(minutes: Int): String = when {
    minutes >= 60 -> "%.1f 小时".format(minutes / 60f)
    else -> "$minutes 分钟"
}

/** 阅读统计卡（2026-11-15 UI 打磨轮 + P1-⑤）：一张卡三格，今日格带每日目标进度环 */
@Composable
private fun ReadingStatsCard(stats: ReadingSummary?, goalMinutes: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            // 今日格：目标进度环 + 时长（达成目标后环满圈）
            Row(
                Modifier.weight(1.2f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgressRing(
                    progress = ((stats?.todayMinutes ?: 0).toFloat() / goalMinutes).coerceIn(0f, 1f),
                    goalMet = (stats?.todayMinutes ?: 0) >= goalMinutes,
                )
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatMinutes(stats?.todayMinutes ?: 0),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "目标 $goalMinutes 分",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatCell(
                label = if (streakMilestoneHit(stats?.streakDays ?: 0) != null)
                    "连续 🎉达成" else "连续",
                value = "${stats?.streakDays ?: 0} 天",
                emphasized = streakMilestoneHit(stats?.streakDays ?: 0) != null,
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = "累计",
                value = formatMinutes(stats?.totalMinutes ?: 0),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 每日目标进度环（P1-⑤）：38dp 圆环，达成后满圈琥珀色 + 中心对勾 */
@Composable
private fun ProgressRing(progress: Float, goalMet: Boolean) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val arcColor = if (goalMet) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(38.dp)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = track,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = stroke),
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
            )
            if (progress > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                )
            }
        }
        if (goalMet) {
            Text("✓", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFFB74D))
        }
    }
}

/** 热力图卡（P1-⑤，koreader/AnkiDroid 格子风）：近 17 周每日阅读分钟，Canvas 自绘格子矩阵。
 *  色阶按「当日分钟 ÷ 每日目标」分四档；点格子显示当日数据；副标题提示距下一里程碑天数。 */
@Composable
private fun HeatMapCard(summary: ReadingSummary?, goalMinutes: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            var selectedInfo by remember { mutableStateOf<String?>(null) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("近 17 周", style = MaterialTheme.typography.titleSmall)
                    val streak = summary?.streakDays ?: 0
                    val next = nextStreakMilestone(streak)
                    Text(
                        selectedInfo
                            ?: next?.let { "连续 $streak 天 · 距 ${it} 天里程碑还差 ${it - streak} 天" }
                            ?: "连续 $streak 天，坚持得漂亮",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 图例：少 → 多
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(0f, 0.35f, 0.65f, 1f).forEach { a ->
                        Box(
                            Modifier
                                .padding(horizontal = 2.dp)
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (a == 0f) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.primary.copy(alpha = a),
                                ),
                        )
                    }
                    Text("多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            val emptyColor = MaterialTheme.colorScheme.surfaceVariant
            val primary = MaterialTheme.colorScheme.primary
            ReadingHeatMap(
                byDay = summary?.byDay.orEmpty(),
                goalMinutes = goalMinutes,
                emptyColor = emptyColor,
                lowColor = primary.copy(alpha = 0.30f),
                midColor = primary.copy(alpha = 0.62f),
                highColor = primary,
                onPickDay = { info -> selectedInfo = info },
            )
        }
    }
}

/** 格子矩阵：列=周（周一顶行），近 17 周含本周；未来日期不画 */
@Composable
private fun ReadingHeatMap(
    byDay: List<com.studyfriend.app.data.db.DayMinutes>,
    goalMinutes: Int,
    emptyColor: Color,
    lowColor: Color,
    midColor: Color,
    highColor: Color,
    onPickDay: (String) -> Unit,
) {
    val cell = 15.dp
    val gap = 3.dp
    val weeks = 17
    val minutesMap = remember(byDay) { byDay.associate { it.day to it.minutes } }
    // 本周周一（中国习惯周一为一周之首），列 0 = 16 周前的周一
    val mondayThisWeek = remember { java.time.LocalDate.now().minusDays((java.time.LocalDate.now().dayOfWeek.value - 1).toLong()) }
    val gridStart = remember { mondayThisWeek.minusWeeks((weeks - 1).toLong()) }
    val density = LocalDensity.current
    val dayOf = { col: Int, row: Int -> gridStart.plusWeeks(col.toLong()).plusDays(row.toLong()) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(cell * 7 + gap * 6),
    ) {
        val cellPx = (cell + gap).toPx()
        val side = cell.toPx()
        for (col in 0 until weeks) {
            for (row in 0..6) {
                val d = dayOf(col, row)
                if (d.isAfter(java.time.LocalDate.now())) continue
                val m = minutesMap[d.toString()] ?: 0
                val color = when {
                    m == 0 -> emptyColor
                    m < goalMinutes / 2 -> lowColor
                    m < goalMinutes -> midColor
                    else -> highColor
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(col * cellPx, row * cellPx),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(side * 0.2f),
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, emphasized: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 三 Tab 胶囊分段（替代老式下划线 TabRow）：选中 primaryContainer 底 */
@Composable
private fun ReviewTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("今日复习", "错题本", "术语").forEachIndexed { i, t ->
            val sel = selected == i
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 评估会话：到期行 + 进页时固化的题目快照（空列表 = 无题退化单问） */
private data class ReviewSession(val due: DueItem, val questions: List<QuizQuestion>)

private suspend fun buildSession(repo: BookRepository, due: DueItem): ReviewSession {
    // 批D：术语翻转卡——单题（问题=术语，答案=首现章的大白话解释），复用章评估流推进
    if (due.item.itemType == com.studyfriend.app.data.db.DbValues.ITEM_TERM) {
        val plain = repo.chapter(due.item.chapterId)?.keyTermsJson
            ?.let { KeyTermsCodec.decode(it).firstOrNull { t -> t.term == due.item.title }?.plain }
        return ReviewSession(
            due,
            listOf(QuizQuestion(q = due.item.title, a = plain ?: "（该术语的解释已随章重读更新）")),
        )
    }
    val asset = repo.asset(due.item.chapterId)
    return ReviewSession(due, QuizCodec.decode(asset?.quizJson))
}

// ---------- Tab 0：今日复习 ----------

@Composable
private fun DuePane(
    repo: BookRepository,
    onStart: (DueItem) -> Unit,
    sortMode: String,
    onSortModeChange: (String) -> Unit,
    onContinueRead: (chapterId: Long) -> Unit = {},
) {
    // now 进页固化：跨天停留不翻转，重进页面重新收集拿新 now
    val now = remember { System.currentTimeMillis() }
    val due by repo.dueListWithBookFlow(now)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // M5 契约防御（计划 §2.4）：同章多行（如重命名后再排期）只展示一条。
    // 批D：术语行不参与去重——同一首现章可挂多个术语，按 item.id 逐行展示
    val visible = remember(due) {
        due.distinctBy { if (it.item.itemType == com.studyfriend.app.data.db.DbValues.ITEM_TERM) "term_${it.item.id}" else it.item.bookId to it.item.chapterId }
    }
    // P1 复习两小招：排序切换——risk=最快要忘优先（默认）/ due=到期序 / random=随机（进页随机一次不跳动）
    val sorted = remember(visible, sortMode) {
        when (sortMode) {
            "due" -> visible.sortedBy { it.item.dueAt }
            "random" -> visible.shuffled(java.util.Random(now))
            else -> {
                val risk = visible.associate { it.item.id to Fsrs.retrievability(it.item, now) }
                visible.sortedWith(compareBy({ risk[it.item.id] ?: 0.0 }, { it.item.dueAt }))
            }
        }
    }
    if (visible.isEmpty()) {
        // 空态续读卡（第八波实景：空态区大片空白，顺势引导回书——复用书架「继续读」语义，
        // 最近在读=lastReadAt 非空的 READY 书，与书架横幅同口径）
        val cont = produceState<Pair<com.studyfriend.app.data.db.BookEntity, com.studyfriend.app.data.db.ChapterEntity>?>(null) {
            val book = repo.shelfFlow().first().firstOrNull {
                it.status == DbValues.BOOK_READY && it.lastReadAt != null && it.lastReadChapterId != null
            }
            if (book != null) {
                repo.chapter(book.lastReadChapterId!!)?.let { ch -> value = book to ch }
            }
        }
        // 空态续读卡（第八波实景：空态区大片空白，顺势引导回书——复用书架「继续读」语义，
        // 最近在读=lastReadAt 非空的 READY 书，与书架横幅同口径）。EmptyState 自身
        // fillMaxSize 居中，卡用 Box 底部对齐浮在下方，不与空态抢高度
        Box(Modifier.fillMaxSize()) {
            EmptyState(
                title = "今天没有要复习的",
                subtitle = "读完一章后，这里会帮你安排复习",
                icon = Icons.Filled.Refresh,
            )
            cont.value?.let { (book, ch) ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onContinueRead(ch.id) },
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            "继续读",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "《${book.title}》· ${ch.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "sort_row") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "排序",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(
                    "risk" to "最快要忘",
                    "due" to "到期序",
                    "random" to "随机",
                ).forEach { (mode, label) ->
                    val sel = sortMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            )
                            .clickable { onSortModeChange(mode) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sel) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(sorted, key = { it.item.id }) { d ->
            val overdueDays = ((now - d.item.dueAt) / DAY_MS).toInt()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStart(d) },
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.item.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (d.item.itemType == com.studyfriend.app.data.db.DbValues.ITEM_TERM) "术语卡 · ${d.bookTitle}" else d.bookTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val overdue = overdueDays > 0
                    Text(
                        if (overdue) "逾期 $overdueDays 天" else "今天到期",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                (if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                    .copy(alpha = 0.10f),
                                RoundedCornerShape(999.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

// ---------- 章内评估流（计划 §2.2） ----------

/**
 * 3D 翻卡（2026-11-22 实景体验轮第九波，AnkiDroid 复习动画 + FlashcardCompose MIT 参考思路）：
 * rotationY 0→180°，过 90° 换面（背面自身再转 180° 校正镜像，读起来是正字）；
 * 答案面延迟 180ms 渐显（翻转前半程不露内容，「答案渐显」的低配实现——纯 alpha 免 blur）。
 * 卡底走阅读主题包卡色（复习页无阅读主题上下文，恒走 MaterialTheme 卡底）。
 */
@Composable
internal fun FlipCard(
    flipped: Boolean,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit,
) {
    val rotation = animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "flipRotation",
    )
    val backAlpha = animateFloatAsState(
        targetValue = if (flipped) 1f else 0f,
        animationSpec = tween(durationMillis = 320, delayMillis = 180),
        label = "flipBackAlpha",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp)
            .clickable(onClickLabel = "翻面看答案") { onFlip() },
    ) {
        Box(
            Modifier.graphicsLayer {
                rotationY = rotation.value
                cameraDistance = 16 * density
            },
            contentAlignment = Alignment.Center,
        ) {
            if (rotation.value <= 90f) {
                front()
            } else {
                // 背面：自身转 180° 校正容器镜像；alpha 渐显防翻面前半程露答案
                Box(Modifier.graphicsLayer { rotationY = 180f; alpha = backAlpha.value }) { back() }
            }
        }
    }
}

@Composable
private fun ReviewSessionPane(
    session: ReviewSession,
    onDone: (Fsrs.FsrsAdvance) -> Unit,
    onCancel: () -> Unit,
) {
    val qs = session.questions
    var idx by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var verdicts by remember { mutableStateOf(listOf<ReviewVerdict>()) }
    // P0-① 按钮预写间隔：进会话时按当前卡模拟四档各一次（fuzz 已关，预写=实际排期）
    val previews = remember(session.due.item) { Fsrs.previewDays(session.due.item, System.currentTimeMillis()) }

    fun selfAssess(v: ReviewVerdict) {
        val next = verdicts + v
        if (qs.isEmpty() || idx + 1 >= qs.size) {
            // 章级聚合一次推进：单按钮场景聚合值即该值；P0-① FSRS 按聚合档评分排出下次间隔
            onDone(Fsrs.review(session.due.item, aggregateVerdict(next), System.currentTimeMillis()))
        } else {
            verdicts = next
            idx += 1
            revealed = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel) { Text("返回列表") }
            if (qs.isNotEmpty()) {
                Text(
                    "第 ${idx + 1}/${qs.size} 题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(session.due.item.title, style = MaterialTheme.typography.titleMedium)

        if (qs.isEmpty()) {
            Text("这一章的内容还记得多少？", style = MaterialTheme.typography.titleLarge)
        } else {
            // 术语/自测卡翻面（第九波实景轮，AnkiDroid 复习动画思路）：问题面→点卡 3D 翻转→
            // 答案面渐显。评分按钮常驻下方（不翻面直接评分也不拦，老行为不变）
            val q = qs[idx]
            FlipCard(
                flipped = revealed,
                onFlip = { revealed = true },
                front = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    ) {
                        Text("问题", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text(q.q, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "点卡片看答案",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                back = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    ) {
                        Text("参考答案", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text(q.a, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        if (q.explain.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                q.explain,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                },
            )
        }
        VerdictButtons(previews, ::selfAssess)
    }
}

/** 四档评分按钮（P0-①）：按钮上预写下次间隔（AnkiDroid 思路——评分前知道后果），记得为主按钮 */
@Composable
private fun VerdictButtons(
    previews: Map<ReviewVerdict, Int>,
    onVerdict: (ReviewVerdict) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VerdictButton("忘了", previews[ReviewVerdict.FORGOT], false, Modifier.weight(1f)) { onVerdict(ReviewVerdict.FORGOT) }
        VerdictButton("模糊", previews[ReviewVerdict.FUZZY], false, Modifier.weight(1f)) { onVerdict(ReviewVerdict.FUZZY) }
        VerdictButton("记得", previews[ReviewVerdict.REMEMBER], true, Modifier.weight(1f)) { onVerdict(ReviewVerdict.REMEMBER) }
        VerdictButton("太简单", previews[ReviewVerdict.EASY], false, Modifier.weight(1f)) { onVerdict(ReviewVerdict.EASY) }
    }
}

@Composable
private fun VerdictButton(
    label: String,
    days: Int?,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                if (days != null) "约 $days 天" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (filled) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    if (filled) {
        Button(onClick = onClick, modifier = modifier) { content() }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { content() }
    }
}

// ---------- Tab 1：错题本（M5 契约 #1 + 学习闭环轮重测） ----------

/** 错题行展示数据：题干从该章资产 quizJson 按 qIndex 回查；资产缺失 missing=true（计划 §2.3 文案） */
private data class WrongRow(
    val bookTitle: String,
    val chapterTitle: String,
    val chapterId: Long,
    val qIndex: Int,
    val question: String,
    val correctAnswer: String,
    val explain: String,
    val myAnswer: String,
    val feedback: String?,
    val missing: Boolean,
)

/** 错题重测会话（学习闭环轮）：整书的可重测错题（missing 行剔除） */
private data class WrongSession(val bookTitle: String, val rows: List<WrongRow>)

private suspend fun loadWrongRows(repo: BookRepository): List<WrongRow> {
    val wrong = latestWrongAttempts(repo.wrongLatest())
    if (wrong.isEmpty()) return emptyList()
    val assetCache = HashMap<Long, List<QuizQuestion>>()
    // 批D：讲解自问（source=NOTE）的题干从该章讲解卡 questionsJson 回查（qIndex-基线=卡内序号）
    val noteQCache = HashMap<Long, List<CheckQuestion>>()
    return wrong.map { w ->
        if (w.source == "NOTE") {
            val qs = noteQCache.getOrPut(w.chapterId) {
                repoNotesOnce(repo, w.chapterId)
            }
            val q = qs.getOrNull(w.qIndex - DbValues.NOTE_Q_BASE)
            WrongRow(
                bookTitle = w.bookTitle,
                chapterTitle = w.chapterTitle,
                chapterId = w.chapterId,
                qIndex = w.qIndex,
                question = q?.q?.takeIf { it.isNotBlank() } ?: "讲解自问（原讲解已更新）",
                correctAnswer = q?.a ?: "",
                explain = "",
                myAnswer = w.answer,
                feedback = w.feedback,
                missing = q == null,
            )
        } else {
            val qs = assetCache.getOrPut(w.chapterId) {
                QuizCodec.decode(repo.asset(w.chapterId)?.quizJson)
            }
            val q = qs.getOrNull(w.qIndex)
            WrongRow(
                bookTitle = w.bookTitle,
                chapterTitle = w.chapterTitle,
                chapterId = w.chapterId,
                qIndex = w.qIndex,
                question = q?.q?.takeIf { it.isNotBlank() } ?: "第 ${w.qIndex + 1} 题",
                correctAnswer = q?.a ?: "",
                explain = q?.explain ?: "",
                myAnswer = w.answer,
                feedback = w.feedback,
                missing = q == null,
            )
        }
    }
}

/** 批D：讲解自问题干回查——该章全部讲解卡的 check_questions 顺序展平（与生成页同序） */
private suspend fun repoNotesOnce(repo: BookRepository, chapterId: Long): List<CheckQuestion> =
    repo.notesOnce(chapterId).flatMap { decodeCheckQuestions(it.questionsJson) }

@Composable
private fun WrongBookPane(repo: BookRepository, onRetest: (List<WrongRow>) -> Unit) {
    // 一次性加载（null = 加载中）：错题本无实时性要求，进页快照即可
    val rows by produceState<List<WrongRow>?>(initialValue = null) {
        value = loadWrongRows(repo)
    }
    when {
        rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        rows!!.isEmpty() -> EmptyState(
            title = "错题本是空的",
            subtitle = "自测答错的题会出现在这里，方便考前翻看",
            icon = Icons.Filled.Warning,
        )
        else -> {
            var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
            LazyColumn(Modifier.fillMaxSize()) {
                rows!!.groupBy { it.bookTitle }.forEach { (book, rs) ->
                    item(key = "header_$book") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, top = 14.dp, bottom = 4.dp, end = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                book,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            // 学习闭环轮：整书错题重测（总结包已更新的 missing 行不可测）
                            val retestable = rs.filter { !it.missing }
                            if (retestable.isNotEmpty()) {
                                TextButton(onClick = { onRetest(retestable) }) {
                                    Text("重测本书错题（${retestable.size}）")
                                }
                            }
                        }
                    }
                    items(rs, key = { "${it.chapterId}_${it.qIndex}" }) { r ->
                        val rowKey = "${r.chapterId}_${r.qIndex}"
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expandedKey = if (expandedKey == rowKey) null else rowKey }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text(r.question, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (r.missing) "${r.chapterTitle} · 总结包已更新，题目内容不可用"
                                else "${r.chapterTitle} · 我的作答：${r.myAnswer}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (expandedKey == rowKey) {
                                Text(
                                    "参考答案：${r.correctAnswer.ifBlank { "（原题已随总结包更新）" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                if (r.explain.isNotBlank()) {
                                    Text(r.explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!r.feedback.isNullOrBlank()) {
                                    Text("当时反馈：${r.feedback}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** 错题重测流（学习闭环轮）：逐题 输入作答 → 对答案 → 三按钮自评落库；答对自动出列 */
@Composable
private fun WrongSessionPane(
    session: WrongSession,
    onAnswer: (row: WrongRow, answer: String, verdict: String) -> Unit,
    onDone: () -> Unit,
) {
    var idx by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var answerText by remember { mutableStateOf("") }
    val rows = session.rows
    val finished = idx >= rows.size

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDone) { Text("退出重测") }
            if (!finished) {
                Text(
                    "第 ${idx + 1}/${rows.size} 题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(session.bookTitle, style = MaterialTheme.typography.titleMedium)

        if (finished) {
            Text("重测完成！", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                "答对的题已自动移出错题本，还在错的题留着下次再练。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("返回错题本") }
        } else {
            val r = rows[idx]
            Text(r.question, style = MaterialTheme.typography.titleLarge)
            Text(
                r.chapterTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!revealed) {
                OutlinedTextField(
                    value = answerText,
                    onValueChange = { answerText = it },
                    placeholder = { Text("先自己作答，再看答案") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) { Text("对答案") }
            } else {
                Text("参考答案：${r.correctAnswer}", style = MaterialTheme.typography.bodyLarge)
                if (r.explain.isNotBlank()) {
                    Text(r.explain, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("对照解析，给自己一个评价：", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onAnswer(r, answerText, "CORRECT")
                        answerText = ""; revealed = false; idx += 1
                    }) { Text("答对") }
                    OutlinedButton(onClick = {
                        onAnswer(r, answerText, "PARTIAL")
                        answerText = ""; revealed = false; idx += 1
                    }) { Text("模糊") }
                    OutlinedButton(onClick = {
                        onAnswer(r, answerText, "WRONG")
                        answerText = ""; revealed = false; idx += 1
                    }) { Text("答错") }
                }
            }
        }
    }
}

// ---------- Tab 2：术语卡盒（学习闭环轮） ----------

@Composable
private fun TermsPane(repo: BookRepository) {
    // 一次性加载：术语无实时性要求，进页快照
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    data class TermsData(
        val rows: List<com.studyfriend.app.data.db.ChapterTermsRow>,
        val groups: List<TermBookGroup>,
    )
    val data by produceState<TermsData?>(initialValue = null) {
        val rows = repo.chaptersWithTerms()
        value = TermsData(rows, buildTermGroups(rows))
    }
    when {
        data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        data!!.groups.isEmpty() -> EmptyState(
            title = "还没有术语卡",
            subtitle = "读完章节、完成粗读后，每章的关键术语和大白话解释会汇集到这里",
            icon = Icons.Filled.Star,
        )
        else -> {
            // 手风琴：一次展开一章，翻看不迷路
            var expandedChapter by rememberSaveable { mutableStateOf<String?>(null) }
            LazyColumn(Modifier.fillMaxSize()) {
                data!!.groups.forEach { g ->
                    item(key = "book_${g.bookTitle}") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, bottom = 4.dp, end = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                g.bookTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            // 批D：全书术语（跨章去重）一键进复习排期——翻转卡走今日复习流
                            val bookRows = data!!.rows.filter { it.bookId == g.bookId }
                            val deduped = dedupeBookTerms(
                                bookRows.map { TermChapter(it.chapterTitle, KeyTermsCodec.decode(it.keyTermsJson)) },
                                bookRows.map { it.chapterId },
                            )
                            if (deduped.isNotEmpty()) {
                                TextButton(onClick = {
                                    val now = System.currentTimeMillis()
                                    scope.launch {
                                        repo.scheduleReviews(
                                            deduped.map { d ->
                                                com.studyfriend.app.data.db.ReviewItemEntity(
                                                    bookId = g.bookId, chapterId = d.chapterId,
                                                    title = d.term, intervalIdx = 0, dueAt = now,
                                                    itemType = com.studyfriend.app.data.db.DbValues.ITEM_TERM,
                                                )
                                            },
                                        )
                                        android.widget.Toast.makeText(
                                            context, "已加入 ${deduped.size} 个术语（重复的自动跳过）",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }) { Text("加入复习（${deduped.size} 词）") }
                            }
                        }
                    }
                    g.chapters.forEach { ch ->
                        // 章唯一键：书名+章名（章节 id 不在分组结构里，重名章用文本区分已够用）
                        val chKey = "${g.bookTitle}_${ch.chapterTitle}"
                        item(key = "ch_$chKey") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedChapter = if (expandedChapter == chKey) null else chKey }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(ch.chapterTitle, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${ch.terms.size} 词",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (expandedChapter == chKey) {
                                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ch.terms.forEach { t ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(
                                                    t.term,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                Text(
                                                    t.plain,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
