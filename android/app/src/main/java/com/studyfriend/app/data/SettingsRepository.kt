package com.studyfriend.app.data

import androidx.room.withTransaction
import com.studyfriend.app.data.ai.SecretCrypto
import com.studyfriend.app.data.ai.SecretCryptoException
import com.studyfriend.app.data.ai.SecretStore
import com.studyfriend.app.data.ai.isStructurallyCorrupt
import com.studyfriend.app.data.db.SettingEntity
import com.studyfriend.app.data.db.StudyDatabase
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

    /** 视觉兜底实际生效的 Key：专属 Key 优先，未配置回退主 Key（同一家时两处不用重复填） */
    suspend fun resolveVisionKeyOrNull(): String? =
        decryptVisionKeyOrNull() ?: decryptKeyOrNull()

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
