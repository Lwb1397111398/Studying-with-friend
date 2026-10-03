package com.studyfriend.app.data.importer.pdfpipeline

import android.util.Log
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.AiException
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.ChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * 目录页视觉识别（P3b-1 探针）：把 tocLike 页图交给视觉模型，产出结构化目录条目。
 * 与 [VisionTranscriber]（忠实转写整页文本）职责不同：本类是结构化提取（Parser）。
 *
 * 探针定位：只产数据（调用方记 logcat + 内存暂存），不参与分章（P3b-2 消费）；
 * 任何环节失败 → null，导入行为与不挂探针时逐字节一致。
 *
 * 重试策略独立于 VisionTranscriber（仅网络错/超时重试 1 次；≤8 次调用场景不值得
 * 抽共享接口）：若 VisionTranscriber 重试策略变更（如加指数退避），此处需手动
 * 评估同步。不经 VisionScheduler——单书 ≤8 次轻量调用；调用量持续 >10 次/书时
 * 重新评估接入。
 *
 * 缓存（cacheDir 由构造器传入）：按区段全有全无——区段成功才写
 * `toc_v{N}_{uriHash}_s{页列表}.json`（原子写 temp+rename）；区段内容性失败
 * （unparseable JSON）写 `toc_fail_v{N}_{uriHash}_s{页列表}.marker`（30 天 TTL 内同书
 * 该区段跳过探针，防坏目录页每导必烧；临时性失败如网络/渲染错不写 marker）。
 * 拼接全集过整体守卫：守卫失败写全部区段 marker（第二层整书熔断——守卫作用于
 * 拼接全集无法定位坏区段，防坏目录页每导必烧 30 天；真书从未触发，纯防御路径），
 * 已成功区段缓存不写（坏目录不落缓存）。区段级 Fail 与守卫失败是两层独立熔断：
 * 前者只影响单区段（一册失败不拖垮另一册，P3b-2 §6），后者整书兜底；
 * 单区段书（[parse] 入口）行为与 P3b-1 一致。TocJson.kt 顶部记录格式版本历史；
 * Prompt/解析规则/模型/入口变更时递增 [FORMAT_VERSION]（旧版本缓存与 marker 因
 * 文件名不同自然失效；v3→v4 过渡期 v3 文件磁盘留存但被忽略，已有缓存的书首次
 * 重导多付一次探针成本）。
 */
class TocVisionParser(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val cacheDir: File,
    private val enabled: Boolean = true,
    private val chatFn: suspend (ChatRequest) -> String = { req -> AiClient.chat(req) },
    private val renderFn: (Int) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 逐页识别目录（单区段便捷入口，行为与 P3b-1 一致）。入参=调用方选好的
     * tocLike 物理页号（1-based，升序）+ 文件 uriHash（缓存/marker 文件名用）。
     * 内部逐页「渲染→调用→释放」不预加载全部页，内存峰值 ≤1 页图。任一环节失败
     * 整体返 null（半份目录不采纳），已成功页丢弃。
     */
    suspend fun parse(pageNumbers: List<Int>, uriHash: String): List<TocEntry>? =
        parseSegments(listOf(pageNumbers), uriHash)

    /**
     * 按区段识别目录（P3b-2 §3.4/§6）：区段间独立——成功区段写区段缓存保留，
     * 失败区段按失败类型处置（内容性失败写区段 marker 单独熔断；临时性失败
     * 不写，下次重试）；全部成功区段条目合并后过整体守卫（守卫作用于拼接全集，
     * 与 P3b-1 同判据）。预算耗尽停止处理后续区段，已成功区段照常走守卫返回
     * （区段级全有全无：一册失败不再拖垮另一册，单区段书退化为 P3b-1 行为）。
     */
    suspend fun parseSegments(segments: List<List<Int>>, uriHash: String): List<TocEntry>? =
        withContext(Dispatchers.IO) {
            if (!enabled || segments.isEmpty() || segments.all { it.isEmpty() }) {
                return@withContext null
            }
            val start = clock()
            val elapsed: () -> Long = { clock() - start }
            cleanOrphanTmp()

            // (区段下标, 条目)；缓存命中的区段直接计入（其内容在写入前已过守卫）
            val ok = mutableListOf<Pair<Int, List<TocEntry>>>()
            var budgetOut = false
            for ((idx, segPages) in segments.withIndex()) {
                if (segPages.isEmpty()) continue
                val cacheFile = segCacheFile(uriHash, segPages)
                val cached = readCache(cacheFile)
                if (cached != null) {
                    Log.i(TAG, "segment $idx cache hit ($uriHash, ${cached.size} entries)")
                    ok += idx to cached
                    continue
                }
                val marker = segMarkerFile(uriHash, segPages)
                if (markerFresh(marker)) {
                    Log.i(TAG, "segment $idx fail marker fresh, skip ($uriHash)")
                    continue
                }
                when (val r = probeSegment(segPages, elapsed)) {
                    is SegResult.Ok -> ok += idx to r.entries // 缓存延后到守卫通过统一写（坏目录不落缓存）
                    is SegResult.Fail -> {
                        Log.w(TAG, "segment $idx failed (mark=${r.mark})")
                        if (r.mark) writeMarker(marker)
                    }
                    SegResult.BudgetOut -> {
                        budgetOut = true
                        break // 已成功区段照常使用，不再处理后续区段（无 marker 无缓存）
                    }
                }
            }

            val merged = ok.flatMap { it.second }
            if (merged.isEmpty()) return@withContext null

            if (!TocJson.passesGuards(merged)) {
                Log.w(TAG, "TOC pages may be misidentified: ${segments.size} segments, ${merged.size} entries failed guards")
                // 整书熔断（全部区段含未处理区段写 marker，防坏目录页每导必烧 30 天）；
                // 坏目录不落缓存——区段缓存在守卫通过后才写，此处无缓存写入
                for (segPages in segments) if (segPages.isNotEmpty()) writeMarker(segMarkerFile(uriHash, segPages))
                return@withContext null
            }
            // 守卫通过：各区段写缓存（失败区段 marker 保留，30 天 TTL 后自然重试）
            for ((idx, entries) in ok) writeCache(segCacheFile(uriHash, segments[idx]), entries)
            val level1 = merged.count { it.level == 1 }
            Log.i(
                TAG,
                "toc entries=${merged.size} (level1=$level1, level2=${merged.size - level1}, " +
                    "segments=${ok.size}/${segments.size}, budgetOut=$budgetOut)",
            )
            if (merged.size < MIN_QUALITY_ENTRIES) {
                Log.w(TAG, "toc quality warning: ${merged.size} entries, below minimum $MIN_QUALITY_ENTRIES")
            }
            merged
        }

    /** 单区段逐页处理；渲染/网络失败=临时性（不熔断），unparseable=内容性（熔断） */
    private suspend fun probeSegment(segPages: List<Int>, elapsed: () -> Long): SegResult {
        val collected = mutableListOf<TocEntry>()
        for (pageNo in segPages) {
            // 统一前置检查（首次与重试同判据）：容量算术 (480−12)/60≈7.8 → 8 页整
            if (elapsed() + READ_TIMEOUT_MS > TOTAL_TIMEOUT_MS) {
                Log.w(TAG, "toc budget exhausted after ${collected.size} entries, page $pageNo dropped")
                return SegResult.BudgetOut
            }
            val pageStart = clock()
            val pngBase64 = try {
                renderFn(pageNo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "page render failed: $pageNo", e)
                return SegResult.Fail(mark = false)
            }
            val raw = callWithRetry(pngBase64, elapsed) ?: return SegResult.Fail(mark = false)
            // 单页只解析不守卫（目录尾页可能条目很少，页级守卫会误杀）；
            // 守卫作用于拼接全集：条目数/level=1/非降序跨页连续性一次判足
            val pageEntries = TocJson.parse(raw, applyGuards = false)
            if (pageEntries != null && pageEntries.isEmpty()) {
                Log.w(TAG, "page $pageNo: all entries filtered out")
            }
            if (pageEntries == null) {
                Log.w(TAG, "page $pageNo returned unparseable JSON")
                return SegResult.Fail(mark = true)
            }
            // 单页耗时=渲染+调用+解析（E2E 敏感性报告口径，P3b-2 调参依据）
            Log.i(TAG, "page $pageNo: ${pageEntries.size} entries, ${clock() - pageStart}ms")
            collected += pageEntries
        }
        return SegResult.Ok(collected)
    }

    private sealed class SegResult {
        class Ok(val entries: List<TocEntry>) : SegResult()

        /** 区段失败；mark=true 写区段熔断 marker（内容性失败），false 不写（临时性失败） */
        class Fail(val mark: Boolean) : SegResult()
        data object BudgetOut : SegResult()
    }

    /** 网络错/超时重试 1 次（重试前再查预算）；其余异常与 JSON 解析失败同罚：不重试 */
    private suspend fun callWithRetry(pngBase64: String, elapsed: () -> Long): String? {
        repeat(TRANSCRIBE_ATTEMPTS) { attempt ->
            if (attempt > 0 && elapsed() + READ_TIMEOUT_MS > TOTAL_TIMEOUT_MS) {
                Log.w(TAG, "retry skipped: no budget left")
                return null
            }
            try {
                return chatFn(request(pngBase64))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = e is IOException || (e as? AiException)?.httpCode != null
                Log.w(TAG, "page call failed (attempt ${attempt + 1}, retryable=$retryable): ${e.message}")
                if (!retryable || attempt == TRANSCRIBE_ATTEMPTS - 1) return null
            }
        }
        return null
    }

    private fun request(pngBase64: String) = ChatRequest(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = 0.1,
        maxTokens = 8000,
        messages = listOf(
            AiMessage(role = "user", content = PROMPT, images = listOf("data:image/png;base64,$pngBase64")),
        ),
        readTimeoutMs = READ_TIMEOUT_MS.toInt(),
    )

    /** 未命中/损坏/版本不符/超大一律 null（调用方重建）；并发边界：探针期单线程调用 */
    private fun readCache(f: File): List<TocEntry>? = try {
        if (!f.isFile()) null
        else if (f.length() > MAX_CACHE_BYTES) {
            Log.w(TAG, "cache oversize (${f.length()}B), rebuild")
            null
        } else TocJson.parse(f.readText())
    } catch (e: Exception) {
        Log.w(TAG, "cache read failed, rebuild: ${e.message}")
        null
    }

    /** 原子写：temp+rename 杜绝读到半截 JSON；写失败只记日志不废已成功结果 */
    private fun writeCache(f: File, entries: List<TocEntry>) {
        try {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, f.name + ".tmp")
            // kotlinx 构建：字符串转义交给库（手写 jsonQuote 易随字段变更漏转义），
            // 格式与 readCache→TocJson.parse 的 {"entries":[...]} 口径对称
            tmp.writeText(
                buildJsonObject {
                    put("entries", buildJsonArray {
                        entries.forEach { e ->
                            add(buildJsonObject {
                                put("title", JsonPrimitive(e.title))
                                put("page", e.page?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("level", JsonPrimitive(e.level))
                            })
                        }
                    })
                }.toString(),
            )
            // Linux（Android 真机）renameTo 原子覆盖已存在目标，无 TOCTOU 窗口；
            // 桌面 JVM（单测）renameTo 不覆盖，失败时退回先删再 rename——此时窗口内
            // 读方最多 miss 重建，无害。rename 成功则 tmp 已不存在，两次失败路径
            // 均有 tmp.delete() 兜底；仅「写途中进程崩溃」会留 .tmp 孤儿，
            // 由 cleanOrphanTmp 在下次探针入口统一清理
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed (result still returned): ${e.message}")
        }
    }

    /** marker 内容=ISO 时间戳；写失败仅记日志（熔断失效=下次重导多烧几次调用） */
    private fun writeMarker(marker: File) {
        try {
            cacheDir.mkdirs()
            marker.writeText(Instant.ofEpochMilli(clock()).toString())
        } catch (e: Exception) {
            Log.w(TAG, "marker write failed: ${e.message}")
        }
    }

    private fun markerFresh(marker: File): Boolean = try {
        if (!marker.exists()) false
        else {
            val ts = Instant.parse(marker.readText().trim())
            Duration.between(ts, Instant.ofEpochMilli(clock())).toMillis() < MARKER_TTL_MS
        }
    } catch (e: Exception) {
        false
    }

    // 段 key=uriHash+段页列表（v1.2 修正）：只含段序号的旧 key 会在取样策略
    // 变化（如段 [18,19]→[18,19,20]）后命中毒化旧缓存；页列表直接入文件名
    // （区段=连续目录页列表，通常 ≤10 页，文件名长度可控）——段定义一变即 miss
    // 且无 hashCode 碰撞可能
    private fun segCacheFile(uriHash: String, segPages: List<Int>) =
        File(cacheDir, "toc_v${FORMAT_VERSION}_${uriHash}_s${segPages.joinToString("-")}.json")

    private fun segMarkerFile(uriHash: String, segPages: List<Int>) =
        File(cacheDir, "toc_fail_v${FORMAT_VERSION}_${uriHash}_s${segPages.joinToString("-")}.marker")


    /** 清理崩溃残留的 .tmp 孤儿（writeText 与 rename 之间进程死亡）：mtime 超 10 分钟即删 */
    private fun cleanOrphanTmp() {
        try {
            cacheDir.mkdirs()
            val now = clock()
            cacheDir.listFiles { f -> f.name.startsWith("toc_v") && f.name.endsWith(".tmp") }?.forEach { tmp ->
                try {
                    if (now - tmp.lastModified() > ORPHAN_TMP_TTL_MS) tmp.delete()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "P3b"
        /** 缓存/marker 格式版本：Prompt、解析规则、模型或入口（区段化）变更时递增（历史见 TocJson.kt 头） */
        internal const val FORMAT_VERSION = 4
        private const val TRANSCRIBE_ATTEMPTS = 2
        private const val READ_TIMEOUT_MS = 60_000L
        /** 总预算：8 页区段采样 × 60s readTimeout（P3b-2 §3.4；页面渲染开销计入同一预算口径） */
        private const val TOTAL_TIMEOUT_MS = 480_000L
        private const val MARKER_TTL_MS = 30L * 24 * 60 * 60 * 1000
        /** .tmp 孤儿清理阈值：写入途中崩溃才会留下（正常路径 rename/delete 兜底），10 分钟足够区分 */
        private const val ORPHAN_TMP_TTL_MS = 10L * 60 * 1000
        private const val MIN_QUALITY_ENTRIES = 10

        /** 缓存文件大小上限：正常目录 JSON 仅数 KB（真书实测 <5KB），超限视为损坏走重建 */
        private const val MAX_CACHE_BYTES = 1_000_000L

        private val PROMPT =
            "这是书的目录页照片。把目录条目提取为 JSON：" +
                "{\"entries\":[{\"title\":\"条目标题原文\",\"page\":123,\"level\":1}]}。" +
                "要求：只输出 JSON，不要解释或代码围栏；title 忠实原文不改写不翻译；" +
                "page=条目点线后标注的页码整数，看不到页码用 null；" +
                "level：章/篇/部/编/卷/回等大标题=1，节/小节=2，三级及以下条目（如（一）、1.）也记 2；" +
                "双栏目录按先左栏后右栏、栏内从上到下顺序输出；忽略页眉、页脚、本页页码。"
    }
}
