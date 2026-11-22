package com.studyfriend.app.data.tts

import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Edge 在线朗读客户端：Sec-MS-GEC v2 令牌（edge-tts drm.py 参考实现交叉验证）/SSML 组帧/音频帧解析/MockWebServer 全链路/403 时钟校正重试。
 *  Robolectric：失败路径会打 android.util.Log（纯 JVM 没有该实现，挂 runner 才能跑） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EdgeTtsClientTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------- 1. Sec-MS-GEC v2：参考实现交叉验证向量 + 5 分钟窗粒度 ----------

    @Test
    fun secMsGecV2_matchesReferenceImplementationVectors() {
        // edge-tts drm.py generate_sec_ms_gec_v2 独立生成（ticks=300 秒粒度×1e7 拼 TrustedClientToken，
        // 摘要=调整常量 SHA-256 + CS64，格式 <16hex>-<64hex>）
        val expected = "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2"
        assertEquals(expected, generateSecMsGecV2(1700000000))
        // 5 分钟窗内（+99 秒）同一令牌
        assertEquals(expected, generateSecMsGecV2(1700000099))
        assertEquals(
            "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            generateSecMsGecV2(0),
        )
        // 格式：<16 hex>-<64 hex>
        assertTrue(generateSecMsGecV2(0).matches(Regex("[0-9A-F]{16}-[0-9A-F]{64}")))
    }

    // ---------- 2. SSML 组帧：转义 / 语速 / 固定 en-US ----------

    @Test
    fun buildSsml_escapesXmlAndFormatsRate() {
        val ssml = buildSsml("甲<乙>丙&丁", EdgeVoices.XIAOXIAO, 20)
        assertTrue("&amp;" in ssml)
        assertTrue("&lt;乙&gt;" in ssml)
        assertTrue("<乙>" !in ssml)
        assertTrue("rate='+20%'" in ssml)
        assertTrue("xml:lang='en-US'" in ssml)
        assertTrue("voice name='zh-CN-XiaoxiaoNeural'" in ssml)
        val rateOf = { p: Int -> buildSsml("x", EdgeVoices.YUNXI, p).substringAfter("rate='").substringBefore("'") }
        assertEquals("+0%", rateOf(0))
        assertEquals("-30%", rateOf(-30))
        assertEquals("+100%", rateOf(100))
    }

    // ---------- 3. 协议消息：speech.config 与 ssml 头块 ----------

    @Test
    fun protocolMessages_carryPathsAndFormat() {
        val cfg = speechConfigMessage("ts")
        assertTrue(cfg.startsWith("X-Timestamp:ts\r\nContent-Type:application/json; charset=utf-8\r\n"))
        assertTrue("Path:speech.config" in cfg)
        assertTrue("audio-24khz-48kbitrate-mono-mp3" in cfg)
        assertTrue("JSON 尾随空行（参考实现同款）", cfg.endsWith("}}}}\r\n"))
        assertTrue("\"wordBoundaryEnabled\":\"true\"" in cfg)
        val ssml = ssmlMessage("你好。", EdgeVoices.XIAOXIAO, 0, "ts", "reqid")
        assertTrue(ssml.startsWith("X-RequestId:reqid\r\nContent-Type:application/ssml+xml\r\n"))
        assertTrue("X-Timestamp 尾带 Z（微软怪癖）", "X-Timestamp:tsZ\r\n" in ssml)
        assertTrue("Path:ssml" in ssml)
        assertTrue(ssml.endsWith("你好。</prosody></voice></speak>"))
    }

    // ---------- 4. 二进制音频帧解析 ----------

    @Test
    fun parseAudioFrame_extractsPayload() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frame = audioFrame(payload)
        assertTrue(payload.contentEquals(parseAudioFrame(frame)!!))
        // 非 audio 路径（turn.start 等）返回 null
        val otherHeader = "X-Timestamp:ts\r\nPath:turn.start\r\n".toByteArray(Charsets.US_ASCII)
        val other = byteArrayOf(((otherHeader.size shr 8) and 0xFF).toByte(), (otherHeader.size and 0xFF).toByte()) +
            otherHeader
        assertNull(parseAudioFrame(other.toByteString()))
        // 残帧（声明的头长超出实际字节）返回 null
        assertNull(parseAudioFrame(byteArrayOf(0x10, 0x00, 0x01).toByteString()))
        // 过短帧返回 null
        assertNull(parseAudioFrame(byteArrayOf(0x00).toByteString()))
    }

    // ---------- 5. MockWebServer 全链路：config → ssml → 两帧音频 → turn.end ----------

    @Test
    fun synthesize_fullRoundTrip(): Unit = runBlocking {
        val payload1 = ByteArray(300) { (it % 251).toByte() }
        val payload2 = ByteArray(120) { 0x55 }
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if ("Path:ssml" in text) {
                        // 客户端发来的 ssml 应带原文（无转义需求）与音色
                        assertTrue("你好，世界。" in text)
                        assertTrue("zh-CN-XiaoxiaoNeural" in text)
                        webSocket.send(audioFrame(payload1))
                        webSocket.send(audioFrame(payload2))
                        webSocket.send(
                            "X-RequestId:x\r\nContent-Type:application/json; charset=utf-8\r\n" +
                                "Path:turn.end\r\n\r\n{}"
                        )
                    }
                }
            })
        )
        val client = EdgeTtsClient(endpoint = "ws://${server.hostName}:${server.port}/?TrustedClientToken=test")
        val bytes = client.synthesize("你好，世界。", EdgeVoices.XIAOXIAO, 0)

        assertEquals(payload1.size + payload2.size, bytes!!.size)
        assertTrue(payload1.contentEquals(bytes.copyOfRange(0, payload1.size)))
        // 握手 URL 带 v2 GEC 参数；握手头带扩展 Origin 与 muid Cookie
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertTrue(url.queryParameter("Sec-MS-GEC")!!.matches(Regex("[0-9A-F]{16}-[0-9A-F]{64}")))
        assertEquals("2-143.0.3650.75", url.queryParameter("Sec-MS-GEC-Version"))
        assertTrue(url.queryParameter("ConnectionId")?.length == 32)
        assertEquals("test", url.queryParameter("TrustedClientToken"))
        assertEquals("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold", request.getHeader("Origin"))
        assertTrue(request.getHeader("Cookie")!!.matches(Regex("muid=[0-9A-F]{32};")))
    }

    // ---------- 6. 403 → 时钟校正 → 重试成功 ----------

    @Test
    fun synthesize_authFailure_retriesWithCorrectedClock(): Unit = runBlocking {
        // 第一次握手 403；探测返回固定服务器时间（2027 年，与当前必然不同窗）；第二次握手成功
        val fixedServerEpoch = 1_800_000_000L
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if ("Path:ssml" in text) {
                        webSocket.send(audioFrame(byteArrayOf(1, 2, 3)))
                        webSocket.send("X-RequestId:x\r\nPath:turn.end\r\n\r\n{}")
                    }
                }
            })
        )
        val client = EdgeTtsClient(
            endpoint = "ws://${server.hostName}:${server.port}/?TrustedClientToken=test",
            clockSkewProbe = { fixedServerEpoch },
        )
        val bytes = client.synthesize("内容。", EdgeVoices.XIAOXIAO, 0)

        assertEquals(3, bytes!!.size)
        // 请求按时间序：第一发=本机时钟的 GEC；第二发=校正后（固定服务器时间）的 GEC
        val firstRequest = server.takeRequest()
        val secondRequest = server.takeRequest()
        val gecFirst = firstRequest.requestUrl!!.queryParameter("Sec-MS-GEC")!!
        val gecSecond = secondRequest.requestUrl!!.queryParameter("Sec-MS-GEC")!!
        assertEquals(generateSecMsGecV2(fixedServerEpoch), gecSecond)
        assertTrue("两次握手 GEC 必不同", gecFirst != gecSecond)
    }

    // ---------- 7. 握手被拒且探测不到服务器时间 → 返回 null 不重试 ----------

    @Test
    fun synthesize_authFailureWithoutProbe_returnsNull(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val client = EdgeTtsClient(
            endpoint = "ws://${server.hostName}:${server.port}/?TrustedClientToken=test",
            clockSkewProbe = { null },
        )
        assertNull(client.synthesize("内容。", EdgeVoices.XIAOXIAO, 0))
        assertEquals("只有一次握手请求", 1, server.requestCount)
    }

    private fun audioFrame(payload: ByteArray): ByteString {
        val header = "X-RequestId:abc\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n".toByteArray(Charsets.US_ASCII)
        val frame = byteArrayOf(((header.size shr 8) and 0xFF).toByte(), (header.size and 0xFF).toByte()) +
            header + payload
        return frame.toByteString()
    }
}
