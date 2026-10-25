package com.studyfriend.app.data

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 诊断日志（数据安全轮）：filesDir/logs/ 下按天分文件的滚动日志，只保留最近 7 天。
 * 用途：老板把导出的日志文件发给 AI 排障——崩溃堆栈与 AI 任务失败原因都有据可查。
 *
 * 写入方：①全局未捕获异常（install 挂 handler，先写盘再交还原 handler，不打断系统
 * 崩溃流程）；②AiTaskRunner 四个 AI 任务（讲解/粗读/总结/总览）的失败/取消原因。
 * 读取方：设置页「导出诊断日志」（最近几天合并为一个 txt）。
 *
 * 设计取舍：纯文本按天分文件（不引日志框架）；写盘是同步小 append（日志频率低，
 * 调用方都在后台协程/崩溃线程）；未 install 时全部调用静默丢弃（测试环境直接调不炸）。
 */
object DiagLog {

    private const val FILE_PREFIX = "log-"
    private const val FILE_SUFFIX = ".txt"
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val LINE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private val lock = Any()

    @Volatile
    private var dir: File? = null

    /** App 启动调用：建目录、清 7 天前旧文件、挂崩溃捕获 */
    fun install(context: Context) {
        installDir(File(context.filesDir, "logs"))
        // 崩溃捕获：写盘后再交还原 handler（保持系统崩溃对话框 / ANR 上报流程不变）
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { recordCrash(t, e) }
            prev?.uncaughtException(t, e)
        }
    }

    /** 目录注入（install 与测试共用入口） */
    fun installDir(logDir: File, keepDays: Int = KEEP_DAYS) {
        synchronized(lock) {
            dir = logDir.apply { mkdirs() }
            purgeOldLocked(keepDays)
        }
    }

    fun i(tag: String, message: String) = writeLine("I", tag, message, null)
    fun w(tag: String, message: String) = writeLine("W", tag, message, null)
    fun e(tag: String, message: String, error: Throwable? = null) = writeLine("E", tag, message, error)

    /** 合并最近 [keepDays] 天日志为一段文本（按日期升序，导出用）；无日志返回空串 */
    fun export(keepDays: Int = KEEP_DAYS): String {
        val d = dir ?: return ""
        val files = d.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.sortedBy { it.name } // 文件名即 yyyyMMdd，字典序=时间序
            .orEmpty()
        if (files.isEmpty()) return ""
        val oldest = LocalDate.now().minusDays((keepDays - 1).toLong()).format(DATE_FMT)
        return files.filter { it.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX) >= oldest }
            .joinToString("\n\n") { f ->
                val body = runCatching { f.readText() }.getOrDefault("")
                "==== ${f.name} ====\n${body.trimEnd()}"
            }
    }

    /** 崩溃记录（install 的 handler 与测试共用；单独成方法方便断言） */
    fun recordCrash(thread: Thread, error: Throwable) {
        writeLine("F", "Crash", "未捕获异常 @${thread.name}: ${error}", error)
    }

    private fun writeLine(level: String, tag: String, message: String, error: Throwable?) {
        val d = dir ?: return
        val now = LocalDateTime.now()
        val line = buildString {
            append(now.format(LINE_TIME_FMT))
            append(' ').append(level).append(' ').append(tag).append(": ")
            append(message)
            if (error != null) {
                append('\n')
                append(error.stackTraceToString())
            }
            append('\n')
        }
        synchronized(lock) {
            runCatching { File(d, FILE_PREFIX + now.format(DATE_FMT) + FILE_SUFFIX).appendText(line) }
        }
    }

    /** 删除 [keepDays] 天前及更早的旧日志（保留口径与 export 一致：今天往前共 keepDays 天）；
     *  文件名日期解析，解析不了的 log-* 一并清理防堆积 */
    private fun purgeOldLocked(keepDays: Int) {
        val d = dir ?: return
        val oldestKept = LocalDate.now().minusDays((keepDays - 1).toLong()).format(DATE_FMT)
        d.listFiles()?.forEach { f ->
            val datePart = f.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
            val outdated = if (datePart.length == 8 && datePart.all { it.isDigit() }) datePart < oldestKept else true
            if (f.name.startsWith(FILE_PREFIX) && outdated) runCatching { f.delete() }
        }
    }

    const val KEEP_DAYS = 7
}
