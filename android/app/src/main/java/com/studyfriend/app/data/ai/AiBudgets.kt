package com.studyfriend.app.data.ai

/**
 * AI 输入预算集中定义。主流大模型上下文已到百万 token 级，输入侧按"十万字"放宽
 * （CJK 1 字 ≤ 1 token，实际占用更低；即使 262K 上下文的模型也留足输出与思考余量）。
 */
object AiBudgets {

    /** 单次调用 user 消息的总字符预算（≈十万 token 上下文级） */
    const val INPUT_CHARS_MAX = 100_000
}
