package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues

/** 解析产物：段落带角色（BODY/FRONT/BACK/TOC），映射到 ParagraphEntity 在 VM 侧完成。
 *  pageNo=段首页码（P3b-2 pageNo 链路；TXT/粘贴路径恒 null），目录驱动切章的锚定与段落重排依据 */
data class ParsedPara(val text: String, val role: String, val pageNo: Int? = null)

data class ParsedChapter(
    val title: String,
    val paras: List<ParsedPara>,
    /** 无标题兜底（盲切/整书单章）时为 true，UI 据此提示 */
    val blindCut: Boolean = false,
)

/** 自定义识别规则一行标题都没匹配到时抛出 */
class CustomRegexNoMatchException(message: String) : Exception(message)

/** 纯 Kotlin 书籍解析：无 Android 依赖，全量可单测。规则见 docs/plans/M2-导入与解析.md §2 */
object BookParser {

    private const val GRADE_A = 1
    private const val GRADE_B = 2
    private const val GRADE_C = 3

    // A 档（章级）
    private val RE_A_HAN = Regex("^第[一二三四五六七八九十百千0-9零〇两]+[章单元篇部卷回]")
    private val RE_A_LATIN = Regex("^(Chapter|Unit)\\s+\\d+", RegexOption.IGNORE_CASE)
    private val FIXED_WORDS = setOf("前言", "序言", "引言", "目录", "附录", "后记", "参考文献")

    // P8 前置/后置标题变体族（老板「按内容分块」诉求）：固定词外的常见变体（写在前面/
    // 第X版序/总序/译序/凡例/作者简介/致谢/跋…）。全行精确锚定（^…$）——正文行文不可能
    // 整行恰好等于这些词，比 contains 安全；命中走 A_FIXED 档（PDF 路径同受〔标题〕字号
    // 证据门槛约束，TXT 路径纯形态护栏）。多版序（初版序/第二版序）标题不同 → 各自成章。
    private val RE_FRONT_TITLE = Regex(
        "^(?:" +
            "(?:第?[0-9一二三四五六七八九十百]{1,3}版|初版|再版|新版|修订版|增订版)?序(?:言|[一二三四五六七八九十])?" +
            "|(?:丛书)?总序|自序|代序|小序" +
            "|写在前面(?:的话)?|写在卷首|作者的话|译者的话" +
            "|(?:再版|修订|新版|出版|编写|编译|增订)说明" +
            "|凡例|缩略语表?|简称(?:对照)?表" +
            "|作者简介|译者简介|作者与译者简介|关于作者|献词" +
            ")$",
    )

    // 后置变体：致谢/谢辞/跋 与「参考文献/附录/后记」同走 ROLE_BACK
    private val RE_BACK_TITLE = Regex("^(?:致谢|谢辞|译后记|跋)$")

    /** 前置标题全行判定（切章 role=FRONT 的依据；含固定词前言/序言/引言） */
    internal fun isFrontMatterTitle(t: String): Boolean =
        t == "前言" || t == "序言" || t == "引言" || RE_FRONT_TITLE.matches(t)

    /** 后置标题判定（role=BACK；含固定词参考文献/附录/后记） */
    internal fun isBackMatterTitle(t: String): Boolean =
        t == "参考文献" || t == "附录" || t == "后记" || RE_BACK_TITLE.matches(t)

    /**
     * 目录条目的前置判定（P8）：比 [isFrontMatterTitle] 宽——目录条目常带副题/年份
     * （「写在前面——致读者」「序（2003年）」），整行锚定会漏，前缀放宽 + 长度护栏。
     * 仅用于把前置条目从 TocChapterCalibrator 的正文章节锚定分母剔除（前置页码相对
     * 目录前置，锚定窗口永远够不着，徒增分母），不参与切章。
     */
    internal fun isFrontMatterEntryTitle(t: String): Boolean {
        if (t.isEmpty() || t.length > 14) return false
        if (isFrontMatterTitle(t)) return true
        return t.startsWith("写在前面") || t.startsWith("写在卷首") || t.startsWith("总序") ||
            t.startsWith("译序") || t.startsWith("译者序") || t.startsWith("译者的话") ||
            t.startsWith("自序") || t.startsWith("作者的话") || t.startsWith("序言") ||
            t.startsWith("初版序") || t.startsWith("再版序") || t.startsWith("再版说明") ||
            t.startsWith("修订说明") || t.startsWith("增订版") || t.startsWith("凡例") ||
            t.startsWith("缩略语") || t.startsWith("作者简介") || t.startsWith("译者简介") ||
            Regex("^第?[0-9一二三四五六七八九十百]{1,3}版").containsMatchIn(t)
    }

    // B 档（节级）
    private val RE_B = Regex("^第[一二三四五六七八九十百千0-9零〇两]+[节讲]")

    // C 档（小样式，序号类一律行首锚定）
    private val RE_C_HAN = Regex("^[一二三四五六七八九十]+[、.，]")
    private val RE_C_PAREN = Regex("^（[一二三四五六七八九十]+）")
    private val RE_C_NUM = Regex("^\\d{1,3}(\\.\\d{1,3})+[、.．]?\\s*\\S")
    private val RE_C_NUM1 = Regex("^\\d{1,3}[、.．]\\s*\\S")

    // 标题不以句读标点结尾（护栏）。与下方 RE_MID_PUNCT 同属标点护栏体系，加字符时两处对齐
    private val END_PUNCT = "。？！，、；,.?!;"

    // 目录条目形态：点线/省略号 + 页码结尾（"第一章 导论……1"），不是标题。
    // 点线字符族含 … · . 以及 PDF 提取常见的实心圆点 •(U+2022)/‧(U+2027)（OPT-C C2）
    // P6c-D 扫描书适配：加全角句点「．」与 OCR 圆点认读「●」（shpc 目录页点线被 OCR
    // 认成「●●」「..」独立行）；页码放宽括号形态——OCR 目录页实录「……（1）」「……(355)」
    // 带括号页码是主流，纯数字口径整页 0 命中 → 目录区识别/探针取样全链漏（DB 实录
    // shpc role=TOC 0 条、条目混进「序言」章）。
    // internal：TocChapterCalibrator 锚点/章名匹配同样要排除目录页点线条目（P3b-2）
    internal val RE_TOC_LINE = Regex("[…·.•‧．●]{2,}\\s*[（(]?\\d{1,4}[）)]?\\s*$")

    /** 孤页码行的括号形态（P6c-D）：OCR 目录页页码常独立成行且带括号「(355)」「（1）」 */
    internal val RE_PAGE_ONLY_PAREN = Regex("[（(]\\d{1,4}[）)]")

    /**
     * 目录条目形态三（P6c-D 扫描书第三判据）：行首「第X章/节/目/款」标题样 + 行尾页码
     * （点线可有可无——OCR 目录条目点线常被吃掉：「第二节美国法上的惩罚性赔偿 (359)」）。
     * 与 PdfCleaner.RE_TOC_HEADING_LINE 同源同防误伤口径（正文引用不以独立行+页码收尾）。
     */
    internal val RE_TOC_HEADING_LINE =
        Regex("^第[一二三四五六七八九十百千零〇两]+[章节节目款回].{0,60}?[（(]?\\d{1,4}[）)]?\\s*$")

    // 页眉护栏（OPT-C C2）：行尾"CJK 字 + 页码"（如页眉"第一章 私法绪论 3"）不是标题。
    // 只拦内置 A/B 档命中（C 档样式数字内嵌不受影响；custom 路径 = 用户手工重切，不拦）
    // internal：TocChapterCalibrator 锚点/章名匹配同样排除页眉行（P3b-2）
    internal val RE_TRAIL_PAGE = Regex("[\\u4e00-\\u9FFF]\\s*\\d{1,4}\\s*$")

    // A 档实书回归护栏（OPT-D，E2E 真书《民法总则》630 页实测发现）：
    // 正文里引用章节结构的行（"第二章婚姻规定，未使……" / "第四章：法律行为 第一节：权利能力"）
    // 会被 RE_A_HAN 误判成章标题并吞走正文。三道护栏只作用于 A_HAN 命中：
    // ①超长不认（真章标题如"第二章 民法的法源及法律的适用"仅 15 字）；
    // ②行中含句读不认（章标题不以逗号分句）；
    // ③章+节同行不认（真章标题独占一行，"第X节"另起一行）。
    // 英文 Chapter 行（A_LATIN）天然偏长，不受此限；B/C 档与 custom 路径不拦。
    private const val A_TITLE_MAX_LEN = 24
    // 顿号(、)刻意不在列：法定书章名大量含顿号（如《民法典》"物权的设立、变更、转让和消灭"）
    private val RE_MID_PUNCT = Regex("[，,；;]")
    private val RE_SECTION_INLINE = Regex("第[一二三四五六七八九十百千0-9零〇两]+[节讲]")

    private const val TITLE_MAX_LEN = 40
    private const val BLIND_CUT_THRESHOLD = 50_000
    private const val BLIND_CUT_CHAPTER_CHARS = 3_000

    // TOC 区域识别（OPT-C C3）：孤页码行与标题样式行
    private val RE_PAGE_ONLY = Regex("\\d{1,4}")
    private val RE_HEADINGISH = Regex("^第[一二三四五六七八九十百千0-9零〇两]+[章单元篇部卷回节讲]")

    /** 密度兜底：块内点线/孤页码行达到该数量才无锚点划区 */
    private const val TOC_FALLBACK_MIN_DOTS = 3

    /** 无标题且不超阈值时整书单章的章名 */
    const val WHOLE_BOOK_TITLE = "全文"

    /**
     * 脚注段前缀（OPT-E 管线协议）：PdfLoader.assembleText 给 footnote 段打上
     * 此前缀，本解析器识别后剥掉并把段落标为 ROLE_FOOTNOTE。TXT 路径不经过
     * assembleText；正文偶有此前缀时剥掉标脚注，语义恰好一致，无害。
     */
    const val FOOTNOTE_MARK = "〔脚注〕"

    /**
     * 标题候选前缀（P3a 字号证据链）：PdfLoader.assembleText(styleAware=true) 给
     * size ≥ bodySize+1.5pt 的非脚注段打此前缀，本解析器剥掉并把该行记为 big 证据；
     * A/B 档命中但无此证据（styleAware=true）→ 降为正文——拦真书版权页假章
     * 「第十二章权利的行使」（HiddenHorzOCR 10.4pt ≈ 正文）。C 档序号小标题与
     * 正文同字号，不设门槛；custom 手工重切优先；TXT 路径无前缀体系不受影响。
     * styleAware=false 时不剥不拦，行为与 P2 逐字节一致。
     */
    const val TITLE_SIZE_MARK = "〔标题〕"

    /**
     * 页边界标记（P3b-2 pageNo 链路）：PdfExtractResult.assembleText 在每个有段落的
     * PDF 页正文前插入独立行「〔页N〕」（N=1-based 页号，来源 PageOut.pageNum），本解析器
     * 预处理时剥掉，并把该页号记为随后段落的 pageNo（段首页码；跨页并段文本挂在前一页
     * 段落上，天然取首页）。TXT/粘贴路径不经过 assembleText，pageNo 一律 null。与
     * 〔脚注〕/〔标题〕同属管线协议前缀族；正文独立成行命中「〔页N〕」的几率可忽略，
     * 万一命中也只是被当作页边界剥掉，无内容损失。
     */
    const val PAGE_MARK_PREFIX = "〔页"
    private val RE_PAGE_MARK = Regex("^〔页(\\d+)〕$")

    /**
     * 目录页整页标记（P6c-E）：PdfExtractResult.assembleText 给 PdfCleaner 判定的
     * tocLike 页 body 前插独立行「〔目录〕」。本解析器预处理剥标并记 blockTocMark，
     * 标记块整块走 tocEntries 条目重组、且不产标题命中——不再用 isTocBlock 占比
     * 重判：OCR 目录页点线漂移成行首「..」「●●」、页码独立成行、「第九章」误读成
     * 「第力章」，isTocLikeLine 整页只命中孤立页码行，0.5 占比必不过（shpc 60 页
     * E2E 实录：3 个 tocLike 页仅救出 2 个孤页码段，其余条目 softMerge 成正文）。
     * 前级 PdfCleaner 三判据已验目录页，此处信任不重判。TXT 路径不经过
     * assembleText 不受影响；与〔标题〕/〔脚注〕/〔页N〕同属管线协议前缀族。
     */
    const val TOC_BLOCK_MARK = "〔目录〕"

    /**
     * 目录章标题（P6g，老板拍板）：目录块（〔目录〕标记块/散条目块）一律独立成
     * 「目录」章，与第一章/第二章同级——不再挂进邻近章（msw 40 页 E2E 实录：
     * p6 标记块挂进「序言」章、p7 散条目块挂进「第一章法律思维」章）。连续目录
     * 块（目录跨页）合入同一个「目录」章，不重复开章。
     */
    const val TOC_CHAPTER_TITLE = "目录"

    /** 孤页码行（P6g tocEntries 处置）：纯数字/括号数字，可带句点收尾 */
    private val RE_TOC_LONE_PAGE = Regex("^\\(?\\d{1,4}\\)?[.。]?$")

    /** 条目尾页码（P6g）：上一条目已带页码时孤页码判残留丢弃 */
    private val RE_TOC_TAIL_PAGE = Regex("\\d{1,4}\\s*[.。]?$")

    /**
     * TOC 连续区（块下标闭区间）。锚点路径记录锚点行坐标（区内唯一可产标题命中的行）；
     * anchorLine = -1 表示无锚点、按密度兜底划区。
     */
    private class TocRegion(val start: Int, val end: Int, val anchorBlock: Int, val anchorLine: Int) {
        operator fun contains(bi: Int): Boolean = bi in start..end
    }

    private class TitleHit(val blockIdx: Int, val lineIdx: Int, val grade: Int, val style: String, val title: String)

    /**
     * 解析全书。[customTitleRegex] 非 null 时替换内置标题正则族（目录重切），
     * 非法正则抛 [java.util.regex.PatternSyntaxException] 由调用方转为友好提示。
     * [styleAware]=true（PDF 路径）：剥〔标题〕前缀并把 A/B 档无字号证据的命中
     * 降为正文；false（TXT）与 P2 行为逐字节一致。
     */
    fun parse(
        raw: String,
        customTitleRegex: String? = null,
        styleAware: Boolean = false,
    ): List<ParsedChapter> {
        val custom = customTitleRegex?.let { Regex(it) }
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val blocks0 = splitBlocks(text)
        // P3b-2 预处理：剥〔页N〕页边界标记并记每块页号（须在 trim/正则/目录识别之前，
        // 理由同〔标题〕前缀——标记行不得参与 isTocBlock 强行占比与标题候选判定）。
        // 无标记的块顺延继承最近一次标记的页号（同页后续段落各自成块、无标记）。
        val (blocksPageMarked, blockPagesRaw) = stripPageMarks(blocks0)
        // P6c-E 预处理：剥〔目录〕页级标记并记 blockTocMark（须在目录识别/标题候选之前，
        // 理由同〔页N〕——标记行不得参与 isTocBlock 占比与标题候选判定）。剥标后空块
        // （标记孤块）丢弃，页号/标记数组同步收缩。
        val blockTocMarkRaw = BooleanArray(blocksPageMarked.size)
        val strippedToc = blocksPageMarked.mapIndexed { bi, lines ->
            if (lines.firstOrNull()?.trim() == TOC_BLOCK_MARK) {
                blockTocMarkRaw[bi] = true
                lines.drop(1)
            } else lines
        }
        val keptBlocks = strippedToc.indices.filter { strippedToc[it].isNotEmpty() }
        val blocksMarked = keptBlocks.map { strippedToc[it] }
        val blockPages = keptBlocks.map { blockPagesRaw[it] }
        val blockTocMark = BooleanArray(keptBlocks.size) { blockTocMarkRaw[keptBlocks[it]] }
        // P3a 预处理：剥〔标题〕前缀并记 big 行。逐行检查（目录页 \n 连接多段成块，
        // 前缀可能在块中间行）；必须发生在 trim/正则/锚点匹配之前，否则「〔标题〕目 录」
        // 匹配不上锚点、目录区识别失效。styleHints 与 blocks 同构（每块每行一一对应）。
        var styleHints: List<BooleanArray>? = null
        val blocks = if (!styleAware) blocksMarked else {
            val hints = List(blocksMarked.size) { BooleanArray(blocksMarked[it].size) }
            styleHints = hints
            blocksMarked.mapIndexed { bi, lines ->
                lines.mapIndexed { li, line ->
                    val s = line.trimStart()
                    if (s.startsWith(TITLE_SIZE_MARK)) {
                        hints[bi][li] = true
                        s.removePrefix(TITLE_SIZE_MARK)
                    } else {
                        line
                    }
                }
            }
        }
        val tocRegion = detectTocRegion(blocks, custom)
        val hits = findTitleHits(blocks, custom, tocRegion, styleHints, blockTocMark)
        if (hits.isEmpty()) {
            // §2.3：自定义规则零命中要明确报错，而不是静默滑进盲切兜底
            if (custom != null) {
                throw CustomRegexNoMatchException("识别规则没有命中任何标题行，请检查正则")
            }
            return blindChapters(blocks, blockPages).also(::assertNoMarkLeak)
        }

        // 档位偏序 A≥B≥C：1 级 = 出现的最高档位；仅 C 时首个命中的 C 样式为 1 级
        val topGrade = hits.minOf { it.grade }
        val topHits = if (topGrade == GRADE_C) {
            val firstStyle = hits.first { it.grade == GRADE_C }.style
            hits.filter { it.style == firstStyle }
        } else {
            hits.filter { it.grade == topGrade }
        }
        val hitByBlock = topHits.associateBy { it.blockIdx }

        val chapters = mutableListOf<ParsedChapter>()
        var curTitle = "开篇"
        // 开篇章（第一个标题命中前的封面/版权/总序）标 FRONT：无用前置信息有 role 归属
        var curRole = DbValues.ROLE_FRONT
        // 「目录」章的插入式恢复（P6g）：切开目录章时保存此前章上下文，TOC 块后
        // 遇到正文块时恢复——否则 curRole=TOC 永久生效，散条目后的正文段被吞进
        // 目录章并误标 TOC（strayTocEntriesAfterChapter 回归实录）
        var savedTitle: String? = null
        var savedRole = DbValues.ROLE_FRONT
        var curParas = mutableListOf<ParsedPara>()

        fun flush() {
            if (curParas.isNotEmpty()) chapters.add(ParsedChapter(curTitle, curParas.toList()))
            curParas = mutableListOf()
        }

        // 密度兜底区（无锚点）的条目：顺延并入下一命中章的段落头部，绝不落入"开篇"章
        val pendingToc = mutableListOf<ParsedPara>()

        for ((bi, lines) in blocks.withIndex()) {
            val blockPage = blockPages[bi]
            val hit = hitByBlock[bi]
            when {
                hit != null -> {
                    flush()
                    curTitle = hit.title
                    curRole = roleFor(hit.title)
                    if (pendingToc.isNotEmpty()) {
                        curParas.addAll(pendingToc)
                        pendingToc.clear()
                    }
                    if (tocRegion != null && bi in tocRegion) {
                        // 锚点章（"目录"）：区内条目重组为 ROLE_TOC 段，锚点行本身已作为章标题
                        curParas.addAll(tocEntries(lines, hit.lineIdx, blockPage))
                    } else {
                        for (para in parasOf(lines.filterIndexed { li, _ -> li != hit.lineIdx }, curRole, blockPage)) {
                            curParas.add(para)
                        }
                    }
                }
                tocRegion != null && bi in tocRegion -> {
                    if (tocRegion.anchorLine >= 0) {
                        curParas.addAll(tocEntries(lines, -1, blockPage))
                    } else {
                        // P6g 补：锚点行被 LOW_CONF 兜底吃掉（msw p6 简目页实录）时
                        // 区内条目仍独立成「目录」章，不再顺延挂进下一命中章头部
                        // （老板拍板：目录与第一章/第二章同级）；pendingToc 与尾部
                        // 兜底保留作安全网，正常不再触发
                        if (curTitle != TOC_CHAPTER_TITLE) {
                            flush()
                            savedTitle = curTitle
                            savedRole = curRole
                            curTitle = TOC_CHAPTER_TITLE
                            curRole = DbValues.ROLE_TOC
                        }
                        curParas.addAll(tocEntries(lines, -1, blockPage))
                    }
                }
                else -> {
                    // 脚注段（管线协议前缀）：剥前缀、标 FOOTNOTE，AI 与阅读主流程跳过
                    val firstLine = lines.firstOrNull()?.trim().orEmpty()
                    when {
                        firstLine.startsWith(FOOTNOTE_MARK) -> {
                            curParas.add(
                                ParsedPara(firstLine.removePrefix(FOOTNOTE_MARK), DbValues.ROLE_FOOTNOTE, blockPage),
                            )
                        }
                        blockTocMark[bi] -> {
                            // P6c-E：tocLike 页整块条目重组——前级 PdfCleaner 三判据已验
                            // 目录页，不再 isTocBlock 占比重判（OCR 碎行占比必不过，E2E 实录）
                            // P6g：flush 后独立成「目录」章（老板拍板：目录与第一章/第二章
                            // 同级，不混进邻近章）；已在目录章内（目录跨页连续块）则直接续挂
                            if (curTitle != TOC_CHAPTER_TITLE) {
                                flush()
                                savedTitle = curTitle
                                savedRole = curRole
                                curTitle = TOC_CHAPTER_TITLE
                                curRole = DbValues.ROLE_TOC
                            }
                            curParas.addAll(tocEntries(lines, -1, blockPage))
                        }
                        isTocBlock(lines) -> {
                            // 目录区外的散条目块（目录尾页没盖进 tocRegion / OCR 目录页
                            // 点线被吃光漏判 tocLike，msw p7 实录）：条目重组标 ROLE_TOC
                            // 并同样独立成「目录」章——块已自我声明为目录条目，切出成章
                            // 与段 role 一致，避免挂进第一章正文
                            if (curTitle != TOC_CHAPTER_TITLE) {
                                flush()
                                savedTitle = curTitle
                                savedRole = curRole
                                curTitle = TOC_CHAPTER_TITLE
                                curRole = DbValues.ROLE_TOC
                            }
                            curParas.addAll(tocEntries(lines, -1, blockPage))
                        }
                        else -> {
                            if (curTitle == TOC_CHAPTER_TITLE) {
                                // 目录块结束、回到正文：恢复切出前的章上下文（目录章是
                                // 插入式章节，不改变后续正文/标题的章归属）
                                flush()
                                curTitle = savedTitle ?: "开篇"
                                curRole = savedRole
                                savedTitle = null
                            }
                            for (para in parasOf(lines, curRole, blockPage)) curParas.add(para)
                        }
                    }
                }
            }
        }
        if (pendingToc.isNotEmpty()) {
            // 兜底区之后没有更多命中章：条目并入最后一章尾部，避免丢内容
            curParas.addAll(pendingToc)
        }
        flush()
        // P6g：「目录」章是插入式章节——切出、恢复后，被它切开的同名章成两截且
        // 中间隔着目录章（strayTocEntriesAfterChapter 实录：第一章导论/目录/第一章导论）。
        // 同名章紧邻、或仅隔一个「目录」章时合并回去（条目续挂前段，目录章位置跟随文流）。
        val merged = mutableListOf<ParsedChapter>()
        for (ch in chapters) {
            val n = merged.size
            val j = if (n >= 2 && merged[n - 1].title == TOC_CHAPTER_TITLE && merged[n - 2].title == ch.title) {
                n - 2
            } else n - 1
            if (j >= 0 && merged[j].title == ch.title) {
                merged[j] = merged[j].copy(paras = merged[j].paras + ch.paras)
            } else merged.add(ch)
        }
        return merged.also(::assertNoMarkLeak)
    }

    /**
     * 前缀泄漏 post-condition（P3a）：任何输出段落文本的行首不得残留〔标题〕前缀——
     * 泄漏到阅读文本比假章更严重，运行时强制抛错而非静默污染。不用 check()（它抛
     * IllegalArgumentException，语义不符）。internal 供单测直调（正常路径剥前缀在先，
     * 无法从公开入口构造出泄漏）。〔脚注〕不纳入：目录页单块多段场景下
     * 块中行首〔脚注〕是既有已验收行为。
     */
    internal fun assertNoMarkLeak(chapters: List<ParsedChapter>) {
        for (c in chapters) {
            for (p in c.paras) {
                if (p.text.lineSequence().any {
                        it.trimStart().startsWith(TITLE_SIZE_MARK) ||
                            it.trim() == TOC_BLOCK_MARK ||
                            RE_PAGE_MARK.containsMatchIn(it.trim())
                    }
                ) {
                    throw IllegalStateException("P3a/P3b/P6c-E 标记泄漏: ${p.text.take(20)}")
                }
            }
        }
    }

    /**
     * 剥〔页N〕页边界标记（P3b-2 pageNo 链路）：返回剥标后的块列表与每块页号。
     * 块页号=块内首个标记的页号；块内无标记则顺延继承最近一次标记（同页后续段落
     * 各自成块、无标记）。只剩标记的块（空页）丢弃；任何块产生前无标记（TXT 开头）
     * 页号为 null。标记永远在各页正文首行之前（assembleText 保证），块中标记即页界。
     */
    internal fun stripPageMarks(blocks: List<List<String>>): Pair<List<List<String>>, List<Int?>> {
        val outBlocks = mutableListOf<List<String>>()
        val outPages = mutableListOf<Int?>()
        var last: Int? = null
        for (block in blocks) {
            var page: Int? = null
            val lines = mutableListOf<String>()
            for (line in block) {
                val m = RE_PAGE_MARK.matchEntire(line.trim())
                if (m == null) lines.add(line) else {
                    if (page == null) page = m.groupValues[1].toInt()
                    last = page
                }
            }
            if (lines.isEmpty()) continue // 原生空块不会出现（splitBlocks 已滤），此处防标记孤块
            outBlocks.add(lines)
            outPages.add(page ?: last)
        }
        return outBlocks to outPages
    }

    // ---- TOC 区域识别（OPT-C C3） ----

    /** 块级 TOC 判定：≥1 条点线条目，且 点线/孤页码/标题样 行占比 ≥ 0.5 */
    private fun isTocBlock(lines: List<String>): Boolean {
        var dot = 0
        var strong = 0
        var nonEmpty = 0
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            nonEmpty++
            val dotLike = isTocLikeLine(t)
            if (dotLike) dot++
            if (dotLike || RE_HEADINGISH.containsMatchIn(t) || t in FIXED_WORDS) strong++
        }
        return dot >= 1 && nonEmpty > 0 && strong.toDouble() / nonEmpty >= 0.5
    }

    /** 目录条目行判定（isTocBlock/密度兜底/tocEntries 触发三处共用）：点线页码/孤页码/标题样页码 */
    private fun isTocLikeLine(t: String): Boolean =
        RE_TOC_LINE.containsMatchIn(t) ||
            RE_PAGE_ONLY.matches(t) || RE_PAGE_ONLY_PAREN.matches(t) ||
            RE_TOC_HEADING_LINE.containsMatchIn(t)

    /** 从锚点行（目录/目次/Contents）或密度兜底起划区，连续 isTocBlock 块并入 */
    private fun detectTocRegion(blocks: List<List<String>>, custom: Regex?): TocRegion? {
        if (custom != null) return null // 目录重切由用户手工兜底，不再自动划区
        blocks.forEachIndexed { bi, lines ->
            lines.forEachIndexed { li, raw ->
                val t = raw.trim()
                if (t == "目录" || t == "目次" || t.equals("Contents", ignoreCase = true)) {
                    return TocRegion(bi, extendRegion(blocks, bi), bi, li)
                }
            }
        }
        for ((bi, lines) in blocks.withIndex()) {
            val dots = lines.count { isTocLikeLine(it.trim()) }
            if (isTocBlock(lines) && dots >= TOC_FALLBACK_MIN_DOTS) {
                return TocRegion(bi, extendRegion(blocks, bi), bi, -1)
            }
        }
        return null
    }

    private fun extendRegion(blocks: List<List<String>>, start: Int): Int {
        var end = start
        while (end + 1 < blocks.size && isTocBlock(blocks[end + 1])) end++
        return end
    }

    /**
     * 区内条目重组：累积行直到点线/孤页码触发行，触发行并入当前累积后成段
     * （跨行条目如"第一节\n请求权……107"并回一段），一律 ROLE_TOC。
     */
    private fun tocEntries(lines: List<String>, skipLine: Int = -1, pageNo: Int? = null): List<ParsedPara> {
        val out = mutableListOf<ParsedPara>()
        val acc = mutableListOf<String>()
        fun flushAcc() {
            if (acc.isEmpty()) return
            val merged = softMerge(acc)
            // P6g：孤页码段处置——OCR 目录页把条目页码拆成独立行（msw p7 实录：
            // 「159」「88」各自成段）。单行纯页码且上一条目尾无页码 → 拼回上一条目；
            // 上一条目尾已有页码 → 原书版式不存在条目间夹孤页码，判 OCR 残留丢弃
            // （并入会造「…388 88」幻觉页码）。
            if (acc.size == 1 && RE_TOC_LONE_PAGE.matches(merged) && out.isNotEmpty()) {
                val prevTail = out.last().text.trim()
                if (!RE_TOC_TAIL_PAGE.containsMatchIn(prevTail.takeLast(8))) {
                    out[out.size - 1] = out.last().copy(text = softMerge(listOf(prevTail, merged)))
                }
            } else if (merged.isNotBlank()) {
                out.add(ParsedPara(merged, DbValues.ROLE_TOC, pageNo))
            }
            acc.clear()
        }
        for ((li, raw) in lines.withIndex()) {
            if (li == skipLine) continue
            val t = raw.trim()
            if (t.isEmpty()) continue
            acc.add(t)
            // 触发行与 isTocBlock 同源（P6c-D：括号页码独立行「(355)」也触发，防 OCR
            // 目录页把「(355)」并进下一条目）
            if (isTocLikeLine(t)) flushAcc()
        }
        flushAcc()
        return out
    }

    // ---- 标题判定 ----

    private fun findTitleHits(
        blocks: List<List<String>>,
        custom: Regex?,
        tocRegion: TocRegion?,
        styleHints: List<BooleanArray>? = null,
        blockTocMark: BooleanArray = BooleanArray(blocks.size),
    ): List<TitleHit> {
        val hits = mutableListOf<TitleHit>()
        blocks.forEachIndexed { bi, lines ->
            // 〔目录〕块整块不产标题命中（P6c-E）：目录条目行「第九章 xxx」会开假章
            if (blockTocMark[bi]) return@forEachIndexed
            for ((li, line) in lines.withIndex()) {
                // TOC 区内不产标题命中，仅锚点行本身例外（"目录"成章）
                if (tocRegion != null && bi in tocRegion && !(bi == tocRegion.anchorBlock && li == tocRegion.anchorLine)) {
                    continue
                }
                val t = line.trim()
                if (!isTitleCandidate(t)) continue
                val hit = matchTitle(t, custom) ?: continue
                // P3a 字号门槛（styleHints 非 null 即 styleAware=true）：A/B 档命中但
                // 该行无〔标题〕证据 → 降为正文。custom 手工重切优先不拦；C 档与正文
                // 同字号不拦；锚点行「目 录」由目录页直通带字号获得证据
                if (styleHints != null && hit.first <= GRADE_B && hit.second != "CUSTOM" &&
                    styleHints.getOrNull(bi)?.getOrNull(li) != true
                ) {
                    println("P3a styleGuard blocked: ${t.take(20)}")
                    continue
                }
                // A_HAN_SUB：护栏拦下后破折号截断救回的副标题章名，标题取前半
                val title = if (hit.second == "A_HAN_SUB") subtitleCut(t) ?: t else t
                hits.add(TitleHit(bi, li, hit.first, hit.second, title))
                break // 一个块最多一个标题
            }
        }
        return hits
    }

    /** 护栏：非空 + 行长 ≤40 + 不以句读标点结尾 + 非目录条目（点线页码） */
    private fun isTitleCandidate(t: String): Boolean =
        t.isNotEmpty() && t.length <= TITLE_MAX_LEN && t.last() !in END_PUNCT &&
            !RE_TOC_LINE.containsMatchIn(t)

    /** 返回 (档位, 样式名)；正则行首锚定，匹配即标题。custom 非空时替换内置正则族 */
    private fun matchTitle(t: String, custom: Regex?): Pair<Int, String>? {
        val hit = when {
            custom != null -> if (custom.containsMatchIn(t)) GRADE_A to "CUSTOM" else null
            RE_A_HAN.containsMatchIn(t) -> GRADE_A to "A_HAN"
            RE_A_LATIN.containsMatchIn(t) -> GRADE_A to "A_LATIN"
            t in FIXED_WORDS || isFrontMatterTitle(t) || isBackMatterTitle(t) -> GRADE_A to "A_FIXED"
            RE_B.containsMatchIn(t) -> GRADE_B to "B"
            RE_C_HAN.containsMatchIn(t) -> GRADE_C to "C_HAN"
            RE_C_PAREN.containsMatchIn(t) -> GRADE_C to "C_PAREN"
            RE_C_NUM.containsMatchIn(t) -> GRADE_C to "C_NUM"
            RE_C_NUM1.containsMatchIn(t) -> GRADE_C to "C_NUM1"
            else -> null
        } ?: return null
        // 页眉护栏：内置 A/B 档命中但行尾是"CJK+页码"（PDF 页眉"第一章 私法绪论 3"）→ 降为正文。
        // 英文标题（Chapter 12 Something）不以 CJK 结尾，不受影响；C 档数字内嵌样式不受影响
        if (hit.second != "CUSTOM" && hit.first <= GRADE_B && RE_TRAIL_PAGE.containsMatchIn(t)) return null
        // OPT-D：A 档中文命中三护栏（超长 / 行中句读 / 章+节同行）→ 正文，见常量处注释
        if (hit.second == "A_HAN") {
            if (t.length > A_TITLE_MAX_LEN || RE_MID_PUNCT.containsMatchIn(t)) {
                // 破折号副标题救回：真书第一章"章名 ——副标题"超长被拦，截断取前半（subtitleCut）
                return if (subtitleCut(t) != null) GRADE_A to "A_HAN_SUB" else null
            }
            if (RE_SECTION_INLINE.containsMatchIn(t)) return null
        }
        return hit
    }

    /**
     * 副标题截断：返回破折号前的章名部分；救不回返回 null（维持原拦截）。
     * 真书《民法总则》第一章实证："第一章 私法绪论 ——私法社会、私法秩序、私法原则"
     * 25 字被超长护栏拦。破折号是中文书副标题强特征：前半须仍是干净章名
     * （不超长/无句读/无节号），后半须无行中句读且不以句末标点结尾
     * （防"第一章讲完——他走了。"式正文行）。尾页码/目录点线护栏在更早环节已拦。
     */
    private fun subtitleCut(t: String): String? {
        val dashIdx = t.indexOfFirst { c -> c.code == 0x2014 || c.code == 0x2500 || c.code == 0x2E3A || c == '-' || c == '－' }
        if (dashIdx <= 0) return null
        val head = t.substring(0, dashIdx).trim()
        val tail = t.substring(dashIdx).trimStart('—', '─', '-', '－', ' ', '　')
        if (head.isEmpty() || tail.isEmpty() || head.length > A_TITLE_MAX_LEN) return null
        if (RE_MID_PUNCT.containsMatchIn(head) || RE_SECTION_INLINE.containsMatchIn(head)) return null
        if (tail.last() in END_PUNCT || RE_MID_PUNCT.containsMatchIn(tail)) return null
        return head
    }

    // P8：前置标题族（前言/序言/写在前面/第X版序/凡例/作者简介…）→ FRONT（阅读页次级
    // 小字，与「开篇」一致）；后置族（参考文献/附录/后记/致谢/跋）→ BACK；目录章沿用 FRONT
    private fun roleFor(title: String): String = when {
        title == "目录" -> DbValues.ROLE_FRONT
        isBackMatterTitle(title) -> DbValues.ROLE_BACK
        isFrontMatterTitle(title) -> DbValues.ROLE_FRONT
        else -> DbValues.ROLE_BODY
    }

    // ---- 块与段落 ----

    /** 按空行分块（空行 = isBlank，含全角空格行）；返回每块的物理行列表。
     *  internal：KitBookParser（KIT 成品书直入）复用同一分块口径保证锚定行一致 */
    internal fun splitBlocks(text: String): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        for (line in text.split('\n')) {
            if (line.isBlank()) {
                if (cur.isNotEmpty()) {
                    blocks.add(cur)
                    cur = mutableListOf()
                }
            } else {
                cur.add(line)
            }
        }
        if (cur.isNotEmpty()) blocks.add(cur)
        return blocks
    }

    /** 块内行软合并成段（标题判定已先行完成，此处不再产生标题） */
    private fun parasOf(lines: List<String>, role: String, pageNo: Int? = null): List<ParsedPara> {
        val merged = softMerge(lines)
        return if (merged.isBlank()) emptyList() else listOf(ParsedPara(merged, role, pageNo))
    }

    /** 中文直接拼接；两侧都非 CJK 时补空格（英文单词边界） */
    internal fun softMerge(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty()) {
                val prev = sb.last()
                if (!isCjkLike(prev) && !isCjkLike(t.first())) sb.append(' ')
            }
            sb.append(t)
        }
        return sb.toString()
    }

    private fun isCjkLike(c: Char): Boolean =
        c in '\u3000'..'\u9FFF' || c in '\uFF00'..'\uFFEF'

    // ---- 兜底：无标题整书 / 超长盲切 ----

    private fun blindChapters(blocks: List<List<String>>, blockPages: List<Int?>): List<ParsedChapter> {
        // 与页号同步过滤（防 filter 后索引错位）：splitBlocks 产出的块必非空，过滤只是防御
        val paras = blocks.mapIndexed { i, b -> softMerge(b) to blockPages[i] }.filter { it.first.isNotBlank() }
        val total = paras.sumOf { it.first.length }
        if (total <= BLIND_CUT_THRESHOLD) {
            return listOf(
                ParsedChapter(
                    WHOLE_BOOK_TITLE,
                    paras.map { (text, page) -> ParsedPara(text, DbValues.ROLE_BODY, page) },
                    blindCut = true,
                ),
            )
        }
        val chapters = mutableListOf<ParsedChapter>()
        var acc = mutableListOf<ParsedPara>()
        var accLen = 0
        var n = 0
        for ((text, page) in paras) {
            acc.add(ParsedPara(text, DbValues.ROLE_BODY, page))
            accLen += text.length
            if (accLen >= BLIND_CUT_CHAPTER_CHARS) {
                n++
                chapters.add(ParsedChapter("第 $n 部分", acc.toList(), blindCut = true))
                acc = mutableListOf()
                accLen = 0
            }
        }
        if (acc.isNotEmpty()) {
            n++
            chapters.add(ParsedChapter("第 $n 部分", acc.toList(), blindCut = true))
        }
        return chapters
    }
}
