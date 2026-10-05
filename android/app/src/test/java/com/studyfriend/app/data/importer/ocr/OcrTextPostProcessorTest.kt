package com.studyfriend.app.data.importer.ocr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OcrTextPostProcessor 单测（P6b S3）：四规则语义锁定 + Y 判据 fixture 回放。
 *
 * - 规则 a/b：与 Python 原型（gt_fix2.py TO_HALF / strip_citations.py）逐条同构，
 *   抽样用例锁语义，全量一致性由 fixture 回放用例锁定（Y 判据 0 diff）。
 * - 规则 c：fixture 8 页标定回放（8/8）+ 阈值边界 + 噪声防护。
 *   ±20% 扰动检验属标定环节责任，已在 .e2e/p6a 探针实测（阈值 2.4/3.6 下 8/8 分类
 *   不变），此处不再重复——fixture 行数据本身即实测样本。
 * - 规则 d：构造数据锁合并语义（P6a 实测碎片占比 0.05%，8 页标定集无碎片样本）。
 */
class OcrTextPostProcessorTest {

    private fun line(
        text: String,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        conf: Float = 0.95f,
    ) = OcrLine(text, x0, y0, x1, y1, conf)

    // ================= 规则 a：标点宽度归一 =================

    @Test
    fun `rule a - five fullwidth puncts to halfwidth`() {
        assertEquals(",():;", OcrTextPostProcessor.normalizePunctLine("，（）：；"))
    }

    @Test
    fun `rule a - unmapped puncts untouched`() {
        // 句号/引号/书名号/顿号不在映射表（gt_fix2 口径），中文序号顿号保持原样
        val t = "一、「引号」。！？—…"
        assertEquals(t, OcrTextPostProcessor.normalizePunctLine(t))
    }

    @Test
    fun `rule a - list api maps each line keeps geometry`() {
        val lines = listOf(
            line("契约，履行", 10f, 20f, 30f, 40f, 0.9f),
            line("侵权：责任", 50f, 60f, 70f, 80f, 0.8f),
        )
        val out = OcrTextPostProcessor.normalizePunct(lines)
        assertEquals("契约,履行", out[0].text)
        assertEquals("侵权:责任", out[1].text)
        // 几何与置信度不动
        assertEquals(10f, out[0].x0, 1e-4f)
        assertEquals(40f, out[0].y1, 1e-4f)
        assertEquals(0.9f, out[0].confidence, 1e-4f)
    }

    // ================= 规则 b：引注隐去 =================

    @Test
    fun `rule b - FN head circled removed keeps indent and space`() {
        // 行首圈码+空白：删圈码，保留缩进与圈码后的空格（Python group(1)+group(2) 口径）
        // 「  ① 结果」→ 缩进2 + 圈码后的空格1 + 结果 = 三空格；行首空白由管线 trim 兜底
        assertEquals("   结果", OcrTextPostProcessor.stripCitationLine("  ① 结果"))
    }

    @Test
    fun `rule b - circled without following space falls to inline removal`() {
        // FN 要求圈码后至少一个空白；无空白的行首圈码走 R-IN 全删
        assertEquals("结果", OcrTextPostProcessor.stripCitationLine("①结果"))
    }

    @Test
    fun `rule b - inline circled removed`() {
        assertEquals("依契约履行", OcrTextPostProcessor.stripCitationLine("依契约①履行"))
    }

    @Test
    fun `rule b - bracket head removed with trailing space`() {
        assertEquals("结果", OcrTextPostProcessor.stripCitationLine("【12】结果"))
        assertEquals("结果", OcrTextPostProcessor.stripCitationLine("[3] 结果"))
    }

    @Test
    fun `rule b - inline bracket removed`() {
        assertEquals("依契约履行", OcrTextPostProcessor.stripCitationLine("依契约[12]履行"))
    }

    @Test
    fun `rule b - CIP line exempt`() {
        // 著录结构行（罗马数字+.+圈码）整行保留（CIP 格式非引注）
        val t = "I. ①损害赔偿 ②民法典研究"
        assertEquals(t, OcrTextPostProcessor.stripCitationLine(t))
        val t2 = "II. ①王泽鉴著"
        assertEquals(t2, OcrTextPostProcessor.stripCitationLine(t2))
    }

    @Test
    fun `rule b - body numbering untouched`() {
        // 正文序号 (1)（一）1. 非圈码非方括号数字，规则天然不碰
        val t = "(1)（一）1. 条文引用"
        assertEquals(t, OcrTextPostProcessor.stripCitationLine(t))
    }

    @Test
    fun `rule b - explicit unicode classes match fullwidth space and digit`() {
        // S7 E2E 平台差异回归：Android regex 不支持 UNICODE_CHARACTER_CLASS（类初始化即崩，
        // 进程被杀），已改显式字符类 \s→[\s\u00A0\u3000] \d→[0-9\uFF10-\uFF19]。
        // 本例锁全角空格/全角数字在显式类下与原 Unicode 语义一致（JVM 过=Android 过）。
        // 全角空格脚注：删圈码保留圈码后的全角空格（group(1)+group(2) 口径）
        assertEquals("　结果", OcrTextPostProcessor.stripCitationLine("①　结果"))
        // 全角数字方括号引注（行首+句内）
        assertEquals("结果", OcrTextPostProcessor.stripCitationLine("【１】结果"))
        assertEquals("依契约履行", OcrTextPostProcessor.stripCitationLine("依契约[３]履行"))
    }

    // ================= 规则 c：图形页分类 =================

    @Test
    fun `rule c - calibration 8 pages 8 of 8`() {
        // SAMPLE8 标定回放：p081/p247 为树状图页，其余 6 页正文
        val root = Json.parseToJsonElement(readFixture()).jsonObject
        val expectedType = mapOf(81 to "DIAGRAM", 247 to "DIAGRAM")
        root["pages"]!!.jsonArray.forEach { p ->
            val page = p.jsonObject
            val pageNum = page["page"]!!.jsonPrimitive.int
            val lines = page["lines"]!!.jsonArray.map { it.toOcrLine() }
            val want = expectedType[pageNum] ?: "BODY"
            assertEquals("p%03d".format(pageNum), want, OcrTextPostProcessor.classifyPage(lines).name)
        }
    }

    @Test
    fun `rule c - tall box from p081 detected`() {
        // p081 竖排根节点实测框：50.8×462px@140dpi → 26.1×237.6pt，ar=9.09
        val lines = listOf(line("损害赔偿的请求权基础", 234.1f, 120f, 260.2f, 357.6f))
        assertEquals(
            OcrTextPostProcessor.PageType.DIAGRAM,
            OcrTextPostProcessor.classifyPage(lines),
        )
    }

    @Test
    fun `rule c - narrow tall noise box ignored`() {
        // 细噪声框（如孤立标点被 det 拉出高框）宽 < 12.3pt 不触发，防误判
        val lines = listOf(line("：", 100f, 120f, 105f, 200f))
        assertEquals(
            OcrTextPostProcessor.PageType.BODY,
            OcrTextPostProcessor.classifyPage(lines),
        )
    }

    @Test
    fun `rule c - L residue does not trigger alone`() {
        // L 残迹只记日志不判定：两行行首 L 残迹、无竖排框 → BODY
        val logs = mutableListOf<String>()
        val lines = listOf(
            line("L从给付义务", 600f, 300f, 900f, 320f),
            line("L2.探寻请求权基础", 600f, 340f, 900f, 360f),
        )
        assertEquals(
            OcrTextPostProcessor.PageType.BODY,
            OcrTextPostProcessor.classifyPage(lines) { logs.add(it) },
        )
        assertEquals(1, logs.size) // 观察日志仍输出
    }

    @Test
    fun `rule c - threshold boundary at aspect three`() {
        // 阈值语义锁定：ar>3.0 触发。ar=3.1（w=12.4pt h=38.44pt）→ DIAGRAM；
        // ar=2.9 → BODY；w=12.3pt（下界含）不触发（w 必须严格大于）
        val tall = line("竖排", 100f, 100f, 112.4f, 138.44f) // h/w=3.09>3
        assertEquals(
            OcrTextPostProcessor.PageType.DIAGRAM,
            OcrTextPostProcessor.classifyPage(listOf(tall)),
        )
        val notTall = line("正文行", 100f, 100f, 112.4f, 135.96f) // h/w=2.9<3
        assertEquals(
            OcrTextPostProcessor.PageType.BODY,
            OcrTextPostProcessor.classifyPage(listOf(notTall)),
        )
    }

    // ================= 规则 d：碎片行合并 =================

    @Test
    fun `rule d - fragment merged into nearest neighbor in x order`() {
        // bodySize=10.5pt：碎片=框高<5.25pt；伙伴 y 中心距 ≤5.25pt
        val frag1 = line("依契约", 100f, 200f, 150f, 204f, 0.9f) // h=4 碎片
        val frag2 = line("履行", 200f, 199f, 250f, 203f, 0.7f) // h=4 碎片，y 中心距 1
        val normal = line("其他正文内容", 100f, 300f, 400f, 314f, 0.95f) // h=14 正常行
        val out = OcrTextPostProcessor.mergeFragments(listOf(normal, frag1, frag2), bodySize = 10.5f)
        assertEquals(2, out.size)
        val merged = out.first { it.y0 <= 204f && it.y1 >= 199f }
        assertEquals("依契约履行", merged.text) // 按 x0 序拼接
        assertEquals(100f, merged.x0, 1e-4f) // 框并集
        assertEquals(250f, merged.x1, 1e-4f)
        assertEquals(0.7f, merged.confidence, 1e-4f) // 置信度取 min
        assertEquals("其他正文内容", out.first { it.y0 > 250f }.text) // 正常行不受影响
    }

    @Test
    fun `rule d - orphan fragment kept as is`() {
        // 页内无 y 中心距 ≤5.25pt 的伙伴 → 孤儿碎片原样保留不丢字
        val orphan = line("孤立碎片", 100f, 500f, 160f, 504f, 0.8f)
        val far = line("远处的正常行", 100f, 600f, 400f, 614f, 0.95f)
        val out = OcrTextPostProcessor.mergeFragments(listOf(orphan, far), bodySize = 10.5f)
        assertEquals(2, out.size)
        assertEquals("孤立碎片", out[0].text)
    }

    @Test
    fun `rule d - normal height lines never merged`() {
        val a = line("第一行", 100f, 200f, 400f, 214f, 0.9f) // h=14 正常
        val b = line("第二行", 100f, 228f, 400f, 242f, 0.9f) // h=14 正常
        val out = OcrTextPostProcessor.mergeFragments(listOf(a, b), bodySize = 10.5f)
        assertEquals(2, out.size)
        assertEquals("第一行", out[0].text)
        assertEquals("第二行", out[1].text)
    }

    // ================= Y 判据：fixture 回放（Kotlin vs Python 原型 0 diff） =================

    @Test
    fun `fixture replay - rule a plus b matches python prototype on 8 pages`() {
        val root = Json.parseToJsonElement(readFixture()).jsonObject
        val expected = root["expected"]!!.jsonObject
        var checked = 0
        root["pages"]!!.jsonArray.forEach { p ->
            val page = p.jsonObject
            val pageNum = page["page"]!!.jsonPrimitive.int
            val lines = page["lines"]!!.jsonArray.map { it.toOcrLine() }
            val out = OcrTextPostProcessor.stripCitations(OcrTextPostProcessor.normalizePunct(lines))
            val exp = expected[pageNum.toString()]!!.jsonArray.map {
                it.jsonObject["text"]!!.jsonPrimitive.content
            }
            assertEquals("p%03d 行数".format(pageNum), exp.size, out.size)
            exp.zip(out).forEachIndexed { i, (e, o) ->
                assertEquals("p%03d 行%d".format(pageNum, i + 1), e, o.text)
            }
            checked += exp.size
        }
        assertEquals(243, checked) // 8 页行总数（38+31+30+36+30+30+41+7）
    }

    // ================= D 判据：独立验证（S2b fixture，训练/测试分离） =================

    @Test
    fun `rule c - independent validation 3 diagram hit and 3 body zero false positive`() {
        // S2b 补跑 fixture（docs/plans/P6b 计划案 §S2b）：6 页全在 8 页标定集外。
        // DIAGRAM 组三形态：p135 横排主体+竖排侧标签（机制⑥）/p218 行内竖排标签（机制⑥）
        // /p266 整页旋转图（机制④）；BODY 组 p055/p119/p398 预检竖排框零命中。
        // p219 横排树状图页不入集：规则 c 按设计不覆盖（树线字符被 OCR 认成「厂」「L」），
        // 属 P6c DocLayout 移交范围（t3_dval_run.py 头注释记档）。
        val root = Json.parseToJsonElement(readDvalFixture()).jsonObject
        val expectedType = root["expected_type"]!!.jsonObject
        root["pages"]!!.jsonArray.forEach { p ->
            val page = p.jsonObject
            val pageNum = page["page"]!!.jsonPrimitive.int
            val lines = page["lines"]!!.jsonArray.map { it.toOcrLine() }
            val want = expectedType[pageNum.toString()]!!.jsonPrimitive.content
            assertEquals("p%03d".format(pageNum), want, OcrTextPostProcessor.classifyPage(lines).name)
        }
    }

    // ================= fixture 读取 =================

    private fun readFixture(): String =
        javaClass.getResourceAsStream("/ocr/t3_sample8.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("fixture 缺失：src/test/resources/ocr/t3_sample8.json")

    private fun readDvalFixture(): String =
        javaClass.getResourceAsStream("/ocr/t3_dval.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("fixture 缺失：src/test/resources/ocr/t3_dval.json")

    private fun kotlinx.serialization.json.JsonElement.toOcrLine(): OcrLine {
        val o = jsonObject
        return OcrLine(
            text = o["text"]!!.jsonPrimitive.content,
            x0 = o["x0"]!!.jsonPrimitive.double.toFloat(),
            y0 = o["y0"]!!.jsonPrimitive.double.toFloat(),
            x1 = o["x1"]!!.jsonPrimitive.double.toFloat(),
            y1 = o["y1"]!!.jsonPrimitive.double.toFloat(),
            confidence = o["confidence"]!!.jsonPrimitive.double.toFloat(),
        )
    }
}
