package com.studyfriend.app.data.importer.pdfpipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P6b S1：TextSourceRow 统一行模型单测。
 *
 * ① adapter 恒等性：PLine→TextSourceRow→PLine 全字段无损；
 * ② 单 span 行为锁定：单 span 经 TextSourceRow→SpanInfo→normalize 输出全字段
 *    逐一断言（OCR 路径「近恒等」假设以显式测试固化，含 −1 坐标分支）；
 * ③ 多 span 恒等性：直接 normalize 与经 TextSourceRow 中转的 normalize 结果
 *    全字段一致（封装对 SpanInfo 无损）；
 * ④ sourceVersion 双路径断言：数字路径恒 0、OCR 行透传 1（P6b 计划案 S6 前置，
 *    OcrLine 侧断言在 S2/S4 落地后补）。
 *
 * KDoc 锁定声明：本组单测同时锁定 RowNormalizer 单/多 span 分支行为，
 * normalize 逻辑变更时须同步更新。
 */
class TextSourceRowTest {

    // ---------- ① adapter 恒等性 ----------

    @Test
    fun `adapter pline round trip - identity all fields`() {
        val originals = listOf(
            // 正常行（二进制精确值，x1 = x0+w 无舍入）
            PLine("第一章 绪论", x0 = 50.0f, x1 = 540.5f, y0 = 100.25f, size = 12.0f),
            // 坐标未知行（-1 分支）
            PLine("无坐标正文行", x0 = -1f, x1 = -1f, y0 = -1f, size = -1f),
            // 字号未知行（size ≤ 0）
            PLine("奇怪字体行", x0 = 50f, x1 = 200f, y0 = 300f, size = 0f),
        )
        for (p in originals) {
            val row = TextSourceRow.fromPLine(p)
            assertEquals("text", p.text, row.text)
            assertEquals("x", p.x0, row.x, 0f)
            assertEquals("y", p.y0, row.y, 0f)
            assertEquals("w", p.x1 - p.x0, row.w, 0f)
            assertEquals("fontSize", p.size, row.fontSize, 0f)
            assertNull("fontFamily 恒 null（P6b F2）", row.fontFamily)
            assertNull("数字路径 confidence 恒 null", row.confidence)
            assertEquals("数字路径 sourceVersion 恒 0", TextSourceRow.SOURCE_DIGITAL, row.sourceVersion)

            val back = row.toPLine()
            assertEquals("round trip 全字段", p, back)
        }
    }

    // ---------- ② 单 span 行为锁定 ----------

    @Test
    fun `normalize single span via textSourceRow - all fields locked`() {
        val p = PLine("第一章 私法绪论", x0 = 50.0f, x1 = 540.5f, y0 = 100.25f, size = 12.0f)
        val out = RowNormalizer.normalize(listOf(TextSourceRow.fromPLine(p).toSpanInfo()))
        assertEquals(1, out.size)
        assertEquals("text 恒等（无空白不 trim 变化）", p.text, out[0].text)
        assertEquals("x0", p.x0, out[0].x0, 0.01f)
        assertEquals("x1", p.x1, out[0].x1, 0.01f)
        assertEquals("y0", p.y0, out[0].y0, 0.01f)
        assertEquals("size 单 span 加权均值=原值", p.size, out[0].size, 0.01f)
    }

    @Test
    fun `normalize single span negative coords - minus one branch locked`() {
        val p = PLine("正文行", x0 = -1f, x1 = -1f, y0 = -1f, size = -1f)
        val out = RowNormalizer.normalize(listOf(TextSourceRow.fromPLine(p).toSpanInfo()))
        assertEquals(1, out.size)
        assertEquals("正文行", out[0].text)
        assertEquals(-1f, out[0].x0, 0f)
        assertEquals(-1f, out[0].x1, 0f)
        assertEquals(-1f, out[0].y0, 0f)
        assertEquals(-1f, out[0].size, 0f)
    }

    // ---------- ③ 多 span 恒等性 ----------

    @Test
    fun `normalize multi span via textSourceRow - matches direct normalize digital`() {
        val spans = listOf(
            RowNormalizer.SpanInfo("担保物权制度", 50f, 155f, 100f, 10.5f),
            RowNormalizer.SpanInfo("研究", 155f, 176f, 100f, 10.0f),
            RowNormalizer.SpanInfo("的第一节", 176f, 240f, 100.2f, 10.5f),
            RowNormalizer.SpanInfo("概述", 240f, 272f, 100f, 10.25f),
            RowNormalizer.SpanInfo("。", 272f, 276f, 100f, 10.5f),
        )
        val direct = RowNormalizer.normalize(spans)
        val viaRow = RowNormalizer.normalize(
            spans.map { s ->
                TextSourceRow(s.text, s.x0, s.y0, s.x1 - s.x0, s.size, s.size, null, null, s.sourceVersion)
                    .toSpanInfo()
            },
        )
        assertEquals(direct, viaRow)
    }

    @Test
    fun `normalize multi span via textSourceRow - matches direct normalize ocr`() {
        // OCR 多 span（同一 OcrLine 不会多 span，此处防御性验证封装无损语义）
        val spans = listOf(
            RowNormalizer.SpanInfo("第二章", 50f, 110f, 200f, 14.0f, sourceVersion = TextSourceRow.PROD_OCR_V1),
            RowNormalizer.SpanInfo("抵押权", 110f, 180f, 200.1f, 14.0f, sourceVersion = TextSourceRow.PROD_OCR_V1),
        )
        val direct = RowNormalizer.normalize(spans)
        val viaRow = RowNormalizer.normalize(
            spans.map { s ->
                TextSourceRow(s.text, s.x0, s.y0, s.x1 - s.x0, s.size, s.size, null, 0.97f, s.sourceVersion)
                    .toSpanInfo()
            },
        )
        assertEquals(direct, viaRow)
    }

    // ---------- ④ sourceVersion 双路径断言 ----------

    @Test
    fun `sourceVersion - digital path constant zero through pipeline`() {
        assertEquals(0, PLine("t", 0f, 1f, 0f, 10f).sourceVersion)
        assertEquals(0, RowNormalizer.SpanInfo("t", 0f, 1f, 0f, 10f).sourceVersion)
        val out = RowNormalizer.normalize(listOf(RowNormalizer.SpanInfo("数字行", 50f, 150f, 100f, 10.5f)))
        assertEquals("数字路径 normalize 后恒 0", 0, out[0].sourceVersion)
    }

    @Test
    fun `sourceVersion - ocr row propagates one through pipeline`() {
        val row = TextSourceRow(
            "第一章 绪论", x = 50f, y = 100f, w = 490f, h = 16.6f,
            fontSize = 16.6f, fontFamily = null, confidence = 0.97f,
            sourceVersion = TextSourceRow.PROD_OCR_V1,
        )
        assertEquals(1, row.toSpanInfo().sourceVersion)
        val out = RowNormalizer.normalize(listOf(row.toSpanInfo()))
        assertEquals("OCR 行 normalize 后恒 1", TextSourceRow.PROD_OCR_V1, out[0].sourceVersion)
    }

    @Test
    fun `sourceVersion - mixed spans take max into pline`() {
        // 行内混合（防御性：OCR 行混入数字 span）→ 取 max，行整体按 OCR 口径
        val out = RowNormalizer.normalize(
            listOf(
                RowNormalizer.SpanInfo("前半", 50f, 100f, 100f, 10.5f, sourceVersion = 0),
                RowNormalizer.SpanInfo("后半", 100f, 150f, 100f, 10.5f, sourceVersion = TextSourceRow.PROD_OCR_V1),
            ),
        )
        assertEquals(TextSourceRow.PROD_OCR_V1, out[0].sourceVersion)
    }
}
