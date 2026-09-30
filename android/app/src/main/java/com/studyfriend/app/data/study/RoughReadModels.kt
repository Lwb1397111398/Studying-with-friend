package com.studyfriend.app.data.study

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** AI 粗读输出（宽松解析，字段容错交给 PlanParser） */
@Serializable
data class RoughReadPlan(
    val gist: String = "",
    @SerialName("key_terms") val keyTerms: List<KeyTerm> = emptyList(),
    val paragraphs: List<PlanEntry> = emptyList(),
)

@Serializable
data class KeyTerm(val term: String, val plain: String)

/** id/group 宽松解析：AI 把数字写成字符串也能过（计划 M4a §2.3） */
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val prim = (decoder as JsonDecoder).decodeJsonElement()
        // null 解成 -1（必不在任何块内）→ 整条按幻觉编号丢弃，避免撞上真实 idx=0 被"后到生效"覆盖
        if (prim is JsonNull) return -1
        return prim.jsonPrimitive.intOrNull ?: prim.jsonPrimitive.content.trim().toDoubleOrNull()?.toInt() ?: -1
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

@Serializable
data class PlanEntry(
    @Serializable(with = LenientIntSerializer::class) val id: Int,
    val action: String,
    val group: List<@Serializable(with = LenientIntSerializer::class) Int> = emptyList(),
    val why: String? = null,
)

/** 归并调用输出 */
@Serializable
data class MergedOverview(val gist: String, @SerialName("key_terms") val keyTerms: List<KeyTerm> = emptyList())

/** 解析并校验后的一个标注决定 */
enum class AiAction { NONE, SKIP, EXPLAIN, GROUP }

data class Decision(
    val paraIdx: Int,
    val action: AiAction,
    val groupStart: Int?, // 仅 GROUP：组首段 idx（groupId 写库值）
    val why: String?,
)

/** 块缺标率超阈值等不可自动修复的解析失败 */
open class PlannerException(message: String) : Exception(message)

/** 无 API Key/密钥失效：UI 据此引导"去设置"（结构化原因，避免文案字符串耦合） */
class MissingKeyException(message: String) : PlannerException(message)

/**
 * AI 输出 → 合法决定集（计划 M4a §2.3）：
 * 归一化 → 越界/非法丢弃 → group 连续与长度校验（违者降级 explain）→ 重复 id 后到生效 → 缺标率阈值。
 * groupId = 组首段 idx（章内唯一、可重建，M4b 枚举讲解单元以此为准）。
 */
object PlanParser {

    const val MISSING_MAX_RATIO = 0.3

    fun parse(plan: RoughReadPlan, blockIdxs: Set<Int>): Map<Int, Decision> {
        val result = LinkedHashMap<Int, Decision>()
        for (raw in plan.paragraphs) {
            val id = raw.id
            if (id !in blockIdxs) continue // 幻觉编号：丢弃
            val action = when (raw.action.trim().lowercase()) {
                "skip" -> AiAction.SKIP
                "explain" -> AiAction.EXPLAIN
                "group" -> {
                    val g = raw.group
                    val ok = g.size in 2..3 && g.all { it in blockIdxs } &&
                        g == (g.min()..g.max()).toList()
                    if (ok) AiAction.GROUP else AiAction.EXPLAIN // 单段/空/超长/不连续/含块外 → 降级
                }
                else -> continue // 非法 action：丢弃
            }
            result[id] = if (action == AiAction.GROUP) {
                val g = raw.group
                Decision(id, AiAction.GROUP, groupStart = g.min(), why = raw.why?.take(40))
            } else {
                Decision(id, action, groupStart = null, why = raw.why?.take(40))
            }
        }
        val missing = blockIdxs.count { it !in result }
        if (blockIdxs.isNotEmpty() && missing > blockIdxs.size * MISSING_MAX_RATIO) {
            throw PlannerException("本块缺标 $missing/${blockIdxs.size} 超过 30%")
        }
        return result
    }

    /**
     * 本块讲解单元预算（计划 M4a §2.1）：
     * 多块 = clamp(1..4, round(10 × 本块字数占比))；单块 = 10（整章由 prompt"3~10"条目约束）。
     */
    fun unitBudget(blockChars: Int, chapterChars: Int, blockTotal: Int): Int {
        if (blockTotal <= 1 || chapterChars <= 0) return 10
        val raw = (10.0 * blockChars / chapterChars).roundToInt()
        return raw.coerceIn(1, 4)
    }
}
