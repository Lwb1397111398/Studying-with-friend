package com.studyfriend.app.data.importer.pdfpipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import java.io.File

/**
 * J8 性能取证（r12-QC1）：手动测试，默认跳过——需同时满足 ① 本机 `.e2e/mfzz.pdf`
 * （630 页真书）存在 ② 开关文件 `.e2e/p4_timing_run` 存在；任一不满足 [Assume]
 * 自动 skip，常规套件与 CI 零负担。手动跑法：`touch .e2e/p4_timing_run` 后
 * `./gradlew testDebugUnitTest --tests "*P4TimingManualTest*"`，跑完删除开关文件。
 *
 * 口径声明：Robolectric/桌面 JVM（pdfbox 扫描与过滤逻辑同真机，但 PdfRenderer 兜底
 * 与真机 pdfium 速度不同口径）——本测试不传 pageRenderer，decode 失败走快速失败路径，
 * 故 stage2 计时口径为「decode 失败路径」，真机 pdfium 渲染耗时 UNMEASURED。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P4TimingManualTest {

    private lateinit var context: Context
    private lateinit var staging: File

    @Before
    fun setUp() {
        assumeTrue("开关文件不存在，跳过（跑法见类注释）", File("C:/AIWorkSpace/Studying-with-friend/.e2e/p4_timing_run").exists())
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        staging = File(context.cacheDir, "figures_staging_timing")
    }

    @Test
    fun stage1_scan_630pages_withinBudget() {
        val pdf = File("C:/AIWorkSpace/Studying-with-friend/.e2e/mfzz.pdf")
        assumeTrue("本机才有 mfzz.pdf", pdf.exists())
        PDDocument.load(pdf.readBytes()).use { doc ->
            val pageParaY0s = (1..doc.numberOfPages).associateWith { emptyList<Float>() }
            val perPage = mutableListOf<Long>()
            val t0 = System.nanoTime()
            val result = PdfFigureExtractor(staging).extract(
                doc, pageParaY0s,
                onProgress = { pageNo, _ ->
                    if (perPage.size < pageNo) {
                        // 每 50 页回调一次，用累计值反推区间耗时
                        perPage.add(System.nanoTime())
                    }
                },
            )
            val stage1Ms = (System.nanoTime() - t0) / 1_000_000
            println("P4-TIMING stage1(630页扫描+过滤+锚定) = ${stage1Ms}ms")
            println("P4-TIMING progress checkpoints = $perPage")
            println("P4-TIMING stats: total=${result.stats.totalObjects} " +
                "kept=${result.stats.kept} errored=${result.stats.errored} " +
                "r1=${result.stats.droppedR1} r2=${result.stats.droppedR2}")
            // stage2 口径=decode 失败快速路径（无 pageRenderer）；4 张全 JBIG2 必失败
            assertTrue("stage1 预算 60s（实际 ${stage1Ms}ms）", stage1Ms < 60_000)
            assertTrue("幸存图 4 张", result.figures.isEmpty() && result.stats.errored == 4)
            val memMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_000_000
            println("P4-TIMING jvm-used-mem ≈ ${memMb}MB（JVM 口径，非真机 meminfo）")
        }
    }
}
