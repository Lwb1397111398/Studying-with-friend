package com.studyfriend.app.data.ai

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SecretCryptoTest {

    @Test
    fun roundTrip_preservesChineseAndSymbols() {
        val key = SecretCrypto.newKey()
        val plain = "sk-密钥Key_123!@# 中文"
        val payload = SecretCrypto.encrypt(plain, key)
        assertEquals(plain, SecretCrypto.decrypt(payload, key))
    }

    @Test
    fun sameInputTwoCiphertexts_differByRandomIv() {
        val key = SecretCrypto.newKey()
        assertNotEquals(SecretCrypto.encrypt("同一明文", key), SecretCrypto.encrypt("同一明文", key))
    }

    @Test(expected = SecretCryptoException::class)
    fun tamperedCiphertext_throws() {
        val key = SecretCrypto.newKey()
        val payload = SecretCrypto.encrypt("机密内容", key)
        val raw = Base64.getDecoder().decode(payload)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        SecretCrypto.decrypt(Base64.getEncoder().encodeToString(raw), key)
    }

    @Test(expected = SecretCryptoException::class)
    fun wrongKey_throws() {
        val payload = SecretCrypto.encrypt("机密内容", SecretCrypto.newKey())
        SecretCrypto.decrypt(payload, SecretCrypto.newKey())
    }

    @Test(expected = SecretCryptoException::class)
    fun tooShortPayload_throws() {
        // 不足 12B IV 的 payload 直接拒绝
        SecretCrypto.decrypt(Base64.getEncoder().encodeToString(ByteArray(8)), SecretCrypto.newKey())
    }

    @Test(expected = SecretCryptoException::class)
    fun nonBase64Payload_throws() {
        SecretCrypto.decrypt("这不是@@Base64!!", SecretCrypto.newKey())
    }
}
