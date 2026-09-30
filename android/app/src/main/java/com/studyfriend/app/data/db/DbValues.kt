package com.studyfriend.app.data.db

/** 字符串枚举值的唯一定义处；DAO SQL 里出现的同名字面量须与此保持一致 */
object DbValues {
    // books.sourceType
    const val SRC_TXT = "TXT"
    const val SRC_PDF = "PDF"
    const val SRC_PASTE = "PASTE"

    // books.status
    const val BOOK_IMPORTED = "IMPORTED"
    const val BOOK_READY = "READY"

    // chapters.readState
    const val READ_NOT = "NOT_READ"
    const val READ_READING = "READING"
    const val READ_DONE = "DONE"

    // paragraphs.role
    const val ROLE_BODY = "BODY"
    const val ROLE_FRONT = "FRONT"
    const val ROLE_BACK = "BACK"

    // paragraphs.aiAction（粗读三态 + 未规划占位）
    const val ACT_NONE = "NONE"
    const val ACT_SKIP = "SKIP"
    const val ACT_EXPLAIN = "EXPLAIN"
    const val ACT_GROUP = "GROUP"

    // quiz_attempts.verdict
    const val VERDICT_CORRECT = "CORRECT"
    const val VERDICT_WRONG = "WRONG"
    const val VERDICT_PARTIAL = "PARTIAL"

    /** 复习间隔天数表，intervalIdx 0..4 对应 */
    val REVIEW_INTERVAL_DAYS = intArrayOf(1, 3, 7, 14, 30)
}
