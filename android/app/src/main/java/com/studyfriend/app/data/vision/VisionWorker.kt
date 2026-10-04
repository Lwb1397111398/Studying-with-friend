package com.studyfriend.app.data.vision

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.importer.PdfPageRenderer
import kotlinx.coroutines.CancellationException

/**
 * 视觉后台队列执行器（OPT-F）：消化一本书的 vision_queue PENDING 页。
 * 每页一次视觉调用（约 1 分钟），缓存命中直接标完成不调模型；单页失败计
 * attempts，达上限定格 FAILED（不再自动重试，防无限烧钱）；未配置 Key 时不烧
 * attempts 直接退避重试，设置页保存配置后会 REPLACE 重调度立即拿到新配置。
 * 全部终态后尝试守卫式整书重建（[VisionRebuilder]），让转写成果落到书内容。
 */
class VisionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as StudyApp
        val bookId = inputData.getLong(KEY_BOOK_ID, -1)
        if (bookId <= 0) return Result.failure()
        val db = app.database
        val dao = db.visionQueueDao()
        val pending = dao.byBook(bookId).filter { it.status == DbValues.VQ_PENDING }
        Log.d(TAG, "run bookId=$bookId pending=${pending.size}")
        if (pending.isEmpty()) return Result.success()

        val vision = app.settingsRepo.buildVisionTranscriber()
        if (vision == null) {
            Log.d(TAG, "no transcriber (未配置/开关关) → retry")
            return Result.retry() // 未配置/开关关：不烧 attempts，退避等配置变更重调度
        }

        var renderer: PdfPageRenderer? = null
        try {
            // P4 第二道闸（计划案 §3-C 5b，r6-P1-3c）：幸存图页消费兜底——上游
            // excludeFigurePages 过滤失守（历史遗留队列行/缓存变体/新入口）时下游仍拒绝，
            // ordAfterPara 锚定保命；低质量放行页（第一道闸判定、lowQuality 标记）例外
            val figurePages = db.figureDao().pageNosByBook(bookId).toSet()
            for (item in pending) {
                if (item.pageNo in figurePages && !item.lowQuality) {
                    Log.w(
                        TAG,
                        "figure gate: page ${item.pageNo} skipped (figure page, anchoring protection)",
                    )
                    dao.updateStatus(item.id, DbValues.VQ_DONE, item.attempts, System.currentTimeMillis())
                    continue
                }
                val uri = item.uri
                if (VisionCache.read(applicationContext, uri, item.pageNo) != null) {
                    dao.updateStatus(item.id, DbValues.VQ_DONE, item.attempts, System.currentTimeMillis())
                    continue // 上次转写成功但状态没落库（进程被杀）：缓存命中免重烧
                }
                val r = renderer ?: PdfPageRenderer(applicationContext, Uri.parse(uri))
                    .also { renderer = it }
                val t = try {
                    vision.transcribePage(item.originChars, r.renderPageBase64(item.pageNo - 1))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "page ${item.pageNo} failed: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                if (t != null) {
                    VisionCache.write(applicationContext, uri, item.pageNo, t)
                    dao.updateStatus(item.id, DbValues.VQ_DONE, item.attempts, System.currentTimeMillis())
                } else {
                    val attempts = item.attempts + 1
                    dao.updateStatus(
                        item.id,
                        if (attempts >= MAX_PAGE_ATTEMPTS) DbValues.VQ_FAILED else DbValues.VQ_PENDING,
                        attempts,
                        System.currentTimeMillis(),
                    )
                }
            }
        } catch (e: CancellationException) {
            return Result.retry() // 系统 stop（省电/约束变化）：任务保留，下次续跑
        } catch (e: Exception) {
            return Result.retry() // 渲染器级故障（uri 失效等）：退避重试，页级 attempts 不动
        } finally {
            renderer?.close()
        }

        // 全部页到终态（含 FAILED 定格）→ 尝试把已转写内容重建进书
        if (dao.byBook(bookId).none { it.status == DbValues.VQ_PENDING }) {
            VisionRebuilder.rebuildIfSafe(db, applicationContext, bookId)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "VisionWorker"
        const val KEY_BOOK_ID = "bookId"

        /** 单页最多消化 3 轮（轮内转写还有 1 次模型级重试），仍失败定格 FAILED */
        const val MAX_PAGE_ATTEMPTS = 3
    }
}
