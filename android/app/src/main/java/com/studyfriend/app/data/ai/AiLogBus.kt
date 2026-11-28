package com.studyfriend.app.data.ai

import com.studyfriend.app.data.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一条 AI 调用日志的生命阶段 */
enum class AiLogState {
    /** 已领任务、还在 RPM 限流窗口排队（AiRateLimiter.acquire 挂起中） */
    QUEUED,
    /** 名额已领到、请求已发出；出字后实时追加 */
    STREAMING,
    /** 正常完成 */
    DONE,
    /** 失败（error 为可直接展示的人话原因） */
    ERROR,
    /** 被取消（用户停止/新任务替换） */
    CANCELLED,
}

/** 一次 AI 网络请求的实时日志。chatJson 降档重试会落多条——真实反映实际打了几次请求。 */
data class AiLogSession(
    val id: Long,
    /** 来源标签：粗读/段落讲解/随手问…（ChatRequest.source，缺省回落模型名） */
    val source: String,
    val model: String,
    val state: AiLogState,
    /** begin 时刻（含排队） */
    val startMs: Long,
    /** 领到名额、开始连接的时刻；null=还在排队 */
    val connectMs: Long? = null,
    /** 首字到达时刻；null=还没出字 */
    val firstDeltaMs: Long? = null,
    /** 进入终态的时刻；null=未结束 */
    val endMs: Long? = null,
    /** 正文累计字符数（全量口径，不受文本截尾影响） */
    val chars: Int = 0,
    /** 实时正文（尾部截断，展示用） */
    val text: String = "",
    val error: String? = null,
)

/**
 * AI 运行日志中枢（2026-11-28）：老板诉求「以前完全不知道 AI 有没有运转、速度如何」——
 * 埋点收口在 AiClient.chat（所有 AI 请求的唯一入口），这里只做内存态记录与快照发布：
 * begin（排队）→ streaming（连接/出字）→ finish（完成/失败）或 cancel（取消）。
 *
 * UI 从 [snapshots] collectAsState 实时刷新；设置页入口卡用同一份数据显示进行中数量。
 * 完成时顺手写一行 DiagLog（导出诊断日志能带每次调用的耗时与字数，排障有据）。
 *
 * 设计取舍：纯内存不落库（实时看板用途，进程死了日志也没意义）；text 只留尾部
 * [TEXT_TAIL_MAX] 字符防长输出撑爆重组与内存；条数上限 [SESSIONS_MAX] 裁最旧。
 * 全部写操作走锁；delta 高频但对象小（≤50 条小快照），实测够用不为过早优化加复杂度。
 */
object AiLogBus {

    private val lock = Any()
    private val sessions = ArrayList<AiLogSession>()
    private val _snapshots = MutableStateFlow<List<AiLogSession>>(emptyList())

    /** 不可变快照：index 0 = 最新。日志页与设置页入口卡共用 */
    val snapshots: StateFlow<List<AiLogSession>> = _snapshots.asStateFlow()

    private var nextId = 1L

    /** 记一条新调用（QUEUED 态，排队可见）；返回 id 供后续 delta/finish */
    fun begin(source: String, model: String): Long = synchronized(lock) {
        val id = nextId++
        sessions.add(
            0,
            AiLogSession(
                id = id, source = source.ifBlank { model }, model = model,
                state = AiLogState.QUEUED, startMs = System.currentTimeMillis(),
            ),
        )
        trimLocked()
        publishLocked()
        id
    }

    /** RPM 名额领到、开始连接 */
    fun streaming(id: Long) = update(id) {
        it.copy(state = AiLogState.STREAMING, connectMs = System.currentTimeMillis())
    }

    /** 流式增量到达：首次记首字时刻；正文累计与截尾 */
    fun delta(id: Long, text: String) = update(id) {
        val merged = it.text + text
        it.copy(
            state = AiLogState.STREAMING,
            firstDeltaMs = it.firstDeltaMs ?: System.currentTimeMillis(),
            chars = it.chars + text.length,
            text = tail(merged),
        )
    }

    /** 终态：error=null 完成；否则失败。终态幂等（重复 finish/cancel 不改账） */
    fun finish(id: Long, error: String? = null) = update(id) {
        if (it.state == AiLogState.DONE || it.state == AiLogState.ERROR) return@update it
        val end = System.currentTimeMillis()
        val s = it.copy(state = if (error == null) AiLogState.DONE else AiLogState.ERROR, endMs = end, error = error)
        // 顺手写诊断日志：导出给 AI 排障时每次调用耗时/字数可查（DiagLog 未 install 静默丢弃）
        if (error == null) {
            DiagLog.i(TAG, diagLine(s))
        } else {
            DiagLog.e(TAG, diagLine(s) + " 失败：$error")
        }
        s
    }

    /** 取消（用户停止/替换/队列中被打断） */
    fun cancel(id: Long) = update(id) {
        if (it.state == AiLogState.DONE || it.state == AiLogState.ERROR) return@update it
        it.copy(state = AiLogState.CANCELLED, endMs = System.currentTimeMillis())
    }

    /** 清空全部记录（日志页按钮；单测隔离也用它） */
    fun clear() = synchronized(lock) {
        sessions.clear()
        publishLocked()
    }

    private fun update(id: Long, fn: (AiLogSession) -> AiLogSession) = synchronized(lock) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx >= 0) {
            sessions[idx] = fn(sessions[idx])
            publishLocked()
        }
    }

    /** 上限裁最旧（列表头=最新，从尾部裁） */
    private fun trimLocked() {
        while (sessions.size > SESSIONS_MAX) sessions.removeAt(sessions.size - 1)
    }

    private fun publishLocked() {
        _snapshots.value = sessions.toList()
    }

    /** 尾部截断：超长只留最后 TEXT_TAIL_MAX 字符，前缀「…」标意截断 */
    private fun tail(text: String): String =
        if (text.length <= TEXT_TAIL_MAX) text else "…" + text.substring(text.length - TEXT_TAIL_MAX)

    /** DiagLog 一行摘要：排队/首字/出字耗时与速度（首字时刻缺失时给「无输出」） */
    private fun diagLine(s: AiLogSession): String {
        val queueSec = s.connectMs?.let { (it - s.startMs) / 1000.0 }
        val first = s.firstDeltaMs
        return buildString {
            append("[${s.source}]")
            if (queueSec != null && queueSec >= 0.05) append(" 排队${fmtSec(queueSec)}")
            if (first != null) {
                append(" 首字${fmtSec((first - (s.connectMs ?: s.startMs)) / 1000.0)}")
                val span = (s.endMs ?: System.currentTimeMillis()) - first
                if (span > 0) append(" ${s.chars}字/${fmtSec(span / 1000.0)}（${fmtSpeed(s.chars, span)}）")
                else append(" ${s.chars}字")
            } else {
                append(" 无输出")
            }
        }
    }

    // ---- 展示格式化（日志页与 DiagLog 共用口径；公开供单测锁定） ----

    fun fmtSec(sec: Double): String =
        if (sec >= 60) "${(sec / 60).toInt()}分${(sec % 60).toInt().toString().padStart(2, '0')}秒"
        else "%.1f秒".format(sec)

    fun fmtSpeed(chars: Int, spanMs: Long): String {
        if (spanMs <= 0) return "—"
        return "%.0f字/秒".format(chars * 1000.0 / spanMs)
    }

    private const val TAG = "AiCall"

    /** 单条正文文本保留上限（尾部），防长输出撑爆重组 */
    const val TEXT_TAIL_MAX = 3000

    /** 保留条数上限：约等于一次「一键分析全书」的调用面，再早的没回看价值 */
    const val SESSIONS_MAX = 50
}
