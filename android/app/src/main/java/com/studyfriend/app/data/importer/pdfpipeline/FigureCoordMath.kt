package com.studyfriend.app.data.importer.pdfpipeline

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** PDF 用户空间矩形（pt）：y 向上、原点在 mediaBox 左下（可能非 (0,0)） */
data class PdfRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/**
 * P4 坐标数学：用户空间→显示空间折算、CTM 方向检测、段级锚定、章归属。
 * 全部纯函数，JVM 可测（不依赖 android.graphics；仿射产物 CtmAffine 由提取器在
 * Android 侧经 Matrix.setValues 消费）。
 */
object FigureCoordMath {

    /**
     * 用户空间 bbox → 显示空间 bbox（P1-1 主场景：mfzz 82% 页 /Rotate 90）。
     * mediaBox 原点非 (0,0) 先平移到原点；/Rotate 为显示时的顺时针旋转角；
     * 对 bbox 四顶点各自变换后取 min/max——旋转下轴对齐外接框不能只变两角。
     * 折算口径与 PDFTextStripper 的 DirAdj 显示坐标对齐（J1 单测 + J9 fixture 锁定）。
     */
    fun rotateRect(rotation: Int, rect: PdfRect, mediaBox: PdfRect): DispBbox {
        val mbW = mediaBox.x1 - mediaBox.x0
        val mbH = mediaBox.y1 - mediaBox.y0
        fun toDisp(ux: Float, uy: Float): Pair<Float, Float> {
            val rx = ux - mediaBox.x0
            val ry = uy - mediaBox.y0
            return when (((rotation % 360) + 360) % 360) {
                90 -> Pair(ry, rx) // 顺时针 90°：原左边→顶边（dispY 跟 rx），原底边→左边（dispX 跟 ry）
                180 -> Pair(mbW - rx, mbH - ry)
                270 -> Pair(mbH - ry, mbW - rx) // 逆时针 90°：原左边→底边、原底边→右边
                else -> Pair(rx, mbH - ry) // 0°：仅 y 翻转
            }
        }
        val pts = listOf(
            toDisp(rect.x0, rect.y0),
            toDisp(rect.x1, rect.y0),
            toDisp(rect.x0, rect.y1),
            toDisp(rect.x1, rect.y1),
        )
        val minX = pts.minOf { it.first }
        val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }
        val maxY = pts.maxOf { it.second }
        return DispBbox(minX, minY, maxX - minX, maxY - minY)
    }

    /**
     * CTM 方向归一化产物（90° 族旋转/翻转，可带均匀缩放）。
     * Android Matrix.setValues 行序语义：x' = scaleX·x + skewX·y + transX。
     * PDF 矩阵行主序 [a b; c d]（x' = a·x + c·y + e）→ scaleX=a、skewX=c、skewY=b、scaleY=d。
     */
    data class CtmAffine(
        val scaleX: Float,
        val skewX: Float,
        val transX: Float,
        val skewY: Float,
        val scaleY: Float,
        val transY: Float,
        /** 行列式 <0：含镜像翻转（如 -1 缩放），转正时一并处理 */
        val flip: Boolean,
    )

    /**
     * CTM → 方向仿射（计划案 v1.8，r8-P2-8）：90° 倍数旋转（可带翻转/均匀缩放）→ 归一化
     * 仿射，阶段 2 编码前据此把像素转正；斜切/非 90° 旋转 → null，
     * 走「按原始方向展示 + parseNote 提示」分支。
     * [srcW]/[srcH] 提供时均匀性按「pt/px」判定（n1 对源 x 轴、n2 对源 y 轴）——
     * 显示 bbox 长宽比 ≠ 像素长宽比的拉伸显示图（pt 非均匀、像素均匀）照常转正；
     * 缺省时按 pt 均匀判定（J1 既有用例口径）。
     */
    fun ctmToAffine(
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        e: Float,
        f: Float,
        srcW: Int? = null,
        srcH: Int? = null,
    ): CtmAffine? {
        val n1 = sqrt(a * a + b * b)
        val n2 = sqrt(c * c + d * d)
        if (n1 < 1e-6f || n2 < 1e-6f) return null
        // 正交（轴点积≈0）
        if (abs(a * c + b * d) > 1e-3f * n1 * n2) return null
        // 均匀缩放：像素口径（有源尺寸）优先——同一旋转图各像素轴缩放一致即转正安全
        val uniformOk = if (srcW != null && srcH != null && srcW > 0 && srcH > 0) {
            val px1 = n1 / srcW
            val px2 = n2 / srcH
            abs(px1 - px2) <= 1e-3f * max(px1, px2)
        } else {
            abs(n1 - n2) <= 1e-3f * max(n1, n2)
        }
        if (!uniformOk) return null
        val ux = a / n1
        val uy = b / n1
        val vx = c / n2
        val vy = d / n2
        // 归一化后必须轴对齐（元素 ∈ {0,±1}）——防 45° 等斜旋转（正交但非 90° 族）混入
        if (abs(abs(ux) + abs(uy) - 1f) > 1e-3f || abs(abs(vx) + abs(vy) - 1f) > 1e-3f) return null
        fun snap(v: Float) = when {
            abs(v) < 0.5f -> 0f
            v > 0f -> 1f
            else -> -1f
        }
        val scale = n1
        return CtmAffine(
            scaleX = snap(ux) * scale,
            skewX = snap(vx) * scale,
            transX = e,
            skewY = snap(uy) * scale,
            scaleY = snap(vy) * scale,
            transY = f,
            flip = a * d - b * c < 0f,
        )
    }

    /**
     * 段级锚定（计划案 §3-B）：ordAfterPara = 该页全量段列表中最后一个 y0 ≤ 图顶 y 的
     * 段页内序号（0-based）；无任何段在图上方 → -1（页首前）。
     * paraY0s 须按页内文档序（y 递增）排列，且取 crossPageMerge 之后的列表
     * （与 ReadScreen 渲染口径一致，r9-P1-1）。
     */
    fun anchorFigure(figureY0: Float, paraY0s: List<Float>): Int {
        var ord = -1
        for (i in paraY0s.indices) {
            if (paraY0s[i] <= figureY0) ord = i
        }
        return ord
    }

    /**
     * 章归属区间映射（计划案 §3-C/D 单一时点共用）：页号 →「起点 ≤ 页号取最大者」的章下标。
     * 早于首章 → 0、晚于末章 → 末章（clamp 两端，图不丢弃）；空章表 → -1（调用方跳过）。
     * chapterStartPages 须按章序递增排列（调用方排序，与段落重排同一规则）。
     */
    fun assignChapter(pageNo: Int, chapterStartPages: List<Int>): Int {
        if (chapterStartPages.isEmpty()) return -1
        var idx = 0
        for (i in chapterStartPages.indices) {
            if (chapterStartPages[i] <= pageNo) idx = i else break
        }
        return idx
    }
}
