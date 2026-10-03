package com.studyfriend.app.data.importer.pdfpipeline

/**
 * 目录探针页区段化取样（P3b-2 §3.4）：tocLike 页升序按「相邻间隔 >5 页」聚类分组，
 * 每组取前 2 页，全组上限 8 页。修复多册/多篇书目录区段未覆盖问题（mzzz 实况：
 * 上册目录 p28-30 一组、下册 p557+ 一组，P3b-1 固定取前 4 页只覆盖上册，下册
 * 目录条目缺失导致第九章无法校准）。
 *
 * 阈值依据：两本真书实测分布——同册目录页相邻间隔 ≤3 页、册间间隔 >500 页，
 * 间隔阈值取 5 对两类间隔均有数量级余量（不敏感区）；扩大书目时按分布复核。
 *
 * 上限语义：总名额按区段顺序分配，装不下一个完整区段（组内取样页数）时整段
 * 丢弃——部分取样会让该区段只剩 1 页样本，区段级全有全无下 1 页失败即全弃，
 * 且单页样本无法做多锚互差校验，不如保前段完整。
 */
object TocPageGrouper {
    /** 相邻页间隔超过该值视为不同目录区段（册/篇边界） */
    const val GAP_THRESHOLD = 5

    /** 每区段取样页数：目录首页条目密度最高，2 页足够覆盖一册目录 */
    const val PER_SEGMENT = 2

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
            val pick = seg.take(PER_SEGMENT)
            if (used + pick.size > MAX_PAGES) break
            result += pick
            used += pick.size
        }
        return result
    }
}
