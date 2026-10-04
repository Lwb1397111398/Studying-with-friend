package com.studyfriend.app.data.importer.pdfpipeline

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.Log
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDInlineImage
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.reflect.KMutableProperty0

/**
 * P4 示意图保留——两阶段图片提取器（阶段 1 元数据零解码过滤 / 阶段 2 仅幸存者解码落盘）。
 *
 * == commit B1 引擎 PoC 结论（通过，2026-10-03）==
 * pdfbox-android tom-roush fork 2.0.27.0（jar 反编译探查 + `PdfFigureEnginePocTest` 运行期验证）：
 *  (a) `PDFGraphicsStreamEngine` 存在且自带 `drawImage(PDImage)` 回调——**直接采用**，
 *      Form 递归（showForm）与 CTM 栈由基类处理，免自写（计划案 (a) 主路线成立）；
 *  (b) `Do` 拦截可达：drawImage 回调收到 `PDImageXObject`（PocTest formNested 用例断言）；
 *  (c) `getGraphicsState().currentTransformationMatrix` 在回调内可达（PocTest 实测）；
 *  (d) Form 双层嵌套内的图可达（PocTest processPage_formNested_imageReachable）；
 *  (e) CTM 内容级旋转探测：`FigureCoordMath.ctmToAffine` 已备，阶段 2 编码前转正；
 *      真书幸存图 CTM 正交性在 J2 E2E（模拟器导入）时经 Log.w 复核；
 *  (f) 跨页 CTM 不串页（PocTest noCrossPageCtmLeak：第 1 页泄漏 cm 2x 末态下回调
 *      CTM.scaleX=16f=2×内部 8，第 2 页回调仅含内部因子=8f）——引擎逐页独立，
 *      无需手动 reset；页级 ImageMeta 集合由提取器每页开始时自行清空；
 *  (g) `PDOptionalContentGroup` 类存在（jar 探查）；基类 DrawObject 是否内建 OC 隐藏
 *      判定未确认——B2 残项：不支持则 `Log.w` 一次后忽略（隐藏层图照提，droppedOC 恒 0）；
 *  (h) `PDImageXObject.getImage(Rect, int)` 采样重载存在（jar 探查）——超大图可预采样；
 *      MAX_SOURCE_PIXELS 守卫优先（超限直接跳过不解码）。
 *  备选 2（`page.getContents()` 字节流独立解析 Do/cm）经 PocTest backupRoute 用例验证可行；
 *  主路线 (a) 成立，故不启用。
 *  已知行为：`PDPageContentStream.drawImage(x, y)` 内部叠加图片像素尺寸的 cm
 *  （8px 测试图 → ×8 因子），回调 CTM = 外部 CTM × 内部因子——提取器统一读
 *  graphics state 的 CTM，bbox 语义正确（PocTest 数值佐证）。
 *
 * 与计划案的偏差记录：P1-10「单实例跨页复用」因基类构造器绑定 PDPage（protected
 * PDFGraphicsStreamEngine(PDPage)）改为**每页新建引擎实例**（轻对象，开销可忽略）；
 * 单线程约束不变（v1.8，r8-P1-4）：`extract()` 为同步单线程调用——只在导入协程内
 * 串行执行一次，不暴露给并发调用方，graphics state 栈无线程安全诉求。
 */
class PdfFigureExtractor(private val stagingDir: File) {

    /** extract 产物：figures（已赋 seqNo、md5 已算）+ stats（parseNote 对账） */
    class FigureExtractResult(
        val figures: List<ExtractedFigure>,
        val stats: FigureExtractorStats,
    )

    /**
     * 两阶段主入口。
     * [pageParaY0s]：页号(1-based) → 该页全量段落（含 FRONT/TOC/FOOTNOTE）的段首行
     * y0 列表（crossPageMerge **之后**取，r9-P1-1，与 ReadScreen 渲染口径同源）。
     * [onProgress]：阶段 1 每 50 页回调（当前页号, 总页数）。
     * [pageRenderer]：解码失败兜底（r12-P1-1，null=不兜底，行为同旧版）。
     */
    fun extract(
        doc: PDDocument,
        pageParaY0s: Map<Int, List<Float>>,
        onProgress: (pageNo: Int, total: Int) -> Unit = { _, _ -> },
        pageRenderer: PageRenderer? = null,
        isCancelled: () -> Boolean = { false },
    ): FigureExtractResult {
        val stats = FigureExtractorStats()
        // 加密权限检查（v1.8，r8-P1-3）：限制提取的书直接跳过，图零提取不逐张撞权限
        if (doc.isEncrypted && !doc.currentAccessPermission.canExtractContent()) {
            stats.permDenied = true
            return FigureExtractResult(emptyList(), stats)
        }
        // 清上次残留（崩溃/异常/历史版本取消路径可能滞留 cacheDir），再开本轮暂存
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        try {
            val totalPages = doc.numberOfPages
            val metas = mutableListOf<FigureImageMeta>()

            // ---- 阶段 1：逐页引擎扫描，零解码只记元数据 ----
            for (p in 0 until totalPages) {
                if (isCancelled()) {
                    Log.w(TAG, "figure scan cancelled at p${p + 1}/$totalPages")
                    stagingDir.deleteRecursively() // 无幸存者产出，staging 即刻清
                    return FigureExtractResult(emptyList(), stats)
                }
                val page = doc.getPage(p)
                val pageNo = p + 1
                val engine = PageScanEngine(page, pageNo, metas, stats)
                try {
                    engine.processPage(page)
                } finally {
                    engine.release() // 兜底复位（正常路径 processPage 结束时已复位）
                }
                if (pageNo % PROGRESS_EVERY_PAGES == 0 || pageNo == totalPages) {
                    onProgress(pageNo, totalPages)
                }
            }
            // totalObjects 只计可提取候选图元（metas），「检测到 X 张」与 r1/r2/r3/kept 账目
            // 闭合；内联图不可提取、由 detail 行与比率 warn 独立展示（r12-QC3-P1）
            stats.totalObjects = metas.size

            val survivors = applyRules(metas, stats)

            // ---- 锚定 + seqNo（r9-P0-1：全书唯一序号防文件名碰撞；先排序后赋号）----
            val anchored = survivors
                .map { meta ->
                    val paraY0s = pageParaY0s[meta.pageNo] ?: emptyList()
                    meta.ordAfterPara = FigureCoordMath.anchorFigure(meta.disp.y, paraY0s)
                    meta
                }
                .sortedWith(
                    compareBy<FigureImageMeta> { it.pageNo }
                        .thenBy { it.ordAfterPara }
                        .thenBy { it.disp.y },
                )

            // ---- 阶段 2：仅幸存者解码、转正、降采样、编码、落 staging、算 md5 ----
            val figures = mutableListOf<ExtractedFigure>()
            var seqNo = 0
            for (meta in anchored) {
                if (isCancelled()) {
                    Log.w(TAG, "figure decode cancelled, kept=${figures.size}/${anchored.size}")
                    break
                }
                seqNo++
                // 解码失败 → 系统 PdfRenderer 整页渲染兜底（JBIG2 等移植版解不了的编码）
                val err0 = stats.errored
                val ov0 = stats.erroredOversize
                val pp0 = stats.erroredPerm
                val figure = decodeAndSave(meta, seqNo, stats)
                    ?: pageRenderer?.let { renderAndSave(it, meta, seqNo, stats) }
                if (figure != null) {
                    // 兜底救回即最终成功：撤销本次 decode 阶段的失败记账（含权限失败——
                    // pdfium 走独立 fd 不受 pdfbox 权限限制，救回即与 erroredPerm 矛盾）
                    rollbackIfIncreased(stats::errored, err0)
                    rollbackIfIncreased(stats::erroredOversize, ov0)
                    rollbackIfIncreased(stats::erroredPerm, pp0)
                    figures.add(figure)
                    stats.kept++
                    stats.keptPages.add(meta.pageNo)
                }
            }
            return FigureExtractResult(figures, stats)
        } finally {
            // staging 生命周期：此处**不清**——幸存图要等 confirmImport 才从 staging 挪进
            // figures/{bookId}/（r12-QC1），extract 返回≠消费完成；清理责任在：
            // ① 开头 deleteRecursively（清上次残留/取消残留）② 阶段1取消分支（无产出）
            // ③ PdfLoader 图片阶段 catch（extract 抛异常=无产出可安全清）
        }
    }

    // ---------------------------------------------------------------- 阶段 1 过滤

    /** R1→R2→R3（xref 型→内容型）依次过滤，返回幸存 meta（副作用：累计 stats） */
    private fun applyRules(
        metas: List<FigureImageMeta>,
        stats: FigureExtractorStats,
    ): MutableList<FigureImageMeta> {
        val afterR1 = mutableListOf<FigureImageMeta>()
        for (m in metas) {
            val (pw, ph) = m.dispPage
            when {
                isR1FullPageBleed(m.disp, pw, ph) -> stats.droppedR1++
                isR2Decoration(m.disp, pw, ph) -> stats.droppedR2++
                else -> afterR1.add(m)
            }
        }
        // R3 xref 型：同 xref ≥3 页且同位 → 滤
        val byXref = afterR1.groupBy { it.cosRef }
        val droppedXref = mutableSetOf<FigureImageMeta>()
        for ((_, group) in byXref) {
            val pages = group.map { it.pageNo }.distinct()
            if (pages.size >= PdfFigureThresholds.R3_MIN_PAGES) {
                val positions = group.map { it.normPos() }
                if (isR3RepeatingHeader(positions)) droppedXref.addAll(group)
            }
        }
        val afterXrefR3 = afterR1.filter { it !in droppedXref }
        stats.droppedR3Xref = droppedXref.size

        // R3 内容型（v1.9，r9-P2-6；v1.10 只对候选集算 md5，r10-P2-2）：
        // 同 rawMd5 ≥3 页且同位 → 滤（生成器常把同页眉图重复定义为不同 xref）
        val droppedContent = mutableSetOf<FigureImageMeta>()
        val byMd5 = HashMap<String, MutableList<FigureImageMeta>>()
        for (m in afterXrefR3) {
            val md5 = rawBytesMd5(m.image) ?: continue
            byMd5.getOrPut(md5) { mutableListOf() }.add(m)
        }
        for ((_, group) in byMd5) {
            val pages = group.map { it.pageNo }.distinct()
            if (pages.size >= PdfFigureThresholds.R3_MIN_PAGES) {
                val positions = group.map { it.normPos() }
                if (isR3RepeatingHeader(positions)) droppedContent.addAll(group)
            }
        }
        stats.droppedR3Content = droppedContent.size
        return afterXrefR3.filter { it !in droppedContent }.toMutableList()
    }

    // ---------------------------------------------------------------- 阶段 2 解码落盘

    /** 单图解码→CTM 转正→降采样→编码→staging 落盘→md5；失败返回 null（计 errored） */
    private fun decodeAndSave(
        meta: FigureImageMeta,
        seqNo: Int,
        stats: FigureExtractorStats,
    ): ExtractedFigure? {
        // 超大图守卫（v1.10，r10-P1-2）：全分辨率分配前拦截，不解码直接跳过
        if (meta.srcW.toLong() * meta.srcH > PdfFigureThresholds.MAX_SOURCE_PIXELS) {
            stats.erroredOversize++
            return null
        }
        val bmp = decodeWithFallback(meta.image, stats) ?: return null
        val upright = rotateUpright(bmp, meta, stats)
        // 转正创建的是新位图（恒等/非正交返回原引用）：原图立即可回收，不跨处理单元驻留
        if (upright !== bmp) bmp.recycle()
        return finalizeFigure(upright, meta, seqNo, stats)
    }

    /**
     * 渲染兜底（r12-P1-1）：系统 pdfium 整页渲染 → 显示 bbox 裁剪 → 复用编码落盘。
     * pdfium 输出即折算显示空间（顶左原点、CTM 已摆好），**跳过 rotateUpright**
     * （再转正会双重旋转）；渲染比例按显示 bbox 宽达 [PdfFigureThresholds.TARGET_WIDTH_MIN]，
     * 整页像素预算压在 [PdfFigureThresholds.MAX_SOURCE_PIXELS] 内。失败返回 null
     * （decode 阶段的失败记账保持，由调用方决定是否撤销）。
     */
    private fun renderAndSave(
        render: PageRenderer,
        meta: FigureImageMeta,
        seqNo: Int,
        stats: FigureExtractorStats,
    ): ExtractedFigure? {
        val (pw, ph) = meta.dispPage
        if (pw <= 0f || ph <= 0f || meta.disp.w <= 0f || meta.disp.h <= 0f) return null
        var scale = (PdfFigureThresholds.TARGET_WIDTH_MIN / meta.disp.w).coerceAtLeast(0.5f)
        val maxScale = sqrt(
            PdfFigureThresholds.MAX_SOURCE_PIXELS.toDouble() / (pw.toDouble() * ph),
        ).toFloat()
        if (scale > maxScale) scale = maxScale
        // 单张耗时留证（r12-QC4-P2）：J8 兜底渲染单张耗时暂 UNMEASURED，此日志使
        // 下一次真机导入自动留下毫秒级证据（logcat grep "fallback p"）
        val startedNs = System.nanoTime()
        val pageBmp = try {
            render.renderPage(meta.pageNo, meta.disp, scale)
        } catch (e: Exception) {
            Log.w(TAG, "render fallback p${meta.pageNo} failed (${(System.nanoTime() - startedNs) / 1_000_000}ms)", e)
            null
        } ?: return null
        // 裁剪显示 bbox 区域（px = pt × scale，clamp 页内防边界舍入越界）
        val left = (meta.disp.x * scale).roundToInt().coerceIn(0, pageBmp.width - 1)
        val top = (meta.disp.y * scale).roundToInt().coerceIn(0, pageBmp.height - 1)
        val w = (meta.disp.w * scale).roundToInt().coerceAtMost(pageBmp.width - left)
        val h = (meta.disp.h * scale).roundToInt().coerceAtMost(pageBmp.height - top)
        if (w <= 0 || h <= 0) {
            pageBmp.recycle()
            return null
        }
        val cropped = Bitmap.createBitmap(pageBmp, left, top, w, h)
        if (cropped != pageBmp) pageBmp.recycle()
        stats.fallbackRendered++
        Log.i(TAG, "fallback p${meta.pageNo} took ${(System.nanoTime() - startedNs) / 1_000_000}ms (w=${w} h=${h})")
        return finalizeFigure(cropped, meta, seqNo, stats)
    }

    /** 降采样（>TARGET_WIDTH_MIN 时）→ 编码 → staging 落盘 → md5 → ExtractedFigure */
    private fun finalizeFigure(
        bmp0: Bitmap,
        meta: FigureImageMeta,
        seqNo: Int,
        stats: FigureExtractorStats,
    ): ExtractedFigure? {
        var bmp = bmp0
        try {
            if (bmp.width > PdfFigureThresholds.TARGET_WIDTH_MIN) {
                val newH = (bmp.height.toFloat() * PdfFigureThresholds.TARGET_WIDTH_MIN / bmp.width).roundToInt()
                val scaled = Bitmap.createScaledBitmap(
                    bmp, PdfFigureThresholds.TARGET_WIDTH_MIN, newH.coerceAtLeast(1), true,
                )
                if (scaled != bmp) bmp.recycle()
                bmp = scaled
            }
            val format = if (hasMeaningfulAlpha(bmp)) "png" else "jpg"
            val finalName = "p${meta.pageNo}_f$seqNo.$format"
            val file = File(stagingDir, finalName)
            val md5 = encodeAndHash(bmp, file, format)
            if (md5 == null) {
                stats.errored++
                return null
            }
            return ExtractedFigure(
                pageNo = meta.pageNo,
                ordAfterPara = meta.ordAfterPara,
                bboxY0 = meta.disp.y,
                seqNo = seqNo,
                widthPx = bmp.width,
                heightPx = bmp.height,
                format = format,
                stagingFile = file,
                finalName = finalName,
                md5 = md5,
            )
        } catch (e: Exception) {
            Log.w(TAG, "figure p${meta.pageNo} encode failed", e)
            stats.errored++
            return null
        } finally {
            bmp?.recycle() // 平台类型防御：createScaledBitmap 异常路径下 bmp 可能未赋新值
        }
    }

    /** getImage + CMYK/色彩空间兜底（r6-P2-1c）+ 权限异常单计（r8-P1-3） */
    private fun decodeWithFallback(image: PDImageXObject, stats: FigureExtractorStats): Bitmap? {
        try {
            return image.image
        } catch (e: SecurityException) {
            Log.w(TAG, "perm denied: ${e.message}")
            stats.erroredPerm++
            return null
        } catch (e: Exception) {
            // 强制 RGB 色彩空间再解一次（CMYK/ICC 异常色空间的兜底），仍失败才计入 errored
            try {
                image.colorSpace = PDDeviceRGB.INSTANCE
                return image.image
            } catch (e2: Exception) {
                Log.w(TAG, "decode failed after RGB fallback: ${e2.message}")
                stats.errored++
                return null
            }
        }
    }

    /**
     * CTM 90° 族旋转/镜像转正（r5-P1-1 + r8-P2-8）：文件里就是正向图，阅读页零渲染成本。
     * 方向矩阵只取 CTM 的符号（缩放因子交给降采样，避免均匀缩放 CTM 放大位图浪费内存）；
     * 非正交 CTM（ctmToAffine→null）维持原始方向并记录页号供 parseNote 提示。
     */
    private fun rotateUpright(src: Bitmap, meta: FigureImageMeta, stats: FigureExtractorStats): Bitmap {
        val aff = FigureCoordMath.ctmToAffine(
            meta.ctmA, meta.ctmB, meta.ctmC, meta.ctmD, meta.ctmE, meta.ctmF,
            srcW = meta.srcW, srcH = meta.srcH,
        )
        if (aff == null) {
            if (stats.nonOrthoCtmPages.add(meta.pageNo)) {
                Log.w(TAG, "p${meta.pageNo} skewed CTM, keep orientation")
            }
            return src
        }
        val sx = sign(aff.scaleX)
        val skx = sign(aff.skewX)
        val sky = sign(aff.skewY)
        val sy = sign(aff.scaleY)
        val identity = sx == 1f && sy == 1f && skx == 0f && sky == 0f && !aff.flip
        if (identity) return src
        val m = Matrix()
        m.setValues(floatArrayOf(sx, skx, 0f, sky, sy, 0f, 0f, 0f, 1f))
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } catch (e: Exception) {
            // createBitmap 极端 float 精度下可抛 IAE：退回原图维持原方向，src 交还调用方
            // 按恒等路径回收。不能 finally recycle——恒等路径返回的就是 src 本身（r12-QC3-P0）
            Log.w(TAG, "p${meta.pageNo} rotate failed, keep orientation: ${e.message}")
            src
        }
    }

    private fun sign(v: Float): Float = when {
        abs(v) < 0.5f -> 0f
        v > 0f -> 1f
        else -> -1f
    }

    /** 兜底救回即最终成功：decode 阶段多记的失败计数回退到快照（r12-QC4-P3 去重三处同型 if） */
    private fun rollbackIfIncreased(prop: KMutableProperty0<Int>, snapshot: Int) {
        if (prop.get() > snapshot) prop.set(snapshot)
    }

    /**
     * 真 alpha 检测（B2 实测口径）：fork getImage() 恒返回 ARGB_8888（hasAlpha() 恒 true），
     * 但 SMask 图在解码层即失败——能解出的图 alpha 恒全 255，全走 JPG（体积 ~1/10）；
     * 抽样 5 点（四角+中心）防 fork 未来支持 SMask 后透明图被 JPG 抹掉。
     */
    private fun hasMeaningfulAlpha(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val points = listOf(
            0 to 0,
            b.width - 1 to 0,
            0 to b.height - 1,
            b.width - 1 to b.height - 1,
            b.width / 2 to b.height / 2,
        )
        return points.any { (x, y) -> (b.getPixel(x, y) ushr 24) != 0xFF }
    }

    /** 编码落盘并对落盘字节算 md5（hex，小写）；IO 失败返回 null */
    /**
     * 编码落 staging 并算 md5。md5 对 **staging 文件字节**计算：importBook/重挂挪移用
     * renameTo（同分区原子改名，字节不变）或 copyTo（字节复制），filesDir 最终文件
     * 字节与 staging 一致，故此 md5 等价于最终落盘文件摘要（r12-QC2 声明）。
     */
    private fun encodeAndHash(bmp: Bitmap, file: File, format: String): String? {
        return try {
            FileOutputStream(file).use { out ->
                val ok = if (format == "png") {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                } else {
                    bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                check(ok) { "compress returned false" }
            }
            val digest = MessageDigest.getInstance("MD5").digest(file.readBytes())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "encode failed: ${e.message}")
            file.delete()
            null
        }
    }

    private fun rawBytesMd5(image: PDImageXObject): String? = try {
        val digest = MessageDigest.getInstance("MD5").digest(image.stream.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.w(TAG, "raw md5 failed: ${e.message}")
        null
    }

    // ---------------------------------------------------------------- 扫描引擎

    /**
     * 单页扫描引擎（阶段 1）：drawImage 回调里只读元数据，绝不调 getImage()。
     * Form 递归由基类 showForm 处理，此处覆写加深度上限 50（r7-P2-1）。
     */
    private class PageScanEngine(
        page: PDPage,
        private val pageNo: Int,
        private val out: MutableList<FigureImageMeta>,
        private val stats: FigureExtractorStats,
    ) : PDFGraphicsStreamEngine(page) {
        private var formDepth = 0
        private val pageRotation = page.rotation
        private val mediaBox: PDRectangle = page.mediaBox
        private val mbRect = PdfRect(
            mediaBox.lowerLeftX, mediaBox.lowerLeftY, mediaBox.upperRightX, mediaBox.upperRightY,
        )
        // 显示空间页宽高（/Rotate 90/270 时宽高互换）
        val dispPage: Pair<Float, Float> =
            if (pageRotation % 180 == 90) Pair(mediaBox.height, mediaBox.width)
            else Pair(mediaBox.width, mediaBox.height)

        /** processPage 异常中断时的兜底复位（正常路径 finally 亦调，幂等） */
        fun release() {
            formDepth = 0
        }

        override fun drawImage(pdImage: PDImage) {
            if (pdImage is PDInlineImage) {
                stats.inlineImageCount++
                stats.biPages[pageNo] = (stats.biPages[pageNo] ?: 0) + 1
                return
            }
            val img = pdImage as? PDImageXObject ?: return
            val ctm = getGraphicsState().currentTransformationMatrix
            // fork Matrix getter 语义：scaleX=a、shearY=b、shearX=c、scaleY=d（PDF cm [a b c d e f]）
            val a = ctm.scaleX
            val b = ctm.shearY
            val c = ctm.shearX
            val d = ctm.scaleY
            val e = ctm.translateX
            val f = ctm.translateY
            // Do 映射单位正方形 → user space 四顶点外接框
            val xs = listOf(e, a + e, a + c + e, c + e)
            val ys = listOf(f, b + f, b + d + f, d + f)
            val userRect = PdfRect(
                xs.minOrNull() ?: 0f, ys.minOrNull() ?: 0f, xs.maxOrNull() ?: 0f, ys.maxOrNull() ?: 0f,
            )
            val disp = FigureCoordMath.rotateRect(pageRotation, userRect, mbRect)
            out.add(
                FigureImageMeta(
                    // 加载态下同一间接图像对象 = 同一 COSStream 实例（COSStream 无 equals 覆写，
                    // 引用相等即 xref 相等语义；fork 不暴露文档内 xref 编号，写时才分配）
                    cosRef = img.stream.cosObject,
                    pageNo = pageNo,
                    disp = disp,
                    dispPage = dispPage,
                    srcW = img.width,
                    srcH = img.height,
                    image = img,
                    ctmA = a, ctmB = b, ctmC = c,
                    ctmD = d, ctmE = e, ctmF = f,
                ),
            )
        }

        override fun showForm(form: PDFormXObject) {
            if (formDepth >= FORM_DEPTH_LIMIT) {
                stats.droppedFormDepth++
                return
            }
            formDepth++
            try {
                super.showForm(form)
            } finally {
                formDepth--
            }
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {}
        override fun clip(p0: android.graphics.Path.FillType) {}
        override fun moveTo(p0: Float, p1: Float) {}
        override fun lineTo(p0: Float, p1: Float) {}
        override fun curveTo(p0: Float, p1: Float, p2: Float, p3: Float, p4: Float, p5: Float) {}
        override fun getCurrentPoint(): PointF = PointF(0f, 0f)
        override fun closePath() {}
        override fun endPath() {}
        override fun strokePath() {}
        override fun fillPath(p0: android.graphics.Path.FillType) {}
        override fun fillAndStrokePath(p0: android.graphics.Path.FillType) {}
        override fun shadingFill(p0: COSName) {}
    }

    companion object {
        private const val TAG = "PdfFigureExtractor"
        private const val FORM_DEPTH_LIMIT = 50
        private const val JPEG_QUALITY = 90
        private const val PROGRESS_EVERY_PAGES = 50

        init {
            // OC 可选内容层（(g) 残项）：fork 未暴露隐藏层判定 API——隐藏层图照提，
            // 只在首个实例加载时 Log.w 一次登记，droppedOC 恒 0（J2 容差 ±1 承接）
            Log.w(TAG, "optional-content hidden layers not detectable on this fork; such images are kept")
        }
    }
}

/** 阶段 1 单图元数据（零解码）；image 引用仅用于阶段 2 解码与 rawMd5，不持有像素 */
internal class FigureImageMeta(
    /** 图像流 COSStream 实例（引用相等即同 xref 语义，R3 xref 型分组键） */
    val cosRef: com.tom_roush.pdfbox.cos.COSStream,
    val pageNo: Int,
    val disp: DispBbox,
    val dispPage: Pair<Float, Float>,
    val srcW: Int,
    val srcH: Int,
    val image: PDImageXObject,
    val ctmA: Float,
    val ctmB: Float,
    val ctmC: Float,
    val ctmD: Float,
    val ctmE: Float,
    val ctmF: Float,
) {
    var ordAfterPara: Int = -1

    /** 归一化位置（R3 同位判定）：x0/y0/宽 相对显示页宽高 */
    fun normPos(): NormPos = NormPos(
        disp.x / dispPage.first,
        disp.y / dispPage.second,
        disp.w / dispPage.first,
    )
}

/** 阶段 2 产物（commit C 落库 FigureEntity 的全部数据源） */
data class ExtractedFigure(
    val pageNo: Int,
    val ordAfterPara: Int,
    val bboxY0: Float,
    val seqNo: Int,
    val widthPx: Int,
    val heightPx: Int,
    /** "png" | "jpg" */
    val format: String,
    /** cacheDir/figures_staging/ 临时文件；importBook 成功后移入 filesDir/figures/{bookId}/ */
    val stagingFile: File,
    /** 正式文件名（figures/{bookId}/ 下）：p{pageNo}_f{seqNo}.{format} */
    val finalName: String,
    /** 落盘字节 md5（hex 小写） */
    val md5: String,
)

/** 提取统计（parseNote 双层文案的对账数据） */
class FigureExtractorStats {
    var totalObjects = 0
    var kept = 0
    var droppedR1 = 0
    var droppedR2 = 0
    var droppedR3Xref = 0
    var droppedR3Content = 0
    var errored = 0
    var erroredPerm = 0
    var erroredOversize = 0
    var inlineImageCount = 0

    /** 页号 → 内联图出现次数（parseNote 技术明细：p12 含 3 处内联图，≤5 页全列） */
    val biPages = mutableMapOf<Int, Int>()
    var droppedFormDepth = 0

    /** 本 pdfbox fork 未暴露 OC 隐藏层（Optional Content）判定，恒 0；P6 版面模型支持时升格（B2 残项，Log.w 登记） */
    var droppedOC = 0

    /** 整书权限拒绝（入口跳过；parseNote 主文案改「该书限制图片提取」） */
    var permDenied = false

    /** 阶段 1 运行期异常（PdfLoader 层 catch 后置位；figures 返回空、文本正常落库） */
    var fatalError = false

    /** 非正交 CTM（旋转/镜像图维持原始方向）的页号集合 */
    val nonOrthoCtmPages = mutableSetOf<Int>()

    /** 有幸存图的页号集合（parseNote「示意图位于 p…」与低质量分支提示共用） */
    val keptPages = mutableSetOf<Int>()

    /** 系统 PdfRenderer 兜底救回的张数（r12-P1-1：pdfbox(Android) 缺 JBIG2 filter 等解码失败） */
    var fallbackRendered = 0
}

/**
 * 系统 PdfRenderer 整页渲染兜底接口（r12-P1-1）：pdfbox(Android 移植) 的 FilterFactory
 * 未注册 JBIG2Filter（jar 反编译核实），JBIG2 编码幸存图全部解码失败——tom-roush 无
 * 现成开关（README 仅 JPX 有可选库），标准 jbig2-imageio 又依赖 Android 不存在的
 * javax.imageio。故对解码失败的幸存图改用系统 pdfium（支持全部 PDF 滤波器）整页渲染后
 * 按显示 bbox 裁剪。
 *
 * [pageNo] 1-based；[disp] 折算显示空间 bbox（顶左原点，/Rotate 已折算）；
 * [scale] 渲染比例（pt→px），由提取器按「目标宽 + 整页像素预算」算好传入。
 * 返回整页位图（宽 = ceil(页宽×scale)），实现方负责异常转 null。
 */
fun interface PageRenderer {
    fun renderPage(pageNo: Int, disp: DispBbox, scale: Float): Bitmap?
}
