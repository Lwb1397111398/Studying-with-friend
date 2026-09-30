# OPT-A 子计划案：API Key 保存链加固 + LongCat 预设 + 推理模型预算（R2）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans 逐任务执行。步骤用 `- [ ]` 勾选跟踪。

**Goal:** 让"保存 API Key"在任何设备上都不再单点失败（AndroidKeyStore 异常自动降级软件密钥文件，新旧密文共存可判）；内置美团 LongCat 预设；把推理模型会吃满的 max_tokens 预算提到安全水位。

**Architecture:** 新增 `ResilientSecretStore`（KeyStore 优先、失败回退 `noBackupFilesDir` 软件密钥文件）+ **密文来源前缀**（`k1:`/`s1:`，无前缀=遗留 k1，解密按标记选实现，失败不静默删除）；`StudyApp` 暴露单例 `secretStore`；backup rules 排除密钥文件；预算数值提升 + `chatJson` 空正文重试预算翻倍。

**Tech Stack:** 纯 Kotlin + Robolectric（Robolectric 无 AndroidKeyStore provider，正好真跑兜底路径）。

---

### Task A1: 密钥来源弹性链 ResilientSecretStore（含密文来源标记）

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/data/ai/SecretStore.kt`
- Test: `android/app/src/test/java/com/studyfriend/app/data/ai/ResilientSecretStoreTest.kt`（新建）

**密文格式**：`k1:<base64(iv||ct)>` = AndroidKeyStore；`s1:<base64(iv||ct)>` = 软件密钥文件；无前缀 = 遗留密文，按 k1 处理（历史上只有 KeyStore 写入方，SettingsRepository.kt:49 存储格式为纯 base64）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.studyfriend.app.data.ai

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric 无 AndroidKeyStore provider：ResilientSecretStore 必须自动走软件密钥兜底 */
@RunWith(RobolectricTestRunner::class)
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
```

- [ ] **Step 2: 跑测试确认失败** — `./gradlew.bat testDebugUnitTest --tests "com.studyfriend.app.data.ai.ResilientSecretStoreTest" --no-daemon`，预期编译错误（类不存在）。

- [ ] **Step 3: 实现（追加到 SecretStore.kt）**

```kotlin
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

    private fun splitMarker(payload: String): Pair<String, String> = when {
        payload.startsWith(MARK_KEYSTORE) -> MARK_KEYSTORE to payload.removePrefix(MARK_KEYSTORE)
        payload.startsWith(MARK_SOFTWARE) -> MARK_SOFTWARE to payload.removePrefix(MARK_SOFTWARE)
        else -> MARK_KEYSTORE to payload // 遗留密文无前缀
    }

    private companion object {
        const val MARK_KEYSTORE = "k1:"
        const val MARK_SOFTWARE = "s1:"
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
```

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令）。

### Task A2: decryptKeyOrNull 不再静默删除 + 单例化 secretStore

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/data/SettingsRepository.kt:69-77`（同步更新 :22/:68 的 KDoc"失效即删旧密文"表述）
- Modify: `android/app/src/main/java/com/studyfriend/app/StudyApp.kt`
- Modify: `android/app/src/main/java/com/studyfriend/app/ui/screens/SettingsViewModel.kt:23`
- Test: `android/app/src/test/java/com/studyfriend/app/data/SettingsRepoTest.kt`（**改写既有用例** + 追加 2 例）

**删除语义（R2 钉死，机制 = repo 侧纯格式检查，零 SecretStore 接口变更）：**
- `payload` 带前缀（`k1:`/`s1:`）但前缀后的 body **不是合法 Base64** → 结构性损坏，删除密文（这类密文任何实现都永远解不开）；
- 其余一切解密失败（含无前缀遗留密文解密失败）→ **保留密文**，仅返回 null（keystore 可能暂时故障，重启可愈）。

- [ ] **Step 1a: 改写既有用例**（SettingsRepoTest.kt:84-98 `decryptKey_fails_deletesCipherAndReturnsNull` 断言的是旧行为——改名+翻转断言；顺带把类 KDoc"失效删键"表述改为"失效不删密文"。既有文件模式是 `val database = db()` + try/finally close，片段照此写）：

```kotlin
@Test
fun decryptFailure_keepsCipherAndReturnsNull() = runBlocking {
    val database = db()
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
```

- [ ] **Step 1b: 追加 2 例**（需补 import：`org.junit.Assert.assertNotNull`、`com.studyfriend.app.data.db.SettingEntity`）：

```kotlin
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
```

- [ ] **Step 2:** `decryptKeyOrNull` 实现：先调用 SecretStore.kt 新增的 internal 纯函数 `isStructurallyCorrupt(payload)`（**提取共享，勿在 repo 复制**——k1:/s1: 前缀探测 + `Base64.getDecoder().decode(body)` 试解；ResilientSecretStore.splitMarker 复用同一探测），为真 → 删除密文返回 null；否则 `store.decrypt(payload)`，`SecretCryptoException` 捕获后返回 null（不删）。
- [ ] **Step 3:** StudyApp 增加 `val secretStore: SecretStore by lazy { ResilientSecretStore(applicationContext) }`，`settingsRepo` 改用；SettingsViewModel 第 23 行改用 `(app as StudyApp).secretStore`；**顺手删除两处随之失效的 `KeyStoreSecretStore` import**（StudyApp.kt:6、SettingsViewModel.kt:15）。grep 确认 `KeyStoreSecretStore()` 仅剩 ResilientSecretStore 内一处构造。

### Task A3: LongCat 预设 + 保存失败报错带原因

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/data/SettingsRepository.kt:87-91`
- Test: `android/app/src/test/java/com/studyfriend/app/data/SettingsRepoTest.kt`（追加 1 例）

- [ ] **Step 1: 失败测试**（SettingsRepoTest 追加）：

```kotlin
@Test
fun presets_containLongCat_withCorrectUrlAndModel() {
    val lc = SettingsRepository.PRESETS.firstOrNull { it.first.contains("LongCat") }
    assertNotNull(lc)
    assertEquals("https://api.longcat.chat/openai/v1", lc!!.second)
    assertEquals("LongCat-2.5-Preview", lc.third)
}
```

- [ ] **Step 2:** PRESETS 首位插入 `Triple("美团 · LongCat（推荐）", "https://api.longcat.chat/openai/v1", "LongCat-2.5-Preview")`；既有商汤预设的"（推荐）"后缀去掉（全列表只保留一个推荐位）。
- [ ] **Step 3:** 保存失败报错文案不在 KeyStoreSecretStore 内改——ResilientSecretStore.encrypt 已统一包装两条路径全败的最终失败（A1 Step 3），文案"无法保存 API Key（密钥不可用：…）"含底层原因；本任务无需再动 SecretStore.kt。

### Task A4: 推理模型 max_tokens 预算提升 + 空正文自动翻倍重试

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/data/ai/AiClient.kt:146-152`（chatJson 重试）
- Modify: `android/app/src/main/java/com/studyfriend/app/data/study/RoughReadPlanner.kt:252,319`
- Modify: `android/app/src/main/java/com/studyfriend/app/data/study/NotePlanner.kt:184`
- Modify: `android/app/src/main/java/com/studyfriend/app/ui/screens/SettingsViewModel.kt:106`
- Test: `android/app/src/test/java/com/studyfriend/app/data/ai/AiClientTest.kt`（追加 1 例）

- [ ] **Step 1: 失败测试**（AiClientTest，复用既有 MiniHttpServer 假服务端；断言：第一次响应流正文为空、第二次为合法 JSON 时，第二次请求体里的 `max_tokens` 等于第一次的 2 倍；先读该测试文件确认现有助手函数形态再落笔）。
- [ ] **Step 2:** chatJson 中 retryReq 追加预算翻倍：仅当 `first.isBlank()`（正文为空=思考吃满预算）时 `maxTokens = req.maxTokens?.let { it * 2 }`，且此分支的重试提示文案换成"上一次回复没有任何正文内容（可能是思考耗尽了输出预算），请直接输出最终结果本身"（原 RETRY_HINT"不是合法 JSON"对空正文场景语义不准）；非空仅解析失败时维持原预算与原提示重试。
- [ ] **Step 3:** 数值提升（总计划 §3 钉死值）：RoughReadPlanner 块预算下限 `8192`→`16384`；归并 `8192`→`16384`；NotePlanner `10_000`→`16_000`；SettingsViewModel 测试连接 `512`→`2048`。SummaryPlanner/OverviewPlanner 已是 16_000 不动。
- [ ] **Step 4:** 全量 `testDebugUnitTest` 0 失败。

### Task A5: backup rules 排除密钥文件

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`（application 节点）
- Create: `android/app/src/main/res/xml/data_extraction_rules.xml`
- Create: `android/app/src/main/res/xml/backup_rules.xml`

- [ ] **Step 1:** Manifest application 加 `android:dataExtractionRules="@xml/data_extraction_rules"` 与 `android:fullBackupContent="@xml/backup_rules"`（`allowBackup` 保持 true，书库可备份）。
- [ ] **Step 2:** 两规则文件排除 `secret_key.bin`。**domain 只允许框架合法值**（`root/file/database/sharedpref/external` 与 `device_*` 系列；**不存在 `no_backup` domain**——写了会触发 lint Error 且运行时规则解析失败，AGP lint 的 FullBackupContent 检查会校验）。`noBackupFilesDir` 本身从不参与云备份/设备迁移（系统级天然排除），规则仅作显式声明，用 `root` 域精确兜底：

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="root" path="no_backup/secret_key.bin" />
</full-backup-content>
```

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="no_backup/secret_key.bin" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="no_backup/secret_key.bin" />
    </device-transfer>
</data-extraction-rules>
```

（`domain="file"` 只覆盖 `files/`，罩不住其兄弟目录 `no_backup/`，故必须用 `root` 域。**联动约束**：若执行中实测 Robolectric/真机对 `noBackupFilesDir` 异常而把存储位置改到 `filesDir`，必须同步把排除路径改为 `<exclude domain="file" path="secret_key.bin" />`，不得只改其一。）
- [ ] **Step 3:** `assembleDebug` + `lintDebug` 通过（domain 合法性校验是本任务的主要验证点）。

### Task A6: 设置页预设 chips 换 FlowRow（4 个不溢出）

**Files:**
- Modify: `android/app/src/main/java/com/studyfriend/app/ui/screens/SettingsScreen.kt:62-70`
- [ ] **Step 1:** Row → `FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp))`，加 `@OptIn(ExperimentalLayoutApi::class)`（ReadScreen 已有同用法先例）。
- [ ] **Step 2:** `compileDebugKotlin` 通过 + 全量 `testDebugUnitTest` 0 失败。（设置页无既有 Compose 自动化覆盖——M5UiSmokeTest 只断言一句报错文案、并不渲染设置页，勿声称其覆盖本项；FlowRow 用法对照 ReadScreen.kt:189 先例。）

### Task A7: 包验证与提交

- [ ] `./gradlew.bat testDebugUnitTest lintDebug --no-daemon` 全绿。
- [ ] `git add` 相关文件，`git commit -m "fix(settings): key 保存链加固（keystore→软件密钥兜底+来源标记）+ LongCat 预设 + 推理模型预算"`。

## 验收标准（包级）

1. ResilientSecretStoreTest 5 例全绿（Robolectric 下真跑兜底路径，断言 s1 前缀/跨实例/遗留密文兼容/垃圾密文报错/密钥文件损坏自愈）。
2. `decryptKeyOrNull`：解密失败保留密文（改写后的既有用例 + 新增遗留密文例），结构性损坏（带标记+非法 Base64）删除密文（新增例）。
3. 预设含 LongCat（首位）；设置页 4 chips FlowRow 不溢出。
4. 预算：测试连接 2048；粗读块下限 16384；归并 16384；讲解 16000；空正文重试翻倍（带测试）。
5. backup rules 两文件落盘且 assembleDebug 通过。
6. 既有 213 测试全数保持绿（含按新语义改名翻转的原 `decryptKey_fails_deletesCipherAndReturnsNull` 用例）。
