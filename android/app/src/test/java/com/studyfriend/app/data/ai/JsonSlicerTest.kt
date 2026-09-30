package com.studyfriend.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonSlicerTest {

    @Test
    fun plainJson_passesThrough() {
        assertEquals("""{"a":1}""", JsonSlicer.slice("""{"a":1}"""))
    }

    @Test
    fun fencedJson_stripped() {
        val raw = "```json\n{\"a\": 1}\n```"
        assertEquals("{\"a\": 1}", JsonSlicer.slice(raw))
    }

    @Test
    fun fenceWithoutJsonTag_stripped() {
        val raw = "```\n{\"a\":1}\n```"
        assertEquals("{\"a\":1}", JsonSlicer.slice(raw))
    }

    @Test
    fun noiseAroundJson_extracted() {
        val raw = "好的，以下是结果：\n{\"name\": \"搭子\", \"n\": 2}\n希望对你有帮助！"
        assertEquals("""{"name": "搭子", "n": 2}""", JsonSlicer.slice(raw))
    }

    @Test
    fun nestedBraces_fullMatch() {
        val raw = """{"a":{"b":[1,{"c":2}]},"d":3}"""
        assertEquals(raw, JsonSlicer.slice(raw))
    }

    @Test
    fun bracesInsideString_notCounted() {
        val raw = """{"s":"a}b{c]d[e\"f{g"}"""
        assertEquals(raw, JsonSlicer.slice(raw))
    }

    @Test
    fun escapedBackslashAndQuote_handled() {
        // "p" 的值是 a\"b{c（含转义引号与转义反斜杠），{ 不参与配平
        val raw = """{"p":"a\\\"b{c","q":1}"""
        assertEquals(raw, JsonSlicer.slice(raw))
    }

    @Test
    fun array_extracted() {
        assertEquals("[1,2,3]", JsonSlicer.slice("结果如下 [1,2,3] 完毕"))
    }

    @Test(expected = JsonSliceException::class)
    fun noJson_throws() {
        JsonSlicer.slice("抱歉，我不明白你的意思。")
    }

    @Test(expected = JsonSliceException::class)
    fun unclosedJson_throws() {
        JsonSlicer.slice("{\"a\": 1, \"b\": [1,2")
    }
}
