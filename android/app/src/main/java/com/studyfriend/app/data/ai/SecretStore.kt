package com.studyfriend.app.data.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException

interface SecretStore {
    fun encrypt(plain: String): String
    fun decrypt(payload: String): String
}

/**
 * AndroidKeyStore AES-256 密钥 + SecretCrypto 协议（计划 M3 §2）。
 * 取钥/解密任何异常都视为密钥失效（换机/清数据/密文损坏），抛给上层删键重填；
 * CancellationException 原样放行。Robolectric 的 AndroidKeyStore 不可靠，
 * 协议层由 SecretCryptoTest 覆盖，本实现由模拟器/真机 E2E 验证（M7）。
 */
class KeyStoreSecretStore : SecretStore {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return newKey()
    }

    /** 生成新密钥。协议固定 Base64(iv||ct)，SecretCrypto 自带随机 IV，
     *  必须开 caller nonce，否则 keystore2 以 CALLER_NONCE_PROHIBITED 拒绝加密 */
    private fun newKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String = try {
        SecretCrypto.encrypt(plain, key())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 旧版密钥没开 caller nonce（该版保存必然失败，不可能留下旧密文）：
        // 删掉重建一次自愈；仍失败才报给上层
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(ALIAS)
            SecretCrypto.encrypt(plain, newKey())
        } catch (e2: CancellationException) {
            throw e2
        } catch (e2: Exception) {
            throw SecretCryptoException("密钥不可用，无法保存 API Key", e2)
        }
    }

    override fun decrypt(payload: String): String = try {
        SecretCrypto.decrypt(payload, key())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SecretCryptoException("密钥已失效，请重新填写 API Key", e)
    }

    private companion object {
        const val ALIAS = "studyfriend_api_key"
    }
}

/**
 * 密钥来源弹性链（OPT-A）：AndroidKeyStore 优先；任何异常（无 provider / keystore 损坏 /
 * caller-nonce 被拒 / 生成失败）回退软件密钥文件，保证保存链无单点失败（实测 P1）。
 * 密文来源前缀 k1:/s1:（无前缀=遗留 k1）决定解密用哪侧实现；标记实现失败即抛给上层
 * 提示重填，不做静默删除（keystore 可能只是暂时故障，删除会毁掉本可恢复的 Key）。
 * 保存侧两条路径全败时在此统一包装 SecretCryptoException（文案含底层原因）。
 * 软件密钥存 noBackupFilesDir（沙箱内且不入云备份/设备迁移），权衡记录于 docs/plans/OPT-总计划.md §3。
 */
class ResilientSecretStore(context: android.content.Context) : SecretStore {

    private val keystore = KeyStoreSecretStore()
    private val soft = SoftwareKeyFileStore(context)

    override fun encrypt(plain: String): String = try {
        MARK_KEYSTORE + keystore.encrypt(plain)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // keystore 整链失败 → 软件密钥兜底，密文带 s1 标记
        try {
            MARK_SOFTWARE + soft.encrypt(plain)
        } catch (e2: CancellationException) {
            throw e2
        } catch (e2: Exception) {
            // 两条路径都失败：统一在此包装最终失败（含底层原因），调用方拿到 SecretCryptoException
            throw SecretCryptoException("无法保存 API Key（密钥不可用：${e2.message ?: e.message ?: "未知"}）", e2)
        }
    }

    override fun decrypt(payload: String): String {
        val (marker, body) = splitMarker(payload)
        val err = try {
            return when (marker) {
                MARK_SOFTWARE -> soft.decrypt(body)
                else -> keystore.decrypt(body) // k1 与无前缀遗留密文同走 KeyStore
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        throw SecretCryptoException(
            // err 本身可能已带"请重新填写"文案，取其 cause 的技术性原因，避免双层包装文案重复
            "密钥已失效，请重新填写 API Key（${err.cause?.message ?: err.message ?: "密文无法解读"}）",
            err,
        )
    }

    private fun splitMarker(payload: String): Pair<String, String> =
        splitCipherMarker(payload) ?: (MARK_KEYSTORE to payload) // 遗留密文无前缀
}

/** 密文来源前缀：k1 = AndroidKeyStore（含无前缀遗留密文），s1 = 软件密钥文件 */
internal const val MARK_KEYSTORE = "k1:"
internal const val MARK_SOFTWARE = "s1:"

/** 按前缀拆分密文来源标记；无前缀（遗留 k1 密文）返回 null */
internal fun splitCipherMarker(payload: String): Pair<String, String>? = when {
    payload.startsWith(MARK_KEYSTORE) -> MARK_KEYSTORE to payload.removePrefix(MARK_KEYSTORE)
    payload.startsWith(MARK_SOFTWARE) -> MARK_SOFTWARE to payload.removePrefix(MARK_SOFTWARE)
    else -> null
}

/**
 * 结构性损坏：带来源标记但 body 不是合法 Base64——密钥来源无关，任何实现都永远解不开，
 * 删除是唯一出路；其余解密失败一律保留密文（keystore 可能只是暂时故障，重启可愈）。
 */
internal fun isStructurallyCorrupt(payload: String): Boolean {
    val body = splitCipherMarker(payload)?.second ?: return false
    return try {
        java.util.Base64.getDecoder().decode(body)
        false
    } catch (e: IllegalArgumentException) {
        true
    }
}

/** AndroidKeyStore 不可用时的兜底：随机 AES-256 密钥存 noBackupFilesDir（Base64），协议不变 */
class SoftwareKeyFileStore(context: android.content.Context) : SecretStore {

    private val file = java.io.File(context.noBackupFilesDir, "secret_key.bin")

    @Synchronized
    private fun key(): javax.crypto.SecretKey {
        if (file.exists()) {
            try {
                val raw = java.util.Base64.getDecoder().decode(file.readText())
                return javax.crypto.spec.SecretKeySpec(raw, "AES")
            } catch (e: Exception) {
                // 密钥文件损坏（写入中断/磁盘满）：与 keystore 自愈同语义，删除重建
                file.delete()
            }
        }
        val k = SecretCrypto.newKey()
        file.parentFile?.mkdirs()
        file.writeText(java.util.Base64.getEncoder().encodeToString(k.encoded))
        return k
    }

    override fun encrypt(plain: String): String = SecretCrypto.encrypt(plain, key())

    override fun decrypt(payload: String): String = SecretCrypto.decrypt(payload, key())
}

/** 失效路径单测用：可配置 decrypt 抛异常的假实现（可逆"加密"用 reverse 模拟） */
class FakeSecretStore(
    private val failDecrypt: Boolean = false,
) : SecretStore {

    override fun encrypt(plain: String): String = plain.reversed()

    override fun decrypt(payload: String): String {
        if (failDecrypt) throw SecretCryptoException("模拟密钥失效")
        return payload.reversed()
    }
}
