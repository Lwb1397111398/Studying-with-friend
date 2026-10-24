package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.importer.pdfpipeline.ExtractedFigure
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** KIT 图包 figures 清单条目（kit.json figures[] 一行；stagingFile=read 时解压落位） */
data class KitFigureMeta(
    val pageNo: Int,
    val rect: List<Float>, // [左,上,右,下] 页面比例（电脑侧 PDF 图块精确坐标）
    val kind: String, // photo / table / diagram
    val title: String,
    val desc: String,
    val file: String, // zip 内相对路径 figures/xxx.png
    val widthPx: Int, // 清单可缺（旧包）——0 时 FigureRow 走 4:3 占位比
    val heightPx: Int,
    val md5: String = "",
    val stagingFile: File? = null,
)

/** KitZipReader.read 产物：元数据 + 正文 + 已解压图清单 */
data class KitZipData(
    val meta: KitMeta,
    val text: String, // book.txt 全文（未剥元数据块，调用方统一 stripMeta）
    val figures: List<KitFigureMeta>,
)

/**
 * bookocr-kit KIT 图包（书名_Kit图包.zip）读取器：kit.json（kit:1 章表 + figures 清单）
 * + book.txt + figures 目录下的真图 png。图解压到 cacheDir 暂存目录（staging 语义与 P4
 * 幸存图一致，confirmImport 经 importBook 挪入 filesDir/figures/{bookId}/）。
 * 协议不认/损坏 → null，调用方回人话错误——zip 是图文混排载体，不静默降级成纯文字。
 */
object KitZipReader {

    /** 读图包字节。[stagingDir] 由调用方清建。 */
    fun read(bytes: ByteArray, stagingDir: File): KitZipData? {
        val entries = HashMap<String, File>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val out = safeTarget(stagingDir, e.name)
                        if (out != null) {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { o -> zis.copyTo(o) }
                            entries[e.name] = out
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return null
        }
        val kitJson = entries["kit.json"]?.readText()?.let(::stripBom) ?: return null
        val bookTxt = entries["book.txt"]?.readText()?.let(::stripBom) ?: return null
        val obj = try {
            JSONObject(kitJson)
        } catch (e: Exception) {
            return null
        }
        val meta = KitBookParser.parseMeta(obj) ?: return null

        val figs = mutableListOf<KitFigureMeta>()
        val arr = obj.optJSONArray("figures") ?: org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val page = o.optInt("page", 0)
            val file = o.optString("file", "")
            val staging = entries[file]
            val rectArr = o.optJSONArray("rect")
            val rect = mutableListOf<Float>()
            if (rectArr != null && rectArr.length() == 4) {
                for (j in 0 until 4) rect.add(rectArr.optDouble(j).toFloat())
            }
            // 半包容错：缺图文件/坏记录剔除该图（正文图题描述段仍在，只是不显真图）
            if (page < 1 || staging == null || rect.size != 4) continue
            figs.add(
                KitFigureMeta(
                    pageNo = page, rect = rect,
                    kind = o.optString("kind", "photo"),
                    title = o.optString("title", ""), desc = o.optString("desc", ""),
                    file = file,
                    widthPx = o.optInt("w", 0), heightPx = o.optInt("h", 0),
                    md5 = md5Of(staging), stagingFile = staging,
                ),
            )
        }
        return KitZipData(meta, bookTxt, figs)
    }

    /** zip entry 落位：防路径穿越（绝对路径/.. 一律拒），只收 stagingDir 内相对路径 */
    private fun safeTarget(stagingDir: File, name: String): File? {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.split("/").any { it == ".." }) return null
        val f = File(stagingDir, normalized)
        val root = stagingDir.canonicalPath + File.separator
        return if (f.canonicalPath.startsWith(root)) f else null
    }

    private fun stripBom(s: String): String = if (s.startsWith("﻿")) s.substring(1) else s

    private fun md5Of(f: File): String = try {
        MessageDigest.getInstance("MD5").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }
}

/**
 * KIT 图清单 ↔ KitBookParser 段落配对：电脑侧 pack 把图占位行按图在页面里的纵向位置写进
 * 正文（每页第 k 个〔图：…〕段 = 该页 y 序第 k 张图），App 端 parasOfKit 按行序产 FIGURE
 * 段——两侧顺序天然同源。ordAfterPara=该 FIGURE 段在页内段落列表的下标，buildReadRows
 * 据此把真图行插到图题描述段之后（图文原位混排）。该页无 FIGURE 段（图版页正文被 thin
 * 处理等）ord=-1，buildReadRows 兜底插页首/章首。
 */
object KitFigureMatcher {

    fun match(chapters: List<ParsedChapter>, figures: List<KitFigureMeta>): List<ExtractedFigure> {
        val paraCountByPage = HashMap<Int, Int>()
        val figSlotsByPage = HashMap<Int, MutableList<Int>>()
        for (ch in chapters) {
            for (p in ch.paras) {
                val pg = p.pageNo ?: continue
                val idx = paraCountByPage[pg] ?: 0
                paraCountByPage[pg] = idx + 1
                if (p.role == DbValues.ROLE_FIGURE) {
                    figSlotsByPage.getOrPut(pg) { mutableListOf() }.add(idx)
                }
            }
        }
        val taken = HashMap<Int, Int>()
        val out = mutableListOf<ExtractedFigure>()
        for (f in figures.asSequence()
            .filter { it.stagingFile != null }
            .sortedWith(compareBy({ it.pageNo }, { it.rect.getOrElse(1) { 0f } }))) {
            val k = (taken[f.pageNo] ?: 0).also { taken[f.pageNo] = it + 1 }
            val ord = figSlotsByPage[f.pageNo]?.getOrNull(k) ?: -1
            val seq = out.size + 1
            out.add(
                ExtractedFigure(
                    pageNo = f.pageNo,
                    ordAfterPara = ord,
                    bboxY0 = f.rect.getOrElse(1) { 0f },
                    seqNo = seq,
                    widthPx = f.widthPx,
                    heightPx = f.heightPx,
                    format = "png",
                    stagingFile = f.stagingFile!!,
                    finalName = "p${f.pageNo}_f${seq}.png",
                    md5 = f.md5,
                ),
            )
        }
        return out
    }
}
