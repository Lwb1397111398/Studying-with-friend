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
 */
class VisionTranscriberTest {

    private fun pageOf(originChars: Int): PageOut = PageOut(
        pageNum = 1, tocLike = false, rawChars = originChars, puaCount = 0,
        lineCount = 10, shortLineCount = 0,
        paras = List(3) { Para("正".repeat(originChars / 3)) },
        firstLine = null, lastLine = null,
    )

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
                    .transcribePage(pageOf(100), "QUJD")
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
                VisionTranscriber(server.baseUrl, "k", "m").transcribePage(pageOf(50), "QQ==")
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
                VisionTranscriber(server.baseUrl, "k", "m").transcribePage(pageOf(300), "QQ==")
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
                VisionTranscriber(server.baseUrl, "k", "m").transcribePage(pageOf(100), "QQ==")
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
                VisionTranscriber(server.baseUrl, "k", "m").transcribePage(pageOf(100), "QQ==")
            }
            assertNull(t)
            assertEquals(2, calls)
        } finally {
            server.stop()
        }
    }
}
