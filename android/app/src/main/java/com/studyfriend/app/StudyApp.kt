package com.studyfriend.app

import android.app.Application
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiGate
import com.studyfriend.app.data.ai.ResilientSecretStore
import com.studyfriend.app.data.ai.SecretStore
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.study.AiClientChatJsonFn
import com.studyfriend.app.data.study.AiClientChatTextFn
import com.studyfriend.app.data.study.NoteRunner
import com.studyfriend.app.data.study.OverviewRunner
import com.studyfriend.app.data.study.RoughReadRunner
import com.studyfriend.app.data.study.SummaryRunner
import com.studyfriend.app.data.vision.VisionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StudyApp : Application() {
    val database: StudyDatabase by lazy { StudyDatabase.build(this) }

    /** 应用级协程作用域：页面退出也不取消的写库/UI 外任务（标记读完等） */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 全局 AI 互斥：粗读与讲解共用一把锁，任何时刻只跑一个 AI 任务（M4b §2.5） */
    val aiGate: AiGate = AiGate()

    /** 应用级密钥仓单例（OPT-A）：keystore 优先、软件密钥兜底；设置页与双 Runner 共享 */
    val secretStore: SecretStore by lazy { ResilientSecretStore(applicationContext) }

    /** 设置仓库（无状态、读时解密）：双 Runner 与视觉后台 Worker 共享一份（质检 P2-5） */
    val settingsRepo: SettingsRepository by lazy { SettingsRepository(database, secretStore) }

    /** 全局唯一粗读任务管理：返回书架/换页不中断，start 前自动替换旧任务 */
    val roughReadRunner: RoughReadRunner by lazy {
        RoughReadRunner(
            db = database,
            context = applicationContext,
            settings = settingsRepo,
            chatJsonFn = AiClientChatJsonFn,
            scope = appScope,
            gate = aiGate,
        )
    }

    /** 全局唯一讲解队列：与粗读经 aiGate 互斥（M4b §2.7） */
    val noteRunner: NoteRunner by lazy {
        NoteRunner(
            db = database,
            context = applicationContext,
            settings = settingsRepo,
            chatTextFn = AiClientChatTextFn,
            scope = appScope,
            gate = aiGate,
        )
    }

    /** 全局唯一总结包任务：与前两者经 aiGate 互斥（M5 §2.5） */
    val summaryRunner: SummaryRunner by lazy {
        SummaryRunner(
            db = database,
            context = applicationContext,
            settings = settingsRepo,
            scope = appScope,
            gate = aiGate,
        )
    }

    /** 全局唯一全书总览任务：与前三者经 aiGate 互斥（M6 §2.6） */
    val overviewRunner: OverviewRunner by lazy {
        OverviewRunner(
            db = database,
            context = applicationContext,
            settings = settingsRepo,
            scope = appScope,
            gate = aiGate,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 诊断日志（数据安全轮）：崩溃捕获 + AI 任务失败原因，filesDir/logs/ 滚动 7 天；
        // 设置页「导出诊断日志」可把最近几天合并发给 AI 排障。须在任何 Runner 启动前挂好
        com.studyfriend.app.data.DiagLog.install(this)
        // 视觉后台队列断点续跑（OPT-F）：进程死亡/设备重启后，把仍有 PENDING 页的书
        // 重新挂上 WorkManager（KEEP 不打断在跑的任务；WorkManager 自身持久化是主保障）
        appScope.launch { VisionScheduler.restorePending(database, this@StudyApp) }
        // 复习到期提醒（功能补齐轮）：按设置注册/取消每日查询任务（默认开；关 = 取消注册）。
        // runCatching：WorkManager 未初始化的环境（Robolectric 测试沙盒）静默跳过，不拦启动
        appScope.launch {
            runCatching {
                if (settingsRepo.reviewReminderEnabled()) {
                    com.studyfriend.app.data.study.ReviewReminderWorker.schedule(this@StudyApp)
                } else {
                    com.studyfriend.app.data.study.ReviewReminderWorker.cancel(this@StudyApp)
                }
            }
        }
    }
}
