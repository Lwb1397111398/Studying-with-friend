package com.studyfriend.app.data.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.studyfriend.app.StudyApp
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.data.db.VisionQueueDao
import com.studyfriend.app.data.importer.PdfPageRenderer
import com.studyfriend.app.data.importer.pdfpipeline.VisionTranscriber
import com.studyfriend.app.data.db.VisionQueueEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * 视觉后台队列执行器（OPT-F）：消化一本书的 vision_queue PENDING 页。
 * 每页一次视觉调用（约 1 分钟），缓存命中直接标完成不调模型；单页失败计
 * attempts，达上限定格 FAILED（不再自动重试，防无限烧钱）；未配置 Key 时不烧
 * attempts 直接退避重试，设置页保存配置后会 REPLACE 重调度立即拿到新配置。
 * 全部终态后尝试守卫式整书重建（[VisionRebuilder]），让转写成果落到书内容。
 *
 * 前台化（R3）：整队一次跑完远超 JobScheduler 10 分钟 job 时限——每次超时被杀
 * 都会在 24h 窗口记一笔，累计超 10 次配额耗尽、该应用全部调度停摆（实测实录）。
 * setForeground 挂常驻通知转前台服务后不受 job 时限约束，一次跑完不烧配额。
 *
 * 多模型并行轮转（R3，老板 2026-10-07 指令）：配置了多个视觉模型时走流水线——
 * 渲染必须单线程（Pdfium native 层非线程安全），主协程逐页渲染 base64 推入
 * Channel；每个模型一条消费协程从 Channel 领页转写（谁空闲谁领=自适应轮转：
 * 某模型限流只卡自己，其余模型继续消化其他页，恢复后自动回到均衡）。
 * 单模型配置时行为与原顺序消化等价。页完成顺序乱序无影响：rebuild 按页号排序。
 */
class VisionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /** 渲染协程 → 模型协程的单页任务（渲染串行产出，模型并行消费） */
    private data class PageJob(val item: VisionQueueEntity, val pngBase64: String)

    override suspend fun doWork(): Result {
        val app = applicationContext as StudyApp
        val bookId = inputData.getLong(KEY_BOOK_ID, -1)
        if (bookId <= 0) return Result.failure()
        val db = app.database
        val dao = db.visionQueueDao()
        val pending = dao.byBook(bookId).filter { it.status == DbValues.VQ_PENDING }
        Log.d(TAG, "run bookId=$bookId pending=${pending.size}")
        if (pending.isEmpty()) return Result.success()

        val transcribers = app.settingsRepo.buildVisionTranscribers()
        if (transcribers.isEmpty()) {
            Log.d(TAG, "no transcriber (未配置/开关关) → retry")
            return Result.retry() // 未配置/开关关：不烧 attempts，退避等配置变更重调度
        }
        Log.d(TAG, "models=${transcribers.map { it.model }}")

        // 大批量才前台化（整队预计超 10 分钟 job 时限、每次超时烧一笔 24h 配额）；
        // 小批量纯后台。API 35 模拟器上 FGS type 校验失败会连带 worker 被框架
        // cancel（实测实录：run 后 16ms 即 cancelled），真机无此限制。
        if (pending.size >= FOREGROUND_MIN_PAGES) runCatching { setForeground(foregroundInfo()) }

        try {
            // P4 第二道闸（计划案 §3-C 5b，r6-P1-3c）：幸存图页消费兜底——上游
            // excludeFigurePages 过滤失守（历史遗留队列行/缓存变体/新入口）时下游仍拒绝，
            // ordAfterPara 锚定保命；低质量放行页（第一道闸判定、lowQuality 标记）例外
            val figurePages = db.figureDao().pageNosByBook(bookId).toSet()
            coroutineScope {
                val jobs = Channel<PageJob>(capacity = 8)
                val lanes = transcribers.map { transcriber ->
                    launch(Dispatchers.IO) {
                        for (job in jobs) consume(jobs, transcriber, job, dao)
                    }
                }
                var renderer: PdfPageRenderer? = null
                try {
                    for (item in pending) {
                        if (figureGateSkip(item.pageNo, figurePages, item.lowQuality)) {
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
                        jobs.send(PageJob(item, r.renderPageBase64(item.pageNo - 1)))
                    }
                } finally {
                    renderer?.close()
                    jobs.close() // 渲染完关闭：模型协程消费完存量自然退出
                }
                lanes.joinAll()
            }
        } catch (e: CancellationException) {
            return Result.retry() // 系统 stop（省电/约束变化）：任务保留，下次续跑
        } catch (e: Exception) {
            return Result.retry() // 渲染器级故障（uri 失效等）：退避重试，页级 attempts 不动
        }

        // 全部页到终态（含 FAILED 定格）→ 尝试把已转写内容重建进书；
        // 仍有 PENDING（本轮有页转写失败但未达 attempts 上限）→ retry 自动重排：
        // 否则这些页要等下次冷启动/设置保存才被再消化（C3a 实录 F3：页 5 卡 6 分钟）。
        // 收敛性：每轮失败 attempts+1，达 MAX_PAGE_ATTEMPTS 定格 FAILED，终态必达
        if (dao.byBook(bookId).none { it.status == DbValues.VQ_PENDING }) {
            VisionRebuilder.rebuildIfSafe(db, applicationContext, bookId)
            return Result.success()
        }
        return Result.retry()
    }

    /** 模型协程的单页消费：转写→缓存→状态落库（各页独立行，无共享可变状态） */
    private suspend fun consume(
        jobs: Channel<PageJob>,
        transcriber: VisionTranscriber,
        job: PageJob,
        dao: com.studyfriend.app.data.db.VisionQueueDao,
    ) {
        val item = job.item
        val t = try {
            transcriber.transcribePage(item.originChars, job.pngBase64)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "page ${item.pageNo} failed [${transcriber.model}]: " +
                "${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (t != null) {
            VisionCache.write(applicationContext, item.uri, item.pageNo, t)
            dao.updateStatus(item.id, DbValues.VQ_DONE, item.attempts, System.currentTimeMillis())
            Log.d(TAG, "page ${item.pageNo} done [${transcriber.model}] chars=${t.totalChars}")
        } else {
            // 页级失败可见性（C3a 实录 F4）：transcribePage 内部吞模型异常静默
            // 返 null，页失败此前零日志；渲染级异常仍走渲染协程的原有 Log.w
            Log.w(
                TAG,
                "page ${item.pageNo} transcription null [${transcriber.model}] " +
                    "(attempt ${item.attempts + 1}/$MAX_PAGE_ATTEMPTS)",
            )
            val attempts = item.attempts + 1
            dao.updateStatus(
                item.id,
                if (attempts >= MAX_PAGE_ATTEMPTS) DbValues.VQ_FAILED else DbValues.VQ_PENDING,
                attempts,
                System.currentTimeMillis(),
            )
        }
    }

    /** 前台服务通知（R3）：dataSync 类型，跑完自动撤 */
    private fun foregroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "视觉转写", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("视觉模型转写扫描页")
            .setContentText("后台转写识别困难的页面，完成后自动停止")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "VisionWorker"
        const val KEY_BOOK_ID = "bookId"
        private const val CHANNEL_ID = "vision_transcribe"
        private const val NOTIF_ID = 0x5150

        /** 超过该页数整队预计超 10 分钟 job 时限，才值得前台化 */
        const val FOREGROUND_MIN_PAGES = 8

        /** 单页最多消化 3 轮（轮内转写还有 1 次模型级重试），仍失败定格 FAILED */
        const val MAX_PAGE_ATTEMPTS = 3

        /**
         * 图页消费闸判定（r12-QC3-P1 抽纯函数供单测，worker 内薄包一层）：
         * 幸存图页且非低质量放行页 → 跳过转写（ordAfterPara 锚定保命）。
         */
        fun figureGateSkip(pageNo: Int, figurePages: Set<Int>, lowQuality: Boolean): Boolean =
            pageNo in figurePages && !lowQuality
    }
}
