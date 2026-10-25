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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.BookRepository
import com.studyfriend.app.data.db.DueItem
import com.studyfriend.app.data.db.QuizAttemptEntity
import com.studyfriend.app.data.db.computeStreak
import com.studyfriend.app.data.study.DAY_MS
import com.studyfriend.app.data.study.QuizCodec
import com.studyfriend.app.data.study.QuizQuestion
import com.studyfriend.app.data.study.ReviewAdvance
import com.studyfriend.app.data.study.ReviewVerdict
import com.studyfriend.app.data.study.TermBookGroup
import com.studyfriend.app.data.study.advanceReview
import com.studyfriend.app.data.study.aggregateVerdict
import com.studyfriend.app.data.study.buildTermGroups
import com.studyfriend.app.data.study.latestWrongAttempts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 复习中心（M6 计划 §3.1 + 学习闭环轮）：Tab0 今日复习（到期列表 → 章内评估流）
 * + Tab1 错题本（去重按书分组，可整书重测）+ Tab2 术语卡盒（全书 keyTerms 按书翻看）。
 * 保持无参签名：底栏 Tab 页，自行取 StudyApp 依赖。
 */
@Composable
fun ReviewScreen() {
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
                // 落库挂 appScope：自评完成瞬间切 Tab/退页也不丢这次推进
                app.appScope.launch {
                    repo.markReviewResult(s.due.item.id, adv.intervalIdx, adv.dueAt, adv.done)
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
        // 体验增量轮：阅读统计卡（今日/连续/累计）——进页查一次（阅读页按分钟入账）
        val today = remember { java.time.LocalDate.now().toString() }
        val stats by produceState<ReadingSummary?>(initialValue = null) {
            val byDay = app.database.readingStatDao().minutesByDay()
            value = ReadingSummary(
                todayMinutes = byDay.firstOrNull { it.day == today }?.minutes ?: 0,
                streakDays = computeStreak(byDay.map { it.day }.toSet(), java.time.LocalDate.now()),
                totalMinutes = byDay.sumOf { it.minutes },
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatCell(label = "今日", value = "${stats?.todayMinutes ?: 0} 分钟")
            StatCell(label = "连续", value = "${stats?.streakDays ?: 0} 天")
            StatCell(label = "累计", value = "%.1f 小时".format((stats?.totalMinutes ?: 0) / 60f))
        }
        HorizontalDivider()
        TabRow(selectedTabIndex = tab) {
            listOf("今日复习", "错题本", "术语").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> DuePane(repo = repo, onStart = { due ->
                // 会话状态写回切主线程（与项目线程纪律一致）
                app.appScope.launch {
                    val s = buildSession(repo, due)
                    withContext(Dispatchers.Main) { session = s }
                }
            })
            1 -> WrongBookPane(repo = repo, onRetest = { rows ->
                wrongSession = WrongSession(rows.first().bookTitle, rows)
            })
            else -> TermsPane(repo = repo)
        }
    }
}

/** 统计卡数据（体验增量轮）：minutesByDay 一次查出后内存汇总 */
private data class ReadingSummary(
    val todayMinutes: Int,
    val streakDays: Int,
    val totalMinutes: Int,
)

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 评估会话：到期行 + 进页时固化的题目快照（空列表 = 无题退化单问） */
private data class ReviewSession(val due: DueItem, val questions: List<QuizQuestion>)

private suspend fun buildSession(repo: BookRepository, due: DueItem): ReviewSession {
    val asset = repo.asset(due.item.chapterId)
    return ReviewSession(due, QuizCodec.decode(asset?.quizJson))
}

// ---------- Tab 0：今日复习 ----------

@Composable
private fun DuePane(repo: BookRepository, onStart: (DueItem) -> Unit) {
    // now 进页固化：跨天停留不翻转，重进页面重新收集拿新 now
    val now = remember { System.currentTimeMillis() }
    val due by repo.dueListWithBookFlow(now)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // M5 契约防御（计划 §2.4）：同章多行（如重命名后再排期）只展示一条
    val visible = remember(due) { due.distinctBy { it.item.bookId to it.item.chapterId } }

    if (visible.isEmpty()) {
        EmptyState(title = "今天没有要复习的", subtitle = "读完一章后，这里会帮你安排复习")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(visible, key = { it.item.id }) { d ->
            val overdueDays = ((now - d.item.dueAt) / DAY_MS).toInt()
            ListItem(
                headlineContent = { Text(d.item.title, fontWeight = FontWeight.Medium) },
                supportingContent = { Text(d.bookTitle) },
                trailingContent = {
                    Text(
                        if (overdueDays <= 0) "今天到期" else "逾期 $overdueDays 天",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier
                    .clickable { onStart(d) }
                    .padding(horizontal = 4.dp),
            )
            HorizontalDivider()
        }
    }
}

// ---------- 章内评估流（计划 §2.2） ----------

@Composable
private fun ReviewSessionPane(
    session: ReviewSession,
    onDone: (ReviewAdvance) -> Unit,
    onCancel: () -> Unit,
) {
    val qs = session.questions
    var idx by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var verdicts by remember { mutableStateOf(listOf<ReviewVerdict>()) }

    fun selfAssess(v: ReviewVerdict) {
        val next = verdicts + v
        if (qs.isEmpty() || idx + 1 >= qs.size) {
            // 章级聚合一次推进：单按钮场景聚合值即该值
            onDone(advanceReview(session.due.item.intervalIdx, aggregateVerdict(next), System.currentTimeMillis()))
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
            val q = qs[idx]
            Text(q.q, style = MaterialTheme.typography.titleLarge)
            if (revealed) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("参考答案：${q.a}", style = MaterialTheme.typography.bodyLarge)
                    if (q.explain.isNotBlank()) {
                        Text(q.explain, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) { Text("看答案") }
            }
        }
        VerdictButtons(::selfAssess)
    }
}

@Composable
private fun VerdictButtons(onVerdict: (ReviewVerdict) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onVerdict(ReviewVerdict.REMEMBER) }, modifier = Modifier.weight(1f)) { Text("记得") }
        OutlinedButton(onClick = { onVerdict(ReviewVerdict.FUZZY) }, modifier = Modifier.weight(1f)) { Text("模糊") }
        OutlinedButton(onClick = { onVerdict(ReviewVerdict.FORGOT) }, modifier = Modifier.weight(1f)) { Text("忘了") }
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
    return wrong.map { w ->
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

@Composable
private fun WrongBookPane(repo: BookRepository, onRetest: (List<WrongRow>) -> Unit) {
    // 一次性加载（null = 加载中）：错题本无实时性要求，进页快照即可
    val rows by produceState<List<WrongRow>?>(initialValue = null) {
        value = loadWrongRows(repo)
    }
    when {
        rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        rows!!.isEmpty() -> EmptyState(title = "错题本是空的", subtitle = "自测答错的题会出现在这里，方便考前翻看")
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
    val groups by produceState<List<TermBookGroup>?>(initialValue = null) {
        value = buildTermGroups(repo.chaptersWithTerms())
    }
    when {
        groups == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        groups!!.isEmpty() -> EmptyState(
            title = "还没有术语卡",
            subtitle = "读完章节、完成粗读后，每章的关键术语和大白话解释会汇集到这里",
        )
        else -> {
            // 手风琴：一次展开一章，翻看不迷路
            var expandedChapter by rememberSaveable { mutableStateOf<String?>(null) }
            LazyColumn(Modifier.fillMaxSize()) {
                groups!!.forEach { g ->
                    item(key = "book_${g.bookTitle}") {
                        Text(
                            g.bookTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                        )
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
