package com.studyfriend.app.ui.screens

/**
 * 章「部件」分类（实景体验轮第六波，主测《中国民法典评注·条文选注》实录）：
 * 开篇/目录/附录这类前置、后置件与真条文章在章目录页、确认目录页、阅读抽屉里
 * 长得一模一样——14 万字的「目录」章夹在正文中间毫无提示，用户不知道该跳过。
 * 纯函数按标题词族判定（章表无 role 字段，BookAnalysisScope 的剔除词族同源思路），
 * 只影响展示（徽标+弱化色），不动数据层、不参与任何删除/跳过逻辑。
 */
internal object ChapterKinds {

    enum class Kind { FRONT, BODY, BACK }

    /** 前置件：以这些词开头的章名（开篇/序言/目录/凡例/绪论…；「绪论/导论」无编号在前部，
     *  带编号的真章如「第一章」不命中） */
    private val FRONT_WORDS = listOf(
        "开篇", "开卷", "序言", "自序", "他序", "代序", "前言", "引言", "绪论", "导论",
        "凡例", "编写说明", "出版说明", "修订说明", "再版说明", "导读", "目录", "目 录", "版权", "封面",
    )

    /** 后置件：附录/参考文献/索引/后记… */
    private val BACK_WORDS = listOf(
        "附录", "附錄", "参考文献", "参考书目", "主要参考", "索 引", "索引", "后记", "後記",
        "跋", "致谢", "年表", "大事记", "关键词", "条文索引", "事项索引",
    )

    /** 判定：后置件优先（「附录」不会撞前置词）；其余一律 BODY */
    fun kindOf(title: String): Kind {
        val t = title.trim()
        if (BACK_WORDS.any { t.startsWith(it) }) return Kind.BACK
        if (FRONT_WORDS.any { t.startsWith(it) }) return Kind.FRONT
        return Kind.BODY
    }

    /** 徽标文案：前置件「前置」，后置件「附录」（附录一是最常见的后置形态） */
    fun badgeOf(title: String): String? = when (kindOf(title)) {
        Kind.FRONT -> "前置"
        Kind.BACK -> "附录"
        Kind.BODY -> null
    }
}
