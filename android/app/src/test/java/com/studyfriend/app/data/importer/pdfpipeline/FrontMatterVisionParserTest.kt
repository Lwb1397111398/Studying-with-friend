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
import java.time.Instant

/**
 * 前置探针（P8）：纯状态机 FrontMatterBlocks（同块合并/序言按标题分块/toc+body 跳过）、
 * FrontJson 解码守卫（顺序号恰为 1..n、kind 合法）、probe 缓存/marker/全有全无/all-body
 * 守卫。Robolectric（android.util.Log）；网络用 chatFn 注入假实现，零真实调用。
 */
@RunWith(RobolectricTestRunner::class)
class FrontMatterVisionParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- FrontMatterBlocks 纯状态机 ----------

    @Test
    fun blocks_mergeConsecutiveSameKind() {
        val vs = listOf(
            FrontPageVerdict(1, FrontKinds.COVER, null),
            FrontPageVerdict(2, FrontKinds.COPYRIGHT, null),
            FrontPageVerdict(3, FrontKinds.BIO, "关于作者"),
            FrontPageVerdict(4, FrontKinds.BIO, null), // 无标题续页并前块（bio 不按标题分）
            FrontPageVerdict(5, FrontKinds.PREFACE, null), // 无标题序言 → 默认「序言」
            FrontPageVerdict(6, FrontKinds.PREFACE, null),
        )
        val blocks = FrontMatterBlocks.build(vs)
        assertEquals(
            listOf("封面" to (1..1), "版权页" to (2..2), "关于作者" to (3..4), "序言" to (5..6)),
            blocks.map { it.title to (it.startPage..it.endPage) },
        )
    }

    @Test
    fun blocks_prefaceDifferentTitles_splitIntoEditions() {
        // 老板核心诉求：初版序/第二版序各自成块，不糊在一起
        val vs = listOf(
            FrontPageVerdict(3, FrontKinds.PREFACE, "初版序"),
            FrontPageVerdict(4, FrontKinds.PREFACE, "初版序"),
            FrontPageVerdict(5, FrontKinds.PREFACE, "第二版序言"),
            FrontPageVerdict(6, FrontKinds.PREFACE, null), // 无标题续页并「第二版序言」？默认名不同不并
        )
        val blocks = FrontMatterBlocks.build(vs)
        assertEquals(listOf("初版序", "第二版序言", "序言"), blocks.map { it.title })
        assertEquals(3..4, blocks[0].startPage..blocks[0].endPage)
        assertEquals(5..5, blocks[1].startPage..blocks[1].endPage)
        assertEquals(6..6, blocks[2].startPage..blocks[2].endPage)
    }

    @Test
    fun blocks_skipTocAndBodyPages() {
        val vs = listOf(
            FrontPageVerdict(1, FrontKinds.COVER, null),
            FrontPageVerdict(2, FrontKinds.TOC, null),  // 目录页：归目录链路，不产前置块
            FrontPageVerdict(3, FrontKinds.PREFACE, "总序"), // 目录在前的书：序言块仍要收
            FrontPageVerdict(4, FrontKinds.BODY, null),
        )
        val blocks = FrontMatterBlocks.build(vs)
        assertEquals(listOf("封面" to 1, "总序" to 3), blocks.map { it.title to it.startPage })
    }

    @Test
    fun blocks_gapBreaksMerge() {
        // 页号不连续（中间隔页）不合块——防把不相邻页误并
        val vs = listOf(
            FrontPageVerdict(1, FrontKinds.OTHER, "推荐语"),
            FrontPageVerdict(3, FrontKinds.OTHER, "推荐语"),
        )
        val blocks = FrontMatterBlocks.build(vs)
        assertEquals(2, blocks.size)
    }

    // ---------- FrontJson 解码守卫 ----------

    @Test
    fun json_happyPath_and_guards() {
        val raw = """
            {"pages":[
              {"page":2,"kind":"copyright","title":null},
              {"page":1,"kind":"cover","title":null},
              {"page":3,"kind":"preface","title":"初版序"}
            ]}
        """.trimIndent()
        val vs = FrontJson.parse(raw, 3)
        assertEquals(listOf(1, 2, 3), vs!!.map { it.page })
        assertEquals("初版序", vs[2].title)
        // 条目数不齐 → null
        assertNull(FrontJson.parse("""{"pages":[{"page":1,"kind":"cover","title":null}]}""", 3))
        // 顺序号重复 → null
        assertNull(
            FrontJson.parse(
                """{"pages":[{"page":1,"kind":"cover","title":null},""" +
                    """{"page":1,"kind":"bio","title":null},{"page":3,"kind":"toc","title":null}]}""",
                3,
            ),
        )
        // 未知 kind → null
        assertNull(
            FrontJson.parse(
                """{"pages":[{"page":1,"kind":" appendix","title":null},{"page":2,"kind":"cover","title":null}]}""",
                2,
            ),
        )
    }

    @Test
    fun json_cacheRoundTrip() {
        val vs = listOf(
            FrontPageVerdict(3, FrontKinds.PREFACE, "初版序"),
            FrontPageVerdict(4, FrontKinds.BODY, null),
        )
        val back = FrontJson.parseCached(FrontJson.encodeCached(vs))
        assertEquals(vs, back)
    }

    // ---------- probe（缓存/marker/守卫/全有全无） ----------

    private class FakeClock {
        var now = 0L
        fun fn(): () -> Long = { now }
    }

    /** 页 1-5：第一批（1-4）cover/copyright/preface×2，第二批（5）preface 第二版 */
    private val chunk1 =
        """{"pages":[{"page":1,"kind":"cover","title":null},{"page":2,"kind":"copyright","title":null},""" +
            """{"page":3,"kind":"preface","title":"初版序"},{"page":4,"kind":"preface","title":"初版序"}]}"""
    private val chunk2 =
        """{"pages":[{"page":1,"kind":"preface","title":"第二版序言"}]}"""

    private fun cacheFile(dir: File, hash: String, pages: List<Int>) =
        File(dir, "fm_v${FrontMatterVisionParser.FORMAT_VERSION}_${hash}_s${pages.joinToString("-")}.json")

    private fun markerFile(dir: File, hash: String, pages: List<Int>) =
        File(dir, "fm_fail_v${FrontMatterVisionParser.FORMAT_VERSION}_${hash}_s${pages.joinToString("-")}.marker")

    private fun makeParser(dir: File, clock: FakeClock, chatFn: suspend (ChatRequest) -> String) =
        FrontMatterVisionParser(
            "https://api.test", "sk-test", "test-model", dir,
            chatFn = chatFn,
            renderFn = { "QQ==" },
            clock = clock.fn(),
        )

    @Test
    fun probe_twoChunks_physicalPageMapping_andCache() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        val p = makeParser(dir, clock) { _ ->
            calls.add(1)
            clock.now += 1_000
            if (calls.size == 1) chunk1 else chunk2
        }
        val r = p.probe(listOf(1, 2, 3, 4, 5), "h1")
        assertEquals(5, r!!.size)
        // 照片顺序号已映射回物理页号
        assertEquals(listOf(1, 2, 3, 4, 5), r.map { it.page })
        assertEquals("初版序", r[2].title)
        assertEquals("第二版序言", r[4].title)
        assertEquals(2, calls.size)
        // 缓存落盘且可读回
        assertTrue(cacheFile(dir, "h1", listOf(1, 2, 3, 4)).isFile)
        assertTrue(cacheFile(dir, "h1", listOf(5)).isFile)
        val again = p.probe(listOf(1, 2, 3, 4, 5), "h1")
        assertEquals("缓存命中零网络调用", 2, calls.size)
        assertEquals(r, again)
    }

    @Test
    fun probe_unparseableChunk_nullAndMarker() = runBlocking {
        val clock = FakeClock()
        val dir = tmp.newFolder()
        val p = makeParser(dir, clock) { _ -> "not json at all" }
        assertNull(p.probe(listOf(1, 2, 3, 4), "h1"))
        assertTrue("内容性失败写熔断 marker", markerFile(dir, "h1", listOf(1, 2, 3, 4)).exists())
        assertFalse(cacheFile(dir, "h1", listOf(1, 2, 3, 4)).exists())
    }

    @Test
    fun probe_freshMarker_zeroCalls() = runBlocking {
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        markerFile(dir, "h1", listOf(1, 2, 3, 4)).writeText(Instant.now().toString())
        val p = makeParser(dir, clock) { _ -> calls.add(1); chunk1 }
        assertNull(p.probe(listOf(1, 2, 3, 4), "h1"))
        assertEquals("熔断 TTL 内零网络调用", 0, calls.size)
    }

    @Test
    fun probe_allBodyPages_discard() = runBlocking {
        // 选页范围错误（全判 body）→ 整体弃用，不落缓存
        val clock = FakeClock()
        val dir = tmp.newFolder()
        val p = makeParser(dir, clock) { _ ->
            """{"pages":[{"page":1,"kind":"body","title":null},{"page":2,"kind":"body","title":null},
               {"page":3,"kind":"body","title":null},{"page":4,"kind":"body","title":null}]}"""
        }
        assertNull(p.probe(listOf(1, 2, 3, 4), "h1"))
        assertFalse(cacheFile(dir, "h1", listOf(1, 2, 3, 4)).exists())
    }

    @Test
    fun probe_secondChunkFails_allOrNothing() = runBlocking {
        // 全有全无：第二批失败 → 整次 null（半份结构不采纳）
        val clock = FakeClock()
        val calls = mutableListOf<Int>()
        val dir = tmp.newFolder()
        val p = makeParser(dir, clock) { _ ->
            calls.add(1)
            clock.now += 1_000
            if (calls.size == 1) chunk1 else "not json"
        }
        assertNull(p.probe(listOf(1, 2, 3, 4, 5), "h1"))
        assertEquals(2, calls.size)
        assertTrue(markerFile(dir, "h1", listOf(5)).exists())
    }
}
