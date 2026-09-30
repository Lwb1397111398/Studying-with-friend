package com.studyfriend.app.data.study

import android.content.Context
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.SettingsSnapshot
import com.studyfriend.app.data.ai.AiException
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.PromptLoader
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

/** 讲解批量结果（与 RoughReadOutcome 同构；无归并维度，generated=本次新生成条数） */
class NoteOutcome(
    val doneUnits: Int,
    val totalUnits: Int,
    val generated: Int,
    val interruptedAtUnit: Int? = null,
    val interruptedReason: String? = null,
)

/**
 * 段落讲解批量流水线（计划 M4b §2.4；v2 输出改标签文本）：枚举 → 孤儿清理 → 缓存判定 →
 * 逐单元 chat 文本流（单元级重试、401/403/404 直断）→ 标签解析 → 先删后插（同 paraIds 唯一）→
 * 即时落库断点。串行执行，全部 IO 在 Dispatchers.IO。
 */
class NotePlanner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatTextFn: ChatTextFn,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(
        chapterId: Long,
        force: Boolean = false,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): NoteOutcome = withContext(Dispatchers.IO) {
        val chapter = db.chapterDao().byIdOnce(chapterId)
            ?: throw PlannerException("章节不存在")
        val paragraphs = db.paragraphDao().byChapter(chapterId)
        val key = settings.decryptKeyOrNull()
            ?: throw MissingKeyException("API Key 已失效或未保存，请先到设置页填写")
        val cfg = settings.load()
        // 讲解详略三档（总计划 §1 目标 7）：标准档即 prompt 原文，简略/深入在 system 末尾追加指令
        val systemPrompt = buildString {
            append(PromptLoader.load(context, PROMPT_NOTE))
            when (settings.detailLevel()) {
                "简略" -> append(
                    "\n\n本次讲解详略：简略。friendly 只写 150~280 字，围绕最核心的一个点讲透，类比从简；check_questions 最多 1 条。",
                )
                "深入" -> append(
                    "\n\n本次讲解详略：深入。friendly 放宽到 600~900 字，多举生活化例子，主动联系读者可能读过的相关知识；key_points 可到 6 条。",
                )
            }
        }
        val units = enumerateUnits(paragraphs)

        // 孤儿清理在空单元早退之前：force 粗读全变 SKIP 时旧 note 行也会残留
        val unitParaIds = units.map { ParaIdsCodec.encode(it.members.map { m -> m.id }) }.toSet()
        for (note in db.paraNoteDao().byChapterOnce(chapterId)) {
            if (note.paraIds !in unitParaIds) db.paraNoteDao().deleteById(note.id)
        }
        if (units.isEmpty()) return@withContext NoteOutcome(0, 0, 0)

        // 开跑前快照：缓存命中判定与生成中插库互不影响（本流水线是唯一写者）
        val notesByParaIds = db.paraNoteDao().byChapterOnce(chapterId).associateBy { it.paraIds }
        var done = 0
        var generated = 0
        var interruptedAt: Int? = null
        var interruptedReason: String? = null

        for ((ui, unit) in units.withIndex()) {
            coroutineContext.ensureActive()
            val paraIds = ParaIdsCodec.encode(unit.members.map { it.id })
            val existing = notesByParaIds[paraIds]
            if (!force && existing != null &&
                existing.promptVersion == NOTE_VERSION && existing.friendly.isNotBlank()
            ) {
                done++
                onProgress(done, units.size)
                continue
            }
            try {
                // 生成成功后才删旧插新：失败/中断时旧卡保留不丢（最终 QA P2-2）
                val note = runUnitWithRetry(unit, chapter, paragraphs, systemPrompt, cfg, key)
                db.paraNoteDao().deleteByParaIds(paraIds)
                db.paraNoteDao().insert(note)
                generated++
                done++
                onProgress(done, units.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                interruptedAt = ui + 1 // 中断于第 N 条；已完成单元已落库
                interruptedReason = e.message ?: (e::class.simpleName ?: "未知错误")
                break
            }
        }

        NoteOutcome(
            doneUnits = done,
            totalUnits = units.size,
            generated = generated,
            interruptedAtUnit = interruptedAt,
            interruptedReason = interruptedReason,
        )
    }

    // ---------- 单元执行 ----------

    private suspend fun runUnitWithRetry(
        unit: ExplainUnit,
        chapter: ChapterEntity,
        paragraphs: List<ParagraphEntity>,
        systemPrompt: String,
        cfg: SettingsSnapshot,
        key: String,
    ): ParaNoteEntity {
        var lastError: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return runUnitOnce(unit, chapter, paragraphs, systemPrompt, cfg, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                if (e.httpCode in UNRETRYABLE) throw e
                lastError = e
            } catch (e: PlannerException) {
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < ATTEMPTS - 1) plannerBackoff(lastError)
        }
        throw lastError ?: PlannerException("本单元讲解失败")
    }

    private suspend fun runUnitOnce(
        unit: ExplainUnit,
        chapter: ChapterEntity,
        paragraphs: List<ParagraphEntity>,
        systemPrompt: String,
        cfg: SettingsSnapshot,
        key: String,
    ): ParaNoteEntity {
        val anchor = unit.anchor
        val texts = unit.textsForPrompt()
        val prev = paragraphs.firstOrNull { it.idx == anchor.idx - 1 }
        val next = paragraphs.firstOrNull { it.idx == anchor.idx + 1 }
        val why = unit.members.mapNotNull { it.why?.trim() }.filter { it.isNotEmpty() }.joinToString("；")
        val userJson = buildString {
            append("{\"gist\":").append(jsonEsc(chapter.gist.orEmpty()))
            append(",\"terms\":").append(jsonEsc(KeyTermsCodec.decode(chapter.keyTermsJson).joinToString("、") { it.term }))
            append(",\"why\":").append(jsonEsc(why))
            append(",\"context_prev\":").append(jsonEsc(prev?.text?.take(800) ?: ""))
            append(",\"context_next\":").append(jsonEsc(next?.text?.take(800) ?: ""))
            append(",\"paragraphs\":[")
            texts.forEachIndexed { i, t ->
                if (i > 0) append(',')
                append("{\"id\":").append(i).append(",\"text\":").append(jsonEsc(t)).append('}')
            }
            append("]}")
        }
        val plan = NoteTaggedParser.parse(
            chatTextFn(
                ChatRequest(
                    baseUrl = cfg.baseUrl, apiKey = key, model = cfg.model,
                    temperature = 0.5,
                    // 推理型模型的思考 token 计入 max_tokens：讲解卡主体长，预算须给足
                    maxTokens = 16_000,
                    messages = listOf(
                        AiMessage("system", systemPrompt),
                        AiMessage("user", userJson),
                    ),
                ),
            ) { },
        ) ?: throw PlannerException("讲解输出缺少 <讲解> 标签")
        val n = NoteParser.normalize(plan, anchor.text)
        return ParaNoteEntity(
            chapterId = chapter.id,
            paraIds = ParaIdsCodec.encode(unit.members.map { it.id }),
            title = n.title.take(16),
            friendly = n.friendly,
            analogy = n.analogy.ifBlank { null },
            keyPointsJson = json.encodeToString(ListSerializer(String.serializer()), n.keyPoints),
            memoryHook = n.memoryHook.ifBlank { null },
            questionsJson = json.encodeToString(ListSerializer(CheckQuestion.serializer()), n.checkQuestions),
            model = cfg.model,
            promptVersion = NOTE_VERSION,
            createdAt = System.currentTimeMillis(),
        )
    }

    companion object {
        const val PROMPT_NOTE = "explain_note"
        const val NOTE_VERSION = "explain-note-v2"
        const val ATTEMPTS = 3
        val UNRETRYABLE = setOf(401, 403, 404)
    }
}
