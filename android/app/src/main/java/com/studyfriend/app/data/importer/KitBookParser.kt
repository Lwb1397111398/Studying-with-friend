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

    /** 图描述行前缀（kit aivision 模板协议）：整行单独成段标 ROLE_FIGURE */
    private const val FIGURE_PREFIX = "〔图："

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
     * 按章表切章。[body] 须已剥元数据块。锚定规则：anchor（标题行前 8 字）为主判据、
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

        val result = mutableListOf<ParsedChapter>()
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
                        lines = lines.filterIndexed { i, _ -> i != anchorIdx }
                    }
                }
                paras.addAll(parasOfKit(lines, role, blockPages[bi]))
            }
            if (paras.isNotEmpty()) result.add(ParsedChapter(def.title, paras))
        }
        return result
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
            if (t.startsWith(FIGURE_PREFIX)) {
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
