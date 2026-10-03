package com.studyfriend.app.ui.screens

import com.studyfriend.app.data.db.ChapterTreeRow
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParagraphEntity

/** 章树节点（P5 计划案 §2-A）：章行 + 挂接其下的节行（节行=目录锚，见 §2-B） */
data class ChapterNode(val row: ChapterTreeRow, val sections: List<ChapterTreeRow>)

/** 树→LazyColumn 拍平 item；key 统一用 [ChapterTreeRow.id]（chapters 主键，章+节天然唯一） */
sealed class TreeItem {
    abstract val row: ChapterTreeRow
    data class Chapter(override val row: ChapterTreeRow) : TreeItem()
    data class Section(override val row: ChapterTreeRow, val parentChapterId: Long) : TreeItem()
}

private const val LEVEL_SECTION = 2

/**
 * 章树构建（P5 计划案 §2-A）：chapters 全行按 idx 升序，level1 为章，level2 按
 * parentOrder（父章在 level1 序列中的 1-based 序号，TocChapterCalibrator 节挂接语义）
 * 挂到父章下。防御分支（不丢行，落点经 [onFallback] 上报——生产传 Log.w，测试断言被调；
 * P3b-2 数据写入 bug 不得被静默掩盖）：
 * - parentOrder 越界/null → 挂 idx 前最近的 level1 章，前面无章挂首章；
 * - 全书无 level1 行 → 全部 level2 行扁平各自成节点（不挂错、不丢行）。
 * 纯函数无 Android 依赖，ChapterTreeTest 直测。
 */
fun buildChapterTree(
    rows: List<ChapterTreeRow>,
    onFallback: (String) -> Unit = {},
): List<ChapterNode> {
    val sorted = rows.sortedBy { it.idx }
    val chapters = sorted.filter { it.level != LEVEL_SECTION }
    val sections = sorted.filter { it.level == LEVEL_SECTION }
    if (chapters.isEmpty()) {
        if (sections.isNotEmpty()) {
            onFallback("buildChapterTree: 无任何章行，${sections.size} 个节行扁平自成树")
        }
        return sections.map { ChapterNode(it, emptyList()) }
    }
    // 先解析每个节行的宿主章 id，再一次性组装（ChapterNode.sections 不可变，避免中途改嵌套）
    val hostBySection = HashMap<Long, Long>() // 节行 id → 宿主章 id
    for (s in sections) {
        val po = s.parentOrder
        val host = if (po != null && po in 1..chapters.size) {
            chapters[po - 1]
        } else {
            val nearest = chapters.lastOrNull { it.idx < s.idx }
            val fallback = nearest ?: chapters.first()
            onFallback(
                "buildChapterTree: parentOrder=$po 越界，节「${s.title}」" +
                    if (nearest != null) "挂前最近章 idx=${fallback.idx}" else "前无章挂首章 idx=${fallback.idx}",
            )
            fallback
        }
        hostBySection[s.id] = host.id
    }
    return chapters.map { ch ->
        ChapterNode(ch, sections.filter { hostBySection[it.id] == ch.id })
    }
}

/** 树→拍平（P5 计划案 §2-B）：章按 [expandedChapterIds] 决定是否跟自己的节行 */
fun flattenTree(nodes: List<ChapterNode>, expandedChapterIds: Set<Long>): List<TreeItem> =
    buildList {
        for (n in nodes) {
            add(TreeItem.Chapter(n.row))
            if (n.row.id in expandedChapterIds) {
                for (s in n.sections) add(TreeItem.Section(s, n.row.id))
            }
        }
    }

/** highlight 归一：剥全部空白（含全角 U+3000，Kotlin \s 不含它须显式写）与〔标题〕标记前缀 */
internal fun normalizeHighlight(s: String): String =
    Regex("[\\s\\u3000]+").replace(s, "").removePrefix("〔标题〕")

/**
 * 在章的段落流中定位节标题段（P5 §2-B highlight 导航）：归一化后精确相等优先，
 * 零命中退化 startsWith（多命中取文本最短——节标题段通常远短于正文段，评审 P2-1），
 * 再零命中返回 null（调用方不滚动）。匹配域排除 TOC/FOOTNOTE 段：正文 TOC 区常有
 * 同名目录条目段，会误滚到目录区（评审 P2-3）。纯函数，ChapterTreeTest 直测。
 */
fun findHighlightIndex(paras: List<ParagraphEntity>, highlight: String): Int? {
    val key = normalizeHighlight(highlight)
    if (key.isEmpty()) return null
    val candidates = paras.withIndex()
        .filter { it.value.role != DbValues.ROLE_TOC && it.value.role != DbValues.ROLE_FOOTNOTE }
        .map { it.index to normalizeHighlight(it.value.text) }
        .filter { it.second.isNotEmpty() }
    candidates.firstOrNull { it.second == key }?.let { return it.first }
    return candidates.filter { it.second.startsWith(key) }
        .minByOrNull { it.second.length }
        ?.first
}
