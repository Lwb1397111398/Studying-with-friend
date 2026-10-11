package com.studyfriend.app.data

import com.studyfriend.app.data.study.QuickQa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 随手问消息组装：首轮带全上下文、追问只发问题、历史裁最近 10 轮、字段截断 */
class QuickQaMessagesTest {

    private fun ctx(selection: String = "善意取得") = QuickQa.Ctx(
        chapterTitle = "第三章 物权变动",
        gist = "本章讲物权变动的规则",
        selection = selection,
        paragraph = "无处分权人将不动产转让给受让人，符合善意取得要件的，受让人取得所有权。",
        prev = "上一段讲交付。",
        next = "下一段讲遗失物。",
    )

    @Test
    fun firstAsk_carriesFullContext() {
        val msgs = QuickQa.messages("SYS", ctx(), emptyList(), "这个词什么意思？")
        assertEquals(2, msgs.size)
        assertEquals("system", msgs[0].role)
        assertEquals("SYS", msgs[0].content)
        assertEquals("user", msgs[1].role)
        // 上下文字段齐全
        assertTrue(msgs[1].content.contains("\"chapter\""))
        assertTrue(msgs[1].content.contains("善意取得"))
        assertTrue(msgs[1].content.contains("这个词什么意思？"))
    }

    @Test
    fun followUp_onlyQuestionAfterHistory() {
        val history = listOf(QuickQa.Turn("第一问", "第一答"))
        val msgs = QuickQa.messages("SYS", ctx(), history, "再讲讲？")
        assertEquals(4, msgs.size) // system + 首轮(带上下文) + assistant + 追问
        assertEquals("第一问", extractQuestion(msgs[1].content))
        assertEquals("assistant", msgs[2].role)
        assertEquals("第一答", msgs[2].content)
        assertEquals("再讲讲？", msgs[3].content)
        // 追问消息不再重复上下文 JSON
        assertFalse(msgs[3].content.contains("\"chapter\""))
    }

    @Test
    fun historyTrimmedToLast10() {
        val history = (1..15).map { QuickQa.Turn("q$it", "a$it") }
        val msgs = QuickQa.messages("SYS", ctx(), history, "追问")
        // system + (首轮上下文+answer)*10 + 追问 = 22
        assertEquals(22, msgs.size)
        assertTrue(msgs[1].content.contains("q6"))   // 第 6 轮是保留的最旧一轮
        assertFalse(msgs[1].content.contains("q5"))  // 第 5 轮被裁掉
        assertEquals("追问", msgs.last().content)
    }

    @Test
    fun longFieldsTruncated() {
        val big = QuickQa.Ctx(
            chapterTitle = "章",
            gist = "g".repeat(1000),
            selection = "s".repeat(1000),
            paragraph = "p".repeat(2000),
            prev = "a".repeat(1000),
            next = "b".repeat(1000),
        )
        val msg = QuickQa.userMessage(big, "问")
        assertTrue(msg.length < 4000) // 300+500+800+400+400 上限 + 字段骨架
        assertTrue(msg.contains("\"question\":\"问\""))
    }

    /** 从首轮带上下文的 user JSON 里取出 question 字段值 */
    private fun extractQuestion(content: String): String {
        val key = "\"question\":\""
        val start = content.indexOf(key) + key.length
        return content.substring(start, content.indexOf('"', start))
    }
}
