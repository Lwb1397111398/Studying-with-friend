package com.studyfriend.spike.pagerdemo

import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Spike② 分页手感对比 demo：三模式同一份 400 段长文，翻页策略不同。
 *
 *  A SMOOTH     现款复刻：animateScrollBy(±视口×0.88, tween 340ms)——落点为任意像素
 *  B ESTIMATED  视口估算分页：按段高估算累计 ≥0.88 视口，落点 animateScrollToItem 锚段首
 *  C WARMED     B + 预热：落点前先无动画 scrollToItem(target-2) 合成一帧再动画滚过去
 *
 * 「自动翻 20 页」按钮：连续翻下页 20 次，Choreographer 逐帧统计，logcat tag=PagerDemo
 * 输出汇总（总耗时/帧数/p95/jank），UI 卡片同步显示。数据可复现：种子固定 + 自动化路径一致。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PagerDemoApp() }
    }
}

enum class Mode(val label: String) {
    SMOOTH("A 现款滚屏"),
    ESTIMATED("B 估算分页"),
    WARMED("C 估算+预热"),
}

/** 帧统计：翻页期间逐帧记录间隔，结束算汇总 */
class FrameStats {
    private val deltas = mutableListOf<Long>()
    private var lastNanos = 0L
    private var running = false
    private val cb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastNanos > 0) deltas.add((frameTimeNanos - lastNanos) / 1_000_000)
            lastNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        deltas.clear()
        lastNanos = 0L
        running = true
        Choreographer.getInstance().postFrameCallback(cb)
    }

    fun stop(): Summary? {
        if (!running) return null
        running = false
        Choreographer.getInstance().removeFrameCallback(cb)
        if (deltas.isEmpty()) return null
        val sorted = deltas.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
        return Summary(
            frames = deltas.size,
            p50 = pct(0.5),
            p95 = pct(0.95),
            max = sorted.last(),
            jank32 = deltas.count { it > 32 },
            jank50 = deltas.count { it > 50 },
            totalMs = deltas.sum(),
        )
    }

    data class Summary(
        val frames: Int, val p50: Long, val p95: Long, val max: Long,
        val jank32: Int, val jank50: Int, val totalMs: Long,
    ) {
        fun text() = "帧数%d p50=%dms p95=%dms max=%dms jank>32=%d jank>50=%d 动画帧总耗时%dms"
            .format(frames, p50, p95, max, jank32, jank50, totalMs)
    }
}

@Composable
fun PagerDemoApp() {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            val paras = remember { FakeBook.paragraphs() }
            var mode by rememberSaveable { mutableStateOf(Mode.SMOOTH) }
            var autoSummary by remember { mutableStateOf<String?>(null) }
            var autoRunning by remember { mutableStateOf(false) }
            var firstIdx by remember { mutableIntStateOf(0) }
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val stats = remember { FrameStats() }

            // 视口尺寸（正文 LazyColumn 实测高/宽，px）
            var viewportH by remember { mutableStateOf(0f) }
            var viewportW by remember { mutableStateOf(0f) }

            // 段高估算模型：与产品正文字号 18sp/行距 1.7/段间 12dp 一致
            val fontSizePx = with(density) { 18.sp.toPx() }
            val lineH = fontSizePx * 1.7f
            val gapPx = with(density) { 12.dp.toPx() }
            val charsPerLine = (viewportW / fontSizePx).coerceAtLeast(1f)

            fun estHeight(i: Int): Float =
                ceil(paras[i].length / charsPerLine).coerceAtLeast(1f) * lineH + gapPx

            /** 视口估算：从 fromIdx 起累计段高 ≥0.88 视口的第一个放不下的段 = 新页首 */
            fun pageTarget(fromIdx: Int, forward: Boolean): Int {
                val budget = viewportH * 0.88f
                if (forward) {
                    var acc = 0f
                    var i = fromIdx
                    while (i < paras.size && acc < budget) {
                        acc += estHeight(i)
                        i++
                    }
                    return i.coerceAtMost(paras.size - 1)
                } else {
                    var acc = 0f
                    var i = (fromIdx - 1).coerceAtLeast(0)
                    while (i > 0 && acc + estHeight(i) < budget) {
                        acc += estHeight(i)
                        i--
                    }
                    return i.coerceAtLeast(0)
                }
            }

            suspend fun awaitIdle() {
                snapshotFlow { listState.isScrollInProgress }.first { !it }
            }

            fun turnPage(forward: Boolean) {
                if (viewportH <= 0f) return
                scope.launch {
                    when (mode) {
                        Mode.SMOOTH -> {
                            // 现款复刻：ReadScreen 同款 animateScrollBy(±0.88 视口)
                            listState.animateScrollBy(
                                (if (forward) 1 else -1) * viewportH * 0.88f,
                                tween(340, easing = FastOutSlowInEasing),
                            )
                        }
                        Mode.ESTIMATED -> {
                            val target = pageTarget(listState.firstVisibleItemIndex, forward)
                            // 修复编译错误：animateScrollToItem(index, scrollOffset) 不收 AnimationSpec，用其内置默认动画
                            listState.animateScrollToItem(target)
                        }
                        Mode.WARMED -> {
                            // 预热：先无动画跳到落点前 2 段让 LazyColumn 合成，等一帧再动画滚到落点
                            val target = pageTarget(listState.firstVisibleItemIndex, forward)
                            listState.scrollToItem((target - 2).coerceAtLeast(0))
                            withFrameNanos { }
                            // 修复编译错误：同上，用 animateScrollToItem 内置默认动画
                            listState.animateScrollToItem(target)
                        }
                    }
                    firstIdx = listState.firstVisibleItemIndex
                }
            }

            fun autoTurn20() {
                if (autoRunning || viewportH <= 0f) return
                autoRunning = true
                autoSummary = null
                scope.launch {
                    // 从头开始保证可比：先瞬间回到顶部
                    listState.scrollToItem(0)
                    delay(300)
                    stats.start()
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    repeat(20) {
                        turnPage(true)
                        awaitIdle()
                        delay(120)
                    }
                    val wallMs = android.os.SystemClock.elapsedRealtime() - t0
                    val s = stats.stop()
                    val line = "自动翻20页[${mode.name}] 墙钟${wallMs}ms " + (s?.text() ?: "无帧数据")
                    Log.i("PagerDemo", line)
                    autoSummary = line
                    autoRunning = false
                }
            }

            Column(Modifier.fillMaxSize()) {
                // ── 控制条 ──
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Mode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { if (!autoRunning) mode = m },
                            label = { Text(m.label, fontSize = 12.sp) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(onClick = { autoTurn20() }, enabled = !autoRunning) {
                        Text("自动翻20页（下）", fontSize = 13.sp)
                    }
                    Text(
                        "当前首段: ${firstIdx + 1}",
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                        fontSize = 13.sp,
                    )
                }
                autoSummary?.let {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        color = Color(0xFF1B5E20),
                    )
                }
                Text(
                    "操作：点右1/3下页·左1/3上页·左滑下页（与产品同手势）",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    fontSize = 11.sp,
                    color = Color.Gray,
                )

                // ── 正文 ──
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged {
                            viewportH = it.height.toFloat()
                            viewportW = it.width.toFloat()
                        }
                        .pointerInput(mode) {
                            detectTapGestures { offset ->
                                if (autoRunning) return@detectTapGestures
                                when {
                                    offset.x < size.width / 3f -> turnPage(false)
                                    offset.x > size.width * 2f / 3f -> turnPage(true)
                                    // 中间 1/3 不动作（产品里是沉浸切换，demo 不做）
                                }
                            }
                        }
                        .pointerInput(mode) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onDragEnd = {
                                    if (!autoRunning && abs(totalDrag) > size.width / 6f) {
                                        turnPage(forward = totalDrag < 0)
                                    }
                                },
                            ) { change, _ ->
                                totalDrag += change.positionChange().x
                            }
                        },
                ) {
                    items(paras.size) { i ->
                        Text(
                            paras[i],
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            fontSize = 18.sp,
                            lineHeight = (18 * 1.7).sp,
                            style = TextStyle(color = Color(0xFF222222)),
                        )
                    }
                }
            }
        }
    }
}
