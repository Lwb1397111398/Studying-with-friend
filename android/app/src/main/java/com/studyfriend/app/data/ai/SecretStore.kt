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
 * 协议层由 SecretCryptoTest 覆盖，本实现 M7 真机冒烟验证。
 */
class KeyStoreSecretStore : SecretStore {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String = try {
        SecretCrypto.encrypt(plain, key())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SecretCryptoException("密钥不可用，无法保存 API Key", e)
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
