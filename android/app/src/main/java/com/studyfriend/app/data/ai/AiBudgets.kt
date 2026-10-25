package com.studyfriend.app.data.ai

/**
 * AI 输入预算集中定义。主流大模型上下文已到百万 token 级，输入侧按"十万字"放宽
 * （CJK 1 字 ≤ 1 token，实际占用更低；即使 262K 上下文的模型也留足输出与思考余量）。
 */
object AiBudgets {

    /** 单次调用 user 消息的总字符预算（≈十万 token 上下文级） */
    const val INPUT_CHARS_MAX = 100_000

    // ---- 输出预算（推理型模型的思考 token 计入 max_tokens：预算不足会被思考吃光、正文截断） ----

    /** 逐段讲解 / 章末总结 / 全书总览的结构化输出（讲解卡主体长，须给足） */
    const val OUTPUT_TOKENS_PLANNER = 16_000

    /** 粗读分块按段数线性给量：每段约 130 token 的批注卡 + 600 结构开销，钳在 [MIN, MAX] */
    const val ROUGH_READ_PER_PARA_TOKENS = 130
    const val ROUGH_READ_BASE_TOKENS = 600
    const val OUTPUT_TOKENS_ROUGH_MIN = 16_384
    const val OUTPUT_TOKENS_ROUGH_MAX = 32_768

    /** 视觉单页整页转写 / 竖排目视判定（短答钉死格式） */
    const val OUTPUT_TOKENS_VISION_PAGE = 8_000
    const val OUTPUT_TOKENS_VISION_JUDGE = 2_000

    /** AI 判卷（学习闭环轮）：得分点清单+一句建议，短输出钉死格式 */
    const val OUTPUT_TOKENS_JUDGE = 2_000

    // ---- 超时 ----

    const val CONNECT_TIMEOUT_MS = 15_000
    /** 聊天 / JSON 链路默认读超时 */
    const val READ_TIMEOUT_DEFAULT_MS = 90_000
    /** GET /models 模型列表（轻请求） */
    const val READ_TIMEOUT_MODEL_LIST_MS = 30_000
    /** 视觉整页转写（实测 60~70s/页） */
    const val READ_TIMEOUT_VISION_MS = 180_000
    /** 视觉短答判定（目录探针/竖排） */
    const val READ_TIMEOUT_VISION_JUDGE_MS = 120_000
}
