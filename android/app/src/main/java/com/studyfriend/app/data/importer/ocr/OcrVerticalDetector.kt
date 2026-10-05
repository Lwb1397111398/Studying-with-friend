package com.studyfriend.app.data.importer.ocr

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 竖排书检测（P6b S4，v1.2 P2-6 对齐 P6a direction_scan 方法论）：
 * 灰度化 → BIN 阈值二值化 → 行投影/列投影的波动强度（std/mean，裁空白边距带）
 * → ratio = V_v/V_h；ratio > SUSPECT_RATIO 落「可疑带」（P6a 标定 T=0.381 =
 * 横排页比值上界×1.5），ratio > SUSPECT_RATIO×1.25 判竖排。
 *
 * 两段式控成本（v1.3 P2-6）：
 * 1) 初筛：系统性抽样 10 页（每 46 页 1 页）跑投影扫描，命中（ratio>SUSPECT_RATIO）
 *    ≤1 页 → 判横排直接放行；
 * 2) 确认：命中 ≥2 页 → 全本扫描，SUSPECT+VERTICAL 页占比 ≥20% → AI 目视抽 3 页
 *    确认（由调用方接视觉模型）→ 确认竖排则整书拒绝。
 * 体系图页偶发 SUSPECT 由 OCR diagram 分类器消化，不触发整书拒绝
 * （P6a 实证 51 SUSPECT 页复核全为结构性误报、占比 11% 未达 20%，间隔充分）。
 *
 * 纯像素统计，JVM 侧可用 IntArray 模拟测试（[pageRatioFromPixels]）。
 */
object OcrVerticalDetector {

    /** 二值化阈值：灰度 < 160 视为墨迹（direction_scan 同值） */
    private const val BIN = 160

    /** P6a 标定 T：横排页比值上界×1.5（direction_scan_raw.json threshold_T=0.381） */
    const val SUSPECT_RATIO = 0.381f

    /** VERTICAL 判定 = SUSPECT×1.25（T±20% 带内标可疑人工复核的同口径） */
    const val VERTICAL_RATIO = SUSPECT_RATIO * 1.25f

    /** 初筛抽样页数与间隔（461 页书 → 10 页） */
    const val SAMPLE_COUNT = 10
    const val SAMPLE_STRIDE = 46

    /** 全本扫描的整书拒绝占比阈值 */
    const val REJECT_RATIO = 0.20f

    /** 投影比值：>VERTICAL_RATIO 竖排 / >SUSPECT_RATIO 可疑 / 其余横排 */
    fun verdict(ratio: Float): String = when {
        ratio > VERTICAL_RATIO -> "VERTICAL"
        ratio > SUSPECT_RATIO -> "SUSPECT"
        else -> "HORIZONTAL"
    }

    /** 初筛抽样页号（1-based）：每 [SAMPLE_STRIDE] 页取 1 页，至多 [SAMPLE_COUNT] 页 */
    fun samplePages(pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val stride = max(1, min(SAMPLE_STRIDE, pageCount / SAMPLE_COUNT))
        return (1..pageCount step stride).take(SAMPLE_COUNT).toList()
    }

    /** 初筛命中页数 ≥2 → 需要 [runFullScan] 全本确认；≤1 → 判横排放行 */
    fun needsFullScan(sampleRatios: List<Float>): Boolean =
        sampleRatios.count { it > SUSPECT_RATIO } >= 2

    /** 全本扫描：SUSPECT+VERTICAL 页占比 ≥ [REJECT_RATIO] → 走 AI 目视确认（可能整书拒绝） */
    fun fullScanSuspicious(ratios: List<Float>): Boolean =
        ratios.isNotEmpty() &&
            ratios.count { it > SUSPECT_RATIO }.toFloat() / ratios.size >= REJECT_RATIO

    /**
     * 单页投影比值（渲染位图入口）。降采样读取（步长自适应 ≤400 列）控制成本：
     * 投影统计对分辨率不敏感，P6a 手机实测渲染+扫描 <2s（S7 复核）。
     */
    fun pageRatio(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        if (w < 10 || h < 10) return 0f
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return pageRatioFromPixels(px, w, h)
    }

    /** 投影核心（纯数组，JVM 可测）：与 direction_scan.py ratios() 同口径 */
    fun pageRatioFromPixels(px: IntArray, w: Int, h: Int): Float {
        val rows = FloatArray(h)
        val cols = FloatArray(w)
        // 步长采样：x/y 方向各至多 400 个采样点，墨迹计数按采样格归一（比值不受影响）
        val stepX = max(1, w / 400)
        val stepY = max(1, h / 400)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = px[y * w + x]
                // int 平均灰度（RGB 等权，与 np mean(axis=2) 同口径）
                val gray = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                if (gray < BIN) {
                    rows[y] += 1f
                    cols[x] += 1f
                }
                x += stepX
            }
            y += stepY
        }
        val vH = fluctuation(rows)
        val vV = fluctuation(cols)
        return vV / max(vH, 1e-6f)
    }

    /** 行/列投影的波动强度 std/mean，裁掉墨迹 <2%×峰值的空白边距带（direction_scan 同口径） */
    private fun fluctuation(proj: FloatArray): Float {
        var peak = 0f
        for (v in proj) if (v > peak) peak = v
        var lo = 0
        var hi = proj.size - 1
        while (lo <= hi && proj[lo] <= peak * 0.02f) lo++
        while (hi >= lo && proj[hi] <= peak * 0.02f) hi--
        if (hi - lo + 1 < 10) return 0f
        var sum = 0f
        var cnt = 0
        for (i in lo..hi) {
            sum += proj[i]
            cnt++
        }
        val mean = sum / cnt
        if (mean <= 0f) return 0f
        var sq = 0f
        for (i in lo..hi) {
            val d = proj[i] - mean
            sq += d * d
        }
        return sqrt(sq / cnt) / mean
    }
}
