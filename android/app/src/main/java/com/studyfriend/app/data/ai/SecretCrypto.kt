package com.studyfriend.app.data.ai

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 加解密失败（密钥不匹配/密文被篡改/payload 非法）时抛出 */
class SecretCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AES/GCM 协议层（纯 JVM，可单测）：payload = Base64(iv[12] || ciphertext)。
 * Base64 全链路只用 java.util.Base64（含 padding、无换行），禁止 android.util.Base64（flag 差异会破坏往返）。
 * KeyStoreSecretStore 仅替换密钥来源，协议不变（计划 M3 §2）。
 */
object SecretCrypto {

    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encrypt(plain: String, key: SecretKey): String {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    fun decrypt(payload: String, key: SecretKey): String {
        val raw = try {
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            throw SecretCryptoException("密文不是合法 Base64", e)
        }
        if (raw.size <= IV_LEN) throw SecretCryptoException("密文长度不合法")
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val plain = try {
            cipher.doFinal(ct)
        } catch (e: Exception) {
            throw SecretCryptoException("密文校验失败（可能被篡改或密钥不匹配）", e)
        }
        return String(plain, Charsets.UTF_8)
    }

    /** JVM 测试与协议演示用随机 AES 密钥；线上密钥来自 AndroidKeyStore */
    fun newKey(): SecretKey = KeyGenerator.getInstance("AES")
        .apply { init(256) }
        .generateKey()
}
