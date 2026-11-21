package com.studyfriend.app.ui.screens

/**
 * 章内节标题识别（阅读体验轮 2026-11-21）：一章正文里的「第一节 xxx」「一、xxx」
 * 「（一）xxx」「1. xxx」这类小标题此前与正文同样式，层级感全无、看着费劲。
 * 纯函数判级，渲染层（ParaRow）据此换标题样式——只动视觉、不改数据层，零迁移，
 * 存量书无需重导。误杀兜底：长度超限或以句末标点收尾的一律按正文。
 */
internal object ParaHeadings {

    /** 标题行长度上限（字符）：教材节标题几乎没有超过 40 字的，超长按正文 */
    private const val MAX_LEN = 40

    /** 中文数字（含〇/零两种写法）：「第四节」「（三）」「二、」的编号部分 */
    private const val CN_NUM = "0-9〇零一二三四五六七八九十百千"

    /** 一级标题：第X节 / 第X篇 / 第X回 / 第X讲 / 第X部分（章本身在章目录，不进正文判断） */
    private val L1 = Regex("^第[$CN_NUM]+(节|篇|回|讲|部分)")

    /** 二级标题：一、xxx（中文数字 + 顿号/点号收编号，教材惯用；阿拉伯数字编号归三级）；
     *  第X款（法教义书惯用款级标题，《不当得利》装机实录「第一款问题的提出」此前与正文同款）——
     *  「款」后不接顿号，防「第三款、第四款规定的适用」并列句误判（逗号形态已由 BODY_PUNCT 拦） */
    private val L2 = Regex("^[〇零一二三四五六七八九十百千]+[、．.]")
    private val L2_KUAN = Regex("^第[$CN_NUM]+款(?!、)")

    /** 三级标题：（一）xxx / (一) xxx / 1. xxx / 1.1 xxx（圆括号编号或数字编号） */
    private val L3 = Regex("^([（(][$CN_NUM]+[)）]|[$CN_NUM]{1,2}[、．.]|\\d+\\.\\d+)")

    /** 正文标点：标题不含句号/逗号/问叹号（顿号、括号、冒号允许——标题可以用），
     *  一刀切掉「（2020）最高法民终1号判决认为，……」这类以文书号开头的正文段误判 */
    private val BODY_PUNCT = Regex("[。！？；，,]")

    /**
     * 判级：0 = 不是标题；1 = 第X节级；2 = 「一、/第X款」级；3 = 「（一）/1.」级。
     * 空串、超长、含正文标点一律返回 0。
     */
    fun level(text: String): Int {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LEN || BODY_PUNCT.containsMatchIn(t)) return 0
        // 编号行必须还带点内容（纯「一、」孤编号不算标题）
        if (L1.containsMatchIn(t)) return 1
        if (L2.containsMatchIn(t) && t.length > 3) return 2
        if (L2_KUAN.containsMatchIn(t)) return 2
        if (L3.containsMatchIn(t) && t.length > 3) return 3
        return 0
    }

    fun isHeading(text: String): Boolean = level(text) > 0
}
