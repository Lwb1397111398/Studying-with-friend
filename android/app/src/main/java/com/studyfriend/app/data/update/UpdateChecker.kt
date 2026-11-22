package com.studyfriend.app.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub 最新发布里解析出的可更新版本信息 */
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    /** 发布说明（已去掉元数据注释行），弹窗里展示 */
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
)

sealed class UpdateCheckResult {
    data class UpToDate(val remoteVersionName: String) : UpdateCheckResult()
    data class Available(val release: ReleaseInfo) : UpdateCheckResult()
}

/** 检查/下载失败；message 已是人话，直接给用户看 */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 应用自更新（M7）数据层：查 GitHub Releases 最新版本 + 下载 APK。
 * 不依赖任何 Android 类（纯 JVM），单元测试直接用本地 HttpServer 顶 GitHub。
 *
 * 约定：云端工作流每次构建发布时，把机器可读元数据写进发布说明第一行的 HTML 注释
 * `<!-- studyfriend-update versionCode=15 versionName=0.1.15 -->`（GitHub 页面渲染时不可见），
 * 资产里第一个 .apk 就是安装包。仓库公开后匿名即可读 Releases（token 参数保留给私有仓库场景）；
 * 查询固定走 releases/tags/latest，不走 releases/latest（会被其他 Release 抢走，见 UPDATE_TAG 注释）。
 */
object UpdateChecker {

    const val DEFAULT_API_BASE = "https://api.github.com"
    /** 更新源仓库：与 git remote 一致；改仓库名时这里要同步 */
    const val REPO = "Lwb1397111398/Studying-with-friend"

    /**
     * App 更新固定发在 tag `latest` 的 Release（CI 每次 --clobber 覆盖它）。
     * 按 tag 取而不是 releases/latest：后者返回「发布时间最新」的 Release，
     * 仓库里任何其他 Release（如 2026-10-05 的 ocr-models-v1 模型包）发布更晚就会把它抢走，
     * 导致 App 解析不到版本元数据（2026-10-07 实际发生）。
     */
    const val UPDATE_TAG = "latest"

    /**
     * 下载源池里的镜像：GitHub 的资产 CDN（release-assets.githubusercontent.com）在国内时好时坏
     * （2026-10-25 实测整段阻断 0 字节到手；2026-11-22 本宽带直连又能以 ~230KB/s 拉通）——
     * 所以 App 不再「直连失败才换镜像」，而是把直连和镜像组成源池一起并行分块拉，谁通用谁（见 downloadApk）。
     * 镜像站随时会死：这里只保留实测能用的，死一个删一个（改此列表 → push → CI 自动出新版）；
     * 判活命令见 docs/模块总览/M7-应用自更新.md「换源兜底」一节。
     * 2026-11-22 验尸已删（全部超时或 403）：gh-proxy.com / ghproxy.net / github.moeyy.xyz /
     * gh.llkk.cc / ghproxy.link / ghproxy.vip / ghproxy.site / ghproxy.cc / hub.gitmirror.com /
     * ghproxy.homeboyc.cn / cf.ghproxy.cc。
     */
    val DOWNLOAD_MIRRORS = listOf(
        "https://ghfast.top/",   // 2026-11-22 实测可用：支持 Range 断点；单连 ~260KB/s，双连各 ~580KB/s（并行友好），单源 4 连接起开始饿死
    )

    /** 并行分块下载门槛：包体 ≥8MB 且源池 ≥2 个时启用；小包与 E2E 假服务器仍走单流整包 */
    private const val PARALLEL_MIN_BYTES = 8L * 1024 * 1024

    /**
     * 分块目标大小 ~16MB、块数 2~4：镜像会掐单条长连接（2026-10-25 实测 ~12MB 处被掐），块小断点续传粒度细；
     * 源池轮转使**单源并发 ≤2**（2026-11-22 实测单源 4 连接会有 2 条被饿死），块数封顶 4。
     */
    private const val CHUNK_TARGET_BYTES = 16L * 1024 * 1024
    private const val CHUNK_MAX = 4

    /**
     * 查询最新发布并与当前版本比较（versionCode 大于当前才算有更新）。
     * 失败抛 [UpdateException]。
     */
    fun fetchLatest(
        apiBase: String = DEFAULT_API_BASE,
        repo: String = REPO,
        token: String?,
        currentVersionCode: Int,
    ): UpdateCheckResult {
        val (body, apk) = fetchReleaseJson(apiBase, repo, token)
            ?: throw UpdateException(noReleaseMessage())
        val meta = parseUpdateMeta(body)
            ?: throw UpdateException("远端版本信息无法识别：发布说明里缺少版本元数据（需要重新跑一次云端构建）")
        val asset = apk ?: throw UpdateException("远端发布里没有找到 APK 安装包")
        val info = ReleaseInfo(
            versionCode = meta.versionCode,
            versionName = meta.versionName,
            notes = stripMetaComment(body),
            apkUrl = asset.url,
            apkSize = asset.size,
        )
        return if (meta.versionCode > currentVersionCode) {
            UpdateCheckResult.Available(info)
        } else {
            UpdateCheckResult.UpToDate(meta.versionName)
        }
    }

    /**
     * 下载 APK 到 [dest]，onProgress 回调整数百分比（IO 线程回调）。
     * 同版本号的完整文件已存在（长度与远端一致）时直接复用，不重复下载。
     * 大包（≥8MB 且源池 ≥2 个）走**并行分块**：整包切 2~4 段，每段一线程并发拉，
     * 段与段之间按「直连 + 镜像」源池轮转——46MB 包实测单连 ~260KB/s，并行后整体 ~3 倍速；
     * 每段先写独立 `.part.c<N>` 断点、Range 续拉、某段换源不影响其他段，全部齐了拼接改名。
     * 小包/无镜像走单流整包（E2E 假服务器同此路径）：先写 `.part`、校验长度后才改名。
     * 永远不会留下半截冒充完整包（2026-10-25 实测：镜像单发长连接必被掐断，断点续传才能下完）。
     * 仅 github.com 的地址拼镜像（E2E/单测的本地假服务器地址不拼）；
     * 测试用 [mirrors]/[chunkCount] 参数注入本地假镜像与强制分块数。
     */
    fun downloadApk(
        url: String,
        token: String?,
        dest: File,
        expectedSize: Long,
        mirrors: List<String> = if (url.startsWith("https://github.com/")) DOWNLOAD_MIRRORS else emptyList(),
        chunkCount: Int = 0,
        onProgress: (Int) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        if (expectedSize > 0 && dest.exists() && dest.length() == expectedSize) {
            onProgress(100)
            return
        }
        val tmp = File(dest.parentFile, dest.name + ".part")
        // 上次下载字节已齐但没来得及改名（中断在最后一步）：直接收尾，不发请求
        if (expectedSize > 0 && tmp.exists() && tmp.length() == expectedSize) {
            finalizeDownload(tmp, onProgress)
            return
        }
        val sources = listOf(url) + mirrors.map { it + url }
        val parallel = expectedSize > 0 &&
            (chunkCount > 0 || (expectedSize >= PARALLEL_MIN_BYTES && sources.size >= 2))
        if (parallel) {
            downloadParallel(
                sources, token, tmp, expectedSize,
                chunks = chunkCount.takeIf { it > 0 } ?: autoChunkCount(expectedSize),
                onProgress = onProgress,
            )
            return
        }
        var lastError: Exception? = null
        for (source in sources) {
            try {
                downloadFrom(source, token, tmp, onProgress)
                return
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw UpdateException(
            "下载失败：直连和 ${mirrors.size} 个镜像源都没能完成" +
                "（${lastError?.message ?: "未知错误"}）。再点一次「立即更新」会从上次断点继续，多试几次可下完",
            lastError,
        )
    }

    /**
     * 并行分块下载引擎：每段一个线程，段内按源池轮转重试（offset=段号错开，单源并发 ≤2）。
     * 每段独立断点文件 `.part.c<N>`（比单条断点粒度细：某段被镜像掐断只续该段）；
     * 全部段齐 → 拼接进整包 `.part` → 校验总长 → 统一改名收尾。
     * 任一段全源失败即抛人话汇总，已齐的段保留作断点，重试只补缺口。
     */
    private fun downloadParallel(
        sources: List<String>,
        token: String?,
        tmp: File,
        expectedSize: Long,
        chunks: Int,
        onProgress: (Int) -> Unit,
    ) {
        val chunkSize = (expectedSize + chunks - 1) / chunks
        val parts = (0 until chunks).map { File(tmp.parentFile, tmp.name + ".c$it") }
        val chunkDone = Array(chunks) { java.util.concurrent.atomic.AtomicLong(0L) }
        val lastPct = java.util.concurrent.atomic.AtomicInteger(-1)
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failedCount = java.util.concurrent.atomic.AtomicInteger(0)

        /** 段 i 的期望字节数（末段可短于 chunkSize） */
        fun chunkLen(i: Int): Long = minOf(chunkSize * (i + 1), expectedSize) - chunkSize * i

        /** 全局进度 = 各段已收字节数之和；只报单调递增的百分比（段断点被弃用重下时防回跳） */
        fun tick() {
            while (true) {
                val cur = lastPct.get()
                val total = chunkDone.withIndex().sumOf { (i, d) -> d.get().coerceAtMost(chunkLen(i)) }
                val pct = ((total * 100) / expectedSize).toInt().coerceIn(0, 99)
                if (pct <= cur) return
                if (lastPct.compareAndSet(cur, pct)) {
                    onProgress(pct)
                    return
                }
            }
        }

        // 断点预扫描（主线程、线程启动前定案，避免各线程初始化进度竞态）：脏断点弃用，其余计入初始进度
        for (i in 0 until chunks) {
            val part = parts[i]
            val want = chunkLen(i)
            val have = if (part.exists() && part.length() <= want) part.length() else 0L
            if (have == 0L && part.exists()) part.delete()
            chunkDone[i].set(have)
        }
        tick()

        val threads = (0 until chunks).map { i ->
            Thread {
                val want = chunkLen(i)
                val start = chunkSize * i
                val part = parts[i]
                if (chunkDone[i].get() == want) return@Thread // 该段上一轮已拉齐，只等拼接
                for (attempt in sources.indices) {
                    val source = sources[(i + attempt) % sources.size]
                    try {
                        downloadChunkFrom(
                            source, token, part, start, want,
                            have = part.length().coerceAtMost(want),
                        ) { n ->
                            chunkDone[i].addAndGet(n.toLong())
                            tick()
                        }
                        return@Thread
                    } catch (e: Exception) {
                        errors.add("段${i + 1}@${hostOf(source)}: ${e.message}")
                    }
                }
                failedCount.incrementAndGet()
            }.apply { start() }
        }
        threads.forEach { it.join() }
        if (failedCount.get() > 0) {
            throw UpdateException(
                "下载失败：${chunks} 段并行里有 ${failedCount.get()} 段没拉完" +
                    "（${errors.firstOrNull() ?: "未知错误"}）。再点一次「立即更新」会从各段断点继续，多试几次可下完",
            )
        }
        // 各段齐了 → 按序拼接进整包 .part → 校验总长 → 删段断点 → 统一改名收尾
        java.io.FileOutputStream(tmp).use { out ->
            parts.forEach { p -> p.inputStream().use { it.copyTo(out, 64 * 1024) } }
        }
        if (tmp.length() != expectedSize) {
            throw UpdateException("拼接安装包失败（${tmp.length()}/${expectedSize} 字节），请重试")
        }
        parts.forEach { it.delete() }
        finalizeDownload(tmp, onProgress)
    }

    /**
     * 单段单源下载：Range 从段内断点（[start]+[have]）续拉到段尾 [want]。
     * 源不支持 Range 回整包 200 时：只有「第 0 段从头拉」能直接消费（读到段边界即停），
     * 其余段内容对不上号 → 抛错换下一个源。收流截到段边界为止（服务器多发不写盘）。
     */
    private fun downloadChunkFrom(
        url: String,
        token: String?,
        part: File,
        start: Long,
        want: Long,
        have: Long,
        onAppend: (Int) -> Unit,
    ) {
        val conn = open(url, token)
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            if (have > 0) {
                conn.setRequestProperty("Range", "bytes=${start + have}-${start + want - 1}")
            } else if (start > 0) {
                conn.setRequestProperty("Range", "bytes=$start-${start + want - 1}")
            }
            val code = conn.responseCode
            if (code == 200 && (start > 0 || have > 0)) {
                throw UpdateException("该源不支持断点续传（HTTP 200 整包）")
            }
            if (code !in 200..299) {
                throw UpdateException("下载失败（HTTP $code）")
            }
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (part.length() < want) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val remaining = (want - part.length()).toInt()
                        val w = if (n > remaining) remaining else n
                        out.write(buf, 0, w)
                        onAppend(w)
                        if (n > remaining) break // 服务器多发（Range 未兑现）：截到段边界为止
                    }
                }
            }
            if (part.length() != want) {
                throw UpdateException("分块不完整（已收 ${part.length()}/$want 字节）")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 从镜像前缀拼出的源地址里取主机名（人话错误信息用），如 https://ghfast.top/https://github.com/... → ghfast.top */
    private fun hostOf(source: String): String =
        source.substringAfter("://").substringBefore('/')

    /** 按包体自动定块数：目标 ~16MB/段，2~4 段（8~16MB 包 2 段、32MB 2 段、48MB 3 段、64MB+ 4 段） */
    private fun autoChunkCount(size: Long): Int =
        (((size + CHUNK_TARGET_BYTES - 1) / CHUNK_TARGET_BYTES).toInt().coerceIn(2, CHUNK_MAX))

    /** 单源下载到 [tmp]：已有字节带 Range 续拉，服务器不支持 Range 回 200 则从头重下；成功校验长度后改名 */
    private fun downloadFrom(url: String, token: String?, tmp: File, onProgress: (Int) -> Unit) {
        val conn = open(url, token)
        try {
            conn.connectTimeout = 15000
            // 30 秒读不出数据才判死：正常链路单个 64KB 块远快于此；被墙直连假死别拖满 2 分钟才换源
            conn.readTimeout = 30000
            val resumeFrom = if (tmp.exists()) tmp.length() else 0L
            if (resumeFrom > 0) {
                conn.setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw UpdateException("下载失败（HTTP $code）：请稍后再试")
            }
            val base: Long
            val fullLength: Long
            if (code == 206 && resumeFrom > 0) {
                base = resumeFrom
                fullLength = base + conn.contentLengthLong // 206 的 Content-Length 是剩余字节数
            } else {
                base = 0L // 无 .part，或服务器不支持 Range 回了 200：从头下（覆盖旧半截）
                fullLength = conn.contentLengthLong
            }
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmp, base > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = base
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (fullLength > 0) {
                            val pct = (done * 100 / fullLength).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                onProgress(pct)
                                lastPct = pct
                            }
                        }
                    }
                }
            }
            if (fullLength > 0 && tmp.length() != fullLength) {
                // .part 保留作断点，下次接着拉；若服务器与 Range 撒谎导致长度越攒越歪，
                // 下次 Range 会落到 416/长度反复不符，用户重试几次内靠换源从头下自愈
                throw UpdateException("下载不完整（已收 ${tmp.length()} / $fullLength 字节），重试将从断点续传")
            }
            if (fullLength <= 0 && tmp.length() <= 0L) {
                throw UpdateException("下载不完整（未收到数据），请重试")
            }
            finalizeDownload(tmp, onProgress)
        } finally {
            conn.disconnect()
        }
    }

    /** 校验过的完整 `.part` 改名为正式包 */
    private fun finalizeDownload(tmp: File, onProgress: (Int) -> Unit) {
        val dest = File(tmp.parentFile, tmp.name.removeSuffix(".part"))
        dest.delete()
        if (!tmp.renameTo(dest)) {
            throw UpdateException("保存安装包失败（文件被占用？）")
        }
        onProgress(100)
    }

    // ---- 以下为纯解析函数，供单元测试直测 ----

    data class UpdateMeta(val versionCode: Int, val versionName: String)
    data class RemoteAsset(val url: String, val size: Long)

    /** 从发布说明第一行注释里读 versionCode/versionName，读不到返回 null */
    fun parseUpdateMeta(body: String): UpdateMeta? {
        val regex = Regex(
            """<!--\s*studyfriend-update\s+versionCode=(\d+)\s+versionName=([^\s>]+)\s*-->""",
            RegexOption.IGNORE_CASE,
        )
        val m = regex.find(body) ?: return null
        val code = m.groupValues[1].toIntOrNull() ?: return null
        if (m.groupValues[2].isBlank()) return null
        return UpdateMeta(code, m.groupValues[2])
    }

    /** 去掉元数据注释行和首尾空行，剩下的就是给人看的更新说明 */
    fun stripMetaComment(body: String): String =
        body.replace(Regex("""<!--\s*studyfriend-update[^>]*-->"""), "").trim()

    /** 人话版 404 提示：按 tag 取不到 = latest Release 还没建过 */
    fun noReleaseMessage(): String =
        "仓库还没有发布过任何版本（推送代码后等云端构建完成，再点「检查更新」）"

    /**
     * GET /repos/{repo}/releases/latest，返回 (body, 第一个 .apk 资产)。
     * 没有任何发布时 GitHub 返回 404 → 返回 null；其余失败抛 [UpdateException]。
     */
    private fun fetchReleaseJson(apiBase: String, repo: String, token: String?): Pair<String, RemoteAsset?>? {
        val conn = open("$apiBase/repos/$repo/releases/tags/$UPDATE_TAG", token)
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            when (val code = conn.responseCode) {
                200 -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = Json.parseToJsonElement(text).jsonObject
                    val body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val assets = obj["assets"] as? JsonArray ?: JsonArray(emptyList())
                    val apk = assets
                        .mapNotNull { it as? JsonObject }
                        .map { asset ->
                            val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
                            name to RemoteAsset(url, size)
                        }
                        .firstOrNull { (name, _) -> name.endsWith(".apk", ignoreCase = true) }
                        ?.second
                    return body to apk
                }
                404 -> return null
                401 -> throw UpdateException("GitHub 拒绝了请求（401）：请稍后再试")
                403 -> throw UpdateException("GitHub 请求被限流，请稍后再试")
                else -> throw UpdateException("GitHub 返回异常状态（HTTP $code）")
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: java.io.IOException) {
            throw UpdateException("网络连接失败：${e.message ?: "请检查网络"}", e)
        } finally {
            conn.disconnect()
        }
    }

    /** HTTP 连接工厂（鉴权头/UA/API 版本头统一）；P6b S5 OcrModelDownloader 复用 */
    internal fun open(url: String, token: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        // GitHub API 强制要求 User-Agent，缺了直接 403
        conn.setRequestProperty("User-Agent", "studyfriend-app")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (!token.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }
        return conn
    }
}
