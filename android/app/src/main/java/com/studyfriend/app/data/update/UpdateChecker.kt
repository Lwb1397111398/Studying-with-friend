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
     * 先写 .part 临时文件、校验长度后再改名，中断不会留下半截冒充完整包。
     */
    fun downloadApk(
        url: String,
        token: String?,
        dest: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        if (expectedSize > 0 && dest.exists() && dest.length() == expectedSize) {
            onProgress(100)
            return
        }
        val tmp = File(dest.parentFile, dest.name + ".part")
        val conn = open(url, token)
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            val code = conn.responseCode
            if (code !in 200..299) {
                throw UpdateException("下载失败（HTTP $code）：请稍后再试")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                onProgress(pct)
                                lastPct = pct
                            }
                        }
                    }
                }
            }
            if (total > 0 && tmp.length() != total) {
                tmp.delete()
                throw UpdateException("下载不完整（收到 ${tmp.length()} / $total 字节），请重试")
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                throw UpdateException("保存安装包失败（文件被占用？）")
            }
            onProgress(100)
        } finally {
            conn.disconnect()
        }
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
