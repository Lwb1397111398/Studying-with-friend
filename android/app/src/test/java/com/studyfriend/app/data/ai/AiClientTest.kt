package com.studyfriend.app.data.ai

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地 MiniHttpServer 模拟 OpenAI 兼容端点（runBlocking 真实 IO，不用 runTest 虚拟时间） */
class AiClientTest {

    private fun request(
        base: String,
        apiKey: String = "sk-test",
        model: String = "test-model",
        readTimeoutMs: Int = AiBudgets.READ_TIMEOUT_DEFAULT_MS,
    ) = ChatRequest(
        baseUrl = base, apiKey = apiKey, model = model, temperature = 0.3, maxTokens = 16,
        readTimeoutMs = readTimeoutMs,
        messages = listOf(AiMessage("user", "ping")),
    )

    @Test
    fun chat_streamsDeltasInOrder_andReturnsFullText() {
        val server = MiniHttpServer { _, _, _, resp -> resp.sse(listOf("你", "好", "，搭子")) }
        try {
            val deltas = mutableListOf<String>()
            val full = runBlocking { AiClient.chat(request(server.baseUrl)) { deltas.add(it) } }
            assertEquals(listOf("你", "好", "，搭子"), deltas)
            assertEquals("你好，搭子", full)
        } finally {
            server.stop()
        }
    }

    @Test
    fun chat_suppressesInlineThinkBlock_splitAcrossDeltas() {
        // MiniMax-M2.5 实录（2026-11-21 随手问）：思考段内联在正文增量里，标签跨 SSE 分片；
        // onDelta 与返回值都不得出现思考内容
        val server = MiniHttpServer { _, _, _, resp ->
            resp.sse(listOf("<th", "ink>英文思考草稿", "更多思考", "</thi", "nk>判断婴儿", "是否已出生"))
        }
        try {
            val deltas = mutableListOf<String>()
            val full = runBlocking { AiClient.chat(request(server.baseUrl)) { deltas.add(it) } }
            assertEquals("判断婴儿是否已出生", full)
            assertTrue(deltas.none { it.contains("think") || it.contains("思考") })
        } finally {
            server.stop()
        }
    }

    @Test
    fun chat_unclosedThink_returnsEmptyNotThinkingContent() {
        val server = MiniHttpServer { _, _, _, resp -> resp.sse(listOf("<think>", "只有思考", "没有正文")) }
        try {
            val deltas = mutableListOf<String>()
            val full = runBlocking { AiClient.chat(request(server.baseUrl)) { deltas.add(it) } }
            assertEquals("", full)
            assertTrue(deltas.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun chat_onThink_receivesThinking_bodyAndLogStayClean() {
        // 老板诉求「思考亮出来」（2026-11-28）：思考增量另路进 onThink（随手问/换个讲法
        // 盯屏显示用）；正文 onDelta、返回值、AI 运行日志一律不含思考（落库口径不变）
        val server = MiniHttpServer { _, _, _, resp ->
            resp.sse(listOf("<th", "ink>英文草稿", "更多思考", "</thi", "nk>", "判断婴儿", "是否已出生"))
        }
        try {
            val thinks = mutableListOf<String>()
            val deltas = mutableListOf<String>()
            AiLogBus.clear()
            val full = runBlocking {
                AiClient.chat(request(server.baseUrl).copy(onThink = { thinks.add(it) })) { deltas.add(it) }
            }
            assertEquals("判断婴儿是否已出生", full)
            assertEquals(listOf("判断婴儿", "是否已出生"), deltas)
            assertEquals("英文草稿更多思考", thinks.joinToString(""))
            // AI 运行日志保持正文口径：text/chars 都不含思考
            val log = AiLogBus.snapshots.value[0]
            assertEquals(full, log.text)
            assertEquals(full.length, log.chars)
        } finally {
            server.stop()
        }
    }

    @Test
    fun chat_requestHasAuthHeaderStreamAndModel() {
        val auth = ConcurrentLinkedQueue<String>()
        val bodies = ConcurrentLinkedQueue<String>()
        val server = MiniHttpServer { _, headers, body, resp ->
            auth.add(headers["authorization"])
            bodies.add(body)
            resp.sse(listOf("ok"))
        }
        try {
            runBlocking { AiClient.chat(request(server.baseUrl, apiKey = "sk-abc", model = "gpt-x")) {} }
            assertEquals("Bearer sk-abc", auth.poll())
            val body = bodies.poll()
            assertTrue(body.contains("\"stream\":true"))
            assertTrue(body.contains("\"model\":\"gpt-x\""))
            assertTrue(body.contains("\"max_tokens\":16"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun chat_reasoningEffort_highGoesInBody_defaultOmitted() {
        // 思考强度（2026-11-28）：设置 low/medium/high 请求体带 reasoning_effort（OpenAI
        // 兼容口径）；默认（null/空白）不发——保持与服务商默认完全一致的旧行为
        val bodies = ConcurrentLinkedQueue<String>()
        val server = MiniHttpServer { _, _, body, resp ->
            bodies.add(body)
            resp.sse(listOf("ok"))
        }
        try {
            runBlocking {
                AiClient.chat(request(server.baseUrl).copy(reasoningEffort = "high")) {}
                AiClient.chat(request(server.baseUrl).copy(reasoningEffort = "  ")) {}
                AiClient.chat(request(server.baseUrl)) {}
            }
            val high = bodies.poll()
            assertTrue("reasoningEffort=high 应进请求体", high.contains("\"reasoning_effort\":\"high\""))
            // 空白值不发（trim 后为空视为默认）
            assertTrue(bodies.poll()?.contains("reasoning_effort") != true)
            // 默认 null 不发
            assertTrue(bodies.poll()?.contains("reasoning_effort") != true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun chat_401_friendlyMessage() {
        val server = MiniHttpServer { _, _, _, resp -> resp.status(401, "Unauthorized") }
        try {
            val err = runBlocking {
                try {
                    AiClient.chat(request(server.baseUrl)) {}
                    null
                } catch (e: AiException) {
                    e.message
                }
            }
            assertEquals("API Key 无效或无权限", err)
        } finally {
            server.stop()
        }
    }

    // ---- AI 运行日志埋点（2026-11-28）：chat 全链路应落 AiLogBus 记录 ----

    @Test
    fun chat_logsDoneSession_withCharsAndSource() {
        val server = MiniHttpServer { _, _, _, resp -> resp.sse(listOf("你", "好")) }
        try {
            AiLogBus.clear()
            val full = runBlocking { AiClient.chat(request(server.baseUrl).copy(source = "测试链路")) {} }
            val s = AiLogBus.snapshots.value[0]
            assertEquals(AiLogState.DONE, s.state)
            assertEquals("测试链路", s.source)
            assertEquals(full.length, s.chars)
            assertEquals(full, s.text)
            assertNotNull(s.firstDeltaMs)
            assertNotNull(s.endMs)
        } finally {
            server.stop()
        }
    }

    @Test
    fun chat_httpFailure_logsErrorSession() {
        val server = MiniHttpServer { _, _, _, resp -> resp.status(500, "boom") }
        try {
            AiLogBus.clear()
            runBlocking {
                try {
                    AiClient.chat(request(server.baseUrl)) {}
                } catch (_: AiException) {
                }
            }
            val s = AiLogBus.snapshots.value[0]
            assertEquals(AiLogState.ERROR, s.state)
            assertTrue(s.error?.isNotBlank() == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun chat_404_friendlyMessage() {
        val server = MiniHttpServer { _, _, _, resp -> resp.status(404, "Not Found") }
        try {
            val err = runBlocking {
                try {
                    AiClient.chat(request(server.baseUrl)) {}
                    null
                } catch (e: AiException) {
                    e.message
                }
            }
            assertEquals("接口路径不存在，检查 API 地址", err)
        } finally {
            server.stop()
        }
    }

    @Test
    fun cancel_onHangingServer_returnsPromptly() {
        val headerSent = CountDownLatch(1)
        val server = MiniHttpServer { _, _, _, resp ->
            // 只写响应头然后挂住，制造阻塞中的 SSE 读
            resp.status(200)
            headerSent.countDown()
            Thread.sleep(30_000)
        }
        try {
            val finished = runBlocking {
                // readTimeout 3s 是内核级兜底锚点（2026-11-28 加固）：全量跑（千例 JVM 后段）
                // 时取消传导偶发延迟（IO 池被前序用例协程挤占，watcher disconnect 排不上队，
                // 实录连挂 time 恒 30s=等 server 睡醒）——readTimeout 打断阻塞读不依赖协程
                // 调度，任何环境 3s 内必退，落回 5s 断言；传导正常时仍是毫秒级。
                // 协程体 catch 吞掉 readTimeout 的 SocketTimeoutException：退出即目的，
                // 不得让未捕获异常污染后续测试类（UncaughtExceptionsBeforeTest 实录）
                val job = launch {
                    try {
                        AiClient.chat(request(server.baseUrl, readTimeoutMs = 3_000)) {}
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                // 等 server 已写响应头：此刻客户端必然正阻塞在 SSE 读上，
                // 消除"cancel 抢在请求发出前、disconnect 对未连接 socket 无效"的竞态。
                // 必须在 IO 线程阻塞等待——直接 await 会卡死 runBlocking 的
                // event loop，launch 的协程体（尚在队列里）永远不执行
                withContext(Dispatchers.IO) { headerSent.await(5, TimeUnit.SECONDS) }
                job.cancel()
                // 断言"有限时间内返回"（评审 P2-3：足够但有限的超时）
                withTimeoutOrNull(5_000) { job.join(); true }
            }
            assertTrue("取消后 5 秒内应返回", finished == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun chatUrlNormalization() {
        assertEquals(
            "https://api.x.com/v1/chat/completions",
            AiClient.normalizeChatUrl("https://api.x.com/v1/chat/completions/"),
        )
        assertEquals("https://api.x.com/v1/chat/completions", AiClient.normalizeChatUrl(" https://api.x.com/v1 "))
        assertEquals("https://api.x.com/chat/completions", AiClient.normalizeChatUrl("https://api.x.com"))
    }

    @Test
    fun temperatureParsing() {
        assertEquals(0.3, AiClient.parseTemperature("0.3"), 1e-9)
        assertEquals(0.3, AiClient.parseTemperature("0,3"), 1e-9)
        assertEquals(1.0, AiClient.parseTemperature("1.0"), 1e-9)
        assertEquals(0.3, AiClient.parseTemperature("1.5"), 1e-9)
        assertEquals(0.3, AiClient.parseTemperature("abc"), 1e-9)
        assertEquals(0.3, AiClient.parseTemperature(""), 1e-9)
    }

    @Test
    fun friendlyIo_humanizesByExceptionType() {
        // 实景体验轮：连接层异常不再直接亮 Java 原文——按类型给人话建议，技术细节截短附后
        val timeout = java.net.SocketTimeoutException(
            "failed to connect to vsllm.com/2a03:2880:f131:83:face:b00c:0:25de (port 443) after 15000ms",
        )
        val msg = AiClient.friendlyIo(timeout)
        assertTrue(msg.contains("连接超时"))
        assertTrue(msg.contains("详情："))
        // 原始消息截短，不让超长 IP 链路占满弹层
        assertTrue(msg.length < 200)

        assertTrue(AiClient.friendlyIo(java.net.UnknownHostException("x.example")).contains("域名解析失败"))
        assertTrue(AiClient.friendlyIo(java.net.ConnectException("Connection refused")).contains("连不上服务器"))
        // 读超时（非连接阶段）给「响应超时」文案
        assertTrue(AiClient.friendlyIo(java.net.SocketTimeoutException("Read timed out")).contains("响应超时"))
        // 无 message 异常不至于崩
        assertTrue(AiClient.friendlyIo(java.io.IOException()).contains("网络连接失败"))
    }

    @Test
    fun chatJson_retryOnGarbage_thenSuccess() {
        val calls = AtomicInteger(0)
        val lastBody = ConcurrentLinkedQueue<String>()
        val server = MiniHttpServer { _, _, body, resp ->
            lastBody.add(body)
            val n = calls.incrementAndGet()
            resp.sse(if (n == 1) listOf("抱歉我不明白。") else listOf("{\"a\":2}"))
        }
        try {
            @Serializable
            data class Dummy(val a: Int)
            val result = runBlocking { AiClient.chatJson(request(server.baseUrl), serializer<Dummy>()) {} }
            assertEquals(2, calls.get())
            assertEquals(Dummy(2), result)
            lastBody.poll() // 第一次请求体：原始提示，无重试话术
            assertTrue(lastBody.poll()?.contains("只输出 JSON") == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun chatJson_twoFailures_throwsWithOriginalOutput() {
        val server = MiniHttpServer { _, _, _, resp -> resp.sse(listOf("我还是不明白。")) }
        try {
            @Serializable
            data class Dummy(val a: Int)
            val err = runBlocking {
                try {
                    AiClient.chatJson(request(server.baseUrl), serializer<Dummy>()) {}
                    null
                } catch (e: AiException) {
                    e.message
                }
            }
            assertNotNull(err)
            assertTrue(err!!.contains("我还是不明白"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun chatJson_blankContent_retryDoublesMaxTokens_andUsesBlankHint() {
        val calls = AtomicInteger(0)
        val bodies = ConcurrentLinkedQueue<String>()
        val server = MiniHttpServer { _, _, body, resp ->
            bodies.add(body)
            val n = calls.incrementAndGet()
            // 第一次正文为空（推理模型思考吃满预算），第二次才给合法 JSON
            resp.sse(if (n == 1) listOf("") else listOf("{\"a\":2}"))
        }
        try {
            @Serializable
            data class Dummy(val a: Int)
            val result = runBlocking { AiClient.chatJson(request(server.baseUrl), serializer<Dummy>()) {} }
            assertEquals(Dummy(2), result)
            assertEquals(2, calls.get())
            bodies.poll() // 第一次请求体
            val retryBody = bodies.poll()
            // request() 助手固定 maxTokens=16：空正文重试必须翻倍
            assertTrue("重试请求应带翻倍预算 max_tokens=32", retryBody?.contains("\"max_tokens\":32") == true)
            // 空正文场景的提示语义不是"不是合法 JSON"
            assertTrue(retryBody?.contains("直接输出最终结果本身") == true)
        } finally {
            server.stop()
        }
    }

    // ---------- P0-② 降档梯子：JSON mode 首选 → 网关拒收/解析/校验不过自动降纯 prompt 档 ----------

    @Test
    fun chatJson_jsonMode_putsResponseFormatInBody() {
        val bodies = ConcurrentLinkedQueue<String>()
        val server = MiniHttpServer { _, _, body, resp ->
            bodies.add(body)
            resp.sse(listOf("{\"a\":1}"))
        }
        try {
            @Serializable
            data class Dummy(val a: Int)
            val result = runBlocking { AiClient.chatJson(request(server.baseUrl).copy(jsonMode = true), serializer<Dummy>()) {} }
            assertEquals(Dummy(1), result)
            assertTrue("jsonMode 请求体应带 response_format", bodies.poll()?.contains("response_format") == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun chatJson_jsonModeRejected400_degradesToPlainWithoutIt() {
        val calls = AtomicInteger(0)
        val bodies = ConcurrentLinkedQueue<String>()
        val server = MiniHttpServer { _, _, body, resp ->
            bodies.add(body)
            val n = calls.incrementAndGet()
            // 第 1 档：网关不认 response_format 回 400 → 降档后第 2 次给合法 JSON
            if (n == 1) resp.status(400, "response_format is not supported") else resp.sse(listOf("{\"a\":2}"))
        }
        try {
            @Serializable
            data class Dummy(val a: Int)
            val result = runBlocking { AiClient.chatJson(request(server.baseUrl).copy(jsonMode = true), serializer<Dummy>()) {} }
            assertEquals(Dummy(2), result)
            assertEquals(2, calls.get())
            assertTrue("第 1 档应带 response_format", bodies.poll()?.contains("response_format") == true)
            assertTrue("降档后不得再带 response_format", bodies.poll()?.contains("response_format") != true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun chatJson_validateFailure_retriesWithCorrectiveHint_thenThrows() {
        val calls = AtomicInteger(0)
        val bodies = ConcurrentLinkedQueue<String>()
        val server = MiniHttpServer { _, _, body, resp ->
            bodies.add(body)
            calls.incrementAndGet()
            // 两轮都返回合法 JSON，但过不了校验 → 纠正语重试一次后抛结构不合格
            resp.sse(listOf("{\"a\":0}"))
        }
        try {
            @Serializable
            data class Dummy(val a: Int)
            val err = runBlocking {
                try {
                    AiClient.chatJson(
                        request(server.baseUrl),
                        serializer<Dummy>(),
                        validate = { if (it.a == 0) "a 不能为 0" else null },
                    ) {}
                    null
                } catch (e: AiException) {
                    e.message
                }
            }
            assertEquals(2, calls.get())
            assertNotNull(err)
            assertTrue(err!!.contains("结构不合格"))
            assertTrue(err.contains("a 不能为 0"))
            bodies.poll() // 第 1 轮请求体
            assertTrue("重试请求应带纠正语", bodies.poll()?.contains("a 不能为 0") == true)
        } finally {
            server.stop()
        }
    }

    // ---------- listModels（「查找模型」拉服务商模型列表） ----------

    @Test
    fun listModels_parsesDataIds_getPathAndBearer() {
        val paths = ConcurrentLinkedQueue<String>()
        val auth = ConcurrentLinkedQueue<String>()
        val server = MiniHttpServer { path, headers, _, resp ->
            paths.add(path)
            auth.add(headers["authorization"])
            resp.json("""{"object":"list","data":[{"id":"b-model","object":"model"},{"id":"a-model"}]}""")
        }
        try {
            val models = runBlocking { AiClient.listModels(server.baseUrl, "sk-abc") }
            // 去尾斜杠的 base 拼上 /models；按字母排序
            assertEquals(listOf("a-model", "b-model"), models)
            assertEquals("/v1/models", paths.poll())
            assertEquals("Bearer sk-abc", auth.poll())
        } finally {
            server.stop()
        }
    }

    @Test
    fun listModels_toleratesBareArray_andModelField() {
        val server = MiniHttpServer { _, _, _, resp ->
            // 个别网关返回裸数组、字段用 model/name：宽松兼容
            resp.json("""[{"model":"m1"},{"name":"m2"},{"id":123,"x":1}]""")
        }
        try {
            val models = runBlocking { AiClient.listModels(server.baseUrl, "sk") }
            assertEquals(listOf("m1", "m2"), models)
        } finally {
            server.stop()
        }
    }

    @Test
    fun listModels_garbageBody_returnsEmpty_notCrash() {
        val server = MiniHttpServer { _, _, _, resp -> resp.json("<html>网关错误页</html>") }
        try {
            val models = runBlocking { AiClient.listModels(server.baseUrl, "sk") }
            assertTrue(models.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun listModels_401_friendlyMessage() {
        val server = MiniHttpServer { _, _, _, resp -> resp.status(401, "Unauthorized") }
        try {
            val err = runBlocking {
                try {
                    AiClient.listModels(server.baseUrl, "sk")
                    null
                } catch (e: AiException) {
                    e.message
                }
            }
            assertEquals("API Key 无效或无权限", err)
        } finally {
            server.stop()
        }
    }
}
