package com.studyfriend.app.data.study

import android.content.Context
import android.util.Log
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.SettingsSnapshot
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.PromptLoader
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.ChapterEntity
import com.studyfriend.app.data.db.ParaNoteEntity
import com.studyfriend.app.data.db.ParagraphEntity
import com.studyfriend.app.data.db.ReviewItemEntity
import com.studyfriend.app.data.db.StudyDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** M5 注入点：生产引用 AiClient.chat（纯文本流式），测试传假实现（同 ChatJsonFn 模式） */
interface ChatTextFn {
    suspend operator fun invoke(req: ChatRequest, onDelta: (String) -> Unit): String
}

object AiClientChatTextFn : ChatTextFn {
    override suspend fun invoke(req: ChatRequest, onDelta: (String) -> Unit): String =
        AiClient.chat(req, onDelta)
}

/** 一次总结包生成的结果（retried=解析失败重试一次后成功） */
class SummaryOutcome(
    val questionCount: Int,
    val retried: Boolean,
)

/**
 * 章末总结包流水线（计划 M5 §2.4）：前置检查 → note 归位锚段 → chat 流式（temp 0.4 / 6000）
 * → 五标签解析（失败纠正重试 1 次，两次失败存 raw 快照后抛）→ 单事务落库（清旧作答 + upsert
 * 资产 + 排期种子，失败/取消整体回滚，旧资产绝不覆盖）→ 排期首期 1 天，IGNORE 防重。
 * 单调用原子任务：取消即无落库（chat 阶段零写库，写库阶段在事务内回滚）。
 */
class SummaryPlanner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatTextFn: ChatTextFn,
) {

    suspend fun run(
        chapterId: Long,
        onDelta: (String) -> Unit = {},
    ): SummaryOutcome = withContext(Dispatchers.IO) {
        val chapter = db.chapterDao().byIdOnce(chapterId) ?: throw PlannerException("章节不存在")
        val key = settings.decryptKeyOrNull()
            ?: throw MissingKeyException("API Key 已失效或未保存，请先到设置页填写")
        val cfg = settings.load()
        val paragraphs = db.paragraphDao().byChapter(chapterId)
        if (paragraphs.none { it.aiAction != "NONE" }) {
            throw PlannerException("本章还没有粗读标注，请先完成粗读")
        }
        val systemPrompt = PromptLoader.load(context, PROMPT_SUMMARY)

        // 讲解卡按锚段归位：note 只挂锚段条目（M5 §2.1）
        val notes = db.paraNoteDao().byChapterOnce(chapterId)
            .filter { it.promptVersion == NotePlanner.NOTE_VERSION && it.friendly.isNotBlank() }
        val noteByAnchorIdx = HashMap<Int, ParaNoteEntity>()
        for (unit in enumerateUnits(paragraphs)) {
            val paraIds = ParaIdsCodec.encode(unit.members.map { it.id })
            notes.firstOrNull { it.paraIds == paraIds }?.let { noteByAnchorIdx[unit.anchor.idx] = it }
        }
        val terms = KeyTermsCodec.decode(chapter.keyTermsJson).map { it.term }

        var retried = false
        var lastRaw: String? = null
        var bundle: SummaryBundle? = null
        for (attempt in 1..PARSE_ATTEMPTS) {
            coroutineContext.ensureActive()
            val userJson = buildSummaryUserJson(chapter.title, chapter.gist, terms, paragraphs, noteByAnchorIdx) +
                if (attempt > 1) "\n\n上次输出不符合五标签格式，请严格按标签格式重新输出全部五段" else ""
            // 累计 partial 直通 UI（重试时归零重算），Streaming 视图据此增量渲染
            val raw = StringBuilder()
            val got = chatTextFn(summaryRequest(cfg, key, systemPrompt, userJson)) { delta ->
                raw.append(delta)
                onDelta(raw.toString())
            }
            bundle = TaggedBundleParser.parse(got)
            if (bundle != null) {
                retried = attempt > 1
                break
            }
            lastRaw = got
        }
        val result = bundle ?: run {
            Log.w(TAG, "总结包解析失败 raw 快照: ${lastRaw?.take(2000)}")
            throw PlannerException("总结包解析失败，请重试")
        }

        // 落库三连写包单事务：清旧作答、upsert 资产、排期种子要么全成要么全无——
        // 写库窗口内取消/异常整体回滚，不会出现"作答被清而资产旧"或"资产新而种子缺"
        val outcome = SummaryOutcome(result.questions.size, retried)
        db.withTransaction {
            // 重生成清空本章作答：题目身份随资产版本失效（评审 P1-3）；失败路径到不了这里，旧资产绝不覆盖
            if (db.chapterAssetDao().byChapter(chapterId) != null) {
                db.quizAttemptDao().deleteForChapter(chapterId)
            }
            db.chapterAssetDao().upsert(
                ChapterAssetEntity(
                    chapterId = chapterId,
                    summaryMd = result.summaryMd,
                    mindmapTree = result.mindmapTree,
                    mindmapJson = null, // 渲染时由 TAB 树现转，库存原始可人读文本
                    memoryMd = result.memoryMd,
                    chainMd = result.chainMd,
                    quizJson = QuizCodec.encode(result.questions),
                    model = cfg.model,
                    promptVersion = SUMMARY_VERSION,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            // 排期种子：1 天首期，M6 负责推进；先清同章全部旧排期行（唯一键含 title，章改名后 IGNORE 挡不住；
            // 含 done 行——其无读取方，重生成即开启新一轮周期），再插新种子
            db.reviewItemDao().deleteForChapter(chapterId)
            db.reviewItemDao().insertAll(
                listOf(
                    ReviewItemEntity(
                        bookId = chapter.bookId, chapterId = chapterId, title = chapter.title,
                        intervalIdx = 0, dueAt = System.currentTimeMillis() + FIRST_DUE_MS,
                    ),
                ),
            )
        }
        outcome
    }

    private fun summaryRequest(
        cfg: SettingsSnapshot,
        key: String,
        systemPrompt: String,
        userJson: String,
    ) = ChatRequest(
        baseUrl = cfg.baseUrl, apiKey = key, model = cfg.model,
        temperature = 0.4, maxTokens = 6000,
        messages = listOf(AiMessage("system", systemPrompt), AiMessage("user", userJson)),
    )

    companion object {
        const val PROMPT_SUMMARY = "summary_pack"
        const val SUMMARY_VERSION = "summary-pack-v1"
        const val PARSE_ATTEMPTS = 2
        const val FIRST_DUE_MS = 24 * 60 * 60 * 1000L
        private const val TAG = "SummaryPlanner"
    }
}
