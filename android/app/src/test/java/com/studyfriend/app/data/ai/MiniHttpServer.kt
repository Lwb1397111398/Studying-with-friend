package com.studyfriend.app.data.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** handler 写响应用；HTTP/1.0 + Connection: close，靠 EOF 结束（HttpURLConnection 兼容） */
class MiniResponse(private val raw: OutputStream) {
    fun status(code: Int, reason: String = "OK") {
        raw.write("HTTP/1.0 $code $reason\r\nConnection: close\r\n\r\n".toByteArray())
        raw.flush()
    }

    /** SSE：每块包成 data: 行（引号/反斜杠转义），最后 [DONE]；不关流由 server 统一关闭 */
    fun sse(chunks: List<String>) {
        raw.write("HTTP/1.0 200 OK\r\nConnection: close\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray())
        chunks.forEach { chunk ->
            val esc = chunk.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            raw.write("data: {\"choices\":[{\"delta\":{\"content\":\"$esc\"}}]}\n\n".toByteArray())
        }
        raw.write("data: [DONE]\n\n".toByteArray())
        raw.flush()
    }
}

/**
 * 测试专用迷你 HTTP 服务器（127.0.0.1，仅支持单请求短连接）。
 * com.sun.net.httpserver 在 AGP --release 下不可见，故用 ServerSocket 手写。
 */
class MiniHttpServer(
    private val handler: (path: String, headers: Map<String, String>, body: String, resp: MiniResponse) -> Unit,
) {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val pool = Executors.newFixedThreadPool(3)
    private val acceptThread = Thread {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            pool.submit { runCatching { handle(socket) } }
        }
    }.apply { isDaemon = true; start() }

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port/v1"

    fun stop() {
        running.set(false)
        server.close()
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val ins = s.getInputStream()
            val requestLine = readLine(ins) ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(ins) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = ByteArray(len)
            var n = 0
            while (n < len) {
                val r = ins.read(bodyBytes, n, len - n)
                if (r < 0) break
                n += r
            }
            handler(path, headers, String(bodyBytes, Charsets.UTF_8), MiniResponse(s.getOutputStream()))
        }
    }

    private fun readLine(ins: InputStream): String? {
        val bos = ByteArrayOutputStream()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (bos.size() == 0) null else bos.toString("UTF-8")
            if (b == '\n'.code) return bos.toString("UTF-8").trimEnd('\r')
            bos.write(b)
        }
    }
}
