package com.studyfriend.app.data.vision

import android.content.Context
import com.studyfriend.app.data.importer.pdfpipeline.PageTranscription
import com.studyfriend.app.data.importer.pdfpipeline.TranscriptionJson
import java.io.File

/**
 * 视觉转写落盘缓存（OPT-F 从 ImportViewModel 抽出共用）：导入同步路径、后台队列
 * Worker、整书重建三处共用同一份缓存。文件名规则保持与 OPT-E 一致
 * （vision_{uri hash}_{页号}.json），旧书重导/后台续跑天然命中，不重复烧钱。
 */
object VisionCache {

    /** uri → 缓存文件名哈希（P3b-1 目录缓存共用此算法，禁止复制实现） */
    fun uriHash(uri: String): String = uri.hashCode().toString(16)

    fun file(context: Context, uri: String, pageNo: Int): File =
        File(context.cacheDir, "vision_${uriHash(uri)}_$pageNo.json")

    /** 未命中/损坏一律返回 null（调用方按未转写处理） */
    fun read(context: Context, uri: String, pageNo: Int): PageTranscription? =
        try {
            val f = file(context, uri, pageNo)
            if (f.exists()) TranscriptionJson.parse(f.readText()) else null
        } catch (e: Exception) {
            null
        }

    /** 落盘失败不影响主流程（下次重新转写，只是多花一次调用） */
    fun write(context: Context, uri: String, pageNo: Int, t: PageTranscription) {
        runCatching { file(context, uri, pageNo).writeText(TranscriptionJson.encode(t)) }
    }
}
