package com.studyfriend.app.data.study

import android.content.Context
import android.util.Log
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.SettingsSnapshot
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.PromptLoader
import com.studyfriend.app.data.db.ChapterAssetEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 一次全书总览生成的结果（retried=解析失败重试一次后成功） */
class OverviewOutcome(val retried: Boolean)

/**
 * 全书总览流水线（计划 M6 §2.6）：前置检查（书存在 → Key → 已总结章数 <2 拒绝）
 * → 单调用流式 chat（temp 0.5 / 4000）→ 三标签解析（失败纠正重试 1 次，两败存 raw 快照后抛）
 * → 落库复用 BookDao.saveOverviewJson（覆盖语义：总览无关联作答数据，直接覆盖不联动）。
 * 单调用原子任务：chat 阶段零写库、落库单条 UPDATE——取消/失败即零落库，旧总览绝不半覆盖。
 */
class OverviewPlanner(
    private val db: StudyDatabase,
    private val context: Context,
    private val settings: SettingsRepository,
    private val chatTextFn: ChatTextFn,
) {

    suspend fun run(
        bookId: Long,
        onDelta: (String) -> Unit = {},
    ): OverviewOutcome = withContext(Dispatchers.IO) {
        val book = db.bookDao().get(bookId) ?: throw PlannerException("书不存在")
        val key = settings.decryptKeyOrNull()
            ?: throw MissingKeyException("API Key 已失效或未保存，请先到设置页填写")
        val cfg = settings.load()
        val systemPrompt = PromptLoader.load(context, PROMPT_OVERVIEW)

        // 输入只收已总结章（promptVersion 匹配且 summaryMd 非空）：
        // 先用 byBookFlow 投影过滤候选（几百章大书也只对候选逐章查资产，≤20 次，计划 §2.5）
        val chapters = db.chapterDao().byBook(bookId)
        val candidateIds = db.chapterDao().byBookFlow(bookId).first()
            .filter { it.assetPromptVersion == SummaryPlanner.SUMMARY_VERSION && it.assetSummaryPresent }
            .map { it.id }
            .toSet()
        val assetByChapterId = HashMap<Long, ChapterAssetEntity>()
        for (c in chapters) {
            if (c.id in candidateIds) {
                db.chapterAssetDao().byChapter(c.id)?.let { assetByChapterId[c.id] = it }
            }
        }
        if (candidateIds.size < 2) {
            throw PlannerException("至少读完并总结 2 章，才能生成全书总览")
        }

        var retried = false
        var lastRaw: String? = null
        var bundle: OverviewBundle? = null
        for (attempt in 1..PARSE_ATTEMPTS) {
            coroutineContext.ensureActive()
            val userJson = buildOverviewUserJson(book.title, chapters, assetByChapterId) +
                if (attempt > 1) "\n\n上次输出不符合三标签格式，请严格按标签格式重新输出全部三段" else ""
            // 累计 partial 直通 UI（重试时归零重算）
            val raw = StringBuilder()
            val got = chatTextFn(overviewRequest(cfg, key, systemPrompt, userJson)) { delta ->
                raw.append(delta)
                onDelta(raw.toString())
            }
            bundle = OverviewParser.parse(got)
            if (bundle != null) {
                retried = attempt > 1
                break
            }
            lastRaw = got
        }
        val result = bundle ?: run {
            Log.w(TAG, "全书总览解析失败 raw 快照: ${lastRaw?.take(2000)}")
            throw PlannerException("全书总览解析失败，请重试")
        }

        val payload = OverviewPayload(
            overviewMd = result.overviewMd,
            treeText = result.treeText,
            mainlineMd = result.mainlineMd,
            promptVersion = OverviewCodec.VERSION,
            model = cfg.model,
            createdAt = System.currentTimeMillis(),
        )
        db.bookDao().saveOverviewJson(bookId, OverviewCodec.encode(payload), System.currentTimeMillis())
        OverviewOutcome(retried)
    }

    private fun overviewRequest(
        cfg: SettingsSnapshot,
        key: String,
        systemPrompt: String,
        userJson: String,
    ) = ChatRequest(
        baseUrl = cfg.baseUrl, apiKey = key, model = cfg.model,
        temperature = 0.5,
        // 推理型模型的思考 token 计入 max_tokens：三件套输出长，预算须给足
        maxTokens = 16_000,
        messages = listOf(AiMessage("system", systemPrompt), AiMessage("user", userJson)),
    )

    companion object {
        const val PROMPT_OVERVIEW = "book_overview"
        const val PARSE_ATTEMPTS = 2
        private const val TAG = "OverviewPlanner"
    }
}
