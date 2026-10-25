package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ChapterTermsRow

// 术语卡盒（学习闭环轮）：全书术语按书分组的组装纯函数（复习中心「术语」Tab 消费）

/** 一章的术语卡：章名 + 该章 keyTerms（粗读时生成：术语 + 大白话解释） */
data class TermChapter(val chapterTitle: String, val terms: List<KeyTermsCodec.TermPlain>)

/** 一本书的术语组：书名 + 各章术语卡（按章序） */
data class TermBookGroup(val bookTitle: String, val chapters: List<TermChapter>)

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
    return groups.map { (bookId, chapters) -> TermBookGroup(titles.getValue(bookId), chapters) }
}
