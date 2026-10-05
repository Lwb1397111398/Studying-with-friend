package com.studyfriend.app.data.importer.pdfpipeline

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.JsonSlicer

/** 转写结果 JSON 编解码（视觉转写与 VM 的逐页落盘缓存共用） */
object TranscriptionJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(t: PageTranscription): String = buildJsonObject {
        putJsonArray("body") { t.body.forEach { add(it) } }
        putJsonArray("footnotes") { t.footnotes.forEach { add(it) } }
    }.toString()

    fun parse(raw: String): PageTranscription? = try {
        val obj = json.parseToJsonElement(JsonSlicer.slice(raw)).jsonObject
        PageTranscription(body = obj.stringList("body"), footnotes = obj.stringList("footnotes"))
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
}

/**
 * 视觉转写（OPT-E）：龙猫视觉模型逐页转写整页书页，产出的段落整页替换文字层。
 * 复用 AiClient（推理模型的 reasoning_content 独立成字段，extractDelta 只取
 * delta.content 天然忽略思考过程）。温度 0.1、读超时 180s（实测 60~70s/页）。
 *
 * 失败语义（经验帖：失败回退不阻断）：解析失败/长度守卫不过 → 重试 1 次；
 * 最终失败返回 null，调用方保留该页文字层内容继续导入。
 */
class VisionTranscriber(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {

    /**
     * 转写单页。长度守卫（经验帖校准）：转写字符 < 清洗后原页×0.5 且原页 > 200 字
     * → 判定漏转/截断，按失败处理。守卫口径用清洗后段落字符合计（originChars 由
     * 调用方传入——导入路径取 page.paras 字数，后台队列路径取 vision_queue.originChars），
     * 不用 rawChars（rawChars 含将被删掉的页眉页码，会让守卫失真）。
     */
    suspend fun transcribePage(originChars: Int, pngBase64: String): PageTranscription? {
        repeat(TRANSCRIBE_ATTEMPTS) { attempt ->
            val t = try {
                parseTranscription(callModel(pngBase64))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (t != null && passesLengthGuard(originChars, t.totalChars)) return t
            if (attempt == TRANSCRIBE_ATTEMPTS - 1) return null
        }
        return null
    }

    private suspend fun callModel(pngBase64: String): String = AiClient.chat(
        ChatRequest(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            temperature = 0.1,
            maxTokens = 8000,
            messages = listOf(
                AiMessage(
                    role = "user",
                    content = PROMPT,
                    images = listOf("data:image/png;base64,$pngBase64"),
                ),
            ),
            readTimeoutMs = 180_000,
        ),
    )

    /**
     * AI 目视判断竖排（P6b S4 竖排书拒绝链最后一环）：全本投影扫描疑似后抽 3 页问
     * 视觉模型；答「是/否」，解析失败返回 null（调用方按「确认不了不拒绝」放行）。
     */
    suspend fun isVerticalPage(pngBase64: String): Boolean? {
        val raw = try {
            AiClient.chat(
                ChatRequest(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    temperature = 0.1,
                    maxTokens = 2000,
                    messages = listOf(
                        AiMessage(
                            role = "user",
                            content = VERTICAL_PROMPT,
                            images = listOf("data:image/png;base64,$pngBase64"),
                        ),
                    ),
                    readTimeoutMs = 120_000,
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return when {
            raw.contains("是竖排") || raw.contains("VERTICAL") -> true
            raw.contains("不是竖排") || raw.contains("横排") || raw.contains("HORIZONTAL") -> false
            else -> null
        }
    }

    private fun parseTranscription(raw: String): PageTranscription? = TranscriptionJson.parse(raw)

    private fun passesLengthGuard(originChars: Int, transcribedChars: Int): Boolean =
        originChars <= LENGTH_GUARD_ORIGIN_MIN || transcribedChars >= originChars * LENGTH_GUARD_RATIO

    private companion object {
        const val TRANSCRIBE_ATTEMPTS = 2
        const val LENGTH_GUARD_ORIGIN_MIN = 200
        const val LENGTH_GUARD_RATIO = 0.5

        /** 竖排目视判定的钉死格式提示词：强约束短语，解析按短语匹配（P6b S4） */
        private val VERTICAL_PROMPT =
            "这张书页图片里的文字排版是竖排（从上到下、从右到左阅读）还是横排？" +
                "只回答以下四个短语之一：是竖排 / 不是竖排。不要任何解释。"

        /** 实测有效的钉死格式提示词：只输出 JSON、忠实原文、忽略页眉页脚页码 */
        private val PROMPT =
            "把这张书页图片中的全部文字内容忠实转写为 JSON：" +
                "{\"body\":[\"每个逻辑段一个字符串\"],\"footnotes\":[\"页脚小字注释，没有则空数组\"]}。" +
                "要求：只输出 JSON，不要任何解释或代码围栏；忠实原文，不增删、不改写、不翻译，" +
                "无法辨认的字用□；忽略页眉、页脚、页码。"
    }
}
