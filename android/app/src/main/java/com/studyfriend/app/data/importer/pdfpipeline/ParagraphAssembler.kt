package com.studyfriend.app.data.importer.pdfpipeline

import kotlin.math.max

/**
 * 行→段落组装（OPT-E，OPT-G P2 增强）。输入是清洗后的正文行（页眉/页码/脚注已摘除），
 * 按"字号、行距、缩进、句末标点"四类信号决定断段还是续接：
 * 1. 段首禁则标点：无条件并回（段落被误切的强信号）
 * 2. 标题行（字号 ≥ 正文×1.15，或 OCR 行的编号形态标题 P6c-F）：独立成段；连续大字行且垂直间距紧 → 标题自身折行合并
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

    /**
     * OCR 行标题容差（P6c-D）：OCR 行字号=det 框高×0.68，框高噪声 std≈3pt（P6a 真书
     * 实测标题−正文差 std 3.06pt），1.15×（≈1.6pt）容差被正文行高噪声击穿 → 大量正文行
     * 误判标题 → 规则 2 flush + 规则 3 连环切 → 句中断段（shpc 461 页 DB 实录：正文页
     * 内误断 1846 对、段长中位 33 字，断口「国家存⟂在的意义」「工．⟂作权财产权」）。
     * 1.4× 仍保留真章标题识别（影印书章标题 ≥1.5× 正文）。数字路径零改动。
     */
    internal const val TITLE_FACTOR_OCR = 1.4f

    /** 行所属来源是否 OCR（字号容差分支用）；混合段按行级判定，数字行不受影响 */
    private fun isOcrLine(l: PLine): Boolean = l.sourceVersion >= TextSourceRow.PROD_OCR_V1

    private fun titleFactorOf(l: PLine): Float =
        if (isOcrLine(l)) TITLE_FACTOR_OCR else TITLE_FACTOR

    /** 标题判据：字号路（数字路径主路）+ OCR 行编号形态路（P6c-F，见 [headingLikeOcr]） */
    private fun titleLike(l: PLine, stats: DocStats): Boolean =
        l.size >= stats.bodySize * titleFactorOf(l) ||
            (headingTextRouteEnabled && isOcrLine(l) && headingLikeOcr(l.text))

    /** 标题折行容差按行来源分支（OCR 大标题行距按字号算更远，见 TITLE_FOLD_FACTOR_OCR 标定） */
    private fun titleFoldFactorOf(l: PLine): Float =
        if (isOcrLine(l)) TITLE_FOLD_FACTOR_OCR else TITLE_FOLD_FACTOR
    private const val TITLE_FOLD_FACTOR = 2.2f

    /**
     * OCR 行标题折行容差（P6c-F）：大字号标题框的行间距按字号算是正文的 1.7 倍
     * （shpc p31 章题两行 dy=58.8pt、字号 19pt → 3.1×字号；2.2× 判不成折行，
     * 后半截「损害赔偿制度」掉进正文，章题被截成「第一章风险社会保护国家与」）。
     * 同块下一段落的 dy=159pt=8.4×字号，4.5 的余量既够接折行又不会把下一块并进来。
     */
    private const val TITLE_FOLD_FACTOR_OCR = 4.5f
    private const val Y_JUMP_FACTOR = 1.5f
    private const val INDENT_FACTOR = 0.8f
    private const val SHORT_LINE_FACTOR = 1.5f
    private const val FULL_LINE_FACTOR = 0.5f
    private const val NEAR_FULL_FACTOR = 1.0f
    private const val DEEP_INDENT_FACTOR = 1.6f

    /** 满行必接回退开关：P2 验收不达标时置 false 单独禁用该臂，近满行接/深缩进独立保留 */
    @JvmField
    internal var fullLineJoinEnabled = true

    /** 标题文本形态路开关（P6c-F，见 [headingLikeOcr]）。默认开；离线回放台用它做 A/B 归因。 */
    @JvmField
    internal var headingTextRouteEnabled = true

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
     * 中文编号标题形态（P6c-F 扫描书）：「第X章/节/目/款/篇/编/卷/回」、「一、」或「（四）」起头。
     * 不含「条」——正文里 '第18条第1项规定,…' 这类条文史是正文不是标题。
     */
    private val RE_CN_SECTION = Regex("^第[零〇一二三四五六七八九十百千两]+[章节目篇编卷回款]")
    // 序号后的顿号在 OCR 里常被认成逗号（实录 '二,保护国家'），半角/全角逗号都收；
    // 裸数字序号同理（实录 '2比例原则(释字第531号解释)' 独立成行被并进正文）
    private val RE_NUM_LEAD = Regex("^[一二三四五六七八九十]+[、,，]")
    private val RE_PAREN_NUM_LEAD = Regex("^[（(][零〇一二三四五六七八九十]+[）)]")
    private val RE_DIGIT_LEAD = Regex("^[0-9]{1,2}[^0-9.．]")

    /**
     * 标题的文本形态路（仅 OCR 行）：OCR 行字号=框高×0.68，影印书的小节标题与正文框高
     * 常相同（shpc 实测同为 15.5pt），字号路认不出 → 标题被并进上一段正文
     * （实录 '…构成一个包括预防管制及救济的规范体系第三节损害赔偿制度'、
     * '第一款州法与危险预万刑法对讳反国家共同生活秩序…'）。
     * 判据=编号形态 + 全长 ≤24 字 + 不以句末标点收尾。数字路径不启用（字号可靠、行为已 E2E 锁定）。
     */
    private fun headingLikeOcr(t: String): Boolean =
        t.length in 3..24 && !t.endsWith("。") && !t.endsWith("，") && !t.endsWith("、") &&
            (RE_CN_SECTION.containsMatchIn(t) || RE_NUM_LEAD.containsMatchIn(t) ||
                RE_PAREN_NUM_LEAD.containsMatchIn(t) || RE_DIGIT_LEAD.containsMatchIn(t))

    /**
     * [hitStats] 规则命中计数器（可观测性）：非 null 时累计各臂命中次数（跨页累计，
     * 由调用方持有生命周期），供 E2E 验收归因（如满行必接在低满行率排版下命中率稀少）。
     * 以参数注入而非静态字段——并发 clean() 各自持 map，无交叉污染。
     * 生产路径传 null 零开销。
     * 另含 P6c-F 两个质量信号：`q_breakPrevNotSentence`（上一行非句末却断段=句子被斩，
     * 头号投诉的直接量化）与 `q_joinSentThenIndent`（上一行句末且本行缩进却续接=真段界
     * 被抹平，防「为压低前者而把全书并成一段」的反向退化）。
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
            if (first != null) cur.append(lineJoiner(cur.last(), first)) // 与 softJoin 单源
            cur.append(line.text)
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
            val prevSent = endsSentence(p.text)
            val isTitle = titleLike(line, stats)
            val prevTitle = titleLike(p, stats)
            val strongNewSeg = prevSent && first != null &&
                RE_STRONG_NEW_SEG.containsMatchIn(line.text.take(6))
            val topAligned = line.x0 >= 0f && line.x0 < stats.left + INDENT_FACTOR * stats.bodySize
            // 上一行是否排到右缘（满行=该句被排版折断，句子未完的强证据）
            val prevFull = p.x1 >= 0f && p.x1 >= stats.right - FULL_LINE_FACTOR * stats.bodySize
            if (strongNewSeg) hitStats.hit("guard_strongNewSeg")
            val parasBefore = paras.size
            when {
                // 1. 段首禁则标点：无条件并回
                first != null && first in LEADING_NO_BREAK -> {
                    hitStats.hit("1_noBreakJoin"); appendJoined(line)
                }
                // 2. 标题行：折行合并或独立成段
                isTitle -> {
                    if (prevTitle && !dy.isNaN() && dy >= 0f && dy <= titleFoldFactorOf(p) * p.size) {
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
                    prevFull -> {
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
                //    P6c-F 缩进分支加「上一行不满行」前提：上一行已到右缘 = 该句是被排版
                //    折断的，本行缩进不足以证明新段。shpc 真书引文块实录：满行「…应自解释
                //    公布之日起,至迟于届满」+ 缩进短行「一年时失其效力。」被本臂斩成两段。
                !dy.isNaN() && stats.pitchThreshold != null && dy > stats.pitchThreshold &&
                    (
                        endsSentence(p.text) ||
                            (!prevFull &&
                                line.x0 >= stats.left + INDENT_FACTOR * stats.bodySize)
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
            // 质量信号计数（P6c-F 离线回放台度量「过断/漏断」；生产传 null 零开销）：
            // 断段但上一行非句末 = 句子被斩断的强信号（本管线头号投诉）；标题臂（2/3）造的
            // 边界是正当断段，不计入，否则「认出更多小节标题」反而被量成「断得更多」。
            // 续接但上一行句末且本行缩进 = 真段落边界被抹平的强信号（防「为压低前者而全并」）
            val broke = paras.size > parasBefore
            if (broke && !prevSent && !isTitle && !prevTitle) hitStats.hit("q_breakPrevNotSentence")
            val leadingJoin = first != null && first in LEADING_NO_BREAK
            if (!broke && !leadingJoin && prevSent && line.x0 >= 0f && !topAligned) {
                hitStats.hit("q_joinSentThenIndent")
            }
            prev = line
        }
        flush()
        return paras
    }
}

/**
 * 段末判定：中英句末标点、引号书名号收尾、圈码序号（①–⑳）、引注 [12]/〔3〕 收尾。
 *
 * P6c-F 扫描书两处补强（shpc 60 页回放台实测）：
 * 1. **半角 : ; 计入句末**——OCR 规则 a 把全角 ：； 转成半角（OcrTextPostProcessor.TO_HALF），
 *    旧的句末集只有全角，这些行永远判不出句末 → 该断的段不断（'…兹举两个"司法院大法官"解释，
 *    以供参照：' 后接引文块被并成一段）。数字路径原文是全角，补集同样适用，无副作用。
 * 2. **行尾单角脚注编号=句子在此结束**——扫描书脚注号被 OCR 认成正文数字粘在行尾
 *    （实录 '…以防治犯罪保障人民安全2'、'…预防危险的机能2'），末字符是数字时句末判据全废。
 *    限「恰好 1 位数字 + 前一是 CJK/中日标点」，'…共计30'（两位）不受影响。
 */
fun endsSentence(t: String): Boolean {
    val s = t.trimEnd()
    if (s.isEmpty()) return false
    val last = s.last()
    if (last in "。！？；：！?:" || last == '…' || last == '”' || last == '』' || last == '」') return true
    if (last.code in 0x2460..0x2473) return true
    if (last in '0'..'9' && isTrailingFootnoteMark(s)) return true
    return RE_CITATION_END.containsMatchIn(s.takeLast(5))
}

/** 行尾脚注编号（1 位数字，前一个是 CJK 汉字或中日标点）——见 [endsSentence] 注 2 */
private fun isTrailingFootnoteMark(s: String): Boolean {
    if (s.length < 2) return false
    if (s[s.length - 2] in '0'..'9') return false // 两位以上数字是实数（'共计30'），不是脚注号
    val before = s[s.length - 2].code
    return before in 0x3000..0x9FFF || before in 0xFF00..0xFFEF
}

private val RE_CITATION_END = Regex("[\\[〔]\\d{1,3}[\\]〕]$")

/** 段落拼接分隔符的实现入口（规则见 [lineJoiner]） */
fun softJoin(a: String, b: String): String {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    return a + lineJoiner(a.last(), b.first()) + b
}

/**
 * 行间/碎片拼接的分隔符规则——[softJoin]、[ParagraphAssembler] 的 appendJoined 与
 * OcrTextPostProcessor.mergeTwo（碎片框合并）三处共用单源，防「一处改了另一处漂移」。
 *
 * P6c-F 扫描书两条修正（真书 shpc 60 页回放台实录）：
 * 1. **标点两侧不补空格**：规则 a 把 ，：（）； 转半角后，这些 ASCII 标点不再落旧判据的
 *    「CJK」集合，于是中文标点后凭空插空格（实录 '制定广若十保护人民权益的特别民法, 其特色有三:'）。
 * 2. **西文断词直连**：det 把一个德文长词切成两框（'echselseiligeAuf-' + 'fangordnungen'），
 *    行末是连字符（- ‐ －，本就不算词字符）时两侧直连，还原成原词。
 * 其余照旧：西文词之间、中英相邻都补一个空格（保 ASCII 单词不被粘死、跨语种可读）。
 */
internal fun lineJoiner(last: Char, first: Char): String {
    val lw = isLatinWordChar(last)
    val cw = isCjkWordChar(last)
    if (!lw && !cw) return ""
    val lw2 = isLatinWordChar(first)
    val cw2 = isCjkWordChar(first)
    if (!lw2 && !cw2) return ""
    return if (lw || lw2) " " else ""
}

fun joinTexts(texts: List<String>): String = texts.fold("") { acc, t -> softJoin(acc, t) }

/** 西文词字符：ASCII 字母数字 + 拉丁扩展（德文变音） */
private fun isLatinWordChar(c: Char): Boolean =
    c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c.code in 0xC0..0xFF

/** 汉字（不含中日标点区）——标点两侧一律直连，见 [lineJoiner] 的 P6c-F 注 1 */
private fun isCjkWordChar(c: Char): Boolean = c.code in 0x4E00..0x9FFF
