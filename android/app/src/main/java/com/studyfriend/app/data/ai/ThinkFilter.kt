package com.studyfriend.app.data.ai

/**
 * 推理模型 think 标签抑制器（实景体验轮 2026-11-21）：
 * MiniMax-M2.5 等推理模型把思考过程以 `<think>…</think>` 内联在正文增量里流出，
 * 随手问实录整段英文思考草稿原文上屏。收口在 [AiClient.chat] 的增量链路——喂原始
 * delta、吐可见增量，一处过滤全部消费者受益（随手问/换个讲法/讲解/总结/粗读/判卷），
 * 落库内容也不会再混入思考段。标签被分片切断（如 `<thi` + `nk>`）不漏、不提前上屏；
 * 未闭合的 think（正文被思考吃光/请求中断）不吃正文。
 */
internal class ThinkSuppressor {
    private var inThink = false
    private val pending = StringBuilder()

    /** 喂入原始增量，返回应当展示/累计的可见文本（可能为空串） */
    fun feed(delta: String): String {
        pending.append(delta)
        val out = StringBuilder()
        while (true) {
            if (inThink) {
                val close = pending.indexOf(TAG_CLOSE)
                if (close < 0) {
                    // 思考中：全部丢弃，仅留可能是闭合标签前缀的尾巴防分片漏判
                    holdBack(TAG_CLOSE)
                    return out.toString()
                }
                pending.delete(0, close + TAG_CLOSE.length)
                inThink = false
            } else {
                val open = pending.indexOf(TAG_OPEN)
                if (open < 0) {
                    // 没有完整开标签：放行正文，但「可能是 <think> 前缀」的尾巴先扣住，
                    // 防止 "<thi" 这种半截标签先上屏又收不回
                    val hold = tailPrefixLen(TAG_OPEN)
                    out.append(pending, 0, pending.length - hold)
                    pending.delete(0, pending.length - hold)
                    return out.toString()
                }
                out.append(pending, 0, open)
                pending.delete(0, open + TAG_OPEN.length)
                inThink = true
            }
        }
    }

    /** 流结束：放行扣着的非标签尾巴；仍在 think 里（未闭合）则不再吐任何内容 */
    fun finish(): String {
        val rest = pending.toString()
        pending.clear()
        return if (inThink) "" else rest
    }

    /** 只留 buf 末尾「是 tag 真前缀」的那段，其余删除（思考段不再膨胀内存） */
    private fun holdBack(tag: String) {
        val hold = tailPrefixLen(tag)
        pending.delete(0, pending.length - hold)
    }

    /** pending 末尾恰好是 tag 的真前缀（不含全等，全等走 indexOf 分支）时的长度 */
    private fun tailPrefixLen(tag: String): Int {
        val max = minOf(pending.length, tag.length - 1)
        for (len in max downTo 1) {
            var match = true
            for (i in 0 until len) {
                if (pending[pending.length - len + i] != tag[i]) {
                    match = false
                    break
                }
            }
            if (match) return len
        }
        return 0
    }

    companion object {
        private const val TAG_OPEN = "<think>"
        private const val TAG_CLOSE = "</think>"
    }
}
