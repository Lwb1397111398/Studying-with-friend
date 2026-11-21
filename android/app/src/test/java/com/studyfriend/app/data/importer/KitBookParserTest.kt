package com.studyfriend.app.data.importer

import com.studyfriend.app.data.db.DbValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KIT 成品书直入协议：元数据块提取/剥离、章表锚定切章、role/pageNo/FIGURE 落段 */
class KitBookParserTest {

    /** 模拟 bookocr-kit pack 产出：头部协议块 + 〔页N〕标记正文（含前置/目录/图/脚注） */
    private fun sampleTxt(): String {
        val meta = "{\"kit\":1,\"title\":\"测试书\",\"pages\":6,\"chapters\":[" +
            "{\"t\":\"开篇\",\"p\":1,\"lv\":1,\"r\":\"FRONT\",\"a\":\"开篇\"}," +
            "{\"t\":\"第二版序\",\"p\":2,\"lv\":1,\"r\":\"FRONT\",\"a\":\"第二版序\"}," +
            "{\"t\":\"目录\",\"p\":3,\"lv\":1,\"r\":\"TOC\",\"a\":\"目录\"}," +
            "{\"t\":\"第一章 总则\",\"p\":4,\"lv\":1,\"r\":\"BODY\",\"a\":\"第一章 总则\"}," +
            "{\"t\":\"第二章 分则\",\"p\":6,\"lv\":1,\"r\":\"BODY\",\"a\":\"第二章 分则\"}" +
            "],\"v\":\"bookocr-kit 1.1\"}"
        return listOf(
            "${KitBookParser.META_BEGIN}$meta${KitBookParser.META_END}",
            "〔页1〕",
            "版权页文字。",
            "",
            "〔页2〕",
            "第二版序",
            "",
            "序言正文。",
            "",
            "〔页3〕",
            "目 录",
            "",
            "第一章 总则……4",
            "第二章 分则……6",
            "",
            "〔页4〕",
            "第一章 总则",
            "",
            "总则正文一段。",
            "〔图：立法流程图〕",
            "",
            "〔页5〕",
            "〔脚注〕本条为2021年修正条文。",
            "",
            "〔页6〕",
            "第二章 分则",
            "",
            "分则正文。",
        ).joinToString("\n")
    }

    // ---- extract / stripMeta ----

    @Test
    fun extract_fullMeta() {
        val meta = KitBookParser.extract(sampleTxt())
        assertEquals("测试书", meta?.title)
        assertEquals(6, meta?.pages)
        assertEquals("bookocr-kit 1.1", meta?.version)
        assertEquals(5, meta?.chapters?.size)
        val ch4 = meta?.chapters?.get(3)
        assertEquals("第一章 总则", ch4?.title)
        assertEquals(4, ch4?.page)
        assertEquals(1, ch4?.level)
        assertEquals("BODY", ch4?.role)
        assertEquals("第一章 总则", ch4?.anchor)
    }

    @Test
    fun extract_failures() {
        // 无协议块（普通 txt）→ null，调用方回退通用解析
        assertNull(KitBookParser.extract("第一章 引论\n\n正文。"))
        // JSON 损坏 → null
        assertNull(KitBookParser.extract("${KitBookParser.META_BEGIN}{bad${KitBookParser.META_END}"))
        // 只有开始标记没有结束标记 → null
        assertNull(KitBookParser.extract("${KitBookParser.META_BEGIN}{\"kit\":1}"))
        // kit 版本不认（未来 v2）→ null
        assertNull(
            KitBookParser.extract(
                "${KitBookParser.META_BEGIN}{\"kit\":2,\"chapters\":[{\"t\":\"甲\",\"p\":1}]}${KitBookParser.META_END}",
            ),
        )
        // 章表为空 → null
        assertNull(
            KitBookParser.extract(
                "${KitBookParser.META_BEGIN}{\"kit\":1,\"chapters\":[]}${KitBookParser.META_END}",
            ),
        )
    }

    @Test
    fun stripMeta_removesBlockAndKeepsBody() {
        val stripped = KitBookParser.stripMeta(sampleTxt())
        assertTrue(!stripped.contains(KitBookParser.META_BEGIN))
        assertTrue(stripped.startsWith("〔页1〕"))
        // 元数据行连同换行一起剥，不残留头部空块
        assertEquals("〔页1〕", stripped.lineSequence().first())
    }

    @Test
    fun stripMeta_noBlock_returnsRaw() {
        assertEquals("正文", KitBookParser.stripMeta("正文"))
    }

    // ---- parse：章表锚定切章 ----

    @Test
    fun parse_chapterListAndRoles() {
        val meta = KitBookParser.extract(sampleTxt())!!
        val chapters = KitBookParser.parse(KitBookParser.stripMeta(sampleTxt()), meta)
        assertEquals(listOf("开篇", "第二版序", "目录", "第一章 总则", "第二章 分则"), chapters.map { it.title })
        // 开篇/第二版序=FRONT、目录=TOC、正文=BODY；锚行（标题行）从正文剔除
        val paras = chapters.associate { it.title to it.paras }
        assertEquals("版权页文字。", paras.getValue("开篇").single().text)
        assertEquals(DbValues.ROLE_FRONT, paras.getValue("开篇").single().role)
        assertEquals(DbValues.ROLE_TOC, paras.getValue("目录").single().role)
        // 「目 录」标题行带空格，锚「目录」靠去空白匹配命中并被剔除，条目行成 TOC 段
        assertEquals("第一章 总则……4第二章 分则……6", paras.getValue("目录").single().text)
        // 第一章段：锚行已剔（无段落文本等于章标题）、BODY 带页号
        val body = paras.getValue("第一章 总则").filter { it.role == DbValues.ROLE_BODY }
        assertTrue(body.isNotEmpty())
        assertTrue(body.none { it.text == "第一章 总则" })
        assertEquals(4, body.first().pageNo)
        assertEquals("分则正文。", paras.getValue("第二章 分则").single { it.role == DbValues.ROLE_BODY }.text)
    }

    @Test
    fun parse_tocEntryDoesNotHijackAnchor() {
        // 页3 目录条目「第一章 总则……4」与章锚同前缀，页号闸（blockPage≥p）拦住，
        // 第一章必须锚定到页4 的真标题行而非目录条目
        val meta = KitBookParser.extract(sampleTxt())!!
        val chapters = KitBookParser.parse(KitBookParser.stripMeta(sampleTxt()), meta)
        val ch1 = chapters.first { it.title == "第一章 总则" }
        // 目录条目归目录章；第一章内不得出现条目形态（……4 结尾）文本
        assertTrue(ch1.paras.none { it.text.endsWith("……4") })
        assertEquals(4, ch1.paras.firstNotNullOfOrNull { it.pageNo })
    }

    @Test
    fun parse_figureAndFootnoteParagraphs() {
        val meta = KitBookParser.extract(sampleTxt())!!
        val chapters = KitBookParser.parse(KitBookParser.stripMeta(sampleTxt()), meta)
        val ch1 = chapters.first { it.title == "第一章 总则" }
        // 〔图：…〕与正文同块：图行单独成段标 FIGURE，其余行 softMerge 成 BODY 段
        val figure = ch1.paras.single { it.role == DbValues.ROLE_FIGURE }
        assertEquals("〔图：立法流程图〕", figure.text)
        assertEquals(4, figure.pageNo)
        // 〔脚注〕协议行剥标标 FOOTNOTE（与 BookParser 同口径）
        val footnote = ch1.paras.single { it.role == DbValues.ROLE_FOOTNOTE }
        assertEquals("本条为2021年修正条文。", footnote.text)
        assertEquals(5, footnote.pageNo)
        assertEquals("总则正文一段。", ch1.paras.single { it.text == "总则正文一段。" }.text)
    }

    @Test
    fun parse_firstChapterAnchorMissFallsBackToHead() {
        // 首章（开篇 p=1）锚「开篇」在正文不存在 → 兜底从块 0 收，开头内容不丢
        val meta = KitBookParser.extract(sampleTxt())!!
        val chapters = KitBookParser.parse(KitBookParser.stripMeta(sampleTxt()), meta)
        assertEquals("版权页文字。", chapters.first().paras.single().text)
    }

    @Test
    fun parse_midChapterAnchorMissSkipsToPrevious() {
        // 第二章锚在正文缺失 → 跳章，其页内容归前一章（不按页号猜切）
        val meta = KitBookParser.extract(sampleTxt())!!
        val broken = meta.copy(
            chapters = meta.chapters.map {
                if (it.title == "第一章 总则") it.copy(anchor = "第八章 不存在") else it
            },
        )
        val chapters = KitBookParser.parse(KitBookParser.stripMeta(sampleTxt()), broken)
        assertEquals(listOf("开篇", "第二版序", "目录", "第二章 分则"), chapters.map { it.title })
        // 第一章的页4/页5 内容全部归入目录章之后的首个命中章之前——即目录章吞并
        // 页4-5（含图/脚注），内容不丢
        val toc = chapters.first { it.title == "目录" }
        assertTrue(toc.paras.any { it.text == "总则正文一段。" })
    }

    @Test
    fun parse_level2StaysInBody() {
        // lv=2 节条目不切章：标题行留正文，章表只收 1 级
        val meta = KitBookParser.extract(sampleTxt())!!
        val withSec = meta.copy(
            chapters = meta.chapters + KitChapter("第一节 基本原则", 7, 2, "BODY", "第一节 基本原"),
        )
        val text = KitBookParser.stripMeta(sampleTxt()) +
            "\n\n〔页7〕\n第一节 基本原则\n\n节内正文。\n"
        val chapters = KitBookParser.parse(text, withSec)
        assertTrue(chapters.none { it.title.startsWith("第一节") })
        // 节标题行作为正文段留在所属章（页7 归第二章范围）
        val ch2 = chapters.first { it.title == "第二章 分则" }
        assertTrue(ch2.paras.any { it.text == "第一节 基本原则" })
        assertTrue(ch2.paras.any { it.text == "节内正文。" })
    }

    @Test
    fun parse_pageMarkPageNoInheritance() {
        // 无〔页N〕标记的后续块顺延继承页号（BookParser.stripPageMarks 同协议）
        val meta = KitBookParser.extract(sampleTxt())!!
        val text = KitBookParser.stripMeta(sampleTxt()) +
            "\n\n页6 后续无标记块正文。\n"
        val chapters = KitBookParser.parse(text, meta)
        val ch2 = chapters.first { it.title == "第二章 分则" }
        assertEquals(6, ch2.paras.single { it.text == "页6 后续无标记块正文。" }.pageNo)
    }

    @Test
    fun parse_halfWidthColonFigureMark() {
        // 半角冒号〔图:…〕是子代理偶发笔误（kit 质检放行、pack 原样保留），App 侧一并
        // 认领成 FIGURE 段，防图题行混进正文
        val meta = KitBookParser.extract(sampleTxt())!!
        val text = KitBookParser.stripMeta(sampleTxt()) +
            "\n\n〔页7〕\n正文前一行。\n〔图:表3-1 对比表格〕\n"
        val chapters = KitBookParser.parse(text, meta)
        val ch2 = chapters.first { it.title == "第二章 分则" }
        val figure = ch2.paras.single { it.role == DbValues.ROLE_FIGURE }
        assertEquals("〔图:表3-1 对比表格〕", figure.text)
        assertEquals(7, figure.pageNo)
    }

    // ---- preferredTitle：书名预填（zip 图包文件名带「_Kit图包」尾巴，kit 元数据优先） ----

    @Test
    fun preferredTitle_prefersKitMeta() {
        assertEquals("民法物权", KitBookParser.preferredTitle("民法物权", "民法物权_Kit图包.zip"))
        // 尾巴不再混进书名
        assertTrue(!KitBookParser.preferredTitle("民法物权", "民法物权_Kit图包.zip").endsWith("图包"))
    }

    @Test
    fun preferredTitle_fallsBackToFileName() {
        // kit 书名为空/无 KIT 块 → 文件名去扩展名；空文件名 → 未命名
        assertEquals("民法物权", KitBookParser.preferredTitle(null, "民法物权.txt"))
        assertEquals("民法物权", KitBookParser.preferredTitle("  ", "民法物权_Kit图包.zip"))
        assertEquals("未命名", KitBookParser.preferredTitle(null, ""))
    }

    @Test
    fun preferredTitle_stripsImportMarkerPrefix() {
        // 2026-11-15 起 pack 产出带「导入这个_」前缀，文件名兜底不得把标记带进书名
        assertEquals(
            "民法思维-王泽鉴2022",
            KitBookParser.preferredTitle(null, "导入这个_民法思维-王泽鉴2022_Kit图包.zip"),
        )
    }

    @Test
    fun preferredTitle_stripsSiteSourceTail() {
        // 《袖珍民法典评注》实景实录（2026-11-21）：kit.json title 从书源文件名继承
        // z-library 站源尾巴上书架；作者括号「(杨代雄)」与版次括号不得误伤
        assertEquals(
            "袖珍民法典评注(第二版)_(杨代雄)",
            KitBookParser.preferredTitle(
                "袖珍民法典评注(第二版)_(杨代雄)_(z-library.sk,_1lib.sk,_z-lib.sk)",
                "x.zip",
            ),
        )
        assertEquals(
            "民法总论",
            KitBookParser.preferredTitle(null, "民法总论_(z-library.sk,_1lib.sk,_z-lib.sk)_Kit图包.zip"),
        )
    }

    // ---- filterFrontMatter：编扉页假章剔除（实景体验轮 2026-11-21） ----

    /** 正文段（长、无页码尾巴） */
    private fun bodyPara(text: String) = ParsedPara(text, DbValues.ROLE_BODY)

    /** 简目形态行（编扉页/分编目录页特征：「标题 / 075」页码尾巴） */
    private fun tocLine(title: String, page: Int) =
        ParsedPara("$title / ${"%03d".format(page)}", DbValues.ROLE_BODY)

    @Test
    fun frontMatter_dropsVolumeTocChapter_keepsRealChapter() {
        // 《民法总论》实景：书前「第二编 法律行为」编简目章（简目行为主）与后文同名真章混排
        val tocLines = (1..20).map { tocLine("第${it}节 概念_$it", 75 + it) }
        val realParas = (1..100).map { bodyPara("这是第二章真正的正文第${it}段，内容足够长，包含标点和完整句子。") }
        val chapters = listOf(
            ParsedChapter("开篇", listOf(ParsedPara("序言正文。", DbValues.ROLE_FRONT))),
            ParsedChapter("第二编 法律行为", tocLines), // 编简目假章
            ParsedChapter("第二编 法律行为", realParas), // 同名真章
        )
        val out = KitBookParser.filterFrontMatter(chapters)
        assertEquals(listOf("开篇", "第二编 法律行为"), out.map { it.title })
        assertEquals(realParas, out[1].paras)
    }

    @Test
    fun frontMatter_dropsTinyVolumeFragment() {
        // 编级残片：「第一编 | 基础理论」3 段 23 字（p30 扉页碎页实录）
        val chapters = listOf(
            ParsedChapter("第一编 | 基础理论", listOf(bodyPara("第一编"), bodyPara("基础理论"), bodyPara("第一章"))),
            ParsedChapter("第一章 民法基础", (1..30).map { bodyPara("民法基础正文第${it}段，内容充实，完整成句。") }),
        )
        val out = KitBookParser.filterFrontMatter(chapters)
        assertEquals(listOf("第一章 民法基础"), out.map { it.title })
    }

    @Test
    fun frontMatter_dedupesSameTitleBodyChapters() {
        // 重名兜底：扉页行「第九章　代理」1 段 5 字 vs 正文「第九章　代　理」354 段——去空白同名，小残片剔
        val chapters = listOf(
            ParsedChapter("第九章　代理", listOf(bodyPara("代理"))),
            ParsedChapter("第九章　代　理", (1..50).map { bodyPara("代理正文第${it}段，讨论代理权的发生与消灭。") }),
        )
        val out = KitBookParser.filterFrontMatter(chapters)
        assertEquals(1, out.size)
        assertEquals(50, out[0].paras.size)
    }

    @Test
    fun frontMatter_keepsRealSameNameChaptersBothBig() {
        // 《袖珍民法典评注》实录（2026-11-21）：民法典六编各有「第一章一般规定」「第一节
        // 一般规定」——真重名章动辄数千字，不得留大剔小整章删除（内容丢失）
        val big1 = (1..30).map { bodyPara("总则编一般规定评注第${it}段，完整成句有标点。") }
        val big2 = (1..30).map { bodyPara("合同编一般规定评注第${it}段，完整成句有标点。") }
        val out = KitBookParser.filterFrontMatter(
            listOf(
                ParsedChapter("第一章一般规定", big1),
                ParsedChapter("第一章一般规定", big2),
            ),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun frontMatter_keepsNormalChaptersUntouched() {
        // 正常书：正文段无页码尾巴、段长中位高——零误杀；目录/序言章不参与判定原样保留
        val chapters = listOf(
            ParsedChapter("目录", listOf(ParsedPara("第一章……4", DbValues.ROLE_TOC))),
            ParsedChapter("第二版序", listOf(ParsedPara("序言。", DbValues.ROLE_FRONT))),
            ParsedChapter("第一章 总则", (1..20).map { bodyPara("总则正文第${it}段，句子完整且篇幅正常，不出现页码尾巴。") }),
        )
        val out = KitBookParser.filterFrontMatter(chapters)
        assertEquals(chapters.map { it.title }, out.map { it.title })
    }

    @Test
    fun frontMatter_shortTocChapterConservative() {
        // 保守约束：段数 <5 的简目形态章不剔（宁漏不误杀）；章名非编级——
        // 编级超短章会命中判据 1 被剔（dropsTinyVolumeFragment 已覆盖）
        val chapters = listOf(
            ParsedChapter("第四部分 权利理论", (1..4).map { tocLine("第${it}节", 75) }),
        )
        val out = KitBookParser.filterFrontMatter(chapters)
        assertEquals(1, out.size)
    }

    @Test
    fun frontMatter_dropsPunctFreeShortLineChapter() {
        // 编扉页纯标题行形态（p15~17 实录）：行无页码尾巴也无句读标点——2b 判据
        val plainLines = (1..12).map { bodyPara("第${it}章 法律行为的本质_$it") }
        val realParas = (1..50).map { bodyPara("这一编的正文第${it}段，带标点的完整句子，内容充实。") }
        val out = KitBookParser.filterFrontMatter(
            listOf(
                ParsedChapter("第二编 法律行为", plainLines), // 扉页纯标题行假章
                ParsedChapter("第一编 基础理论", realParas), // 同字数区间的真章对照（带标点不剔）
            ),
        )
        assertEquals(listOf("第一编 基础理论"), out.map { it.title })
    }

    @Test
    fun frontMatter_longMedianChaptersConservative() {
        // 中位段长 >40 的章不剔（长段正文书零误杀，即使偶有几行带页码尾巴）
        val paras = (1..20).map {
            if (it <= 5) tocLine("第${it}节", 75) else bodyPara("这一段是完整的正文段落，长度超过四十个字的门槛线，因此中位数不满足简目章判据，应当保留。")
        }
        val out = KitBookParser.filterFrontMatter(listOf(ParsedChapter("第一章 总则", paras)))
        assertEquals(1, out.size)
    }

    // ---- 锚行剥标题留余文（实景体验轮 2026-11-21：《袖珍民法典评注》内容丢失实录） ----

    @Test
    fun stripTitlePrefix_variants() {
        // 章头与条文同行：剥标题留余文
        assertEquals(
            "第一百七十三条内容。",
            KitBookParser.stripTitlePrefix("第三节代理终止第一百七十三条内容。", "第三节代理终止"),
        )
        // 标题带全角空格、行不带：空白容差
        assertEquals(
            "第五十七条内容。",
            KitBookParser.stripTitlePrefix("第一节　一般规定第五十七条内容。", "第一节一般规定"),
        )
        // 标题即整行：返回空串（等价旧的整行剔除）
        assertEquals("", KitBookParser.stripTitlePrefix("第二章自然人", "第二章自然人"))
        // 行不含完整标题前缀：原样返回（宁漏不删）
        assertEquals(
            "正文不匹配原样保留。",
            KitBookParser.stripTitlePrefix("正文不匹配原样保留。", "第二章自然人"),
        )
    }

    @Test
    fun parse_gluedAnchorLine_keepsRemainderAsParagraph() {
        val meta = "{\"kit\":1,\"title\":\"评注\",\"pages\":2,\"chapters\":[" +
            "{\"t\":\"第三节代理终止\",\"p\":1,\"lv\":1,\"r\":\"BODY\",\"a\":\"第三节代理终止\"}," +
            "{\"t\":\"第八章民事责任\",\"p\":2,\"lv\":1,\"r\":\"BODY\",\"a\":\"第八章民事责任\"}" +
            "],\"v\":\"v\"}"
        val txt = listOf(
            "${KitBookParser.META_BEGIN}$meta${KitBookParser.META_END}",
            "〔页1〕",
            "第三节代理终止第一百七十三条 [委托代理的终止]委托代理终止。",
            "",
            "〔页2〕",
            "第八章民事责任",
            "",
            "责任正文。",
        ).joinToString("\n")
        val out = KitBookParser.parse(KitBookParser.stripMeta(txt), KitBookParser.extract(txt)!!)
        assertEquals(listOf("第三节代理终止", "第八章民事责任"), out.map { it.title })
        assertEquals(1, out[0].paras.size)
        assertEquals("第一百七十三条 [委托代理的终止]委托代理终止。", out[0].paras[0].text)
    }

    @Test
    fun parse_emptyHeaderChapter_titleMergesIntoNext() {
        // 章头行自成一块、正文全归属其下首个节章（「第二章自然人」0 段实录）——
        // 空章标题前挂（「章名 · 节名」），层级上下文不丢
        val meta = "{\"kit\":1,\"title\":\"评注\",\"pages\":2,\"chapters\":[" +
            "{\"t\":\"第二章自然人\",\"p\":1,\"lv\":1,\"r\":\"BODY\",\"a\":\"第二章自然人\"}," +
            "{\"t\":\"第一节民事权利能力\",\"p\":1,\"lv\":1,\"r\":\"BODY\",\"a\":\"第一节民事权利能力\"}," +
            "{\"t\":\"第二节监护\",\"p\":2,\"lv\":1,\"r\":\"BODY\",\"a\":\"第二节监护\"}" +
            "],\"v\":\"v\"}"
        val txt = listOf(
            "${KitBookParser.META_BEGIN}$meta${KitBookParser.META_END}",
            "〔页1〕",
            "第二章自然人",
            "",
            "第一节民事权利能力",
            "",
            "第一节正文一段。",
            "",
            "〔页2〕",
            "第二节监护",
            "",
            "第二节正文。",
        ).joinToString("\n")
        val out = KitBookParser.parse(KitBookParser.stripMeta(txt), KitBookParser.extract(txt)!!)
        assertEquals(
            listOf("第二章自然人${KitBookParser.SEP_TITLE_MERGE}第一节民事权利能力", "第二节监护"),
            out.map { it.title },
        )
        assertEquals(1, out[0].paras.size)
        assertEquals("第一节正文一段。", out[0].paras[0].text)
    }

    @Test
    fun parse_consecutiveEmptyChapters_chainMerge() {
        // 连续空章成链：「第一编」→「第一章」→ 有正文的「第一节」
        val meta = "{\"kit\":1,\"title\":\"评注\",\"pages\":2,\"chapters\":[" +
            "{\"t\":\"第一编\",\"p\":1,\"lv\":1,\"r\":\"BODY\",\"a\":\"第一编\"}," +
            "{\"t\":\"第一章一般规定\",\"p\":1,\"lv\":1,\"r\":\"BODY\",\"a\":\"第一章一般规定\"}," +
            "{\"t\":\"第一节开篇条文\",\"p\":2,\"lv\":1,\"r\":\"BODY\",\"a\":\"第一节开篇条文\"}" +
            "],\"v\":\"v\"}"
        val content = (1..10).joinToString("\n\n") { "条文评注第${it}段，完整成句带标点，内容充实超过残片阈值。" }
        val txt = listOf(
            "${KitBookParser.META_BEGIN}$meta${KitBookParser.META_END}",
            "〔页1〕",
            "第一编",
            "",
            "第一章一般规定",
            "",
            "〔页2〕",
            "第一节开篇条文",
            "",
            content,
        ).joinToString("\n")
        val out = KitBookParser.parse(KitBookParser.stripMeta(txt), KitBookParser.extract(txt)!!)
        assertEquals(1, out.size)
        assertEquals(
            listOf("第一编", "第一章一般规定", "第一节开篇条文").joinToString(KitBookParser.SEP_TITLE_MERGE),
            out[0].title,
        )
    }
}
