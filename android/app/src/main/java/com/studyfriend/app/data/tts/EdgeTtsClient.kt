package com.studyfriend.app.data.tts

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.Buffer
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Edge 在线朗读可选音色（微软 Edge 浏览器「大声朗读」同款在线神经语音） */
object EdgeVoices {
    /** 晓晓（女声，Edge 中文默认音色） */
    const val XIAOXIAO = "zh-CN-XiaoxiaoNeural"

    /** 云希（男声） */
    const val YUNXI = "zh-CN-YunxiNeural"
}

/**
 * Edge 在线朗读客户端（TTS 三模式之「Edge 在线」）：走 Edge 浏览器「大声朗读」同一套
 * WebSocket 协议（speech.platform.bing.com），无需 API key、无需 205MB 本地模型，有网就能用。
 * 协议对齐社区 edge-tts 实现（2026-11 实抓 v7.x 源码，测试向量由其 drm.py 独立交叉验证）：
 * 1. wss 握手 URL 带 TrustedClientToken + ConnectionId + Sec-MS-GEC（v2 令牌，见 [generateSecMsGecV2]）
 *    + Sec-MS-GEC-Version（"2-143.0.3650.75"）；
 * 2. 握手头：Origin 是 Edge 朗读扩展 ID（chrome-extension://…），另带 muid Cookie；
 * 3. 连上先发 speech.config（声明输出 audio-24khz-48kbitrate-mono-mp3），再发 ssml 请求；
 * 4. 收二进制帧：前 2 字节大端头长，头里 Path:audio 的负载就是 mp3 字节；收到文本帧 Path:turn.end 收尾。
 * [endpoint] 与 [clockSkewProbe] 可注入（单测用 MockWebServer 的 ws:// 换掉生产 wss://，不发真网请求）。
 */
class EdgeTtsClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val clockSkewProbe: suspend () -> Long? = ::probeServerTime,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        // 绕过系统代理直连：模拟器/部分设备会继承宿主 HTTP 代理，代理对 WSS 长连接掐断
        // （实测 connection closed）；speech.platform.bing.com 国内直连可达，直连更稳
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    /** 设备时钟偏差秒数（正常 0；401/403 后按服务器 Date 头校正，下次握手生效） */
    @Volatile
    private var clockSkewSeconds: Long = 0L

    /**
     * 合成一段文本 → mp3 字节。null=失败（原因进日志，引擎层统一人话报错）。
     * 401/403 多为设备时钟偏差超过 5 分钟窗口：探测服务器时间算偏差重试一次。
     */
    suspend fun synthesize(text: String, voice: String, ratePercent: Int): ByteArray? {
        val first = synthesizeOnce(text, voice, ratePercent)
        if (first is SynthResult.Audio) return first.bytes
        if (first is SynthResult.Failure && first.auth) {
            clockSkewProbe()?.let { serverEpoch ->
                clockSkewSeconds = serverEpoch - System.currentTimeMillis() / 1000
                android.util.Log.i(TAG, "设备时钟偏差 ${clockSkewSeconds}s，已校正重试")
                val second = synthesizeOnce(text, voice, ratePercent)
                if (second is SynthResult.Audio) return second.bytes
            }
        }
        return null
    }

    private suspend fun synthesizeOnce(text: String, voice: String, ratePercent: Int): SynthResult {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val url = buildUrl(connectionId, System.currentTimeMillis() / 1000 + clockSkewSeconds)
        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", WSS_ORIGIN)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${newMuid()};")
            .build()

        val outcome = CompletableDeferred<SynthResult>()
        val ws = client.newWebSocket(
            request,
            object : WebSocketListener() {
                private val buffer = Buffer()

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(speechConfigMessage(timestamp()))
                    webSocket.send(ssmlMessage(text, voice, ratePercent, timestamp(), requestId()))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if ("Path:turn.end" in text) {
                        outcome.complete(SynthResult.Audio(buffer.readByteArray()))
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    parseAudioFrame(bytes)?.let { buffer.write(it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    outcome.complete(
                        SynthResult.Failure(
                            response?.let { "HTTP ${it.code}" }
                                ?: "${t.javaClass.simpleName}: ${t.message ?: "连接失败"}",
                            auth = code == 401 || code == 403,
                        ),
                    )
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    outcome.complete(SynthResult.Failure("连接关闭（$code）", auth = false))
                }
            },
        )
        try {
            val result = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { outcome.await() }
            return when (result) {
                is SynthResult.Audio -> result
                is SynthResult.Failure -> {
                    android.util.Log.w(TAG, "Edge 合成失败：${result.message}")
                    result
                }
                null -> {
                    android.util.Log.w(TAG, "Edge 合成超时（${SYNTH_TIMEOUT_MS}ms）")
                    SynthResult.Failure("合成超时", auth = false)
                }
            }
        } finally {
            ws.cancel()
        }
    }

    /** 生产端点：TrustedClientToken 是 Edge 客户端公开常量（非密钥），Sec-MS-GEC 参数在 buildUrl 追加 */
    internal fun buildUrl(connectionId: String, unixSeconds: Long): String =
        "$endpoint&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${generateSecMsGecV2(unixSeconds)}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    private sealed interface SynthResult {
        data class Audio(val bytes: ByteArray) : SynthResult

        /** [auth]=401/403（疑似设备时钟偏差，值得校正重试） */
        data class Failure(val message: String, val auth: Boolean) : SynthResult
    }

    private companion object {
        const val TAG = "EdgeTts"
        const val SYNTH_TIMEOUT_MS = 20_000L

        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

        /** Edge 朗读扩展 ID：微软校验 Origin 必须是它（旧版 azure.microsoft.com 已失效回 403） */
        const val WSS_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

        val DEFAULT_ENDPOINT =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"

        fun requestId(): String = UUID.randomUUID().toString().replace("-", "")

        /** muid Cookie（edge-tts headers_with_muid 同款：32 位大写 hex 随机标识） */
        fun newMuid(): String =
            (1..16).map { "%02X".format((Math.random() * 256).toInt()) }.joinToString("")

        /** edge-tts 同款时间戳格式（JS Date toString 风格，服务端不校验，格式保持对齐） */
        fun timestamp(): String {
            val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date())
        }
    }
}

/** 服务器时间探测（秒级 unix time）：HEAD speech.platform.bing.com 读 Date 响应头；失败返回 null */
private suspend fun probeServerTime(): Long? = withContext(Dispatchers.IO) {
    try {
        val conn = URL("https://speech.platform.bing.com").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "HEAD"
        val serverMs = conn.date
        conn.disconnect()
        if (serverMs > 0) serverMs / 1000 else null
    } catch (t: Throwable) {
        android.util.Log.w("EdgeTts", "服务器时间探测失败：${t.message}")
        null
    }
}

// ======================= SSML 与协议消息 =======================

/** SSML 组装（edge-tts mkssml 同款）：正文做 XML 转义；xml:lang 固定 en-US（与参考实现一致） */
internal fun buildSsml(text: String, voice: String, ratePercent: Int): String {
    val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val rate = if (ratePercent >= 0) "+$ratePercent%" else "$ratePercent%"
    return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
        "<voice name='$voice'><prosody pitch='+0Hz' rate='$rate' volume='+0%'>$escaped</prosody>" +
        "</voice></speak>"
}

/** speech.config 声明消息（edge-tts 同款：JSON 尾随 \r\n，wordBoundary 开/句边界关） */
internal fun speechConfigMessage(ts: String): String =
    "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
        """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false",""" +
        """"wordBoundaryEnabled":"true"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""" + "\r\n"

/** ssml 请求消息：头块 + 空行 + SSML 正文；X-Timestamp 尾带 Z（微软官方实现的已知怪癖） */
internal fun ssmlMessage(text: String, voice: String, ratePercent: Int, ts: String, requestId: String): String =
    "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n" +
        buildSsml(text, voice, ratePercent)

/** 二进制帧解析：前 2 字节大端头长 + 头文本 + 负载；只取 Path:audio 的负载（其余返回 null） */
internal fun parseAudioFrame(bytes: ByteString): ByteArray? {
    if (bytes.size < 2) return null
    val headerLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    if (bytes.size < 2 + headerLen) return null
    val header = bytes.substring(2, 2 + headerLen).utf8()
    if ("Path:audio" !in header) return null
    return bytes.substring(2 + headerLen).toByteArray()
}

// ======================= Sec-MS-GEC v2 令牌 =======================

/**
 * Sec-MS-GEC v2 防伪令牌（2025 下半年微软升级版，edge-tts drm.py 的 Kotlin 逐行移植）：
 * 输入串与 v1 同构——unix 时间加 Windows 纪元（1601-01-01）向下取整 5 分钟窗、乘 1e7（100ns ticks）
 * 拼上 TrustedClientToken；摘要改为「调整常量的 SHA-256 + CS64 校验和」，格式 `<16 hex>-<64 hex>`。
 * 测试向量由 drm.py 参考实现独立生成（EdgeTtsClientTest），改任何一处常量都会挂测试。
 * 全程 Long 低 32 位运算（乘法溢出回绕不影响 mod 2^32 结果）。
 */
internal fun generateSecMsGecV2(unixTimestamp: Long, token: String = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"): String {
    var ticks = unixTimestamp + 11_644_473_600L
    ticks -= ticks % 300
    ticks *= 10_000_000L
    val data = "$ticks$token".toByteArray(Charsets.US_ASCII)
    val (cs64, sha256) = tokenHash(data)
    return "$cs64-$sha256"
}

private const val U32 = 0xFFFFFFFFL

/** 32 位左旋 16 位（rol16：低 16 位与高 16 位互换） */
private fun rol16(value: Long): Long {
    val v = value and U32
    return ((v shl 16) or (v ushr 16)) and U32
}

private val SHA256_INITIAL = longArrayOf(
    0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
    0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
)

/** 前 14 轮（全零字块）效果的预烘焙状态：轮次从 14 起跑 */
private val SHA256_STATE = longArrayOf(
    0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
    0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
)

/** 首 7 字节盐所属块的 words[16..29] 专用排程常量（非标准 SHA-256） */
private val SHA256_SCHEDULE = longArrayOf(
    0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA,
    0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6,
    0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
    0xD258B060, 0x72766564,
)

private val ROUND_CONSTANTS = longArrayOf(
    0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
    0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
    0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
    0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
    0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
    0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
    0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
    0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
)

/** 第 14 轮常量被替换成该值（仅首块使用） */
private const val SHA256_ADJUSTMENT = 0xC9DC06A7L

private val BLOCK_CONSTANTS: LongArray = LongArray(64).also {
    ROUND_CONSTANTS.copyInto(it, 0, 0, 14)
    it[14] = SHA256_ADJUSTMENT
    ROUND_CONSTANTS.copyInto(it, 15, 15, 64)
}

private const val SIGMA0_ADJUSTMENT = 0x059C0B80L
private const val SIGMA1_ADJUSTMENT = 0x000B92C0L

/** CS64 校验和系数（coefficient, offset）成对出现，共 14 对 */
private val CS64_COEFFS = longArrayOf(
    0xE82E91B1, 0x44DE24C6, 0x3BEB65BC, 0xC89C5933,
    0x8D1EC211, 0x314EF5D5, 0xE92491B1, 0xF0A5B9D5,
    0x06064CF3, 0xDA79FA60, 0xBED20560, 0x89F9C9F8,
    0x35FA38C6, 0xCB49E3C6,
)

private val CS64_OFFSETS = longArrayOf(
    0xB32700D0, 0x88DFF51D, 0x29514038, 0xB328CBDF,
    0xD405B4D5, 0x26E55F3C, 0x607801C6, 0x82B2C1FB,
    0xFA51A3DA, 0x207CC464, 0x3C3C74F3, 0xD2D14B61,
    0xBB04A2C1, 0xC5626167,
)

private const val CS64_FACTOR = 0x82893933L
private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

/** SHA-256 small sigma 0/1（结果截到 32 位；加法掩码时机不影响低 32 位结果） */
private fun sigma0(value: Long): Long =
    ((value ushr 7) xor (value shl 25) xor (value ushr 18) xor (value shl 14) xor (value ushr 3)) and U32

private fun sigma1(value: Long): Long =
    ((value ushr 17) xor (value shl 15) xor (value ushr 19) xor (value shl 13) xor (value ushr 10)) and U32

/**
 * SHA-256 轮函数（规范形式，与 edge-tts 的 8 轮展开优化等价）：rotA/rotE 用 ×(2^32+1) 的
 * 64 位技巧把左右旋合并成一次移位——低 32 位加法不受高位影响，结果与逐旋位实现一致。
 */
private fun sha256Rounds(state: LongArray, words: List<Long>, firstRound: Int, useBlockConstants: Boolean): LongArray {
    var a = state[0]
    var b = state[1]
    var c = state[2]
    var d = state[3]
    var e = state[4]
    var f = state[5]
    var g = state[6]
    var h = state[7]
    val constants = if (useBlockConstants) BLOCK_CONSTANTS else ROUND_CONSTANTS
    for (index in firstRound until 64) {
        val rotE = e * 0x100000001L
        val rotA = a * 0x100000001L
        val t1 = h +
            ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
            (g xor (e and (f xor g))) +
            constants[index] + words[index]
        val t2 = ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
            ((a and b) or (c and (a or b)))
        val newA = (t1 + t2) and U32
        val newE = (d + t1) and U32
        d = c; c = b; b = a; a = newA
        h = g; g = f; f = e; e = newE
    }
    return longArrayOf(a, b, c, d, e, f, g, h)
}

/** SHA-256 词扩展（16..63）：sigma 用未掩码中间值，最终和掩码——与参考实现位位一致 */
private fun expandSchedule(words: MutableList<Long>, firstWord: Int) {
    for (index in firstWord until 64) {
        val x = words[index - 2]
        val y = words[index - 15]
        val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
        val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
        words += (s1 + words[index - 7] + s0 + words[index - 16]) and U32
    }
}

/** 首 7 字节盐折进初始状态：words[0..13]=0、words[14]/[15]=盐字节，从第 14 轮起跑（前 14 轮效果预烘焙） */
private fun sha256InitState(salt7: ByteArray): LongArray {
    val words = mutableListOf<Long>()
    repeat(14) { words += 0L }
    words += ((salt7[0].toLong() and 0xFF) shl 16) or
        ((salt7[1].toLong() and 0xFF) shl 8) or
        (salt7[2].toLong() and 0xFF)
    words += ((salt7[3].toLong() and 0xFF) shl 24) or
        ((salt7[4].toLong() and 0xFF) shl 16) or
        ((salt7[5].toLong() and 0xFF) shl 8) or
        (salt7[6].toLong() and 0xFF)
    for (index in 16 until 30) {
        var value = SHA256_SCHEDULE[index - 16]
        if (index - 2 >= 14) {
            value += if (index == 16) (sigma1(words[index - 2]) xor SIGMA1_ADJUSTMENT) else sigma1(words[index - 2])
        }
        if (index - 7 >= 14) value += words[index - 7]
        if (index - 15 >= 14) {
            value += if (index == 29) (sigma0(words[index - 15]) xor SIGMA0_ADJUSTMENT) else sigma0(words[index - 15])
        }
        words += value and U32
    }
    words += (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
        SHA256_ADJUSTMENT - ROUND_CONSTANTS[14]) and U32
    expandSchedule(words, 31)
    val working = sha256Rounds(SHA256_STATE, words, 14, useBlockConstants = true)
    return LongArray(8) { (SHA256_INITIAL[it] + working[it]) and U32 }
}

/** 调整版 SHA-256：首 7 字节折进初始状态，其余按 length=57+len 的伪长度补位分块 */
private fun sha256Digest(data: ByteArray): ByteArray {
    require(data.size >= 7) { "token salt must be >= 7 bytes" }
    val length = 57 + data.size
    val padCount = Math.floorMod(55 - length, 64)
    val padded = ByteArray(data.size - 7 + 1 + padCount + 8)
    data.copyInto(padded, 0, 7, data.size)
    padded[data.size - 7] = 0x80.toByte()
    val bits = length.toLong() * 8
    for (i in 0 until 8) {
        padded[padded.size - 8 + i] = ((bits ushr ((7 - i) * 8)) and 0xFF).toByte()
    }
    var state = sha256InitState(data.copyOfRange(0, 7))
    var offset = 0
    while (offset < padded.size) {
        val words = mutableListOf<Long>()
        for (i in 0 until 16) {
            val j = offset + i * 4
            words += ((padded[j].toLong() and 0xFF) shl 24) or
                ((padded[j + 1].toLong() and 0xFF) shl 16) or
                ((padded[j + 2].toLong() and 0xFF) shl 8) or
                (padded[j + 3].toLong() and 0xFF)
        }
        expandSchedule(words, 16)
        val working = sha256Rounds(state, words, 0, useBlockConstants = false)
        for (i in 0 until 8) state[i] = (state[i] + working[i]) and U32
        offset += 64
    }
    val out = ByteArray(32)
    for (i in 0 until 8) {
        for (b in 0 until 4) {
            out[i * 4 + b] = ((state[i] ushr ((3 - b) * 8)) and 0xFF).toByte()
        }
    }
    return out
}

/** 数据按小端 32 位词切（补零到偶数个词） */
private fun wordsLe4Even(data: ByteArray): List<Long> {
    val pad = (4 - (data.size % 4)) % 4
    val total = data.size + pad
    val words = mutableListOf<Long>()
    var i = 0
    while (i < total) {
        val b = { k: Int -> (if (k < data.size) data[k].toLong() and 0xFF else 0L) }
        words += b(i) or (b(i + 1) shl 8) or (b(i + 2) shl 16) or (b(i + 3) shl 24)
        i += 4
    }
    if (words.size % 2 == 1) words += 0L
    return words
}

/** CS64 校验和：两个 32 位累加器交换后异或收尾（对调版 SHA-256 输出做密钥） */
private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
    var keyA = (digest[0].toLong() and 0xFF) or
        ((digest[1].toLong() and 0xFF) shl 8) or
        ((digest[2].toLong() and 0xFF) shl 16) or
        ((digest[3].toLong() and 0xFF) shl 24)
    var keyB = (digest[4].toLong() and 0xFF) or
        ((digest[5].toLong() and 0xFF) shl 8) or
        ((digest[6].toLong() and 0xFF) shl 16) or
        ((digest[7].toLong() and 0xFF) shl 24)
    keyA = keyA or 1L
    keyB = keyB or 1L
    val factorA = (keyA * CS64_FACTOR) and U32
    val factorB = (keyB * CS64_FACTOR) and U32

    val terms = mutableListOf<Long>()
    val offsets = mutableListOf<Long>()
    for (i in CS64_COEFFS.indices) {
        terms += (CS64_COEFFS[i] * (if (i % 2 == 0) factorA else factorB)) and U32
        offsets += CS64_OFFSETS[i]
    }
    for ((index, word) in wordsLe4Even(byteArrayOf(0) + data).withIndex()) {
        var term = word * (if (index % 2 == 0) keyA else keyB)
        var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
        if (index == 0) {
            term += CS64_ADJUSTMENT0 * factorA
            offset += CS64_ADJUSTMENT1
        }
        terms += term and U32
        offsets += offset and U32
    }

    var swapAcc = 0L
    var swapLast = 0L
    var state = 0L
    var acc = 0L
    var index = 0
    while (index < terms.size) {
        val x = terms[index]
        val y = terms[index + 1]
        var t = rol16((offsets[index] + x) and U32)
        var q = (t * 0x76F50F34L) and U32
        swapAcc += (rol16(q) * 0xC3EFEA97L + q) and U32
        t = rol16((offsets[index + 1] + y) and U32)
        q = (t * 0x1881CE3CL) and U32
        swapLast = (rol16(q) * 0x35BD1EC9L + q) and U32
        swapAcc = (swapAcc + swapLast) and U32

        state = (state * keyA + x) and U32
        state = (rol16(state) * 0xCF98B111L) and U32
        state = (rol16(state) * 0x87085B9FL) and U32
        state = (rol16(state) * 0x12CEB96DL) and U32
        state = (rol16(state) * 0x257E1D83L) and U32
        acc += state
        t = (state * keyB + y) and U32
        t = (rol16(t) * 0xA27416F5L) and U32
        t = (rol16(t) * 0xD38396FFL) and U32
        state = (rol16((rol16(t) * 0x7C932B89L) and U32) * 0xBFA49F69L) and U32
        acc = (acc + state) and U32
        index += 2
    }
    return (swapLast xor state) to (swapAcc xor acc)
}

/** 令牌哈希：CS64（小端 8 字节 hex）- 调整版 SHA-256（大写 hex） */
private fun tokenHash(data: ByteArray): Pair<String, String> {
    val digest = sha256Digest(data)
    val (w1, w2) = cs64Digest(data, digest)
    val cs64Bytes = ByteArray(8)
    for (b in 0 until 4) {
        cs64Bytes[b] = ((w1 ushr (b * 8)) and 0xFF).toByte()
        cs64Bytes[4 + b] = ((w2 ushr (b * 8)) and 0xFF).toByte()
    }
    val cs64 = cs64Bytes.joinToString("") { "%02X".format(it) }
    val sha256 = digest.joinToString("") { "%02X".format(it) }
    return cs64 to sha256
}
