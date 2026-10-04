package com.studyfriend.app.data.importer.pdfpipeline

/**
 * P4 示意图保留——两阶段图片提取器（阶段 1 元数据零解码过滤 / 阶段 2 仅幸存者解码落盘）。
 * 实现于 commit B2；本文件头先固化 commit B1 引擎 PoC 结论（阻塞门，计划案风险 8）。
 *
 * == commit B1 引擎 PoC 结论（通过，2026-10-03）==
 * pdfbox-android tom-roush fork 2.0.27.0（jar 反编译探查 + `PdfFigureEnginePocTest` 运行期验证）：
 *  (a) `PDFGraphicsStreamEngine` 存在且自带 `drawImage(PDImage)` 回调——**直接采用**，
 *      Form 递归（showForm）与 CTM 栈由基类处理，免自写（计划案 (a) 主路线成立）；
 *  (b) `Do` 拦截可达：drawImage 回调收到 `PDImageXObject`（PocTest formNested 用例断言）；
 *  (c) `getGraphicsState().currentTransformationMatrix` 在回调内可达（PocTest 实测）；
 *  (d) Form 双层嵌套内的图可达（PocTest processPage_formNested_imageReachable）；
 *  (e) CTM 内容级旋转探测：`FigureCoordMath.ctmToAffine` 已备，B2 阶段 2 接线；
 *      真书幸存图 CTM 正交性在 J2 E2E（模拟器导入）时经 Log.w 复核；
 *  (f) 跨页 CTM 不串页（PocTest noCrossPageCtmLeak：第 1 页泄漏 cm 2x 末态下回调
 *      CTM.scaleX=2f，第 2 页回调仅含内部因子=1f）——引擎逐页独立，无需手动 reset；
 *      页级 ImageMeta 集合由提取器每页开始时自行清空；
 *  (g) `PDOptionalContentGroup` 类存在（jar 探查）；基类 DrawObject 是否内建 OC 隐藏
 *      判定未确认——B2 残项：不支持则 `Log.w` 一次后忽略（droppedOC 仅在 API 可用时启用）；
 *  (h) `PDImageXObject.getImage(Rect, int)` 采样重载存在（jar 探查）——超大图可预采样；
 *      MAX_SOURCE_PIXELS 守卫优先（超限直接跳过不解码）。
 *  备选 2（`page.getContents()` 字节流独立解析 Do/cm）经 PocTest backupRoute 用例验证可行；
 *  主路线 (a) 成立，故不启用。
 *  已知行为：`PDPageContentStream.drawImage(x, y)` 内部叠加图片像素尺寸的 cm
 *  （1px 测试图 → ×1 因子；早期 8px 实验图观测 ×8），回调 CTM = 外部 CTM × 内部
 *  因子——提取器统一读 graphics state 的 CTM，bbox 语义正确（PocTest 数值佐证）。
 *
 * 单线程约束（计划案 v1.8，r8-P1-4）：`extract()` 为同步单线程调用——只在导入协程内
 * 串行执行一次，不暴露给并发调用方，graphics state 栈无线程安全诉求。
 */
object PdfFigureExtractorPlaceholder {
    // commit B2 将以 class PdfFigureExtractor 替换本占位对象（保持包路径，避免空文件告警）
}
