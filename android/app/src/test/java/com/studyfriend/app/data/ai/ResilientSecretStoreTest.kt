package com.studyfriend.app.data.ai

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric 无 AndroidKeyStore provider：ResilientSecretStore 必须自动走软件密钥兜底 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResilientSecretStoreTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun keystoreUnavailable_fallsBackToSoftwareKeyAndRoundTrips() {
        val store = ResilientSecretStore(context)
        val payload = store.encrypt("ak_test_123")
        // 兜底路径写的密文必须带 s1 来源标记
        assertTrue("兜底密文应有 s1 前缀", payload.startsWith("s1:"))
        assertEquals("ak_test_123", store.decrypt(payload))
    }

    @Test
    fun softwareKeyPayload_persistsAcrossInstances() {
        val payload = ResilientSecretStore(context).encrypt("ak_long_key")
        assertEquals("ak_long_key", ResilientSecretStore(context).decrypt(payload))
    }

    @Test
    fun legacyPayloadWithoutPrefix_throwsSecretCryptoException() {
        val store = ResilientSecretStore(context)
        assertThrows(SecretCryptoException::class.java) { store.decrypt("AAAA" + "not-base64!!") }
    }

    @Test
    fun corruptSoftwarePayload_throwsSecretCryptoException() {
        val store = ResilientSecretStore(context)
        store.encrypt("warmup") // 确保密钥文件已生成
        assertThrows(SecretCryptoException::class.java) { store.decrypt("s1:bad-payload") }
    }

    @Test
    fun corruptKeyFile_selfHealsOnNextOperation() {
        ResilientSecretStore(context).encrypt("warmup") // 确保密钥文件已生成
        // 模拟写入中断/磁盘满导致的密钥文件损坏
        java.io.File(context.noBackupFilesDir, "secret_key.bin").writeText("!!!garbage!!!")
        val store = ResilientSecretStore(context)
        val payload = store.encrypt("ak_after_heal") // key() 读到坏文件 → 删除重建
        assertEquals("ak_after_heal", store.decrypt(payload))
    }
}
