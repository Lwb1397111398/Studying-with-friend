package com.studyfriend.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 数据安全轮：诊断日志按天分文件 / export 只取最近 7 天 / install 清旧 / 崩溃带堆栈 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagLogTest {

    private lateinit var dir: File
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("diaglog").toFile()
        DiagLog.installDir(dir)
    }

    private fun dayFile(daysAgo: Long): File =
        File(dir, "log-" + LocalDate.now().minusDays(daysAgo).format(dateFmt) + ".txt")

    @Test
    fun logWritesToDatedFileWithLevelAndTag() {
        DiagLog.i("NoteRunner", "讲解开始")
        DiagLog.w("AiGate", "占线被拒")
        DiagLog.e("SummaryRunner", "生成失败：429", RuntimeException("boom-429"))

        val content = dayFile(0).readText()
        assertTrue(content.contains("I NoteRunner: 讲解开始"))
        assertTrue(content.contains("W AiGate: 占线被拒"))
        assertTrue(content.contains("E SummaryRunner: 生成失败：429"))
        // 异常日志带堆栈（Throwable 类名出现在 stack trace 里）
        assertTrue(content.contains("java.lang.RuntimeException: boom-429"))
        assertTrue(content.contains("at com.studyfriend.app.data.DiagLogTest"))
    }

    @Test
    fun exportMergesRecentDaysOnly() {
        dayFile(8).writeText("八天前的日志")
        dayFile(6).writeText("六天前的日志")
        dayFile(0).writeText("今天的日志")
        dayFile(1).writeText("昨天的日志")

        val text = DiagLog.export()
        // 最近 7 天（含今天 0..6）在场，8 天前被排除；按日期升序合并
        assertTrue(text.contains("昨天的日志"))
        assertTrue(text.contains("今天的日志"))
        assertTrue(text.contains("六天前的日志"))
        assertFalse("8 天前的日志不应进导出", text.contains("八天前的日志"))
        assertTrue("按日期升序合并", text.indexOf("六天前的日志") < text.indexOf("昨天的日志") && text.indexOf("昨天的日志") < text.indexOf("今天的日志"))
        assertTrue(text.startsWith("==== log-"))
    }

    @Test
    fun installPurgesFilesOlderThanKeepDays() {
        dayFile(9).writeText("远古")
        dayFile(7).writeText("恰好 7 天前（保留边界=第 7 天前删除）")
        dayFile(3).writeText("三天前")
        // 非法文件名（解析不出日期）也一并清理防堆积
        File(dir, "log-garbage.txt").writeText("垃圾")
        File(dir, "other.txt").writeText("非日志文件不动")

        DiagLog.installDir(dir)

        assertFalse(dayFile(9).exists())
        assertFalse("第 7 天前应被删（只留最近 7 天=今天..6 天前）", dayFile(7).exists())
        assertTrue(dayFile(3).exists())
        assertFalse(File(dir, "log-garbage.txt").exists())
        assertTrue(File(dir, "other.txt").exists())
    }

    @Test
    fun recordCrashWritesFatalWithThreadAndStack() {
        DiagLog.recordCrash(Thread.currentThread(), IllegalStateException("主线程炸了"))

        val content = dayFile(0).readText()
        assertTrue(content.contains("F Crash:"))
        assertTrue(content.contains("@${Thread.currentThread().name}"))
        assertTrue(content.contains("IllegalStateException: 主线程炸了"))
    }

    @Test
    fun exportEmptyDirReturnsBlank() {
        assertEquals("", DiagLog.export())
    }
}
