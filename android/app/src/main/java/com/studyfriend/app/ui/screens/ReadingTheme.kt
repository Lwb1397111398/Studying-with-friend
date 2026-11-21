package com.studyfriend.app.ui.screens

import androidx.compose.ui.graphics.Color

/** 阅读页固定纸色的组合局部：null=跟随书房主题（ReadScreen 顶层 provide，正文/卡片消费） */
internal val LocalReadingTheme = androidx.compose.runtime.staticCompositionLocalOf<ReadingTheme?> { null }

/**
 * 阅读背景主题包（实景体验轮第六波，借鉴 legado/Ominididact 思路，色值为通行惯例配色）：
 * 阅读时长长的用户对背景色敏感（图书馆白天 / 夜读 OLED），全局三态主题管不到
 * 「正文纸面」这个层——阅读页允许在「跟随书房」之外选一套固定纸色，正文文字、
 * 次级文字、卡片底色成套切换。设计语言仍是「书房」：五套全部是纸的家族，不彩虹。
 */
internal data class ReadingTheme(
    /** settings 存储的稳定 id（"follow"/"paper"/…） */
    val id: String,
    /** Aa 弹层色卡旁的显示名 */
    val label: String,
    /** 纸面背景色 */
    val bg: Color,
    /** 正文文字色（暖墨系，绝不用纯黑 #000） */
    val text: Color,
    /** 次级文字色（日期/摘要/次级角色段） */
    val secondary: Color,
    /** 卡片底色（要点卡/讲解卡/下一章卡） */
    val cardBg: Color,
) {
    companion object {
        /** 跟随书房主题（默认）：不覆写任何颜色，全部走 MaterialTheme 现状 */
        const val FOLLOW = "follow"

        // 五套固定主题（follow 不在内——它无固定色值）。先声明后引用：companion 属性
        // 按声明顺序初始化，ALL 放在前面会拿到未初始化的 null
        private val PAPER = ReadingTheme(
            id = "paper", label = "纸白",
            bg = Color(0xFFFFFFFF), text = Color(0xFF3A3226),
            secondary = Color(0xFF8A7D66), cardBg = Color(0xFFF6F2E9),
        )
        private val PARCHMENT = ReadingTheme(
            id = "parchment", label = "羊皮纸",
            bg = Color(0xFFFAF4E2), text = Color(0xFF4A3B28),
            secondary = Color(0xFF94815C), cardBg = Color(0xFFF2E9D2),
        )
        private val GREEN = ReadingTheme(
            id = "green", label = "护眼绿",
            bg = Color(0xFFC7E6CF), text = Color(0xFF2F3B33),
            secondary = Color(0xFF5C7A66), cardBg = Color(0xFFB9DFC2),
        )
        private val INK = ReadingTheme(
            id = "ink", label = "墨玉",
            bg = Color(0xFF2B2926), text = Color(0xFFD8CFC0),
            secondary = Color(0xFF9A8F7E), cardBg = Color(0xFF38342F),
        )
        private val BLACK = ReadingTheme(
            id = "black", label = "纯黑",
            bg = Color(0xFF000000), text = Color(0xFFCFC6B8),
            secondary = Color(0xFF8A8175), cardBg = Color(0xFF1A1918),
        )

        /** 五套固定主题（follow 不在内——它无固定色值） */
        val ALL = listOf(PAPER, PARCHMENT, GREEN, INK, BLACK)

        /** 按 settings id 解析；未知值回退 null（调用方按「跟随主题」处理） */
        fun byId(id: String): ReadingTheme? = ALL.firstOrNull { it.id == id }
    }
}
