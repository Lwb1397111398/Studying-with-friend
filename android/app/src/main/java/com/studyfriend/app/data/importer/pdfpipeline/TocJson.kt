package com.studyfriend.app.data.importer.pdfpipeline

import com.studyfriend.app.data.ai.JsonSlicer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 目录识别结果 JSON 解码（P3b-1）。纯函数、不做任何 I/O，缓存格式版本历史见下：
 * // v2: 2026-10-02, initial prompt/rules（entries[{title,page,level}]，非降序≥90%、≥3条、level=1≥1）
 * // v3: 2026-10-02, 真书 E2E 取证：title 上限 40→80（不当得利第266条引注条目实测 41 字，40 上限
 * //     静默丢弃）；新增 [normalizeTitle] 排版空格归一（目录页分散对齐的字间空格「绪 论」「第 515
 * //     号」被模型忠实还原，下游按无空格约定比对/切章）。
 */
object TocJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** 整体守卫阈值（P3b-1 计划案 §3.3） */
    private const val MIN_ENTRIES = 3
    private const val MIN_LEVEL1 = 1

    /** 相邻页码对中非降序（含相等）占比下限；v7.0 由 80% 收紧：20% 逆序属系统性错误 */
    private const val MIN_NON_DESCENDING_RATIO = 0.9

    /**
     * title 长度上限 80。BookParser 的 TITLE_MAX_LEN=40 是「正文行误判为标题」的
     * 护栏，不适用于目录条目：真书实测含法条引注的条目达 41 字，40 上限会静默丢弃。
     * 80 留约一倍余量；P3b-2 目录驱动切章时再统一两处口径。
     */
    private const val MAX_TITLE_LEN = 80

    /**
     * 排版空格归一：目录页为对齐美观在字间/数字两侧插空格（「绪 论」「第 515 号」），
     * 视觉模型忠实还原；下游比对与切章按无空格约定。规则=删除与「非 ASCII 字母数字」
     * 相邻的空白（含全角 U+3000，Kotlin \s 不含它须显式写）：「绪 论」→「绪论」、
     * 「第 515 号」→「第515号」；纯拉丁词间空格（"Appendix A"）两侧均为 ASCII
     * 字母，保留。internal 供单测直测。
     *
     * 已知取舍：中-拉丁相邻处的空格也会被删（「第一章 Introduction」→「第一章Introduction」）。
     * 不收窄为「两侧均非 ASCII 才删」——那会让「第 515 号」（数字属 ASCII）保留空格，
     * 破坏真书已验证的归一化结果；两害取其轻，且真书目录未见中-拉丁混排标题，
     * P3b-2 扩大书目验证时如命中再评估。
     */
    internal val TYPO_SPACE = Regex("(?<![A-Za-z0-9])[\\s\\u3000]+|[\\s\\u3000]+(?![A-Za-z0-9])")

    internal fun normalizeTitle(raw: String): String = TYPO_SPACE.replace(raw.trim(), "")

    /**
     * 解析模型输出的目录 JSON；任何环节不过 → null（调用方按「本次无目录」回退，
     * 半份目录比没有更危险）。单条目容错：title 非空白/≤80 字、page 正整数或 null、
     * level ∈ {1,2}，越界条目丢弃不整体失败。
     *
     * applyGuards=false 供 TocVisionParser 逐页调用：目录尾页可能只有两三条，
     * 页级守卫会误杀；守卫（含跨页页码连续性）由调用方对拼接全集调
     * [passesGuards] 一次判足。默认 true 供缓存读回与单页直用。
     */
    fun parse(raw: String, applyGuards: Boolean = true): List<TocEntry>? {
        return try {
            val obj = json.parseToJsonElement(JsonSlicer.slice(raw)).jsonObject
            val arr = obj["entries"] as? JsonArray ?: return null
            if (arr.isEmpty()) return null
            val entries = arr.mapNotNull { entry(it) }
            if (!applyGuards || passesGuards(entries)) entries else null
        } catch (e: Exception) {
            null
        }
    }

    private fun entry(el: JsonElement): TocEntry? {
        val o = el as? JsonObject ?: return null
        val title = (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let { normalizeTitle(it) } ?: return null
        if (title.isEmpty() || title.length > MAX_TITLE_LEN) return null
        val level = (o["level"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        if (level != 1 && level != 2) return null
        // page：显式 null/缺键 = 模型未见页码，保留；给错值（≤0/浮点/字符串/复合类型）
        // = 模型输出不可信，整条丢弃（与 null 降级区分，防脏页码混进下游）
        val page = when (val p = o["page"]) {
            null, is JsonNull -> null
            is JsonPrimitive -> {
                val v = p.content.toDoubleOrNull()
                if (p.isString || v == null || v <= 0 || v != v.toLong().toDouble()) return null
                v.toInt()
            }
            else -> return null
        }
        return TocEntry(title, page, level)
    }

    /**
     * 整体守卫（全部通过才非 null）：
     * ①条目数 ≥3 ②level=1 条目 ≥1 ③有页码条目按出现顺序、相邻对中
     * 非降序（≤，页码全同与密集 [1,1,2,2,3,3] 均放行）占比 ≥90%。
     * 页码少于 2 个时无相邻对，视为有序。
     */
    fun passesGuards(entries: List<TocEntry>): Boolean {
        if (entries.size < MIN_ENTRIES) return false
        if (entries.count { it.level == 1 } < MIN_LEVEL1) return false
        val pages = entries.mapNotNull { it.page }
        if (pages.size < 2) return true
        val pairs = pages.zipWithNext()
        val nonDescending = pairs.count { (a, b) -> a <= b }
        return nonDescending >= pairs.size * MIN_NON_DESCENDING_RATIO
    }
}
