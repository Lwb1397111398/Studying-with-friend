package com.studyfriend.app.data.vision

import android.content.Context
import android.util.Log
import com.studyfriend.app.data.BookRepository
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.importer.BookParser
import com.studyfriend.app.data.importer.PdfExtractResult
import com.studyfriend.app.data.importer.pdfpipeline.DocStats
import com.studyfriend.app.data.importer.pdfpipeline.PageOut
import com.studyfriend.app.data.importer.pdfpipeline.PageTranscription
import com.studyfriend.app.data.importer.pdfpipeline.Para
import com.studyfriend.app.data.importer.pdfpipeline.joinTexts

/**
 * 守卫式整书重建（OPT-F；P6c C3a 立案后重写底稿来源）：
 * 后台队列全部页到终态后，把已转写内容应用到书。
 *
 * 为什么底稿取自数据库而不是重跑 PdfLoader.extract：extract 只读 PDF 文字层，
 * 扫描书（图像型 PDF——视觉队列的主场景）没有文字层，重跑得到整书空页、
 * 仅队列页有内容，整书替换把 14772 字的书洗成 89 字（C3a 实录 P0）。
 * 库内段落是导入管线（清洗+跨页合并）的最终产物，按页重组等价于重跑底稿，
 * 且不再依赖源文件存活（uri 失效的书也能重建，行为变化登记 M2 总览）。
 *
 * 目录页专条：视觉转写的目录条目无点线，BookParser.isTocBlock（要求 ≥1 条
 * 点线条目）必然不认，不推断 tocLike 会把「第X章 标题 页码」逐条解析成假章
 * （C3a 被洗书的 1 章 9 段即此形态）。推断规则与边界见 [inferTocLike]。
 *
 * 覆盖守卫（宁可白跑不可毁数据）：重建产物字符量 < 预期 80% → 放弃重建。
 * 预期=现书字符 − 被替换页原字符 + 替换页视觉字符（只对比「没被换的页必须
 * 活下来」，不拦单页视觉文本天然偏短——原 0.8 整书比会误杀单页全替换的合法重建）。
 *
 * 原有守卫（保留）：该书存在任何 AI 消费（粗读标注/讲解笔记/章末资产/复习
 * 排期任一）→ 不重建；用户可重新导入应用增强（缓存命中秒级）。
 */
object VisionRebuilder {

    private const val TAG = "VisionRebuilder"

    suspend fun rebuildIfSafe(db: StudyDatabase, context: Context, bookId: Long): Boolean {
        val dao = db.visionQueueDao()
        val queue = dao.byBook(bookId)
        if (queue.isEmpty()) return false
        if (dao.hasAnyAiConsumption(bookId)) return false
        val doneItems = queue.filter { it.status == DbValues.VQ_DONE }
        if (doneItems.isEmpty()) return false

        // 底稿：库内段落按页重组。TXT 书 pageNo 为空、视觉队列只来自 PDF 导入——
        // 出现空页号说明数据形态不符预期，不重建（宁可白跑）。
        val rows = db.paragraphDao().byBookForRebuild(bookId)
        if (rows.isEmpty() || rows.any { it.pageNo == null }) return false
        val bookChars = rows.sumOf { it.text.length }
        val byPage = rows.groupBy { it.pageNo!! }

        // 队列 DONE 页：缓存命中换视觉内容，缓存被系统清理则保底稿原样（部分页
        // 缓存丢失不放弃整书重建——其余页的视觉成果仍应应用）；全无缓存才不重建
        val doneByPage = doneItems.associateBy { it.pageNo }
        var replaced = 0
        var replacedDbChars = 0
        var replacedVisionChars = 0
        val pages = (byPage.keys + doneByPage.keys).toSortedSet().map { pageNo ->
            val item = doneByPage[pageNo]
            val dbRows = byPage[pageNo].orEmpty()
            val t = item?.let { VisionCache.read(context, it.uri, pageNo) }
            val paras: List<Para>
            if (t != null) {
                paras = visionParas(t)
                replaced++
                replacedDbChars += dbRows.sumOf { it.text.length }
                replacedVisionChars += paras.sumOf { it.text.length }
            } else {
                paras = parasFromDb(dbRows)
            }
            PageOut(
                pageNum = pageNo,
                tocLike = inferTocLike(paras.map { it.text }),
                rawChars = paras.sumOf { it.text.length },
                puaCount = 0,
                lineCount = paras.size,
                shortLineCount = 0,
                paras = paras,
                firstLine = null,
                lastLine = null,
            )
        }
        if (replaced == 0) return false

        // 复用 PdfExtractResult.assembleText 本尊组装（〔页N〕页标、脚注标、目录页
        // 单 \n 分隔全走同一份格式代码，不复制实现）；库内容已过导入管线合并，
        // alreadyMerged=true 跳过 crossPageMerge（无几何数据可依，也不需要）；
        // styleAware=false 与原重建一致（无字号证据，标题靠正则判定）。
        // DocStats(Float.NaN, 0f, 0f, null) 参数合法性（评审第 1 轮意见 2）：
        // PdfTypes.kt:68-75 明示 NaN=「无信号，下游 >= 比较安全退化」、pitchThreshold
        // null=「行距无信号」；assembleText 消费 stats 仅两处——crossPageMerge
        // （alreadyMerged=true 已跳过）与 styleAware 打标阈值（本调用走默认
        // styleAware=false 不进入）——NaN/null 全程不被解引用为数值，
        // 且 VisionRebuilderPureTest 组装格式锁定用例以本构造实证输出。
        val result = PdfExtractResult(
            pages = pages,
            stats = DocStats(Float.NaN, 0f, 0f, null),
            scanned = false,
            alreadyMerged = true,
        )
        val chapters = try {
            BookParser.parse(result.assembleText())
        } catch (e: Exception) {
            return false
        }
        if (chapters.isEmpty() || chapters.all { it.paras.isEmpty() }) return false
        val newChars = chapters.sumOf { ch -> ch.paras.sumOf { it.text.length } }
        if (!coverageOk(newChars, bookChars - replacedDbChars + replacedVisionChars)) {
            Log.w(TAG, "rebuild aborted: coverage $newChars vs expected " +
                "${bookChars - replacedDbChars + replacedVisionChars}")
            return false
        }
        Log.i(TAG, "rebuild bookId=$bookId pages=${pages.size} " +
            "chars=$bookChars→$newChars chapters=${chapters.size}")

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

    /** 库段落 → 管线段（脚注角色回挂；页码由 assembleText 的〔页N〕标记重推，无需带） */
    private fun parasFromDb(rows: List<ParagraphEntity>): List<Para> =
        rows.map { Para(text = it.text, footnote = it.role == DbValues.ROLE_FOOTNOTE) }

    /** 视觉转写 → 管线段（与导入同步替换路径同构：正文段 + 脚注合并段） */
    private fun visionParas(t: PageTranscription): List<Para> = buildList {
        addAll(t.body.map { Para(it) })
        if (t.footnotes.isNotEmpty()) add(Para(joinTexts(t.footnotes), footnote = true))
    }

    /** 目录页推断阈值：≥3 条命中才算（2 条以下不伤正文，误判代价见 inferTocLike） */
    const val TOC_INFER_MIN = 3

    /** 视觉目录条目样式：「第X章/节/回 标题…页码」结尾是数字（点线被转写丢弃）。
     *  数字用序号字集+阿拉伯混排；「第十条」类法条不在 [章节回] 内，天然不命中 */
    internal val RE_TOC_ENTRY =
        Regex("""^第[一二三四五六七八九十百千0-9]+[章节回][^0-9]*\d+\s*$""")

    /**
     * 视觉替换页的 tocLike 推断（纯函数供 JVM 单测）：≥[TOC_INFER_MIN] 条段落
     * 以「第X章/节/回…数字」结尾判为目录页。判 true 的收益=条目按目录区组装
     * （assembleText 单 \n 连块），BookParser 密度判定吃得下；判错的代价：
     * 假阴性=少见目录形态漏判成假章条目（宁可白跑方向），假阳性=正文数字清单
     * 误并单块（只是组装形态差异，BookParser 仍按标题行与点线判定章节，无实锤假章）。
     */
    internal fun inferTocLike(texts: List<String>): Boolean =
        texts.count { RE_TOC_ENTRY.containsMatchIn(it.trim()) } >= TOC_INFER_MIN

    /**
     * 覆盖守卫（纯函数供 JVM 单测）：重建产物字符量 ≥ 预期 80% 才允许替换。
     * C3a 实录：扫描书部分页兜底重建把 14772 字洗成 89 字——守卫兜住一切
     * 「产物远小于预期」的异常（解析塌缩、底稿丢失）。expected=0（空书）放行。
     * 80% 为拍板值（DECLARED）：视觉与 OCR 同源同页，长度天然相近，0.8 给
     * 断句/合并差异留裕量；方向宁可白跑。触发线变更须报老板。
     */
    internal fun coverageOk(newChars: Int, expectedChars: Int): Boolean =
        expectedChars == 0 || newChars.toLong() * 5 >= expectedChars.toLong() * 4
}
