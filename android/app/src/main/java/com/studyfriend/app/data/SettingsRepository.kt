package com.studyfriend.app.data

import androidx.room.withTransaction
import com.studyfriend.app.data.ai.SecretCrypto
import com.studyfriend.app.data.ai.SecretCryptoException
import com.studyfriend.app.data.ai.SecretStore
import com.studyfriend.app.data.db.SettingEntity
import com.studyfriend.app.data.db.StudyDatabase
import javax.crypto.SecretKey

/** 设置页配置快照（Key 永不明文出库，只给"是否已存"） */
data class SettingsSnapshot(
    val baseUrl: String,
    val model: String,
    val temperature: Double,
    val hasKey: Boolean,
)

/**
 * 设置存取（计划 M3 §1/§2）：普通配置直存 settings 表；
 * API Key 经 SecretStore 加密为 Base64(iv||ct) 存 `api_key_enc`，明文不落库。
 * 解密发现密钥失效（换机/清数据）时删掉旧密文，让用户重填。
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

    /** 解出明文 Key 供 AiClient 使用；密钥失效时删掉旧密文并返回 null */
    suspend fun decryptKeyOrNull(): String? {
        val payload = db.settingDao().get(KEY_ENC)?.value ?: return null
        return try {
            store.decrypt(payload)
        } catch (e: SecretCryptoException) {
            db.settingDao().delete(KEY_ENC)
            null
        }
    }

    companion object {
        const val KEY_ENC = "api_key_enc"
        const val KEY_DETAIL = "note_detail"
        val DETAIL_LEVELS = listOf("简略", "标准", "深入")
        const val DEFAULT_BASE = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        /** 仅供测试/协议演示生成密钥；线上走 AndroidKeyStore */
        fun newProtocolKey(): SecretKey = SecretCrypto.newKey()
    }
}
