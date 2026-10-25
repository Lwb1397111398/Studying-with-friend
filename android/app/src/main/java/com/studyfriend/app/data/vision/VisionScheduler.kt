package com.studyfriend.app.data.vision

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.studyfriend.app.data.db.StudyDatabase
import java.util.concurrent.TimeUnit

/**
 * 视觉后台队列调度（OPT-F）：超出单次同步转写上限的可疑页不走"拒绝导入"，
 * 而是文字层先行入库 + 页级任务落 vision_queue，由 WorkManager 逐页消化。
 * 每本书一个 unique 任务（REPLACE 保证重复入队不叠跑）；约束联网，退避重试。
 */
object VisionScheduler {

    private const val TAG = "VisionScheduler"

    /** 队列总页数上限：超过仍拒绝导入（整本无文字层的极端 PDF，静默烧几小时不合适） */
    const val MAX_QUEUE_PAGES = 400

    fun workName(bookId: Long) = "vision_$bookId"

    /** 立即调度某本书的队列任务（导入确认后调用；已有同名任务会被替换） */
    fun enqueue(context: Context, bookId: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(bookId),
            ExistingWorkPolicy.REPLACE,
            buildRequest(bookId),
        )
    }

    /**
     * 进程死亡/设备重启后的续跑：WorkManager 任务本身持久化，一般会自动恢复；
     * 这里补扫"队列有 PENDING 但没有活跃任务"的书再挂一次（KEEP：不打断在跑的）。
     * App 启动时调用。批B：未配置视觉的书跳过——页挂 PENDING 等配置保存后
     * reenqueueAllPending 补齐，不再冷启动空转退避。
     */
    suspend fun restorePending(db: StudyDatabase, context: Context) {
        val app = context.applicationContext as com.studyfriend.app.StudyApp
        val visionReady = app.settingsRepo.buildVisionTranscribers().isNotEmpty()
        for (bookId in db.visionQueueDao().booksWithPending()) {
            if (!visionReady) break // 未配置视觉：全部跳过（booksWithPending 的行都无人可消费）
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(bookId),
                ExistingWorkPolicy.KEEP,
                buildRequest(bookId),
            )
        }
    }

    /**
     * 设置变更（视觉开关/Key/模型保存）后的重调度：所有有 PENDING 的书全部
     * REPLACE 一次——此前因缺 Key 退避重试的任务立即拿到新配置开跑。
     * FAILED 定格页一并重置回 PENDING（attempts 清零）：用户改配置 = 明确的重试
     * 意图，给一个全新的重试周期，坏 Key 期间烧光 3 轮的页不必整本重导。
     * 日常启动续跑（[restorePending]）不做此重置，FAILED 维持终态防烧钱。
     */
    suspend fun reenqueueAllPending(db: StudyDatabase, context: Context) {
        val dao = db.visionQueueDao()
        val reset = dao.resetFailedToPending(System.currentTimeMillis())
        if (reset > 0) Log.i(TAG, "reset $reset FAILED page(s) → PENDING")
        for (bookId in dao.booksWithPending()) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(bookId),
                ExistingWorkPolicy.REPLACE,
                buildRequest(bookId),
            )
        }
    }

    /**
     * 批B：按书重试/唤醒视觉增强——书架长按入口。重置该书 FAILED 页（不动其他书）
     * 并立即调度队列（无 PENDING 时 Worker success 早退）；视觉未配置时 Worker
     * 自身走退避等待（不烧 attempts），配置保存后自动续跑。
     */
    suspend fun retryFailedByBook(db: StudyDatabase, context: Context, bookId: Long): Int {
        val reset = db.visionQueueDao().resetFailedByBook(bookId, System.currentTimeMillis())
        if (reset > 0) Log.i(TAG, "retry book $bookId: reset $reset FAILED page(s) → PENDING")
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(bookId),
            ExistingWorkPolicy.REPLACE,
            buildRequest(bookId),
        )
        return reset
    }

    private fun buildRequest(bookId: Long) = OneTimeWorkRequestBuilder<VisionWorker>()
        .setInputData(workDataOf(VisionWorker.KEY_BOOK_ID to bookId))
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
}
