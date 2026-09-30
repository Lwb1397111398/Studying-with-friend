package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.mindmap.TreeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 全书总览三件套（计划 M6 §2.5）：三标签内容经校验后的载体 */
class OverviewBundle(
    val overviewMd: String,
    val treeText: String,
    val mainlineMd: String,
)

/** BookEntity.overviewJson 列的存储结构（payload 含版本与生成信息） */
@Serializable
data class OverviewPayload(
    val overviewMd: String,
    val treeText: String,
    val mainlineMd: String,
    val promptVersion: String,
    val model: String,
    val createdAt: Long,
)

object OverviewCodec {
    val VERSION = "overview-v1"
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(payload: OverviewPayload): String = json.encodeToString(OverviewPayload.serializer(), payload)

    /** 坏数据回退 null（UI 走重新生成，不崩溃） */
    fun decode(raw: String?): OverviewPayload? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(OverviewPayload.serializer(), raw) }.getOrNull()
    }
}

/**
 * 三中文标签提取（计划 M6 §2.5，与 M5 TaggedBundleParser 同模式）：
 * 非贪婪 DOT_ALL 逐标签提取、允许乱序；校验全过才返回
 * （总览/主线非空 + 导图树可解析非空），任一不满足返回 null——整包成功才落库。
 */
object OverviewParser {

    fun parse(raw: String): OverviewBundle? {
        fun tag(name: String): String? =
            Regex("<$name>\\s*(.*?)\\s*</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

        val overviewMd = tag("总览") ?: return null
        val treeText = tag("导图") ?: return null
        val mainlineMd = tag("主线") ?: return null

        if (TreeText.parse(treeText).isEmpty()) return null
        return OverviewBundle(overviewMd, treeText, mainlineMd)
    }
}

// ---------- 输入组装（计划 M6 §2.5：每章摘要 + 总量预算硬顶） ----------

/** user 消息总字数预算：超预算按档降级重建（与 M5 输入组装同思路） */
private const val OVERVIEW_BUDGET = 20_000

/** >20 章取前 20（AI 上下文稳定边界），附 truncated 标记 */
private const val OVERVIEW_CHAPTER_LIMIT = 20

/** 档位规格：Triple(每章 summary 截断, 每章 chain 截断) */
private val OVERVIEW_TIERS = listOf(600 to 300, 300 to 150, 150 to 75)

/**
 * 组装全书总览 user JSON。assetByChapterId 由调用方逐章 ChapterAssetDao.byChapter 组装
 * （≤20 次查询）；只收 promptVersion 匹配且 summaryMd 非空的章（未总结章进不了输入）。
 * 确定性降级：档 0 起逐档重建，≤20K 即用；终档为地板。
 */
fun buildOverviewUserJson(
    bookTitle: String,
    chapters: List<ChapterEntity>,
    assetByChapterId: Map<Long, ChapterAssetEntity>,
): String {
    repeat(OVERVIEW_TIERS.size) { tier ->
        val s = buildOverviewJson(bookTitle, chapters, assetByChapterId, tier)
        if (s.length <= OVERVIEW_BUDGET) return s
    }
    return buildOverviewJson(bookTitle, chapters, assetByChapterId, OVERVIEW_TIERS.size)
}

private fun buildOverviewJson(
    bookTitle: String,
    chapters: List<ChapterEntity>,
    assetByChapterId: Map<Long, ChapterAssetEntity>,
    tier: Int,
): String {
    val spec = OVERVIEW_TIERS.getOrElse(tier) { 150 to 75 }
    val usable = chapters.filter {
        assetByChapterId[it.id]?.let { a ->
            a.promptVersion == SummaryPlanner.SUMMARY_VERSION && a.summaryMd.isNotBlank()
        } == true
    }
    val kept = usable.take(OVERVIEW_CHAPTER_LIMIT)
    val truncated = usable.size > OVERVIEW_CHAPTER_LIMIT

    val entries = kept.map { c ->
        val a = assetByChapterId.getValue(c.id)
        buildString {
            append("{\"idx\":").append(c.idx)
            append(",\"title\":").append(jsonEsc(c.title))
            append(",\"summary\":").append(jsonEsc(a.summaryMd.trim().take(spec.first)))
            if (a.chainMd.isNotBlank()) append(",\"chain\":").append(jsonEsc(a.chainMd.trim().take(spec.second)))
            append('}')
        }
    }
    return buildString {
        append("{\"book\":").append(jsonEsc(bookTitle))
        append(",\"chapters\":[").append(entries.joinToString(",")).append(']')
        if (truncated) append(",\"truncated\":true")
        append('}')
    }
}
