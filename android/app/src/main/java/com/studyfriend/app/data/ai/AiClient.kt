package com.studyfriend.app.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** AI 调用失败，message 为可直接展示的中文；httpCode 非 2xx 时携带（M4 粗读用于区分可否重试） */
class AiException(message: String, val httpCode: Int? = null) : Exception(message)

data class AiMessage(val role: String, val content: String)

data class ChatRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double = 0.3,
    val maxTokens: Int? = null,
    val messages: List<AiMessage>,
)

/**
 * OpenAI 兼容 /chat/completions 流式客户端（计划 M3 §3）。
 * HttpURLConnection 零依赖；onDelta 在 IO 线程回调，Compose mutableStateOf 写入线程安全。
 */
object AiClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** 中转站用户常粘贴全路径或尾斜杠：trim → 去尾 / → 不是 completions 结尾就拼上 */
    fun normalizeChatUrl(raw: String): String =
        raw.trim().trimEnd('/').let {
            if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
        }

    /** 温度解析：兼容中文逗号小数；非法/越界回退默认 0.3 */
    fun parseTemperature(raw: String, fallback: Double = 0.3): Double =
        raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: fallback

    suspend fun chat(req: ChatRequest, onDelta: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val conn = try {
                URL(normalizeChatUrl(req.baseUrl)).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                // MalformedURLException 等：用户存了非法地址时的专属文案
                throw AiException("API 地址格式不正确，请检查设置")
            }
            val job = coroutineContext[Job]
            // readLine 阻塞在 IO 线程，协程 cancel 打不断它。挂一个哨兵子协程：
            // 取消传播到它时 finally 里 disconnect，让阻塞 read 立即抛 IOException。
            // （invokeOnCompletion 默认 onCancelling=false 等完全结束才触发，为时已晚；
            //   onCancelling=true 版本是 internal API，故用哨兵这一公开惯用法）
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    conn.disconnect()
                }
            }
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 90_000
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer ${req.apiKey}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.outputStream.use { it.write(bodyOf(req).toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                // 取消可能发生在 connect/写体阶段（那时哨兵尚未断开已连接 socket）：
                // responseCode 返回后立即检查，别等 90s 读超时
                coroutineContext.ensureActive()
                if (code !in 200..299) {
                    throw AiException(friendlyHttp(code, readErrorHead(conn)), httpCode = code)
                }

                val full = StringBuilder()
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        coroutineContext.ensureActive()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val delta = extractDelta(data)
                        if (delta.isNotEmpty()) {
                            full.append(delta)
                            onDelta(delta)
                        }
                    }
                }
                full.toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                throw e
            } catch (e: IOException) {
                // disconnect 触发的"取消型" IOException 还原为取消，不冒充网络错误
                if (job?.isCancelled == true) throw CancellationException("cancelled")
                throw AiException("网络异常：${e.message ?: "连接失败"}")
            } catch (e: Exception) {
                if (job?.isCancelled == true) throw CancellationException("cancelled")
                throw AiException("请求失败：${e.message ?: "未知错误"}")
            } finally {
                watcher.cancel()
                conn.disconnect()
            }
        }

    /**
     * 要求 AI 返回 JSON：chat → 剥壳 → 反序列化；失败自动重试 1 次
     * （附加提示"只输出 JSON"），再失败抛含原始输出片段的异常（计划 M3 §4）。
     */
    suspend fun <T> chatJson(
        req: ChatRequest,
        deserializer: DeserializationStrategy<T>,
        onDelta: (String) -> Unit = {},
    ): T {
        val first = chat(req, onDelta)
        try {
            return json.decodeFromString(deserializer, JsonSlicer.slice(first))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        // 立刻重打常撞限流窗口；稍等片刻再要 JSON（推理模型输出长，也给网关喘息）
        delay(1_500)
        val retryReq = req.copy(
            messages = req.messages +
                AiMessage("assistant", first.take(2000)) +
                AiMessage("user", RETRY_HINT),
        )
        val second = chat(retryReq, onDelta)
        try {
            return json.decodeFromString(deserializer, JsonSlicer.slice(second))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw AiException("AI 输出的 JSON 无法解析，原始输出片段：${second.take(120)}")
        }
    }

    private const val RETRY_HINT = "你上一次的输出不是合法 JSON。请只输出 JSON，不要任何其他文字、解释或代码围栏。"

    private fun bodyOf(req: ChatRequest): String = buildJsonObject {
        put("model", req.model)
        put("stream", true)
        put("temperature", req.temperature)
        req.maxTokens?.let { put("max_tokens", it) }
        putJsonArray("messages") {
            req.messages.forEach { m ->
                addJsonObject {
                    put("role", m.role)
                    put("content", m.content)
                }
            }
        }
    }.toString()

    /** 宽松取 choices[0].delta.content：任何缺字段/形状不符都给空串，不让单行坏数据毁掉整次流 */
    private fun extractDelta(data: String): String = try {
        val obj = json.parseToJsonElement(data).jsonObject
        val choice0 = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val delta = choice0?.get("delta") as? JsonObject
        when (val c = delta?.get("content")) {
            is JsonPrimitive -> if (c.isString || c.content != "null") c.content else ""
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }

    private fun friendlyHttp(code: Int, body: String): String = when (code) {
        401, 403 -> "API Key 无效或无权限"
        404 -> "接口路径不存在，检查 API 地址"
        429 -> "请求太频繁或额度用尽"
        else -> "请求失败（HTTP $code）${if (body.isNotBlank()) "：$body" else ""}"
    }

    /** 只读错误响应前 300 字节（低内存友好），errorStream 缺失返回空串 */
    private fun readErrorHead(conn: HttpURLConnection): String {
        val stream = conn.errorStream ?: return ""
        return try {
            val buf = ByteArray(300)
            var n = 0
            while (n < buf.size) {
                val r = stream.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            String(buf, 0, n, Charsets.UTF_8)
        } catch (e: IOException) {
            ""
        } finally {
            runCatching { stream.close() }
        }
    }
}
