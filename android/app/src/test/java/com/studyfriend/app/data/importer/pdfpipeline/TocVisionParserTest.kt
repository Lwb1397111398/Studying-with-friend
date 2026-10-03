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
 * 目录探针（P3b-1/P3b-2）：区段缓存读写与版本、按区段熔断 marker、超时预算守卫、
 * 区段级全有全无语义。Robolectric（android.util.Log）；网络用 chatFn 注入假实现，
 * 零真实调用。每页返回 2 条：页级不守卫（applyGuards=false 由探针约定），守卫作用
 * 于拼接全集。段缓存文件名含段页列表签名（[TocVisionParser.segCacheFile] v1.2：
 * 段定义变化即 miss，防旧段缓存毒化新取样）。
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

    /** 默认 [listOf(1,2)] 与多数单段用例的 parse 页列表对应；文件名页列表口径与 segCacheFile/segMarkerFile 一致 */
    private fun cacheFile(dir: File, hash: String, pages: List<Int> = listOf(1, 2)) =
        File(dir, "toc_v${TocVisionParser.FORMAT_VERSION}_${hash}_s${pages.joinToString("-")}.json")

    private fun markerFile(dir: File, hash: String, pages: List<Int> = listOf(1, 2)) =
        File(dir, "toc_fail_v${TocVisionParser.FORMAT_VERSION}_${hash}_s${pages.joinToString("-")}.marker")

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
        val cached = TocJson.parse(cacheFile(dir, "h1").readText())
        assertEquals(4, cached!!.size)
    }

    @Test
    fun parse_corruptCache_missAndRebuild() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        cacheFile(dir, "h1").writeText("garbage{{{")
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        val r = p.parse(listOf(1, 2), "h1")
        assertEquals(4, r!!.size)
        assertEquals("损坏缓存视为 miss，应重建", 2, calls.size)
        assertTrue("重建后缓存应可读回", TocJson.parse(cacheFile(dir, "h1").readText()) != null)
    }

    @Test
    fun parse_oversizeCache_missAndRebuild() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        // 超过 MAX_CACHE_BYTES 的坏缓存：不读入内存，视为 miss 重建并覆盖
        val oversize = cacheFile(dir, "h1")
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
        File(dir, "toc_v1_h1_s0.json").writeText(pageA)
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
        cacheFile(dir, "h1").writeText(entries)
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
            markerFile(dir, "h1").exists(),
        )
    }

    @Test
    fun parse_freshMarker_zeroNetworkCalls() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        markerFile(dir, "h1").writeText(Instant.now().toString())
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        assertNull(p.parse(listOf(1, 2), "h1"))
        assertEquals("熔断 TTL 内零网络调用", 0, calls.size)
    }

    @Test
    fun parse_markerFromOldVersion_treatedAsExpired() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        File(dir, "toc_fail_v1_h1_s0.marker").writeText(Instant.now().toString())
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        assertEquals(4, p.parse(listOf(1, 2), "h1")!!.size)
        assertTrue("应正常重试", calls.size == 2)
    }

    @Test
    fun parse_budgetBoundaryEquality_passes() = runBlocking {
        // 第一页耗 420s：第二页前置检查 420k+60k=480k，与总预算等号放行（> 而非 ≥）
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val p = makeParser(tmp.newFolder(), clock, twoPageChat(clock, calls, chatDelayMs = 420_000))
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
            clock.advance(430_000) // 失败发生在 430s：重试需 430k+60k=490k > 480k
            throw IOException("timeout")
        }
        assertNull(p.parse(listOf(1, 2), "h1"))
        assertEquals("预算耗尽不重试", 1, calls.size)
        assertFalse("网络失败不写熔断 marker", markerFile(dir, "h1").exists())
    }

    // ---------- P3b-2 区段化：段间独立、按区段熔断、守卫作用于拼接全集 ----------

    /** 段 0 两页成功（4 条），段 1 首页 unparseable */
    private fun partialSuccessChat(clock: FakeClock, calls: MutableList<Int>) =
        { _: ChatRequest ->
            calls.add(1)
            clock.advance(1_000)
            when {
                calls.size == 1 -> pageA
                calls.size == 2 -> pageB
                else -> "not json at all"
            }
        }

    @Test
    fun parseSegments_partialSuccess_returnsMergedAndMarksOnlyFailedSegment() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        val p = makeParser(dir, clock, partialSuccessChat(clock, calls))
        // 模拟 mzzz：上册 [28,29] 正常，下册 [557,558] unparseable
        val r = p.parseSegments(listOf(listOf(28, 29), listOf(557, 558)), "h1")
        assertEquals("成功区段照常返回，一册失败不拖垮另一册", 4, r!!.size)
        assertEquals("只处理到失败页为止（段 0 两页 + 段 1 首页）", 3, calls.size)
        assertTrue("失败区段写 marker 熔断", markerFile(dir, "h1", listOf(557, 558)).exists())
        assertFalse("成功区段不写 marker", markerFile(dir, "h1", listOf(28, 29)).exists())
        assertTrue("守卫通过后成功区段写缓存", cacheFile(dir, "h1", listOf(28, 29)).exists())
        assertFalse("失败区段无缓存", cacheFile(dir, "h1", listOf(557, 558)).exists())
    }

    @Test
    fun parseSegments_segmentCacheHitAndFreshMarker_zeroCalls() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        val entries = """{"entries":[{"title":"第一章 甲","page":1,"level":1},{"title":"第一节 a","page":2,"level":2},{"title":"第二章 乙","page":10,"level":1},{"title":"第二节 b","page":11,"level":2}]}"""
        cacheFile(dir, "h1", listOf(28, 29)).writeText(entries)
        markerFile(dir, "h1", listOf(557, 558)).writeText(Instant.now().toString())
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        val r = p.parseSegments(listOf(listOf(28, 29), listOf(557, 558)), "h1")
        assertEquals(4, r!!.size)
        assertEquals("缓存命中区段 + 熔断区段均零网络调用", 0, calls.size)
    }

    @Test
    fun parseSegments_segmentPagesChange_oldCacheNotHit() = runBlocking {
        // v1.2 回归：段缓存 key 含页列表签名——取样策略从 [18] 变 [18,19] 后，
        // 旧段缓存不得命中（真书事故：旧 key 只含段序号，2 页取样缓存毒化 3 页新取样，
        // 尾页条目永远进不来）
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        cacheFile(dir, "h1", listOf(18)).writeText(pageA)
        val p = makeParser(dir, clock, twoPageChat(clock, calls))
        val r = p.parseSegments(listOf(listOf(18, 19)), "h1")
        assertEquals(4, r!!.size)
        assertEquals("段定义变化必须重新调用（旧缓存不毒化新探针）", 2, calls.size)
    }

    @Test
    fun parseSegments_guardFail_writesAllMarkersAndNoCache() = runBlocking {
        // 每页返回 2 条降序页码条目：拼接 4 条过条目数线，但页码逆序比 1/3 < 90% → 守卫失败
        val badPage =
            """{"entries":[{"title":"甲","page":100,"level":1},{"title":"乙","page":1,"level":2}]}"""
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        val p = makeParser(dir, clock) { _ ->
            calls.add(1)
            clock.advance(1_000)
            badPage
        }
        assertNull(p.parseSegments(listOf(listOf(28, 29), listOf(557, 558)), "h1"))
        assertTrue("守卫失败整书熔断：段 0 写 marker", markerFile(dir, "h1", listOf(28, 29)).exists())
        assertTrue("守卫失败整书熔断：段 1 写 marker", markerFile(dir, "h1", listOf(557, 558)).exists())
        assertFalse("坏目录不落缓存（缓存延后到守卫通过才写）", cacheFile(dir, "h1", listOf(28, 29)).exists())
        assertFalse(cacheFile(dir, "h1", listOf(557, 558)).exists())
    }

    @Test
    fun parseSegments_budgetOut_returnsProcessedSegmentsWithoutMarker() = runBlocking {
        // 段 0 单页成功（单页 4 条过守卫）；段 1 首页前置检查超预算 → 停止不调用
        val fullPage =
            """{"entries":[{"title":"第一章 甲","page":1,"level":1},{"title":"第一节 a","page":2,"level":2},{"title":"第二章 乙","page":10,"level":1},{"title":"第二节 b","page":11,"level":2}]}"""
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        val p = makeParser(dir, clock) { _ ->
            calls.add(1)
            clock.advance(430_000) // 段 1 前置检查 430k+60k=490k > 480k → BudgetOut
            fullPage
        }
        val r = p.parseSegments(listOf(listOf(28), listOf(557, 558)), "h1")
        assertEquals("已处理区段照常走守卫返回", 4, r!!.size)
        assertEquals("预算耗尽后后续页零调用", 1, calls.size)
        assertFalse("预算停止非失败，不写任何 marker", markerFile(dir, "h1", listOf(28)).exists())
        assertFalse(markerFile(dir, "h1", listOf(557, 558)).exists())
        assertTrue("成功区段照常写缓存", cacheFile(dir, "h1", listOf(28)).exists())
        assertFalse(cacheFile(dir, "h1", listOf(557, 558)).exists())
    }
}
