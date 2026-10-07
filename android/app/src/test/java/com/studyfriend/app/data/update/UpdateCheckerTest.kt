package com.studyfriend.app.data.update

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
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * UpdateChecker 纯 JVM 测试：内置极简 HTTP 服务器顶替 GitHub，
 * 验证元数据解析、版本比较、鉴权头、下载进度与断点复用。
 * （com.sun.net.httpserver 在 AGP 单测编译的 JDK 模块限制下不可用，故手写 ServerSocket 版）
 */
class UpdateCheckerTest {

    private lateinit var fake: FakeGitHub
    private lateinit var tmp: TemporaryFolder

    @Before
    fun startServer() {
        tmp = TemporaryFolder()
        tmp.create()
        fake = FakeGitHub().also { it.start() }
    }

    @After
    fun stopServer() {
        fake.stop()
        tmp.delete()
    }

    private fun body(versionCode: Int = 20, versionName: String = "0.1.$versionCode"): String =
        """
        {
          "tag_name": "latest",
          "name": "学伴 App $versionName",
          "body": "<!-- studyfriend-update versionCode=$versionCode versionName=$versionName -->\n自动构建\n\n更新内容：\n- 应用自更新（M7）",
          "assets": [
            {"name": "StudyingWithFriend-latest.apk",
             "browser_download_url": "http://127.0.0.1:${fake.port}/download/app.apk",
             "size": 12345}
          ]
        }
        """.trimIndent()

    private fun fetch(currentCode: Int, token: String? = null): UpdateCheckResult {
        fake.latestBody = body()
        return UpdateChecker.fetchLatest(
            apiBase = "http://127.0.0.1:${fake.port}",
            repo = "x/y",
            token = token,
            currentVersionCode = currentCode,
        )
    }

    // ---- parseUpdateMeta ----

    @Test
    fun `parseUpdateMeta 从发布说明注释里读出版本号`() {
        val meta = UpdateChecker.parseUpdateMeta(
            "<!-- studyfriend-update versionCode=15 versionName=0.1.15 -->\n说明文字",
        )
        assertEquals(15, meta?.versionCode)
        assertEquals("0.1.15", meta?.versionName)
    }

    @Test
    fun `parseUpdateMeta 缺失或写坏时返回 null`() {
        assertNull(UpdateChecker.parseUpdateMeta("普通发布说明，没有元数据"))
        assertNull(UpdateChecker.parseUpdateMeta("<!-- studyfriend-update versionCode=abc versionName=x -->"))
    }

    @Test
    fun `stripMetaComment 只去掉注释行，说明文字保留`() {
        val stripped = UpdateChecker.stripMetaComment(
            "<!-- studyfriend-update versionCode=15 versionName=0.1.15 -->\n更新内容：\n- 修 bug",
        )
        assertEquals("更新内容：\n- 修 bug", stripped)
    }

    // ---- fetchLatest ----

    @Test
    fun `远端版本更新时返回 Available 且带说明和下载地址`() {
        val result = fetch(currentCode = 14, token = "tok-123") as UpdateCheckResult.Available
        assertEquals(20, result.release.versionCode)
        assertEquals("0.1.20", result.release.versionName)
        assertEquals("http://127.0.0.1:${fake.port}/download/app.apk", result.release.apkUrl)
        assertEquals(12345L, result.release.apkSize)
        assertEquals("自动构建\n\n更新内容：\n- 应用自更新（M7）", result.release.notes)
        // 私有仓库必须带 Bearer 令牌，且 GitHub 强制要求 User-Agent
        assertEquals("Bearer tok-123", fake.authHeader)
        assertEquals("studyfriend-app", fake.userAgent)
    }

    @Test
    fun `远端不比当前新时返回 UpToDate`() {
        val result = fetch(currentCode = 20) as UpdateCheckResult.UpToDate
        assertEquals("0.1.20", result.remoteVersionName)
    }

    @Test
    fun `404 时提示还没有发布过版本`() {
        fake.latestStatus = 404
        try {
            fetch(currentCode = 1, token = null)
            throw AssertionError("应当抛出 UpdateException")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("还没有发布过"))
        }
    }

    @Test
    fun `带令牌 404 时同样提示没有版本`() {
        fake.latestStatus = 404
        try {
            fetch(currentCode = 1, token = "tok")
            throw AssertionError("应当抛出 UpdateException")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("还没有发布过"))
        }
    }

    @Test
    fun `401 时提示稍后再试`() {
        fake.latestStatus = 401
        try {
            fetch(currentCode = 1, token = "bad")
            throw AssertionError("应当抛出 UpdateException")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    // ---- downloadApk ----

    @Test
    fun `downloadApk 写出完整文件且进度到 100`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        val progress = mutableListOf<Int>()
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
        ) { progress.add(it) }
        assertEquals(FakeGitHub.DOWNLOAD_BYTES.toLong(), dest.length())
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        // 临时文件不残留
        assertNull(File(dest.parentFile, dest.name + ".part").takeIf { it.exists() })
        assertEquals(1, fake.downloadRequests.get())
    }

    @Test
    fun `downloadApk 同版本完整文件已存在时直接复用不联网`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        dest.writeBytes(ByteArray(FakeGitHub.DOWNLOAD_BYTES))
        val progress = mutableListOf<Int>()
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
        ) { progress.add(it) }
        assertEquals(100, progress.last())
        assertEquals(0, fake.downloadRequests.get())
    }
}

/** 单线程极简 HTTP 服务器：只回 /releases/latest（可配状态码）与 /download/app.apk 两个路径 */
private class FakeGitHub {
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    var port: Int = 0
        private set
    val latestRequests = AtomicInteger(0)
    val downloadRequests = AtomicInteger(0)

    @Volatile var authHeader: String? = null
    @Volatile var userAgent: String? = null
    @Volatile var latestStatus: Int = 200
    @Volatile var latestBody: String = ""

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
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val path = requestLine.split(" ").getOrNull(1).orEmpty()
        val out = socket.getOutputStream()
        when {
            path.endsWith("/releases/tags/latest") -> {
                latestRequests.incrementAndGet()
                authHeader = headers["authorization"]
                userAgent = headers["user-agent"]
                val bytes =
                    if (latestStatus == 200) latestBody.toByteArray()
                    else """{"message":"Not Found"}""".toByteArray()
                out.write(
                    ("HTTP/1.1 $latestStatus St\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.write(bytes)
            }
            path.endsWith("/download/app.apk") -> {
                downloadRequests.incrementAndGet()
                val bytes = ByteArray(DOWNLOAD_BYTES) { (it % 251).toByte() }
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

    companion object {
        const val DOWNLOAD_BYTES = 200_000
    }
}
