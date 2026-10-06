package com.studyfriend.app.data.importer.pdfpipeline

import com.studyfriend.app.data.ai.MiniHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉转写（OPT-E）：MiniHttpServer 模拟龙猫 OpenAI 兼容端点。
 * 覆盖：请求体带 image_url 段、JSON 解析、剥围栏、长度守卫、失败重试后回退 null。
 * OPT-F：transcribePage 直接收 originChars（长度守卫口径由调用方传入）。
 */
class VisionTranscriberTest {

    @Test
    fun transcribe_parsesBodyAndFootnotes_andSendsImagePart() {
        val bodies = mutableListOf<String>()
        val server = MiniHttpServer { _, _, body, resp ->
            bodies.add(body)
            resp.sse(listOf("""{"body":["第一段","第二段"],"footnotes":["注1","注2"]}"""))
        }
        try {
            val t = runBlocking {
                VisionTranscriber(server.baseUrl, "sk-test", "LongCat-2.5-Preview")
                    .transcribePage(100, "QUJD")
            }
            assertEquals(listOf("第一段", "第二段"), t!!.body)
            assertEquals(listOf("注1", "注2"), t.footnotes)
            // OpenAI 兼容视觉格式：content 是 parts 数组，image_url.url 是 data URI
            val body = bodies.single()
            assertTrue("应含 image_url 段", body.contains("\"image_url\""))
            assertTrue("图片应为 data URI", body.contains("\"data:image/png;base64,QUJD\""))
            assertTrue("提示词应钉死只输出 JSON", body.contains("只输出 JSON"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun transcribe_stripsCodeFence() {
        val server = MiniHttpServer { _, _, _, resp ->
            resp.sse(listOf("```json\n{\"body\":[\"围栏内的段\"],\"footnotes\":[]}\n```"))
        }
        try {
            val t = runBlocking {
                VisionTranscriber(server.baseUrl, "k", "m").transcribePage(50, "QQ==")
            }
            assertEquals(listOf("围栏内的段"), t!!.body)
            assertEquals(0, t.footnotes.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun lengthGuard_discardsShortTranscription_afterRetry() {
        var calls = 0
        val server = MiniHttpServer { _, _, _, resp ->
            calls++
            resp.sse(listOf("""{"body":["短"],"footnotes":[]}"""))
        }
        try {
            val t = runBlocking {
                VisionTranscriber(server.baseUrl, "k", "m").transcribePage(300, "QQ==")
            }
            assertNull("转写字符 < 原页一半且原页>200 → 弃用", t)
            assertEquals("应重试 1 次后放弃", 2, calls)
        } finally {
            server.stop()
        }
    }

    @Test
    fun lengthGuard_notApplied_whenOriginShort() {
        // 原页 ≤200 字不做长度守卫（短页本身字少，转写略短属正常）
        val server = MiniHttpServer { _, _, _, resp ->
            resp.sse(listOf("""{"body":["比原文短"],"footnotes":[]}"""))
        }
        try {
            val t = runBlocking {
                VisionTranscriber(server.baseUrl, "k", "m").transcribePage(100, "QQ==")
            }
            assertEquals(listOf("比原文短"), t!!.body)
        } finally {
            server.stop()
        }
    }

    @Test
    fun failure_retriesOnce_thenReturnsNull() {
        var calls = 0
        val server = MiniHttpServer { _, _, _, resp ->
            calls++
            resp.sse(listOf("这不是 JSON"))
        }
        try {
            val t = runBlocking {
                VisionTranscriber(server.baseUrl, "k", "m").transcribePage(100, "QQ==")
            }
            assertNull(t)
            assertEquals(2, calls)
        } finally {
            server.stop()
        }
    }

    // ================= P6c C3a：竖排判定解析（否定短语优先） =================

    @Test
    fun `vertical verdict - negation phrase wins over substring`() {
        // C3a 实锤回归：横排仿真书模型答「不是竖排」，旧正序匹配 contains("是竖排")
        // 在「不是竖排」里命中 → 误判 true → 3 页全中触发竖排拒绝
        assertEquals(false, parseVerticalVerdict("不是竖排"))
        assertEquals(
            false,
            parseVerticalVerdict("不是竖排。这本书文字从左到右排列"),
        )
    }

    @Test
    fun `vertical verdict - horizontal and english forms`() {
        assertEquals(false, parseVerticalVerdict("横排"))
        assertEquals(false, parseVerticalVerdict("这是横排书页"))
        assertEquals(false, parseVerticalVerdict("HORIZONTAL"))
    }

    @Test
    fun `vertical verdict - affirmative forms`() {
        assertEquals(true, parseVerticalVerdict("是竖排"))
        assertEquals(true, parseVerticalVerdict("这张书页是竖排（从上到下、从右到左阅读）"))
        assertEquals(true, parseVerticalVerdict("VERTICAL"))
    }

    @Test
    fun `vertical verdict - unparseable returns null for pass-through`() {
        // null=确认不了不拒绝（放行），由调用方 OcrImportRunner 处理
        assertNull(parseVerticalVerdict("无法判断"))
        assertNull(parseVerticalVerdict(""))
    }

    @Test
    fun `vertical verdict - mixed layout counts as horizontal`() {
        // 混合排版归横排：竖排拒绝链只针对整书竖排，误放行代价远小于误拒
        assertEquals(false, parseVerticalVerdict("正文横排，页边有竖排批注"))
    }
}
