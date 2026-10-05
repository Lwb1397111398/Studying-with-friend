package com.studyfriend.app.data.importer.pdfpipeline

import kotlin.math.max

/**
 * 行→段落组装（OPT-E，OPT-G P2 增强）。输入是清洗后的正文行（页眉/页码/脚注已摘除），
 * 按"字号、行距、缩进、句末标点"四类信号决定断段还是续接：
 * 1. 段首禁则标点：无条件并回（段落被误切的强信号）
 * 2. 标题行（字号 ≥ 正文×1.15）：独立成段；连续大字行且垂直间距紧 → 标题自身折行合并
 * 3. 标题后的正文：先把标题 flush 隔离
 * 4. y 回跳（dy < −1.5×字号）：新栏/页内结构变化
 * 4.5 深缩进标签块（≈2字缩进的短行）：列表/标签项独立成段（置于满行必接之前防被续接臂吞掉）
 * 4.75 满行必接（x1 ≥ right−0.5×字号）：中文段落末行几乎必然不满 → 满行后未完，
 *      无论句末与否续接；修复"满行+句末+顶格续行"被行距抖动/短行臂误断
 * 4.875 近满行接（x1 ≥ right−1.0×字号 且句末 且本行顶格）：段中句号续接
 * 5. 行距超过 pitchThreshold：空隙即分段（有行距信号时最可靠）
 * 6. 首行缩进（x0 ≥ 左边距+0.8×字号）且上一行句末
 * 7. 短行（x1 < 右边距−1.5×字号）且上一行句末
 * 8. 无行距信号（pitchThreshold==null）时中宽行句末即断（兜底臂；满行/近满已被 4.75/4.875 接走）
 * 9. 默认续接
 * 满行必接/近满行接受强新段短路守卫约束：上一行句末且本行以序号（一、/1./（一）/①）起头
 * → 两臂同时让位，落入常规断段链；两臂均要求本行顶格（缩进=新段意图，优先断）。
 */
object ParagraphAssembler {

    private const val TITLE_FACTOR = 1.15f
    private const val TITLE_FOLD_FACTOR = 2.2f
    private const val Y_JUMP_FACTOR = 1.5f
    private const val INDENT_FACTOR = 0.8f
    private const val SHORT_LINE_FACTOR = 1.5f
    private const val FULL_LINE_FACTOR = 0.5f
    private const val NEAR_FULL_FACTOR = 1.0f
    private const val DEEP_INDENT_FACTOR = 1.6f

    /** 满行必接回退开关：P2 验收不达标时置 false 单独禁用该臂，近满行接/深缩进独立保留 */
    @JvmField
    internal var fullLineJoinEnabled = true

    /**
     * 深缩进标签块开关，默认关闭。真书（王泽鉴《民法总则》重排版，630 页）实测：
     * 该书排版恶劣（部分页双栏、页边有行号栏、正文满行仅 32%），约 10% 行落入
     * "深缩进+短行"区间，此臂净增 ~1500 个碎片段（6661→8137 段，段长中位 37→29 字）。
     * 待 P3b 有字号证据（列表项真实字号差异）后再评估启用。
     */
    @JvmField
    internal var deepIndentBlockEnabled = false

    private fun MutableMap<String, Int>?.hit(key: String) {
        this?.let { it[key] = (it[key] ?: 0) + 1 }
    }

    /** 段首禁则标点：这些字符起头的行必须并回上一段 */
    private val LEADING_NO_BREAK = setOf('，', '。', '、', '；', '：', '）', '」', '』', '”', '！', '？', '…')

    /** 强新段起头：序号形态（一、/1./（一）/(2)/①）——上一行句末+本行序号起头 = 明确新段信号 */
    private val RE_STRONG_NEW_SEG = Regex("^[（(]?([一二三四五六七八九十]+|\\d{1,3})[、.．)）]|^[\\u2460-\\u2473]")

    /**
     * [hitStats] 规则命中计数器（可观测性）：非 null 时累计各臂命中次数（跨页累计，
     * 由调用方持有生命周期），供 E2E 验收归因（如满行必接在低满行率排版下命中率稀少）。
     * 以参数注入而非静态字段——并发 clean() 各自持 map，无交叉污染。
     * 生产路径传 null 零开销。
     */
    fun assemble(
        lines: List<PLine>,
        stats: DocStats,
        hitStats: MutableMap<String, Int>? = null,
    ): List<Para> {
        val paras = mutableListOf<Para>()
        val cur = StringBuilder()
        var prev: PLine? = null
        // 段内最大字号（P3a 字号证据）：初值 0f 仅作下界；取 max 而非首行——
        // 正常路径（字号跳变断段生效）与 min 无差别，极端误接路径下保留标题信号。
        // curMax 只在行文本实际进入 cur 时更新（takeIn）：断段臂先 flush 再 takeIn，
        // 顺序反了会把断段行的字号混进前一段（单测 p3a_titleFollowedByBody 锁定）
        var curMax = 0f
        // 段首行 y0（P4 图锚定数据源）：段首行进入 cur 时记录，flush 后重置
        var curY0 = -1f
        // 段内行来源版本最大值（P6b）：0=全数字、1=含 OCR 行，flush 写入 Para，
        // assembleText 据此选打标阈值（OCR 段放宽、数字段零改动）
        var curSrcVer = 0

        fun flush() {
            val t = cur.toString().trim()
            if (t.isNotEmpty()) paras.add(Para(t, size = curMax, y0 = curY0, sourceVersion = curSrcVer))
            cur.setLength(0)
            curMax = 0f
            curY0 = -1f
            curSrcVer = 0
        }

        fun takeIn(line: PLine) {
            if (cur.isEmpty()) curY0 = line.y0
            curMax = max(curMax, line.size)
            curSrcVer = maxOf(curSrcVer, line.sourceVersion)
            cur.append(line.text)
        }

        fun appendJoined(line: PLine) {
            if (cur.isEmpty()) {
                takeIn(line)
                return
            }
            val first = line.text.firstOrNull()
            val joiner = if (first != null && isCjkLike(cur.last()) && isCjkLike(first)) "" else " "
            cur.append(joiner).append(line.text)
            curMax = max(curMax, line.size)
            curSrcVer = maxOf(curSrcVer, line.sourceVersion)
        }

        for (line in lines) {
            val p = prev
            if (p == null) {
                takeIn(line)
                prev = line
                continue
            }
            val dy = if (p.y0 >= 0f && line.y0 >= 0f) line.y0 - p.y0 else Float.NaN
            val first = line.text.firstOrNull()
            val isTitle = line.size >= stats.bodySize * TITLE_FACTOR
            val prevTitle = p.size >= stats.bodySize * TITLE_FACTOR
            val strongNewSeg = endsSentence(p.text) && first != null &&
                RE_STRONG_NEW_SEG.containsMatchIn(line.text.take(6))
            val topAligned = line.x0 >= 0f && line.x0 < stats.left + INDENT_FACTOR * stats.bodySize
            if (strongNewSeg) hitStats.hit("guard_strongNewSeg")
            when {
                // 1. 段首禁则标点：无条件并回
                first != null && first in LEADING_NO_BREAK -> {
                    hitStats.hit("1_noBreakJoin"); appendJoined(line)
                }
                // 2. 标题行：折行合并或独立成段
                isTitle -> {
                    if (prevTitle && !dy.isNaN() && dy >= 0f && dy <= TITLE_FOLD_FACTOR * p.size) {
                        hitStats.hit("2_titleFold"); appendJoined(line) // 标题自身折行
                    } else {
                        hitStats.hit("2_titleNew"); flush()
                        takeIn(line)
                    }
                }
                // 3. 标题后的正文：标题独立成段
                prevTitle -> {
                    hitStats.hit("3_afterTitle"); flush()
                    takeIn(line)
                }
                // 4. y 回跳
                !dy.isNaN() && dy < -Y_JUMP_FACTOR * max(p.size, line.size) -> {
                    hitStats.hit("4_yJump"); flush()
                    takeIn(line)
                }
                // 4.5 深缩进标签块：≈2字缩进的短行（列表/标签项）独立成段
                //     （默认关闭：真书正文排版下误拆严重，见 deepIndentBlockEnabled 注释）
                deepIndentBlockEnabled &&
                    line.x0 >= 0f && line.x0 >= stats.left + DEEP_INDENT_FACTOR * stats.bodySize &&
                    line.x1 < stats.right - SHORT_LINE_FACTOR * line.size -> {
                    hitStats.hit("4.5_deepIndent"); flush()
                    takeIn(line)
                }
                // 4.75 满行必接：段落末行几乎必然不满 → 上一行满行即未完，续接
                //     （修复"满行+句末+顶格续行"被行距抖动/短行臂误断的段裂）
                fullLineJoinEnabled && !strongNewSeg && topAligned &&
                    p.x1 >= stats.right - FULL_LINE_FACTOR * stats.bodySize -> {
                    hitStats.hit("4.75_fullLineJoin"); appendJoined(line)
                }
                // 4.875 近满行接：近满+句末+顶格续行 = 段中句号
                !strongNewSeg && topAligned &&
                    p.x1 >= stats.right - NEAR_FULL_FACTOR * stats.bodySize &&
                    endsSentence(p.text) -> {
                    hitStats.hit("4.875_nearFullJoin"); appendJoined(line)
                }
                // 5. 行距超过段落阈值（守卫：上一行句末、或本行缩进起新段，才许断——
                //    E2E 实证：句中说一半的顶格续行遇行距抖动被误断，正文碎段重灾区）
                !dy.isNaN() && stats.pitchThreshold != null && dy > stats.pitchThreshold &&
                    (
                        endsSentence(p.text) ||
                            line.x0 >= stats.left + INDENT_FACTOR * stats.bodySize
                        ) -> {
                    hitStats.hit("5_pitch"); flush()
                    takeIn(line)
                }
                // 6. 首行缩进 + 上一行句末
                line.x0 >= 0f && line.x0 >= stats.left + INDENT_FACTOR * stats.bodySize &&
                    endsSentence(p.text) -> {
                    hitStats.hit("6_indentSent"); flush()
                    takeIn(line)
                }
                // 7. 短行 + 上一行句末
                line.x0 >= 0f && line.x1 < stats.right - SHORT_LINE_FACTOR * line.size &&
                    endsSentence(p.text) -> {
                    hitStats.hit("7_shortLineSent"); flush()
                    takeIn(line)
                }
                // 8. 无行距信号兜底：中宽行句末即断（满行/近满已被 4.75/4.875 续接）
                stats.pitchThreshold == null && endsSentence(p.text) -> {
                    hitStats.hit("8_fallbackSent"); flush()
                    takeIn(line)
                }
                // 9. 默认续接
                else -> {
                    hitStats.hit("9_defaultJoin"); appendJoined(line)
                }
            }
            prev = line
        }
        flush()
        return paras
    }
}

/** 段末判定：中英句末标点、引号书名号收尾、圈码序号（①–⑳）、引注 [12]/〔3〕 收尾 */
fun endsSentence(t: String): Boolean {
    val s = t.trimEnd()
    if (s.isEmpty()) return false
    val last = s.last()
    if (last in "。！？；：！?" || last == '…' || last == '”' || last == '』' || last == '」') return true
    if (last.code in 0x2460..0x2473) return true
    return RE_CITATION_END.containsMatchIn(s.takeLast(5))
}

private val RE_CITATION_END = Regex("[\\[〔]\\d{1,3}[\\]〕]$")

/** 段落拼接：CJK 直连，非 CJK 之间补空格（保 ASCII 单词不被粘死） */
fun softJoin(a: String, b: String): String {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val joiner = if (isCjkLike(a.last()) && isCjkLike(b.first())) "" else " "
    return a + joiner + b
}

fun joinTexts(texts: List<String>): String = texts.fold("") { acc, t -> softJoin(acc, t) }

private fun isCjkLike(c: Char): Boolean = c.code in 0x3000..0x9FFF || c.code in 0xFF00..0xFFEF
