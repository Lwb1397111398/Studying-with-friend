package com.studyfriend.app.data

import androidx.room.withTransaction
import com.studyfriend.app.data.ai.SecretCrypto
import com.studyfriend.app.data.ai.SecretCryptoException
import com.studyfriend.app.data.ai.SecretStore
import com.studyfriend.app.data.ai.isStructurallyCorrupt
import com.studyfriend.app.data.db.SettingEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.importer.pdfpipeline.TextSourceRow
import com.studyfriend.app.data.importer.pdfpipeline.TocVisionParser
import com.studyfriend.app.data.importer.pdfpipeline.VisionTranscriber
import java.io.File
import javax.crypto.SecretKey

/** 设置页配置快照（Key 永不明文出库，只给"是否已存"） */
data class SettingsSnapshot(
    val baseUrl: String,
    val model: String,
    val temperature: Double,
    val hasKey: Boolean,
    /** 视觉兜底开关（OPT-E）：默认开；无 Key 时导入流程自动跳过视觉 */
    val visionEnabled: Boolean,
    /** 视觉转写模型：与聊天模型分离（聊天用的文本模型不一定带视觉） */
    val visionModel: String,
    /** 视觉专属 API 地址：空 = 跟主配置同一家（文本走 A 家、视觉兜底走 B 家时才填） */
    val visionBaseUrl: String,
    /** 是否已存视觉专属 Key（明文不出库） */
    val hasVisionKey: Boolean,
    /** 备用视觉组（R3 多模型轮转）：空 = 未配置备用；三键齐（地址/模型/专属 Key）才参与轮转 */
    val vision2BaseUrl: String,
    val vision2Model: String,
    /** 是否已存备用视觉专属 Key（明文不出库；备用组不回退主 Key——异家 Key 打异家 URL 无意义） */
    val hasVision2Key: Boolean,
    /** 应用自更新（M7）：是否已存 GitHub 只读令牌（明文不出库） */
    val hasGithubToken: Boolean,
    /** 发现新版本自动弹窗检查；默认开 */
    val updateAutoCheck: Boolean,
    /** 更新源 API 地址覆盖：仅测试/私有部署用，空 = 官方 GitHub API */
    val updateApiBase: String,
    /** 扫描书本地识别（OCR）开关（P6b S4）：默认开；off = 扫描书走现状路径（回滚方案载体） */
    val ocrEnabled: Boolean,
)

/**
 * 设置存取（计划 M3 §1/§2）：普通配置直存 settings 表；
 * API Key 经 SecretStore 加密为 Base64(iv||ct) 存 `api_key_enc`，明文不落库。
 * 解密失败不删密文（keystore 可能暂时故障）；仅"结构性损坏"（带来源标记但非合法
 * Base64，永远解不开）删除密文让用户重填（OPT-A Task A2）。
 */
class SettingsRepository(
    private val db: StudyDatabase,
    private val store: SecretStore,
) {

    suspend fun load(): SettingsSnapshot {
        val base = db.settingDao().get("api_base")?.value.orEmpty()
        val model = db.settingDao().get("api_model")?.value.orEmpty()
        val tempRaw = db.settingDao().get("api_temp")?.value.orEmpty()
        val temp = tempRaw.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.3
        return SettingsSnapshot(
            baseUrl = base.ifBlank { DEFAULT_BASE },
            model = model.ifBlank { DEFAULT_MODEL },
            temperature = temp,
            hasKey = db.settingDao().get(KEY_ENC)?.value != null,
            visionEnabled = db.settingDao().get(KEY_VISION_ENABLED)?.value != "false",
            visionModel = db.settingDao().get(KEY_VISION_MODEL)?.value
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_VISION_MODEL,
            visionBaseUrl = db.settingDao().get(KEY_VISION_BASE)?.value.orEmpty(),
            hasVisionKey = db.settingDao().get(KEY_VISION_KEY_ENC)?.value != null,
            vision2BaseUrl = db.settingDao().get(KEY_VISION2_BASE)?.value.orEmpty(),
            vision2Model = db.settingDao().get(KEY_VISION2_MODEL)?.value.orEmpty(),
            hasVision2Key = db.settingDao().get(KEY_VISION2_KEY_ENC)?.value != null,
            hasGithubToken = db.settingDao().get(KEY_GITHUB_TOKEN_ENC)?.value != null,
            updateAutoCheck = db.settingDao().get(KEY_UPDATE_AUTO)?.value != "false",
            updateApiBase = db.settingDao().get(KEY_UPDATE_API_BASE)?.value.orEmpty(),
            ocrEnabled = db.settingDao().get(KEY_OCR_ENABLED)?.value != "false",
        )
    }

    suspend fun save(baseUrl: String, model: String, temperature: Double, keyPlain: String?) {
        // 多键一次保存包成事务：中途失败不留半套配置（最终 QA P2-7）
        db.withTransaction {
            db.settingDao().upsert(SettingEntity("api_base", baseUrl.trim()))
            db.settingDao().upsert(SettingEntity("api_model", model.trim()))
            db.settingDao().upsert(SettingEntity("api_temp", temperature.toString()))
            if (!keyPlain.isNullOrBlank()) {
                val payload = store.encrypt(keyPlain.trim())
                db.settingDao().upsert(SettingEntity(KEY_ENC, payload))
            }
        }
    }

    /** 讲解详略档位（总计划 §1 目标 7）：简略/标准/深入，默认标准；坏值回退标准 */
    suspend fun detailLevel(): String =
        db.settingDao().get(KEY_DETAIL)?.value?.takeIf { it in DETAIL_LEVELS } ?: "标准"

    suspend fun saveDetailLevel(level: String) {
        if (level !in DETAIL_LEVELS) return
        db.settingDao().upsert(SettingEntity(KEY_DETAIL, level))
    }

    suspend fun clearKey() {
        db.settingDao().delete(KEY_ENC)
    }

    /**
     * 视觉兜底配置（OPT-E）：开关即点即存；模型/地址/Key 随"保存"按钮落库。
     * null = 保持不变（即点即存路径只传开关，不覆盖输入框里未保存的值）；
     * baseUrl 传空串 = 显式清空专属地址（回退主配置）；Key 只在非空时加密写入。
     */
    suspend fun saveVision(
        enabled: Boolean,
        model: String? = null,
        baseUrl: String? = null,
        keyPlain: String? = null,
    ) {
        db.withTransaction {
            db.settingDao().upsert(SettingEntity(KEY_VISION_ENABLED, if (enabled) "true" else "false"))
            if (!model.isNullOrBlank()) {
                db.settingDao().upsert(SettingEntity(KEY_VISION_MODEL, model.trim()))
            }
            if (baseUrl != null) {
                db.settingDao().upsert(SettingEntity(KEY_VISION_BASE, baseUrl.trim()))
            }
            if (!keyPlain.isNullOrBlank()) {
                val payload = store.encrypt(keyPlain.trim())
                db.settingDao().upsert(SettingEntity(KEY_VISION_KEY_ENC, payload))
            }
        }
    }

    /** 清除视觉专属 Key（之后视觉回退用主 Key） */
    suspend fun clearVisionKey() {
        db.settingDao().delete(KEY_VISION_KEY_ENC)
    }

    /**
     * 备用视觉组（R3 多模型轮转）：随"保存"按钮落库，模式同 [saveVision]。
     * null = 保持不变；Key 只在非空时加密写入。三键齐才参与轮转（见 buildVisionTranscribers）。
     */
    suspend fun saveVision2(
        baseUrl: String? = null,
        model: String? = null,
        keyPlain: String? = null,
    ) {
        db.withTransaction {
            if (baseUrl != null) {
                db.settingDao().upsert(SettingEntity(KEY_VISION2_BASE, baseUrl.trim()))
            }
            if (!model.isNullOrBlank()) {
                db.settingDao().upsert(SettingEntity(KEY_VISION2_MODEL, model.trim()))
            }
            if (!keyPlain.isNullOrBlank()) {
                val payload = store.encrypt(keyPlain.trim())
                db.settingDao().upsert(SettingEntity(KEY_VISION2_KEY_ENC, payload))
            }
        }
    }

    /** 清除备用视觉 Key（备用组三键缺一即退出轮转） */
    suspend fun clearVision2Key() {
        db.settingDao().delete(KEY_VISION2_KEY_ENC)
    }

    // ---- 扫描书本地识别（P6b S4）：开关 / 用户反馈计数 / OCR 版本标记 ----

    /** OCR 开关即点即存（off = 扫描书走现状路径，OCR 产出缓存不删） */
    suspend fun saveOcrEnabled(enabled: Boolean) {
        db.settingDao().upsert(SettingEntity(KEY_OCR_ENABLED, if (enabled) "true" else "false"))
    }

    /**
     * 用户反馈「识别结果有误？」计数 +1（v1.3 P1-1 回滚评估入口）：
     * 全局累计 + 按书（书名 key）双记；返回全局累计数，≥3 时导入完成提示升级
     * 「建议在设置中关闭 OCR」。
     */
    suspend fun addOcrFeedback(bookKey: String): Int {
        val total = (db.settingDao().get(KEY_OCR_FEEDBACK_TOTAL)?.value?.toIntOrNull() ?: 0) + 1
        val perBookKey = "$KEY_OCR_FEEDBACK_BOOKPrefix$bookKey"
        val perBook = (db.settingDao().get(perBookKey)?.value?.toIntOrNull() ?: 0) + 1
        db.withTransaction {
            db.settingDao().upsert(SettingEntity(KEY_OCR_FEEDBACK_TOTAL, total.toString()))
            db.settingDao().upsert(SettingEntity(perBookKey, perBook.toString()))
        }
        return total
    }

    suspend fun ocrFeedbackTotal(): Int =
        db.settingDao().get(KEY_OCR_FEEDBACK_TOTAL)?.value?.toIntOrNull() ?: 0

    /** 记录本次导入使用的 OCR 产出版本（引擎版本升级时供重导提示比对，v1.5 S1 条款） */
    suspend fun saveLastOcrVersion(version: Int) {
        db.settingDao().upsert(SettingEntity(KEY_OCR_LAST_VERSION, version.toString()))
    }

    suspend fun lastOcrVersion(): Int =
        db.settingDao().get(KEY_OCR_LAST_VERSION)?.value?.toIntOrNull() ?: TextSourceRow.PROD_OCR_V1

    // ---- 应用自更新（M7）：GitHub 只读令牌 / 自动检查开关 / 上次检查时间 ----

    /** 保存 GitHub 只读令牌：与 AI Key 同一套加密落库，明文不落盘 */
    suspend fun saveGithubToken(plain: String) {
        val payload = store.encrypt(plain.trim())
        db.settingDao().upsert(SettingEntity(KEY_GITHUB_TOKEN_ENC, payload))
    }

    suspend fun clearGithubToken() {
        db.settingDao().delete(KEY_GITHUB_TOKEN_ENC)
    }

    /** 解出 GitHub 令牌：解密失败返回 null 且不删密文（逻辑同 AI Key，仅结构性损坏才删） */
    suspend fun decryptGithubTokenOrNull(): String? {
        val payload = db.settingDao().get(KEY_GITHUB_TOKEN_ENC)?.value ?: return null
        if (isStructurallyCorrupt(payload)) {
            db.settingDao().delete(KEY_GITHUB_TOKEN_ENC)
            return null
        }
        return try {
            store.decrypt(payload)
        } catch (e: SecretCryptoException) {
            null
        }
    }

    /** 自动检查开关（即点即存） */
    suspend fun setUpdateAutoCheck(enabled: Boolean) {
        db.settingDao().upsert(SettingEntity(KEY_UPDATE_AUTO, if (enabled) "true" else "false"))
    }

    /** 每次真正联网检查后记录时间，自动检查据此节流（24 小时一次） */
    suspend fun markUpdateChecked() {
        db.settingDao().upsert(SettingEntity(KEY_UPDATE_LAST, System.currentTimeMillis().toString()))
    }

    suspend fun updateLastCheckedMs(): Long =
        db.settingDao().get(KEY_UPDATE_LAST)?.value?.toLongOrNull() ?: 0L

    /** 视觉兜底实际生效的 Key：专属 Key 优先，未配置回退主 Key（同一家时两处不用重复填） */
    suspend fun resolveVisionKeyOrNull(): String? =
        decryptVisionKeyOrNull() ?: decryptKeyOrNull()

    /**
     * 组装视觉转写器列表（R3 多模型轮转）：开关开且至少一组可用才非空。
     * 主组：专属 Key 优先、留空回退主 Key（同一家时两处不用重复填）；
     * 备用组：地址/模型/专属 Key 三键齐才入列（异家 Key 不回退主 Key）。
     * 导入同步路径与探针取首个；后台队列 Worker 用全列表做多模型并行轮转。
     */
    suspend fun buildVisionTranscribers(): List<VisionTranscriber> {
        val snap = load()
        if (!snap.visionEnabled) return emptyList()
        val list = mutableListOf<VisionTranscriber>()
        resolveVisionKeyOrNull()?.let { key ->
            val base = snap.visionBaseUrl.ifBlank { snap.baseUrl }
            list += VisionTranscriber(base, key, snap.visionModel)
        }
        val key2 = decryptVision2KeyOrNull()
        if (key2 != null && snap.vision2BaseUrl.isNotBlank() && snap.vision2Model.isNotBlank()) {
            list += VisionTranscriber(snap.vision2BaseUrl, key2, snap.vision2Model)
        }
        return list
    }

    /** 单转写器入口（导入同步路径/目录探针共用，取轮转列表首个） */
    suspend fun buildVisionTranscriber(): VisionTranscriber? = buildVisionTranscribers().firstOrNull()

    /**
     * 组装目录探针（P3b-1）：与 [buildVisionTranscriber] 同源快照（开关/Key/专属地址
     * 留空回退主配置），视觉不可用 → null（探针静默跳过）。cacheDir/renderFn 由调用方
     * 绑定（探针逐页渲染→调用→释放，内存峰值 ≤1 页图）。
     */
    suspend fun buildTocVisionParser(cacheDir: File, renderFn: (Int) -> String): TocVisionParser? {
        val snap = load()
        if (!snap.visionEnabled) return null
        val key = resolveVisionKeyOrNull() ?: return null
        val base = snap.visionBaseUrl.ifBlank { snap.baseUrl }
        return TocVisionParser(base, key, snap.visionModel, cacheDir, renderFn = renderFn)
    }

    /**
     * 解出明文 Key 供 AiClient 使用。解密失败仅返回 null、保留密文（OPT-A）：
     * keystore 可能只是暂时故障，删除会毁掉本可恢复的 Key；唯一例外是
     * 结构性损坏（带 k1:/s1: 标记但 body 非合法 Base64）——任何实现都解不开，删除。
     */
    /** 解视觉专属 Key，逻辑同 [decryptKeyOrNull]（仅结构性损坏才删密文） */
    suspend fun decryptVisionKeyOrNull(): String? {
        val payload = db.settingDao().get(KEY_VISION_KEY_ENC)?.value ?: return null
        if (isStructurallyCorrupt(payload)) {
            db.settingDao().delete(KEY_VISION_KEY_ENC)
            return null
        }
        return try {
            store.decrypt(payload)
        } catch (e: SecretCryptoException) {
            null
        }
    }

    /** 解备用视觉专属 Key，逻辑同 [decryptVisionKeyOrNull] */
    suspend fun decryptVision2KeyOrNull(): String? {
        val payload = db.settingDao().get(KEY_VISION2_KEY_ENC)?.value ?: return null
        if (isStructurallyCorrupt(payload)) {
            db.settingDao().delete(KEY_VISION2_KEY_ENC)
            return null
        }
        return try {
            store.decrypt(payload)
        } catch (e: SecretCryptoException) {
            null
        }
    }

    suspend fun decryptKeyOrNull(): String? {
        val payload = db.settingDao().get(KEY_ENC)?.value ?: return null
        if (isStructurallyCorrupt(payload)) {
            db.settingDao().delete(KEY_ENC)
            return null
        }
        return try {
            store.decrypt(payload)
        } catch (e: SecretCryptoException) {
            null
        }
    }

    companion object {
        const val KEY_ENC = "api_key_enc"
        const val KEY_DETAIL = "note_detail"
        const val KEY_VISION_ENABLED = "vision_enabled"
        const val KEY_VISION_MODEL = "vision_model"
        const val KEY_VISION_BASE = "vision_base"
        const val KEY_VISION_KEY_ENC = "vision_key_enc"
        // 备用视觉组（R3 多模型轮转）
        const val KEY_VISION2_BASE = "vision2_base"
        const val KEY_VISION2_MODEL = "vision2_model"
        const val KEY_VISION2_KEY_ENC = "vision2_key_enc"
        // 扫描书本地识别（P6b S4）
        const val KEY_OCR_ENABLED = "ocr_enabled"
        const val KEY_OCR_FEEDBACK_TOTAL = "ocr_feedback_total"
        const val KEY_OCR_FEEDBACK_BOOKPrefix = "ocr_feedback_book_"
        const val KEY_OCR_LAST_VERSION = "ocr_last_version"
        // 应用自更新（M7）
        const val KEY_GITHUB_TOKEN_ENC = "github_token_enc"
        const val KEY_UPDATE_AUTO = "update_auto_check"
        const val KEY_UPDATE_LAST = "update_last_check_ms"
        const val KEY_UPDATE_API_BASE = "update_api_base"
        val DETAIL_LEVELS = listOf("简略", "标准", "深入")
        const val DEFAULT_BASE = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        /** 视觉兜底默认模型（OPT-E）：龙猫 2.5 预览版，实测书页整页转写可用 */
        const val DEFAULT_VISION_MODEL = "LongCat-2.5-Preview"

        /** 快捷预设（label, baseUrl, model）：设置页一键填入，保存前不落库 */
        val PRESETS = listOf(
            Triple("美团 · LongCat（推荐）", "https://api.longcat.chat/openai/v1", "LongCat-2.5-Preview"),
            Triple("商汤 · GLM-5.2", "https://token.sensenova.cn/v1", "glm-5.2"),
            Triple("商汤 · DeepSeek-V4", "https://token.sensenova.cn/v1", "deepseek-v4-flash"),
            Triple("商汤 · Flash-Lite（快）", "https://token.sensenova.cn/v1", "sensenova-6.8-flash-lite"),
        )

        /** 仅供测试/协议演示生成密钥；线上走 AndroidKeyStore */
        fun newProtocolKey(): SecretKey = SecretCrypto.newKey()
    }
}
