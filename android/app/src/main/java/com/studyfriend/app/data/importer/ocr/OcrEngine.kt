package com.studyfriend.app.data.importer.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil

/**
 * 单行识别结果。坐标为输入位图像素坐标（PdfPageRenderer 140dpi 渲染图，y 自顶向下），
 * pt 换算（×72/140）在 OcrPageSource adapter 执行（P6b S4，引擎不做单位决策）。
 * confidence = 该行 CTC 保留字符步概率均值（PaddleOCR 标准口径，0..1）。
 */
data class OcrLine(
    val text: String,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val confidence: Float,
)

/**
 * OCR 引擎接口（P6b S2）：open → recognize×N → close 三段式。
 * det/rec 双 session 生命周期 = 一次导入会话持有、结束 close（onnxruntime arena
 * ~208MB PSS 释放，P6a F9）；串行并发=1 由 OcrImportRunner 伴生对象 Mutex 保证
 * （决策案 §3.1 CPU 密集不宜并发），引擎本身非线程安全。
 * 接口化供 S6 单测 mock 识别路径（真模型 E2E 走 S7 模拟器）。
 */
interface OcrEngine : AutoCloseable {
    fun open(modelsDir: File)
    fun recognize(bitmap: Bitmap): List<OcrLine>
    override fun close()
}

/**
 * PP-OCRv5 mobile det+rec 生产引擎（P6a PoC 逻辑移植，OcrPocActivity 下架后以此为准）：
 * det 预处理 limit 960/32 取整/(x/255−0.5)/0.5 → det 推理 → 后处理（prob>0.3、
 * 8 连通 BFS、轴对齐框、扩张 25%、均值≥0.5 过滤）→ 框按 y 桶 24px+x 排序（阅读序）
 * → rec 批 8（高 48 等比缩放、批内定宽右侧补零，P6c Phase 1 动态宽）→ CTC greedy 解码（class 0=blank，dict[class−1]）。
 * 简化口径声明（P6a 判据③基于此）：det 后处理无 unclip/透视变换，轴对齐框+固定比例扩张。
 */
class PpOcrEngine(private val log: (String) -> Unit = {}) : OcrEngine {

    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var dict: List<String> = emptyList()

    override fun open(modelsDir: File) {
        val e = OrtEnvironment.getEnvironment()
        val t0 = System.currentTimeMillis()
        det = e.createSession(File(modelsDir, DET_MODEL).absolutePath)
        rec = e.createSession(File(modelsDir, REC_MODEL).absolutePath)
        dict = File(modelsDir, DICT_FILE).readText(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        env = e
        log("OcrEngine open ${System.currentTimeMillis() - t0}ms dict=${dict.size}")
    }

    override fun recognize(bitmap: Bitmap): List<OcrLine> {
        val e = env ?: error("OcrEngine 未 open（须先调 open(modelsDir)）")
        val d = det ?: error("OcrEngine 未 open（det session 缺失）")
        val r = rec ?: error("OcrEngine 未 open（rec session 缺失）")

        // ---- det 预处理：limit_side_len=960，取 32 倍数，(x/255-0.5)/0.5 RGB ----
        var rh = bitmap.height.toLong(); var rw = bitmap.width.toLong()
        val scale = 960.0 / maxOf(rh, rw)
        if (scale < 1.0) { rh = (rh * scale).toLong(); rw = (rw * scale).toLong() }
        rh = rh / 32 * 32; rw = rw / 32 * 32
        val H = rh.toInt(); val W = rw.toInt()
        val small = Bitmap.createScaledBitmap(bitmap, W, H, true)
        val px = IntArray(W * H)
        small.getPixels(px, 0, W, 0, 0, W, H)
        if (small !== bitmap) small.recycle()
        val chw = FloatArray(3 * H * W)
        val plane = H * W
        for (i in px.indices) {
            val p = px[i]
            chw[i] = (((p shr 16 and 0xFF) / 255f) - 0.5f) / 0.5f
            chw[plane + i] = (((p shr 8 and 0xFF) / 255f) - 0.5f) / 0.5f
            chw[2 * plane + i] = (((p and 0xFF) / 255f) - 0.5f) / 0.5f
        }

        // ---- det 推理 + 后处理（输入/输出 tensor 用完即 close，arena 不泄漏）----
        val detIn = OnnxTensor.createTensor(e, FloatBuffer.wrap(chw), longArrayOf(1, 3, rh, rw))
        val detOut = d.run(mapOf("x" to detIn))
        val prob = (detOut[0] as OnnxTensor).floatBuffer
        val boxes = OcrDetPost.extractBoxes(prob, H, W)
        detOut.close()
        detIn.close()

        // ---- rec：批 8，高 48 等比缩放 + 批内定宽右侧补零（P6c Phase 1 动态宽，
        //      对齐 PC replica_ocr.py rec_pre_dyn/norm_pad_dyn 口径），CTC greedy 解码 ----
        val sx = bitmap.width.toFloat() / W; val sy = bitmap.height.toFloat() / H
        val recH = 48
        val out = mutableListOf<OcrLine>()
        for (chunk in boxes.chunked(REC_BATCH)) {
            // 阶段一：逐框算裁剪矩形与等比目标宽（不缩放；坐标 coerce 与旧版逐行同）
            val rects = ArrayList<IntArray>(chunk.size) // [x0,y0,x1,y1] 原图像素坐标
            val rws = IntArray(chunk.size)
            chunk.forEachIndexed { bi, box ->
                val x0 = (box[0] * sx).toInt().coerceIn(0, bitmap.width - 2)
                val y0 = (box[1] * sy).toInt().coerceIn(0, bitmap.height - 2)
                val x1 = (box[2] * sx).toInt().coerceIn(x0 + 1, bitmap.width - 1)
                val y1 = (box[3] * sy).toInt().coerceIn(y0 + 1, bitmap.height - 1)
                rects.add(intArrayOf(x0, y0, x1, y1))
                rws[bi] = OcrRecPre.cropRecWidth(x1 - x0, y1 - y0)
            }
            // 定批宽（PC replica:257-258 同式：int(48 * max(320/48, 批内最大宽高比))）
            val cropWs = IntArray(chunk.size) { rects[it][2] - rects[it][0] }
            val cropHs = IntArray(chunk.size) { rects[it][3] - rects[it][1] }
            val batchW = OcrRecPre.batchRecWidth(cropWs, cropHs)
            // 阶段二：逐框裁剪 → 一步缩放到 (min(rw,batchW),48) → 归一化填左、右侧补零
            val data = FloatArray(chunk.size * 3 * recH * batchW)
            chunk.forEachIndexed { bi, _ ->
                val rect = rects[bi]
                val crop = Bitmap.createBitmap(bitmap, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
                val tgtW = minOf(rws[bi], batchW) // rw>batchW 的 ≤2px 下采样，对齐 PC norm_pad_dyn min 语义
                val rs = Bitmap.createScaledBitmap(crop, tgtW, recH, true)
                val cpx = IntArray(recH * tgtW)
                rs.getPixels(cpx, 0, tgtW, 0, 0, tgtW, recH)
                if (rs !== crop) rs.recycle(); crop.recycle()
                OcrRecPre.fillNormalized(cpx, tgtW, batchW, data, bi)
            }
            val t = OnnxTensor.createTensor(
                e, FloatBuffer.wrap(data),
                longArrayOf(chunk.size.toLong(), 3, recH.toLong(), batchW.toLong()),
            )
            val o = r.run(mapOf("x" to t))
            val outT = o[0] as OnnxTensor
            val buf = outT.floatBuffer
            val shape = outT.info.shape // [N,T,C]
            val decoded = OcrCtc.decode(buf, shape[0].toInt(), shape[1].toInt(), shape[2].toInt(), dict)
            o.close()
            t.close()
            decoded.forEachIndexed { bi, (text, conf) ->
                // 解码空串的框丢弃（det 切出的无字符区域）；box 记原图像素坐标
                if (text.isNotEmpty()) {
                    val b = chunk[bi]
                    out.add(
                        OcrLine(
                            text,
                            x0 = b[0] * sx, y0 = b[1] * sy, x1 = b[2] * sx, y1 = b[3] * sy,
                            confidence = conf,
                        ),
                    )
                }
            }
        }
        return out
    }

    override fun close() {
        det?.close(); rec?.close()
        det = null; rec = null; dict = emptyList()
    }

    companion object {
        const val DET_MODEL = "ppocrv5-mobile-det.onnx"
        const val REC_MODEL = "ppocrv5-mobile-rec.onnx"
        const val DICT_FILE = "ppocrv5_dict.txt"
        private const val REC_BATCH = 8
    }
}

/** det 后处理纯函数（P6a PoC 同口径）：8 连通 BFS → 轴对齐框 → 扩张 25% → y 桶排序 */
object OcrDetPost {
    private const val PROB_THRESHOLD = 0.3f
    private const val MIN_BOX_AREA = 32
    private const val BOX_MEAN_MIN = 0.5f
    private const val EXPAND_RATIO = 0.25f

    /** prob: [H*W] 概率图；返回排序后的框 [x0,y0,x1,y1]（缩小图像素坐标） */
    fun extractBoxes(prob: java.nio.FloatBuffer, H: Int, W: Int): List<IntArray> {
        val mask = BooleanArray(H * W) { prob.get(it) > PROB_THRESHOLD }
        val visited = BooleanArray(H * W)
        val qx = IntArray(H * W); val qy = IntArray(H * W)
        val boxes = ArrayList<IntArray>()
        for (y in 0 until H) for (x in 0 until W) {
            val i0 = y * W + x
            if (!mask[i0] || visited[i0]) continue
            var head = 0; var tail = 0
            qx[tail] = x; qy[tail] = y; tail++; visited[i0] = true
            var minx = x; var maxx = x; var miny = y; var maxy = y
            var sum = 0f; var cnt = 0
            while (head < tail) {
                val cx = qx[head]; val cy = qy[head]; head++
                cnt++; sum += prob.get(cy * W + cx)
                if (cx < minx) minx = cx; if (cx > maxx) maxx = cx
                if (cy < miny) miny = cy; if (cy > maxy) maxy = cy
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx; val ny = cy + dy
                    if (nx in 0 until W && ny in 0 until H) {
                        val ni = ny * W + nx
                        if (mask[ni] && !visited[ni]) { visited[ni] = true; qx[tail] = nx; qy[tail] = ny; tail++ }
                    }
                }
            }
            if ((maxx - minx + 1) * (maxy - miny + 1) >= MIN_BOX_AREA && sum / cnt >= BOX_MEAN_MIN) {
                val ex = ((maxx - minx + 1) * EXPAND_RATIO).toInt().coerceAtLeast(2)
                val ey = ((maxy - miny + 1) * EXPAND_RATIO).toInt().coerceAtLeast(2)
                boxes.add(
                    intArrayOf(
                        (minx - ex).coerceAtLeast(0), (miny - ey).coerceAtLeast(0),
                        (maxx + ex).coerceAtMost(W - 1), (maxy + ey).coerceAtMost(H - 1),
                    ),
                )
            }
        }
        boxes.sortWith(compareBy({ it[1] / 24 }, { it[0] }))
        return boxes
    }
}

/** CTC greedy 解码纯函数（PaddleOCR 标准口径）：class 0=blank，dict[class−1]；去重相邻、去 blank */
object OcrCtc {
    /** buf: [N,T,C]；返回每行 (text, confidence)，confidence=保留字符步概率均值（空行 0f） */
    fun decode(buf: java.nio.FloatBuffer, n: Int, t: Int, c: Int, dict: List<String>): List<Pair<String, Float>> {
        val out = mutableListOf<Pair<String, Float>>()
        for (ni in 0 until n) {
            val sb = StringBuilder()
            var prev = 0
            var confSum = 0f; var confCnt = 0
            for (ti in 0 until t) {
                val base = (ni * t + ti) * c
                var best = 0; var bv = buf.get(base)
                for (ci in 1 until c) {
                    val v = buf.get(base + ci)
                    if (v > bv) { bv = v; best = ci }
                }
                if (best != 0 && best != prev) {
                    // class 1..dict.size → dict[class−1]；越界（词典版本不符）跳该字符不崩
                    if (best <= dict.size) {
                        sb.append(dict[best - 1])
                        confSum += bv; confCnt++
                    }
                }
                prev = best
            }
            out.add(sb.toString() to if (confCnt > 0) confSum / confCnt else 0f)
        }
        return out
    }
}

/** rec 动态宽预处理纯函数（P6c Phase 1，对齐 PC replica_ocr.py rec_pre_dyn/norm_pad_dyn 口径）：
 *  根因修复——旧固定 320×48 硬拉伸把长行压约 4 倍致 CTC 全 blank、conf 崩（PC 全量 401 页实证） */
object OcrRecPre {
    private const val REC_H = 48
    private const val REC_MIN_W = 320 // PC 口径：批宽下限 320（宽高比下限 320/48）

    /** 单框 rec 输入宽：高 48 等比缩放后的宽 = ceil(cropW*48/cropH)，最小 1（PC rec_pre_dyn:128 同式） */
    fun cropRecWidth(cropW: Int, cropH: Int): Int =
        maxOf(1, ceil(cropW * REC_H.toDouble() / cropH).toInt())

    /** 批内统一定宽 = int(48 * max(320/48, 批内最大宽高比))，下限 320（PC replica:257-258 同式）；
     *  coerceAtLeast 防 float 舍入把 48*(320f/48f) 截断成 319 */
    fun batchRecWidth(cropWs: IntArray, cropHs: IntArray): Int {
        var maxRatio = REC_MIN_W.toFloat() / REC_H
        for (i in cropWs.indices) {
            val r = cropWs[i].toFloat() / cropHs[i]
            if (r > maxRatio) maxRatio = r
        }
        return maxOf(REC_MIN_W, (REC_H * maxRatio).toInt())
    }

    /** 把已缩放到 (rw×48) 的 ARGB 像素归一化 ((c/255−0.5)/0.5) 填入批次 data 第 bi 行，
     *  平面布局 [3][48][batchW]，内容靠左、右侧补零（PC norm_pad_dyn:137-138 同构，0f 即归一化零点）；
     *  @param rw 必须已由调用方 minOf 截断（rw ≤ batchW）；漏截断时 require 即抛，
     *  错误信息提示回查调用方 minOf——宁抛异常不做静默越界写 */
    fun fillNormalized(px: IntArray, rw: Int, batchW: Int, data: FloatArray, bi: Int) {
        require(rw in 1..batchW) { "rw=$rw 超出批宽 batchW=$batchW（调用方漏做 minOf 截断？）" }
        val plane = REC_H * batchW
        val off = bi * 3 * plane
        for (y in 0 until REC_H) for (x in 0 until rw) {
            val p = px[y * rw + x]
            val di = off + y * batchW + x
            data[di] = (((p shr 16 and 0xFF) / 255f) - 0.5f) / 0.5f
            data[plane + di] = (((p shr 8 and 0xFF) / 255f) - 0.5f) / 0.5f
            data[2 * plane + di] = (((p and 0xFF) / 255f) - 0.5f) / 0.5f
        }
    }
}
