package com.studyfriend.app.data.importer.ocr

import com.studyfriend.app.data.importer.pdfpipeline.lineJoiner

/**
 * OCR 行内后处理器（P6b S3）：四条规则全部为纯 JVM 函数（无 Android 依赖，JVM 单测直测）。
 * 在 pt 空间工作——OcrLine 的像素坐标须先经 OcrPageSource ×72/dpi 换算（S4）再进本对象。
 *
 * 调用序（OcrImportRunner 两遍法，S4）：
 *   Pass1 逐页 recognize → [normalizePunct]（规则 a）→ [stripCitations]（规则 b）
 *   Pass2 全书 bodySize(pt) → 逐页 [classifyPage]（规则 c）→ [mergeFragments]（规则 d）
 *
 * 标定依据（SAMPLE8 = p003/p032/p062/p081/p092/p122/p247/p384，其中 p081/p247 为树状图页，
 * fixture t3_sample8.json 为 pt 空间 140dpi 口径）：
 * - 规则 c：计划案原文「竖排侧标框 + 树线字符频次」双特征。OCR 实测（.e2e/p6a 探针）
 *   树线字符频次在 8 页全部为 0——树线是图形不是文字，OCR 把 └ 认成 L、─ 认成一/工，
 *   第二特征物理不可用。按计划案 §8 风险 2 处置链落地为单特征版：
 *   竖排框（高宽比 > 3 且宽 > 12.3pt≈24px@140dpi）。正文 6 页 maxAr≤1.02，
 *   树状图页 4.22/9.09，阈值 3.0 的 ±20% 扰动（2.4/3.6）下 8/8 分类不变，两端余量约 4 倍。
 *   行首 L 残迹（树状图页 1/3 vs 正文 0/6）分离度不足，只记日志不参与判定。
 * - 规则 a：TO_HALF 五标点映射与 GT 修复原型 gt_fix2.py 同表（，：（）；→ 半角）。
 *   「树状图区保持全角」是 GT 特化口径，生产不实现——diagram 页整页兜底（S4），
 *   不参与 W 判据（6 非 diagram 页 literal CER），无需分区。
 * - 规则 b：与 strip_citations.py 逐条同构（处理序 CIP 豁免→FN→BR_HEAD→IN→BR_IN；
 *   正文序号 (1)（一）1. 非圈码非方括号数字，规则天然不碰）。Y 判据：fixture 8 页
 *   Python 原型期望输出 0 diff 由单测锁定。
 * - 规则 d：碎片行合并按计划案口径「行高 < 主字号×0.5 且与伙伴 y 中心间距 ≤ 0.5×字号」，
 *   合并 = 文本按 x0 序拼接 + 框取并集 + 置信度取 min（保守）。孤儿碎片（页内无近邻）
 *   原样保留不丢字。「页失败率 > 10% 整页兜底」属 S4 导入层指标，不在本对象。
 */
object OcrTextPostProcessor {

    /** 页型：BODY 走正常管线；DIAGRAM 整页树状图兜底（S4 入 pendingVisionItems） */
    enum class PageType { BODY, DIAGRAM }

    // ---- 规则 a：标点宽度归一（全角 → 半角，gt_fix2.py TO_HALF 同表） ----
    private val TO_HALF = mapOf('，' to ',', '（' to '(', '）' to ')', '：' to ':', '；' to ';')

    /** 单行标点归一：，：（）； 五个全角标点转半角，其余字符原样 */
    fun normalizePunctLine(text: String): String = buildString(text.length) {
        for (c in text) append(TO_HALF[c] ?: c)
    }

    fun normalizePunct(lines: List<OcrLine>): List<OcrLine> =
        lines.map { it.copy(text = normalizePunctLine(it.text)) }

    // ---- 规则 b：引注隐去（strip_citations.py 同构） ----
    // 空白/数字的 Unicode 语义用显式字符类手写（S7 E2E 平台差异修复）：原实现用
    // Pattern.UNICODE_CHARACTER_CLASS 对齐 Python3（\s \d 按 Unicode 匹配），但 Android
    // regex 不支持该 flag——类初始化即 IllegalArgumentException，进程崩溃（JVM 单测
    // 全绿掩盖此差异，模拟器 E2E 实录）。显式类两端行为一致：
    // \s → [\s\u00A0\u3000]（ASCII 空白 + 不换行空格 + 全角空格，OCR 行内实际会出现的
    // 全集；Unicode 其余空白字符 OCR 输出不出现）；\d → [0-9\uFF10-\uFF19]（半角+全角数字）
    private const val WS = "[\\s\\u00A0\\u3000]"
    private const val DIGIT = "[0-9\\uFF10-\\uFF19]"
    private const val CIRCLED = "[\\u2460-\\u2473]" // ①-⑳
    private val RE_CIP_LINE = java.util.regex.Pattern.compile("[IVX\\u2160-\\u2163]+\\.(?:$WS)*$CIRCLED")
    private val RE_FN_HEAD = java.util.regex.Pattern.compile("^($WS*)$CIRCLED($WS+)")
    private val RE_IN = java.util.regex.Pattern.compile(CIRCLED)
    private val RE_BR_HEAD = java.util.regex.Pattern.compile("^($WS*)[【\\[]$DIGIT{1,3}[】\\]]($WS*)")
    private val RE_BR_IN = java.util.regex.Pattern.compile("[【\\[]$DIGIT{1,3}[】\\]]")
    private val RE_TREE_RESIDUE = java.util.regex.Pattern.compile("^$WS*L(?=[\\u4e00-\\u9fff0-9（(])")

    /**
     * 单行引注隐去：CIP 著录行（I.①/II.① 结构）整行豁免；行首圈码脚注编号删圈码保空白；
     * 行首【n】/[n] 数字引注连后随空白删除；句内圈码与句内方括号数字全删。
     */
    fun stripCitationLine(text: String): String {
        if (RE_CIP_LINE.matcher(text).find()) return text
        var out = text
        RE_FN_HEAD.matcher(out).takeIf { it.find() }?.let { m ->
            out = m.group(1) + m.group(2) + out.substring(m.end())
        }
        RE_BR_HEAD.matcher(out).takeIf { it.find() }?.let { m ->
            out = m.group(1) + out.substring(m.end())
        }
        if (RE_IN.matcher(out).find()) out = RE_IN.matcher(out).replaceAll("")
        if (RE_BR_IN.matcher(out).find()) out = RE_BR_IN.matcher(out).replaceAll("")
        return out
    }

    fun stripCitations(lines: List<OcrLine>): List<OcrLine> =
        lines.map { it.copy(text = stripCitationLine(it.text)) }

    // ---- 规则 c：图形页分类器（竖排框单特征，标定见类 KDoc） ----
    private const val TALL_ASPECT = 3.0f
    private const val MIN_TALL_WIDTH_PT = 12.3f // = 24px@140dpi；竖排根节点实测 33.6/35.5pt

    /**
     * 首个竖排框（w>MIN_TALL_WIDTH_PT 且 h/w>TALL_ASPECT，入参 pt 口径 OcrLine）；无则 null。
     * classifyPage 与 OcrImportRunner 的 DIAGRAM 诊断日志共用本函数——判定表达式单源，防漂移。
     */
    fun findTallBox(lines: List<OcrLine>): OcrLine? =
        lines.firstOrNull { l ->
            val w = l.x1 - l.x0
            val h = l.y1 - l.y0
            w > MIN_TALL_WIDTH_PT && h / w > TALL_ASPECT
        }

    /** 诊断量化：首个竖排框 (h/w, w)（pt 口径）；无竖排框返回 null。供 OcrImportRunner 日志。 */
    fun tallBoxAspect(lines: List<OcrLine>): Pair<Float, Float>? =
        findTallBox(lines)?.let { ((it.y1 - it.y0) / (it.x1 - it.x0)) to (it.x1 - it.x0) }

    /**
     * 页型判定：存在竖排框（高宽比 > 3、宽 > 12.3pt）→ DIAGRAM。
     * 行首 L 残迹只记日志（观察特征，不参与判定——样本薄，误判风险大于收益）。
     */
    fun classifyPage(lines: List<OcrLine>, log: (String) -> Unit = {}): PageType {
        var lResidue = 0
        for (l in lines) {
            if (RE_TREE_RESIDUE.matcher(l.text).find()) lResidue++
        }
        if (lResidue > 0) log("treeResidue L-lines=$lResidue（观察特征，不判定）")
        return if (findTallBox(lines) != null) PageType.DIAGRAM else PageType.BODY
    }

    // ---- 规则 d：碎片行合并（行高 < 主字号×0.5 的框并入 y 中心最近邻） ----
    private const val FRAG_HEIGHT_FACTOR = 0.5f
    private const val FRAG_GAP_FACTOR = 0.5f

    /**
     * 碎片行合并：框高 < 0.5×bodySize 的碎片框，并入 y 中心距 ≤ 0.5×bodySize 的最近邻
     * （文本按 x0 序拼接、框并集、置信度取 min）。无近邻的孤儿碎片原样保留不丢字。
     * P6a 全书实测碎片行占比 0.05%，本规则是兜底不是常态路径。
     */
    fun mergeFragments(lines: List<OcrLine>, bodySize: Float): List<OcrLine> {
        if (lines.size < 2) return lines
        val fragMaxH = bodySize * FRAG_HEIGHT_FACTOR
        val maxGap = bodySize * FRAG_GAP_FACTOR
        val byY = lines.sortedBy { (it.y0 + it.y1) / 2f }
        val taken = BooleanArray(byY.size)
        val result = ArrayList<OcrLine>(byY.size)
        for (i in byY.indices) {
            if (taken[i]) continue
            val cur = byY[i]
            if (cur.y1 - cur.y0 >= fragMaxH) {
                result.add(cur)
                continue
            }
            val cy = (cur.y0 + cur.y1) / 2f
            var bestJ = -1
            var bestDist = Float.MAX_VALUE
            for (j in byY.indices) {
                if (j == i || taken[j]) continue
                val d = kotlin.math.abs((byY[j].y0 + byY[j].y1) / 2f - cy)
                if (d <= maxGap && d < bestDist) {
                    bestDist = d
                    bestJ = j
                }
            }
            if (bestJ < 0) {
                result.add(cur)
                continue
            }
            taken[bestJ] = true
            result.add(mergeTwo(cur, byY[bestJ]))
        }
        return result
    }

    private fun mergeTwo(a: OcrLine, b: OcrLine): OcrLine {
        val (l, r) = if (a.x0 <= b.x0) a to b else b to a
        // P6c-F：碎片框可能是同一西文词的兩半（det 横向切框），直连会把词粘死
        //（shpc 实录 'erungsslrategienimIn-undAusland'）——与 softJoin 单源规则补白
        val joiner = if (l.text.isEmpty() || r.text.isEmpty()) "" else
            lineJoiner(l.text.last(), r.text.first())
        return OcrLine(
            text = l.text + joiner + r.text,
            x0 = minOf(a.x0, b.x0),
            y0 = minOf(a.y0, b.y0),
            x1 = maxOf(a.x1, b.x1),
            y1 = maxOf(a.y1, b.y1),
            confidence = minOf(a.confidence, b.confidence),
        )
    }
}
