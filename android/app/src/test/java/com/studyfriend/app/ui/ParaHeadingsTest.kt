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
    fun l2_kuanOcrVariants() {
        // 《债法原理》装机实录：扫描书把「第」认成「笫/苐」高频混淆字，款级标题不能漏判
        assertEquals(2, ParaHeadings.level("笫一款债之意义"))
        assertEquals(2, ParaHeadings.level("苐二款债法的编制体系"))
        // 混淆字形态同样守并列句防护
        assertEquals(0, ParaHeadings.level("笫三款、第四款规定的适用"))
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

    // ---------- 页码尾巴（实景体验轮第六波：《条文选注》简目行误判修复） ----------

    @Test
    fun pageTail_isBody() {
        // 书前简目行：编号 + 点串 + 页码区间 → 目录行不是标题（此前整页黑压压假标题）
        assertEquals(0, ParaHeadings.level("一、规范目的与内容沿革......1-5"))
        assertEquals(0, ParaHeadings.level("（一）规范目的......1-3"))
        assertEquals(0, ParaHeadings.level("二、规范性质与射程……6-20"))
        assertEquals(0, ParaHeadings.level("1. 民事自然人借款的适用.....10-14"))
        // 全角省略号 + 单页码
        assertEquals(0, ParaHeadings.level("（二）内容沿革……23"))
        // 中文数字页码（「至」连写）
        assertEquals(0, ParaHeadings.level("第三节 附论……五至八"))
    }

    @Test
    fun realHeadingWithoutTail_stillHeading() {
        // 防误伤：真节标题没有点串尾巴，判级不受影响
        assertEquals(1, ParaHeadings.level("第一节 债之意义"))
        assertEquals(2, ParaHeadings.level("一、规范目的"))
        assertEquals(3, ParaHeadings.level("（一）规范目的"))
        // 正文里普通的省略号（后接汉字/句读）不受页码规则影响——句读已由 BODY_PUNCT 拦
        assertEquals(0, ParaHeadings.level("他要说的意思是……"))
    }

    // ---------- 段首法条号（实景体验轮第八波：《合同编（上）》释义体装机实录） ----------

    @Test
    fun articleNo_hitsFirstParagraph() {
        // 条号与条文黏连成段（OCR 原始形态）：段首「第X条」命中，区间含首尾
        val r = ParaHeadings.articleNoRange("第四百六十三条本编调整因合同产生的民事关系。【条文释义】")!!
        assertEquals(0, r.first)
        assertEquals("第四百六十三条".length - 1, r.last)
        // 段首前导空白容差：区间按原文偏移
        val r2 = ParaHeadings.articleNoRange("  第五条合同的定义。")!!
        assertEquals(2, r2.first)
        assertEquals(2 + "第五条".length - 1, r2.last)
        // 修正案条号「第X条之一」一并命中
        val r3 = ParaHeadings.articleNoRange("第二百三十四条之一故意伤害他人身体的。")!!
        assertEquals(0, r3.first)
        assertEquals("第二百三十四条之一".length - 1, r3.last)
    }

    @Test
    fun articleNo_noFalsePositives() {
        // 正文中的条号引用（非段首）不命中——段首判据天然排除
        assertEquals(null, ParaHeadings.articleNoRange("根据第四百六十三条的规定，合同是民事主体之间设立、变更、终止民事法律关系的协议。"))
        // 段首是「第X条」但后面直接句读之外的东西？——命中是预期（视觉强调无害），不在此拦
        // 普通正文段/节标题段不命中
        assertEquals(null, ParaHeadings.articleNoRange("合同的订立需要经过要约与承诺两个阶段。"))
        assertEquals(null, ParaHeadings.articleNoRange("第一节 一般规定"))
        // 空串/纯空白
        assertEquals(null, ParaHeadings.articleNoRange(""))
        assertEquals(null, ParaHeadings.articleNoRange("   "))
    }

    // ---------- 【区块标记】（第八波同书实录：标记黏连段尾/段中，正文里无区分度） ----------

    @Test
    fun blockMarks_findsAllRanges() {
        // 黏连形态：标记在段尾
        val t1 = "本编调整因合同产生的民事关系。【案例评注】"
        val m1 = ParaHeadings.findBlockMarks(t1)
        assertEquals(1, m1.size)
        assertEquals("【案例评注】", t1.substring(m1[0].first, m1[0].last + 1))
        // 黏连形态：标记在段中（条文+释义黏连）
        val t2 = "第四百六十九条书面形式是合同书、信件。【条文释义】本条是对合同形式的规定。"
        val m2 = ParaHeadings.findBlockMarks(t2)
        assertEquals(1, m2.size)
        assertEquals("【条文释义】", t2.substring(m2[0].first, m2[0].last + 1))
        // 一段双标记
        val t3 = "基本案情……【案例评注】专家点评……【专家点评】"
        assertEquals(2, ParaHeadings.findBlockMarks(t3).size)
        // 无标记
        assertEquals(0, ParaHeadings.findBlockMarks("普通段落没有标记。").size)
        // 超长括号内容（>14 字）不命中（防跨段大括号误吞）
        assertEquals(0, ParaHeadings.findBlockMarks("【这一段标记内容非常长超过了十四个字的限制长度】").size)
    }
}
