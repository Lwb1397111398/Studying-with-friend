package com.studyfriend.app.data.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 候选编码全部失败仍乱码时抛出 */
class DecodeException(message: String) : Exception(message)

/** 文本解码（纯 JVM，可单测）。编码结论只在导入过程内使用，不持久化 */
object TextLoader {

    const val AUTO = "AUTO"

    private val NO_BOM_CANDIDATES = listOf("UTF-8", "GBK", "GB18030")

    /** text 为解码结果；charset 为最终采用的编码名 */
    data class Decoded(val text: String, val charset: String)

    /**
     * explicit = AUTO 时走探测：BOM 优先；无 BOM 依次 UTF-8（严格）/GBK/GB18030（REPLACE+乱码判据）。
     * 显式指定时直通（REPLACE 宽松解码），用于手动编码下拉兜底。
     */
    fun decode(bytes: ByteArray, explicit: String = AUTO): Decoded {
        if (explicit != AUTO) {
            // 显式路径统一剥掉可能残留的 BOM 字符（如显式 UTF-16LE 打开带 BOM 文件）
            return Decoded(String(bytes, Charset.forName(explicit)).removePrefix("\uFEFF"), explicit)
        }
        bomCharset(bytes)?.let { name ->
            // String(Charset) 不剥离 BOM，手动去掉开头的 U+FEFF 字符
            val text = String(bytes, Charset.forName(name)).removePrefix("\uFEFF")
            // BOM 命中也要过乱码判据：BOM 可能只是被错误处理过的文件开头
            if (garbledRatio(text) < 0.005) return Decoded(text, name)
        }
        for (name in NO_BOM_CANDIDATES) {
            val text = when (name) {
                "UTF-8" -> strictUtf8(bytes) ?: continue
                else -> String(bytes, Charset.forName(name))
            }
            if (garbledRatio(text) < 0.005) return Decoded(text, name)
        }
        throw DecodeException("无法识别文件编码，请手动选择编码后重试")
    }

    private fun bomCharset(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> "UTF-8"
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> "UTF-16LE"
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> "UTF-16BE"
        else -> null
    }

    /** UTF-8 严格解码（REPORT），失败返回 null 换下一候选 */
    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** U+FFFD 占比，REPLACE 兜底路径的乱码判据（严格路径成功即无 FFFD，无需判） */
    internal fun garbledRatio(text: String): Double {
        if (text.isEmpty()) return 0.0
        var bad = 0
        for (c in text) if (c == '\uFFFD') bad++
        return bad.toDouble() / text.length
    }
}
