package com.studyfriend.app.data

import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.ai.FakeSecretStore
import com.studyfriend.app.data.db.SettingEntity
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 设置存取（计划 M3 §5）：4 键 upsert/读回/覆盖、加密 Key 往返、失效不删密文（仅结构性损坏删除） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepoTest {

    private fun db() = StudyDatabase.build(ApplicationProvider.getApplicationContext())

    @Test
    fun detailLevel_defaultStandard_roundTripInvalidFallback() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            assertEquals("标准", repo.detailLevel())
            repo.saveDetailLevel("深入")
            assertEquals("深入", repo.detailLevel())
            repo.saveDetailLevel("简略")
            assertEquals("简略", repo.detailLevel())
            // 非法档不落库，保持上一个合法值
            repo.saveDetailLevel("胡写的")
            assertEquals("简略", repo.detailLevel())
        } finally {
            database.close()
        }
    }

    @Test
    fun saveAndLoad_roundTrip() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            val loaded = repo.load()
            assertEquals(SettingsRepository.DEFAULT_BASE, loaded.baseUrl)
            assertEquals(SettingsRepository.DEFAULT_MODEL, loaded.model)
            assertEquals(0.3, loaded.temperature, 1e-9)
            assertFalse(loaded.hasKey)

            repo.save("https://api.example.com/v1", "deepseek-chat", 0.5, "sk-我的Key")
            val after = repo.load()
            assertEquals("https://api.example.com/v1", after.baseUrl)
            assertEquals("deepseek-chat", after.model)
            assertEquals(0.5, after.temperature, 1e-9)
            assertTrue(after.hasKey)

            // 明文不落库：settings 表里只有密文
            val raw = database.settingDao().get(SettingsRepository.KEY_ENC)!!.value
            assertFalse(raw.contains("sk-我的Key"))

            // save 不传 Key 时不清掉已有密文
            repo.save("https://api.example.com/v2", "m2", 0.1, null)
            assertTrue(repo.load().hasKey)
        } finally {
            database.close()
        }
    }

    @Test
    fun decryptKey_returnsPlain_whenStoreHealthy() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            repo.save("https://b/v1", "m", 0.3, "sk-明文Key")
            assertEquals("sk-明文Key", repo.decryptKeyOrNull())
        } finally {
            database.close()
        }
    }

    @Test
    fun decryptFailure_keepsCipherAndReturnsNull() = runBlocking {
        val database = db()
        // 失效场景：decrypt 恒抛异常（换机/清数据后的 KeyStore）
        val repo = SettingsRepository(database, FakeSecretStore(failDecrypt = true))
        try {
            repo.save("https://x/v1", "m", 0.3, "plain-key")
            assertNull(repo.decryptKeyOrNull())
            // FakeSecretStore 加密产物（plain.reversed()）无前缀 = 遗留密文 → 解密失败走保留路径
            assertNotNull(database.settingDao().get(SettingsRepository.KEY_ENC)?.value)
        } finally {
            database.close()
        }
    }

    @Test
    fun markedCipherWithIllegalBase64Body_isDeleted() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            database.settingDao().upsert(SettingEntity(SettingsRepository.KEY_ENC, "s1:!!!not-base64!!!"))
            assertNull(repo.decryptKeyOrNull())
            assertNull(database.settingDao().get(SettingsRepository.KEY_ENC)) // 已删除
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyCipher_decryptFailure_isKept() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore(failDecrypt = true))
        try {
            database.settingDao().upsert(SettingEntity(SettingsRepository.KEY_ENC, "遗留原始串无前缀"))
            assertNull(repo.decryptKeyOrNull())
            assertEquals("遗留原始串无前缀", database.settingDao().get(SettingsRepository.KEY_ENC)?.value)
        } finally {
            database.close()
        }
    }

    @Test
    fun clearKey_removesEntry() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            repo.save("https://b/v1", "m", 0.3, "sk-x")
            assertTrue(repo.load().hasKey)
            repo.clearKey()
            assertFalse(repo.load().hasKey)
            assertNull(repo.decryptKeyOrNull())
        } finally {
            database.close()
        }
    }

    @Test
    fun presets_containLongCat_withCorrectUrlAndModel() {
        val lc = SettingsRepository.PRESETS.firstOrNull { it.first.contains("LongCat") }
        assertNotNull(lc)
        assertEquals("https://api.longcat.chat/openai/v1", lc!!.second)
        assertEquals("LongCat-2.5-Preview", lc.third)
        // 验收 3：LongCat 必须居首位（全列表唯一推荐位）
        assertEquals("美团 · LongCat（推荐）", SettingsRepository.PRESETS.first().first)
    }

    @Test
    fun visionIndependentBaseAndKey_roundTripAndFallback() = runBlocking {
        // 视觉专属地址/Key 独立存取；未配置时视觉 Key 回退主 Key；清除后同样回退
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            repo.save("https://a.example.com/v1", "m1", 0.3, "main-key-123")
            assertFalse(repo.load().hasVisionKey)
            assertEquals("main-key-123", repo.resolveVisionKeyOrNull())

            repo.saveVision(true, "vision-m", "https://vision.example.com/v1", "vision-key-789")
            val snap = repo.load()
            assertTrue(snap.hasVisionKey)
            assertEquals("https://vision.example.com/v1", snap.visionBaseUrl)
            assertEquals("vision-m", snap.visionModel)
            assertEquals("vision-key-789", repo.decryptVisionKeyOrNull())
            assertEquals("vision-key-789", repo.resolveVisionKeyOrNull())
            assertEquals("main-key-123", repo.decryptKeyOrNull()) // 主 Key 不受影响

            // 开关即点即存（其余参数缺省）不覆盖已存的地址与 Key
            repo.saveVision(false)
            val snap2 = repo.load()
            assertFalse(snap2.visionEnabled)
            assertEquals("https://vision.example.com/v1", snap2.visionBaseUrl)
            assertEquals("vision-key-789", repo.resolveVisionKeyOrNull())

            repo.clearVisionKey()
            assertFalse(repo.load().hasVisionKey)
            assertEquals("main-key-123", repo.resolveVisionKeyOrNull())
        } finally {
            database.close()
        }
    }
}
