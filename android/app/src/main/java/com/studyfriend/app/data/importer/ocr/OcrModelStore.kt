package com.studyfriend.app.data.importer.ocr

import android.content.Context
import java.io.File

/**
 * OCR 模型文件定位（P6b S4 桩 / S5 分发落地）：
 * 模型目录 = filesDir/ocr_models，三文件齐备（det+rec+dict）才可启用 OCR 导入；
 * 缺失时扫描书回退现状路径（拒绝或视觉转写），由 OcrModelDownloader（S5）补齐。
 * 21.08MB 模型不进 APK（S5 gh Release 分发），故不存在「assets 兜底」分支。
 */
object OcrModelStore {

    const val DET_FILE = PpOcrEngine.DET_MODEL
    const val REC_FILE = PpOcrEngine.REC_MODEL
    const val DICT_FILE = PpOcrEngine.DICT_FILE

    fun modelsDir(context: Context): File = File(context.filesDir, "ocr_models")

    /** 三文件齐备 = 模型可用；任一缺失 = 不可用（下载器可据此补齐） */
    fun modelsPresent(context: Context): Boolean {
        val dir = modelsDir(context)
        return dir.isFile(DET_FILE) && dir.isFile(REC_FILE) && dir.isFile(DICT_FILE)
    }

    private fun File.isFile(name: String) = File(this, name).let { it.exists() && it.length() > 0 }
}
