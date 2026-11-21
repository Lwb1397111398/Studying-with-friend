package com.studyfriend.app.data.study

/**
 * 一键分析全书的章节范围规则（阅读体验轮 2026-11-21）：老板点名「跳过开篇、目录、
 * 参考书目等没有意义的内容，直接第一章这样讲」。
 *
 * 规则两步：
 * 1. 正文起点 = 第一个标题呈「第X章/节/篇/回/讲/部分、Chapter N、专题N、单元N」格式的章；
 *    全书都没有这种格式（短篇/散文集）时起点 = 0。起点之前的章（封面/开篇/序言/目录）全部跳过。
 * 2. 全程剔除命中「目录/参考文献/参考书目/附录/索引/后记/致谢/年表…」的章（书末后置件
 *    和中途出现的目录页）。
 *
 * 纯函数 + 单测（BookAnalysisScopeTest），Runner 只消费结果不动规则。
 */
internal object BookAnalysisScope {

    /** 分析计划：targets = 参与分析的章下标（按原顺序）；skipped = (下标, 标题) 跳过清单 */
    data class Plan(val targets: List<Int>, val skipped: List<Pair<Int, String>>) {
        val targetCount: Int get() = targets.size
        val skippedCount: Int get() = skipped.size
    }

    /** 正文章标题格式：第X章/节/篇/回/讲/部分、Chapter N、专题N、单元N、课文N、第N课 */
    private val BODY_TITLE = Regex(
        "^(第[0-9〇零一二三四五六七八九十百千]+[章节篇回讲部分]|chapter\\s*\\d+|专题[0-9〇零一二三四五六七八九十]+|单元[0-9〇零一二三四五六七八九十]+|课文[0-9〇零一二三四五六七八九十]+|第[0-9〇零一二三四五六七八九十百千]+课)",
        RegexOption.IGNORE_CASE,
    )

    /** 明确跳过：后置件 + 目录类（中途再出现目录页也剔掉） */
    private val SKIP_TITLE = Regex(
        "(目录|参考文献|参考书目|引用书目|附录|索引|后记|跋$|致谢|谢辞|鸣谢|年表|大事记|术语表|名词表|勘误|版权页|再版说明|修订说明|凡例)",
    )

    /** 残章跳过阈值：低于此字数的章不进分析（扉页残章/空章，粗读纯烧 API） */
    private const val MIN_BODY_CHARS = 50

    /**
     * 生成分析计划。[charCounts] 为每章正文字数（与 [titles] 等长，可为 null 不启用）；
     * 实景轮 2026-11-21 新增第三步：残章跳过——切章错误的 Kit 书会有 1 段 5 字的扉页
     * 残章（「第九章　代理」p102 实录），逐章粗读纯烧 API 且产出无意义，<50 字的章
     * 直接进跳过清单（章数据不动，只是不分析）。
     */
    fun plan(titles: List<String>, charCounts: List<Int>? = null): Plan {
        // 正文起点：第一个「第X章」式标题；全书无此格式时从 0 起（不误杀任何章）
        val start = titles.indexOfFirst { BODY_TITLE.containsMatchIn(it.trim()) }.let { if (it < 0) 0 else it }
        val targets = mutableListOf<Int>()
        val skipped = mutableListOf<Pair<Int, String>>()
        titles.forEachIndexed { i, title ->
            when {
                i < start -> skipped += i to title
                SKIP_TITLE.containsMatchIn(title.trim()) -> skipped += i to title
                charCounts != null && i < charCounts.size && charCounts[i] < MIN_BODY_CHARS -> skipped += i to title
                else -> targets += i
            }
        }
        return Plan(targets, skipped)
    }
}
