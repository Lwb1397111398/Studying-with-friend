package com.studyfriend.app.data.vision

import android.content.Context
import android.util.Log
import com.studyfriend.app.data.BookRepository
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.importer.pdfpipeline.PageTranscription
import com.studyfriend.app.data.importer.pdfpipeline.joinTexts

/**
 * 结构保全式整书重建（OPT-F；P6c C3a/R1 方向 A，老板 2026-10-06 拍板）：
 * 后台队列全部页到终态后，把已转写内容应用到书——**不再重解析章界**。
 *
 * 为什么底稿取自数据库而不是重跑 PdfLoader.extract：extract 只读 PDF 文字层，
 * 扫描书（图像型 PDF——视觉队列的主场景）没有文字层（C3a 实录 P0，14772 字洗成 89 字）。
 *
 * 为什么不再走 BookParser 重解析（C3a 修复版行为，R1 立案推翻）：重解析输入=纯文本，
 * 而导入时的章界靠字号证据（DB 不存字号）检出——实测基线里连独立「第X章」段落都不
 * 存在（LIKE '第_章%' 0 行），重解析把 11 章塌成 5 章。结构保全=现有章 title/idx/level
 * 原样保留，只把替换页视觉段按页归属拼回所属章。
 *
 * 页归属规则（拍板值，变更须报老板）：
 * - D1 替换页有底稿行 → 归该页最后一行所在章（章首跨页时新章保住开头）；
 * - D2 孤儿替换页（底稿无该页行，如目录页）→ floor：页码 ≤p 的最近有字页所属章，
 *   无则 ceil 兜底；
 * - 缓存 miss 的 DONE 页 → 底稿行原地保留（部分替换语义，replaced 计数不加）；
 * - 未替换页 → 底稿行原地不动（跨章页行级保全）。
 * 已知边界：某章首页视觉转写为空体时该章首段 pageNo 后移，figures 重挂的章起点
 * 随之偏移（replaceBookContent 不变量按章对齐）——空体=该页本就无字，影响可接受。
 *
 * 守卫（全保留）：存在任何 AI 消费 → 不重建；底稿空/空页号 → 不重建；含 level=2
 * 节行 → 不重建（replaceBookContent 删旧章重插会丢节行，宁可白跑）；缓存全 miss →
 * 不重建；覆盖守卫（重建产物字符 < 预期 80% 放弃，宁可白跑不可毁数据）。
 */
object VisionRebuilder {

    private const val TAG = "VisionRebuilder"

    private data class NewPara(val text: String, val role: String, val pageNo: Int)
    private data class OutPara(
        val text: String,
        val role: String,
        val pageNo: Int,
        val sortPage: Int,
        val sortSeq: Int,
    )

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
        val chapters = db.chapterDao().byBook(bookId)
        if (chapters.any { it.level != 1 }) {
            Log.w(TAG, "rebuild skip bookId=$bookId: level-2 sections present")
            return false
        }
        val bookChars = rows.sumOf { it.text.length }
        val byPage = rows.groupBy { it.pageNo!! }
        val owners: Map<Int, Long> = byPage.mapValues { (_, rs) -> pageOwnerLast(rs) }

        // 队列 DONE 页：缓存命中换视觉内容，缓存被系统清理则底稿行原地保留
        val doneByPage = doneItems.associateBy { it.pageNo }
        val newRows = LinkedHashMap<Int, List<NewPara>>()
        var replaced = 0
        var replacedDbChars = 0
        var replacedVisionChars = 0
        for ((page, item) in doneByPage) {
            val t = VisionCache.read(context, item.uri, page)
            if (t == null) {
                Log.w(TAG, "rebuild partial: cache miss page=$page")
                continue
            }
            val tocPage = inferTocLike(t.body)
            val paras = buildList {
                t.body.forEach {
                    add(NewPara(it, if (tocPage) DbValues.ROLE_TOC else DbValues.ROLE_BODY, page))
                }
                if (t.footnotes.isNotEmpty()) {
                    add(NewPara(joinTexts(t.footnotes), DbValues.ROLE_FOOTNOTE, page))
                }
            }
            newRows[page] = paras
            replaced++
            replacedDbChars += byPage[page]?.sumOf { it.text.length } ?: 0
            replacedVisionChars += paras.sumOf { it.text.length }
        }
        if (replaced == 0) return false

        // 视觉段按页归属落到章（D1/D2）
        val placed = HashMap<Long, MutableList<NewPara>>()
        for ((page, paras) in newRows) {
            val owner = orphanOwner(owners, page) ?: return false // 不可达：rows 非空则 owners 非空
            placed.getOrPut(owner) { mutableListOf() }.addAll(paras)
        }

        // 逐章输出：未替换页底稿行原样 + 归属本章的视觉段，按 (pageNo, 页内序) 升序。
        // 页内不混排（替换页只有视觉段、未替换页只有底稿行）；同页多底稿行 sortSeq 同值，
        // compareBy 稳定排序保持 byBookForRebuild 原序。Set<Long?> 容纳可空 pageNo（守卫已滤空）。
        val replacedPages: Set<Int?> = newRows.keys
        val pairs = chapters.map { ch ->
            val kept = rows.filter { it.chapterId == ch.id && it.pageNo !in replacedPages }
                .map { OutPara(it.text, it.role, it.pageNo!!, it.pageNo!!, 0) }
            val news = placed[ch.id].orEmpty().mapIndexed { i, np ->
                OutPara(np.text, np.role, np.pageNo, np.pageNo, 1_000_000 + i)
            }
            val paras = (kept + news)
                .sortedWith(compareBy({ it.sortPage }, { it.sortSeq }))
                .map {
                    ParagraphEntity(
                        chapterId = 0, idx = 0, text = it.text,
                        role = it.role, pageNo = it.pageNo,
                    )
                }
            ChapterEntity(
                bookId = bookId, idx = ch.idx, title = ch.title,
                readState = DbValues.READ_NOT, gist = null, keyTermsJson = null,
                level = ch.level, parentOrder = ch.parentOrder,
            ) to paras
        }

        val newChars = pairs.sumOf { (_, ps) -> ps.sumOf { it.text.length } }
        if (!coverageOk(newChars, bookChars - replacedDbChars + replacedVisionChars)) {
            Log.w(TAG, "rebuild aborted: coverage $newChars vs expected " +
                (bookChars - replacedDbChars + replacedVisionChars))
            return false
        }
        Log.i(TAG, "rebuild bookId=$bookId pages=${byPage.keys.size + newRows.size} " +
            "chars=$bookChars→$newChars chapters=${chapters.size} (preserved)")

        val book = db.bookDao().get(bookId) ?: return false
        BookRepository(db).replaceBookContent(
            bookId,
            book.copy(updatedAt = System.currentTimeMillis()),
            pairs,
        )
        return true
    }

    /** D1：页归属=该页底稿最后一行所在章（rows 已按 pageNo,c.idx,p.idx 序，末行=页内最晚开始的章） */
    internal fun pageOwnerLast(rows: List<ParagraphEntity>): Long = rows.last().chapterId

    /** D2：孤儿替换页归属——floor（≤page 的最近有字页）优先，无则 ceil 兜底；
     *  owners 空（无任何底稿行）返回 null，调用侧 rows.isEmpty 守卫先行 */
    internal fun orphanOwner(owners: Map<Int, Long>, page: Int): Long? =
        owners.filterKeys { it <= page }.keys.maxOrNull()?.let { owners[it] }
            ?: owners.filterKeys { it > page }.keys.minOrNull()?.let { owners[it] }

    /** 目录页推断阈值：≥3 条命中才算（2 条以下不伤正文，误判代价见 inferTocLike） */
    const val TOC_INFER_MIN = 3

    /** 视觉目录条目样式：「第X章/节/回 标题…页码」结尾是数字（点线可能被转写保留或丢弃，
     *  两种形态 C3a 双跑均实测——仅要求尾随页码数字）。数字用序号字集+阿拉伯混排；
     *  「第十条」类法条不在 [章节回] 内，天然不命中 */
    internal val RE_TOC_ENTRY =
        Regex("""^第[一二三四五六七八九十百千0-9]+[章节回][^0-9]*\d+\s*$""")

    /**
     * 视觉替换页的 tocLike 推断（纯函数供 JVM 单测）：≥[TOC_INFER_MIN] 条段落
     * 以「第X章/节/回…数字」结尾判为目录页。判 true 的收益=该页段落标 ROLE_TOC
     * （目录区组装与 AI 跳过）；判错的代价：假阴性=少见目录形态漏判成正文段，
     * 假阳性=正文数字清单误标 TOC（只影响段落 role 展示，不动章界）。
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
