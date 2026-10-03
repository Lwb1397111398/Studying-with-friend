package com.studyfriend.app.data.importer.pdfpipeline

/**
 * 目录探针页区段化取样（P3b-2 §3.4）：tocLike 页升序按「相邻间隔 >5 页」聚类分组，
 * 段内全取，全组上限 8 页。修复多册/多篇书目录区段未覆盖问题（mzzz 实况：上册目录
 * p28-31、下册 p557+，P3b-1 固定取前 4 页只覆盖上册，下册目录条目缺失导致第九章
 * 无法校准）。
 *
 * 段内全取的依据（v1.2 修正）：v1.1「每段取前 2 页」被真书证伪——bddl 目录占 3 页，
 * 只探前 2 页漏掉尾页 12 条（第八、九章等 4 个 level1），校准后八九章仍并入第七章；
 * mzzz 上册目录占 4 页，漏掉一半。目录页数不受控（1-8 页都有可能），取样页数不能
 * 拍脑袋定死，护栏交给 MAX_PAGES 总量上限承担。
 *
 * 阈值依据：两本真书实测分布——同册目录页相邻间隔 ≤3 页、册间间隔 >500 页，
 * 间隔阈值取 5 对两类间隔均有数量级余量（不敏感区）；扩大书目时按分布复核。
 *
 * 上限语义：总名额按区段顺序分配，装不下一个完整区段（组内页数）时整段丢弃——
 * 部分取样会让该区段样本残缺，区段级全有全无下残缺样本无法做多锚互差校验，
 * 不如保前段完整。两真书实测需求 3+3=6 页 ≤8；单册目录超 8 页的长目录书拿不到
 * 完整目录（记 P3b-3 扩书目时复核）。
 */
object TocPageGrouper {
    /** 相邻页间隔超过该值视为不同目录区段（册/篇边界） */
    const val GAP_THRESHOLD = 5

    /** 全组页数上限：视觉调用次数护栏（8 页 × 60s readTimeout = 480s 总预算口径） */
    const val MAX_PAGES = 8

    fun group(tocLikePages: List<Int>): List<List<Int>> {
        val sorted = tocLikePages.sorted()
        if (sorted.isEmpty()) return emptyList()
        val segments = mutableListOf<List<Int>>()
        var current = mutableListOf(sorted.first())
        for (p in sorted.drop(1)) {
            if (p - current.last() > GAP_THRESHOLD) {
                segments += current
                current = mutableListOf(p)
            } else {
                current += p
            }
        }
        segments += current
        var used = 0
        val result = mutableListOf<List<Int>>()
        for (seg in segments) {
            if (used + seg.size > MAX_PAGES) break
            result += seg
            used += seg.size
        }
        return result
    }
}
