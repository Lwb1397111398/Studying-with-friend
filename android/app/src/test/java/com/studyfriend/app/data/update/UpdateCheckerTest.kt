package com.studyfriend.app.data.update

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
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

    @Test
    fun `parseUpdateMeta 解析第二行 sha256 注释且缺省为 null`() {
        val sha = "ab".repeat(32)
        val meta = UpdateChecker.parseUpdateMeta(
            "<!-- studyfriend-update versionCode=30 versionName=0.1.30 -->\n" +
                "<!-- studyfriend-update-sha256=$sha -->\n说明",
        )
        assertEquals(30, meta?.versionCode)
        assertEquals("0.1.30", meta?.versionName)
        assertEquals(sha, meta?.sha256)
        // 旧格式（无 sha 注释行）兼容：主元数据照常解析，sha 为 null
        val plain = UpdateChecker.parseUpdateMeta("<!-- studyfriend-update versionCode=30 versionName=0.1.30 -->")
        assertEquals(30, plain?.versionCode)
        assertNull(plain?.sha256)
    }

    @Test
    fun `stripMetaComment 同时去掉 sha256 注释行`() {
        val stripped = UpdateChecker.stripMetaComment(
            "<!-- studyfriend-update versionCode=15 versionName=0.1.15 -->\n" +
                "<!-- studyfriend-update-sha256=${"cd".repeat(32)} -->\n更新内容",
        )
        assertEquals("更新内容", stripped)
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
    fun `downloadApk 镜像优先成功时直连不再空耗`() {
        tmp.newFolder("updates")
        fake.downloadStatus = 500 // 直连若被误打头阵会先撞 500——命中镜像即成功，不该出现它的请求
        val mirror = FakeGitHub().also { it.start() }
        try {
            val dest = File(tmp.root, "updates/update-20.apk")
            val progress = mutableListOf<Int>()
            // 镜像前缀拼原地址，与线上镜像同构（路径里带完整原始 URL）；源池顺序镜像优先、直连殿后
            UpdateChecker.downloadApk(
                "http://127.0.0.1:${fake.port}/download/app.apk",
                token = null,
                dest = dest,
                expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
                mirrors = listOf("http://127.0.0.1:${mirror.port}/"),
            ) { progress.add(it) }
            assertEquals(FakeGitHub.DOWNLOAD_BYTES.toLong(), dest.length())
            assertEquals(100, progress.last())
            assertEquals(1, mirror.downloadRequests.get())
            assertEquals(0, fake.downloadRequests.get())
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

    // ---- downloadApk 内容校验（SHA-256 / 长度）：坏包清缓存自愈，不再装出「软件包似乎无效」----

    /** 与 FakeGitHub 载荷同构的「好内容」及其指纹 */
    private fun goodPayload(): ByteArray = ByteArray(FakeGitHub.DOWNLOAD_BYTES) { (it % 251).toByte() }

    private fun shaOf(bytes: ByteArray): String {
        val f = File(tmp.root, "sha-target.bin")
        f.writeBytes(bytes)
        return UpdateChecker.sha256File(f).also { f.delete() }
    }

    @Test
    fun `downloadApk 下载内容与 sha256 不符时报校验失败并清缓存`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        try {
            // 长度恰好对但内容坏（如镜像塞回错误字节）：下载完成后哈希比对拦住
            UpdateChecker.downloadApk(
                "http://127.0.0.1:${fake.port}/download/app.apk",
                token = null,
                dest = dest,
                expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
                sha256 = "e".repeat(64),
            ) { }
            throw AssertionError("应当抛出 UpdateException")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("校验失败"))
        }
        assertTrue("坏包不应留在正式名下", !dest.exists())
        assertNull(File(dest.parentFile, dest.name + ".part").takeIf { it.exists() })
    }

    @Test
    fun `downloadApk 缓存坏包长度对内容错时清掉重下`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        dest.writeBytes(ByteArray(FakeGitHub.DOWNLOAD_BYTES)) // 全 0：长度恰好对、内容坏（历史上会被永远复用）
        val progress = mutableListOf<Int>()
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
            sha256 = shaOf(goodPayload()),
        ) { progress.add(it) }
        assertEquals(goodPayload().toList(), dest.readBytes().toList())
        assertEquals(100, progress.last())
        assertEquals("坏缓存应触发重下而不是复用", 1, fake.downloadRequests.get())
    }

    @Test
    fun `downloadApk 缓存完整且 sha 相符时直接复用不联网`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        dest.writeBytes(goodPayload())
        val progress = mutableListOf<Int>()
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
            sha256 = shaOf(goodPayload()),
        ) { progress.add(it) }
        assertEquals(100, progress.last())
        assertEquals(0, fake.downloadRequests.get())
    }

    @Test
    fun `downloadApk 整包断点长度对内容错时清掉重下`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        val part = File(tmp.root, "updates/update-20.apk.part")
        part.writeBytes(ByteArray(FakeGitHub.DOWNLOAD_BYTES)) // 字节齐但内容坏：不能直接转正
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
            sha256 = shaOf(goodPayload()),
        ) { }
        assertEquals(goodPayload().toList(), dest.readBytes().toList())
        assertEquals(1, fake.downloadRequests.get())
        assertNull(part.takeIf { it.exists() })
    }

    @Test
    fun `downloadApk 下载长度与远端元数据不符时报校验失败不落地`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        try {
            // 服务器诚实地报了自己的长度并收完（如镜像塞回错误页），但与 Release 元数据 size 对不上
            UpdateChecker.downloadApk(
                "http://127.0.0.1:${fake.port}/download/app.apk",
                token = null,
                dest = dest,
                expectedSize = FakeGitHub.DOWNLOAD_BYTES + 1L,
            ) { }
            throw AssertionError("应当抛出 UpdateException")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("校验失败"))
        }
        assertTrue(!dest.exists())
        assertNull(File(dest.parentFile, dest.name + ".part").takeIf { it.exists() })
    }

    // ---- downloadApk 并行分块（大包提速路径：≥8MB 且有镜像时启用；单测用 chunkCount 强制开启）----

    @Test
    fun `downloadApk 大包分块并行拼出完整文件且进度单调到 100`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        val progress = Collections.synchronizedList(mutableListOf<Int>())
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
            chunkCount = 3,
        ) { progress.add(it) }
        assertEquals(FakeGitHub.DOWNLOAD_BYTES.toLong(), dest.length())
        // 三段按序拼接后与单流整包字节完全一致
        assertEquals(
            ByteArray(FakeGitHub.DOWNLOAD_BYTES) { (it % 251).toByte() }.toList(),
            dest.readBytes().toList(),
        )
        assertEquals(100, progress.last())
        assertEquals("进度应单调", progress, progress.sorted())
        // 段断点与整包 .part 都不残留
        for (i in 0 until 3) {
            assertNull(File(dest.parentFile, dest.name + ".part.c$i").takeIf { it.exists() })
        }
        assertNull(File(dest.parentFile, dest.name + ".part").takeIf { it.exists() })
        // 三段各发一次请求
        assertEquals(3, fake.downloadRequests.get())
    }

    @Test
    fun `downloadApk 分块并行某段失败自动换源成功`() {
        tmp.newFolder("updates")
        val mirror = FakeGitHub().also { it.start() }
        mirror.downloadStatus = 500 // 源池镜像优先：镜像打头阵失败，两段都落到直连补完
        try {
            val dest = File(tmp.root, "updates/update-20.apk")
            val progress = Collections.synchronizedList(mutableListOf<Int>())
            UpdateChecker.downloadApk(
                "http://127.0.0.1:${fake.port}/download/app.apk",
                token = null,
                dest = dest,
                expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
                mirrors = listOf("http://127.0.0.1:${mirror.port}/"),
                chunkCount = 2,
            ) { progress.add(it) }
            assertEquals(FakeGitHub.DOWNLOAD_BYTES.toLong(), dest.length())
            assertEquals(
                ByteArray(FakeGitHub.DOWNLOAD_BYTES) { (it % 251).toByte() }.toList(),
                dest.readBytes().toList(),
            )
            assertEquals(100, progress.last())
            // 源池轮转（offset=段号）：段 0 首发镜像(500)落直连，段 1 轮转偏移直接从直连起步
            assertEquals(1, mirror.downloadRequests.get())
            assertEquals(2, fake.downloadRequests.get())
        } finally {
            mirror.stop()
        }
    }

    @Test
    fun `downloadApk 分块并行段断点续传只补缺口`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        val part0 = File(tmp.root, "updates/update-20.apk.part.c0")
        // 第 0 段前 40% 是上轮拉到的断点（与假服务器载荷同构）
        part0.writeBytes(ByteArray(40_000) { (it % 251).toByte() })
        val progress = Collections.synchronizedList(mutableListOf<Int>())
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
            chunkCount = 2,
        ) { progress.add(it) }
        assertEquals(FakeGitHub.DOWNLOAD_BYTES.toLong(), dest.length())
        assertEquals(
            ByteArray(FakeGitHub.DOWNLOAD_BYTES) { (it % 251).toByte() }.toList(),
            dest.readBytes().toList(),
        )
        assertNull(part0.takeIf { it.exists() })
        // 段 0 续传 1 次 + 段 1 整段 1 次，不重下已收字节
        assertEquals(2, fake.downloadRequests.get())
        // 首个进度从已收的 20% 起步
        assertTrue(progress.first() >= 20)
        assertEquals(100, progress.last())
    }

    @Test
    fun `downloadApk 分块并行非零段遇不支持 Range 源时报人话且断点保留`() {
        tmp.newFolder("updates")
        fake.ignoreRange = true
        val dest = File(tmp.root, "updates/update-20.apk")
        try {
            UpdateChecker.downloadApk(
                "http://127.0.0.1:${fake.port}/download/app.apk",
                token = null,
                dest = dest,
                expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
                chunkCount = 2,
            ) { }
            throw AssertionError("应当抛出 UpdateException")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("段"))
            assertTrue(e.message!!.contains("断点"))
        }
        // 段 0（第 0 段允许 200 整包读到段边界）已齐保留；段 1 建连前即抛错、无断点文件；整包不落地
        assertEquals(100_000L, File(tmp.root, "updates/update-20.apk.part.c0").length())
        assertNull(File(tmp.root, "updates/update-20.apk.part.c1").takeIf { it.exists() })
        assertTrue(!dest.exists())
        // 段 0 一次成功；段 1 三轮源池轮转（唯一源）全撞 200 整包 → 3 次请求
        assertEquals(4, fake.downloadRequests.get())
    }

    @Test
    fun `downloadApk 分块并行长连接被掐断后段内续拉补完`() {
        tmp.newFolder("updates")
        fake.cutAfterBytes = 60_000 // 每次响应发 60KB 即断开（模拟镜像掐长连接，观测掐点 ~12MB 的等比例缩小）
        val dest = File(tmp.root, "updates/update-20.apk")
        val progress = Collections.synchronizedList(mutableListOf<Int>())
        // 段长 100KB：段 0 = 200整包截断60KB → 续拉40KB；段 1 = 206截断60KB → 续拉40KB，各 2 次请求补完
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
            chunkCount = 2,
        ) { progress.add(it) }
        assertEquals(FakeGitHub.DOWNLOAD_BYTES.toLong(), dest.length())
        assertEquals(
            ByteArray(FakeGitHub.DOWNLOAD_BYTES) { (it % 251).toByte() }.toList(),
            dest.readBytes().toList(),
        )
        assertEquals(100, progress.last())
        assertNull(File(dest.parentFile, dest.name + ".part.c0").takeIf { it.exists() })
        assertNull(File(dest.parentFile, dest.name + ".part.c1").takeIf { it.exists() })
        assertEquals(4, fake.downloadRequests.get())
    }

    @Test
    fun `downloadApk 段数多于线程数时按队列分发仍拼出完整包`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        val progress = Collections.synchronizedList(mutableListOf<Int>())
        // 6 段、单源 → 线程数 = min(6, 1×2) = 2，线程完成后从队列领下一段
        UpdateChecker.downloadApk(
            "http://127.0.0.1:${fake.port}/download/app.apk",
            token = null,
            dest = dest,
            expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
            chunkCount = 6,
        ) { progress.add(it) }
        assertEquals(FakeGitHub.DOWNLOAD_BYTES.toLong(), dest.length())
        assertEquals(
            ByteArray(FakeGitHub.DOWNLOAD_BYTES) { (it % 251).toByte() }.toList(),
            dest.readBytes().toList(),
        )
        assertEquals(100, progress.last())
        assertEquals(6, fake.downloadRequests.get())
        for (i in 0 until 6) {
            assertNull(File(dest.parentFile, dest.name + ".part.c$i").takeIf { it.exists() })
        }
    }

    @Test
    fun `downloadApk 分块下载 sha 不符时清掉整包与段断点`() {
        tmp.newFolder("updates")
        val dest = File(tmp.root, "updates/update-20.apk")
        try {
            // 拼接总长恰好对但内容坏（Range 错位字节/坏镜像段）：哈希比对拦在改名前
            UpdateChecker.downloadApk(
                "http://127.0.0.1:${fake.port}/download/app.apk",
                token = null,
                dest = dest,
                expectedSize = FakeGitHub.DOWNLOAD_BYTES.toLong(),
                chunkCount = 2,
                sha256 = "e".repeat(64),
            ) { }
            throw AssertionError("应当抛出 UpdateException")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("校验失败"))
        }
        assertTrue(!dest.exists())
        assertNull(File(dest.parentFile, dest.name + ".part").takeIf { it.exists() })
        for (i in 0 until 2) {
            assertNull(File(dest.parentFile, dest.name + ".part.c$i").takeIf { it.exists() })
        }
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

    /** ≥0 时每次响应最多写出这么多字节就断开连接（模拟镜像掐长连接），已写部分照常到达客户端 */
    @Volatile var cutAfterBytes: Long = -1

    private fun writeBody(out: java.io.OutputStream, data: ByteArray) {
        val cut = cutAfterBytes
        if (cut in 0L until data.size) out.write(data, 0, cut.toInt()) else out.write(data)
    }

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
                        writeBody(out, remain)
                    } else {
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        writeBody(out, bytes)
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
