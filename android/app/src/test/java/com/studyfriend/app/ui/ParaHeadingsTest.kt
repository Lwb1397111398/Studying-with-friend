package com.studyfriend.app.ui

import com.studyfriend.app.ui.screens.ParaHeadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读体验轮：章内节标题判级纯函数（15 例）——误杀正文是红线，逐类锁定 */
class ParaHeadingsTest {

    // ---------- 一级（第X节/篇/回/讲/部分） ----------

    @Test
    fun l1_section() {
        assertEquals(1, ParaHeadings.level("第一节 抵押权概述"))
        assertEquals(1, ParaHeadings.level("第二节物权的变动"))
        assertEquals(1, ParaHeadings.level("第十二节 总结"))
    }

    @Test
    fun l1_partAndChapterLike() {
        assertEquals(1, ParaHeadings.level("第一篇 总论"))
        assertEquals(1, ParaHeadings.level("第三部分 物权请求权"))
        assertEquals(1, ParaHeadings.level("第一讲 民法是什么"))
    }

    // ---------- 二级（一、xxx / 第X款） ----------

    @Test
    fun l2_chineseNumeralWithDunhao() {
        assertEquals(2, ParaHeadings.level("一、所有权的意义"))
        assertEquals(2, ParaHeadings.level("十二、无因管理"))
        assertEquals(2, ParaHeadings.level("二．用益物权"))
    }

    @Test
    fun l2_kuanTitle() {
        // 《不当得利》装机实录：王泽鉴书款级标题「第X款 xxx」此前与正文同款渲染
        assertEquals(2, ParaHeadings.level("第一款问题的提出"))
        assertEquals(2, ParaHeadings.level("第二款 不当得利的意义"))
        assertEquals(2, ParaHeadings.level("第十款请求权基础"))
    }

    @Test
    fun l2_kuanGuards() {
        // 「第三款、第四款规定的适用」并列句：款后接顿号 → 正文
        assertEquals(0, ParaHeadings.level("第三款、第四款规定的适用"))
        // 款后接逗号 → BODY_PUNCT 拦
        assertEquals(0, ParaHeadings.level("第二款，学说上有争议。"))
        // 句中提及款级（非开头）
        assertEquals(0, ParaHeadings.level("依第一款规定，甲得请求返还。"))
    }

    // ---------- 三级（（一）/1. /1.1） ----------

    @Test
    fun l3_parenthesisNumeral() {
        assertEquals(3, ParaHeadings.level("（一）构成要件"))
        assertEquals(3, ParaHeadings.level("(三)法律效果"))
        assertEquals(3, ParaHeadings.level("（十二）抗辩事由"))
    }

    @Test
    fun l3_digitNumeral() {
        assertEquals(3, ParaHeadings.level("1. 概念界定"))
        assertEquals(3, ParaHeadings.level("2、立法例"))
        assertEquals(3, ParaHeadings.level("1.1 物权请求权的一般规定"))
    }

    // ---------- 正文误杀防护 ----------

    @Test
    fun bodyText_notHeading() {
        // 含句中逗号/句号 → 正文
        assertEquals(0, ParaHeadings.level("（2020）最高法民终1号判决认为，双方约定有效。"))
        assertEquals(0, ParaHeadings.level("第一节所述内容，在实务中存在争议。"))
        assertEquals(0, ParaHeadings.level("一、二、三、四这几个数字在法条中出现频繁。"))
        // 以句末标点收尾
        assertEquals(0, ParaHeadings.level("他举了三个例子。"))
    }

    @Test
    fun longOrEmpty_notHeading() {
        // 超过 40 字上限
        val long = "第一节 " + "甲".repeat(45)
        assertEquals(0, ParaHeadings.level(long))
        assertEquals(0, ParaHeadings.level(""))
        assertEquals(0, ParaHeadings.level("   "))
        // 纯编号孤行不算标题
        assertEquals(0, ParaHeadings.level("一、"))
    }

    @Test
    fun headingFlag_matchesLevel() {
        assertTrue(ParaHeadings.isHeading("第一节 物权变动"))
        assertFalse(ParaHeadings.isHeading("物权的变动，是指物权的设立、变更、转让和消灭。"))
    }
}
