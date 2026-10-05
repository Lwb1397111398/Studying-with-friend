package com.studyfriend.app.data.importer.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

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
 * → rec 批 8（48×320 拉伸）→ CTC greedy 解码（class 0=blank，dict[class−1]）。
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

        // ---- rec：批 8，48x320 拉伸，CTC greedy 解码 + 字符置信度均值 ----
        val sx = bitmap.width.toFloat() / W; val sy = bitmap.height.toFloat() / H
        val recW = 320; val recH = 48
        val out = mutableListOf<OcrLine>()
        for (chunk in boxes.chunked(REC_BATCH)) {
            val data = FloatArray(chunk.size * 3 * recH * recW)
            val cpx = IntArray(recH * recW)
            chunk.forEachIndexed { bi, box ->
                val x0 = (box[0] * sx).toInt().coerceIn(0, bitmap.width - 2)
                val y0 = (box[1] * sy).toInt().coerceIn(0, bitmap.height - 2)
                val x1 = (box[2] * sx).toInt().coerceIn(x0 + 1, bitmap.width - 1)
                val y1 = (box[3] * sy).toInt().coerceIn(y0 + 1, bitmap.height - 1)
                val crop = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
                val rs = Bitmap.createScaledBitmap(crop, recW, recH, true)
                rs.getPixels(cpx, 0, recW, 0, 0, recW, recH)
                if (rs !== crop) rs.recycle(); crop.recycle()
                val off = bi * 3 * recH * recW
                for (i in cpx.indices) {
                    val p = cpx[i]
                    data[off + i] = (((p shr 16 and 0xFF) / 255f) - 0.5f) / 0.5f
                    data[off + recH * recW + i] = (((p shr 8 and 0xFF) / 255f) - 0.5f) / 0.5f
                    data[off + 2 * recH * recW + i] = (((p and 0xFF) / 255f) - 0.5f) / 0.5f
                }
            }
            val t = OnnxTensor.createTensor(
                e, FloatBuffer.wrap(data),
                longArrayOf(chunk.size.toLong(), 3, recH.toLong(), recW.toLong()),
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
