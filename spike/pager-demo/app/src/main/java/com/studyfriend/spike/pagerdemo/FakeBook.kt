package com.studyfriend.spike.pagerdemo

import kotlin.random.Random

/**
 * 造 400 段法律教材风段落（固定种子可复现），长度 60~220 字分布，模拟真实章节体量。
 * 零书稿内容——句子由通用法学概念模板拼装。
 */
object FakeBook {

    const val PARA_COUNT = 400

    private val SENTENCES = listOf(
        "物权的设立、变更、转让和消灭，应当依照法律规定进行登记，未经登记不发生物权效力。",
        "动产交付之后，受让人取得该动产的所有权，风险自交付时起移转至买受人一方。",
        "善意取得的构成要件包括受让人善意、支付合理对价以及已经完成交付或者登记。",
        "合同成立后，当事人应当按照约定全面履行自己的义务，并遵循诚信原则行事。",
        "当事人一方不履行合同义务或者履行不符合约定的，应当承担继续履行等违约责任。",
        "债权人可以将债权的全部或者部分转让给第三人，但该债权依其性质不得转让的除外。",
        "保证期间届满前，债权人未依法向保证人主张权利的，保证人的保证责任即告免除。",
        "抵押权设立前，抵押财产已经出租并转移占有的，原租赁关系不受该抵押权的影响。",
        "因不可抗力不能履行民事义务的，不承担民事责任，但法律另有规定的依照其规定。",
        "诉讼时效期间自权利人知道或者应当知道权利受到损害以及义务人之日起计算。",
        "无处分权人将不动产转让给受让人，所有权人有权追回，但善意取得情形除外。",
        "留置权人在其债权未受清偿前，有权留置已经合法占有的债务人的动产。",
    )

    fun paragraphs(seed: Long = 20261116L): List<String> {
        val rnd = Random(seed)
        return List(PARA_COUNT) { i ->
            val len = 60 + rnd.nextInt(161) // 60~220 字
            val sb = StringBuilder()
            var guard = 0
            while (sb.length < len && guard < 12) {
                sb.append(SENTENCES[rnd.nextInt(SENTENCES.size)])
                guard++
            }
            "第%03d段 %s".format(i + 1, sb.toString())
        }
    }
}
