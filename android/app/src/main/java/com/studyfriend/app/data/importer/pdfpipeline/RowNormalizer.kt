package com.studyfriend.app.data.importer.pdfpipeline

/**
 * 行内 span 归一化（OPT-G P1）：把一次行分隔内收集的 span 序列重估字号、
 * 按传递式聚类分组、组内四分支缝合，产出 1..n 条 PLine。
 *
 * 与论文神器原算法的架构差异：pdfbox 已按内容流行分隔（writeLineSeparator），
 * 无页级行碎片可聚；碎片问题在行内 span 粒度（上标/引注/标点乱值），
 * 故聚类从「页级行列表」下沉到「行内 span 组」。
 *
 * 算法出处：论文神器经验帖 §5 robust_line_size（剔除线 0.55×max + 字符加权）、
 * §6.4 传递式聚类（容差 0.5×字号+1.0pt，与组内任一成员比）+ 缝合四分支。
 * wide_gap 分支（宽缝专名，需栏沟检测）与多栏不覆盖——本项目语料为单栏，
 * 边界声明见 docs/模块总览。
 *
 * 纯 JVM：日志经注入回调（默认丢弃），Android 侧在 LineCollector 传 Log.w；
 * 失败由调用方回退现行为。
 */
object RowNormalizer {

    /** 剔除线系数：span 字号 < 0.55×行最大字号 视为标点乱值（正常上标 ≈0.7×，不误剔） */
    private const val OUTLIER_RATIO = 0.55f

    /** 聚类容差 = 0.5×字号 + 1.0pt（绝对余量 1.0pt 吸收同行碎片 y 抖动） */
    private const val TOL_ABS = 1.0f

    /** 一次行分隔内收集的 span 信息（与 TextPosition 一一对应的折算值） */
    data class SpanInfo(
        val text: String,
        val x0: Float,
        val x1: Float,
        val y0: Float,
        val size: Float,
    )

    fun normalize(spans: List<SpanInfo>, log: (String) -> Unit = {}): List<PLine> {
        val valid = spans.filter { it.text.isNotBlank() }
        if (valid.isEmpty()) return emptyList()
        // 无位置信息的行（坐标全 −1）：几何中性，原样单行输出（现行为）
        if (valid.any { it.x0 < 0 || it.y0 < 0 }) {
            return listOf(
                PLine(
                    text = valid.joinToString("") { it.text }.trim(),
                    x0 = -1f, x1 = -1f, y0 = -1f, size = -1f,
                ),
            )
        }

        // 步骤 1：行级 robust size 重估（仅用于容差与组字号兜底；
        // 被剔除的乱值 span 仍参与步骤 2 聚类与步骤 3 拼接，不丢字）
        val refSize = robustSize(valid)

        // 步骤 2：传递式聚类——新 span 与组内任一成员 y0 距离 ≤ 容差即收入
        val tol = 0.5f * refSize + TOL_ABS
        val sorted = valid.sortedWith(compareBy({ it.y0 }, { it.x0 }))
        val groups = mutableListOf<MutableList<SpanInfo>>(mutableListOf(sorted[0]))
        for (s in sorted.drop(1)) {
            val cur = groups.last()
            val near = cur.any { g -> kotlin.math.abs(g.y0 - s.y0) <= tol }
            if (near) cur.add(s) else groups.add(mutableListOf(s))
        }

        // 步骤 3：组内 x 排序 + 四分支缝合
        return groups.map { g -> stitch(g.sortedBy { it.x0 }, refSize, log) }
    }

    /** 行级字号重估：剔除 <0.55×max 的乱值 span，按字符数加权平均；全被剔退回 max */
    private fun robustSize(spans: List<SpanInfo>): Float {
        val max = spans.maxOf { it.size }
        if (max <= 0f) return -1f
        val kept = spans.filter { it.size >= max * OUTLIER_RATIO && it.size > 0f }
        if (kept.isEmpty()) return max
        val weight = kept.sumOf { it.text.length.coerceAtLeast(1).toDouble() }
        if (weight == 0.0) return max
        return kept.sumOf { it.size.toDouble() * it.text.length.coerceAtLeast(1) }
            .toFloat() / weight.toFloat()
    }

    /** 组内拼接：按 x 序依次并入已拼 bbox；四分支都不中也拼（组内不丢字），记日志 */
    private fun stitch(group: List<SpanInfo>, refSize: Float, log: (String) -> Unit): PLine {
        val size = groupSize(group, refSize)
        val refY = groupY(group)
        var text = group[0].text
        var x0 = group[0].x0
        var x1 = group[0].x1
        for (s in group.drop(1)) {
            val gap = s.x0 - x1
            val sizeRatio = if (refSize > 0 && s.size > 0) s.size / refSize else 1f
            val dy = kotlin.math.abs(s.y0 - refY)
            val contained = s.x0 >= x0 - 2f && s.x1 <= x1 + 2f
            val overlapped = gap >= -1.5f * refSize && gap < 0f
            val adjacent = gap >= 0f && gap <= 0.8f * refSize && sizeRatio >= 0.9f
            val nearNeighbor = dy <= 0.45f * refSize && gap <= 0.8f * refSize
            if (!(contained || overlapped || adjacent || nearNeighbor)) {
                log("stitch fallback: gap=$gap dy=$dy size=$size text=${s.text.take(12)}")
            }
            text += s.text
            x0 = minOf(x0, s.x0)
            x1 = maxOf(x1, s.x1)
        }
        return PLine(text.trim(), x0, x1, groupMinY(group), size)
    }

    /** 组字号：≥剔除线的 span 字符加权平均；全被剔（纯乱值组）退回行 refSize */
    private fun groupSize(group: List<SpanInfo>, refSize: Float): Float {
        val max = group.maxOf { it.size }
        if (max <= 0f) return refSize
        val kept = group.filter { it.size >= max * OUTLIER_RATIO && it.size > 0f }
        if (kept.isEmpty()) return refSize
        val weight = kept.sumOf { it.text.length.coerceAtLeast(1).toDouble() }
        if (weight == 0.0) return refSize
        return kept.sumOf { it.size.toDouble() * it.text.length.coerceAtLeast(1) }
            .toFloat() / weight.toFloat()
    }

    /** 组主行 y：取字符数最多 span 的 y0（参考基线，防越并越偏） */
    private fun groupY(group: List<SpanInfo>): Float =
        group.maxByOrNull { it.text.length }?.y0 ?: group[0].y0

    private fun groupMinY(group: List<SpanInfo>): Float = group.minOf { it.y0 }
}
