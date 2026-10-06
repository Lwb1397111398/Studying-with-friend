package com.studyfriend.app.data.importer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** rec 动态宽预处理纯函数单测（P6c Phase 1，口径对齐 PC replica_ocr.py rec_pre_dyn/norm_pad_dyn） */
class OcrRecPreTest {

    // ---- cropRecWidth：高 48 等比缩放，ceil，最小 1（PC rec_pre_dyn:128 同式）----

    @Test
    fun `cropRecWidth - normal line keeps aspect`() {
        assertEquals(1200, OcrRecPre.cropRecWidth(1000, 40)) // 1000*48/40=1200
        assertEquals(640, OcrRecPre.cropRecWidth(640, 48))
    }

    @Test
    fun `cropRecWidth - very tall box floors to 1`() {
        assertEquals(1, OcrRecPre.cropRecWidth(1, 100)) // ceil(0.48)=1，max(1,·) 兜底
    }

    @Test
    fun `cropRecWidth - ceil on non divisible`() {
        assertEquals(100, OcrRecPre.cropRecWidth(100, 48))
        assertEquals(101, OcrRecPre.cropRecWidth(101, 48)) // ceil(100.99…)=101
    }

    // ---- batchRecWidth：int(48*max(320/48, 最大宽高比))，下限 320（PC replica:257-258 同式）----

    @Test
    fun `batchRecWidth - all short lines floor to 320`() {
        // 全部宽高比 <320/48≈6.67：2.08 / 4.17 / 6.25
        assertEquals(320, OcrRecPre.batchRecWidth(intArrayOf(100, 200, 300), intArrayOf(48, 48, 48)))
    }

    @Test
    fun `batchRecWidth - long line sets width by floor truncation`() {
        // 实测最大宽高比 ≈31：1488×48 → int(48*31)=1488（floor 截断口径）
        assertEquals(1488, OcrRecPre.batchRecWidth(intArrayOf(1488), intArrayOf(48)))
    }

    @Test
    fun `batchRecWidth - float rounding guard keeps 320`() {
        // 48*(320f/48f) 浮点截断可能得 319，护栏必须恰 320
        assertEquals(320, OcrRecPre.batchRecWidth(intArrayOf(320, 320), intArrayOf(48, 48)))
    }

    // ---- fillNormalized：归一化靠左填、右侧补零、三通道平面偏移（PC norm_pad_dyn:137-138 同构）----

    @Test
    fun `fillNormalized - left filled normalized right zero`() {
        val batchW = 8; val rw = 2
        val px = IntArray(48 * rw) { 0xFF808080.toInt() } // 灰底
        px[0] = 0xFFFFFFFF.toInt() // y=0,x=0 白 → (255/255-0.5)/0.5=1f
        px[1] = 0xFF000000.toInt() // y=0,x=1 黑 → -1f
        val data = FloatArray(1 * 3 * 48 * batchW)
        OcrRecPre.fillNormalized(px, rw, batchW, data, 0)
        val plane = 48 * batchW
        assertEquals(1f, data[0 * plane + 0 * batchW + 0], 1e-6f) // R 通道白
        assertEquals(-1f, data[0 * plane + 0 * batchW + 1], 1e-6f) // R 通道黑
        assertEquals(0f, data[0 * plane + 5], 1e-6f) // x≥rw 右侧补零
        val g = ((0x80 / 255f) - 0.5f) / 0.5f // 灰 ≈0.0039，三通道同值
        assertEquals(g, data[0 * plane + 1 * batchW + 0], 1e-4f)
        assertEquals(g, data[1 * plane + 1 * batchW + 0], 1e-4f)
        assertEquals(g, data[2 * plane + 1 * batchW + 0], 1e-4f)
    }

    @Test
    fun `fillNormalized - batch offset writes row bi only`() {
        val batchW = 4; val rw = 1
        val px = IntArray(48) { 0xFFFFFFFF.toInt() }
        val data = FloatArray(2 * 3 * 48 * batchW)
        OcrRecPre.fillNormalized(px, rw, batchW, data, 1) // 写第 2 行
        assertEquals(0f, data[0], 1e-6f) // 第 1 行全零
        assertEquals(1f, data[3 * 48 * batchW], 1e-6f) // 第 2 行 R 通道 y0x0
    }

    @Test
    fun `fillNormalized - rejects rw beyond batchW`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrRecPre.fillNormalized(IntArray(48 * 5), 5, 4, FloatArray(3 * 48 * 4), 0)
        }
    }
}
