package com.studyfriend.app.data.importer.pdfpipeline

/**
 * 统一行模型（P6b 扫描件管线）：数字路径（pdfbox）与扫描路径（OCR）的行数据
 * 在进入 P1 行内聚类（RowNormalizer）前的公共封装（决策案 §2 决策 3 草图八字段）。
 *
 * 坐标语义：统一 PDF 点单位（pt）、左上原点、y 自顶向下——与 PLine 的「旋转修正后
 * 显示坐标」同系。pdfbox 侧 y 翻转已由 LineCollector 承担；OCR 像素坐标 ×(72/140)
 * 后同向，无需再翻转（P6b 计划案 S1 记档，防过度设计）。
 *
 * adapter 职责边界（决策案原文）：只做单位换算/字段映射/置信度透传，不做任何清洗
 * 决策——OCR 特有清洗（宽度归一/引注隐去等）在 OCR 源侧 OcrTextPostProcessor 执行，
 * 不在本类。数字路径恒等映射：PLine→TextSourceRow→PLine 全字段无损
 * （TextSourceRowTest 锁定）；OCR 路径：OcrLine→TextSourceRow（×72/140 换算，
 * OcrPageSource 实现）→SpanInfo（每行单 span）→P1 原样进入。
 *
 * sourceVersion（决策案：与 TocJson FORMAT_VERSION 同纪律）：产出该行的管线版本，
 * 行级属性——混合书各行可携带不同版本号，下游不依赖统一版本号；管线变更（模型/
 * 换算规则/清洗规则）时递增并全量重导（重导触发机制见 P6b 计划案 S1：比对
 * lastOcrVersion，不匹配提示重导）。P3a 打标阈值按段内最大 sourceVersion 分支
 * （OCR 段放宽、数字段零改动——P6a 判据⑤触发条款落地，见 PdfExtractResult.assembleText）。
 */
data class TextSourceRow(
    val text: String,
    /** 行左上角 x（pt，显示坐标） */
    val x: Float,
    /** 行左上角 y（pt，显示坐标，y 自顶向下） */
    val y: Float,
    /** 行宽（pt）；数字路径 = PLine.x1 − PLine.x0 */
    val w: Float,
    /** 行高（pt）；OCR 路径 = 像素行高 ×(72/140)，即 fontSize 的换算来源；
     *  数字路径 PLine 无行高概念，以 fontSize 近似填充（不参与 toPLine 还原，仅文档语义） */
    val h: Float,
    /** 数字路径 = pdfbox 实测字号；OCR 路径 = 行高换算估计值（噪声 ±0.5pt；P6a 判据⑤
     *  实测标题−正文差 mean 1.85pt/std 3.06pt → 阈值放宽条款已触发） */
    val fontSize: Float,
    /** 现管线无字体名信号（P6b F2：数字路径本无、OCR 无从获取）——全路径恒 null。
     *  保留字段为 P6c PP-DocLayout 引入时的评估项；实现不得写非 null 值、不得建死分支 */
    val fontFamily: String?,
    /** 数字路径 null（确定性文本）；OCR 路径 0..1 行级置信度（页级由上层聚合） */
    val confidence: Float?,
    /** 产出该行的管线版本：SOURCE_DIGITAL(0)=数字、PROD_OCR_V1(1)=OCR */
    val sourceVersion: Int,
) {
    /** 还原为提取层行（数字路径 adapter 逆变换；x1=x+w、size=fontSize，几何字段无损） */
    fun toPLine(): PLine = PLine(text, x, x + w, y, fontSize, sourceVersion)

    /** OCR 路径 adapter：单 span 封装（size=fontSize=行高换算值），P1 近恒等进入 */
    fun toSpanInfo(): RowNormalizer.SpanInfo =
        RowNormalizer.SpanInfo(text, x, x + w, y, fontSize, sourceVersion)

    companion object {
        /** 数字 pdfbox 确定性路径 */
        const val SOURCE_DIGITAL = 0

        /** OCR 生产 v1：det/rec 模型哈希 + 宽度映射规则 + 引注规则 + 72/140 系数的组合标识（P6b S1 定值） */
        const val PROD_OCR_V1 = 1

        /** 数字路径 adapter：PLine→TextSourceRow 恒等封装（h 以 size 近似填充，见属性注释） */
        fun fromPLine(p: PLine): TextSourceRow =
            TextSourceRow(p.text, p.x0, p.y0, p.x1 - p.x0, p.size, p.size, null, null, p.sourceVersion)
    }
}
