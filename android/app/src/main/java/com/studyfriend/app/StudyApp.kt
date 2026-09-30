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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class StudyApp : Application() {
    val database: StudyDatabase by lazy { StudyDatabase.build(this) }

    /** 应用级协程作用域：页面退出也不取消的写库/UI 外任务（标记读完等） */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 全局 AI 互斥：粗读与讲解共用一把锁，任何时刻只跑一个 AI 任务（M4b §2.5） */
    val aiGate: AiGate = AiGate()

    /** 应用级密钥仓单例（OPT-A）：keystore 优先、软件密钥兜底；设置页与双 Runner 共享 */
    val secretStore: SecretStore by lazy { ResilientSecretStore(applicationContext) }

    /** 设置仓库（无状态、读时解密）：双 Runner 共享一份（质检 P2-5） */
    private val settingsRepo: SettingsRepository by lazy { SettingsRepository(database, secretStore) }

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
}
