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
     *  「款」后不接顿号，防「第三款、第四款规定的适用」并列句误判（逗号形态已由 BODY_PUNCT 拦）。
     *  「第」纳入笫/苐：扫描书 OCR 高频混淆字，《债法原理》装机实录「笫一款债之意义」漏判款级 */
    private val L2 = Regex("^[〇零一二三四五六七八九十百千]+[、．.]")
    private val L2_KUAN = Regex("^[第笫苐][$CN_NUM]+款(?!、)")

    /** 三级标题：（一）xxx / (一) xxx / 1. xxx / 1.1 xxx（圆括号编号或数字编号） */
    private val L3 = Regex("^([（(][$CN_NUM]+[)）]|[$CN_NUM]{1,2}[、．.]|\\d+\\.\\d+)")

    /** 正文标点：标题不含句号/逗号/问叹号（顿号、括号、冒号允许——标题可以用），
     *  一刀切掉「（2020）最高法民终1号判决认为，……」这类以文书号开头的正文段误判 */
    private val BODY_PUNCT = Regex("[。！？；，,]")

    /** 页码尾巴（实景体验轮第六波，主测《条文选注》实录）：书前「简目」行带
     *  「一、规范目的与内容沿革......1-5」式页码引导——是目录行不是节标题，此前被
     *  判级成标题后整页黑压压全是假标题。省略号/点串 + 数字区间收尾的一律按正文。 */
    private val PAGE_TAIL = Regex("""[.·•…]{2,}\s*[\d〇零一二三四五六七八九十百千][\d〇零一二三四五六七八九十百千\-－—–~～至到]{0,12}$""")

    /**
     * 段首法条号（实景体验轮第八波，主测《合同编（上）》实录）：释义/评注体条文书
     * 「第四百六十三条本编调整因合同产生的民事关系。……」条号与条文黏连成段，与正文
     * 同款渲染时条文骨架完全隐形。返回段首「第X条(之X)」在原文中的字符区间（容前导
     * 空白，含首尾），非条文段返回 null。不设长度/标点拦截——条文段几乎必有句读，
     * 走 BODY_PUNCT 会全军覆没；误判面只有「段首恰好以条号开头的引用段」，视觉强调
     * 无害。段首判据天然排除正文中的「根据第X条的规定」引用。
     */
    private val ARTICLE = Regex("第[$CN_NUM]+条(之[$CN_NUM]+)?")

    fun articleNoRange(text: String): IntRange? {
        val off = text.indexOfFirst { !it.isWhitespace() }
        if (off < 0) return null
        // matchAt（非 find+offset）：^ 锚在 find(text, off) 里仍按输入 index 0 判定，
        // 前导空白段会整体落空——matchAt 才是真正的「从 off 起匹配」
        return ARTICLE.matchAt(text, off)?.range
    }

    /**
     * 书内区块标记（第八波同书实录）：「【条文释义】【案例评注】」是释义/评注体的
     * 结构骨架，OCR 分段常把它们黏在前段尾巴/下段开头（「……进行调整。【案例评注】」），
     * 正文里毫无区分度。返回段内全部「【…】」子串（2~14 字，容黏连形态）的字符区间，
     * 渲染层主色加粗即可与正文区分——不动段落数据，零迁移。 */
    private val BLOCK_MARK = Regex("""【[^】]{2,14}】""")

    fun findBlockMarks(text: String): List<IntRange> =
        BLOCK_MARK.findAll(text).map { it.range }.toList()

    /**
     * 判级：0 = 不是标题；1 = 第X节级；2 = 「一、/第X款」级；3 = 「（一）/1.」级。
     * 空串、超长、含正文标点、带页码尾巴一律返回 0。
     */
    fun level(text: String): Int {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LEN || BODY_PUNCT.containsMatchIn(t)) return 0
        if (PAGE_TAIL.containsMatchIn(t)) return 0
        // 编号行必须还带点内容（纯「一、」孤编号不算标题）
        if (L1.containsMatchIn(t)) return 1
        if (L2.containsMatchIn(t) && t.length > 3) return 2
        if (L2_KUAN.containsMatchIn(t)) return 2
        if (L3.containsMatchIn(t) && t.length > 3) return 3
        return 0
    }

    fun isHeading(text: String): Boolean = level(text) > 0
}
