package com.studyfriend.app.data.study

import com.studyfriend.app.data.ai.AiMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 随手问（阅读页长按选字 → 底部问答卡）上下文与消息组装（纯函数，便于单测）。
 * 不走 AiGate 全局互斥：单次轻量请求，与粗读/讲解批处理管道不抢锁；弹层内
 * 一次只发一个请求（UI 层防重入）。模型走讲解模型（noteModel，空则主力）。
 */
object QuickQa {

    /** 一轮已完成的问答 */
    data class Turn(val question: String, val answer: String)

    /** 选中文字及其所在章/段上下文（截断在这里统一做，UI 层传原始文本） */
    data class Ctx(
        val chapterTitle: String,
        val gist: String?,
        /** 用户选中并发问的文字（词/句/段粒度） */
        val selection: String,
        /** 选中文字所在的段落全文 */
        val paragraph: String,
        val prev: String?,
        val next: String?,
    )

    /** 浮条「问这是什么意思」的默认问题（词/句粒度共用，selection 字段即答案指向） */
    const val ASK_MEANING = "选中的内容是什么意思？"

    /** 浮条「问整段」：selection 换成整段文本后用这个问题 */
    const val ASK_PARA = "这一段是什么意思？"

    private val json = Json

    /** user 消息 JSON：字段与 assets/prompts/quick_qa.txt 一一对应 */
    fun userMessage(ctx: Ctx, question: String): String = buildJsonObject {
        put("chapter", ctx.chapterTitle)
        put("gist", ctx.gist.orEmpty().take(300))
        put("selection", ctx.selection.take(500))
        put("paragraph", ctx.paragraph.take(800))
        put("prev", ctx.prev.orEmpty().take(400))
        put("next", ctx.next.orEmpty().take(400))
        put("question", question)
    }.toString()

    /**
     * 完整消息列表：system + 首轮带全上下文 + 历史轮（只追加问答正文，上下文不重复发）
     * + 本轮问题（追问只发问题本身）。历史取最近 10 轮，防长会话 token 膨胀。
     */
    fun messages(
        systemPrompt: String,
        ctx: Ctx,
        history: List<Turn>,
        question: String,
    ): List<AiMessage> {
        val msgs = mutableListOf(AiMessage("system", systemPrompt))
        val recent = history.takeLast(10)
        if (recent.isEmpty()) {
            msgs += AiMessage("user", userMessage(ctx, question))
            return msgs
        }
        recent.forEachIndexed { i, turn ->
            msgs += if (i == 0) {
                // 首轮带全上下文，之后的轮次才能只发问题
                AiMessage("user", userMessage(ctx, turn.question))
            } else {
                AiMessage("user", turn.question)
            }
            msgs += AiMessage("assistant", turn.answer)
        }
        msgs += AiMessage("user", question)
        return msgs
    }
}
