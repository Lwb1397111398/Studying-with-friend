package com.studyfriend.app.data.study

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.studyfriend.app.MainActivity
import com.studyfriend.app.StudyApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 复习到期提醒（功能补齐轮）：每日一次后台查询到期复习数，>0 发本地通知
 * （点击打开 App）；无到期静默。不发通知的三大前置：开关关 / 系统通知权限没给 /
 * 到期数为 0。不用精确闹钟权限（无需整点提醒，24h 周期足够）。
 */
class ReviewReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as StudyApp
        val now = System.currentTimeMillis()
        val dueCount = app.database.reviewItemDao().dueCountFlow(now).first()
        if (dueCount > 0) {
            notify(dueCount)
        }
        return Result.success()
    }

    private fun notify(dueCount: Int) {
        val manager = NotificationManagerCompat.from(applicationContext)
        // Android 13+：系统通知权限没给就不发（开关开着但权限拒绝 = 用户明确不想被打扰）
        if (Build.VERSION.SDK_INT >= 33 && !manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "复习提醒", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("读书搭子 · 复习提醒")
            .setContentText("有 $dueCount 章复习到期了，趁热打铁巩固一下")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val UNIQUE_WORK = "review_reminder"
        const val CHANNEL_ID = "review_reminder"
        const val NOTIFICATION_ID = 2001

        /** 开=每日注册（KEEP 不打断）；关=取消注册。StudyApp.onCreate 按设置调用其一 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReviewReminderWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
