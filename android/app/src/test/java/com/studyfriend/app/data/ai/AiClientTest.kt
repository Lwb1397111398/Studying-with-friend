package com.studyfriend.app.data.ai

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    private fun request(base: String, apiKey: String = "sk-test", model: String = "test-model") =
        ChatRequest(
            baseUrl = base, apiKey = apiKey, model = model, temperature = 0.3, maxTokens = 16,
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
                val job = launch { AiClient.chat(request(server.baseUrl)) {} }
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
}
