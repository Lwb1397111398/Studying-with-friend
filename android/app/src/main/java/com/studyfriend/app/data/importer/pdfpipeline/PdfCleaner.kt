package com.studyfriend.app.data.importer.pdfpipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 页面清洗（OPT-E）：拿到带几何信息的行后的规则层，按序做五件事——
 * 1. 目录页整页直通（目录条目是 BookParser 的章节资产，绝不能清改）
 * 2. 删页眉（顶部条带 + 小字号）、删页码行
 * 3. 收脚注（底部条带 + 显著小字号）
 * 4. 坏字清理：控制符剔除、彝文借码标点还原、PUA 统计
 * 5. 行→段落组装（委托 ParagraphAssembler）+ 跨页续接合并（crossPageMerge）
 *
 * 铁律（经验帖 §11 适配）：规则优先、不丢字——所有删除都有几何+字号双重证据；
 * 唯一的字符改写是彝文借码标点（有页级守卫防误伤真彝文）。
 */
object PdfCleaner {

    // ---- 目录页判定 ----
    /** 目录行：前导点线 + 尾页码（与 BookParser.RE_TOC_LINE 同源形态） */
    private val RE_TOC_LINE = Regex("[…·.•‧]{2,}\\s*\\d+\\s*$")
    private const val TOC_MIN_HITS = 3

    // ---- 页码形态 ----
    private val RE_PAGE_NUM = Regex(
        "^(\\d{1,4}|[−\\-–]\\s*\\d{1,4}\\s*[−\\-–]|[·•‧]\\s*\\d{1,4}\\s*[·•‧]|第?\\s*\\d{1,4}\\s*页|\\d{1,3}\\s*/\\s*\\d{1,3})$",
    )

    // ---- 页眉 / 脚注 ----
    private const val HEADER_BAND_PT = 56f        // 顶部条带 = max(56pt, 12% 页高)
    private const val HEADER_BAND_FACTOR = 0.12f
    private const val HEADER_SIZE_FACTOR = 0.95f  // 字号 < 正文×0.95 才算页眉
    private const val HEADER_MAX_CHARS = 40       // 且不超过 40 字（防误删顶部大段）
    private const val FOOTER_BAND_FACTOR = 0.28f  // 脚注：底部 28% 页高
    private const val FOOTER_SIZE_FACTOR = 0.85f  // 字号 < 正文×0.85 才算脚注

    /** 坏字体借用的彝文区标点 → 常用标点（《民法总则》实测：逗号/句号/分号被映射进彝文区） */
    private val YI_PUNCT = mapOf(
        'ꎬ' to '，',
        'ꎮ' to '。',
        'ꎻ' to '；',
    )

    /** 真彝文音节区（U+A000–U+A48C）；页内除借码标点外还有音节 → 是真彝文，不许整页替换 */
    private val RE_YI_SYLLABLE = Regex("[\\uA000-\\uA48C]")

    /** PUA（私用区，字体 cmap 坏掉时出现）：E000–F8FF 与增补私用区 A 的高代理段 */
    private fun isPua(c: Char): Boolean = c.code in 0xE000..0xF8FF || c.code in 0xDB80..0xDBFF

    // ---------------------------------------------------------------- 统计

    /**
     * 全书几何统计：
     * - 正文字号：0.5pt 桶、字符数加权众数（正文行字最多自然胜出；并列取大桶）
     * - 左右边距：2pt 桶众数，仅 |size−body|≤1.0 的行参与（空回退 36pt）
     * - 行距阈值：同页相邻行 dy 直方图（1pt 桶）主峰 pitch；找 ≥1.1×pitch 且
     *   计数 ≤ 主峰×0.25 的最小"谷点" → 阈值 min(1.5×pitch, 谷点)；
     *   无谷点且样本 ≥8 → 1.4×pitch；样本 3~7 无谷点（或 <3）→ null 无信号
     */
    fun docStats(pagesLines: List<List<PLine>>, dims: List<Pair<Float, Float>>): DocStats {
        val sizeBuckets = HashMap<Int, Int>()
        pagesLines.forEach { lines ->
            lines.forEach { l ->
                if (l.text.isBlank()) return@forEach
                val key = (l.size * 2).toInt()
                sizeBuckets[key] = (sizeBuckets[key] ?: 0) + l.text.length
            }
        }
        val bodySize = modeBucket(sizeBuckets, preferHigh = true).let {
            if (it == Int.MIN_VALUE) 10.5f else it / 2.0f
        }

        val leftBuckets = HashMap<Int, Int>()
        val rightBuckets = HashMap<Int, Int>()
        pagesLines.forEachIndexed { idx, lines ->
            val width = dims.getOrNull(idx)?.first ?: 0f
            lines.forEach { l ->
                if (l.x0 < 0f || l.text.isBlank()) return@forEach
                if (abs(l.size - bodySize) > 1.0f) return@forEach
                leftBuckets[(l.x0 / 2).toInt()] = (leftBuckets[(l.x0 / 2).toInt()] ?: 0) + l.text.length
                val x1 = if (width > 0f) minOf(l.x1, width) else l.x1
                rightBuckets[(x1 / 2).toInt()] = (rightBuckets[(x1 / 2).toInt()] ?: 0) + l.text.length
            }
        }
        // 桶键 = (x/2)（2pt 粒度），还原必须 ×2 —— E2E 实证：曾误写 /2 造成双重除 2，
        // left=6.5/right=88.5 垃圾边距 → 缩进判定恒真（句末行全断）→ 正文碎段 33%
        val left = modeBucket(leftBuckets, preferHigh = false).let {
            if (it == Int.MIN_VALUE) 36f else it * 2.0f
        }
        val right = modeBucket(rightBuckets, preferHigh = true).let {
            if (it == Int.MIN_VALUE) (dims.getOrNull(0)?.first ?: 595f) - 36f else it * 2.0f
        }

        val gaps = mutableListOf<Float>()
        pagesLines.forEach { lines ->
            for (i in 1 until lines.size) {
                val a = lines[i - 1]
                val b = lines[i]
                if (a.y0 < 0f || b.y0 < 0f) continue
                if (abs(a.size - b.size) > 0.5f) continue
                val dy = b.y0 - a.y0
                if (dy > 0f && dy < 200f) gaps.add(dy) // 只收正常向下行距；≥200pt 视为跳栏噪声
            }
        }
        var pitchThreshold: Float? = null
        if (gaps.isNotEmpty()) {
            val hist = HashMap<Int, Int>()
            gaps.forEach { g -> val k = g.toInt(); hist[k] = (hist[k] ?: 0) + 1 }
            val peak = modeBucket(hist, preferHigh = false)
            val peakCount = hist[peak] ?: 0
            val valley = hist.keys
                .filter { it >= (1.1f * peak).toInt() && (hist[it] ?: 0) <= peakCount * 0.25f }
                .minOrNull()
            pitchThreshold = when {
                valley != null -> min(1.5f * peak, valley.toFloat())
                gaps.size >= 8 -> 1.4f * peak
                else -> null
            }
        }
        return DocStats(bodySize, left, right, pitchThreshold)
    }

    /** 桶众数；并列时按 preferHigh 取更大/更小桶（字号/右边界取大、左边距/行距峰取小） */
    private fun modeBucket(buckets: HashMap<Int, Int>, preferHigh: Boolean): Int {
        if (buckets.isEmpty()) return Int.MIN_VALUE
        val best = buckets.values.max()
        val keys = buckets.filterValues { it == best }.keys
        return if (preferHigh) keys.max() else keys.min()
    }

    /**
     * 页眉文本特征（字号判据的兜底路）：行首"第/笫+数字+章节篇回"章名样 + 行尾"CJK+页码"。
     * 码点比较避免字面量，第=0x7B2C 笫=0x7B2B 章=0x7AE0 节=0x8282 篇=0x7BC7 回=0x56DE。
     * 条带内由调用方保证；正文首行在条带下方，"第X章…数字结尾"组合不会出现在那里。
     */
    private fun headerLikeText(t: String): Boolean {
        var i = t.length - 1
        while (i >= 0 && t[i] == ' ') i--
        var digits = 0
        while (i >= 0 && t[i] in '0'..'9') { i--; digits++ }
        if (digits == 0 || digits > 4) return false
        while (i >= 0 && t[i] == ' ') i--
        if (i < 0 || t[i].code !in 0x4E00..0x9FFF) return false
        val c0 = t.first()
        if (c0.code != 0x7B2C && c0.code != 0x7B2B) return false
        var sawNum = false
        for (j in 1 until t.length) {
            val c = t[j]
            if (sawNum && (c.code == 0x7AE0 || c.code == 0x8282 || c.code == 0x7BC7 || c.code == 0x56DE)) return true
            if (c in '0'..'9' || c.code in 0x4E00..0x9FFF) {
                sawNum = true
            } else if (c != ' ') {
                return false
            }
        }
        return false
    }

    /**
     * 页眉文本特征第二路（偶数页形态）："页码 空格 书名"——数字开头(1~4位) +
     * 纯 CJK 结尾(2~12字)无任何标点。E2E 实证：《民法总则》偶数页页眉
     * "10 民法总则"…“236 民法总则”，pdfbox 对部分页报 size=正文，且行尾无页码
     * （页码在行首），headerLikeText 的尾部页码判据认不出。残留 135 条且
     * 占据下页首段位置阻断跨页续接。条带内由调用方保证。
     */
    private fun headerPageNumLike(t: String): Boolean {
        var i = 0
        var digits = 0
        while (i < t.length && t[i] in '0'..'9') { i++; digits++ }
        if (digits == 0 || digits > 4) return false
        if (i < t.length && t[i] == ' ') i++
        var cjk = 0
        while (i < t.length && t[i].code in 0x4E00..0x9FFF) { i++; cjk++ }
        return i == t.length && cjk in 2..12
    }

    /**
     * 脚注文本特征（字号判据的兜底路）：行首脚注编号符。E2E 实证：同书两种
     * 口径——字号报对的页脚注正常收集，报 size=正文的页脚注整页混进正文
     * （段首＠ 110 条 + 含"参见" 287 段）；脚注编号圈码①被坏字体映射成
     * ＠/0/O/心 等随机字符，不可枚举还原，只能按"行首符"整体认。
     * O/0/心 为排除行首小数（0.5）/英文（O'Reilly）误收，要求次字符是 CJK。
     */
    private fun footnoteMarked(t: String): Boolean {
        if (t.isEmpty()) return false
        val c0 = t.first()
        if (c0.code in 0x2460..0x2473) return true       // 圈码①…⑳（编号未坏时）
        if (c0.code == 0xFF20 || c0.code == 0x40) return true // ＠/@（圈码坏映射主形态）
        if (c0.code == 0x30 || c0.code == 0x4F || c0.code == 0x6F || c0.code == 0x5FC3) {
            return t.length > 1 && t[1].code in 0x4E00..0x9FFF
        }
        return t.startsWith("参见")
    }

    // ---------------------------------------------------------------- 清洗

    /**
     * 逐页清洗。dims 与 pagesLines 等长：(页宽, 页高)（旋转已折算，pt）。
     * 返回 PageOut 列表（paras 已组装；跨页续接由调用方再调 crossPageMerge）。
     */
    fun clean(
        pagesLines: List<List<PLine>>,
        dims: List<Pair<Float, Float>>,
        stats: DocStats,
    ): List<PageOut> {
        val out = mutableListOf<PageOut>()
        pagesLines.forEachIndexed { idx, lines ->
            val pageHeight = dims.getOrNull(idx)?.second ?: 842f
            // 清洗前全文统计（rawChars/puaCount 的口径，含将被删除的页眉页码）
            val allText = joinTexts(lines.map { it.text })
            val puaCount = allText.count { isPua(it) }

            // 目录页先判（在页码删除之前——目录页的孤页码是条目触发器）
            val tocLike = lines.count { RE_TOC_LINE.containsMatchIn(it.text) } >= TOC_MIN_HITS
            val headerBand = max(HEADER_BAND_PT, pageHeight * HEADER_BAND_FACTOR)
            val footerBandStart = pageHeight * (1f - FOOTER_BAND_FACTOR)

            val kept = mutableListOf<PLine>()
            val footnotes = mutableListOf<String>()
            for (line in lines) {
                val norm = stripControl(maybeYiNormalize(line.text))
                if (norm.isBlank()) continue
                val l = line.copy(text = norm.trim())
                // 页眉：顶部条带 + 短行，且 小字号；或字号与正文相同但行首章名样+行尾页码；
                // 或"页码 书名"形态（偶数页页眉，行首数字）。三个文本路都只作字号判据的兜底
                // （E2E 实证：《民法总则》部分页眉 pdfbox 报 size=正文，字号判据漏）
                if (l.y0 in 0f..headerBand && l.text.length <= HEADER_MAX_CHARS &&
                    (l.size in 0.1f..(stats.bodySize * HEADER_SIZE_FACTOR) ||
                        headerLikeText(l.text) || headerPageNumLike(l.text))
                ) continue
                // 页码行（目录页不删）
                if (!tocLike && RE_PAGE_NUM.matches(l.text)) continue
                // 脚注：底部条带 + 显著小字号；或行首脚注编号符（文本路兜底，目录页不收）
                if (!tocLike && l.y0 >= footerBandStart &&
                    (l.size in 0.1f..(stats.bodySize * FOOTER_SIZE_FACTOR) || footnoteMarked(l.text))
                ) {
                    footnotes.add(l.text)
                    continue
                }
                kept.add(l)
            }

            if (tocLike) {
                // 目录页整页直通：保留孤页码行，lineCount/shortLineCount 置 0
                // （PageSelector 据此跳过——目录页全是短行，否则必被误选去视觉转写）
                out.add(
                    PageOut(
                        idx + 1, tocLike = true, rawChars = allText.length, puaCount = puaCount,
                        lineCount = 0, shortLineCount = 0,
                        paras = kept.map { Para(it.text) },
                        firstLine = null, lastLine = null,
                    ),
                )
                return@forEachIndexed
            }

            val lineCount = kept.size
            val shortLineCount = kept.count { it.x0 >= 0f && it.x1 < stats.right - 1.5f * stats.bodySize }
            val paras = ParagraphAssembler.assemble(kept, stats).toMutableList()
            if (footnotes.isNotEmpty()) {
                paras.add(Para(joinTexts(footnotes), footnote = true))
            }
            out.add(
                PageOut(
                    idx + 1, tocLike = false, rawChars = allText.length, puaCount = puaCount,
                    lineCount = lineCount, shortLineCount = shortLineCount,
                    paras = paras,
                    firstLine = kept.firstOrNull(), lastLine = kept.lastOrNull(),
                ),
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 跨页续接

    /**
     * 跨页续接合并（carryPara 模式）：上一页末段未句末收尾、末行顶到右边距
     * （差 3 字号以内），且下一页首段从左边距起步、字号是正文（缩进/标题 =
     * 新段反证）→ 把下一页首段并回上一页末段。目录页两侧都不并。
     */
    fun crossPageMerge(pages: List<PageOut>, stats: DocStats) {
        for (i in 1 until pages.size) {
            val prev = pages[i - 1]
            val cur = pages[i]
            if (prev.tocLike || cur.tocLike) continue
            val last = prev.paras.lastOrNull() ?: continue
            val first = cur.paras.firstOrNull() ?: continue
            if (last.footnote || first.footnote) continue
            if (endsSentence(last.text)) continue
            val lastGeom = prev.lastLine ?: continue
            val firstGeom = cur.firstLine ?: continue
            if (lastGeom.x1 < 0f || firstGeom.x0 < 0f) continue
            if (lastGeom.x1 < stats.right - 3f * stats.bodySize) continue
            if (firstGeom.x0 >= stats.left + 0.8f * stats.bodySize) continue
            if (firstGeom.size >= stats.bodySize * 1.15f) continue
            cur.paras.removeAt(0)
            prev.paras[prev.paras.size - 1] = Para(joinTexts(listOf(last.text, first.text)))
        }
    }

    // ---------------------------------------------------------------- 坏字清理

    /** C0 控制符 + DEL 剔除（保留 \n 由行结构表达，行内不该有） */
    private fun stripControl(text: String): String = buildString(text.length) {
        text.forEach { c -> if (c.code >= 32 && c.code != 127) append(c) }
    }

    /**
     * 彝文借码标点还原（页级守卫）：页内出现借码标点、且剔除它们后再无
     * 其他彝文音节 → 整页替换；否则是真彝文文本，原样保留。
     */
    private fun maybeYiNormalize(text: String): String {
        if (!text.any { it in YI_PUNCT.keys }) return text
        val otherYi = text.any { it !in YI_PUNCT.keys && it.code in 0xA000..0xA48C }
        if (otherYi) return text
        return text.map { YI_PUNCT[it] ?: it }.joinToString("")
    }
}
