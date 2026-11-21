package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues
import org.json.JSONObject

/** KIT 章表条目（bookocr-kit pack 元数据块 chapters[] 的一行） */
data class KitChapter(
    val title: String, // t：章标题
    val page: Int, // p：起始 PDF 页号（与〔页N〕标记同源）
    val level: Int, // lv：1=章（App 章表只收 1 级）2=节（标题行留正文，不切章）
    val role: String, // r：FRONT/TOC/BODY/BACK
    val anchor: String, // a：标题行前 8 字锚（切章主判据）
)

/** KIT 成品书元数据（txt 头部〔KIT-书 开始〕{json}〔KIT-书 结束〕单行块） */
data class KitMeta(
    val title: String,
    val pages: Int,
    val chapters: List<KitChapter>,
    val version: String,
)

/**
 * bookocr-kit 成品书直入解析器：章界以电脑侧 chapters 对账结果为权威，App 端不再猜
 * （无正则切章/无目录探针/无视觉兜底/无盲切）。元数据块缺失或损坏时 [extract] 返回
 * null，调用方回退 BookParser 通用路径——绝不因协议问题导致导入失败。
 */
object KitBookParser {

    const val META_BEGIN = "〔KIT-书 开始〕"
    const val META_END = "〔KIT-书 结束〕"

    /** 图描述行前缀（kit 协议）：整行单独成段标 ROLE_FIGURE。全角为规范形态；半角冒号
     *  是子代理偶发笔误（kit 质检 FIG_MARK 容 [:：] 放行、pack 对子代理行原样保留），
     *  App 侧一并认领，防图题行混进正文段。 */
    private val FIGURE_PREFIXES = listOf("〔图：", "〔图:")

    /** 从成品 txt 提取 KIT 元数据；无块/JSON 损坏/kit 版本不认/章表为空 → null */
    fun extract(raw: String): KitMeta? {
        val begin = raw.indexOf(META_BEGIN)
        if (begin < 0) return null
        val jsonStart = begin + META_BEGIN.length
        val end = raw.indexOf(META_END, jsonStart)
        if (end < 0) return null
        val obj = try {
            JSONObject(raw.substring(jsonStart, end))
        } catch (e: Exception) {
            return null
        }
        return parseMeta(obj)
    }

    /** 解析 kit:1 元数据 JSON 对象（txt 头部块与 KIT 图包 kit.json 共用口径） */
    fun parseMeta(obj: JSONObject): KitMeta? {
        if (obj.optInt("kit", 0) != 1) return null
        val arr = obj.optJSONArray("chapters") ?: return null
        val chapters = mutableListOf<KitChapter>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("t").trim()
            if (t.isEmpty()) continue
            chapters.add(
                KitChapter(
                    title = t,
                    page = o.optInt("p", 1),
                    level = o.optInt("lv", 1),
                    role = o.optString("r", "BODY"),
                    anchor = o.optString("a"),
                ),
            )
        }
        if (chapters.isEmpty()) return null
        return KitMeta(
            title = obj.optString("title"),
            pages = obj.optInt("pages", 0),
            chapters = chapters.toList(),
            version = obj.optString("v"),
        )
    }

    /** 剥掉元数据块（连同行尾换行，防头部残留空块）；无块/块不完整时原样返回 */
    fun stripMeta(raw: String): String {
        val begin = raw.indexOf(META_BEGIN)
        if (begin < 0) return raw
        val end = raw.indexOf(META_END, begin)
        if (end < 0) return raw
        var after = end + META_END.length
        if (after < raw.length && raw[after] == '\r') after++
        if (after < raw.length && raw[after] == '\n') after++
        return raw.substring(0, begin) + raw.substring(after)
    }

    /**
     * 书名预填：KIT 元数据 title（电脑侧 init 的工程名=真书名）优先；zip 图包文件名固定
     * 带「_Kit图包」尾巴、2026-11-15 起还带「导入这个_」前缀（电脑侧 pack 产出标记，防与
     * txt/插图版 PDF 混淆），kitTitle 空白走文件名兜底时把已知标记都剥掉。
     * kit title 本身源自书源文件名时可能带网购站源尾巴（实景体验轮 2026-11-21：《袖珍
     * 民法典评注》书架实录 `_（z-library.sk,_1lib.sk,_z-lib.sk）`），一并剥掉。纯 JVM 供单测。
     */
    fun preferredTitle(kitTitle: String?, fileName: String): String =
        kitTitle?.takeIf { it.isNotBlank() }?.let(::cleanSiteTail)?.takeIf { it.isNotBlank() }
            ?: cleanSiteTail(
                fileName.substringBeforeLast('.')
                    .removeSuffix("_Kit图包").removePrefix("导入这个_"),
            ).ifBlank { "未命名" }

    /** 网购电子书文件名惯带的站源尾巴：`_（z-library.sk,_1lib.sk,_z-lib.sk）` 形态（连前面的下划线一起吃） */
    private val RE_SITE_TAIL = Regex("""_*[（(](?:z-library|1lib|z-lib)\.sk[^)）]*[)）]\s*$""")

    /** 书名卫生：剥网购站源尾巴（导入页 kit title 预填与文件名兜底共用） */
    internal fun cleanSiteTail(s: String): String = s.replace(RE_SITE_TAIL, "").trim()

    /** 按章表切章。[body] 须已剥元数据块。锚定规则：anchor（标题行前 8 字）为主判据、
     * 起始页号过闸（blockPage >= p，同源页号恰相等，闸的作用是拦住目录页同文条目误锚），
     * 块指针随章表顺序单调推进。首章锚未命中兜底从头收（封面/版权不丢）；其余章锚未
     * 命中跳过该章——段落归前章，不按页号猜切。锚行本身从起点块剔除（标题不进正文）。
     */
    fun parse(body: String, meta: KitMeta): List<ParsedChapter> {
        val text = body.replace("\r\n", "\n").replace('\r', '\n')
        val blocks0 = BookParser.splitBlocks(text)
        val (blocks, blockPages) = BookParser.stripPageMarks(blocks0)
        if (blocks.isEmpty()) return emptyList()

        val defs = meta.chapters.filter { it.level <= 1 }.ifEmpty { meta.chapters }

        val starts = IntArray(defs.size) { -1 }
        var searchFrom = 0
        for ((ci, def) in defs.withIndex()) {
            var bi = searchFrom
            while (bi < blocks.size) {
                val page = blockPages[bi]
                val pageOk = page == null || page >= def.page
                val hit = def.anchor.isBlank() || blocks[bi].any { anchorHit(it, def.anchor) }
                if (pageOk && hit) break
                bi++
            }
            if (bi < blocks.size) {
                starts[ci] = bi
                searchFrom = bi + 1
            } else if (ci == 0) {
                starts[ci] = 0
            }
        }

        val collected = mutableListOf<ParsedChapter>()
        for (ci in defs.indices) {
            val start = starts[ci]
            if (start < 0) continue // 锚未命中：跳章，段落归前章
            val endExclusive = defs.indices
                .drop(ci + 1)
                .firstOrNull { starts[it] >= 0 }
                ?.let { starts[it] }
                ?: blocks.size
            val def = defs[ci]
            val role = mapRole(def.role)
            val paras = mutableListOf<ParsedPara>()
            for (bi in start until endExclusive) {
                var lines = blocks[bi]
                if (bi == start && def.anchor.isNotBlank()) {
                    val anchorIdx = lines.indexOfFirst { anchorHit(it, def.anchor) }
                    if (anchorIdx >= 0) {
                        // 锚行剥标题留余文（实景体验轮 2026-11-21 内容丢失实录）：kit txt
                        // 章头常与正文同行（「第三节代理终止第一百七十三条 [委托代理的终止]…」
                        // 是一行），整行剔除会把粘住的条文一起删掉——只剥标题前缀，余文成段
                        lines = lines.mapIndexed { i, l ->
                            if (i != anchorIdx) l else stripTitlePrefix(l, def.title)
                        }.filter { it.isNotBlank() }
                    }
                }
                paras.addAll(parasOfKit(lines, role, blockPages[bi]))
            }
            collected.add(ParsedChapter(def.title, paras))
        }
        // 空章标题前挂（实景体验轮 2026-11-21）：《袖珍民法典评注》章表 章/节混级——章头行
        // 自成一块、正文全部归属其下首个节章，切出的「第二章自然人」只有标题块 0 段，直接
        // 丢会连带丢失章节层级上下文。空章标题排队挂到下一个有正文章（「章名 · 节名」），
        // 连续空章自然成链；尾部空章无后章可挂，随弃。
        val merged = mutableListOf<ParsedChapter>()
        val pendingTitles = ArrayDeque<String>()
        for (ch in collected) {
            if (ch.paras.isEmpty()) {
                pendingTitles.addLast(ch.title)
                continue
            }
            val title = if (pendingTitles.isEmpty()) {
                ch.title
            } else {
                val t = (pendingTitles.toList() + ch.title).joinToString(SEP_TITLE_MERGE)
                pendingTitles.clear()
                t
            }
            merged.add(ch.copy(title = title))
        }
        return filterFrontMatter(merged)
    }

    /** 空章标题并入后章的连接符 */
    internal const val SEP_TITLE_MERGE = " · "

    /**
     * 剥掉行首的章标题（容空白差异），返回余文；行不含完整标题前缀时原样返回（宁漏不删）。
     * 标题即整行时返回空串（等价旧的整行剔除）。
     */
    internal fun stripTitlePrefix(line: String, title: String): String {
        val t = title.filter { !it.isWhitespace() }
        if (t.isEmpty()) return line
        var li = 0
        for (tc in t) {
            while (li < line.length && line[li].isWhitespace()) li++
            if (li >= line.length || line[li] != tc) return line
            li++
        }
        return line.substring(li).trim()
    }

    /** 简目形态行：以「/ 075」式页码尾巴收尾（编扉页/分编目录页的行特征，实景轮 2026-11-21） */
    private val RE_TOC_TAIL = Regex("""/\s*\d{1,4}\s*$""")

    /** 编级标题：第X编（编扉页残片判据用） */
    private val RE_VOLUME_TITLE = Regex("""^第[一二三四五六七八九十百]+编""")

    /** 目录/扉页行的无标点特征：不含任何句读标点（正文段几乎必有标点） */
    private val RE_NO_PUNCT = Regex("""^[^，。；：！？、,.;:!?]*$""")

    /**
     * 编扉页假章剔除（实景体验轮 2026-11-21）：《民法总论_Kit图包》实录——书前部的
     * 「编扉页」（印编名+该编简目，1~5 页）被电脑侧标 BODY 成章，与后文真章重名混排、
     * 顺序错乱，用户点进去全是目录行。三类判据（只动 KIT 链路，TXT/PDF 不经此处）：
     * 1. 编级残片：章名「第X编」且全文 <200 字（p30 扉页碎页 3 段 23 字实录）；
     * 2. 简目形态章：段数 ≥5、中位段长 ≤40 字，且满足其一——
     *    a) ≥70% 段以「/ 页码」尾巴收尾（分编总目录页实录：p18~29「第一章 民法基础 / 003」）；
     *    b) ≥70% 段无句读标点且全文 <3000 字（编扉页纯标题行实录：p15~17「第三章 法律行为
     *       的本质」无页码尾巴；无标点占比约束下正文章不可能命中）；
     * 3. 重名兜底：剔除后仍同名的 BODY 章（「第九章　代理」扉页行 1 段 vs 正文 354 段
     *    实录），保留总字数最大者。目录/序言章（TOC/FRONT）不参与。
     * 全部判据为保守约束（宁漏不误杀），纯函数供单测。
     */
    internal fun filterFrontMatter(chapters: List<ParsedChapter>): List<ParsedChapter> {
        val isBody = chapters.map { it.paras.firstOrNull()?.role == DbValues.ROLE_BODY }
        val drop = BooleanArray(chapters.size)
        chapters.forEachIndexed { i, ch ->
            if (!isBody[i]) return@forEachIndexed
            // 判据 1：编级残片（「第X编」且全文 <200 字）
            val chars = ch.paras.sumOf { it.text.length }
            if (chars < 200 && RE_VOLUME_TITLE.containsMatchIn(ch.title.trim())) {
                drop[i] = true
                return@forEachIndexed
            }
            // 判据 2：简目形态章（段数 ≥5 + 中位段长 ≤40 + 页码尾巴/无标点短行两形态其一）
            if (ch.paras.size >= 5) {
                val lens = ch.paras.map { it.text.length }.sorted()
                val median = lens[lens.size / 2]
                if (median <= 40) {
                    val tailHits = ch.paras.count { RE_TOC_TAIL.containsMatchIn(it.text.trim()) }
                    val noPunctHits = ch.paras.count { RE_NO_PUNCT.matches(it.text.trim()) }
                    val tailOk = tailHits * 10 >= ch.paras.size * 7
                    val noPunctOk = noPunctHits * 10 >= ch.paras.size * 7 && chars < 3000
                    if (tailOk || noPunctOk) drop[i] = true
                }
            }
        }
        // 判据 3：重名兜底——幸存 BODY 章去空白同名时，仅当小章是残片形态（<200 字，与
        // 判据 1 同阈值）才剔大留小。《袖珍民法典评注》实录（2026-11-21）：民法典六编各有
        // 「第一章一般规定」「第一节一般规定」，真重名章动辄数千字，一律留大剔小会把真章
        // 连同评注正文整章删除（内容丢失）；wave-1 假章形态（「第九章　代理」1 段 5 字）
        // 仍会被剔。
        val survivors = chapters.withIndex().filter { (i, _) -> isBody[i] && !drop[i] }
        val byTitle = survivors.groupBy { (_, ch) -> ch.title.filter { c -> !c.isWhitespace() } }
        for ((i, ch) in survivors) {
            val key = ch.title.filter { c -> !c.isWhitespace() }
            val group = byTitle.getValue(key)
            if (group.size <= 1) continue
            val best = group.maxBy { (_, c) -> c.paras.sumOf { p -> p.text.length } }
            if (best.index != i && ch.paras.sumOf { p -> p.text.length } < 200) drop[i] = true
        }
        return chapters.filterIndexed { i, _ -> !drop[i] }
    }

    /** kit 章表 role → ParagraphEntity role（TOC 章整章段标 TOC：次级展示 + AI 跳过） */
    private fun mapRole(r: String): String = when (r) {
        "FRONT" -> DbValues.ROLE_FRONT
        "TOC" -> DbValues.ROLE_TOC
        "BACK" -> DbValues.ROLE_BACK
        else -> DbValues.ROLE_BODY
    }

    /** 锚命中：原样前缀，或去空白后前缀（目录标题行「目 录」带空格、锚「目录」无空格） */
    private fun anchorHit(line: String, anchor: String): Boolean {
        val t = line.trim()
        if (t.startsWith(anchor)) return true
        return t.filter { !it.isWhitespace() }
            .startsWith(anchor.filter { !it.isWhitespace() })
    }

    /**
     * 块 → 段：首行〔脚注〕协议行剥标标 FOOTNOTE（与 BookParser 同口径）；〔图：…〕行
     * 逐行单独成段标 FIGURE（图卡展示 + AI 跳过）；其余行 softMerge 成一段归章 role。
     */
    private fun parasOfKit(lines: List<String>, role: String, pageNo: Int?): List<ParsedPara> {
        if (lines.isEmpty()) return emptyList()
        val first = lines.first().trim()
        if (first.startsWith(BookParser.FOOTNOTE_MARK)) {
            return listOf(
                ParsedPara(first.removePrefix(BookParser.FOOTNOTE_MARK), DbValues.ROLE_FOOTNOTE, pageNo),
            )
        }
        val paras = mutableListOf<ParsedPara>()
        val plain = mutableListOf<String>()
        for (line in lines) {
            val t = line.trim()
            if (FIGURE_PREFIXES.any { t.startsWith(it) }) {
                paras.add(ParsedPara(t, DbValues.ROLE_FIGURE, pageNo))
            } else {
                plain.add(line)
            }
        }
        val merged = BookParser.softMerge(plain)
        if (merged.isNotBlank()) paras.add(ParsedPara(merged, role, pageNo))
        return paras
    }
}
