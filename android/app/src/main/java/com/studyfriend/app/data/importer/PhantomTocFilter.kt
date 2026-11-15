package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues

/**
 * 简目假章防御（2026-11-15，《民法物权_Kit图包》章序号乱根因修复）：
 * bookocr-kit 章表把书前/书末简目页（逐章列「第X节」标题的目录页）误切成了「第X章」正文章，
 * App 端 KIT 链路照单全收 → 章列表出现 idx2=第六章排在 idx4=第一章前面。本对象提供两层处置：
 * ① KIT 导入链路 [stripParsed]（重导/新导入防新增）；② DB 迁移 v11→v12 用 [decide] 清理存量书。
 * 判据核心 [decide] 纯 JVM 单源，两条链路不许各自实现（防漂移）。
 *
 * 真书实证判据来源（模拟器 book14 全库扫描，命中 8 纯假章 + 1 混合章、其余书零误杀）：
 * - 纯简目假章：章标题「第X章」+ 全部 BODY 段（4~8 段）都以「第X节」开头（每段 6~18 字目录条目）。
 * - 混合章（书末简目+脚注汇总）：8 段简目条目 + 「注释」段 + 2 万字引注大段——简目段删、
 *   实质内容保留并按首个非简目段改名（「注释」）。
 * - 必须不误杀的邻居：法条章（「第X条」开头+条文正文，无「第X节」段）、真文章（第一段
 *   虽是「第一节」但随后是大量非简目段）。
 */
object PhantomTocFilter {

    /** 章标题形态：第X章（简目假章只会以章级标题出现；节级标题不成章） */
    private val RE_CHAPTER_TITLE = Regex("^第[一二三四五六七八九十百千零〇两]+章")

    /** 简目条目形态：「第一节　概说」（段首第X节）——简目页逐章列节标题的特征 */
    private val RE_SECTION_ENTRY = Regex("^第[一二三四五六七八九十百千零〇两]+节")

    /** 判定处置动作（纯函数，导入链路与 DB 迁移共用）。[bodyTexts] 取章内 role=BODY 段文本。 */
    fun decide(title: String, bodyTexts: List<String>): Decision {
        if (!RE_CHAPTER_TITLE.containsMatchIn(title.trim())) return Decision.Keep
        if (bodyTexts.size < 2) return Decision.Keep
        val nonSec = bodyTexts.filterNot { RE_SECTION_ENTRY.containsMatchIn(it.trim()) }
        if (nonSec.isEmpty()) {
            // 纯简目假章：段数上限 15 拦「简目特别长」的极端书——超限保守保留（宁漏勿杀）
            return if (bodyTexts.size <= 15) Decision.DropWhole else Decision.Keep
        }
        // 混合章守卫：非简目段必须极少（≤3）且存在超长实质段（≥2000 字，书末注释/引注汇总特征）。
        // 真文章的非简目段占绝大多数、法条章无「第X节」段，均到不了这里；到不了就 Keep。
        val mixedOk = nonSec.size <= 3 && nonSec.any { it.length >= 2000 }
        return if (mixedOk) {
            // 改名取首个非简目段前 10 字（「注释」2 字足矣，防名字撞真章标题）
            Decision.DropEntries(nonSec.first().trim().take(10))
        } else {
            Decision.Keep
        }
    }

    /**
     * KIT 导入链路应用：剔除假章/清理混合章，返回清理后章列表与统计（parseNote 用）。
     * 无可处置章时原列表原样返回（零改动路径）。
     */
    fun stripParsed(chapters: List<ParsedChapter>): StripResult {
        var dropped = 0
        var renamed = 0
        var droppedParas = 0
        val out = mutableListOf<ParsedChapter>()
        for (ch in chapters) {
            val bodyTexts = ch.paras.filter { it.role == DbValues.ROLE_BODY }.map { it.text }
            when (val d = decide(ch.title, bodyTexts)) {
                Decision.Keep -> out.add(ch)
                is Decision.DropWhole -> {
                    dropped++
                    droppedParas += ch.paras.size
                }
                is Decision.DropEntries -> {
                    renamed++
                    val kept = ch.paras.filterNot { it.role == DbValues.ROLE_BODY && isSectionEntry(it.text) }
                    droppedParas += ch.paras.size - kept.size
                    out.add(ch.copy(title = d.newTitle, paras = kept))
                }
            }
        }
        val changed = dropped > 0 || renamed > 0
        return StripResult(
            chapters = if (changed) out else chapters,
            droppedChapters = dropped,
            renamedChapters = renamed,
            droppedParas = droppedParas,
        )
    }

    /** 简目条目判定公开给迁移侧复用（SQL 拼不出 Kotlin 正则，段落 id 在 Kotlin 侧算） */
    fun isSectionEntry(text: String): Boolean = RE_SECTION_ENTRY.containsMatchIn(text.trim())

    /** 处置动作：保留 / 整章剔除 / 删简目段并改名（混合章） */
    sealed interface Decision {
        data object Keep : Decision
        data object DropWhole : Decision
        data class DropEntries(val newTitle: String) : Decision
    }

    /** [stripParsed] 结果：cleaned 列表 + parseNote 统计 */
    data class StripResult(
        val chapters: List<ParsedChapter>,
        val droppedChapters: Int,
        val renamedChapters: Int,
        val droppedParas: Int,
    ) {
        /** 人话备注（无处置返回 null）；「简目」老板可懂：目录页被误当成了章 */
        fun note(): String? {
            val parts = mutableListOf<String>()
            if (droppedChapters > 0) parts.add("剔除 ${droppedChapters} 个目录页误切的假章")
            if (renamedChapters > 0) parts.add("$renamedChapters 个目录与内容混合章已清理改名")
            return parts.joinToString("，").ifEmpty { null }
        }
    }
}
