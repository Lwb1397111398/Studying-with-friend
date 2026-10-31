package com.studyfriend.app.data.importer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

/**
 * P6b S2：OcrEngine 纯函数单测（CTC 解码 + det 后处理）。
 * 引擎接口 mock 识别路径（OcrImportRunner 集成）在 S4/S6 落地；真模型 E2E 走 S7 模拟器。
 */
class OcrEngineTest {

    // ---------- OcrCtc.decode ----------

    /** 标准解码：相邻重复去重、blank 跳过、class→dict[class−1]、置信度=保留字符均值 */
    @Test
    fun `ctc decode - dedupe blank and confidence mean`() {
        // dict 2 字符 → C=3（blank+2）；T=5 步：
        // 步0 中(0.9) 步1 中(0.85 重复去重) 步2 blank 步3 文(0.8) 步4 blank
        val data = FloatArray(1 * 5 * 3)
        data[0 * 3 + 1] = 0.9f
        data[1 * 3 + 1] = 0.85f
        data[2 * 3 + 0] = 0.9f
        data[3 * 3 + 2] = 0.8f
        data[4 * 3 + 0] = 0.9f
        val out = OcrCtc.decode(FloatBuffer.wrap(data), n = 1, t = 5, c = 3, dict = listOf("中", "文"))
        assertEquals(1, out.size)
        assertEquals("中文", out[0].first)
        assertEquals((0.9f + 0.8f) / 2, out[0].second, 1e-4f)
    }

    @Test
    fun `ctc decode - all blank yields empty text and zero confidence`() {
        val data = FloatArray(1 * 3 * 2) // C=2（blank+1字符），全 blank
        for (ti in 0 until 3) data[ti * 2 + 0] = 0.99f
        val out = OcrCtc.decode(FloatBuffer.wrap(data), n = 1, t = 3, c = 2, dict = listOf("字"))
        assertEquals("" to 0f, out[0])
    }

    @Test
    fun `ctc decode - class beyond dict skipped not crash`() {
        // class 2 超出 dict.size=1（词典版本不符）→ 跳过该字符
        val data = FloatArray(1 * 2 * 3)
        data[0 * 3 + 2] = 0.9f // class2 > dict.size
        data[1 * 3 + 1] = 0.8f // 字
        val out = OcrCtc.decode(FloatBuffer.wrap(data), n = 1, t = 2, c = 3, dict = listOf("字"))
        assertEquals("字", out[0].first)
        assertEquals(0.8f, out[0].second, 1e-4f)
    }

    @Test
    fun `ctc decode - batch n decodes independently`() {
        // N=2：行 0 全 blank，行 1 单字符——批间不串扰
        val data = FloatArray(2 * 1 * 2)
        data[1 * 2 + 1] = 0.7f
        val out = OcrCtc.decode(FloatBuffer.wrap(data), n = 2, t = 1, c = 2, dict = listOf("甲"))
        assertEquals("", out[0].first)
        assertEquals("甲", out[1].first)
    }

    // ---------- OcrDetPost.extractBoxes ----------

    private fun probMap(H: Int, W: Int, block: (x: Int, y: Int) -> Float): FloatBuffer =
        FloatBuffer.wrap(FloatArray(H * W) { i -> block(i % W, i / W) })

    @Test
    fun `det post - single block expanded box`() {
        // 64×64 概率图，块 x∈[10,40] y∈[10,30] 概率 0.9：w=31 h=21
        // → ex=7 ey=5 → 框 [3,5,47,35]
        val H = 64; val W = 64
        val boxes = OcrDetPost.extractBoxes(
            probMap(H, W) { x, y -> if (x in 10..40 && y in 10..30) 0.9f else 0f },
            H, W,
        )
        assertEquals(1, boxes.size)
        assertTrue(boxes[0].contentEquals(intArrayOf(3, 5, 47, 35)))
    }

    @Test
    fun `det post - tiny block below min area dropped`() {
        // 4×4=16 < MIN_BOX_AREA 32 → 不出框
        val H = 64; val W = 64
        val boxes = OcrDetPost.extractBoxes(
            probMap(H, W) { x, y -> if (x in 10..13 && y in 10..13) 0.9f else 0f },
            H, W,
        )
        assertEquals(0, boxes.size)
    }

    @Test
    fun `det post - low mean block dropped`() {
        // 块够大但框内概率均值 < 0.5 → 不出框
        val H = 64; val W = 64
        val boxes = OcrDetPost.extractBoxes(
            probMap(H, W) { x, y -> if (x in 10..40 && y in 10..30) 0.4f else 0f },
            H, W,
        )
        assertEquals(0, boxes.size)
    }

    @Test
    fun `det post - boxes sorted by y bucket then x`() {
        // 右上框（y=0 桶）在左下框（y=1 桶）之前；同桶内 x 小者在前
        val H = 128; val W = 128
        val boxes = OcrDetPost.extractBoxes(
            probMap(H, W) { x, y ->
                when {
                    x in 60..100 && y in 0..20 -> 0.9f   // 桶 0，x 大
                    x in 0..40 && y in 10..30 -> 0.9f    // 桶 0，x 小
                    x in 0..40 && y in 60..90 -> 0.9f    // 桶 2
                    else -> 0f
                }
            },
            H, W,
        )
        assertEquals(3, boxes.size)
        assertTrue("同桶 x 升序", boxes[0][0] < boxes[1][0])
        assertTrue("y 桶升序", boxes[1][1] / 24 <= boxes[2][1] / 24)
    }
}

/** 批E1：同行水平重叠框合并——大标题被 det 切成两个交叠框时并成一个再 rec，修叠字章题 */
class OcrDetPostMergeTest {

    @Test
    fun `同行交叠框取并集`() {
        // 「第二章」被切成两段：左框 x[10,60]、右框 x[40,120]，y 同行，x 交叠 20px ≥ 30%*50
        val boxes = listOf(
            intArrayOf(10, 100, 60, 130),
            intArrayOf(40, 102, 120, 128),
        )
        val out = OcrDetPost.mergeSameLineOverlaps(boxes)
        assertEquals(1, out.size)
        assertEquals(10, out[0][0])
        assertEquals(100, out[0][1])
        assertEquals(120, out[0][2])
        assertEquals(130, out[0][3])
    }

    @Test
    fun `不同行不合并`() {
        val boxes = listOf(
            intArrayOf(10, 100, 60, 130),
            intArrayOf(10, 140, 60, 170), // 下一行，y 不重叠
        )
        assertEquals(2, OcrDetPost.mergeSameLineOverlaps(boxes).size)
    }

    @Test
    fun `同行但x交叠不足三成不合并`() {
        val boxes = listOf(
            intArrayOf(10, 100, 60, 130),
            intArrayOf(56, 102, 120, 128), // 交叠 4px，min 宽 50 → 8% < 30%
        )
        assertEquals(2, OcrDetPost.mergeSameLineOverlaps(boxes).size)
    }

    @Test
    fun `三框连锁合并`() {
        val boxes = listOf(
            intArrayOf(10, 100, 60, 130),
            intArrayOf(40, 102, 120, 128),
            intArrayOf(90, 101, 180, 129), // 与合并后框 x 交叠 30px ≥ 30%*min(110,90)
        )
        val out = OcrDetPost.mergeSameLineOverlaps(boxes)
        assertEquals(1, out.size)
        assertEquals(180, out[0][2])
    }
}
