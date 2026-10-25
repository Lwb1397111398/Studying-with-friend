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

    @Test
    fun `downloadApk 直连失败自动换镜像成功`() {
        tmp.newFolder("updates")
        fake.downloadStatus = 500
        val mirror = FakeGitHub().also { it.start() }
        try {
            val dest = File(tmp.root, "updates/update-20.apk")
            val progress = mutableListOf<Int>()
            // 镜像前缀拼原地址，与线上镜像同构（路径里带完整原始 URL）
            UpdateChecker.downloadApk(
                "http://127.0.0.1:${fake.port}/download/app.apk",
                token = null,
                dest = dest,
                expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
                mirrors = listOf("http://127.0.0.1:${mirror.port}/"),
            ) { progress.add(it) }
            assertEquals(FakeGitHub.DOWNLOAD_BYTES.toLong(), dest.length())
            assertEquals(100, progress.last())
            assertEquals(1, fake.downloadRequests.get())
            assertEquals(1, mirror.downloadRequests.get())
            assertNull(File(dest.parentFile, dest.name + ".part").takeIf { it.exists() })
        } finally {
            mirror.stop()
        }
    }

    @Test
    fun `downloadApk 直连与镜像全部失败时抛人话汇总`() {
        tmp.newFolder("updates")
        fake.downloadStatus = 500
        val mirror = FakeGitHub().also { it.start() }
        mirror.downloadStatus = 500
        try {
            val dest = File(tmp.root, "updates/update-20.apk")
            try {
                UpdateChecker.downloadApk(
                    "http://127.0.0.1:${fake.port}/download/app.apk",
                    token = null,
                    dest = dest,
                    expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
                    mirrors = listOf("http://127.0.0.1:${mirror.port}/"),
                ) { }
                throw AssertionError("应当抛出 UpdateException")
            } catch (e: UpdateException) {
                assertTrue(e.message!!.contains("直连"))
                assertTrue(e.message!!.contains("镜像"))
            }
            assertEquals(1, fake.downloadRequests.get())
            assertEquals(1, mirror.downloadRequests.get())
        } finally {
            mirror.stop()
        }
    }

    @Test
    fun `downloadApk 非 github 地址默认不拼镜像报单源原始错误`() {
        tmp.newFolder("updates")
        fake.downloadStatus = 500
        val dest = File(tmp.root, "updates/update-20.apk")
        try {
            // 不传 mirrors：本地 http 地址不该触发镜像拼接（E2E 假服务器同此形态）
            UpdateChecker.downloadApk(
                "http://127.0.0.1:${fake.port}/download/app.apk",
                token = null,
                dest = dest,
                expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
            ) { }
            throw AssertionError("应当抛出 UpdateException")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("HTTP 500"))
        }
        assertEquals(1, fake.downloadRequests.get())
    }

    @Test
    fun `downloadApk 半截包带 Range 续传只补剩余字节`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        val part = File(tmp.root, "updates/update-20.apk.part")
        val half = 100_000
        // .part 前半段字节与假服务器载荷同构，模拟上次下载中断
        part.writeBytes(ByteArray(half) { (it % 251).toByte() })
        val progress = mutableListOf<Int>()
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
        ) { progress.add(it) }
        assertEquals(FakeGitHub.DOWNLOAD_BYTES.toLong(), dest.length())
        assertEquals(
            ByteArray(FakeGitHub.DOWNLOAD_BYTES) { (it % 251).toByte() }.toList(),
            dest.readBytes().toList(),
        )
        assertNull(part.takeIf { it.exists() })
        assertEquals(1, fake.downloadRequests.get())
        // 续传起点约 50%，首个进度回调不应从 0 开始
        assertTrue(progress.first() >= 50)
        assertEquals(100, progress.last())
    }

    @Test
    fun `downloadApk 服务器不支持 Range 时丢弃半截从头重下`() {
        tmp.newFolder("updates")
        fake.ignoreRange = true
        val dest = File(tmp.root, "updates/update-20.apk")
        val part = File(tmp.root, "updates/update-20.apk.part")
        part.writeBytes(ByteArray(100_000)) // 内容错误的半截包
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
        ) { }
        assertEquals(
            ByteArray(FakeGitHub.DOWNLOAD_BYTES) { (it % 251).toByte() }.toList(),
            dest.readBytes().toList(),
        )
        assertNull(part.takeIf { it.exists() })
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
    @Volatile var downloadStatus: Int = 200

    /** 置 true 时无视 Range 头恒回 200 全量（模拟不支持断点续传的服务器） */
    @Volatile var ignoreRange: Boolean = false

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
                if (downloadStatus == 200) {
                    val bytes = ByteArray(DOWNLOAD_BYTES) { (it % 251).toByte() }
                    val from = if (ignoreRange) 0L else
                        headers["range"]?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
                    if (from in 1 until bytes.size.toLong()) {
                        // 带 Range 的合法续传请求 → 206 只回剩余字节
                        val remain = bytes.copyOfRange(from.toInt(), bytes.size)
                        out.write(
                            ("HTTP/1.1 206 Partial\r\nContent-Type: application/octet-stream\r\n" +
                                "Content-Length: ${remain.size}\r\n" +
                                "Content-Range: bytes $from-${bytes.size - 1}/${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(),
                        )
                        out.write(remain)
                    } else {
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        out.write(bytes)
                    }
                } else {
                    out.write(
                        ("HTTP/1.1 $downloadStatus St\r\nContent-Type: application/json\r\n" +
                            "Content-Length: 2\r\nConnection: close\r\n\r\n").toByteArray(),
                    )
                    out.write("{}".toByteArray())
                }
            }
            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
        out.flush()
    }

    companion object {
        const val DOWNLOAD_BYTES = 200_000
    }
}
