package com.studyfriend.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2 计划 §5.8：编码探测纯函数测试（纯 JVM，无 Android 依赖） */
class TextLoaderTest {

    @Test
    fun utf8NoBom() {
        val bytes = "中文内容 Hello 123".toByteArray(Charsets.UTF_8)
        val d = TextLoader.decode(bytes)
        assertEquals("中文内容 Hello 123", d.text)
        assertEquals("UTF-8", d.charset)
    }

    @Test
    fun gbkChineseNoBom() {
        val bytes = "民事行为能力分为无、限制、完全三类。".toByteArray(charset("GBK"))
        val d = TextLoader.decode(bytes)
        assertEquals("民事行为能力分为无、限制、完全三类。", d.text)
        assertEquals("GBK", d.charset)
    }

    @Test
    fun utf16LeWithBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "中文测试".toByteArray(Charsets.UTF_16LE)
        val d = TextLoader.decode(bytes)
        assertEquals("中文测试", d.text)
        assertEquals("UTF-16LE", d.charset)
    }

    @Test
    fun sanityRejectsGarbledGbkThenHitsGb18030() {
        // (0x81,0x30,0x81,0x30) 是 GB18030 合法 4 字节序列，但 GBK 的第二字节 0x30 非法
        // → UTF-8 严格失败 → GBK REPLACE 产出大量 U+FFFD 被拒 → GB18030 干净命中
        val exotic = byteArrayOf(0x81.toByte(), 0x30, 0x81.toByte(), 0x30)
        val bytes = ByteArray(0) { 0 } +
            Array(50) { exotic }.flatMap { it.toList() }.toByteArray() +
            "正文".toByteArray(charset("GB18030"))
        val d = TextLoader.decode(bytes)
        assertEquals("GB18030", d.charset)
        assertTrue(d.text.contains("正文"))
        assertFalse(d.text.contains('\uFFFD'))
    }

    @Test(expected = DecodeException::class)
    fun allCandidatesFailThrows() {
        // 0x81 0xFF：GBK/GB18030 的第二字节 0xFF 均非法，UTF-8 严格也失败 → 全候选淘汰
        val bytes = ByteArray(100) { if (it % 2 == 0) 0x81.toByte() else 0xFF.toByte() }
        TextLoader.decode(bytes)
    }

    @Test
    fun explicitCharsetBypassesDetection() {
        val bytes = "测试文本".toByteArray(charset("GB18030"))
        val d = TextLoader.decode(bytes, "GB18030")
        assertEquals("测试文本", d.text)
        assertEquals("GB18030", d.charset)
    }

    @Test
    fun garbledRatio() {
        assertEquals(0.0, TextLoader.garbledRatio("无乱码"), 1e-9)
        assertEquals(0.5, TextLoader.garbledRatio("a\uFFFDb\uFFFD"), 1e-9)
        assertEquals(0.0, TextLoader.garbledRatio(""), 1e-9)
    }

    @Test
    fun bomHitButGarbled_fallsThroughToGbk() {
        // UTF-8 BOM + GBK 中文：BOM 路径解出大量 U+FFFD，被乱码判据拒绝后降级进候选链，
        // 最终由 GBK 候选接住。（注：JDK 的 String 构造对 UTF-16 孤立代理不产 FFFD，
        // 所以 UTF-16 BOM 的乱码无法用该判据识别，与计划"UTF-16 靠手动下拉兜底"一致。
        // BOM 字节会被 GBK 一起解出 mojibake 前缀，属该兜底路径的已知形态）
        val body = "正文内容测试".repeat(50)
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            body.toByteArray(charset("GBK"))
        val d = TextLoader.decode(bytes)
        // BOM 谎报时 UTF-8 严格路径失败，最终由 GBK 候选接住
        // （BOM 3 字节使流错位，尾部孤立字节可能留一个 U+FFFD，在 0.5% 容忍度内）
        assertEquals("GBK", d.charset)
    }
}
