package com.studyfriend.app.data.study

import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.db.ParaNoteEntity

// 换个讲法（学习闭环轮）：讲解卡「没看懂？换个讲法」——把原文与旧讲解喂给讲解模型，
// 流式输出一版更简单、多比方的重讲。临时展示不落库，不动 para_notes 的
// promptVersion 缓存语义（explain-note-v2 命中即跳过批量重生成）。

object ReNote {
    const val PROMPT_RENOTE = "renote"

    /** user 消息 JSON 组装：段落原文（多段 \n 连接）+ 旧讲解正文与比方（截断防超长） */
    fun userJson(paragraphText: String, oldFriendly: String, oldAnalogy: String?): String =
        buildString {
            append("{\"paragraph\":").append(jsonEsc(paragraphText.take(4000)))
            append(",\"old_friendly\":").append(jsonEsc(oldFriendly.take(1500)))
            append(",\"old_analogy\":").append(jsonEsc(oldAnalogy.orEmpty().take(500)))
            append('}')
        }

    fun messages(systemPrompt: String, paragraphText: String, note: ParaNoteEntity): List<AiMessage> =
        listOf(
            AiMessage("system", systemPrompt),
            AiMessage("user", userJson(paragraphText, note.friendly, note.analogy)),
        )
}
