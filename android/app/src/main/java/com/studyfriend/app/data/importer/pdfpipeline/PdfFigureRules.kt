package com.studyfriend.app.data.importer.pdfpipeline

import kotlin.math.abs

/**
 * P4 示意图保留——三层过滤规则：阈值常量与判定纯函数集中于此。
 * 坐标口径：全部用「折算后显示空间」值（/Rotate 已折算，y 自顶向下，单位 pt），
 * 与 PdfTypes 的 PLine 同口径（折算见 FigureCoordMath.rotateRect）。
 *
 * 阈值局限声明（计划案 v1.7，r6-P1-1c）：仅在「扫描底图+文字层」形态的 3 本台版法学
 * 教材（mfzz/bddl/shpc，计划案 §2 探查）上验证过，异源 PDF 通用性未验证——敏感性验证
 * （≥2 本异源真书对账）为 P6 前置任务。调整任一常量必须同步更新计划案 J2 硬基线
 * （mfzz=4±1、bddl=0、shpc=0）。
 */
object PdfFigureThresholds {
    /** R1 面积占比阈值：≥ 此值判整页扫描底图 */
    const val R1_AREA_RATIO = 0.85f

    /** R1 单边占比阈值：宽、高均 ≥ 此值也判底图（与面积阈值成「或」） */
    const val R1_EDGE_RATIO = 0.90f

    /** R2 单边最小边长（pt）：宽或高低于此为装饰元素（≈14mm；mfzz p86 真图高 107pt 安全保留） */
    const val R2_MIN_EDGE_PT = 40f

    /** R2 面积占比阈值：< 此值（相对显示空间页面积）为装饰元素 */
    const val R2_AREA_RATIO = 0.02f

    /** R3 同位重复：同一图（同 xref 或同 rawMd5）出现的页数下限 */
    const val R3_MIN_PAGES = 3

    /** R3 归一化位置偏差容差（x0/y0 相对页宽高、w 相对页宽） */
    const val R3_POS_TOLERANCE = 0.02f

    /** 阶段 2 超大图守卫（计划案 v1.10，r10-P1-2）：源像素数超过则不解码直接跳过 */
    const val MAX_SOURCE_PIXELS = 5_000_000L

    /** 降采样目标宽下限（px）：显示尺寸上限不足时兜底，保全屏预览放大清晰度 */
    const val TARGET_WIDTH_MIN = 1200

    /**
     * 低质量防御分支（计划案 §3-C，P1-3）：图页清洗前文字层字符数低于此值 → 文字层烂，
     * 第一/第二道闸均放行视觉转写（锚定偏移的代价 < 整页乱码）
     */
    const val LOW_RAW_CHARS = 100

    /** 低质量防御分支：PUA 坏字形占清洗前字符数比例超过此值 → 同上放行 */
    const val LOW_PUA_RATIO = 0.10f
}

/** 第一道闸产物：过滤后的可疑页 + 低质量放行的图页→该页幸存图数（parseNote 与入队标记消费） */
data class FigureGateResult(
    val filtered: List<Int>,
    val bypassed: Map<Int, Int>,
)

/**
 * 视觉转写第一道闸（计划案 §3-C，P1-2 方案 B「含图页不转写」源头过滤）：
 * 幸存图所在页从视觉转写选页中排除——整页替换会重排段落，`ordAfterPara` 锚定即失效。
 * 例外（低质量防御分支，P1-3）：图页文字层烂（rawChars < 100 或 pua > 10%）时排除的
 * 代价大于锚定偏移 → 放行转写并记入 [FigureGateResult.bypassed]；第二道闸按同一标记
 * （vision_queue.lowQuality）放行。applyVision 选页与 confirmImport 入队两个入口统一走
 * 本函数；VisionWorker 消费兜底闸是独立防线（§3-C 5b）。
 */
fun excludeFigurePages(
    selected: List<Int>,
    pages: List<PageOut>,
    figurePageNos: Set<Int>,
    figureCountByPage: Map<Int, Int> = emptyMap(),
): FigureGateResult {
    if (figurePageNos.isEmpty()) return FigureGateResult(selected, emptyMap())
    val byNo = pages.associateBy { it.pageNum }
    val filtered = mutableListOf<Int>()
    val bypassed = linkedMapOf<Int, Int>()
    for (pageNo in selected) {
        if (pageNo !in figurePageNos) {
            filtered += pageNo
            continue
        }
        val page = byNo[pageNo]
        if (page != null && isLowQualityPage(page)) {
            bypassed[pageNo] = figureCountByPage[pageNo] ?: 0
            filtered += pageNo
        }
        // 非低质量图页：静默排除（上游是优化，下游拒绝是保命，无 parseNote 义务）
    }
    return FigureGateResult(filtered, bypassed)
}

/** 低质量页判据（计划案 §3-C P1-3 双判据，常量见 [PdfFigureThresholds]） */
fun isLowQualityPage(page: PageOut): Boolean =
    page.rawChars < PdfFigureThresholds.LOW_RAW_CHARS ||
        (page.rawChars > 0 && page.puaCount > page.rawChars * PdfFigureThresholds.LOW_PUA_RATIO)

/** 折算后显示空间 bbox（pt）：x/y 为左上角，w/h 为宽高（y 自顶向下） */
data class DispBbox(val x: Float, val y: Float, val w: Float, val h: Float)

/** R3 输入：同一图在各页的归一化位置（x0/y0 相对显示空间页宽高，w 相对页宽） */
data class NormPos(val nx0: Float, val ny0: Float, val nw: Float)

/** R1 整页扫描底图：面积占比 ≥85%，或（宽 ≥90% 页宽 且 高 ≥90% 页高） */
fun isR1FullPageBleed(b: DispBbox, pageW: Float, pageH: Float): Boolean {
    if (pageW <= 0f || pageH <= 0f || b.w < 0f || b.h < 0f) return false
    if (b.w * b.h / (pageW * pageH) >= PdfFigureThresholds.R1_AREA_RATIO) return true
    return b.w / pageW >= PdfFigureThresholds.R1_EDGE_RATIO &&
        b.h / pageH >= PdfFigureThresholds.R1_EDGE_RATIO
}

/** R2 装饰元素：宽或高 <40pt，或面积 <2% 页面积 */
fun isR2Decoration(b: DispBbox, pageW: Float, pageH: Float): Boolean {
    if (pageW <= 0f || pageH <= 0f) return false
    if (b.w < PdfFigureThresholds.R2_MIN_EDGE_PT || b.h < PdfFigureThresholds.R2_MIN_EDGE_PT) return true
    return b.w * b.h < PdfFigureThresholds.R2_AREA_RATIO * pageW * pageH
}

/**
 * R3 页眉/水印同位重复：同一图出现在 ≥3 页且各页归一化位置两两偏差 ≤2%。
 * 内容型兜底（不同 xref 同字节）由调用方按 xref/rawMd5 分组后传入本函数，分组语义在此之外。
 * 位置不同的合法重复（如书末插图目录）不命中。
 */
fun isR3RepeatingHeader(normPositions: List<NormPos>): Boolean {
    if (normPositions.size < PdfFigureThresholds.R3_MIN_PAGES) return false
    val tol = PdfFigureThresholds.R3_POS_TOLERANCE
    val base = normPositions.first()
    return normPositions.all {
        abs(it.nx0 - base.nx0) <= tol &&
            abs(it.ny0 - base.ny0) <= tol &&
            abs(it.nw - base.nw) <= tol
    }
}
