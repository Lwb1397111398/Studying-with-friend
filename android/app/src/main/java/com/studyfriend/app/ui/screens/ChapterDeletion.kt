package com.studyfriend.app.ui.screens

/**
 * P5-C 确认页删假章：删除记原始下标集合（列表不物理删除，已删行整行点击恢复），
 * 确认导入时才按 deleted 集过滤幸存章喂给后续链路。
 * 校准产物 localIndex 是「剔除列表」的下标（TocChapterCalibrator 以传入 localChapters
 * 的下标回填），须经本映射翻译回原始下标才能查 editedTitles——两个纯函数配对使用。
 */

/** 幸存章的原始下标序列（保序）：totalSize=原始章数，deleted=用户删掉的原始下标集合。
 *  deleted 含越界下标时忽略（防 UI 脏值），全删返回空数组（VM 层另有「至少留一章」守卫）。 */
internal fun keptOriginalIndices(totalSize: Int, deleted: Set<Int>): IntArray =
    (0 until totalSize).filter { it !in deleted }.toIntArray()

/** 校准产物 localIndex（剔除列表下标）→ 原始列表下标；越界/负数（校准器异常产物）
 *  返回 null，调用方走目录 title 兜底并记日志。 */
internal fun keptOriginalIndex(kept: IntArray, localIndex: Int): Int? =
    kept.getOrNull(localIndex)
