package com.studyfriend.app.data.study

import com.studyfriend.app.data.db.ParagraphEntity

/** 一个粗读块：段落引用（不复制文本，省内存）+ 字符量估算 */
data class RoughReadBlock(
    val paragraphs: List<ParagraphEntity>,
    val chars: Int,
) {
    val idxs: Set<Int> get() = paragraphs.mapTo(HashSet()) { it.idx }
}

/**
 * 确定性分块（计划 M4a §2.2）：段落不跨块；字符上限 30000 + 段数上限 150，先触发者截断；
 * 单段超限独占成块（不丢弃，AI 输入仍按每段 200 字截断）。同输入必得同划分——断点续跑的前提。
 * 块放大到十万级上下文可容纳的量级：块更大 → 每章请求更少 → 更不易撞限流窗口。
 */
object RoughReadChunks {

    const val MAX_BLOCK_CHARS = 30_000
    const val MAX_BLOCK_PARAS = 150

    fun split(paragraphs: List<ParagraphEntity>): List<RoughReadBlock> {
        if (paragraphs.isEmpty()) return emptyList()
        val blocks = ArrayList<RoughReadBlock>()
        var bucket = ArrayList<ParagraphEntity>()
        var chars = 0

        fun flush() {
            if (bucket.isNotEmpty()) {
                blocks.add(RoughReadBlock(bucket, chars))
                bucket = ArrayList()
                chars = 0
            }
        }

        for (p in paragraphs) {
            val len = p.text.length
            if (len > MAX_BLOCK_CHARS) {
                flush() // 超长段独占一块
                blocks.add(RoughReadBlock(listOf(p), len))
                continue
            }
            if (bucket.isNotEmpty() &&
                (chars + len > MAX_BLOCK_CHARS || bucket.size + 1 > MAX_BLOCK_PARAS)
            ) {
                flush()
            }
            bucket.add(p)
            chars += len
        }
        flush()
        return blocks
    }
}
