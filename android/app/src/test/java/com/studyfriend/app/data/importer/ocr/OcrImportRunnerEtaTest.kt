package com.studyfriend.app.data.importer.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pass1 剩余时间估算单测（2026-10-11 修静态 1.24s/页在慢机严重偏短）：3 页实测门槛 + 均速外推 */
class OcrImportRunnerEtaTest {

    @Test
    fun `before 3 pages uses static baseline`() {
        // 2 页实测、耗时 10 秒（明显慢于基准）仍用 1.24s/页：前几页噪声不污染预估
        // 98 页 × 1240ms = 121520ms → roundToInt 取整后 122s
        val eta = OcrImportRunner.etaSeconds(total = 100, page = 2, doneThisSession = 2, elapsedMs = 10_000)
        assertEquals(122, eta)
    }

    @Test
    fun `after 3 pages uses measured average speed`() {
        // 5 页耗时 50 秒 → 10s/页；剩 95 页 → 950 秒
        val eta = OcrImportRunner.etaSeconds(total = 100, page = 5, doneThisSession = 5, elapsedMs = 50_000)
        assertEquals(950, eta)
    }

    @Test
    fun `resumed pages do not pollute the average`() {
        // 续跑场景：页计数已到 20，但均速分母用本会话实际识别的 4 页（40s/4=10s/页）；
        // 若误用 20 做分母，均速被稀释成 2s/页，ETA 严重偏短
        val eta = OcrImportRunner.etaSeconds(total = 100, page = 20, doneThisSession = 4, elapsedMs = 40_000)
        assertEquals(80 * 10, eta)
    }

    @Test
    fun `last page estimates zero`() {
        assertEquals(0, OcrImportRunner.etaSeconds(461, 461, 300, 300_000))
    }
}
