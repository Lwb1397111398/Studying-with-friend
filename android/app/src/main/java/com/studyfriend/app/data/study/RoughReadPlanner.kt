package com.studyfriend.app.data.study

import android.content.Context
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.SettingsSnapshot
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.AiException
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.PromptLoader
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

/** M4a 注入点：生产引用 AiClient.chatJson，测试传假实现（计划 M4a §4 可测性决策）。
 *  泛型方法不能用 fun interface（Kotlin 禁止），故为普通接口，用 invoke 运算符约定保持调用简洁。 */
interface ChatJsonFn {
    suspend operator fun <T> invoke(
        req: ChatRequest,
        deserializer: DeserializationStrategy<T>,
        onDelta: (String) -> Unit,
    ): T
}

/** 生产适配器：Runner 默认走真实 AiClient；测试注入假实现 */
object AiClientChatJsonFn : ChatJsonFn {
    override suspend fun <T> invoke(
        req: ChatRequest,
        deserializer: DeserializationStrategy<T>,
        onDelta: (String) -> Unit,
    ): T = AiClient.chatJson(req, deserializer, onDelta)
}

/** user JSON 字符串字面量转义（粗读/讲解/总结共用）：**含首尾引号**——调用点直接 append 值；
 *  注意 M4b 质检 P2-3 合并时曾误用无引号版本，产生 `"text":正文` 非法 JSON，M5 输入测试抓出后统一 */
internal fun jsonEsc(s: String): String = buildString {
    append('"')
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

/** 一次粗读的结果（中断是状态不是异常；取消走 CancellationException 向上抛） */
data class RoughReadOutcome(
    val interruptedAtBlock: Int?, // null = 全部块完成；否则 = 失败块的 1-based 序号
    val interruptedReason: String?, // 中断原因（可直接展示的中文）
    val doneBlocks: Int,
    val totalBlocks: Int,
    val mergeDegraded: Boolean, // 归并失败，写了降级值
    val mergeKeptOld: Boolean,  // 归并失败且非 force 且旧 gist 非空，保留旧值
    val unitCount: Int,         // 全章讲解单元数（explain 段 + group 组），>10 UI 提示
)

/** 章级 key_terms 的 JSON 编解码（keyTermsJson 列，形如 [{"term","plain"}]） */
object KeyTermsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class TermPlain(val term: String, val plain: String)

    private val serializer = ListSerializer(TermPlain.serializer())

    fun encode(terms: List<TermPlain>): String = json.encodeToString(serializer, terms)

    fun decode(raw: String?): List<TermPlain> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}

/**
 * 粗读流水线（计划 M4a §2）：分块 → 逐块 chatJson（块级重试、401/403/404 直断）→
 * PlanParser 容错 → 块完成即落库（断点）→ 归并（失败降级，但非 force 且旧值存在绝不覆盖）。
 * 串行执行，全部 IO 在 Dispatchers.IO。
 */
class RoughReadPlanner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatJsonFn: ChatJsonFn,
) {

    suspend fun run(
        chapterId: Long,
        force: Boolean = false,
        mergeOnly: Boolean = false,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RoughReadOutcome = withContext(Dispatchers.IO) {
        val chapter = db.chapterDao().byIdOnce(chapterId)
            ?: throw PlannerException("章节不存在")
        val paragraphs = db.paragraphDao().byChapter(chapterId)
        if (paragraphs.isEmpty()) throw PlannerException("本章没有段落")

        val key = settings.decryptKeyOrNull()
            ?: throw MissingKeyException("API Key 已失效或未保存，请先到设置页填写")
        val cfg = settings.load()
        val systemPrompt = PromptLoader.load(context, PROMPT_ROUGH)
        val blocks = RoughReadChunks.split(paragraphs)
        val chapterChars = paragraphs.sumOf { it.text.length }.coerceAtLeast(1)

        // 空转保护（计划 §2.5）：全块已标且已有 gist 且非 force → 直接返回，零 API 调用
        if (!force && !mergeOnly && chapter.gist != null &&
            paragraphs.all { it.aiAction != AiAction.NONE.name }
        ) {
            return@withContext RoughReadOutcome(
                interruptedAtBlock = null,
                interruptedReason = null,
                doneBlocks = blocks.size,
                totalBlocks = blocks.size,
                mergeDegraded = false,
                mergeKeptOld = false,
                unitCount = countUnits(paragraphs),
            )
        }

        val blockGists = arrayOfNulls<String>(blocks.size)
        val blockTerms = Array(blocks.size) { emptyList<KeyTermsCodec.TermPlain>() }
        var done = 0
        var interruptedAt: Int? = null
        var interruptedReason: String? = null

        if (!mergeOnly) {
            if (chapter.readState != "DONE") db.chapterDao().updateReadState(chapterId, "READING")

            for ((bi, block) in blocks.withIndex()) {
                coroutineContext.ensureActive()
                val allMarked = block.paragraphs.all { it.aiAction != AiAction.NONE.name }
                if (!force && allMarked) {
                    done++
                    onProgress(done, blocks.size)
                    continue
                }
                try {
                    val outcome = runBlockWithRetry(block, bi, systemPrompt, blocks.size, chapterChars, cfg, key)
                    coroutineContext.ensureActive() // 重试间隙取消：立即退出，已完成块保留
                    applyDecisions(block, outcome.decisions)
                    blockGists[bi] = outcome.plan.gist.take(200)
                    blockTerms[bi] = outcome.plan.keyTerms.take(10)
                        .map { KeyTermsCodec.TermPlain(it.term.take(30), it.plain.take(60)) }
                    done++
                    onProgress(done, blocks.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    interruptedAt = bi + 1 // 中断于第 N 块；已完成块已落库
                    interruptedReason = e.message ?: (e::class.simpleName ?: "未知错误")
                    break
                }
            }
        } else {
            done = blocks.size
            onProgress(done, blocks.size)
        }

        var degraded = false
        var keptOld = false
        if (interruptedAt == null) {
            if (blocks.size == 1 && blockGists[0] != null) {
                // 单块章且块已跑（计划 §2.4）：直接用该块 gist/key_terms，不发归并调用
                saveOverview(chapter, blockGists[0]!!, blockTerms[0])
            } else {
                // 多块归并；或单块 mergeOnly（blockGists 空，走库标注重建要点后归并）
                val merge = mergeOverview(chapter, blocks, blockGists, blockTerms, cfg, key, keepOldIfFail = !force)
                degraded = merge.degraded
                keptOld = merge.keptOld
            }
        }

        RoughReadOutcome(
            interruptedAtBlock = interruptedAt,
            interruptedReason = interruptedReason,
            doneBlocks = done,
            totalBlocks = blocks.size,
            mergeDegraded = degraded,
            mergeKeptOld = keptOld,
            unitCount = countUnits(db.paragraphDao().byChapter(chapterId)),
        )
    }

    /** 全部块已完成但 gist 为空（上次归并前中断）或完成态重试：只跑归并，不受空转保护限制 */
    suspend fun runMergeOnly(chapterId: Long): RoughReadOutcome = run(chapterId, force = false, mergeOnly = true)

    // ---------- 块执行 ----------

    private class BlockOutcome(val plan: RoughReadPlan, val decisions: Map<Int, Decision>)

    private suspend fun runBlockWithRetry(
        block: RoughReadBlock,
        blockIndex: Int,
        systemPrompt: String,
        blockTotal: Int,
        chapterChars: Int,
        cfg: SettingsSnapshot,
        key: String,
    ): BlockOutcome {
        var lastError: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return runBlockOnce(block, blockIndex, systemPrompt, blockTotal, chapterChars, cfg, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                if (e.httpCode in UNRETRYABLE) throw e // 401/403/404：重试白打请求，直接中断
                lastError = e
            } catch (e: PlannerException) {
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < ATTEMPTS - 1) plannerBackoff(lastError) // 末次失败不再等
        }
        throw lastError ?: PlannerException("本块执行失败")
    }

    private suspend fun runBlockOnce(
        block: RoughReadBlock,
        blockIndex: Int,
        systemPrompt: String,
        blockTotal: Int,
        chapterChars: Int,
        cfg: SettingsSnapshot,
        key: String,
    ): BlockOutcome {
        val budget = PlanParser.unitBudget(block.chars, chapterChars, blockTotal)
        val filled = systemPrompt
            .replace("{block_pos}", (blockIndex + 1).toString())
            .replace("{block_total}", blockTotal.toString())
            .replace("{unit_budget}", budget.toString())
        val req = ChatRequest(
            baseUrl = cfg.baseUrl,
            apiKey = key,
            model = cfg.model,
            temperature = 0.2,
            // 推理型模型的思考 token 计入 max_tokens：预算不足会被思考吃光、正文截断
            maxTokens = (block.paragraphs.size * 130 + 600).coerceIn(16_384, 32_768),
            messages = listOf(
                AiMessage("system", filled),
                AiMessage("user", paragraphsJson(block)),
            ),
        )
        val plan = chatJsonFn(req, RoughReadPlan.serializer()) { }
        val decisions = PlanParser.parse(plan, block.idxs)
        return BlockOutcome(plan, decisions)
    }

    private fun paragraphsJson(block: RoughReadBlock): String = buildString {
        append("{\"paragraphs\":[")
        block.paragraphs.forEachIndexed { i, p ->
            if (i > 0) append(",")
            append("{\"id\":").append(p.idx)
            append(",\"text\":").append(jsonEsc(p.text.take(200)))
            append("}")
        }
        append("]}")
    }

    /** 块结果落库：标注段写 aiAction/groupId/why（groupId=组首段 idx）；缺标段保持 NONE */
    private suspend fun applyDecisions(block: RoughReadBlock, decisions: Map<Int, Decision>) {
        for (p in block.paragraphs) {
            val d = decisions[p.idx] ?: continue
            val groupId = if (d.action == AiAction.GROUP) d.groupStart?.toLong() else null
            db.paragraphDao().updateAiAction(p.id, d.action.name, groupId, d.why)
        }
    }

    // ---------- 归并 ----------

    private class MergeResult(val degraded: Boolean, val keptOld: Boolean)

    private suspend fun mergeOverview(
        chapter: ChapterEntity,
        blocks: List<RoughReadBlock>,
        blockGists: Array<String?>,
        blockTerms: Array<List<KeyTermsCodec.TermPlain>>,
        cfg: SettingsSnapshot,
        key: String,
        keepOldIfFail: Boolean,
    ): MergeResult {
        val contributions = blocks.mapIndexed { bi, block ->
            val gist = blockGists[bi]
            if (gist != null) {
                "{\"gist\":${jsonEsc(gist)},\"terms\":${jsonEsc(blockTerms[bi].joinToString("；") { it.term })}}"
            } else {
                // 断点跳过块/mergeOnly：从库标注重建要点（非 skip 段的 why + 文本节选）
                val marked = block.paragraphs.filter { it.aiAction != "NONE" && it.aiAction != "SKIP" }
                val brief = marked.joinToString("；") { p ->
                    val why = p.why?.let { "（$it）" } ?: ""
                    "${p.text.take(60)}$why"
                }.ifBlank { block.paragraphs.firstOrNull()?.text?.take(60) ?: "" }
                "{\"gist\":${jsonEsc(brief)},\"terms\":\"\"}"
            }
        }

        var lastError: Exception? = null
        for (attempt in 0 until ATTEMPTS) {
            try {
                val merged = chatJsonFn(
                    ChatRequest(
                        baseUrl = cfg.baseUrl, apiKey = key, model = cfg.model,
                        temperature = 0.2,
                        // 原值 800 会被推理模型的思考 token 吃光导致正文截断
                        maxTokens = 16_384,
                        messages = listOf(
                            AiMessage("user", PromptLoader.load(context, PROMPT_MERGE) + contributions.joinToString("\n")),
                        ),
                    ),
                    MergedOverview.serializer(),
                ) { }
                saveOverview(chapter, merged.gist.take(200), merged.keyTerms.take(10).map {
                    KeyTermsCodec.TermPlain(it.term.take(30), it.plain.take(60))
                })
                return MergeResult(degraded = false, keptOld = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                lastError = e
                if (e.httpCode in UNRETRYABLE) break // 401/403/404 重试必败，直接进降级分支
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < ATTEMPTS - 1) plannerBackoff(lastError)
        }

        // 归并失败：非 force 且旧 gist 非空 → 保留旧值；否则（force 重标注 / 首次）写降级新值
        val firstGist = blockGists.filterNotNull().firstOrNull()
        val degradedTerms = blockTerms.flatMap { it }.distinctBy { it.term }.take(10)
        return if (keepOldIfFail && chapter.gist != null) {
            MergeResult(degraded = false, keptOld = true)
        } else if (firstGist != null) {
            saveOverview(chapter, firstGist, degradedTerms)
            MergeResult(degraded = true, keptOld = false)
        } else {
            MergeResult(degraded = false, keptOld = true) // 无可写内容，维持空 gist
        }
    }

    private suspend fun saveOverview(chapter: ChapterEntity, gist: String, terms: List<KeyTermsCodec.TermPlain>) {
        db.chapterDao().updateAiGist(chapter.id, gist, KeyTermsCodec.encode(terms))
    }

    private fun countUnits(paragraphs: List<ParagraphEntity>): Int {
        // 以库里的最新标注为准（blocks 里的段落实体是运行前的引用，不含本次落库结果）
        val groupStarts = HashSet<Long>()
        var explains = 0
        for (p in paragraphs) {
            when (p.aiAction) {
                "EXPLAIN" -> explains++
                "GROUP" -> p.groupId?.let { groupStarts.add(it) }
            }
        }
        return explains + groupStarts.size
    }

    companion object {
        const val PROMPT_ROUGH = "rough_read"
        const val PROMPT_MERGE = "rough_read_merge"
        // ATTEMPTS=3 × chatJson 内部 JSON 解析失败重试 1 次 = 最坏 6 请求/块（额度放大上限，计划 §7）
        const val ATTEMPTS = 3 // 首次 + 2 次重试
        val UNRETRYABLE = setOf(401, 403, 404)
    }
}
