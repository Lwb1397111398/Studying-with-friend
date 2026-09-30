package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.mindmap.TreeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 总结包五件套（计划 M5 §2.2）：五标签内容经校验后的载体 */
class SummaryBundle(
    val summaryMd: String,
    val mindmapTree: String,
    val memoryMd: String,
    val chainMd: String,
    val questions: List<QuizQuestion>,
)

/** 一道自测题（总计划 §4.3 任务 3：type 限 RECALL/TRUE_FALSE/CASE，考推演不考背诵） */
@Serializable
data class QuizQuestion(
    val type: String = "RECALL",
    val q: String = "",
    val a: String = "",
    val explain: String = "",
)

/** quizJson 列编解码：坏数据回退空列表，type 归一到白名单（计划 M5 §2.3） */
object QuizCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(QuizQuestion.serializer())
    private val TYPES = setOf("RECALL", "TRUE_FALSE", "CASE")

    fun encode(questions: List<QuizQuestion>): String = json.encodeToString(ser, questions)

    fun decode(raw: String?): List<QuizQuestion> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ser, raw) }.getOrDefault(emptyList())
            .map { it.copy(type = it.type.trim().uppercase().takeIf { t -> t in TYPES } ?: "RECALL") }
            .filter { it.q.isNotBlank() && it.a.isNotBlank() }
    }
}

/**
 * 五标签提取（计划 M5 §2.2）：非贪婪 DOT_ALL 逐标签提取、允许乱序；
 * 校验全过才返回（总结/记忆/串联非空 + 导图树可解析 + 自测 ≥1 题），任一不满足返回 null
 * ——整包成功才落库，不产残包。
 */
object TaggedBundleParser {

    fun parse(raw: String): SummaryBundle? {
        fun tag(name: String): String? =
            Regex("<$name>\\s*(.*?)\\s*</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        val summaryMd = tag("总结") ?: return null
        val mindmapTree = tag("导图") ?: return null
        val memoryMd = tag("记忆思路") ?: return null
        val chainMd = tag("串联") ?: return null
        val quizRaw = tag("自测") ?: return null

        if (TreeText.parse(mindmapTree).isEmpty()) return null
        val questions = QuizCodec.decode(stripFence(quizRaw))
        if (questions.isEmpty()) return null
        return SummaryBundle(summaryMd, mindmapTree, memoryMd, chainMd, questions.take(5))
    }

    /** 剥 AI 可能包在自测 JSON 外的 markdown 围栏（```json ... ```，含单行无换行/语言名大写变体） */
    private fun stripFence(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val body = s.substring(3)
            s = if (body.startsWith("json", ignoreCase = true)) body.substring(4).trim() else body.trim()
        }
        if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        return s
    }
}

// ---------- 输入组装（计划 M5 §2.1：只喂讲解要点而非全文 + 总量预算硬顶） ----------

/** user 消息总字数预算（评审 P1-1）：超预算按档降级重建 */
private const val INPUT_BUDGET = 24_000

/** >120 段为支持边界（终档地板）：前 120 段保留 text/note，其余只留 id+act */
private const val TIER4_LIMIT = 120

/** 档 0-3 规格：Triple(讲解段 text 长度, SKIP 段 text 长度或 null=不带, note friendly 长度或 0=只留 title) */
private val TIERS = listOf(
    Triple(500, 60, 200),
    Triple(300, null, 100),
    Triple(150, null, 50),
    Triple(80, null, 0),
)

/**
 * 组装总结包 user JSON。noteByAnchorIdx 以讲解卡锚段 idx 为键（调用方经
 * enumerateUnits + ParaIdsCodec 归位），保证 note 只挂在锚段条目上。
 * 确定性降级：档 0 起逐档重建，≤24K 即用；档 4 为终档（评审 P2-3）。
 */
fun buildSummaryUserJson(
    title: String,
    gist: String?,
    terms: List<String>,
    paragraphs: List<ParagraphEntity>,
    noteByAnchorIdx: Map<Int, ParaNoteEntity>,
): String {
    repeat(TIERS.size) { tier ->
        val s = buildUserJson(title, gist, terms, paragraphs, noteByAnchorIdx, tier)
        if (s.length <= INPUT_BUDGET) return s
    }
    return buildUserJson(title, gist, terms, paragraphs, noteByAnchorIdx, TIERS.size)
}

private fun buildUserJson(
    title: String,
    gist: String?,
    terms: List<String>,
    paragraphs: List<ParagraphEntity>,
    noteByAnchorIdx: Map<Int, ParaNoteEntity>,
    tier: Int,
): String {
    val spec = TIERS.getOrElse(tier) { Triple(80, null, 0) }
    var kept = 0
    val entries = ArrayList<String>(paragraphs.size)
    for (p in paragraphs) {
        val act = when (p.aiAction) {
            "EXPLAIN" -> "explain"
            "GROUP" -> "group"
            "SKIP" -> "skip"
            else -> continue // NONE 段不进总结输入
        }
        val withinLimit = kept < TIER4_LIMIT
        if (withinLimit) kept++
        val limited = tier >= TIERS.size && !withinLimit

        val sb = StringBuilder()
        sb.append("{\"id\":").append(p.idx).append(",\"act\":\"").append(act).append('"')
        if (!limited) {
            val text = when {
                act == "skip" -> spec.second?.let { p.text.take(it) }
                else -> p.text.take(spec.first)
            }
            text?.let { sb.append(",\"text\":").append(jsonEsc(it)) }
            p.why?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { sb.append(",\"why\":").append(jsonEsc(it.take(40))) }
            noteByAnchorIdx[p.idx]?.let { n ->
                sb.append(",\"note\":{\"t\":").append(jsonEsc(n.title))
                if (spec.third > 0) sb.append(",\"f\":").append(jsonEsc(n.friendly.take(spec.third)))
                sb.append('}')
            }
        }
        sb.append('}')
        entries.add(sb.toString())
    }
    return buildString {
        append("{\"title\":").append(jsonEsc(title))
        append(",\"gist\":").append(jsonEsc(gist.orEmpty()))
        append(",\"terms\":").append(jsonEsc(terms.joinToString("、")))
        append(",\"paragraphs\":[").append(entries.joinToString(",")).append("]}")
    }
}

/**
 * 总结包导出 Markdown（计划 M5 §3.6）：标题行 + 生成日期 + 五件套
 * （导图以代码块包裹的 TAB 树输出，人可读可改；自测题附答案与解析）。
 */
fun buildExportMd(
    bookTitle: String,
    chapterTitle: String,
    asset: ChapterAssetEntity,
    questions: List<QuizQuestion>,
    generatedAt: String,
): String = buildString {
    append("# ").append(chapterTitle).append(" · 总结包\n\n")
    append("> 书：《").append(bookTitle).append("》 ｜ 生成：").append(generatedAt)
    append(" ｜ 模型：").append(asset.model).append("\n\n")

    append("## 总结\n\n").append(asset.summaryMd.trim()).append("\n\n")
    append("## 思维导图\n\n```text\n").append(asset.mindmapTree.trim()).append("\n```\n\n")
    append("## 记忆思路\n\n").append(asset.memoryMd.trim()).append("\n\n")
    append("## 串联\n\n").append(asset.chainMd.trim()).append("\n\n")

    append("## 自测题\n\n")
    if (questions.isEmpty()) {
        append("（本包无自测题）\n")
    } else {
        questions.forEachIndexed { i, q ->
            append(i + 1).append(". [").append(typeLabelOf(q.type)).append("] ").append(q.q).append("\n")
            append("   答案：").append(q.a).append("\n")
            if (q.explain.isNotBlank()) append("   解析：").append(q.explain).append("\n")
        }
    }
}

private fun typeLabelOf(type: String) = when (type) {
    "TRUE_FALSE" -> "判断"
    "CASE" -> "案例"
    else -> "回想"
}
