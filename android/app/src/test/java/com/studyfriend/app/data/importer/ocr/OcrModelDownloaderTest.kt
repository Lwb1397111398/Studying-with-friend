package com.studyfriend.app.data.importer.ocr

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * OcrModelDownloader 纯 JVM 测试：手写 ServerSocket 顶 GitHub（同 UpdateCheckerTest 模式，
 * com.sun.net.httpserver 在 AGP 单测 JDK 模块限制下不可用）。覆盖 SUMS 解析/https 护栏/
 * 资产解析/下载编排（SHA-256 校验、坏文件删除、已存在跳过幂等）。
 * 下载全流程用 enforceHttps=false（本地伪服务器仅 http），https 护栏单独直测。
 */
class OcrModelDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fake: FakeGitHub

    @Before
    fun startServer() {
        fake = FakeGitHub().also { it.start() }
    }

    @After
    fun stopServer() {
        fake.stop()
    }

    // ---- 测试数据：三件套内容与真值 SHA-256 ----

    private val detBytes = ByteArray(1000) { (it % 7).toByte() }
    private val recBytes = ByteArray(2000) { (it % 11).toByte() }
    private val dictText = "字\n词\n"

    private fun sha(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun releaseBody(): String {
        val base = "http://127.0.0.1:${fake.port}"
        val files = listOf(
            "ppocrv5-mobile-det.onnx" to detBytes,
            "ppocrv5-mobile-rec.onnx" to recBytes,
            "ppocrv5_dict.txt" to dictText.toByteArray(),
        )
        val assets = files.joinToString(",") { (name, bytes) ->
            """{"name":"$name","browser_download_url":"$base/download/$name","size":${bytes.size}}"""
        }
        val sums = """{"name":"SHA256SUMS","browser_download_url":"$base/download/SHA256SUMS","size":${sumsReal().length}}"""
        return """{"tag_name":"ocr-models-v1","assets":[$assets,$sums]}"""
    }

    private fun sumsReal(): String =
        listOf(
            "ppocrv5-mobile-det.onnx" to sha(detBytes),
            "ppocrv5-mobile-rec.onnx" to sha(recBytes),
            "ppocrv5_dict.txt" to sha(dictText.toByteArray()),
        ).joinToString("\n") { (n, h) -> "$h  $n" } + "\n"

    // ---- parseSha256Sums ----

    @Test
    fun `parseSha256Sums 标准两空格行解析`() {
        val map = OcrModelDownloader.parseSha256Sums(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789  a.onnx\n" +
                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210  b.txt\n",
        )
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", map["a.onnx"])
        assertEquals("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", map["b.txt"])
    }

    @Test
    fun `parseSha256Sums 大写hex转小写且畸形行跳过`() {
        val map = OcrModelDownloader.parseSha256Sums(
            "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789 a.onnx\n" +
                "这不是哈希行\n" +
                "short a.onnx\n",
        )
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", map["a.onnx"])
        assertEquals(1, map.size)
    }

    @Test
    fun `parseSha256Sums 容错二进制模式星号前缀`() {
        val map = OcrModelDownloader.parseSha256Sums(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789 *a.onnx\n",
        )
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", map["a.onnx"])
        assertNull(map["*a.onnx"])
    }

    // ---- checkUrl ----

    @Test
    fun `checkUrl 放行 https 拒绝其他协议`() {
        OcrModelDownloader.checkUrl("https://objects.githubusercontent.com/x")
        try {
            OcrModelDownloader.checkUrl("http://evil.example/x")
            throw AssertionError("应当拒绝非 https")
        } catch (e: com.studyfriend.app.data.update.UpdateException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    // ---- parseAssets ----

    @Test
    fun `parseAssets 解析文件名到下载地址`() {
        val assets = OcrModelDownloader.parseAssets(releaseBody())
        assertEquals(4, assets.size)
        assertEquals(
            "http://127.0.0.1:${fake.port}/download/ppocrv5-mobile-det.onnx",
            assets["ppocrv5-mobile-det.onnx"]!!.url,
        )
        assertEquals(1000L, assets["ppocrv5-mobile-det.onnx"]!!.size)
    }

    // ---- downloadModels 全流程 ----

    private fun runDownload(modelsDir: File): MutableList<Int> {
        val pcts = mutableListOf<Int>()
        OcrModelDownloader.downloadModels(
            apiBase = "http://127.0.0.1:${fake.port}",
            repo = "x/y",
            token = "tok",
            modelsDir = modelsDir,
            enforceHttps = false,
            onProgress = { _, _, pct -> pcts.add(pct) },
        )
        return pcts
    }

    @Test
    fun `downloadModels 全流程落盘三件套且内容与发布一致`() {
        fake.tagBody = releaseBody()
        fake.downloads = mapOf(
            "ppocrv5-mobile-det.onnx" to detBytes,
            "ppocrv5-mobile-rec.onnx" to recBytes,
            "ppocrv5_dict.txt" to dictText.toByteArray(),
            "SHA256SUMS" to sumsReal().toByteArray(),
        )
        val dir = tmp.newFolder("ocr_models")
        val pcts = runDownload(dir)
        assertEquals(detBytes.size.toLong(), File(dir, "ppocrv5-mobile-det.onnx").length())
        assertTrue(File(dir, "ppocrv5-mobile-det.onnx").readBytes().contentEquals(detBytes))
        assertTrue(File(dir, "ppocrv5-mobile-rec.onnx").readBytes().contentEquals(recBytes))
        assertEquals(dictText, File(dir, "ppocrv5_dict.txt").readText())
        assertEquals(100, pcts.last())
        // .part 临时文件不残留
        assertNull(dir.listFiles()!!.firstOrNull { it.name.endsWith(".part") })
        assertEquals(4, fake.downloadRequests.get()) // 3 文件 + 1 SUMS
    }

    @Test
    fun `downloadModels 哈希不符删文件抛人话错误`() {
        // SUMS 内容写坏 det 的哈希（同长度 64hex，size 口径不变）
        val badSums = sumsReal().replace(sha(detBytes), "0".repeat(64))
        fake.tagBody = releaseBody()
        fake.downloads = mapOf(
            "ppocrv5-mobile-det.onnx" to detBytes,
            "ppocrv5-mobile-rec.onnx" to recBytes,
            "ppocrv5_dict.txt" to dictText.toByteArray(),
            "SHA256SUMS" to badSums.toByteArray(),
        )
        val dir = tmp.newFolder("ocr_models_bad")
        try {
            runDownload(dir)
            throw AssertionError("应当抛校验失败")
        } catch (e: com.studyfriend.app.data.update.UpdateException) {
            assertTrue(e.message!!.contains("校验失败"))
        }
        // 坏文件已删，不冒充完整模型
        assertNull(File(dir, "ppocrv5-mobile-det.onnx").takeIf { it.exists() })
    }

    @Test
    fun `downloadModels 已存在且哈希吻合时跳过下载幂等`() {
        fake.tagBody = releaseBody()
        fake.downloads = mapOf(
            "ppocrv5-mobile-det.onnx" to detBytes,
            "ppocrv5-mobile-rec.onnx" to recBytes,
            "ppocrv5_dict.txt" to dictText.toByteArray(),
            "SHA256SUMS" to sumsReal().toByteArray(),
        )
        val dir = tmp.newFolder("ocr_models_idem")
        runDownload(dir)
        val first = fake.downloadRequests.get()
        runDownload(dir)
        // 第二轮只拉 SUMS（downloadApk 对同长度完整文件复用不联网，但 SUMS 每轮重取）
        assertEquals(first + 1, fake.downloadRequests.get())
    }

    @Test
    fun `downloadModels 发布不存在时人话报错`() {
        fake.tagStatus = 404
        val dir = tmp.newFolder("ocr_models_404")
        try {
            runDownload(dir)
            throw AssertionError("应当抛发布不存在")
        } catch (e: com.studyfriend.app.data.update.UpdateException) {
            assertTrue(e.message!!.contains("ocr-models-v1"))
        }
    }
}

/** 单线程极简 HTTP 服务器：只回 /releases/tags/ocr-models-v1 与 /download/<name> */
private class FakeGitHub {
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    var port: Int = 0
        private set
    val downloadRequests = AtomicInteger(0)

    @Volatile var tagStatus: Int = 200
    @Volatile var tagBody: String = ""
    @Volatile var downloads: Map<String, ByteArray> = emptyMap()

    fun start() {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        thread = Thread {
            while (!ss.isClosed) {
                runCatching { ss.accept().use { handle(it) } }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        serverSocket?.close()
    }

    private fun handle(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val path = requestLine.split(" ").getOrNull(1).orEmpty()
        val out = socket.getOutputStream()
        when {
            path.endsWith("/releases/tags/${OcrModelDownloader.RELEASE_TAG}") -> {
                val bytes =
                    if (tagStatus == 200) tagBody.toByteArray()
                    else """{"message":"Not Found"}""".toByteArray()
                out.write(
                    ("HTTP/1.1 $tagStatus St\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.write(bytes)
            }
            "/download/" in path -> {
                downloadRequests.incrementAndGet()
                val name = path.substringAfterLast("/download/")
                val bytes = downloads[name] ?: ByteArray(0)
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.write(bytes)
            }
            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
        out.flush()
    }
}
