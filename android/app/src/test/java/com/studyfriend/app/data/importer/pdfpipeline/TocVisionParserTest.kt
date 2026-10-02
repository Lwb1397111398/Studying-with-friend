package com.studyfriend.app.data.importer.pdfpipeline

import com.studyfriend.app.data.ai.ChatRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * 目录探针（P3b-1）：缓存读写与版本、失败熔断 marker、超时预算守卫。
 * Robolectric（android.util.Log）；网络用 chatFn 注入假实现，零真实调用。
 * 每页返回 2 条：页级不守卫（applyGuards=false 由探针约定），守卫作用于拼接全集。
 */
@RunWith(RobolectricTestRunner::class)
class TocVisionParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeClock {
        var now = 0L
        fun advance(ms: Long) {
            now += ms
        }

        fun fn(): () -> Long = { now }
    }

    private val pageA =
        """{"entries":[{"title":"第一章 总则","page":1,"level":1},{"title":"第一节 甲","page":2,"level":2}]}"""
    private val pageB =
        """{"entries":[{"title":"第二章 分则","page":10,"level":1},{"title":"第二节 乙","page":11,"level":2}]}"""

    private fun makeParser(dir: File, clock: FakeClock, chatFn: suspend (ChatRequest) -> String) =
        TocVisionParser(
            "https://api.test", "sk-test", "test-model", dir,
            chatFn = chatFn,
            renderFn = { "QQ==" },
            clock = clock.fn(),
        )

    /** 两页各 2 条，拼接后 4 条、pages [1,2,10,11] 升序、level1=2，过守卫 */
    private fun twoPageChat(clock: FakeClock, calls: MutableList<Int>, chatDelayMs: Long = 1_000) =
        { _: ChatRequest ->
            calls.add(1)
            clock.advance(chatDelayMs)
            if (calls.size == 1) pageA else pageB
        }

    @Test
    fun parse_twoPages_andWritesCache() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        val r = p.parse(listOf(1, 2), "h1")
        assertEquals(4, r!!.size)
        assertEquals(2, calls.size)
        // 缓存已落盘且可读回（文件名引用 FORMAT_VERSION，升版测试自动跟随）
        val cached = TocJson.parse(File(dir, "toc_v${TocVisionParser.FORMAT_VERSION}_h1.json").readText())
        assertEquals(4, cached!!.size)
    }

    @Test
    fun parse_corruptCache_missAndRebuild() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        File(dir, "toc_v${TocVisionParser.FORMAT_VERSION}_h1.json").writeText("garbage{{{")
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        val r = p.parse(listOf(1, 2), "h1")
        assertEquals(4, r!!.size)
        assertEquals("损坏缓存视为 miss，应重建", 2, calls.size)
        assertTrue("重建后缓存应可读回", TocJson.parse(File(dir, "toc_v${TocVisionParser.FORMAT_VERSION}_h1.json").readText()) != null)
    }

    @Test
    fun parse_oversizeCache_missAndRebuild() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        // 超过 MAX_CACHE_BYTES 的坏缓存：不读入内存，视为 miss 重建并覆盖
        val oversize = File(dir, "toc_v${TocVisionParser.FORMAT_VERSION}_h1.json")
        oversize.writeText("x".repeat(1_100_000))
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        val r = p.parse(listOf(1, 2), "h1")
        assertEquals(4, r!!.size)
        assertEquals("超大缓存视为 miss，应重建", 2, calls.size)
        assertTrue("重建后缓存恢复正常大小", oversize.length() < 1_000_000)
    }

    @Test
    fun parse_cacheFromOldVersion_ignored() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        File(dir, "toc_v1_h1.json").writeText(pageA)
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        val r = p.parse(listOf(1, 2), "h1")
        assertEquals(4, r!!.size)
        assertEquals("旧版本文件名不读，应走网络重建", 2, calls.size)
    }

    @Test
    fun parse_cacheHit_zeroNetworkCalls() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        val entries = """{"entries":[{"title":"第一章 甲","page":1,"level":1},{"title":"第一节 a","page":2,"level":2},{"title":"第二章 乙","page":10,"level":1},{"title":"第二节 b","page":11,"level":2}]}"""
        File(dir, "toc_v${TocVisionParser.FORMAT_VERSION}_h1.json").writeText(entries)
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        val r = p.parse(listOf(1, 2), "h1")
        assertEquals(4, r!!.size)
        assertEquals("缓存命中应零网络调用", 0, calls.size)
    }

    @Test
    fun parse_unparseableJson_writesMarkerAndReturnsNull() = runBlocking {
        val clock = FakeClock()
        val dir = tmp.newFolder()
        val p = makeParser(dir, clock) { _ -> "not json at all" }
        assertNull(p.parse(listOf(1, 2), "h1"))
        assertTrue(
            "unparseable 应写熔断 marker（区别于网络失败不写）",
            File(dir, "toc_fail_v${TocVisionParser.FORMAT_VERSION}_h1.marker").exists(),
        )
    }

    @Test
    fun parse_freshMarker_zeroNetworkCalls() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        File(dir, "toc_fail_v${TocVisionParser.FORMAT_VERSION}_h1.marker").writeText(Instant.now().toString())
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        assertNull(p.parse(listOf(1, 2), "h1"))
        assertEquals("熔断 TTL 内零网络调用", 0, calls.size)
    }

    @Test
    fun parse_markerFromOldVersion_treatedAsExpired() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        File(dir, "toc_fail_v1_h1.marker").writeText(Instant.now().toString())
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        assertEquals(4, p.parse(listOf(1, 2), "h1")!!.size)
        assertTrue("应正常重试", calls.size == 2)
    }

    @Test
    fun parse_budgetBoundaryEquality_passes() = runBlocking {
        // 第一页耗 240s：第二页前置检查 240k+60k=300k，等号放行（> 而非 ≥）
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val p = makeParser(tmp.newFolder(), clock, twoPageChat(clock, calls, chatDelayMs = 240_000))
        val r = p.parse(listOf(1, 2), "h1")
        assertEquals("等号应放行第二页", 4, r!!.size)
        assertEquals(2, calls.size)
    }

    @Test
    fun parse_retrySkippedWhenBudgetExhausted() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        val p = makeParser(dir, clock) { _ ->
            calls.add(1)
            clock.advance(250_000) // 失败发生在 250s：重试需 250k+60k > 300k
            throw IOException("timeout")
        }
        assertNull(p.parse(listOf(1, 2), "h1"))
        assertEquals("预算耗尽不重试", 1, calls.size)
        assertFalse("网络失败不写熔断 marker", File(dir, "toc_fail_v${TocVisionParser.FORMAT_VERSION}_h1.marker").exists())
    }
}
