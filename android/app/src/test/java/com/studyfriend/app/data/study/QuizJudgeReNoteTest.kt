package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ParaNoteEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 学习闭环轮纯函数：AI 判卷（QuizJudge.userJson/normalize）+ 换个讲法（ReNote 消息组装） */
class QuizJudgeReNoteTest {

    // ---- QuizJudge.userJson：字段齐全 + JSON 合法 + 转义 ----

    @Test
    fun judgeUserJsonIsLegalAndEscaped() {
        val json = QuizJudge.userJson(
            question = "什么是\u201c代理\u201d？",
            myAnswer = "替别人\u201c签合同\u201d",
            reference = "以被代理人名义实施法律行为",
            explain = "见《民法典》第162条",
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("什么是\u201c代理\u201d？", obj["question"]!!.jsonPrimitive.content)
        assertEquals("替别人\u201c签合同\u201d", obj["my_answer"]!!.jsonPrimitive.content)
        assertEquals("以被代理人名义实施法律行为", obj["reference_answer"]!!.jsonPrimitive.content)
        assertEquals("见《民法典》第162条", obj["explain"]!!.jsonPrimitive.content)
    }

    // ---- QuizJudge.normalize：过滤空白/截条数/截字数 ----

    @Test
    fun judgeNormalizeFiltersAndTruncates() {
        val r = QuizJudge.normalize(
            QuizJudgeResult(
                covered = listOf("要点1", "  ", "", "要点2", "要点3", "要点4", "要点5", "要点6", "要点7"),
                missing = listOf("漏1", "漏2"),
                advice = "建议".repeat(50),
            ),
        )
        // covered 截 6 条（7 条被截）
        assertEquals(6, r.covered.size)
        assertEquals("要点1", r.covered[0])
        // advice 截 80 字
        assertEquals(80, r.advice.length)
    }

    @Test
    fun judgeNormalizeKeepsShortValuesIntact() {
        val r = QuizJudge.normalize(QuizJudgeResult(listOf("要点"), listOf("漏点"), "多写具体案例"))
        assertEquals(listOf("要点"), r.covered)
        assertEquals(listOf("漏点"), r.missing)
        assertEquals("多写具体案例", r.advice)
    }

    // ---- ReNote：userJson 截断 + messages 形状 ----

    private fun note(friendly: String = "旧讲解", analogy: String? = "旧比方") = ParaNoteEntity(
        chapterId = 1L, paraIds = "[1]", title = "标题", friendly = friendly,
        analogy = analogy, keyPointsJson = "[]", memoryHook = null,
        questionsJson = null, model = "m", promptVersion = "v", createdAt = 1L,
    )

    @Test
    fun reNoteUserJsonTruncatesLongInputs() {
        val json = ReNote.userJson("字".repeat(5000), "讲".repeat(2000), "比".repeat(600))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(4000, obj["paragraph"]!!.jsonPrimitive.content.length)
        assertEquals(1500, obj["old_friendly"]!!.jsonPrimitive.content.length)
        assertEquals(500, obj["old_analogy"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun reNoteMessagesShape() {
        val msgs = ReNote.messages("系统提示", "段落原文", note(friendly = "旧讲解正文"))
        assertEquals(2, msgs.size)
        assertEquals("system", msgs[0].role)
        assertEquals("系统提示", msgs[0].content)
        val obj = Json.parseToJsonElement(msgs[1].content).jsonObject
        assertEquals("段落原文", obj["paragraph"]!!.jsonPrimitive.content)
        assertEquals("旧讲解正文", obj["old_friendly"]!!.jsonPrimitive.content)
        assertEquals("旧比方", obj["old_analogy"]!!.jsonPrimitive.content)
    }
}
