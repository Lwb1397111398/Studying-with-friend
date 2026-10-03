package com.studyfriend.app.data.importer.pdfpipeline

import android.util.Log
import com.studyfriend.app.data.importer.BookParser
import com.studyfriend.app.data.importer.ParsedChapter
import com.studyfriend.app.data.importer.ParsedPara
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 校准后的章/节行（P3b-2）：[TocChapterCalibrator.calibrate] 成功产物，VM 侧映射为
 * ChapterEntity+ParagraphEntity 落库。
 *
 * @param title       章名：优先页面真实标题行文本，匹配失败回落目录 title（[titleFallback] 标记）
 * @param startPage   章/节起点 PDF 页（1-based）：①对齐保留=本地章原起点；②移动=采纳的本地章起点；③插入=目录映射页
 * @param level       1=章 2=节（TocEntry.level 口径，ChapterEntity.level 同构）
 * @param parentOrder 节的父章序号（父章在 level1 序列中的 1-based 序号）；章为 null
 * @param fromLocalOnly 本地 BookParser 有而目录无的章（只增不删，校准后保留）
 * @param redundantSuspect fromLocalOnly 章与相邻 TOC 章段落区间重叠率 >50%（疑似数据冗余，数据不删）
 * @param lowConfidence 由单锚 low_confidence 区段产出的新章/移动章
 * @param titleFallback 章名未能从页面文本匹配到、回落目录 title（排查标记）
 * @param localIndex    由本地章对齐/保留而来时 = BookParser 章下标（确认页用户改名的映射依据）；③插入章与节为 null
 * @param paras         重排后的段落；节行恒空（段落归属按 level1 章页区间，阅读流不变）
 */
data class CalibratedChapter(
    val title: String,
    val startPage: Int,
    val level: Int = 1,
    val parentOrder: Int? = null,
    val fromLocalOnly: Boolean = false,
    val redundantSuspect: Boolean = false,
    val lowConfidence: Boolean = false,
    val titleFallback: Boolean = false,
    val localIndex: Int? = null,
    val paras: List<ParsedPara> = emptyList(),
)

/** calibrate 成功产物；[systematicShiftSuspect]=位移量方差 <1 且中位数绝对值 ≥2（判据 #4 强制普查标记） */
class CalibrateOutcome(
    val chapters: List<CalibratedChapter>,
    val systematicShiftSuspect: Boolean = false,
)

/**
 * 目录驱动切章校准器（P3b-2 §3.1/§3.2/§3.3）。纯 Kotlin、零状态，锚定所需的页内文本经
 * [calibrate] 的 pageText 函数注入（VM 侧由 assembleText 按页标记切分，单测注入假文本源）。
 *
 * 流程（任一守卫不过 → 返回 null，调用方按现状落库零回归）：
 * ① pageNo 完整性前置校验（缺失 >5% → null）
 * ② 条目按书内页码下降分段（多册书下册页码重排），与探针区段的目录尾页配对
 * ②.5 章行无页码继承：null 页章取同段内其后第一个带页码条目的页码（章起点≈名下
 *    首条页码，偏差 0-1 页；mzzz 十二章/bddl 五六章实证），继承章标 low_confidence
 * ③ 每区段独立求 offset：首/中/尾 3 锚（恰 2 条降级双锚均值四舍五入、<2 弃区段），
 *    锚定窗口 [目录尾页+书内页码−3, 目录尾页+书内页码+20]，页内文本归一化含标题前 8 字
 *    即锚定；≥2 锚互差 ≤1 自洽否则弃区段；单锚 offset 采纳但区段标 low_confidence；
 *    offset 越出 [0,50] 视为系统性 OCR 异常弃区段
 * ④ 同页映射防重（保留 TOC 序号靠前者）与倒挂跳过，条目计入分母分子记 0
 * ⑤ 倒挂混合阈值：倒挂条目 ≤ min(锚定条目数×20%, 5) 且跳过后无倒挂 → 跳过继续；否则整书 null；
 *    全局锚定率（保留条目/全部 level1 条目）≥60% 才校准
 * ⑥ 三档锚定（只增不删）：偏差 ≤1 对齐保留；≥2 移动起点（映射页 ±5 内取偏差最小者、
 *    偏差同取起点页小者）并在新起点 ±1 页重匹配章名；±5 无章插入新章
 * ⑦ 段落按校准后章起点页区间显式重排；fromLocalOnly 章参与分配；null 页号段跟随原属章
 * ⑧ 节挂接：level2 条目按区段 offset 映射后落哪章区间挂哪章，越界丢弃记日志
 * ⑨ 位移量系统性偏移检测（方差 <1 且 |中位数| ≥2 → 疑点标记，供判据 #4 强制普查）
 */
object TocChapterCalibrator {

    private const val TAG = "P3b"

    /** 全局锚定率下限（计划案 §3.1；11 章须 ≥7 章） */
    const val MIN_ANCHOR_RATIO = 0.6

    /** pageNo 缺失比例上限（前置校验） */
    private const val MAX_PAGE_NO_MISSING = 0.05

    /** offset 合理域：真实书页码偏移落在 [0,50]（目录页码相对 PDF 页号的系统性差值） */
    const val OFFSET_MIN = 0
    const val OFFSET_MAX = 50

    /** 锚定窗口：假设页 ±[−3, +20] */
    private const val WINDOW_BACK = 3
    private const val WINDOW_FWD = 20

    /** 标题前缀长度（归一化后取前 8 字匹配）与短标题阈值（≤8 字触发页面上半部位置校验） */
    private const val PREFIX_LEN = 8
    private const val SHORT_TITLE_LEN = 8

    /** 三档①对齐容差（≤1 页保留）与②③搜索半径（映射页 ±5） */
    private const val ALIGN_TOLERANCE = 1
    private const val SEARCH_RADIUS = 5
    /** 三档②章名重匹配半径（新起点 ±1 页） */
    private const val NAME_MATCH_RADIUS = 1

    /** 倒挂混合阈值：条目数 ≤ min(锚定条目数×20%, 5) */
    private const val INVERT_RATIO = 0.2
    private const val INVERT_ABS_CAP = 5

    /** 单区段 level1 条目数 <2 时弃用该区段（3 锚/双锚策略的最低门槛） */
    private const val MIN_SEGMENT_ENTRIES = 2

    /** 位移量系统性偏移疑点：方差 <1 且 |中位数| ≥2 */
    private const val SHIFT_VARIANCE_MAX = 1.0
    private const val SHIFT_MEDIAN_MIN = 2.0

    /**
     * 校准入口。entries=探针目录全集（Done 态），localChapters=BookParser 解析章序列
     * （段落须带 pageNo），pageText=1-based PDF 页号→页内文本（null=无页/无文本层），
     * bookPageCount=PDF 总页数，tocLastPages=各探针区段的目录尾页（区内 tocLike 页最大值）。
     */
    fun calibrate(
        entries: List<TocEntry>,
        localChapters: List<ParsedChapter>,
        pageText: (Int) -> String?,
        bookPageCount: Int,
        tocLastPages: List<Int>,
    ): CalibrateOutcome? {
        val level1 = entries.filter { it.level == 1 }
        if (entries.isEmpty() || level1.isEmpty()) {
            Log.w(TAG, "calibrate skip: entries=${entries.size} level1=${level1.size}")
            return null
        }
        val allParas = localChapters.flatMap { it.paras }
        if (allParas.isEmpty()) {
            Log.w(TAG, "calibrate skip: no local paras")
            return null
        }
        // ① pageNo 完整性前置校验：页号链路断了（如上游标记被破坏）就整体放弃
        val missing = allParas.count { it.pageNo == null }
        if (missing > allParas.size * MAX_PAGE_NO_MISSING) {
            Log.w(TAG, "calibrate skip: pageNo missing $missing/${allParas.size} > 5%")
            return null
        }
        if (bookPageCount <= 0 || tocLastPages.isEmpty()) {
            Log.w(TAG, "calibrate skip: bookPageCount=$bookPageCount tocLastPages=$tocLastPages")
            return null
        }

        // ② 区段划分与目录尾页配对
        val segments = splitSegments(entries)
        val segTocLast = assignTocLastPages(segments.size, tocLastPages) ?: return null

        // ②.5 章行无页码继承（v1.2）：目录排版常态——章行不带页码、名下首条（节/副标题
        // 行）带页码，章起点≈首条页码（偏差 0-1 页，判据 #4 容差内）。真书实证：mzzz
        // 十二章章行全无页码（不继承则锚定率 0/11 <60% 整书放弃）、bddl 五六章无页码。
        // 继承源=同段内其后第一个带页码条目；跨段继承会把上册章错配下册页码，禁止；
        // 段内其后无页码条目则保持 null（④/⑧ 照旧跳过）。继承条目标 inherited，
        // 产出章带 lowConfidence（起点系推断而非页面实测）。
        val effSegments = ArrayList<List<TocEntry>>(segments.size)
        val effInherited = ArrayList<List<Boolean>>(segments.size)
        for (seg in segments) {
            val flags = MutableList(seg.size) { false }
            val eff = ArrayList<TocEntry>(seg.size)
            var next: Int? = null
            for (i in seg.indices.reversed()) {
                val p = seg[i].page
                if (p != null) {
                    next = p
                    eff.add(seg[i])
                } else if (next != null) {
                    flags[i] = true
                    eff.add(seg[i].copy(page = next))
                } else {
                    eff.add(seg[i])
                }
            }
            eff.reverse()
            effSegments += eff
            effInherited += flags
        }

        // ③ 每区段独立 offset（用继承后的条目页码，null 章得以参与锚定）
        val segOffsets = arrayOfNulls<Int>(segments.size)
        val segLowConf = BooleanArray(segments.size)
        effSegments.forEachIndexed { si, seg ->
            val segL1 = seg.filter { it.level == 1 }
            val r = resolveOffset(segL1, segTocLast[si], pageText, bookPageCount)
            segOffsets[si] = r.offset
            segLowConf[si] = r.lowConfidence
            if (r.offset != null) {
                Log.i(TAG, "calibrate seg#$si offset=${r.offset} lowConf=${r.lowConfidence} " +
                    "anchors=${r.anchors}/${segL1.size}")
            } else {
                Log.w(TAG, "calibrate seg#$si 区段弃用（level1=${segL1.size}）：${r.failReason}")
            }
        }

        // ④ 映射 level1 条目 → 候选章起点（TOC 序）；弃用区段/无页码/越界条目不计入分子
        val anchored = mutableListOf<AnchoredEntry>()
        effSegments.forEachIndexed { si, seg ->
            val offset = segOffsets[si] ?: return@forEachIndexed
            for ((ei, e) in seg.withIndex()) {
                if (e.level != 1) continue
                val page = e.page
                if (page == null) {
                    Log.w(TAG, "calibrate: 条目「${e.title}」无页码，跳过")
                    continue
                }
                val mapped = segTocLast[si] + page + offset
                if (mapped < 1 || mapped > bookPageCount) {
                    Log.w(TAG, "calibrate: 条目「${e.title}」映射页 $mapped 越界，跳过")
                    continue
                }
                anchored += AnchoredEntry(e, mapped, si, effInherited[si][ei])
            }
        }

        // ④b 同页防重 + 倒挂分级跳过（贪心：任何会低于前保留项的条目都跳过，其余自然无倒挂）
        val kept = mutableListOf<AnchoredEntry>()
        var inversions = 0
        for (a in anchored) {
            val last = kept.lastOrNull()
            if (last != null) {
                if (a.mapped == last.mapped) {
                    Log.w(TAG, "calibrate: 「${a.e.title}」与「${last.e.title}」同映射页 ${a.mapped}，保留 TOC 序号靠前者")
                    continue
                }
                if (a.mapped < last.mapped) {
                    inversions++
                    Log.w(TAG, "calibrate: 「${a.e.title}」映射页 ${a.mapped} 倒挂于 ${last.mapped}，跳过")
                    continue
                }
            }
            kept += a
        }
        val allowed = minOf(floor(anchored.size * INVERT_RATIO).toInt(), INVERT_ABS_CAP)
        if (inversions > allowed) {
            Log.w(TAG, "calibrate abort: 倒挂 $inversions 条 > min(${anchored.size}×20%, $allowed)，整书放弃")
            return null
        }

        // ④c 全局锚定率守卫（分母=全部 level1 条目，弃用/跳过条目分子记 0）
        val ratio = kept.size.toDouble() / level1.size
        if (ratio < MIN_ANCHOR_RATIO) {
            Log.w(TAG, "calibrate abort: 锚定率 ${kept.size}/${level1.size} < 60%，整书放弃")
            return null
        }

        // ⑥ 三档锚定（只增不删）
        val locals = localChapters.mapIndexed { i, ch ->
            LocalCh(i, ch.paras.firstNotNullOfOrNull { it.pageNo } ?: 0, ch)
        }
        val consumed = BooleanArray(localChapters.size)
        val tocChapters = mutableListOf<CalibratedChapter>()
        val displacements = mutableListOf<Int>()
        for (a in kept) {
            val prefix = a.e.title.take(PREFIX_LEN)
            val candidates = locals.filter {
                !consumed[it.idx] && it.start > 0 && abs(it.start - a.mapped) <= SEARCH_RADIUS
            }
            // 偏差最小者，偏差同取起点页小者（*1000+start 的字典序技巧）
            val best = candidates.minByOrNull { abs(it.start - a.mapped) * 1000 + it.start }
            // 区段级 low_confidence 或起点来自页码继承（推断非实测）→ 产出章标低置信
            val lowConf = segLowConf[a.segIdx] || a.inherited
            if (best == null) {
                // 三档③：±5 无本地章 → 插入新章，章名取该页标题行文本，兜底目录 title
                val name = matchName(listOf(a.mapped), prefix, pageText, bookPageCount)
                tocChapters += CalibratedChapter(
                    title = name ?: a.e.title,
                    startPage = a.mapped,
                    lowConfidence = lowConf,
                    titleFallback = name == null,
                )
            } else {
                consumed[best.idx] = true
                if (abs(best.start - a.mapped) <= ALIGN_TOLERANCE) {
                    // 三档①：对齐保留 BookParser 章（章名=本地章标题=页面真实文本）
                    tocChapters += CalibratedChapter(
                        title = best.parsed.title,
                        startPage = best.start,
                        localIndex = best.idx,
                    )
                } else {
                    // 三档②：移动起点（采纳偏差最小的本地章起点），新起点 ±1 页重匹配章名
                    val name = matchName(
                        listOf(best.start, best.start - NAME_MATCH_RADIUS, best.start + NAME_MATCH_RADIUS),
                        prefix, pageText, bookPageCount,
                    )
                    tocChapters += CalibratedChapter(
                        title = name ?: a.e.title,
                        startPage = best.start,
                        lowConfidence = lowConf,
                        titleFallback = name == null,
                        localIndex = best.idx,
                    )
                    displacements += best.start - a.mapped
                }
            }
        }

        // 只增不删：本地多出的章保留（fromLocalOnly；起点未知(0)也保留，仅不参与三档匹配）
        val localOnly = locals.filter { !consumed[it.idx] }.map {
            CalibratedChapter(
                title = it.parsed.title,
                startPage = it.start,
                fromLocalOnly = true,
                localIndex = it.idx,
            )
        }

        // ⑦ 段落显式重排：全部输出章按起点排序（稳定排序保 TOC/本地序），段落归
        // 「起点 ≤ 页号」中起点最大者（同起点取排序靠前者并 Log.w）
        val sorted = (tocChapters + localOnly).sortedBy { it.startPage }
        for (i in 1 until sorted.size) {
            if (sorted[i].startPage == sorted[i - 1].startPage) {
                Log.w(TAG, "calibrate: 同页多章起点 ${sorted[i].startPage}（「${sorted[i - 1].title}」「${sorted[i].title}」），该页段落归靠前章")
            }
        }
        val parasByCh = Array(sorted.size) { mutableListOf<ParsedPara>() }
        for (p in allParas) {
            val page = p.pageNo ?: continue // null 页号段跟随原属章，见下方补挂
            var best = -1
            var bestStart = -1
            for ((i, ch) in sorted.withIndex()) {
                if (ch.startPage <= page && ch.startPage > bestStart) {
                    bestStart = ch.startPage
                    best = i
                }
            }
            if (best >= 0) parasByCh[best] += p
        }
        // null 页号段（≤5%，前置校验兜底）：跟随原属本地章对应的输出章，原属章无对应时归首章
        val localToOutput = IntArray(localChapters.size) { -1 }
        sorted.forEachIndexed { i, ch -> ch.localIndex?.let { localToOutput[it] = i } }
        for ((li, ch) in localChapters.withIndex()) {
            val nullParas = ch.paras.filter { it.pageNo == null }
            if (nullParas.isEmpty()) continue
            parasByCh[if (localToOutput[li] >= 0) localToOutput[li] else 0] += nullParas
        }

        // fromLocalOnly 冗余检测：TOC 章跨度按 TOC 序列计算（忽略夹在中间的 fromLocalOnly 章，
        // 否则碎片自身起点会截断邻居跨度、永远测不到重叠）；本地章原段落落入相邻 TOC 章
        // 跨度占比 >50% → redundantSuspect（BookParser 过度拆章/幻影章疑点，数据不删）
        val tocIdxs = sorted.indices.filter { !sorted[it].fromLocalOnly }
        val out = sorted.mapIndexed { i, ch ->
            if (!ch.fromLocalOnly) {
                ch.copy(paras = parasByCh[i])
            } else {
                val original = localChapters[ch.localIndex!!].paras
                val prevTi = tocIdxs.indexOfLast { it < i }
                val nextTi = tocIdxs.indexOfFirst { it > i }
                // indexOfLast/First 找不到返回 -1（fromLocalOnly 章在最前/最后时），
                // 必须滤掉——listOfNotNull 只滤 null，-1 下标会越界
                val suspect = original.isNotEmpty() &&
                    listOfNotNull(prevTi, nextTi).filter { it >= 0 }.any { t ->
                    val start = sorted[tocIdxs[t]].startPage
                    val end = if (t + 1 < tocIdxs.size) sorted[tocIdxs[t + 1]].startPage else bookPageCount + 1
                    val overlap = original.count { p ->
                        val pg = p.pageNo
                        pg != null && pg >= start && pg < end
                    }
                    overlap.toDouble() / original.size > 0.5
                }
                if (suspect) {
                    Log.w(TAG, "calibrate: fromLocalOnly 章「${ch.title}」与相邻 TOC 章重叠率 >50%，标 redundantSuspect（数据不删）")
                }
                ch.copy(paras = parasByCh[i], redundantSuspect = suspect)
            }
        }

        // ⑧ 节挂接：level2 按区段 offset 映射后落哪章区间挂哪章，越界丢弃（条目用
        // 继承后版本，null 页节行继承其后者页码，标记随行传递）
        val sectionsByHost = Array(out.size) { mutableListOf<CalibratedChapter>() }
        val level1IndexOf = mutableMapOf<Int, Int>() // 输出列表下标 → level1 序号（1-based）
        var l1Seq = 0
        out.forEachIndexed { i, ch -> if (ch.level == 1) level1IndexOf[i] = ++l1Seq }
        effSegments.forEachIndexed { si, seg ->
            val offset = segOffsets[si] ?: return@forEachIndexed
            for ((ei, e) in seg.withIndex()) {
                if (e.level != 2) continue
                val page = e.page
                if (page == null) {
                    Log.w(TAG, "节「${e.title}」无页码，丢弃")
                    continue
                }
                val mapped = segTocLast[si] + page + offset
                var host = -1
                var hostStart = -1
                for ((i, ch) in out.withIndex()) {
                    if (ch.level == 1 && ch.startPage <= mapped && ch.startPage > hostStart) {
                        hostStart = ch.startPage
                        host = i
                    }
                }
                if (host < 0 || mapped > bookPageCount) {
                    Log.w(TAG, "节「${e.title}」映射页 $mapped 落不进任何章区间，丢弃")
                    continue
                }
                val prefix = e.title.take(PREFIX_LEN)
                val name = matchName(listOf(mapped), prefix, pageText, bookPageCount)
                sectionsByHost[host] += CalibratedChapter(
                    title = name ?: e.title,
                    startPage = mapped,
                    level = 2,
                    parentOrder = level1IndexOf[host],
                    lowConfidence = segLowConf[si] || effInherited[si][ei],
                    titleFallback = name == null,
                )
            }
        }

        // 最终序列：level1 章按序，节行紧跟父章（阅读 idx 空间内；UI 查询过滤 level=1）。
        // title 统一清洗：页面标题行原文可能带〔标题〕标记前缀或尾部点线串
        // （真书 bddl 实证「〔标题〕第四章 …」「第一章绪论......」入章名，观感与
        // 判据 #8 章名比对均受影响）
        val finalList = mutableListOf<CalibratedChapter>()
        out.forEachIndexed { i, ch ->
            finalList += ch.copy(title = cleanTitle(ch.title))
            finalList += sectionsByHost[i].map { it.copy(title = cleanTitle(it.title)) }
        }

        // ⑨ 位移量系统性偏移检测（判据 #4 强制普查标记）
        var shiftSuspect = false
        if (displacements.isNotEmpty()) {
            val sortedD = displacements.sorted()
            val median = if (sortedD.size % 2 == 1) sortedD[sortedD.size / 2].toDouble()
            else (sortedD[sortedD.size / 2 - 1] + sortedD[sortedD.size / 2]) / 2.0
            val mean = displacements.average()
            val variance = displacements.sumOf { d -> (d - mean) * (d - mean) } / displacements.size
            if (variance < SHIFT_VARIANCE_MAX && abs(median) >= SHIFT_MEDIAN_MIN) {
                Log.w(TAG, "calibrate: 位移量方差 $variance <1 且 |中位数| ${abs(median)} ≥2，疑似系统性偏移，建议全量普查")
                shiftSuspect = true
            }
        }
        val lowConfCount = finalList.count { it.lowConfidence }
        if (lowConfCount > 0) {
            Log.i(TAG, "calibrate: low_confidence 章/节数 $lowConfCount/${finalList.size}" +
                (if (lowConfCount > finalList.size * 0.3) "（>30%，E2E 后触发评审）" else ""))
        }
        Log.i(TAG, "calibrate done: 输出 ${finalList.size} 行（level1=${out.size}），" +
            "锚定 ${kept.size}/${level1.size}，位移章 ${displacements.size}，systematicShiftSuspect=$shiftSuspect")
        return CalibrateOutcome(finalList, shiftSuspect)
    }

    /** 锚定条目：目录条目 + 校准后映射页 + 所属区段下标 + 页码是否继承自其后条目 */
    private class AnchoredEntry(val e: TocEntry, val mapped: Int, val segIdx: Int, val inherited: Boolean)

    private class LocalCh(val idx: Int, val start: Int, val parsed: ParsedChapter)

    /** 区段 offset 求解结果；offset=null=区段弃用（failReason 供埋点） */
    private class OffsetResult(
        val offset: Int?,
        val lowConfidence: Boolean,
        val anchors: Int,
        val failReason: String,
    )

    /**
     * 每区段独立 offset（§3.1）：条目 ≥3 取首/中/尾 3 锚、恰 2 降级双锚、<2 弃用；
     * ≥2 锚互差 ≤1 自洽（恰 2 锚取均值四舍五入、3 锚取中位数），单锚采纳但 low_confidence；
     * offset 越出 [0,50] 弃区段。
     */
    private fun resolveOffset(
        segL1: List<TocEntry>,
        tocLast: Int,
        pageText: (Int) -> String?,
        bookPageCount: Int,
    ): OffsetResult {
        if (segL1.size < MIN_SEGMENT_ENTRIES) {
            return OffsetResult(null, false, 0, "区段 level1 条目数 ${segL1.size} <$MIN_SEGMENT_ENTRIES")
        }
        val idxs = if (segL1.size >= 3) listOf(0, segL1.size / 2, segL1.size - 1) else listOf(0, segL1.size - 1)
        val offs = mutableListOf<Int>()
        for (i in idxs) {
            val o = anchorOffset(segL1[i], tocLast, pageText, bookPageCount)
            if (o != null) offs += o
        }
        if (offs.isEmpty()) {
            return OffsetResult(null, false, 0, "全部锚定失败")
        }
        val offset: Int? = when (offs.size) {
            1 -> offs[0]
            2 -> if (abs(offs[0] - offs[1]) <= 1) ((offs[0] + offs[1]) / 2.0).roundToInt() else null
            else -> {
                val s = offs.sorted()
                if (s.last() - s.first() <= 1) s[s.size / 2] else null
            }
        }
        if (offset == null) {
            return OffsetResult(null, false, offs.size, "锚点互差 >1（$offs）不自洽")
        }
        // offset 合理域：窗口本身把 offset 限制在 [-3,+20]，上界 50 是纵深防御（窗口参数
        // 放宽时兜底），经公开 API 不可达；下界拦 -3..-1（标题出现在目录尾页前的排印异常）
        if (offset < OFFSET_MIN || offset > OFFSET_MAX) {
            return OffsetResult(null, false, offs.size, "offset=$offset 越界 [$OFFSET_MIN,$OFFSET_MAX]")
        }
        return OffsetResult(offset, lowConfidence = offs.size == 1, anchors = offs.size, failReason = "")
    }

    /**
     * 单条目锚定：窗口 [tocLast+page−3, tocLast+page+20] 内找页内文本含标题前缀（归一化）
     * 的行，offset=命中页−假设页。行护栏：排除目录点线条目与页眉（CJK+页码结尾）。
     * 短标题（≤8 字）加位置校验：命中行须在页面上半部（避免目录页/交叉引用假锚）。
     * 失败原因分类打点（无文本层/前缀不匹配/位置校验失败）。
     */
    private fun anchorOffset(
        e: TocEntry,
        tocLast: Int,
        pageText: (Int) -> String?,
        bookPageCount: Int,
    ): Int? {
        val page = e.page ?: return null
        val norm = e.title // TocJson.parse 已做 normalizeTitle
        if (norm.isEmpty()) return null
        val prefix = norm.take(PREFIX_LEN)
        val assumed = tocLast + page
        // 下界不低于目录尾页+1：正文起点必在目录之后——否则窗口会扫进目录页本身，
        // 页内「H 录第一章绪论…」类拼接行造成假锚（真书 bddl 实证：绪论锚 offset=-3）
        val from = maxOf(1, assumed - WINDOW_BACK, tocLast + 1)
        val to = minOf(bookPageCount, assumed + WINDOW_FWD)
        if (from > to) return null
        var sawText = false
        var sawPosFail = false
        for (p in from..to) {
            val text = pageText(p) ?: continue
            sawText = true
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val li = lines.indexOfFirst { isAnchorLine(it, prefix) }
            if (li < 0) continue
            if (norm.length <= SHORT_TITLE_LEN && li >= (lines.size + 1) / 2) {
                sawPosFail = true
                continue
            }
            return p - assumed
        }
        Log.w(
            TAG,
            "calibrate 锚定失败「${e.title}」：" +
                if (!sawText) "窗口 $from..$to 无文本层" else if (sawPosFail) "命中行位置校验失败" else "前缀「$prefix」不匹配",
        )
        return null
    }

    /** 锚定/章名共用行判定：含标题前缀、非目录点线条目、非页眉（CJK+页码结尾）。
     *  另排除标题后紧跟页码的拼接行——真书 bddl 实证：页眉「索引 493」与正文拼成一行、
     *  行尾是正文 CJK，RE_TRAIL_PAGE 排不掉；normalize 后「标题+数字」开头即页眉特征
     *  （真章标题名后不会紧跟数字，误排代价=该条目锚定失败警告，好于错锚）。 */
    private fun isAnchorLine(line: String, prefix: String): Boolean {
        val norm = TocJson.normalizeTitle(line)
        if (!norm.contains(prefix)) return false
        if (BookParser.RE_TOC_LINE.containsMatchIn(line)) return false
        if (BookParser.RE_TRAIL_PAGE.containsMatchIn(line)) return false
        val at = norm.indexOf(prefix)
        if (at >= 0 && at + prefix.length < norm.length && norm[at + prefix.length].isDigit()) {
            return false
        }
        return true
    }

    /**
     * 章名匹配：按给定候选页顺序找第一条 [isAnchorLine] 命中行，返回原始行文本
     * （页面真实标题）；找不到返回 null（调用方回落目录 title）。
     */
    private fun matchName(
        pages: List<Int>,
        prefix: String,
        pageText: (Int) -> String?,
        bookPageCount: Int,
    ): String? {
        for (p in pages) {
            if (p < 1 || p > bookPageCount) continue
            val text = pageText(p) ?: continue
            text.lines()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && isAnchorLine(it, prefix) }
                ?.let { return it }
        }
        return null
    }

    /** 章名清洗：剥〔标题〕标记前缀与尾部点线串及其后内容（页码/排印残留） */
    private fun cleanTitle(raw: String): String =
        raw.replace("〔标题〕", "").replace(Regex("[…·.•‧]{2,}.*$"), "").trim()

    /**
     * 区段划分：条目书内页码较前一条目下降 → 新册（多册书下册页码重排）。
     * null 页码不触发分裂（跟随当前册）。
     */
    private fun splitSegments(entries: List<TocEntry>): List<List<TocEntry>> {
        val out = mutableListOf<MutableList<TocEntry>>(mutableListOf())
        var last: Int? = null
        for (e in entries) {
            val p = e.page
            if (p != null && last != null && p < last) out.add(mutableListOf())
            out.last().add(e)
            if (p != null) last = p
        }
        return out
    }

    /**
     * 条目分段与探针区段（每册目录尾页）配对。多册书下册页码重排 → 条目分段数=探针
     * 区段数；单段探针 → 各条目段共用；条目未分段但探针多段（页码连续编号的多册书）→
     * 取第一段目录尾页（offset 可吸收偏差）；其余不齐按序配对、缺的复用末段。
     */
    private fun assignTocLastPages(groupCount: Int, tocLastPages: List<Int>): List<Int>? {
        return when {
            groupCount == tocLastPages.size -> tocLastPages
            tocLastPages.size == 1 -> List(groupCount) { tocLastPages[0] }
            groupCount == 1 -> {
                Log.w(TAG, "calibrate: 条目未分段但探针 ${tocLastPages.size} 区段，取第一段目录尾页 ${tocLastPages[0]}")
                listOf(tocLastPages[0])
            }
            else -> {
                Log.w(TAG, "calibrate: 条目段数 $groupCount 与探针区段数 ${tocLastPages.size} 不齐，按序配对")
                (0 until groupCount).map { tocLastPages[minOf(it, tocLastPages.lastIndex)] }
            }
        }
    }
}
