package com.studyfriend.app.data.export

import com.studyfriend.app.data.db.BookEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParaMemoEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.study.ParaIdsCodec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 读书笔记导出（实景体验轮第六波，模板字段设计借鉴 readest「Export notes」思路）：
 * 把一本书的 AI 讲解与我的批注按「章 → 讲解 → 批注」拼成 Markdown 文件，经系统
 * 分享面板发微信/存网盘/进 Obsidian——法学生的「AI 陪读产出」离开 App 也能用的唯一出口。
 * 纯本地拼接零 AI 调用；纯字符串模板不引第三方库。
 */
internal object NotesExporter {

    /** 结果：生成与否 + 文件路径 + 统计文案（书架长按菜单 Toast/分享用） */
    data class Result(val hasContent: Boolean, val file: File?, val summary: String)

    /**
     * 生成全书 Markdown。无任何讲解与批注时返回 hasContent=false（不生成空文件）。
     * 文件落在 cacheDir/exports/（FileProvider 已开放该路径，随系统清理）。
     */
    suspend fun build(db: StudyDatabase, book: BookEntity, cacheDir: File): Result {
        val chapters = db.chapterDao().byBook(book.id).filter { it.level == 1 }.sortedBy { it.idx }
        var noteCount = 0
        var memoCount = 0
        val sb = StringBuilder()

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sb.append("# 《").append(book.title).append("》读书笔记\n\n")
        sb.append("> 导出时间 ").append(fmt.format(Date())).append(" · 生成自「读书搭子」\n\n")

        for (ch in chapters) {
            val notes = db.paraNoteDao().byChapterOnce(ch.id)
            val memos = db.paraMemoDao().byChapterOnce(ch.id)
            if (notes.isEmpty() && memos.isEmpty()) continue

            // 章序号 1-based：导出是给人和笔记软件看的（App 内部 idx 0-based，章目录页
            // 显示原值；这里按阅读惯例从 1 起，顺序与章序一致）
            sb.append("## ").append(ch.idx + 1).append(". ").append(ch.title).append("\n\n")

            // 段 idx → 原文（讲解卡引用原文摘录用；讲解按章一次查询建映射）
            val paraTextByIdx: Map<Int, String> = if (notes.isNotEmpty()) {
                val paras = db.paragraphDao().byChapter(ch.id)
                paras.associate { it.idx to it.text }
            } else {
                emptyMap()
            }

            if (notes.isNotEmpty()) {
                sb.append("### 讲解\n\n")
                for (n in notes) {
                    noteCount++
                    sb.append("**").append(n.title).append("**\n\n")
                    if (!n.memoryHook.isNullOrBlank()) {
                        sb.append("> 记忆锚点：").append(n.memoryHook).append("\n\n")
                    }
                    val members = ParaIdsCodec.decode(n.paraIds)
                    val quote = members.mapNotNull { paraTextByIdx[it.toInt()] }
                        .joinToString(" ") { it.trim() }
                        .take(160)
                    if (quote.isNotBlank()) {
                        sb.append("> 原文：").append(quote).append(if (paraTextByIdx.isNotEmpty() && quote.length >= 160) "…" else "").append("\n\n")
                    }
                    sb.append(n.friendly.trim()).append("\n\n")
                    if (!n.analogy.isNullOrBlank()) {
                        sb.append("*类比：").append(n.analogy.trim()).append("*\n\n")
                    }
                }
            }

            if (memos.isNotEmpty()) {
                sb.append("### 我的批注\n\n")
                for (m in memos) {
                    memoCount++
                    val t = fmt.format(Date(m.createdAt))
                    if (m.quote.isNotBlank()) {
                        sb.append("> ").append(m.quote.replace("\n", "\n> ")).append("\n\n")
                    }
                    sb.append(m.text.trim()).append("  \n").append("*(").append(t).append(")*\n\n")
                }
            }
        }

        if (noteCount == 0 && memoCount == 0) {
            return Result(false, null, "这本书还没有讲解或批注")
        }
        val body = sb.toString().trim() + "\n"
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "读书笔记_${sanitize(book.title)}.md")
        file.writeText(body)
        return Result(true, file, "讲解 $noteCount 条 · 批注 $memoCount 条")
    }

    /** Windows/Android 文件名非法字符清洗（书名直接来自用户输入/导入，兜底防崩溃） */
    internal fun sanitize(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(60).ifBlank { "书" }
}
