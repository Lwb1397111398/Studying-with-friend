package com.studyfriend.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * P6a 判据③ PoC：onnxruntime-android 跑 PP-OCRv5 mobile det+rec，
 * 测每页分阶段耗时与内存峰值。独立进程 :poc，dumpsys meminfo 可单独采。
 * 用法：adb shell am start -n com.studyfriend.app/.OcrPocActivity --es dir /sdcard/Download/poc
 * 结果：logcat tag=OcrPoc + <dir>/../poc_result.json
 * 注意：判据③只测性能不测准确率，det 后处理用简化版（轴对齐框+固定比例扩张，无 unclip/透视变换）。
 */
class OcrPocActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra("dir") ?: "/sdcard/Download/poc"
        Thread { run(dir) }.start()
    }

    private fun assetBytes(name: String): ByteArray = assets.open(name).use { it.readBytes() }

    private fun run(dir: String) {
        val env = OrtEnvironment.getEnvironment()
        val t0 = SystemClock.elapsedRealtime()
        val det = env.createSession(assetBytes("ocr/ppocrv5-mobile-det.onnx"))
        val rec = env.createSession(assetBytes("ocr/ppocrv5-mobile-rec.onnx"))
        val dict = String(assetBytes("ocr/ppocrv5_dict.txt"), Charsets.UTF_8)
            .lines().filter { it.isNotEmpty() }
        Log.i(TAG, "models loaded ${SystemClock.elapsedRealtime() - t0}ms dict=${dict.size}")

        val files = File(dir).listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name } ?: emptyList()
        val sb = StringBuilder("[")
        files.forEachIndexed { idx, f ->
            val line = processPage(env, det, rec, dict, f)
            if (idx > 0) sb.append(",")
            sb.append("\n").append(line)
            Log.i(TAG, "PAGE ${f.name} $line")
        }
        sb.append("\n]")
        // 结果文件写应用私有目录（scoped storage 下 /sdcard/Download 对应用只读=EROFS）
        File(getExternalFilesDir(null) ?: filesDir, "poc_result.json").writeText(sb.toString())
        Log.i(TAG, "DONE pages=${files.size}")
    }

    private fun memLine(): String {
        val rt = Runtime.getRuntime()
        return "\"nativeMB\":%.0f,\"javaMB\":%.0f".format(
            Debug.getNativeHeapAllocatedSize() / 1048576.0,
            (rt.totalMemory() - rt.freeMemory()) / 1048576.0,
        )
    }

    private fun processPage(env: OrtEnvironment, det: OrtSession, rec: OrtSession, dict: List<String>, file: File): String {
        val tStart = SystemClock.elapsedRealtimeNanos()
        val bmp = BitmapFactory.decodeFile(file.absolutePath)

        // ---- det 预处理：limit_side_len=960，取 32 倍数，(x/255-0.5)/0.5 RGB ----
        var rh = bmp.height.toLong(); var rw = bmp.width.toLong()
        val scale = 960.0 / maxOf(rh, rw)
        if (scale < 1.0) { rh = (rh * scale).toLong(); rw = (rw * scale).toLong() }
        rh = rh / 32 * 32; rw = rw / 32 * 32
        val H = rh.toInt(); val W = rw.toInt()
        val small = Bitmap.createScaledBitmap(bmp, W, H, true)
        val px = IntArray(W * H)
        small.getPixels(px, 0, W, 0, 0, W, H)
        if (small !== bmp) small.recycle()
        val chw = FloatArray(3 * H * W)
        val plane = H * W
        for (i in px.indices) {
            val p = px[i]
            chw[i] = (((p shr 16 and 0xFF) / 255f) - 0.5f) / 0.5f
            chw[plane + i] = (((p shr 8 and 0xFF) / 255f) - 0.5f) / 0.5f
            chw[2 * plane + i] = (((p and 0xFF) / 255f) - 0.5f) / 0.5f
        }
        val tPre = SystemClock.elapsedRealtimeNanos()

        // ---- det 推理 ----
        val detIn = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, rh, rw))
        val detOut = det.run(mapOf("x" to detIn))
        val prob = (detOut[0] as OnnxTensor).floatBuffer
        detOut.close()
        val tDet = SystemClock.elapsedRealtimeNanos()

        // ---- det 后处理：阈值 0.3 → 8 连通域 → 轴对齐框 → 扩张 25% ----
        val mask = BooleanArray(plane) { prob[it] > 0.3f }
        val visited = BooleanArray(plane)
        val qx = IntArray(plane); val qy = IntArray(plane)
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
                cnt++; sum += prob[cy * W + cx]
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
            if ((maxx - minx + 1) * (maxy - miny + 1) >= 32 && sum / cnt >= 0.5f) {
                val ex = ((maxx - minx + 1) * 0.25f).toInt().coerceAtLeast(2)
                val ey = ((maxy - miny + 1) * 0.25f).toInt().coerceAtLeast(2)
                boxes.add(intArrayOf((minx - ex).coerceAtLeast(0), (miny - ey).coerceAtLeast(0),
                    (maxx + ex).coerceAtMost(W - 1), (maxy + ey).coerceAtMost(H - 1)))
            }
        }
        boxes.sortWith(compareBy({ it[1] / 24 }, { it[0] }))
        val tPost = SystemClock.elapsedRealtimeNanos()

        // ---- rec：批 8，48x320 拉伸，CTC greedy ----
        val sx = bmp.width.toFloat() / W; val sy = bmp.height.toFloat() / H
        var textChars = 0
        val recW = 320; val recH = 48
        val batch = 8
        for (chunk in boxes.chunked(batch)) {
            val data = FloatArray(chunk.size * 3 * recH * recW)
            val cpx = IntArray(recH * recW)
            chunk.forEachIndexed { bi, box ->
                val x0 = (box[0] * sx).toInt().coerceIn(0, bmp.width - 2)
                val y0 = (box[1] * sy).toInt().coerceIn(0, bmp.height - 2)
                val x1 = (box[2] * sx).toInt().coerceIn(x0 + 1, bmp.width - 1)
                val y1 = (box[3] * sy).toInt().coerceIn(y0 + 1, bmp.height - 1)
                val crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
                val r = Bitmap.createScaledBitmap(crop, recW, recH, true)
                r.getPixels(cpx, 0, recW, 0, 0, recW, recH)
                if (r !== crop) r.recycle(); crop.recycle()
                val off = bi * 3 * recH * recW
                for (i in cpx.indices) {
                    val p = cpx[i]
                    data[off + i] = (((p shr 16 and 0xFF) / 255f) - 0.5f) / 0.5f
                    data[off + recH * recW + i] = (((p shr 8 and 0xFF) / 255f) - 0.5f) / 0.5f
                    data[off + 2 * recH * recW + i] = (((p and 0xFF) / 255f) - 0.5f) / 0.5f
                }
            }
            val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(chunk.size.toLong(), 3, recH.toLong(), recW.toLong()))
            val out = rec.run(mapOf("x" to t))
            val outT = out[0] as OnnxTensor
            val buf = outT.floatBuffer
            val shape = outT.info.shape // [N,T,C]
            val n = shape[0].toInt(); val tt = shape[1].toInt(); val c = shape[2].toInt()
            for (ni in 0 until n) {
                var prev = -1
                for (ti in 0 until tt) {
                    var best = 0; var bv = buf[(ni * tt + ti) * c]
                    for (ci in 1 until c) {
                        val v = buf[(ni * tt + ti) * c + ci]
                        if (v > bv) { bv = v; best = ci }
                    }
                    if (best != 0 && best != prev) textChars++
                    prev = best
                }
            }
            out.close()
        }
        val tRec = SystemClock.elapsedRealtimeNanos()
        bmp.recycle()

        fun ms(a: Long, b: Long) = (b - a) / 1_000_000.0
        return "{\"page\":\"${file.name}\",\"detMs\":%.0f,\"detPostMs\":%.0f,\"recMs\":%.0f,\"preMs\":%.0f,\"totalMs\":%.0f,\"boxes\":%d,\"chars\":%d,%s}".format(
            ms(tPre, tDet), ms(tDet, tPost), ms(tPost, tRec), ms(tStart, tPre), ms(tStart, tRec),
            boxes.size, textChars, memLine(),
        )
    }

    companion object { private const val TAG = "OcrPoc" }
}
