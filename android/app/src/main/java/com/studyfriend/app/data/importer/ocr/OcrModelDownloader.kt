package com.studyfriend.app.data.importer.ocr

import com.studyfriend.app.data.update.UpdateChecker
import com.studyfriend.app.data.update.UpdateException
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * OCR 模型下载器（P6b S5）：模型不进 APK（瘦身 ~21MB），首次使用时从 GitHub Release
 * `ocr-models-v1` 下载三件套（det/rec.onnx + 词典）到 filesDir/ocr_models/。
 * HTTP 层复用 M7 [UpdateChecker]（鉴权头/UA/`.part` 临时文件原子改名/同长度复用），
 * 附加 SHA-256 完整性校验（Release 附 SHA256SUMS，`sha256sum` 标准格式）——
 * downloadApk 的「同长度即复用」对同长度坏字节会误信，哈希比对补上这个口子。
 * 资产地址仅接受 https（护栏 [checkUrl]；本地 ServerSocket 单测仅 http，
 * 经 enforceHttps 测试缝关闭并单独直测护栏）。
 * 私有仓库拉资产需 M7 设置页的 GitHub 只读令牌（无令牌时 API 404，报人话提示）。
 */
object OcrModelDownloader {

    /** 模型发布 tag：与 APK 版本发布分离（APK 更新频繁，模型基本不变） */
    const val RELEASE_TAG = "ocr-models-v1"

    /** 三件套文件名（与 [PpOcrEngine] companion 对齐，引擎 open 按这些名字读文件） */
    val MODEL_FILES = listOf(PpOcrEngine.DET_MODEL, PpOcrEngine.REC_MODEL, PpOcrEngine.DICT_FILE)

    /** 校验清单资产名 */
    const val SUMS_FILE = "SHA256SUMS"

    data class Asset(val name: String, val url: String, val size: Long)

    /**
     * 按 tag 拉发布资产清单（模型发布不是 latest——APK 版本更常发，latest 会指错）。
     * 404 返回 null（未发布）；其余网络/状态失败抛 [UpdateException]。
     */
    fun fetchModelRelease(
        apiBase: String = UpdateChecker.DEFAULT_API_BASE,
        repo: String = UpdateChecker.REPO,
        token: String?,
    ): Map<String, Asset>? {
        val conn = UpdateChecker.open("$apiBase/repos/$repo/releases/tags/$RELEASE_TAG", token)
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            return when (val code = conn.responseCode) {
                200 -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    parseAssets(text)
                }
                404 -> null
                401 -> throw UpdateException("GitHub 令牌无效或已过期，请在设置页更新令牌后重试")
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

    /** 解析 Release JSON → 文件名→资产表（纯函数供单测） */
    fun parseAssets(releaseJson: String): Map<String, Asset> {
        val obj = Json.parseToJsonElement(releaseJson).jsonObject
        val assets = obj["assets"] as? JsonArray ?: JsonArray(emptyList())
        return assets
            .mapNotNull { it as? JsonObject }
            .mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
                name to Asset(name, url, size)
            }
            .toMap()
    }

    /**
     * SHA256SUMS 行解析（纯函数供单测）：`<64位hex>␣␣<文件名>`（sha256sum 二空格标准，
     * 容错单空格）；sha256sum 二进制模式输出的文件名前导 `*`（Windows 常见）剥掉；
     * 非该形态的行跳过；hex 统一小写供比对。返回 文件名→哈希（下载编排按文件名查预期值）。
     */
    fun parseSha256Sums(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val m = Regex("^([0-9a-fA-F]{64})[ ]+(.+)$").find(line) ?: return@mapNotNull null
                m.groupValues[2].trim().removePrefix("*") to m.groupValues[1].lowercase()
            }
            .toMap()

    /** 资产地址护栏：仅 https（防中间人/防降级） */
    fun checkUrl(url: String) {
        if (!url.startsWith("https://")) {
            throw UpdateException("远端资产地址异常（非 https），已取消下载")
        }
    }

    /**
     * 下载编排：拉清单 → 取 SHA256SUMS → 逐文件 [UpdateChecker.downloadApk]（.part
     * 原子落盘+长度校验）→ SHA-256 比对（不符删文件抛错，防半截/坏字节冒充完整模型）。
     * [onProgress] (已完成文件数, 总文件数, 当前文件百分比)。幂等：已存在且哈希吻合的
     * 文件跳过下载直接通过（downloadApk 同长度复用 + 哈希复核）。
     */
    fun downloadModels(
        apiBase: String = UpdateChecker.DEFAULT_API_BASE,
        repo: String = UpdateChecker.REPO,
        token: String?,
        modelsDir: File,
        enforceHttps: Boolean = true,
        onProgress: (doneFiles: Int, totalFiles: Int, filePct: Int) -> Unit = { _, _, _ -> },
    ) {
        val assets = fetchModelRelease(apiBase, repo, token)
            ?: throw UpdateException("模型发布（$RELEASE_TAG）不存在，请稍后重试；持续失败请联系开发者")
        // 校验清单：缺失时降级为仅长度校验（记 warn 不阻断——清单与文件同步发布，正常恒在）
        val sums = assets[SUMS_FILE]?.let { sumsAsset ->
            if (enforceHttps) checkUrl(sumsAsset.url)
            val tmp = File.createTempFile("sha", ".sums")
            try {
                UpdateChecker.downloadApk(sumsAsset.url, token, tmp, sumsAsset.size) { }
                parseSha256Sums(tmp.readText())
            } finally {
                tmp.delete()
            }
        } ?: emptyMap()
        modelsDir.mkdirs()
        val total = MODEL_FILES.size
        MODEL_FILES.forEachIndexed { idx, name ->
            val asset = assets[name]
                ?: throw UpdateException("模型发布缺少 $name，请稍后重试（发布可能不完整）")
            if (enforceHttps) checkUrl(asset.url)
            val dest = File(modelsDir, name)
            // 已存在且哈希吻合 → 跳过（重复下载幂等；哈希不符会重下）
            if (dest.exists() && sums[name]?.let { sha256File(dest) == it } == true) {
                onProgress(idx + 1, total, 100)
                return@forEachIndexed
            }
            UpdateChecker.downloadApk(asset.url, token, dest, asset.size) { pct ->
                onProgress(idx + 1, total, pct)
            }
            val expected = sums[name]
            if (expected != null && sha256File(dest) != expected) {
                dest.delete()
                throw UpdateException("模型文件校验失败（$name 与发布清单不符），已删除不完整文件，请重试")
            }
            onProgress(idx + 1, total, 100)
        }
    }

    /** 流式 SHA-256（模型 rec 16MB，不整读内存） */
    fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
