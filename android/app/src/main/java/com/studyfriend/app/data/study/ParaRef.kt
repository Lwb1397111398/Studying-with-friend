package com.studyfriend.app.data.study

// 引用溯源（P1-③，调研 docs/调研/开源同类App调研与借鉴.md 的 SurfSense 白名单思路）：
// AI 讲到某段具体内容时在句末标〔p编号〕，编号只能来自喂给模型的段落——这是「回答里
// 只能引用给过的原文」防幻觉出处的核心约束。软校验：编号超白名单的标记在落库前剔除
// （引用是增益不是硬要求，剔除不整页重试，零额外 AI 调用；与降档梯子的硬校验分工不同）。
// 渲染端 ui/components/ParaRefs.kt 把标记显示成可点胶囊跳原文。

object ParaRef {
    /** 标记格式：全角括号 + p 前缀 + 数字。正文出现同形字面的概率≈0，匹配到即视为标记 */
    val REGEX = Regex("〔p(\\d+)〕")

    /**
     * 剔除编号不满足 isValid 的标记，其余内容原样保留（合法标记留给渲染端消费）。
     * 讲解白名单 = 喂进 prompt 的段落序数上限；总结白名单 = 进 prompt 的段落 idx 集合。
     */
    fun stripInvalid(text: String, isValid: (Int) -> Boolean): String =
        REGEX.replace(text) { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && isValid(n)) m.value else ""
        }
}
