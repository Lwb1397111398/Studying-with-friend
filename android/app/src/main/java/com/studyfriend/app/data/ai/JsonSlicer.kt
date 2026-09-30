package com.studyfriend.app.data.ai

/** 输入里截不出完整 JSON 值时抛出 */
class JsonSliceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AI 输出剥壳（纯 JVM，M4/M5 共用，计划 M3 §4）：
 * 1. 剥 ```json / ``` 围栏
 * 2. 从首个 { 或 [ 起括号配平扫描截出首个完整 JSON 值
 *    （字符串字面量内的括号/引号不参与配平，处理 \" 与 \\ 转义）
 */
object JsonSlicer {

    fun slice(raw: String): String {
        val unfenced = stripFence(raw)
        val start = unfenced.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) throw JsonSliceException("输出中找不到 JSON")
        val open = unfenced[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until unfenced.length) {
            val c = unfenced[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when {
                c == '"' -> inString = true
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return unfenced.substring(start, i + 1)
                }
            }
        }
        throw JsonSliceException("JSON 不完整（括号未闭合）")
    }

    private fun stripFence(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("```")) return raw
        val firstBreak = t.indexOf('\n')
        if (firstBreak < 0) return raw
        val withoutHead = t.substring(firstBreak + 1)
        val endFence = withoutHead.lastIndexOf("```")
        return if (endFence >= 0) withoutHead.substring(0, endFence) else withoutHead
    }
}
