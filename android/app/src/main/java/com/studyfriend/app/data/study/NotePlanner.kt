package com.studyfriend.app.data.study

import android.content.Context
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.SettingsSnapshot
import com.studyfriend.app.data.ai.AiException
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.AiBudgets
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.PromptLoader
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.StudyDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
 * 即时落库断点。单元级 2 路限流并发（单元互不依赖：独立落库、独立断点，串行是纯吞吐
 * 损失）；任一单元失败即停发新任务，在途单元跑完正常落库。全部 IO 在 Dispatchers.IO。
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
        val detail = settings.detailLevel()
        // 档位与模型都参与缓存判定：切档/换讲解模型后旧卡失效重生成，设置才真正生效
        val noteVer = noteVersion(detail)
        val noteModelActual = cfg.noteModel.ifBlank { cfg.model }
        val systemPrompt = buildString {
            append(PromptLoader.load(context, PROMPT_NOTE))
            when (detail) {
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

        // 单元级 2 路限流并发：共享游标的 worker 池，谁空闲谁领下一个单元。
        // 计数与中断点全走原子变量（多 lane 并发写）；失败 CAS 取最小序号做中断点，
        // 与串行语义对齐（"中断于第 N 条"），在途单元不受影响跑完落库
        val doneCount = AtomicInteger(0)
        val generatedCount = AtomicInteger(0)
        val interrupt = AtomicReference<UnitInterrupt?>(null)
        val nextIdx = AtomicInteger(0)
        coroutineScope {
            repeat(CONCURRENCY) {
                launch {
                    while (interrupt.get() == null) {
                        val i = nextIdx.getAndIncrement()
                        if (i >= units.size) return@launch
                        coroutineContext.ensureActive()
                        val unit = units[i]
                        val paraIds = ParaIdsCodec.encode(unit.members.map { it.id })
                        val existing = notesByParaIds[paraIds]
                        if (!force && existing != null &&
                            existing.promptVersion == noteVer &&
                            existing.model == noteModelActual &&
                            existing.friendly.isNotBlank()
                        ) {
                            onProgress(doneCount.incrementAndGet(), units.size)
                            continue
                        }
                        try {
                            // 生成成功后才删旧插新：失败/中断时旧卡保留不丢（最终 QA P2-2）；
                            // 删+插包事务，两 lane 并发写库时防"删了旧的新没插进"
                            val note = runUnitWithRetry(unit, chapter, paragraphs, systemPrompt, cfg, key, noteVer, noteModelActual)
                            db.withTransaction {
                                db.paraNoteDao().deleteByParaIds(paraIds)
                                db.paraNoteDao().insert(note)
                            }
                            generatedCount.incrementAndGet()
                            onProgress(doneCount.incrementAndGet(), units.size)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val info = UnitInterrupt(
                                i + 1,
                                e.message ?: (e::class.simpleName ?: "未知错误"),
                            )
                            interrupt.updateAndGet { cur ->
                                if (cur == null || info.atUnit < cur.atUnit) info else cur
                            }
                        }
                    }
                }
            }
        }
        val stopped = interrupt.get()
        NoteOutcome(
            doneUnits = doneCount.get(),
            totalUnits = units.size,
            generated = generatedCount.get(),
            interruptedAtUnit = stopped?.atUnit,
            interruptedReason = stopped?.reason,
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
        noteVer: String,
        noteModelActual: String,
    ): ParaNoteEntity {
        var lastError: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return runUnitOnce(unit, chapter, paragraphs, systemPrompt, cfg, key, noteVer, noteModelActual)
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
            if (attempt < ATTEMPTS - 1) plannerBackoff(lastError, attempt)
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
        noteVer: String,
        noteModelActual: String,
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
            // prev/next 只给局部衔接线索：讲解单元按顺序铺满全章，相邻单元正文本身就含
            // 这段（边界段最多被发 3 遍），800 字版整章约 1/3 输入是重复 token，压到 200 字
            append(",\"context_prev\":").append(jsonEsc(prev?.text?.take(200) ?: ""))
            append(",\"context_next\":").append(jsonEsc(next?.text?.take(200) ?: ""))
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
                    baseUrl = cfg.baseUrl, apiKey = key,
                    // 讲解模型（按用途选模型）：讲解逐段调用用量最大，可单独配低成本模型；空 = 主力
                    model = noteModelActual,
                    temperature = 0.5,
                    // 推理型模型的思考 token 计入 max_tokens：讲解卡主体长，预算须给足
                    maxTokens = AiBudgets.OUTPUT_TOKENS_PLANNER,
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
            model = noteModelActual,
            promptVersion = noteVer,
            createdAt = System.currentTimeMillis(),
        )
    }

    companion object {
        const val PROMPT_NOTE = "explain_note"
        const val NOTE_VERSION = "explain-note-v2"
        const val ATTEMPTS = 3

        /** 档位拼进版本号：标准 = 原版（老卡不失效），简略/深入 = 带后缀版本，切档后旧卡自然重生成 */
        fun noteVersion(detailLevel: String): String =
            if (detailLevel == "标准") NOTE_VERSION else "$NOTE_VERSION-$detailLevel"

        /** 是否当前版本的讲解卡（含档位后缀变体）——渲染/统计/总结输入侧过滤统一走这里 */
        fun isCurrentNote(version: String): Boolean =
            version == NOTE_VERSION || version.startsWith("$NOTE_VERSION-")

        /** 单元并发数：2 路——端点 rpm/tpm 紧（见 PlannerBackoff），2 路在吞吐与限流间取平衡 */
        const val CONCURRENCY = 2

        val UNRETRYABLE = setOf(401, 403, 404)
    }

    /** 并发执行的中断记录：atUnit 取最小失败序号，reason 与之绑定同一次失败 */
    private data class UnitInterrupt(val atUnit: Int, val reason: String)
}
