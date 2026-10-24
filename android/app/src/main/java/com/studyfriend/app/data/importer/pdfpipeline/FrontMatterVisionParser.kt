package com.studyfriend.app.data.importer.pdfpipeline

import android.util.Log
import com.studyfriend.app.data.ai.AiBudgets
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.AiException
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.JsonSlicer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * 前置页视觉判定（P8 探针）：把书的开头若干页按内容分类（封面/版权页/作者介绍/各版
 * 序言/目录/正文…），支撑「开篇」章按块拆分与目录页漏判兜底。
 *
 * 与 [TocVisionParser]（目录条目结构化提取）职责不同：本类只判每页「是什么部分」，
 * 不读条目。批次 = 4 页一次调用（多图消息；版式分类只需粗看，无需逐页精读），一本
 * 新书约 1~6 次调用；缓存/marker 熔断/预算模式与 TocVisionParser 同构
 * （`fm_v{N}_{uriHash}_s{页列表}.json` / `fm_fail_…marker`，30 天 TTL）。
 * 任何失败 → null，调用方回退纯文本路线，零回归。
 */

/** 单页判定；page=全书物理页号（1-based），kind 见 [FrontKinds]，title=页面大标题原文 */
data class FrontPageVerdict(val page: Int, val kind: String, val title: String?)

object FrontKinds {
    const val COVER = "cover"          // 封面（书名/作者大字）
    const val COPYRIGHT = "copyright"  // 版权页（出版信息/ISBN/CIP）
    const val DEDICATION = "dedication"// 献词页
    const val BIO = "bio"              // 作者/译者介绍
    const val PREFACE = "preface"      // 序言族（序/前言/写在前面/第X版序…）
    const val TOC = "toc"              // 目录页
    const val BODY = "body"            // 正文页
    const val OTHER = "other"          // 其他前置（推荐语/丛书说明等）

    val ALL = setOf(COVER, COPYRIGHT, DEDICATION, BIO, PREFACE, TOC, BODY, OTHER)

    /** 前置内容类（toc/body 归现有目录/正文链路，不产前置块） */
    val FRONT = setOf(COVER, COPYRIGHT, DEDICATION, BIO, PREFACE, OTHER)

    /** 块默认名（页面无大标题时用）；other 归「开篇」与 BookParser 无名杂页命名一致 */
    fun defaultTitle(kind: String): String = when (kind) {
        COVER -> "封面"
        COPYRIGHT -> "版权页"
        DEDICATION -> "献词"
        BIO -> "作者介绍"
        PREFACE -> "序言"
        else -> "开篇"
    }
}

/** 合并后的前置块：连续同类页合一块；preface 标题不同分块（初版序/第二版序各自成块） */
data class FrontBlock(val kind: String, val title: String, val startPage: Int, val endPage: Int)

/**
 * verdicts → 前置块 的纯状态机（单测直测；输入按页号升序）。
 * toc/body 页不产块也不终止扫描——部分书目录排在序言之前，序言块仍要收；
 * 「前置区到哪结束」由调用方取首个 toc/body verdict 页另行判定。
 */
object FrontMatterBlocks {

    fun build(verdicts: List<FrontPageVerdict>): List<FrontBlock> {
        val out = mutableListOf<FrontBlock>()
        for (v in verdicts.sortedBy { it.page }) {
            if (v.kind !in FrontKinds.FRONT) continue
            val title = v.title?.trim()?.takeIf { it.isNotEmpty() } ?: FrontKinds.defaultTitle(v.kind)
            val last = out.lastOrNull()
            val mergeable = last != null && last.kind == v.kind && v.page <= last.endPage + 1 &&
                // preface 按标题分块：第二版序≠初版序；无标题的续页并入前块（默认「序言」同名）
                (v.kind != FrontKinds.PREFACE || last.title == title)
            if (mergeable) {
                out[out.size - 1] = last!!.copy(endPage = maxOf(last.endPage, v.page))
            } else {
                out += FrontBlock(v.kind, title, v.page, v.page)
            }
        }
        return out
    }
}

/** 模型输出/缓存 JSON 解码（纯函数） */
internal object FrontJson {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析一批 n 页的模型输出。prompt 只让模型报照片顺序号 1..n（杜绝幻觉页码），
     * 守卫：条目数=n、顺序号恰为 1..n 各一次、kind 合法。任何不符 → null（整批不可信）。
     */
    fun parse(raw: String, requested: Int): List<FrontPageVerdict>? {
        val vs = decode(raw) ?: return null
        if (vs.size != requested || vs.map { it.page }.toSet() != (1..requested).toSet()) return null
        return vs.sortedBy { it.page }
    }

    /** 缓存读回：page 为物理页号，无顺序号契约；页集合由调用方对账 chunk */
    fun parseCached(raw: String): List<FrontPageVerdict>? = decode(raw)

    private fun decode(raw: String): List<FrontPageVerdict>? {
        return try {
            val obj = json.parseToJsonElement(JsonSlicer.slice(raw)).jsonObject
            val arr = obj["pages"] as? JsonArray ?: return null
            val vs = arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return null
                val page = (o["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
                if (page < 1) return null
                val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                if (kind !in FrontKinds.ALL) return null
                val title = when (val t = o["title"]) {
                    null, is JsonNull -> null
                    is JsonPrimitive -> t.content.trim().takeIf { it.isNotEmpty() && it.length <= 40 }
                    else -> return null
                }
                FrontPageVerdict(page, kind, title)
            }
            vs
        } catch (e: Exception) {
            null
        }
    }

    /** 缓存序列化：page 写物理页号（读回 parseCached+页集合对账，与写入方口径对称） */
    fun encodeCached(verdicts: List<FrontPageVerdict>): String = buildJsonObject {
        put("pages", buildJsonArray {
            verdicts.forEach { v ->
                add(buildJsonObject {
                    put("page", JsonPrimitive(v.page))
                    put("kind", JsonPrimitive(v.kind))
                    put("title", v.title?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        })
    }.toString()
}

class FrontMatterVisionParser(
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
     * 逐批识别前置页。pageNumbers 升序物理页号（调用方圈定的前置区，≤24 页）；
     * 全有全无语义：任一批失败（网络/渲染/预算/熔断）→ null；全部页判成 body
     * （选页范围错误）同样 null。成功返回按页号升序的物理页判定。
     */
    suspend fun probe(pageNumbers: List<Int>, uriHash: String): List<FrontPageVerdict>? =
        withContext(Dispatchers.IO) {
            if (!enabled || pageNumbers.isEmpty()) return@withContext null
            val start = clock()
            val elapsed: () -> Long = { clock() - start }
            cleanOrphanTmp()

            val out = mutableListOf<FrontPageVerdict>()
            for (chunk in pageNumbers.chunked(CHUNK_PAGES)) {
                val cacheFile = cacheFile(uriHash, chunk)
                val cached = readCache(cacheFile, chunk)
                if (cached != null) {
                    out += cached
                    continue
                }
                val marker = markerFile(uriHash, chunk)
                if (markerFresh(marker)) {
                    Log.i(TAG, "chunk ${chunk.first()}.. marker fresh, skip ($uriHash)")
                    return@withContext null // 全有全无：一批熔断即整次放弃
                }
                when (val r = probeChunk(chunk, elapsed)) {
                    is ChunkResult.Ok -> out += r.verdicts
                    is ChunkResult.Fail -> {
                        Log.w(TAG, "chunk ${chunk.first()}.. failed (mark=${r.mark})")
                        if (r.mark) writeMarker(marker)
                        return@withContext null
                    }
                    ChunkResult.BudgetOut -> {
                        Log.w(TAG, "front probe budget exhausted at page ${chunk.first()}")
                        return@withContext null
                    }
                }
            }
            // 整体守卫：全部判 body = 选页范围错误（不是前置区），弃用防污染结构
            if (out.isEmpty() || out.all { it.kind == FrontKinds.BODY }) {
                Log.w(TAG, "front probe guard: all ${out.size} pages body, discard")
                return@withContext null
            }
            // 守卫通过后逐批写缓存（坏结果不落缓存，与 TocVisionParser 口径一致）
            for (chunk in pageNumbers.chunked(CHUNK_PAGES)) {
                val vs = out.filter { it.page in chunk }
                if (vs.size == chunk.size) writeCache(cacheFile(uriHash, chunk), vs)
            }
            out.sortedBy { it.page }
        }

    private sealed class ChunkResult {
        class Ok(val verdicts: List<FrontPageVerdict>) : ChunkResult()
        class Fail(val mark: Boolean) : ChunkResult()
        data object BudgetOut : ChunkResult()
    }

    private suspend fun probeChunk(chunk: List<Int>, elapsed: () -> Long): ChunkResult {
        if (elapsed() + READ_TIMEOUT_MS > TOTAL_TIMEOUT_MS) return ChunkResult.BudgetOut
        val images = mutableListOf<String>()
        for (pageNo in chunk) {
            try {
                images += "data:image/png;base64," + renderFn(pageNo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "page render failed: $pageNo", e)
                return ChunkResult.Fail(mark = false)
            }
        }
        val raw = callWithRetry(images, chunk.size, elapsed) ?: return ChunkResult.Fail(mark = false)
        // 照片顺序号 1..n → 物理页号映射（prompt 报顺序号，杜绝幻觉页码）
        val verdicts = FrontJson.parse(raw, chunk.size)
            ?.mapIndexed { i, v -> v.copy(page = chunk[i]) }
            ?: return ChunkResult.Fail(mark = true) // 内容性失败：unparseable/条目不齐
        return ChunkResult.Ok(verdicts)
    }

    /** 网络错/超时重试 1 次（重试前再查预算）；其余异常不重试（口径同 TocVisionParser） */
    private suspend fun callWithRetry(
        images: List<String>,
        n: Int,
        elapsed: () -> Long,
    ): String? {
        repeat(TRANSCRIBE_ATTEMPTS) { attempt ->
            if (attempt > 0 && elapsed() + READ_TIMEOUT_MS > TOTAL_TIMEOUT_MS) return null
            try {
                return chatFn(request(images, n))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = e is IOException || (e as? AiException)?.httpCode != null
                Log.w(TAG, "chunk call failed (attempt ${attempt + 1}, retryable=$retryable): ${e.message}")
                if (!retryable || attempt == TRANSCRIBE_ATTEMPTS - 1) return null
            }
        }
        return null
    }

    private fun request(images: List<String>, n: Int) = ChatRequest(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = 0.1,
        maxTokens = AiBudgets.OUTPUT_TOKENS_VISION_PAGE,
        messages = listOf(
            AiMessage(
                role = "user",
                content = PROMPT_HEAD + n + PROMPT_TAIL,
                images = images,
            ),
        ),
        readTimeoutMs = READ_TIMEOUT_MS.toInt(),
    )

    // ---- 缓存/marker（模式同 TocVisionParser：temp+rename 原子写，写失败不废结果） ----

    private fun readCache(f: File, chunk: List<Int>): List<FrontPageVerdict>? = try {
        if (!f.isFile() || f.length() > MAX_CACHE_BYTES) null
        else FrontJson.parseCached(f.readText())?.let { vs ->
            if (vs.size == chunk.size && vs.map { it.page }.toSet() == chunk.toSet()) vs else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "cache read failed, rebuild: ${e.message}")
        null
    }

    private fun writeCache(f: File, verdicts: List<FrontPageVerdict>) {
        try {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, f.name + ".tmp")
            tmp.writeText(FrontJson.encodeCached(verdicts))
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed (result still returned): ${e.message}")
        }
    }

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
        else Duration.between(Instant.parse(marker.readText().trim()), Instant.ofEpochMilli(clock()))
            .toMillis() < MARKER_TTL_MS
    } catch (e: Exception) {
        false
    }

    private fun cacheFile(uriHash: String, chunk: List<Int>) =
        File(cacheDir, "fm_v${FORMAT_VERSION}_${uriHash}_s${chunk.joinToString("-")}.json")

    private fun markerFile(uriHash: String, chunk: List<Int>) =
        File(cacheDir, "fm_fail_v${FORMAT_VERSION}_${uriHash}_s${chunk.joinToString("-")}.marker")

    private fun cleanOrphanTmp() {
        try {
            cacheDir.mkdirs()
            val now = clock()
            cacheDir.listFiles { f -> f.name.startsWith("fm_") && f.name.endsWith(".tmp") }?.forEach { tmp ->
                try {
                    if (now - tmp.lastModified() > ORPHAN_TMP_TTL_MS) tmp.delete()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "P8"
        /** 缓存/marker 格式版本：Prompt、kind 族、解析规则或入口变更时递增 */
        internal const val FORMAT_VERSION = 1
        private const val TRANSCRIBE_ATTEMPTS = 2
        private const val READ_TIMEOUT_MS = 60_000L
        /** 总预算：24 页前置区 / 4 页一批 = 6 批 × 60s */
        private const val TOTAL_TIMEOUT_MS = 360_000L
        private const val CHUNK_PAGES = 4
        private const val MARKER_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val ORPHAN_TMP_TTL_MS = 10L * 60 * 1000
        private const val MAX_CACHE_BYTES = 100_000L

        private const val PROMPT_HEAD = "这是一本书开头部分的"
        private const val PROMPT_TAIL =
            "张页面照片，按顺序从第1张到第n张。判断每一页属于书的哪个部分，输出JSON：" +
                "{\"pages\":[{\"page\":1,\"kind\":\"cover\",\"title\":null}]}。" +
                "kind取值（英文）：cover=封面（书名/作者大字，无正文段落）；" +
                "copyright=版权页（出版信息/ISBN/版权声明/CIP数据）；dedication=献词页；" +
                "bio=作者介绍或译者介绍；preface=序言类（「序」「前言」「写在前面」" +
                "「第X版序言」「总序」「译序」等，title填该页大标题原文，如「第二版序言」）；" +
                "toc=目录页（条目加页码的列表）；body=正文页（普通连续段落，title用null）；" +
                "other=其他前置内容（推荐语、丛书说明等）。" +
                "page填照片顺序编号1到n；只输出JSON不要解释；一页一条按顺序输出。"
    }
}
