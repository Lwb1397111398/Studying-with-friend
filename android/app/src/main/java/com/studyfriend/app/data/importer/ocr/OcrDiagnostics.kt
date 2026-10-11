package com.studyfriend.app.data.importer.ocr

import android.util.Log
import com.studyfriend.app.data.importer.pdfpipeline.PLine
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** OCR 导入诊断包（自 ImportViewModel 抽出，P6b 系列）：质量备注 / 离线校验快照 /
 *  DEBUG 草稿落盘 / 书名目录键。纯逻辑为主（文件落盘吃 filesDir 参数），便于单测；
 *  页图 PNG 渲染依赖 PdfPageRenderer 留在调用方编排。 */
object OcrDiagnostics {

    /** 书名 → 快照/抽样目录名：非法字符压下划线，截 40 字，空名兜底 "book" */
    fun bookKey(title: String): String =
        title.replace(Regex("[^\\u4e00-\\u9fffA-Za-z0-9]"), "_").take(40).ifBlank { "book" }

    /**
     * OCR 质量备注（v1.4 P2-1 漂移两级告警 + v1.2 P1-1 字数偏差代理）；null=无可提示。
     * 置信度告警：兜底页（0f）剔除后页均置信度 median <0.85 硬告警（建议关 OCR）/
     * <0.90 预警；字数偏差：|页字数−中位|/中位 >60% 的页占比 >10% → 提示可能识别不完整。
     */
    fun qualityNote(pageMeanConfs: List<Float>, pageCharCounts: List<Int>): String? {
        val parts = mutableListOf<String>()
        val confs = pageMeanConfs.filter { it > 0f }
        if (confs.size >= 5) {
            val median = confs.sorted()[confs.size / 2]
            val pct = "%.2f".format(median)
            if (median < OcrImportRunner.PageGate.CONF_THRESHOLD) {
                parts.add("全书识别质量偏低（平均置信度 $pct），建议检查扫描件清晰度，" +
                    "或在设置中关闭「扫描书本地识别」")
            } else if (median < 0.90f) {
                parts.add("识别质量略低（平均置信度 $pct），建议导入后抽查几页阅读效果")
            }
        }
        val charCounts = pageCharCounts.filter { it > 0 }
        if (charCounts.size >= 5) {
            val med = charCounts.sorted()[charCounts.size / 2].toFloat()
            if (med > 0f) {
                val odd = charCounts.count { kotlin.math.abs(it - med) / med > 0.6f }
                if (odd > charCounts.size * 0.10) {
                    parts.add("$odd 页文字量明显偏离全书水平，可能识别不完整")
                }
            }
        }
        return parts.joinToString("；").ifEmpty { null }
    }

    /**
     * 离线校验快照（v1.3 P1-2）：固定种子（bookTitle.hashCode）从有内容的页里抽 1-2 页，
     * 落盘 filesDir/ocr_verify/<bookKey>/snapshot.json（页码+文本+行级置信度），首月每两周
     * 人工抽查对照原书，监控识别漂移；滚动保留最近 2 本。任何失败只记 log 不影响导入。
     */
    fun writeVerifySnapshot(filesDir: File, bookTitle: String, outcome: OcrImportRunner.Outcome) {
        try {
            val dir = File(filesDir, "ocr_verify/${bookKey(bookTitle)}")
            dir.mkdirs()
            val candidates = outcome.pagesLines.withIndex().filter { (_, ls) -> ls.isNotEmpty() }
            if (candidates.isEmpty()) return
            val rng = java.util.Random(bookTitle.hashCode().toLong())
            val count = if (outcome.pagesLines.size >= 100) 2 else 1
            val picked = List(count) { candidates[rng.nextInt(candidates.size)] }.distinctBy { it.index }
            val json = buildJsonObject {
                put("bookTitle", bookTitle)
                put("savedAt", System.currentTimeMillis())
                put("pageCount", outcome.pagesLines.size)
                put("pages", buildJsonArray {
                    picked.forEach { (idx, lines) ->
                        add(buildJsonObject {
                            put("page", idx + 1)
                            put("lineCount", lines.size)
                            put("confs", buildJsonArray {
                                // 防御取值：Outcome 构造已 require 四方同长，此处再兜一层
                                outcome.pageLineConfs.getOrNull(idx)?.forEach { add(JsonPrimitive(it)) }
                            })
                            put("text", lines.joinToString("\n") { it.text })
                        })
                    }
                })
            }
            File(dir, "snapshot.json").writeText(json.toString())
            File(filesDir, "ocr_verify").listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.lastModified() }
                ?.dropLast(2)
                ?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            Log.w("P6b", "ocr verify snapshot: ${e.message}")
        }
    }

    /**
     * 兜底页草稿全量落盘（v1.1 P1-4 的 d<页号>.txt 部分：行文本+行尾 tab+置信度 3 位）
     * ——置信度-质量分层标定（P6b 落地报告 §7 画线）的数据源，过闸页草稿不入库故只落兜底页。
     * 行文本只作 %s 参数不作模板（OCR 文本含 % 会炸 UnknownFormatConversion，实录 d094
     * 附近「%车」致中断）；单页失败不拖垮其余落盘。
     */
    fun writeFallbackDrafts(dir: File, outcome: OcrImportRunner.Outcome) {
        outcome.fallbackPages.forEach { fb ->
            val lines = outcome.pageDrafts.getOrNull(fb.pageNo - 1).orEmpty()
            runCatching {
                File(dir, "d%03d.txt".format(fb.pageNo)).writeText(
                    lines.joinToString("\n") { "%s\t%.3f".format(it.text, it.confidence) },
                )
            }.onFailure { Log.w("P6b", "draft p${fb.pageNo}: ${it.message}") }
        }
    }

    /** DEBUG 页抽样步长：每 20 页抽 1 页（页号 1 起） */
    fun samplePageNos(pageCount: Int): List<Int> = (1..pageCount step 20).toList()

    /** 单页识别文本行（p<页号>.txt 内容源）：行文本逐行拼接（P6 快照同源口径） */
    fun pageTextLines(lines: List<PLine>): List<String> = lines.map { it.text }
}
