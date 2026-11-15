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

/**
 * 设置存取测试：讲解详略四键 + LLM 档案体系（2026-11-15 多模型轮）——
 * 档案 CRUD、resolveLlm 各角色回退、旧配置一次性迁移（幂等）、Key 加密与失效策略。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepoTest {

    /**
     * 测试专用库：inMemory + 单线程 query/transaction executor——Robolectric legacy SQLite
     * shadow 对「多连接写交替」的 busy timeout 极短，连续事务会偶发 SQLITE_BUSY（生产真机
     * WAL 不存在此问题），单线程串行根治。MigrationTest 等仍用 StudyDatabase.build 不受影响。
     */
    private val dbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun db(): StudyDatabase =
        androidx.room.Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StudyDatabase::class.java,
        )
            .setQueryExecutor(dbExecutor)
            .setTransactionExecutor(dbExecutor)
            .build()

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

    // ---- 档案体系 ----

    @Test
    fun loadLlm_freshInstall_createsDefaultMainProfile_idempotent() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            val s = repo.loadLlm()
            assertEquals("全新安装：自动建一个默认主力档案", 1, s.profiles.size)
            assertEquals("主力", s.profiles[0].name)
            assertEquals(SettingsRepository.DEFAULT_BASE, s.profiles[0].baseUrl)
            assertEquals(SettingsRepository.DEFAULT_MODEL, s.profiles[0].model)
            assertTrue(s.keyIds.isEmpty())
            // 没 Key 时解析不可用
            assertNull(repo.resolveLlm(LlmRole.MAIN))
            // 再读不重复建档（迁移幂等）
            assertEquals(1, repo.loadLlm().profiles.size)
        } finally {
            database.close()
        }
    }

    @Test
    fun saveProfile_keyRoundTrip_plaintextNeverOnDisk() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            repo.seedMainProfile("https://api.example.com/v1", "deepseek-chat", 0.5, "sk-我的Key")
            val s = repo.loadLlm()
            assertEquals(1, s.profiles.size)
            assertEquals("https://api.example.com/v1", s.profiles[0].baseUrl)
            assertEquals("deepseek-chat", s.profiles[0].model)
            assertEquals(1, s.keyIds.size)

            val llm = repo.resolveLlm(LlmRole.MAIN)!!
            assertEquals("deepseek-chat", llm.model)
            assertEquals("sk-我的Key", llm.apiKey)
            assertEquals(0.5, llm.temperature, 1e-9)

            // 明文不落库：settings 表里只有密文
            val raw = database.settingDao().get("llm_key_${llm.profileId}_enc")!!.value
            assertFalse(raw.contains("sk-我的Key"))

            // 再保存不传 Key：旧 Key 保持（不清除语义）
            repo.saveProfile(s.profiles[0].copy(baseUrl = "https://api.example.com/v2"), null)
            assertEquals("https://api.example.com/v2", repo.resolveLlm(LlmRole.MAIN)!!.baseUrl)
            assertEquals("sk-我的Key", repo.resolveLlm(LlmRole.MAIN)!!.apiKey)
        } finally {
            database.close()
        }
    }

    @Test
    fun resolveLlm_roleFallbacks_andVision2OptIn() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            repo.seedMainProfile("https://a.example.com/v1", "main-model", 0.3, "main-key")
            // 讲解/视觉未指派 = 跟随主力（模型取主力档案）
            assertEquals("main-model", repo.resolveLlm(LlmRole.NOTE)!!.model)
            assertEquals("main-model", repo.resolveLlm(LlmRole.VISION)!!.model)
            // 备用视觉未指派 = 不启用
            assertNull(repo.resolveLlm(LlmRole.VISION2))

            // 指派讲解到独立档案（Key 与主力不同家也各拿各的）
            repo.saveProfile(LlmProfile("note1", "讲解", "https://b.example.com/v1", "cheap-model"), "note-key")
            repo.saveAssign(repo.loadLlm().assign.copy(note = "note1"))
            val note = repo.resolveLlm(LlmRole.NOTE)!!
            assertEquals("cheap-model", note.model)
            assertEquals("https://b.example.com/v1", note.baseUrl)
            assertEquals("note-key", note.apiKey)
            // 主力不受指派影响
            assertEquals("main-model", repo.resolveLlm(LlmRole.MAIN)!!.model)

            // 指派的档案没 Key → 该角色不可用（不偷偷回退主力 Key：异家 Key 打异家 URL 无意义）
            repo.saveProfile(LlmProfile("note2", "讲解2", "https://b.example.com/v1", "m2"), null)
            repo.saveAssign(repo.loadLlm().assign.copy(note = "note2"))
            assertNull(repo.resolveLlm(LlmRole.NOTE))

            // 指派失效（档案不存在）→ 回退主力
            repo.saveAssign(repo.loadLlm().assign.copy(note = "ghost-id"))
            assertEquals("main-model", repo.resolveLlm(LlmRole.NOTE)!!.model)
        } finally {
            database.close()
        }
    }

    @Test
    fun deleteProfile_clearsAssignRefsAndKey() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            repo.seedMainProfile("https://a.example.com/v1", "main-model", 0.3, "main-key")
            repo.saveProfile(LlmProfile("p2", "讲解", "https://b.example.com/v1", "m2"), "k2")
            repo.saveAssign(repo.loadLlm().assign.copy(note = "p2", vision = "p2", vision2 = "p2"))

            repo.deleteProfile("p2")
            val s = repo.loadLlm()
            assertEquals("只剩主力档案", 1, s.profiles.size)
            assertEquals("讲解指拔回收=跟随主力", "", s.assign.note)
            assertEquals("", s.assign.vision)
            assertEquals("备用指派回收=不启用", "", s.assign.vision2)
            assertNull("档案 Key 一并清除", database.settingDao().get("llm_key_p2_enc"))
        } finally {
            database.close()
        }
    }

    @Test
    fun profileKey_decryptFailureKept_structuralCorruptDeleted() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore(failDecrypt = true))
        try {
            val id = "p1"
            repo.saveProfile(LlmProfile(id, "主力", "https://a/v1", "m"), "plain-key")
            // FakeSecretStore 加密产物无前缀 = 遗留密文口径 → 解密失败保留密文
            assertNull(repo.resolveLlm(LlmRole.MAIN))
            assertNotNull(database.settingDao().get("llm_key_${id}_enc")?.value)

            // 结构性损坏（带来源标记但非合法 Base64）→ 删除让用户重填
            database.settingDao().upsert(SettingEntity("llm_key_${id}_enc", "s1:!!!not-base64!!!"))
            assertNull(repo.decryptProfileKeyOrNull(id))
            assertNull(database.settingDao().get("llm_key_${id}_enc"))
        } finally {
            database.close()
        }
    }

    @Test
    fun clearProfileKey_removesEntry() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            repo.seedMainProfile("https://a/v1", "m", 0.3, "sk-x")
            val id = repo.loadLlm().profiles[0].id
            assertTrue(repo.hasProfileKey(id))
            repo.clearProfileKey(id)
            assertFalse(repo.hasProfileKey(id))
            assertNull(repo.resolveLlm(LlmRole.MAIN))
        } finally {
            database.close()
        }
    }

    // ---- 旧配置迁移 ----

    /** 手工铺一套完整旧版配置（老用户升级现场） */
    private suspend fun seedLegacy(database: StudyDatabase, noteModel: String = "cheap-model") {
        val dao = database.settingDao()
        dao.upsert(SettingEntity("api_base", "https://old.example.com/v1"))
        dao.upsert(SettingEntity("api_model", "old-main"))
        dao.upsert(SettingEntity("api_temp", "0.4"))
        dao.upsert(SettingEntity(SettingsRepository.KEY_ENC, "k1:MAINKEYCIPHER"))
        dao.upsert(SettingEntity(SettingsRepository.KEY_NOTE_MODEL, noteModel))
        dao.upsert(SettingEntity(SettingsRepository.KEY_VISION_MODEL, "vision-old"))
        dao.upsert(SettingEntity(SettingsRepository.KEY_VISION_BASE, "https://vision.example.com/v1"))
        dao.upsert(SettingEntity(SettingsRepository.KEY_VISION_KEY_ENC, "k1:VISIONKEYCIPHER"))
        dao.upsert(SettingEntity(SettingsRepository.KEY_VISION2_BASE, "https://v2.example.com/v1"))
        dao.upsert(SettingEntity(SettingsRepository.KEY_VISION2_MODEL, "vision2-old"))
        dao.upsert(SettingEntity(SettingsRepository.KEY_VISION2_KEY_ENC, "k1:V2KEYCIPHER"))
    }

    @Test
    fun migration_fullLegacy_createsAllProfiles() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            // FakeSecretStore 对任意串都可逆（reversed）——迁移只平移密文不解密，
            // keyIds 只看「是否有密文」，断言不涉及 Key 内容
            seedLegacy(database)
            val s = repo.loadLlm()
            assertEquals("主力+讲解+视觉转写+备用视觉", 4, s.profiles.size)
            val byName = s.profiles.associateBy { it.name }
            assertEquals("https://old.example.com/v1", byName["主力"]!!.baseUrl)
            assertEquals("old-main", byName["主力"]!!.model)
            assertEquals(0.4, byName["主力"]!!.temperature, 1e-9)
            assertEquals("old-main 的讲解另有模型 → 独立档案", "cheap-model", byName["讲解"]!!.model)
            assertEquals("讲解地址沿用旧主力地址", "https://old.example.com/v1", byName["讲解"]!!.baseUrl)
            assertEquals("vision-old", byName["视觉转写"]!!.model)
            assertEquals("https://vision.example.com/v1", byName["视觉转写"]!!.baseUrl)
            assertEquals("vision2-old", byName["备用视觉"]!!.model)

            assertEquals("讲解指派到讲解档案", byName["讲解"]!!.id, s.assign.note)
            assertEquals(byName["视觉转写"]!!.id, s.assign.vision)
            assertEquals(byName["备用视觉"]!!.id, s.assign.vision2)
            assertEquals("主力指派留空（回退第一档）", "", s.assign.main)

            // Key 密文平移：每个档案都有密文（明文永不出库）
            assertEquals(4, s.keyIds.size)
            // 迁移幂等：再读数量不变、旧键没被动
            assertEquals(4, repo.loadLlm().profiles.size)
            assertNotNull(database.settingDao().get(SettingsRepository.KEY_ENC))
        } finally {
            database.close()
        }
    }

    @Test
    fun migration_noteModelSameAsMain_noExtraNoteProfile() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            seedLegacy(database, noteModel = "old-main") // 与主力模型相同 → 不单独立档
            // 本用例专测「讲解不立档」：清掉备用视觉三键（seedLegacy 默认带备用）
            val dao = database.settingDao()
            dao.delete(SettingsRepository.KEY_VISION2_BASE)
            dao.delete(SettingsRepository.KEY_VISION2_MODEL)
            dao.delete(SettingsRepository.KEY_VISION2_KEY_ENC)
            val s = repo.loadLlm()
            assertEquals("主力+视觉转写（备用没配）", 2, s.profiles.size)
            assertEquals("讲解跟主力", "", s.assign.note)
            assertEquals("", s.assign.vision2)
        } finally {
            database.close()
        }
    }

    @Test
    fun migration_freshLegacyOnlyMain_keyResolvesAfterMigrate() = runBlocking {
        val database = db()
        val repo = SettingsRepository(database, FakeSecretStore())
        try {
            val dao = database.settingDao()
            dao.upsert(SettingEntity("api_base", "https://only.example.com/v1"))
            dao.upsert(SettingEntity("api_model", "only-main"))
            dao.upsert(SettingEntity(SettingsRepository.KEY_ENC, "k1:CIPHER"))
            val s = repo.loadLlm()
            assertEquals("没配讲解/备用，也有默认视觉档案（沿用旧默认 LongCat 行为）", 2, s.profiles.size)
            assertEquals("", s.assign.note)
            assertNotNull(s.assign.vision)
            assertEquals(DEFAULT_VISION_NAME, s.profiles.first { it.id == s.assign.vision }.model)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DEFAULT_VISION_NAME = "LongCat-2.5-Preview"
    }
}
