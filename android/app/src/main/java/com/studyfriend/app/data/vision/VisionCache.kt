package com.studyfriend.app.data.vision

import android.content.Context
import com.studyfriend.app.data.importer.pdfpipeline.PageTranscription
import com.studyfriend.app.data.importer.pdfpipeline.TranscriptionJson
import java.io.File

/**
 * 视觉转写落盘缓存（OPT-F 从 ImportViewModel 抽出共用）：导入同步路径、后台队列
 * Worker、整书重建三处共用同一份缓存。文件名规则保持与 OPT-E 一致
 * （vision_{uri hash}_{页号}.json），旧书重导/后台续跑天然命中，不重复烧钱。
 *
 * 存放位置 filesDir/vision_cache/（v1.1 起）：每页转写是约 1 分钟的付费成果，
 * cacheDir 会被系统存储紧张时清空、用户「清除缓存」也会清——清了就意味着整本
 * 重导重烧一遍钱，付费产物不能放临时仓库。历史 cacheDir 文件在 read 未命中时
 * 惰性搬迁（老书升级后第一次重读自动恢复，不丢已烧的成果）。
 */
object VisionCache {

    private const val DIR = "vision_cache"

    /** uri → 缓存文件名哈希（P3b-1 目录探针缓存共用此算法，禁止复制实现） */
    fun uriHash(uri: String): String = uri.hashCode().toString(16)

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    fun file(context: Context, uri: String, pageNo: Int): File =
        File(dir(context), "vision_${uriHash(uri)}_$pageNo.json")

    /** v1.0 时代的 cacheDir 旧路径：仅迁移读取用 */
    private fun legacyFile(context: Context, uri: String, pageNo: Int): File =
        File(context.cacheDir, "vision_${uriHash(uri)}_$pageNo.json")

    /** 未命中/损坏一律返回 null（调用方按未转写处理）。旧 cacheDir 命中时搬到 filesDir */
    fun read(context: Context, uri: String, pageNo: Int): PageTranscription? =
        try {
            val f = file(context, uri, pageNo)
            if (f.exists()) TranscriptionJson.parse(f.readText())
            else {
                val old = legacyFile(context, uri, pageNo)
                if (!old.exists()) null
                else {
                    val t = TranscriptionJson.parse(old.readText())
                    if (t != null) runCatching { old.copyTo(f, overwrite = true) }
                    t
                }
            }
        } catch (e: Exception) {
            null
        }

    /** 落盘失败不影响主流程（下次重新转写，只是多花一次调用） */
    fun write(context: Context, uri: String, pageNo: Int, t: PageTranscription) {
        runCatching { file(context, uri, pageNo).writeText(TranscriptionJson.encode(t)) }
    }
}
