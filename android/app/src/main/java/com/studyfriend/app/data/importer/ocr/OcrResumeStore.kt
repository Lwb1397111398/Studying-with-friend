package com.studyfriend.app.data.importer.ocr

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 扫描书 Pass1 断点续跑存储（2026-10-11 老板实测：识别几十分钟手机黑屏被系统杀进程，
 * 全部进度只在内存、必须从头来）。每页识别完（含规则 a/b 后处理、坐标已转 pt）立即
 * 原子落盘一个小 JSON；重导同一文件时已落盘页直接复用、跳过渲染+推理——缓存口径 =
 * Pass1 出口，与内存态逐字段等价，Pass2 无感知。
 *
 * 目录键 = uriHash_版本标签：识别管线大版本变更（TextSourceRow.PROD_OCR_V1 bump）时
 * 旧缓存自动失效。写入失败（磁盘满等）只降级为「本次无续跑」，不阻断导入。
 * 取消/异常路径不清目录（正是续跑的意义）；整轮成功收尾才清。
 */
object OcrResumeStore {

    private const val DIR = "ocr_resume"

    /** 缓存行所属识别版本：识别规则/模型大改时 bump，旧缓存整目录作废 */
    private const val VERSION_TAG = "v1"

    /**
     * 续跑目录：uriHash 与 [com.studyfriend.app.data.vision.VisionCache.uriHash] 同口径
     * （uri.hashCode 十六进制），自持实现避免 importer 依赖 vision 包。
     */
    fun dirFor(filesDir: File, uri: String): File =
        File(filesDir, "$DIR/${uri.hashCode().toString(16)}_$VERSION_TAG")

    /** 懒清理：删掉当前书之外的全部续跑目录（每本约 1MB 文本，防无限堆积） */
    fun pruneOther(filesDir: File, current: File) {
        val root = File(filesDir, DIR)
        root.listFiles()?.forEach { if (it.absolutePath != current.absolutePath) it.deleteRecursively() }
    }

    /** 已落盘页号全集（排序）；目录不存在返回空集 */
    fun completedPages(dir: File): Set<Int> {
        val pages = sortedSetOf<Int>()
        dir.listFiles()?.forEach { f ->
            f.name.removePrefix("page_").removeSuffix(".json").toIntOrNull()?.let { pages.add(it) }
        }
        return pages
    }

    /** 单页原子落盘（tmp + rename，进程中途被杀不会留下半个文件）；失败返回 false 当无缓存 */
    fun save(dir: File, pageNo: Int, lines: List<OcrLine>, separatorY: Float): Boolean = try {
        dir.mkdirs()
        val tmp = File(dir, "page_%04d.json.tmp".format(pageNo))
        tmp.writeText(encode(lines, separatorY))
        val dst = File(dir, "page_%04d.json".format(pageNo))
        if (!tmp.renameTo(dst)) {
            dst.delete()
            tmp.renameTo(dst)
        }
        true
    } catch (e: Exception) {
        false
    }

    /** null=无缓存或文件损坏（损坏文件顺带删除，防每次重导都读坏） */
    fun load(dir: File, pageNo: Int): Pair<List<OcrLine>, Float>? {
        val f = File(dir, "page_%04d.json".format(pageNo))
        if (!f.isFile) return null
        // decode 自吞异常返回 null，读不出有效数据即视为损坏文件删除（readText 的 IO 异常同样处理）
        return decode(f.readText()) ?: run {
            f.delete()
            null
        }
    }

    /** 整轮成功收尾：删掉该书续跑目录 */
    fun clear(dir: File) {
        dir.deleteRecursively()
    }

    // ---- JSON 编解码（kotlinx.serialization，纯 JVM 可测）----

    internal fun encode(lines: List<OcrLine>, separatorY: Float): String = buildJsonObject {
        // JSON 无 NaN：未检出分隔线存缺省，decode 回 NaN
        if (!separatorY.isNaN()) put("sepY", separatorY)
        put("lines", buildJsonArray {
            lines.forEach { l ->
                add(buildJsonObject {
                    put("t", l.text)
                    put("x0", l.x0)
                    put("y0", l.y0)
                    put("x1", l.x1)
                    put("y1", l.y1)
                    put("c", l.confidence)
                })
            }
        })
    }.toString()

    internal fun decode(json: String): Pair<List<OcrLine>, Float>? = try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val sepY = obj["sepY"]?.jsonPrimitive?.floatOrNull ?: Float.NaN
        val lines = obj.getValue("lines").jsonArray.map { l ->
            val o = l.jsonObject
            OcrLine(
                text = o.getValue("t").jsonPrimitive.content,
                x0 = o.getValue("x0").jsonPrimitive.float,
                y0 = o.getValue("y0").jsonPrimitive.float,
                x1 = o.getValue("x1").jsonPrimitive.float,
                y1 = o.getValue("y1").jsonPrimitive.float,
                confidence = o.getValue("c").jsonPrimitive.float,
            )
        }
        lines to sepY
    } catch (e: Exception) {
        null
    }
}
