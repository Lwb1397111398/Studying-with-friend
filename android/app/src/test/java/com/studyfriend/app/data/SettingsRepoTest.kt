package com.studyfriend.app.data

import androidx.test.core.app.ApplicationProvider
import com.studyfriend.app.data.ai.FakeSecretStore
import com.studyfriend.app.data.db.StudyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 设置存取（计划 M3 §5）：4 键 upsert/读回/覆盖、加密 Key 往返、失效删键 */
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
    fun decryptKey_fails_deletesCipherAndReturnsNull() = runBlocking {
        val database = db()
        // 失效场景：decrypt 恒抛异常（换机/清数据后的 KeyStore）
        val repo = SettingsRepository(database, FakeSecretStore(failDecrypt = true))
        try {
            repo.save("https://b/v1", "m", 0.3, "sk-旧Key")
            assertTrue(repo.load().hasKey)

            assertNull(repo.decryptKeyOrNull())
            // 密文已被删除，hasKey 归 false
            assertFalse(repo.load().hasKey)
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
}
