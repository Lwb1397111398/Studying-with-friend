package com.studyfriend.app.ui.screens

import com.studyfriend.app.data.db.DbValues

/** 阅读页段落视觉样式决策（纯函数，P5-F：展示与 AI 加工解耦） */
object ReadStyles {
    /**
     * 次级视觉（小字次级色、无重点竖条/无 AI 徽标）适用的段角色：目录条目/页脚注/
     * 前置版权页/后置版权页。FRONT/BACK 仅展示降级，不改动 AI 规划行为（P5-F 定案：
     * FRONT/BACK 保留 AI 参与，粗读规划、正文计数等加工口径维持现状）。
     */
    fun isSecondaryRole(role: String): Boolean = when (role) {
        DbValues.ROLE_TOC,
        DbValues.ROLE_FOOTNOTE,
        DbValues.ROLE_FRONT,
        DbValues.ROLE_BACK,
        -> true
        else -> false
    }
}
