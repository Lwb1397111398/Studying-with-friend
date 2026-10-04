package com.studyfriend.app.data.importer.pdfpipeline

/**
 * P4 parseNote 双层文案（计划案 §3-B 产出段，v1.8 r8-P2-4）：主文案面向用户
 * （TocConfirmScreen 默认显示），技术明细收进详情展开区 +Logcat。纯函数无 Android
 * 依赖，JVM 单测锁定（J5 文案对账）。
 *
 * 漏抽不静默（r6-P2-1b）：errored/erroredPerm/erroredOversize/droppedFormDepth 任一 >0
 * 都必须出现在详情里。
 */
object FigureParseNote {

    private const val INLINE_RATIO_WARN = 0.20f
    private const val LANDSCAPE_WARN = 0.30f
    private const val PAGE_LIST_MAX = 5

    /**
     * 主文案；null=无图无提示（无图书 parseNote 零影响，J5）。返回值不含详情。
     * [bypassed]：低质量放行页 → 该页幸存图数（第一道闸 [excludeFigurePages] 产物）。
     */
    fun main(stats: FigureExtractorStats?, bypassed: Map<Int, Int>): String? {
        if (stats == null) return null
        if (stats.fatalError) return "图片提取异常，本次仅导入文字内容"
        if (stats.permDenied) return "该书限制图片提取，未提取示意图"
        if (stats.totalObjects == 0) return null
        val head = "检测到 ${stats.totalObjects} 张图，保留 ${stats.kept} 张示意图"
        val pages = formatPages(stats.keptPages)
        val dropped = stats.droppedR1 + stats.droppedR2 + stats.droppedR3Xref + stats.droppedR3Content
        val body = buildString {
            append(head)
            if (pages.isNotEmpty()) append("（$pages）")
            append("，已自动跳过 $dropped 张背景/装饰图")
        }
        val lowQuality = bypassed.entries.joinToString("；") { (pageNo, m) ->
            "p$pageNo 的 $m 张示意图因文字层质量低可能错位"
        }
        return if (lowQuality.isEmpty()) body else "$body；$lowQuality"
    }

    /** 技术明细（详情展开区）；null=无可展开内容 */
    fun detail(stats: FigureExtractorStats?, landscapeRatio: Float, doubleColumnSuspicion: Boolean): String? {
        if (stats == null) return null
        val lines = mutableListOf<String>()
        if (stats.totalObjects > 0) {
            lines += "滤除 ${stats.droppedR1} 整页底图 / ${stats.droppedR2} 装饰 / " +
                "${stats.droppedR3Xref + stats.droppedR3Content} 同位页眉"
        }
        if (stats.biPages.isNotEmpty()) {
            val entries = stats.biPages.entries.sortedBy { it.key }
            lines += "内联图（本版未提取）：" + formatPages(entries.map { it.key }, "页") +
                "，共 ${entries.sumOf { it.value }} 处"
        }
        if (stats.inlineImageCount > 0 &&
            stats.totalObjects > 0 &&
            stats.inlineImageCount.toFloat() / stats.totalObjects > INLINE_RATIO_WARN
        ) {
            lines += "⚠ 该书含大量内联图，本版未提取"
        }
        if (stats.nonOrthoCtmPages.isNotEmpty()) {
            lines += "${formatPages(stats.nonOrthoCtmPages, "页")} 含旋转/镜像图，按原始方向展示"
        }
        if (stats.errored > 0) lines += "⚠ 因 CMYK/格式问题跳过 ${stats.errored} 张"
        if (stats.erroredPerm > 0) lines += "⚠ ${stats.erroredPerm} 张图因版权权限限制未提取"
        if (stats.erroredOversize > 0) lines += "⚠ ${stats.erroredOversize} 张图因尺寸过大未提取"
        if (stats.droppedFormDepth > 0) lines += "⚠ ${stats.droppedFormDepth} 张图片因嵌套层级过深未提取"
        if (landscapeRatio > LANDSCAPE_WARN) {
            lines += "⚠ 该书为横排版式，示意图位置可能与正文对不齐，可在目录确认页校准"
        }
        if (doubleColumnSuspicion) {
            lines += "⚠ 该书疑似多栏排版，窄栏示意图过滤结果未经同版式验证书校准"
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** 页号列表格式化：p1、p2…；>[PAGE_LIST_MAX] 个列前 5 加「等 N {unit}」 */
    private fun formatPages(pages: Collection<Int>, unit: String = "张"): String {
        if (pages.isEmpty()) return ""
        val sorted = pages.sorted()
        val head = sorted.take(PAGE_LIST_MAX).joinToString("、") { "p$it" }
        return if (sorted.size > PAGE_LIST_MAX) "$head 等 ${sorted.size} $unit" else head
    }
}
