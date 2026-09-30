package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ParagraphEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 讲解单元（计划 M4b §2.1）：anchor 决定卡片渲染位置；members 参与 prompt。
 * 枚举按 groupId 值聚合（与 M4a countUnits 的 groupId 集合计数口径一致，
 * 保证"共 N 个讲解单元"文案与卡数一致）。
 */
class ExplainUnit(val anchor: ParagraphEntity, val members: List<ParagraphEntity>)

/**
 * 段落 → 讲解单元：
 * - EXPLAIN 段 → 单段单元
 * - GROUP 按 groupId 分组：锚 = 组内 idx==groupId 的成员；锚缺失（孤儿组员）取组内最小 idx，仍成组单元
 * - 组员不足 2 段退化为单段单元；超过 3 段截前 3 段；SKIP/NONE/groupId null 不成单元
 */
fun enumerateUnits(paragraphs: List<ParagraphEntity>): List<ExplainUnit> {
    val groups = LinkedHashMap<Long, MutableList<ParagraphEntity>>()
    val units = ArrayList<ExplainUnit>()
    for (p in paragraphs) {
        when (p.aiAction) {
            "EXPLAIN" -> units.add(ExplainUnit(p, listOf(p)))
            "GROUP" -> p.groupId?.let { gid -> groups.getOrPut(gid) { ArrayList() }.add(p) }
        }
    }
    for ((gid, raw) in groups) {
        val members = raw.sortedBy { it.idx }.take(3)
        val anchor = members.firstOrNull { it.idx.toLong() == gid } ?: members.first()
        units.add(ExplainUnit(anchor, if (members.size < 2) listOf(anchor) else members))
    }
    return units.sortedBy { it.anchor.idx }
}

/** prompt 输入文本：每段截 10000 字、合计截 40000 字（十万级上下文下仍留足输出与思考余量）；余量耗尽即止，不出空串条目 */
fun ExplainUnit.textsForPrompt(): List<String> {
    val out = ArrayList<String>(members.size)
    var total = 0
    for (p in members) {
        if (total >= 40_000) break // 余量耗尽：后继成员整体不进 prompt
        var t = p.text.take(10_000)
        if (total + t.length > 40_000) t = t.take(40_000 - total)
        out.add(t)
        total += t.length
    }
    return out
}

/** AI 讲解输出（总计划 §75 规格，字段名沿用） */
@Serializable
data class NotePlan(
    val title: String = "",
    val friendly: String = "",
    val analogy: String = "",
    @SerialName("key_points") val keyPoints: List<String> = emptyList(),
    @SerialName("memory_hook") val memoryHook: String = "",
    @SerialName("check_questions") val checkQuestions: List<CheckQuestion> = emptyList(),
)

@Serializable
data class CheckQuestion(val q: String = "", val a: String = "")

/** 容错规整（计划 M4b §2.3）：friendly 是讲解主体，缺失按单元失败走重试 */
object NoteParser {

    fun normalize(plan: NotePlan, anchorText: String): NotePlan {
        if (plan.friendly.isBlank()) throw PlannerException("讲解主体缺失")
        return NotePlan(
            title = plan.title.trim().ifBlank { anchorText.take(16) },
            friendly = plan.friendly.trim(),
            analogy = plan.analogy.trim().take(80),
            keyPoints = plan.keyPoints.map { it.trim() }.filter { it.isNotEmpty() }.take(5).map { it.take(30) },
            memoryHook = plan.memoryHook.trim().take(40),
            checkQuestions = plan.checkQuestions.filter { it.q.isNotBlank() && it.a.isNotBlank() }.take(3),
        )
    }
}

/**
 * 讲解卡标签文本解析（v2，替代 JSON 输出）：
 * 长段讲解混在 JSON 字符串里既难写又易被截断毁整包，改为与总结包同款的中文标签段格式。
 * <讲解> 必需；其余标签可缺省（<类比>/<钩子> 整标签省略即空值）。
 * 截断容忍：<讲解> 未闭合（finish=length）时取开标签到末尾的全部内容。
 */
object NoteTaggedParser {

    fun parse(raw: String): NotePlan? {
        fun tag(name: String): String? =
            Regex("<$name>\\s*(.*?)\\s*</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        // 截断容忍：闭合标签缺失时兜底取开标签之后的内容（仅对必需的 <讲解>）
        val friendly = tag("讲解")
            ?: Regex("<讲解>\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
                .find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        return NotePlan(
            title = tag("标题").orEmpty(),
            friendly = friendly,
            analogy = tag("类比").orEmpty(),
            keyPoints = tag("要点").orEmpty().lines().mapNotNull(::stripBullet),
            memoryHook = tag("钩子").orEmpty(),
            checkQuestions = parseQuestions(tag("自测").orEmpty()),
        )
    }

    /** 要点行容错：剥 "-", "•", "*", "1."、"1、" 等列表前缀；空行返回 null */
    private fun stripBullet(line: String): String? {
        var t = line.trim()
        if (t.isEmpty()) return null
        t = t.trimStart('-', '•', '*', '·', ' ')
        t = t.replaceFirst(Regex("^\\d+\\s*[.、．]\\s*"), "")
        return t.trim().takeIf { it.isNotEmpty() }
    }

    /** 自测段容错：问/答（或 Q/A）成对逐条提取；缺问的条目丢弃 */
    private fun parseQuestions(raw: String): List<CheckQuestion> {
        if (raw.isBlank()) return emptyList()
        val questions = ArrayList<StringBuilder>()
        val answers = ArrayList<StringBuilder>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val qMatch = Regex("^(?:问|Q)[:：.、]?\\s*(.*)$").find(t)
            val aMatch = Regex("^(?:答|A)[:：.、]?\\s*(.*)$").find(t)
            when {
                qMatch != null -> {
                    questions.add(StringBuilder(qMatch.groupValues[1]))
                    answers.add(StringBuilder())
                }
                aMatch != null && questions.isNotEmpty() -> answers.last().append(aMatch.groupValues[1])
                questions.isNotEmpty() -> {
                    // 续行：答案已开笔则归答案，否则并入题干
                    if (answers.last().isNotEmpty()) answers.last().append(t)
                    else questions.last().append(t)
                }
            }
        }
        return questions.indices
            .map { CheckQuestion(questions[it].toString().trim(), answers[it].toString().trim()) }
            .filter { it.q.isNotBlank() && it.a.isNotBlank() }
    }
}

/** paraIds 确定性编解码：`[1,2,3]` 同单元恒同串——同 paraIds 唯一化与 UI 映射的基础 */
object ParaIdsCodec {
    private val json = Json
    private val ser = ListSerializer(Long.serializer())

    fun encode(ids: List<Long>): String = json.encodeToString(ser, ids)

    fun decode(raw: String?): List<Long> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(ser, raw) }.getOrDefault(emptyList())
}

/** key_points / check_questions 等字符串列表列的通用解码（坏数据回退空列表） */
fun decodeStringList(raw: String?): List<String> =
    if (raw.isNullOrBlank()) emptyList()
    else runCatching { Json.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrDefault(emptyList())

/** check_questions 列解码（M5 自测卡消费；坏数据回退空列表） */
fun decodeCheckQuestions(raw: String?): List<CheckQuestion> =
    if (raw.isNullOrBlank()) emptyList()
    else runCatching { Json.decodeFromString(ListSerializer(CheckQuestion.serializer()), raw) }.getOrDefault(emptyList())
