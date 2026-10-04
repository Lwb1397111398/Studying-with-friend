package com.studyfriend.app.data.importer.pdfpipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P4 commit B1 引擎 PoC（阻塞门）：运行期验证计划案风险 8 的探查项，结论已抄录至
 * PdfFigureExtractor 文件头注释。API 存在性 (a)(c)(g)(h) 由 jar 探查确认；
 * 本测试锁定运行期行为：(b) Do 拦截可达、(d) Form 嵌套递归、(f) 跨页 CTM 不串页、
 * 备选 2（page.getContents() 字节流独立解析 Do/cm）可行性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfFigureEnginePocTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    /** PoC 探针引擎：记录每次 drawImage 回调时的 CTM 与对象 */
    private class ProbeEngine(page: PDPage) : PDFGraphicsStreamEngine(page) {
        val ctmPerImage = mutableListOf<Pair<Float, PDImage>>() // Matrix.scaleX 简记

        override fun drawImage(pdImage: PDImage) {
            ctmPerImage.add(Pair(getGraphicsState().currentTransformationMatrix.scaleX, pdImage))
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {}
        override fun clip(p0: android.graphics.Path.FillType) {}
        override fun moveTo(p0: Float, p1: Float) {}
        override fun lineTo(p0: Float, p1: Float) {}
        override fun curveTo(p0: Float, p1: Float, p2: Float, p3: Float, p4: Float, p5: Float) {}
        override fun getCurrentPoint(): PointF = PointF(0f, 0f)
        override fun closePath() {}
        override fun endPath() {}
        override fun strokePath() {}
        override fun fillPath(p0: android.graphics.Path.FillType) {}
        override fun fillAndStrokePath(p0: android.graphics.Path.FillType) {}
        override fun shadingFill(p0: COSName) {}
    }

    private fun tinyImage(doc: PDDocument): PDImageXObject {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF336699.toInt())
        return LosslessFactory.createFromImage(doc, bmp)
    }

    private fun makeForm(doc: PDDocument, content: String, res: PDResources, bboxSide: Float): PDFormXObject {
        val cosStream: COSStream = doc.document.createCOSStream()
        cosStream.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
        val form = PDFormXObject(cosStream)
        form.bBox = PDRectangle(bboxSide, bboxSide)
        form.resources = res
        return form
    }

    /** (b)(f)：第 1 页泄漏 cm 2x 末态，第 2 页直接画图——回调 CTM 应为单位矩阵 */
    @Test
    fun processPage_drawImageCallback_noCrossPageCtmLeak() {
        val doc = PDDocument()
        val img1 = tinyImage(doc)
        val img2 = tinyImage(doc)

        val page1 = PDPage(PDRectangle(612f, 792f))
        val res1 = PDResources()
        res1.put(COSName.getPDFName("Im1"), img1)
        page1.resources = res1
        doc.addPage(page1)
        PDPageContentStream(doc, page1).use { cs ->
            // 故意不包 q/Q：泄漏 2x 缩放末态到页尾
            cs.transform(com.tom_roush.pdfbox.util.Matrix.getScaleInstance(2f, 2f))
            cs.drawImage(img1, 0f, 0f)
        }

        val page2 = PDPage(PDRectangle(612f, 792f))
        val res2 = PDResources()
        res2.put(COSName.getPDFName("Im2"), img2)
        page2.resources = res2
        doc.addPage(page2)
        PDPageContentStream(doc, page2).use { cs ->
            cs.drawImage(img2, 0f, 0f)
        }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        val reloaded = PDDocument.load(out.toByteArray())

        val engine = ProbeEngine(reloaded.getPage(0))
        engine.processPage(reloaded.getPage(0))
        // drawImage(x,y) 内部叠加图片像素尺寸 cm（8px 图 → ×8）：16 = 泄漏的 2x × 内部 8
        assertEquals("第 1 页 1 张图", 1, engine.ctmPerImage.size)
        assertEquals("第 1 页外部 cm 2x 生效（2×内部 8）", 16f, engine.ctmPerImage[0].first, 0.001f)

        val engine2 = ProbeEngine(reloaded.getPage(1))
        engine2.processPage(reloaded.getPage(1))
        assertEquals("第 2 页 1 张图", 1, engine2.ctmPerImage.size)
        assertEquals(
            "(f) 跨页不串页：第 2 页 CTM 应仅含内部因子 8（若串页则叠加第 1 页末态成 16）",
            8f,
            engine2.ctmPerImage[0].first,
            0.001f,
        )
    }

    /** (d)：Form 双层嵌套内的图可达（递归由基类 showForm 处理） */
    @Test
    fun processPage_formNested_imageReachable() {
        val doc = PDDocument()
        val img = tinyImage(doc)

        // form2 内画图；form1 引 form2；页面引 form1
        val res2 = PDResources()
        res2.put(COSName.getPDFName("Im0"), img)
        val form2 = makeForm(doc, "q 100 0 0 100 0 0 cm /Im0 Do Q", res2, 100f)
        val res1 = PDResources()
        res1.put(COSName.getPDFName("F2"), form2)
        val form1 = makeForm(doc, "q /F2 Do Q", res1, 100f)

        val page = PDPage(PDRectangle(612f, 792f))
        val pageRes = PDResources()
        pageRes.put(COSName.getPDFName("F1"), form1 as PDXObject)
        page.resources = pageRes
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs -> cs.drawXObject(form1, 0f, 0f, 200f, 200f) }

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        val reloaded = PDDocument.load(out.toByteArray())

        val engine = ProbeEngine(reloaded.getPage(0))
        engine.processPage(reloaded.getPage(0))
        assertEquals("(d) Form 双层嵌套内的图可达", 1, engine.ctmPerImage.size)
        val pdImage = engine.ctmPerImage[0].second
        assertTrue("回调对象应为 PDImageXObject", pdImage is PDImageXObject)
    }

    /** 备选 2 最小探查：page.getContents() 字节流可独立解析出 Do/cm 运算符 */
    @Test
    fun backupRoute_contentStreamBytes_parseable() {
        val doc = PDDocument()
        val img = tinyImage(doc)
        val page = PDPage(PDRectangle(612f, 792f))
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.transform(com.tom_roush.pdfbox.util.Matrix.getScaleInstance(3f, 3f))
            cs.drawImage(img, 0f, 0f)
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        val reloaded = PDDocument.load(out.toByteArray())

        val bytes = reloaded.getPage(0).getContents().readBytes()
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue("内容流应含 cm 运算符", Regex("(^|[\\s])cm([\\s])").containsMatchIn(text))
        assertTrue("内容流应含 Do 运算符", Regex("(^|[\\s])Do([\\s])").containsMatchIn(text))
        // 备选 2 可行性成立：字节流可读、运算符可定位（完整解析器仅在 PoC (a)(b) 受阻时实施）
    }
}
