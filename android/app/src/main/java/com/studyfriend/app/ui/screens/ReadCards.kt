package com.studyfriend.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.study.KeyTermsCodec
import com.studyfriend.app.data.study.ParaIdsCodec
import com.studyfriend.app.data.study.decodeStringList
import com.studyfriend.app.ui.components.RefAwareText
import kotlin.math.roundToInt


// 从 ReadScreen.kt 拆出（2026-10-11 结构批次）：段落行/划词文本/概要卡/讲解卡的渲染

/** 阅读字体（实景体验轮第七波）：ReadScreen 根部按 read_font_family 提供，
 *  null=跟随系统；霞鹜文楷等自定义字体在这里下发到全部正文段与标题段 */
internal val LocalReadFontFamily = compositionLocalOf<FontFamily?> { null }

/** 正文阅读字号/行距：随「Aa」弹层调节（功能补齐轮起行距可调，默认 1.7 倍）；
 *  阅读体验轮：中文书籍惯例首行缩进两字（indentChars=2，标题/次级段传 0 不缩进）；
 *  第七波：fontFamily 非空时正文走自选字体（霞鹜文楷 Lite） */
private fun bodyTextStyle(fontSize: Int, lineMult: Float, indentChars: Int = 0, fontFamily: FontFamily? = null): TextStyle = TextStyle(
    fontSize = fontSize.sp,
    lineHeight = (fontSize * lineMult).roundToInt().sp,
    fontFamily = fontFamily,
    textIndent = if (indentChars > 0) TextIndent(firstLine = (fontSize * indentChars).sp) else TextIndent.None,
)

/**
 * 段落行（计划 M4a §3 + P7 划词 + 阅读体验轮 2026-11-21 重排）：
 * - 章内节标题（第X节/一、/（一）…）走标题样式：加粗、上留白拉开层级、不缩进、不缩进；
 * - EXPLAIN 段左侧主题色竖条 + why 小字（「★ 讲」「合并讲」胶囊已删——观感噪音）；
 * - SKIP 段与普通段同样渲染（「已跳过」标签与半透明已删，老板拍板影响观感）；
 * - 正文首行缩进两字；正文长按取词随手问。
 */
@Composable
internal fun ParaRow(
    p: ParagraphEntity,
    fontSize: Int,
    lineHeightMult: Float,
    selectedRange: IntRange?,
    ttsActive: Boolean = false,
    hasMemo: Boolean = false,
    onOpenMemo: (() -> Unit)? = null,
    onLongPress: (IntRange, IntOffset) -> Unit,
    onSelectionChange: ((IntRange) -> Unit)? = null,
    onAnchorChange: ((IntOffset) -> Unit)? = null,
    onDragStateChange: ((Boolean) -> Unit)? = null,
    // 翻页模式：onTap 带文本内点击坐标（Text 本地坐标，x 用于分左右翻页区）
    onTap: ((Offset) -> Unit)? = null,
) {
    // OPT-C C4：目录条目特殊展示——小字次级色，无重点竖条/无 AI 徽标（TOC 段也不参与 AI 标注）
    // OPT-E：脚注段同款次级展示；P5-F：FRONT/BACK（前/后置版权页）纳入次级展示，
    // 但仅视觉降级，AI 加工口径不变（ReadStyles.isSecondaryRole 展示与加工解耦）
    if (ReadStyles.isSecondaryRole(p.role)) {
        SelectableText(
            text = p.text,
            style = MaterialTheme.typography.bodySmall,
            // 阅读主题包（第六波）：固定纸色时次级文字用主题次级色（护眼绿上 onSurfaceVariant 灰字发闷）
            color = LocalReadingTheme.current?.secondary ?: MaterialTheme.colorScheme.onSurfaceVariant,
            highlightRange = selectedRange,
            onLongPress = onLongPress,
            onSelectionChange = onSelectionChange,
            onAnchorChange = onAnchorChange,
            onDragStateChange = onDragStateChange,
            onTap = onTap,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    // 章内节标题（第X节/一、/（一）/1.）：标题样式渲染，AI 标注信息（why/竖条）不掺进来
    val headingLevel = ParaHeadings.level(p.text)
    // 法条号段首判级（第八波，《合同编（上）》装机实录）：释义/评注体条文书「第四百六十三条
    // 本编调整……」条号黏在段首与正文同款，条文骨架隐形。只在非标题段判（标题段不可能是
    // 条文段）；正文分支条号主色加粗 + 上留白一档
    val articleRange = if (headingLevel == 0) ParaHeadings.articleNoRange(p.text) else null
    // EXPLAIN 段左侧主题色竖条（M4a §3.4）：IntrinsicSize 让竖条随内容高度；
    // 学习闭环轮：有批注的段右上角小铅笔标记，点开看/删自己的笔记
    val explained = p.aiAction == DbValues.ACT_EXPLAIN && headingLevel == 0
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                // v7 正文听书：当前朗读段淡染底色（跟随高亮）
                .background(
                    if (ttsActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                ),
        ) {
        if (explained) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(
                    start = if (explained) 12.dp else 16.dp,
                    end = if (explained) 12.dp else 16.dp,
                    // 标题段上方大留白拉开层级（下小留白贴正文）；普通段 8dp 上下（段间 16dp，
                    // 原 24dp 偏松——阅读体验轮收紧）；条文段 14dp（第八波，条号导航感）
                    top = when {
                        headingLevel > 0 -> 18.dp
                        articleRange != null -> 14.dp
                        else -> 8.dp
                    },
                    bottom = if (headingLevel > 0) 6.dp else 8.dp,
                ),
        ) {
            if (headingLevel > 0) {
                // 节标题：一级（第X节/篇/回）字号+2 加粗主色；二三级（一、/（一）/1.）加粗原色
                val level1 = headingLevel == 1
                SelectableText(
                    text = p.text,
                    style = bodyTextStyle(
                        if (level1) fontSize + 2 else fontSize,
                        lineHeightMult,
                        indentChars = 0,
                        fontFamily = LocalReadFontFamily.current,
                    ),
                    color = if (level1) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    fontWeight = FontWeight.SemiBold,
                    highlightRange = selectedRange,
                    onLongPress = onLongPress,
                    onSelectionChange = onSelectionChange,
                    onAnchorChange = onAnchorChange,
                    onDragStateChange = onDragStateChange,
                    onTap = onTap,
                )
            } else {
                // EXPLAIN/GROUP 段：why 收成一行主色小字（原「★ 讲/合并讲」胶囊 + 「为什么讲：」
                // 两行噪声已删）；SKIP 与普通段同款渲染（「已跳过」标签与半透明已删）
                if ((p.aiAction == DbValues.ACT_EXPLAIN || p.aiAction == DbValues.ACT_GROUP) &&
                    !p.why.isNullOrBlank()
                ) {
                    Text(
                        "◆ ${p.why}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                SelectableText(
                    text = p.text,
                    style = bodyTextStyle(fontSize, lineHeightMult, indentChars = 2, fontFamily = LocalReadFontFamily.current),
                    highlightRange = selectedRange,
                    emphasisPrefix = articleRange,
                    blockMarks = true,
                    onLongPress = onLongPress,
                    onSelectionChange = onSelectionChange,
                    onAnchorChange = onAnchorChange,
                    onDragStateChange = onDragStateChange,
                    onTap = onTap,
                )
            }
        }
        }
        if (hasMemo) {
            IconButton(
                onClick = { onOpenMemo?.invoke() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 4.dp)
                    .size(32.dp),
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "查看批注",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 可长按的正文 Text（P7 + 划词扩选轮）：长按取词组并默认扩到整句（所见即所选），
 * 回报句区间 + 光标矩形的 window 坐标；被选中段渲染底色高亮（primaryContainer）+
 * 两端拖拽把手（SelectionHandle）自由调整范围。
 * detectTapGestures 挂上即消费 tap，单击切换沉浸栏必须经 onTap 参数从这里透传（v7），
 * 外层再挂 detectTapGestures 收不到事件。
 */
@Composable
private fun SelectableText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    highlightRange: IntRange?,
    // 法条号段首强调（第八波）：区间内主色加粗（条文骨架，释义/评注体条文书实录）
    emphasisPrefix: IntRange? = null,
    // 【区块标记】主色加粗（第八波）：段内「【条文释义】【案例评注】」骨架与正文区分
    blockMarks: Boolean = false,
    onLongPress: (IntRange, IntOffset) -> Unit,
    onSelectionChange: ((IntRange) -> Unit)? = null,
    onAnchorChange: ((IntOffset) -> Unit)? = null,
    onDragStateChange: ((Boolean) -> Unit)? = null,
    onTap: ((Offset) -> Unit)? = null,
) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val windowBounds = remember { mutableStateOf<Rect?>(null) }
    var handleDragging by remember { mutableStateOf(false) }
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val halfPx = with(LocalDensity.current) { 24.dp.toPx() / 2 }
    val hitRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }
    // 拖动中 highlightRange/回调随重组变化，泵闭包（Unit key 不重启）经 rememberUpdatedState 取最新
    val curRange by rememberUpdatedState(highlightRange)
    val curOnSelection by rememberUpdatedState(onSelectionChange)
    val curOnDragState by rememberUpdatedState(onDragStateChange)

    fun handleCenter(range: IntRange, isStart: Boolean): Offset? {
        val l = layout.value ?: return null
        val cursor = if (isStart) l.getCursorRect(range.first)
        else l.getCursorRect((range.last + 1).coerceAtMost(text.length))
        val x = if (isStart) cursor.left - halfPx * 0.7f else cursor.right + halfPx * 0.7f
        return Offset(x, cursor.center.y)
    }
    val primary = MaterialTheme.colorScheme.primary
    val annotated = remember(text, highlightRange, highlight, primary, emphasisPrefix, blockMarks) {
        if ((highlightRange == null || highlightRange.isEmpty()) && emphasisPrefix == null && !blockMarks) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                // 法条号段首强调（第八波）：条号部分主色加粗，条文段一眼可辨
                emphasisPrefix?.takeIf { !it.isEmpty() }?.let { r ->
                    addStyle(
                        SpanStyle(color = primary, fontWeight = FontWeight.SemiBold),
                        r.first,
                        (r.last + 1).coerceAtMost(text.length),
                    )
                }
                if (blockMarks) {
                    ParaHeadings.findBlockMarks(text).forEach { r ->
                        addStyle(SpanStyle(color = primary, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
                    }
                }
                highlightRange?.takeIf { !it.isEmpty() }?.let { r ->
                    addStyle(
                        SpanStyle(background = highlight),
                        r.first,
                        (r.last + 1).coerceAtMost(text.length),
                    )
                }
            }
        }
    }

    fun anchorOf(range: IntRange): IntOffset? {
        val l = layout.value ?: return null
        val global = windowBounds.value?.let { it.topLeft + l.getCursorRect(range.first).centerLeft }
            ?: return null
        return IntOffset(global.x.roundToInt(), global.y.roundToInt())
    }

    // 选区区间变化（扩句/扩段/拖把手）后校准浮条锚点，浮条跟随新选区
    LaunchedEffect(highlightRange) {
        if (highlightRange != null && !highlightRange.isEmpty()) {
            anchorOf(highlightRange)?.let { onAnchorChange?.invoke(it) }
        }
    }

    Box(
        modifier.pointerInput(Unit) {
            // 拖动泵挂 Text 外层 + Initial pass：父节点须抢在子节点 Text 的 detectTapGestures
            // 之前消费（Main pass 子先父后，纯点按 up 未消费会被 detect 判成 tap 误切沉浸——实测踩坑）。
            // Press 时按「距把手中心 ≤ 把手半径」判命中；down/up 全消费：点把手不透传，
            // 拖动 move 消费后 LazyColumn 不滚动。
            awaitPointerEventScope {
                while (true) {
                    val first = awaitPointerEvent(PointerEventPass.Initial)
                    if (first.type != PointerEventType.Press) continue
                    val range = curRange ?: continue
                    val press = first.changes[0].position
                    val hitStart = handleCenter(range, isStart = true)
                        ?.let { (press - it).getDistance() <= hitRadiusPx } == true
                    val hitEnd = handleCenter(range, isStart = false)
                        ?.let { (press - it).getDistance() <= hitRadiusPx } == true
                    if (!hitStart && !hitEnd) continue
                    first.changes.forEach { it.consume() }
                    handleDragging = true
                    curOnDragState?.invoke(true) // 拖动中藏浮条，松手随新锚点重现
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull() ?: break
                        when (ev.type) {
                            PointerEventType.Move -> {
                                c.consume()
                                val l = layout.value ?: continue
                                val r = curRange ?: continue
                                val off = l.getOffsetForPosition(c.position)
                                    .coerceIn(if (hitStart) 0 else r.first, if (hitStart) r.last else text.length - 1)
                                curOnSelection?.invoke(if (hitStart) off..r.last else r.first..off)
                            }
                            PointerEventType.Release -> {
                                c.consume()
                                break
                            }
                            PointerEventType.Exit -> break
                            else -> {}
                        }
                    }
                    handleDragging = false
                    curOnDragState?.invoke(false)
                }
            }
        },
    ) {
        Text(
            text = annotated,
            style = style,
            color = color,
            fontWeight = fontWeight,
            onTextLayout = { layout.value = it },
            modifier = Modifier
                .onGloballyPositioned { windowBounds.value = it.boundsInWindow() }
                .pointerInput(text) {
                    detectTapGestures(
                        // 翻页模式：坐标透传给调用方分左右翻页区；滚动模式调用方忽略坐标
                        onTap = { pos -> onTap?.invoke(pos) },
                        onLongPress = { pos ->
                            val l = layout.value ?: return@detectTapGestures
                            // 长按默认选整句：词组定位 → 扩句；查词场景拖首把手收缩
                            val phrase = SelectionBounds.phraseAt(text, l.getOffsetForPosition(pos))
                            if (phrase.isEmpty()) return@detectTapGestures
                            val sentence = SelectionBounds.expandToSentence(text, phrase.first, phrase.last)
                            onLongPress(sentence, anchorOf(sentence) ?: IntOffset.Zero)
                        },
                    )
                },
        )
        // 拖拽把手：选区两端各一颗（纯视觉）；拖动手势在 Box 外层事件泵统一处理
        val l = layout.value
        if (highlightRange != null && !highlightRange.isEmpty() && l != null) {
            SelectionHandle(
                cursor = l.getCursorRect(highlightRange.first),
                isStart = true,
                dragging = handleDragging,
            )
            SelectionHandle(
                cursor = l.getCursorRect((highlightRange.last + 1).coerceAtMost(text.length)),
                isStart = false,
                dragging = handleDragging,
            )
        }
    }
}

/**
 * 选区拖拽把手（划词扩选轮）：24dp 圆点悬在选区起/终点旁，纯视觉展示；
 * 拖动手势由 SelectableText 外层事件泵统一处理（按 Press 位置距把手中心判定命中）。
 * 不在把手上挂 pointerInput——子 Box 节点收不到 Press 后续事件（模拟器实测）。
 */
@Composable
private fun SelectionHandle(
    cursor: Rect,
    isStart: Boolean,
    dragging: Boolean,
) {
    val handleSize = 24.dp
    val half = with(LocalDensity.current) { handleSize.toPx() / 2 }
    // 首把手悬在选区起点左侧、尾把手悬在终点右侧，垂直居中于所在行
    val cx = if (isStart) cursor.left - half * 0.7f else cursor.right + half * 0.7f
    val cy = cursor.center.y
    Box(
        Modifier
            .offset { IntOffset((cx - half).roundToInt(), (cy - half).roundToInt()) }
            .size(handleSize)
            .background(
                if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                CircleShape,
            )
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
    )
}

/** 章尾「下一章」卡片（阅读体验轮）：读完整章不用回到顶栏找箭头，点卡片直接进下一章 */
@Composable
internal fun NextChapterCard(title: String, onClick: () -> Unit) {
    Surface(
        // 阅读主题包（第六波）：固定纸色时卡片走主题卡底色（墨玉/纯黑下 surfaceContainer 灰块突兀）
        color = LocalReadingTheme.current?.cardBg ?: MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("下一章", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入下一章",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 本章要点卡（P7 移入段落流可折叠）：gist + key_terms chips（M4a §3.4 视觉规格） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GistCard(gist: String, keyTermsJson: String?) {
    Surface(
        color = LocalReadingTheme.current?.cardBg ?: MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(gist, style = MaterialTheme.typography.bodyMedium)
            val terms = KeyTermsCodec.decode(keyTermsJson)
            if (terms.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    terms.forEach { t ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                t.term,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 讲解卡（计划 M4b §3.2 + 学习闭环轮）：标题+大白话+类比+要点+记忆钩子；生成中显示占位；
 *  底部「换个讲法」由调用方接弹层（临时重讲不落库，见 ReNoteSheet）；
 *  P1-③ 引用溯源：onJumpPara 非空时 friendly 里的〔p编号〕渲染成胶囊，点击跳对应段（回调段 id） */
@Composable
internal fun NoteCard(
    note: ParaNoteEntity?,
    generating: Boolean,
    onReNote: ((ParaNoteEntity) -> Unit)? = null,
    onJumpPara: ((Long) -> Unit)? = null,
) {
    when {
        note != null -> Surface(
            color = LocalReadingTheme.current?.cardBg ?: MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(note.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (onJumpPara != null) {
                    // paraIds 与喂 prompt 的段落序号一一对应（textsForPrompt 按 members 顺序产出，
                    // 截断只截尾）——标记编号 i 直接映射 paraIds[i]；越界（旧数据防御）不跳
                    val paraIds = remember(note.paraIds) { ParaIdsCodec.decode(note.paraIds) }
                    RefAwareText(
                        note.friendly,
                        MaterialTheme.typography.bodyMedium,
                        onParaRef = { idx -> paraIds.getOrNull(idx)?.let(onJumpPara) },
                    )
                } else {
                    Text(note.friendly, style = MaterialTheme.typography.bodyMedium)
                }
                note.analogy?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "打个比方：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val points = decodeStringList(note.keyPointsJson)
                if (points.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    points.forEach { p ->
                        Text("• $p", style = MaterialTheme.typography.bodySmall)
                    }
                }
                note.memoryHook?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "记忆钩子：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (onReNote != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onReNote(note) }) { Text("没看懂？换个讲法") }
                    }
                }
            }
        }

        generating -> Text(
            "搭子正在讲这一段…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        else -> {}
    }
}
