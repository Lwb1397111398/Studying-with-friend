package com.studyfriend.app.data.vision

import android.content.Context
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
     * App 启动时调用。
     */
    suspend fun restorePending(db: StudyDatabase, context: Context) {
        for (bookId in db.visionQueueDao().booksWithPending()) {
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
     */
    suspend fun reenqueueAllPending(db: StudyDatabase, context: Context) {
        for (bookId in db.visionQueueDao().booksWithPending()) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(bookId),
                ExistingWorkPolicy.REPLACE,
                buildRequest(bookId),
            )
        }
    }

    private fun buildRequest(bookId: Long) = OneTimeWorkRequestBuilder<VisionWorker>()
        .setInputData(workDataOf(VisionWorker.KEY_BOOK_ID to bookId))
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
}
