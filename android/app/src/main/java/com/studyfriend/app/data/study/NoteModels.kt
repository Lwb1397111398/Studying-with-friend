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

/** prompt 输入文本（计划 §2.1）：每段截 2500 字、合计截 6000 字；余量耗尽即止，不出空串条目 */
fun ExplainUnit.textsForPrompt(): List<String> {
    val out = ArrayList<String>(members.size)
    var total = 0
    for (p in members) {
        if (total >= 6000) break // 余量耗尽：后继成员整体不进 prompt
        var t = p.text.take(2500)
        if (total + t.length > 6000) t = t.take(6000 - total)
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
