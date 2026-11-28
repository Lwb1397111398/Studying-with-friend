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
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** AI 调用失败，message 为可直接展示的中文；httpCode 非 2xx 时携带（M4 粗读用于区分可否重试） */
class AiException(message: String, val httpCode: Int? = null) : Exception(message)

/** images：data URI 形式（data:image/png;base64,…）的图片，供视觉模型；空 = 纯文本消息 */
data class AiMessage(val role: String, val content: String, val images: List<String> = emptyList())

data class ChatRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double = 0.3,
    val maxTokens: Int? = null,
    val messages: List<AiMessage>,
    /** 读超时（ms）：视觉转写单页要 60 秒级，这类长任务调大；默认值见 [AiBudgets.READ_TIMEOUT_DEFAULT_MS] */
    val readTimeoutMs: Int = AiBudgets.READ_TIMEOUT_DEFAULT_MS,
    /** P0-② 降档梯子：true 时请求体带 response_format={"type":"json_object"}；仅 chatJson 链路消费，网关拒收自动降纯 prompt 档 */
    val jsonMode: Boolean = false,
    /** 来源标签（AI 运行日志显示用，不进请求体）：粗读/段落讲解/随手问…；空则日志回落显示模型名 */
    val source: String = "",
    /** 思考过程回调（2026-11-28 老板诉求「思考亮出来、思考完折叠」）：推理模型 &lt;think&gt; 段
     *  增量，仅盯屏弹层显示用；null=照旧静默滤除。不进返回值/落库/AI 运行日志（日志保持正文口径） */
    val onThink: ((String) -> Unit)? = null,
    /** 思考强度（2026-11-28）：null/空白 = 不传（服务商默认）；low/medium/high 请求体带
     *  reasoning_effort（OpenAI 兼容口径，中转站透传最广）。不支持的端点一般忽略未知参数；
     *  报错时把档案的思考强度调回「默认」即可。 */
    val reasoningEffort: String? = null,
)

/**
 * OpenAI 兼容 /chat/completions 流式客户端（计划 M3 §3）。
 * OkHttp 单例共享连接池；onDelta 在 IO 线程回调，Compose mutableStateOf 写入线程安全。
 * 回调与返回值均已滤掉推理模型内联的 `<think>` 思考段（ThinkSuppressor，2026-11-21）。
 */
object AiClient {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 共享 HTTP 客户端（2026-11-28 首字提速）：旧实现 HttpURLConnection 每次调用后
     * disconnect()，连接从不复用——「一键分析」逐章连环调用时每章都重付 DNS+TCP+TLS
     * 握手（首字平白多零点几秒到一秒）。OkHttp 单例默认连接池 5 空闲连接/5 分钟
     * keep-alive，同端点连续调用复用已握手的连接，首字不再多付握手成本。
     * readTimeout 各请求不同（chat 默认 90s / 视觉 180s / 模型列表 30s）→ 用
     * [OkHttpClient.newBuilder] per-call 覆盖：newBuilder 共享同一连接池，不会分池。
     */
    private val http = OkHttpClient.Builder()
        .connectTimeout(AiBudgets.CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()

    /**
     * 全局 RPM 限流（第九波实景轮）：所有 chat 请求（含 chatJson 各档与视觉转写，全部
     * 收口到本函数）先领名额——滑动窗口每分钟上限见 AiRateLimiter 默认值，满了自动排队，
     * 防批量链路一分钟内打爆免费额度（撞 429 后退避重试反而更费）。
     */
    private val rpmLimiter = AiRateLimiter()

    /** 中转站用户常粘贴全路径或尾斜杠：trim → 去尾 / → 不是 completions 结尾就拼上 */
    fun normalizeChatUrl(raw: String): String =
        raw.trim().trimEnd('/').let {
            if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
        }

    /** 模型列表地址：trim → 去尾 / → 不是 /models 结尾就拼上（粘贴了全路径也兼容） */
    fun normalizeModelsUrl(raw: String): String =
        raw.trim().trimEnd('/').let {
            if (it.endsWith("/models")) it else "$it/models"
        }

    /**
     * 拉取服务商模型列表（OpenAI 兼容 GET /models，Bearer 鉴权），供「查找模型」选择。
     * 宽松解析：data[].id 为主，兜底 model/name 字段；根级裸数组也认；解析不动给空列表。
     * 失败抛 AiException（人话文案），调用方提示后仍可手填模型名。
     */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val url = normalizeModelsUrl(baseUrl).toHttpUrlOrNull()
                ?: throw AiException("API 地址格式不正确，请检查")
            val call = http.newBuilder()
                .readTimeout(AiBudgets.READ_TIMEOUT_MODEL_LIST_MS.toLong(), TimeUnit.MILLISECONDS)
                .build()
                .newCall(
                    Request.Builder().url(url)
                        .header("Authorization", "Bearer $apiKey")
                        .header("Accept", "application/json")
                        .get()
                        .build(),
                )
            try {
                call.execute().use { resp ->
                    val code = resp.code
                    if (code !in 200..299) {
                        throw AiException(friendlyHttp(code, errorHead(resp)), httpCode = code)
                    }
                    val text = resp.body?.string() ?: ""
                    parseModelIds(text).distinct().sorted()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                throw e
            } catch (e: IOException) {
                throw AiException(friendlyIo(e))
            } catch (e: Exception) {
                throw AiException("拉取模型列表失败：${e.message ?: "未知错误"}")
            }
        }

    /** 模型 id 提取：任何形状不符都给空列表，不让单条脏数据毁掉整次拉取 */
    private fun parseModelIds(text: String): List<String> = try {
        val el = json.parseToJsonElement(text)
        val arr: JsonArray = when {
            el is JsonArray -> el
            el is JsonObject && el["data"] is JsonArray -> el["data"] as JsonArray
            else -> JsonArray(emptyList())
        }
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            // 只认字符串值：数字 id 是脏数据，放进去没法发给 /chat/completions
            listOf("id", "model", "name").firstNotNullOfOrNull { key ->
                (o[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** 温度解析：兼容中文逗号小数；非法/越界回退默认 0.3 */
    fun parseTemperature(raw: String, fallback: Double = 0.3): Double =
        raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: fallback

    suspend fun chat(req: ChatRequest, onDelta: (String) -> Unit = {}): String {
        // AI 运行日志（2026-11-28）：begin 在限流前——RPM 排队时长老板也看得见；acquire
        // 中被取消要回收日志（cancel），不能留一条永远 QUEUED 的僵尸记录
        val logId = AiLogBus.begin(req.source, req.model)
        try {
            rpmLimiter.acquire()
        } catch (e: CancellationException) {
            AiLogBus.cancel(logId)
            throw e
        }
        AiLogBus.streaming(logId)
        return withContext(Dispatchers.IO) {
            val url = normalizeChatUrl(req.baseUrl).toHttpUrlOrNull()
            if (url == null) {
                // 非法地址（旧版 MalformedURLException 路径）：用户存了坏配置时的专属文案
                AiLogBus.finish(logId, error = "API 地址格式不正确，请检查设置")
                throw AiException("API 地址格式不正确，请检查设置")
            }
            val job = coroutineContext[Job]
            // 阻塞中的 SSE 读要靠 call.cancel() 打断（okhttp cancel 线程安全、立即生效，
            // 会中断阻塞 read 让它抛 IOException）。挂一个哨兵子协程：取消传播到它时
            // finally 里 cancel。
            // （invokeOnCompletion 默认 onCancelling=false 等完全结束才触发，为时已晚；
            //   onCancelling=true 版本是 internal API，故用哨兵这一公开惯用法）
            val call = http.newBuilder()
                .readTimeout(req.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
                .newCall(
                    Request.Builder().url(url)
                        .header("Authorization", "Bearer ${req.apiKey}")
                        .header("Accept", "text/event-stream")
                        .post(bodyOf(req).toRequestBody("application/json".toMediaType()))
                        .build(),
                )
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { r ->
                    // 取消可能发生在连接/发请求阶段：响应头一到就检查，别等读超时
                    coroutineContext.ensureActive()
                    if (r.code !in 200..299) {
                        val msg = friendlyHttp(r.code, errorHead(r))
                        AiLogBus.finish(logId, error = msg)
                        throw AiException(msg, httpCode = r.code)
                    }

                    // think 标签抑制（实景体验轮 2026-11-21）：推理模型（MiniMax-M2.5 实录）把
                    // 思考过程内联在正文增量里，这里收口后所有消费者拿到的都是干净正文；
                    // onThink 非 null 时思考段增量另路回调（随手问/换个讲法盯屏显示用）
                    val think = ThinkSuppressor(req.onThink)
                    val full = StringBuilder()
                    r.body?.charStream()?.buffered()?.useLines { lines ->
                        for (line in lines) {
                            coroutineContext.ensureActive()
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            val delta = extractDelta(data)
                            if (delta.isNotEmpty()) {
                                val visible = think.feed(delta)
                                if (visible.isNotEmpty()) {
                                    full.append(visible)
                                    onDelta(visible)
                                    AiLogBus.delta(logId, visible)
                                }
                            }
                        }
                    }
                    val tail = think.finish()
                    if (tail.isNotEmpty()) {
                        full.append(tail)
                        onDelta(tail)
                        AiLogBus.delta(logId, tail)
                    }
                    AiLogBus.finish(logId)
                    full.toString()
                }
            } catch (e: CancellationException) {
                AiLogBus.cancel(logId)
                throw e
            } catch (e: AiException) {
                AiLogBus.finish(logId, error = e.message)
                throw e
            } catch (e: IOException) {
                // call.cancel() 触发的"取消型" IOException 还原为取消，不冒充网络错误
                if (job?.isCancelled == true) {
                    AiLogBus.cancel(logId)
                    throw CancellationException("cancelled")
                }
                val msg = friendlyIo(e)
                AiLogBus.finish(logId, error = msg)
                throw AiException(msg)
            } catch (e: Exception) {
                if (job?.isCancelled == true) {
                    AiLogBus.cancel(logId)
                    throw CancellationException("cancelled")
                }
                val msg = "请求失败：${e.message ?: "未知错误"}"
                AiLogBus.finish(logId, error = msg)
                throw AiException(msg)
            } finally {
                watcher.cancel()
            }
        }
    }

    /**
     * 要求 AI 返回 JSON：chat → 剥壳 → 反序列化；失败自动重试 1 次
     * （附加提示"只输出 JSON"），再失败抛含原始输出片段的异常（计划 M3 §4）。
     *
     * P0-② 降档梯子（借鉴 bilingual_book_maker structured.py 范式，MIT）：
     * - req.jsonMode=true 先走 JSON mode（response_format）；网关以 400/422 拒收或解析/校验不过
     *   → 降纯 prompt 档（被拒档位本轮内不重打，不重复付费），已有输出作为 assistant 上下文带入；
     * - validate 钩子非空时对解析结果做结构校验（如判卷结果全空），失败带纠正语重试一次；
     * - jsonMode=false 的调用方行为与旧版完全一致（零回归面）。
     */
    suspend fun <T> chatJson(
        req: ChatRequest,
        deserializer: DeserializationStrategy<T>,
        validate: ((T) -> String?)? = null,
        onDelta: (String) -> Unit = {},
    ): T {
        if (req.jsonMode) {
            val first = try {
                chat(req, onDelta)
            } catch (e: AiException) {
                // 仅格式类拒收降档（response_format 未实现）；鉴权/限流/路径错原样抛，交上层退避
                if (e.httpCode == 400 || e.httpCode == 422) null else throw e
            }
            if (first != null) {
                val parsed = parseJson(first, deserializer)
                val err = parsed?.let { validate?.invoke(it) }
                if (parsed != null && err == null) return parsed
                return chatJsonFallback(req, deserializer, onDelta, validate, seed = first, corrective = err)
            }
            return chatJsonFallback(req, deserializer, onDelta, validate, seed = null, corrective = null)
        }
        return chatJsonFallback(req, deserializer, onDelta, validate, seed = null, corrective = null)
    }

    /** 解析 + 剥壳一次；失败返回 null（取消除外）。chatJson 梯子各档共用 */
    private fun <T> parseJson(raw: String, deserializer: DeserializationStrategy<T>): T? = try {
        json.decodeFromString(deserializer, JsonSlicer.slice(raw))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 纯 prompt 档（原 chatJson 主体）：首打（可带降档种子与纠正语）→ 失败纠正重试 1 次 → 抛人话异常 */
    private suspend fun <T> chatJsonFallback(
        req: ChatRequest,
        deserializer: DeserializationStrategy<T>,
        onDelta: (String) -> Unit,
        validate: ((T) -> String?)?,
        seed: String?,
        corrective: String?,
    ): T {
        val plainReq = req.copy(
            jsonMode = false,
            messages = req.messages +
                // 降档种子：JSON mode 档已产出内容时作为 assistant 上下文带入（帮模型对齐格式）；
                // 空正文不追加空 content 的 assistant 消息（部分网关校验拒绝空 content）
                (if (!seed.isNullOrBlank()) listOf(AiMessage("assistant", seed.take(2000))) else emptyList()) +
                (if (corrective != null) listOf(AiMessage("user", "上一轮输出的问题：$corrective。请修正后重新输出完整 JSON。")) else emptyList()),
        )
        val first = chat(plainReq, onDelta)
        val parsed = parseJson(first, deserializer)
        val err = parsed?.let { validate?.invoke(it) }
        if (parsed != null && err == null) return parsed
        // 立刻重打常撞限流窗口；稍等片刻再要 JSON（推理模型输出长，也给网关喘息）
        delay(1_500)
        // 正文为空 = 思考吃满了输出预算（finish=length）：原样重打只会再收到一次空串，
        // 必须翻倍预算并换提示（"不是合法 JSON"对空正文场景语义不准）。
        // maxTokens=null 的请求无从翻倍、沿用服务端默认预算（当前所有 chatJson 调用方都显式传值）
        val blankContent = first.isBlank()
        val hint = when {
            blankContent -> BLANK_CONTENT_HINT
            parsed != null -> "你上一轮的 JSON 结构不合格（$err）。请重新输出完整 JSON，不要任何其他文字。"
            else -> RETRY_HINT
        }
        val retryReq = plainReq.copy(
            maxTokens = if (blankContent) plainReq.maxTokens?.let { it * 2 } else plainReq.maxTokens,
            messages = plainReq.messages +
                (if (!blankContent) listOf(AiMessage("assistant", first.take(2000))) else emptyList()) +
                AiMessage("user", hint),
        )
        val second = chat(retryReq, onDelta)
        val parsed2 = parseJson(second, deserializer)
        val err2 = parsed2?.let { validate?.invoke(it) }
        if (parsed2 != null && err2 == null) return parsed2
        throw AiException(
            when {
                parsed2 != null -> "AI 输出的 JSON 结构不合格（$err2），原始输出片段：${second.take(120)}"
                else -> "AI 输出的 JSON 无法解析，原始输出片段：${second.take(120)}"
            },
        )
    }

    private const val RETRY_HINT = "你上一次的输出不是合法 JSON。请只输出 JSON，不要任何其他文字、解释或代码围栏。"

    private const val BLANK_CONTENT_HINT = "上一次回复没有任何正文内容（可能是思考耗尽了输出预算），请直接输出最终结果本身。"

    private fun bodyOf(req: ChatRequest): String = buildJsonObject {
        put("model", req.model)
        put("stream", true)
        put("temperature", req.temperature)
        req.maxTokens?.let { put("max_tokens", it) }
        req.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let {
            // 思考强度（OpenAI 兼容口径）：空白值不发，保持与服务商默认完全一致的旧行为
            put("reasoning_effort", it)
        }
        if (req.jsonMode) {
            // P0-② 降档梯子第 1 档：JSON mode（OpenAI 兼容 response_format）
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
        putJsonArray("messages") {
            req.messages.forEach { m ->
                addJsonObject {
                    put("role", m.role)
                    if (m.images.isEmpty()) {
                        put("content", m.content)
                    } else {
                        // OpenAI 兼容视觉格式：content 为 parts 数组（文本段 + image_url 段）
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            }
                            m.images.forEach { uri ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", uri) }
                                }
                            }
                        }
                    }
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

    /**
     * 连接层错误人话化（实景体验轮 2026-11-21）：此前直接把 Java 异常原文亮给用户
     * （实测「网络异常： failed to connect to vsllm.com/2a03:2880:… after 15000ms」）。
     * 按异常类型给可操作的人话建议，技术细节截短附后供反馈排查。
     */
    internal fun friendlyIo(e: IOException): String {
        val detail = (e.message ?: "").take(120)
        val msg = e.message ?: ""
        val hint = when (e) {
            is UnknownHostException -> "域名解析失败——检查 API 地址拼写，或当前网络访问不了该域名"
            is ConnectException -> "连不上服务器——检查网络，或该地址需要代理才能访问"
            is SocketTimeoutException ->
                if (msg.contains("connect", ignoreCase = true)) "连接超时——网络不通、服务器无响应，或该地址需要代理"
                else "服务器响应超时——稍后重试，或换更快的模型"
            is SSLException -> "HTTPS 握手失败——检查 API 地址是否正确、端口是否被拦截"
            else -> "网络连接失败——检查网络后重试"
        }
        return if (detail.isBlank()) hint else "$hint（详情：$detail）"
    }

    /** 只读错误响应前 300 字节（低内存友好），body 缺失返回空串 */
    private fun errorHead(resp: Response): String = try {
        resp.peekBody(300L).string()
    } catch (e: IOException) {
        ""
    }
}
