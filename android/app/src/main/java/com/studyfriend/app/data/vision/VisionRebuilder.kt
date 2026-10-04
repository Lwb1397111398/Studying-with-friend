package com.studyfriend.app.data.vision

import android.content.Context
import com.studyfriend.app.data.BookRepository
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.importer.BookParser
import com.studyfriend.app.data.importer.PdfLoader
import com.studyfriend.app.data.importer.pdfpipeline.Para
import com.studyfriend.app.data.importer.pdfpipeline.joinTexts

/**
 * 守卫式整书重建（OPT-F）：后台队列全部页到终态后，把已转写内容应用到书。
 *
 * 为什么是"整书重建"而不是"逐页替换入库段落"：段落在库里按章连续编号，
 * 视觉转写会改变页面内的段落划分，页→段落映射在替换过程中会错位，而粗读
 * 标注（aiAction）挂在段落行上，错位即污染学习数据。重建走"规则管线重跑 +
 * 缓存回填"，转写结果从落盘缓存读，不再调模型、零费用。
 *
 * 守卫（宁可白跑不可毁数据）：该书存在任何 AI 消费（粗读标注/讲解笔记/章末
 * 资产/复习排期任一）→ 不重建，保持文字层内容；用户可重新导入应用增强
 * （缓存命中秒级，同步替换路径不受守卫限制）。
 */
object VisionRebuilder {

    suspend fun rebuildIfSafe(db: StudyDatabase, context: Context, bookId: Long): Boolean {
        val dao = db.visionQueueDao()
        val queue = dao.byBook(bookId)
        if (queue.isEmpty()) return false
        if (dao.hasAnyAiConsumption(bookId)) return false
        val doneItems = queue.filter { it.status == DbValues.VQ_DONE }
        if (doneItems.isEmpty()) return false

        val uri = queue.first().uri
        // 重跑规则管线（只读文字层，幂等）：不接 isCancelled——CPU 循环不响应取消，
        // Worker 被 stop 后重跑一次即可，转写部分有缓存保护不重复烧钱
        val result = try {
            PdfLoader.extract(
                context,
                android.net.Uri.parse(uri),
                allowScanned = true, // 已是导入成功的书，不再按"无文字层"拦
            )
        } catch (e: Exception) {
            return false
        }

        var replaced = 0
        for (item in doneItems) {
            val t = VisionCache.read(context, item.uri, item.pageNo) ?: continue
            val page = result.pages.firstOrNull { it.pageNum == item.pageNo } ?: continue
            page.paras.clear()
            page.paras.addAll(t.body.map { Para(it) })
            if (t.footnotes.isNotEmpty()) {
                page.paras.add(Para(joinTexts(t.footnotes), footnote = true))
            }
            replaced++
        }
        if (replaced == 0) return false

        val chapters = try {
            BookParser.parse(result.assembleText())
        } catch (e: Exception) {
            return false
        }
        if (chapters.isEmpty() || chapters.all { it.paras.isEmpty() }) return false

        val book = db.bookDao().get(bookId) ?: return false
        val now = System.currentTimeMillis()
        val pairs = chapters.map { ch ->
            // 章内容已替换，旧阅读进度位置不可靠：全部回未读（守卫保证没有学习资产损失）
            ChapterEntity(
                bookId = bookId, idx = 0, title = ch.title,
                readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
            ) to ch.paras.map { p ->
                // pageNo 保留（P4 起被 figures 重挂消费：replaceBookContent 按每章首段
                // pageNo 推章起点区间；此前丢弃会导致重挂后书无法再校准、图归属无据）
                ParagraphEntity(chapterId = 0, idx = 0, text = p.text, role = p.role, pageNo = p.pageNo)
            }
        }
        BookRepository(db).replaceBookContent(
            bookId,
            book.copy(updatedAt = now),
            pairs,
        )
        return true
    }
}
