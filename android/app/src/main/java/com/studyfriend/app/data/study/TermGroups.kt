package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ChapterTermsRow

// 术语卡盒（学习闭环轮）：全书术语按书分组的组装纯函数（复习中心「术语」Tab 消费）

/** 一章的术语卡：章名 + 该章 keyTerms（粗读时生成：术语 + 大白话解释） */
data class TermChapter(val chapterTitle: String, val terms: List<KeyTermsCodec.TermPlain>)

/** 一本书的术语组：书名 + 各章术语卡（按章序） */
data class TermBookGroup(val bookId: Long, val bookTitle: String, val chapters: List<TermChapter>)

/** 批D：跨章去重后的术语（保留首现章与该章解释；首现=书内章序最早） */
data class DedupedTerm(
    val chapterId: Long,
    val chapterTitle: String,
    val term: String,
    val plain: String,
)

/**
 * 术语卡盒组装：decode 每章 keyTermsJson，空章（解析失败/编码为 []）整章剔除，
 * 空书剔除；书序按 bookId（SQL ORDER BY b.id 已保证行序，分组保持首现顺序）。
 */
fun buildTermGroups(rows: List<ChapterTermsRow>): List<TermBookGroup> {
    val groups = LinkedHashMap<Long, MutableList<TermChapter>>()
    val titles = HashMap<Long, String>()
    for (r in rows) {
        val terms = KeyTermsCodec.decode(r.keyTermsJson).filter { it.term.isNotBlank() }
        if (terms.isEmpty()) continue
        titles.putIfAbsent(r.bookId, r.bookTitle)
        groups.getOrPut(r.bookId) { mutableListOf() }.add(TermChapter(r.chapterTitle, terms))
    }
    return groups.map { (bookId, chapters) -> TermBookGroup(bookId, titles.getValue(bookId), chapters) }
}

/**
 * 批D：全书术语跨章去重——同一概念第 3 章和第 8 章各出现一次时只留第 3 章那条
 * （解释取首现章）。归一化 = 去全部空白后等值；章序按传入行序（SQL 已按章 idx）。
 * 供「术语加入复习」入口，重复词不重复排期。
 */
fun dedupeBookTerms(chapters: List<TermChapter>, chapterIds: List<Long>): List<DedupedTerm> {
    require(chapters.size == chapterIds.size) { "章与章 id 数量不一致" }
    val seen = HashSet<String>()
    val out = mutableListOf<DedupedTerm>()
    chapters.forEachIndexed { ci, ch ->
        for (t in ch.terms) {
            val norm = t.term.replace(Regex("\\s+"), "")
            if (!seen.add(norm)) continue
            out.add(DedupedTerm(chapterIds[ci], ch.chapterTitle, t.term, t.plain))
        }
    }
    return out
}
