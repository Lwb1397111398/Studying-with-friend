package com.studyfriend.app.data

import androidx.room.withTransaction
import com.studyfriend.app.data.ai.SecretCrypto
import com.studyfriend.app.data.ai.SecretCryptoException
import com.studyfriend.app.data.ai.SecretStore
import com.studyfriend.app.data.ai.isStructurallyCorrupt
import com.studyfriend.app.data.db.SettingEntity
import com.studyfriend.app.data.db.StudyDatabase
import com.studyfriend.app.data.importer.pdfpipeline.FrontMatterVisionParser
import com.studyfriend.app.data.importer.pdfpipeline.TextSourceRow
import com.studyfriend.app.data.importer.pdfpipeline.TocVisionParser
import com.studyfriend.app.data.importer.pdfpipeline.VisionTranscriber
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 设置页配置快照（Key 永不明文出库，只给"是否已存"）。
 * AI 端点配置已升级为「档案」体系（见 [loadLlm] / [resolveLlm]），本快照只剩非端点类设置。
 */
data class SettingsSnapshot(
    /** 视觉兜底开关（OPT-E）：默认开；无可用档案时导入流程自动跳过视觉 */
    val visionEnabled: Boolean,
    /** 应用自更新（M7）：是否已存 GitHub 只读令牌（明文不出库） */
    val hasGithubToken: Boolean,
    /** 发现新版本自动弹窗检查；默认开 */
    val updateAutoCheck: Boolean,
    /** 更新源 API 地址覆盖：仅测试/私有部署用，空 = 官方 GitHub API */
    val updateApiBase: String,
    /** 扫描书本地识别（OCR）开关（P6b S4）：默认开；off = 扫描书走现状路径（回滚方案载体） */
    val ocrEnabled: Boolean,
)

/**
 * 设置存取（计划 M3 §1/§2）：普通配置直存 settings 表；
 * API Key 经 SecretStore 加密为 Base64(iv||ct) 存 `api_key_enc`，明文不落库。
 * 解密失败不删密文（keystore 可能暂时故障）；仅"结构性损坏"（带来源标记但非合法
 * Base64，永远解不开）删除密文让用户重填（OPT-A Task A2）。
 */
class SettingsRepository(
    private val db: StudyDatabase,
    private val store: SecretStore,
) {

    suspend fun load(): SettingsSnapshot = SettingsSnapshot(
        visionEnabled = db.settingDao().get(KEY_VISION_ENABLED)?.value != "false",
        hasGithubToken = db.settingDao().get(KEY_GITHUB_TOKEN_ENC)?.value != null,
        updateAutoCheck = db.settingDao().get(KEY_UPDATE_AUTO)?.value != "false",
        updateApiBase = db.settingDao().get(KEY_UPDATE_API_BASE)?.value.orEmpty(),
        ocrEnabled = db.settingDao().get(KEY_OCR_ENABLED)?.value != "false",
    )

    // ============ LLM 档案体系（2026-11-15 多模型轮）============
    // 档案列表与功能分配存 settings KV（JSON）；每个档案的 Key 单独加密存
    // `llm_key_<id>_enc`（_enc 后缀 = 备份导出自动排除、恢复不碰）。
    // 旧版「主力 + 讲解模型名 + 视觉两组」配置首次读取时自动迁移成档案，旧键不删（降级安全）。

    private val llmJson = Json { ignoreUnknownKeys = true }

    /** 档案快照（首次调用自动迁移旧配置）；keyIds = 已存 Key 的档案 id 集合（明文不出库） */
    suspend fun loadLlm(): LlmSettings {
        migrateLegacyLlmIfNeeded()
        return LlmSettings(
            profiles = readProfiles(),
            assign = readAssign(),
            keyIds = readProfiles().mapNotNullTo(HashSet()) {
                if (hasProfileKey(it.id)) it.id else null
            },
        )
    }

    /** 保存档案（新建或整行覆盖；keyPlain 非空 = 加密写入，null/空白 = 保持已有 Key 不动） */
    suspend fun saveProfile(profile: LlmProfile, keyPlain: String?) {
        val clean = profile.copy(
            name = profile.name.trim().ifBlank { "档案" },
            baseUrl = profile.baseUrl.trim(),
            model = profile.model.trim(),
        )
        db.withTransaction {
            val cur = readProfiles().toMutableList()
            val idx = cur.indexOfFirst { it.id == clean.id }
            if (idx >= 0) cur[idx] = clean else cur += clean
            db.settingDao().upsert(SettingEntity(KEY_LLM_PROFILES, llmJson.encodeToString(cur)))
            if (!keyPlain.isNullOrBlank()) {
                db.settingDao().upsert(
                    SettingEntity(profileKeyName(clean.id), store.encrypt(keyPlain.trim())),
                )
            }
        }
    }

    /** 删除档案并清掉指向它的分配（main 回退第一档、note/vision 回跟主力、vision2 回不启用） */
    suspend fun deleteProfile(id: String) {
        db.withTransaction {
            db.settingDao().upsert(
                SettingEntity(KEY_LLM_PROFILES, llmJson.encodeToString(readProfiles().filterNot { it.id == id })),
            )
            db.settingDao().delete(profileKeyName(id))
            val a = readAssign()
            if (a.main == id || a.note == id || a.vision == id || a.vision2 == id) {
                db.settingDao().upsert(
                    SettingEntity(
                        KEY_LLM_ASSIGN,
                        llmJson.encodeToString(
                            a.copy(
                                main = a.main.takeUnless { it == id }.orEmpty(),
                                note = a.note.takeUnless { it == id }.orEmpty(),
                                vision = a.vision.takeUnless { it == id }.orEmpty(),
                                vision2 = a.vision2.takeUnless { it == id }.orEmpty(),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    /** 保存功能分配（即点即存） */
    suspend fun saveAssign(assign: LlmAssign) {
        db.settingDao().upsert(SettingEntity(KEY_LLM_ASSIGN, llmJson.encodeToString(assign)))
    }

    /**
     * 解析某角色的实际端点（Key 已解密）。返回 null = 该角色当前不可用（没档案/没 Key）：
     * - MAIN：分配失效时回退第一个档案
     * - NOTE / VISION：未分配 = 跟随主力；分配失效（档案被删）也回退主力
     * - VISION2：未分配或失效 = null（备用视觉不启用，不回退——异家 Key 打异家 URL 无意义）
     */
    suspend fun resolveLlm(role: LlmRole): ResolvedLlm? {
        val s = loadLlm()
        if (s.profiles.isEmpty()) return null
        val main = s.profiles.firstOrNull { it.id == s.assign.main } ?: s.profiles.first()
        val target = when (role) {
            LlmRole.MAIN -> main
            LlmRole.NOTE -> s.profiles.firstOrNull { it.id == s.assign.note } ?: main
            LlmRole.VISION -> s.profiles.firstOrNull { it.id == s.assign.vision } ?: main
            LlmRole.VISION2 -> s.profiles.firstOrNull { it.id == s.assign.vision2 } ?: return null
        }
        val key = decryptProfileKeyOrNull(target.id) ?: return null
        return ResolvedLlm(
            profileId = target.id,
            profileName = target.name,
            baseUrl = target.baseUrl.ifBlank { DEFAULT_BASE },
            apiKey = key,
            model = target.model.ifBlank { DEFAULT_MODEL },
            temperature = target.temperature.coerceIn(0.0, 1.0),
            reasoningEffort = target.reasoningEffort,
        )
    }

    /** 解档案 Key 明文：解密失败返回 null 保留密文；仅结构性损坏删除（与旧 Key 同策略，OPT-A） */
    suspend fun decryptProfileKeyOrNull(id: String): String? {
        val name = profileKeyName(id)
        val payload = db.settingDao().get(name)?.value ?: return null
        if (isStructurallyCorrupt(payload)) {
            db.settingDao().delete(name)
            return null
        }
        return try {
            store.decrypt(payload)
        } catch (e: SecretCryptoException) {
            null
        }
    }

    suspend fun hasProfileKey(id: String): Boolean =
        db.settingDao().get(profileKeyName(id))?.value != null

    /** 清除档案 Key（之后 resolveLlm 对该档案返回 null，UI 提示重填） */
    suspend fun clearProfileKey(id: String) {
        db.settingDao().delete(profileKeyName(id))
    }

    /** 播种主力档案（仅测试用）：更新「主力指向的档案」，没有档案时新建一个 */
    suspend fun seedMainProfile(baseUrl: String, model: String, temperature: Double, keyPlain: String?) {
        val s = loadLlm()
        val existing = s.profiles.firstOrNull { it.id == s.assign.main } ?: s.profiles.firstOrNull()
        val p = existing?.copy(baseUrl = baseUrl, model = model, temperature = temperature)
            ?: LlmProfile(newProfileId(), "主力", baseUrl, model, temperature)
        saveProfile(p, keyPlain)
        if (existing == null) saveAssign(readAssign().copy(main = p.id))
    }

    // ---- 档案读写内部件 ----

    private suspend fun readProfiles(): List<LlmProfile> =
        db.settingDao().get(KEY_LLM_PROFILES)?.value
            ?.let { raw -> runCatching { llmJson.decodeFromString<List<LlmProfile>>(raw) }.getOrDefault(emptyList()) }
            ?.filter { it.id.isNotBlank() }
            .orEmpty()

    private suspend fun readAssign(): LlmAssign =
        db.settingDao().get(KEY_LLM_ASSIGN)?.value
            ?.let { raw -> runCatching { llmJson.decodeFromString<LlmAssign>(raw) }.getOrDefault(LlmAssign()) }
            ?: LlmAssign()

    private fun profileKeyName(id: String) = "llm_key_${id}_enc"

    private fun newProfileId(): String = UUID.randomUUID().toString().substring(0, 8)

    /** 旧 Key 密文原值平移（同一 SecretStore 加解密格式，无需重加密） */
    private suspend fun copyKeyPayload(fromKeyName: String, toProfileId: String) {
        db.settingDao().get(fromKeyName)?.value?.let {
            db.settingDao().upsert(SettingEntity(profileKeyName(toProfileId), it))
        }
    }

    /**
     * 旧配置一次性迁移（幂等：llm_profiles 键存在即跳过；事务内防并发双跑）。旧键不删（降级安全）：
     * - 主力（api_base/api_model/api_temp/api_key_enc）→ 档案「主力」
     * - 讲解模型名非空且 ≠ 主力模型 → 档案「讲解」（地址/Key 沿用主力，密文原值平移）
     * - 视觉组 → 档案「视觉转写」（专属地址/Key 优先回退主力；模型缺省 LongCat，保持旧默认行为）
     * - 备用视觉三键齐 → 档案「备用视觉」
     */
    private suspend fun migrateLegacyLlmIfNeeded() {
        if (db.settingDao().get(KEY_LLM_PROFILES)?.value != null) return
        db.withTransaction {
            if (db.settingDao().get(KEY_LLM_PROFILES)?.value != null) return@withTransaction
            val dao = db.settingDao()
            val legacyBase = dao.get("api_base")?.value.orEmpty().ifBlank { DEFAULT_BASE }
            val legacyModel = dao.get("api_model")?.value.orEmpty().ifBlank { DEFAULT_MODEL }
            val legacyTemp = dao.get("api_temp")?.value?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.3

            val mainId = newProfileId()
            val profiles = mutableListOf(LlmProfile(mainId, "主力", legacyBase, legacyModel, legacyTemp))
            var assign = LlmAssign()
            copyKeyPayload(KEY_ENC, mainId) // 主力 Key 密文原值平移（讲解/视觉的平移同样以它为兜底源）

            // 讲解：旧版只有模型名（地址/Key 永远跟主力）→ 有且不同于主力模型才单独立档
            val noteModel = dao.get(KEY_NOTE_MODEL)?.value.orEmpty().trim()
            if (noteModel.isNotEmpty() && noteModel != legacyModel) {
                val id = newProfileId()
                profiles += LlmProfile(id, "讲解", legacyBase, noteModel)
                copyKeyPayload(KEY_ENC, id)
                assign = assign.copy(note = id)
            }

            // 视觉转写：仅当旧配置真实存在视觉痕迹时才立档——有主力 Key = 用过 App 的老用户
            // （旧版模型缺省 LongCat，立档保持行为不变）；全新安装（什么都没配）不立，
            // 视觉跟随主力档案（UI 指派弹层副标题提示主力模型需带视觉）
            val legacyHasVision = dao.get(KEY_VISION_MODEL)?.value?.takeIf { it.isNotBlank() } != null ||
                dao.get(KEY_VISION_BASE)?.value?.isNotBlank() == true ||
                dao.get(KEY_VISION_KEY_ENC)?.value != null ||
                dao.get(KEY_ENC)?.value != null
            if (legacyHasVision) {
                val visionModel = dao.get(KEY_VISION_MODEL)?.value?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_VISION_MODEL
                val visionBase = dao.get(KEY_VISION_BASE)?.value.orEmpty().ifBlank { legacyBase }
                val visionId = newProfileId()
                profiles += LlmProfile(visionId, "视觉转写", visionBase, visionModel)
                // 专属 Key 优先平移；无专属时平移主力 Key（与旧版 resolveVisionKeyOrNull 回退语义一致）
                if (dao.get(KEY_VISION_KEY_ENC)?.value != null) {
                    copyKeyPayload(KEY_VISION_KEY_ENC, visionId)
                } else {
                    copyKeyPayload(KEY_ENC, visionId)
                }
                assign = assign.copy(vision = visionId)
            }

            // 备用视觉：旧版三键齐才参与轮转 → 三键齐才立档
            val v2Base = dao.get(KEY_VISION2_BASE)?.value.orEmpty().trim()
            val v2Model = dao.get(KEY_VISION2_MODEL)?.value.orEmpty().trim()
            val v2Key = dao.get(KEY_VISION2_KEY_ENC)?.value
            if (v2Base.isNotEmpty() && v2Model.isNotEmpty() && v2Key != null) {
                val id = newProfileId()
                profiles += LlmProfile(id, "备用视觉", v2Base, v2Model)
                db.settingDao().upsert(SettingEntity(profileKeyName(id), v2Key))
                assign = assign.copy(vision2 = id)
            }

            db.settingDao().upsert(SettingEntity(KEY_LLM_PROFILES, llmJson.encodeToString(profiles)))
            db.settingDao().upsert(SettingEntity(KEY_LLM_ASSIGN, llmJson.encodeToString(assign)))
        }
    }

    /** 讲解详略档位（总计划 §1 目标 7）：简略/标准/深入，默认标准；坏值回退标准 */
    suspend fun detailLevel(): String =
        db.settingDao().get(KEY_DETAIL)?.value?.takeIf { it in DETAIL_LEVELS } ?: "标准"

    suspend fun saveDetailLevel(level: String) {
        if (level !in DETAIL_LEVELS) return
        db.settingDao().upsert(SettingEntity(KEY_DETAIL, level))
    }

    /** 视觉兜底开关（OPT-E）：即点即存，与档案体系解耦（开=尝试组装，无可用档案自动跳过） */
    suspend fun saveVisionEnabled(enabled: Boolean) {
        db.settingDao().upsert(SettingEntity(KEY_VISION_ENABLED, if (enabled) "true" else "false"))
    }

    // ---- 阅读字号/行距（功能补齐轮）：无级字号 14..30sp（旧四档 16/18/20/22 天然兼容），
    // ---- 行距 1.4..2.2 倍；坏值回退默认。即调即预览、关弹层存库 ----

    suspend fun readFontSize(): Int =
        db.settingDao().get(KEY_READ_FONT_SIZE)?.value?.toIntOrNull()
            ?.takeIf { it in READ_FONT_MIN..READ_FONT_MAX } ?: DEFAULT_READ_FONT_SIZE

    suspend fun saveReadFontSize(size: Int) {
        if (size !in READ_FONT_MIN..READ_FONT_MAX) return
        db.settingDao().upsert(SettingEntity(KEY_READ_FONT_SIZE, size.toString()))
    }

    suspend fun readLineHeight(): Float =
        db.settingDao().get(KEY_READ_LINE_HEIGHT)?.value?.toFloatOrNull()
            ?.takeIf { it in READ_LINE_MIN..READ_LINE_MAX } ?: DEFAULT_READ_LINE_HEIGHT

    suspend fun saveReadLineHeight(mult: Float) {
        if (mult !in READ_LINE_MIN..READ_LINE_MAX) return
        db.settingDao().upsert(SettingEntity(KEY_READ_LINE_HEIGHT, mult.toString()))
    }

    // ---- 翻页模式（体验优化轮）：true=点屏幕两侧/左右滑翻页，false=上下滚动；watch 供阅读页即时生效。
    // ---- 实景体验轮（2026-11-21）：默认改开——新装机点两侧全是切沉浸，老板 11-15 点名的翻页
    // ---- 要去 Aa 弹层手动开才生效（装机实录）。无键=开；只有显式存过 "false"（用户主动关过）才关 ----

    fun readPageModeFlow(): Flow<Boolean> =
        db.settingDao().watch(KEY_READ_PAGE_MODE).map { it != "false" }

    suspend fun readPageMode(): Boolean =
        db.settingDao().get(KEY_READ_PAGE_MODE)?.value != "false"

    suspend fun saveReadPageMode(on: Boolean) {
        db.settingDao().upsert(SettingEntity(KEY_READ_PAGE_MODE, if (on) "true" else "false"))
    }

    // ---- 阅读背景主题包（实景体验轮第六波）："follow"=跟随书房主题（默认），
    // ---- 其余值 = ReadingTheme.ALL 里的固定纸色；watch 供阅读页即时切换 ----

    fun readThemeFlow(): Flow<String> =
        db.settingDao().watch(KEY_READ_THEME).map { it?.takeIf { v -> v.isNotBlank() } ?: KEY_READ_THEME_DEFAULT }

    suspend fun readTheme(): String =
        db.settingDao().get(KEY_READ_THEME)?.value?.takeIf { it.isNotBlank() } ?: KEY_READ_THEME_DEFAULT

    suspend fun saveReadTheme(id: String) {
        db.settingDao().upsert(SettingEntity(KEY_READ_THEME, id))
    }

    // ---- 音量键翻页（实景体验轮第六波，借鉴 legado）：开=阅读页音量加/减翻上/下一页 ----

    fun readVolumeKeyFlow(): Flow<Boolean> =
        db.settingDao().watch(KEY_READ_VOLUME_KEY).map { it == "true" }

    suspend fun saveReadVolumeKey(on: Boolean) {
        db.settingDao().upsert(SettingEntity(KEY_READ_VOLUME_KEY, if (on) "true" else "false"))
    }

    // ---- 阅读字体（实景体验轮第七波）："default"=系统字体；"lxgw"=霞鹜文楷 Lite
    // ---- （OFL-1.1 随包再分发，assets/OFL-LXGWWenKai-Lite.txt）。watch 供阅读页即时切换 ----

    fun readFontFamilyFlow(): Flow<String> =
        db.settingDao().watch(KEY_READ_FONT_FAMILY).map { it?.takeIf { v -> v.isNotBlank() } ?: READ_FONT_DEFAULT }

    suspend fun saveReadFontFamily(id: String) {
        db.settingDao().upsert(SettingEntity(KEY_READ_FONT_FAMILY, id))
    }

    // ---- 阅读页亮度（实景体验轮第七波，借鉴 legado）：0.15~1.0 写 window.screenBrightness
    // ---- 只覆盖阅读页窗口、离页恢复系统亮度；-1=跟随系统（默认）。滑条拖动即生效、关弹层存库 ----

    fun readBrightnessFlow(): Flow<Float> =
        db.settingDao().watch(KEY_READ_BRIGHTNESS).map {
            it?.toFloatOrNull()?.takeIf { v -> v in READ_BRIGHTNESS_MIN..READ_BRIGHTNESS_MAX } ?: -1f
        }

    suspend fun readBrightness(): Float =
        db.settingDao().get(KEY_READ_BRIGHTNESS)?.value?.toFloatOrNull()
            ?.takeIf { it in READ_BRIGHTNESS_MIN..READ_BRIGHTNESS_MAX } ?: -1f

    suspend fun saveReadBrightness(v: Float) {
        if (v != -1f && v !in READ_BRIGHTNESS_MIN..READ_BRIGHTNESS_MAX) return
        db.settingDao().upsert(SettingEntity(KEY_READ_BRIGHTNESS, v.toString()))
    }

    // ---- 每日阅读目标分钟（P1-⑤）：进度环/热力图色阶基准；坏值回退默认 ----

    suspend fun readDailyGoalMinutes(): Int =
        db.settingDao().get(KEY_READ_DAILY_GOAL)?.value?.toIntOrNull()
            ?.takeIf { it in READ_GOAL_MIN..READ_GOAL_MAX } ?: DEFAULT_READ_GOAL

    suspend fun saveDailyGoalMinutes(min: Int) {
        if (min !in READ_GOAL_MIN..READ_GOAL_MAX) return
        db.settingDao().upsert(SettingEntity(KEY_READ_DAILY_GOAL, min.toString()))
    }

    // ---- 复习到期排序（P1 复习两小招）：坏值回退 risk ----

    suspend fun readReviewSortMode(): String =
        db.settingDao().get(KEY_REVIEW_SORT)?.value?.takeIf { it in REVIEW_SORT_MODES } ?: "risk"

    suspend fun saveReviewSortMode(mode: String) {
        if (mode !in REVIEW_SORT_MODES) return
        db.settingDao().upsert(SettingEntity(KEY_REVIEW_SORT, mode))
    }

    // ---- 外观主题（功能补齐轮）：三态，默认跟随系统；MainActivity watch 本键即时生效 ----

    suspend fun themeMode(): String {
        val v = db.settingDao().get(KEY_THEME)?.value
        return if (v in THEME_MODES) v!! else THEME_SYSTEM
    }

    suspend fun saveThemeMode(mode: String) {
        if (mode !in THEME_MODES) return
        db.settingDao().upsert(SettingEntity(KEY_THEME, mode))
    }

    // ---- 朗读语速（功能补齐轮正文听书）：0.5~2.0 倍，默认 1.0，坏值回退 ----

    suspend fun ttsRate(): Float =
        db.settingDao().get(KEY_TTS_RATE)?.value?.toFloatOrNull()
            ?.takeIf { it in TTS_RATE_MIN..TTS_RATE_MAX } ?: DEFAULT_TTS_RATE

    suspend fun saveTtsRate(rate: Float) {
        if (rate !in TTS_RATE_MIN..TTS_RATE_MAX) return
        db.settingDao().upsert(SettingEntity(KEY_TTS_RATE, rate.toString()))
    }

    // ---- 朗读引擎（TTS 三模式）：system=手机自带 / local=本地离线模型 / edge=Edge 在线朗读；
    //      坏值回退 edge（2026-11-22 修复「点朗读闪退」：真机两条旧引擎路径从未验证过，
    //      Edge 在线朗读零依赖（无需系统引擎/无需 205MB 模型），设为默认；显式选过的用户保持其选择 ----

    suspend fun ttsEngineMode(): String =
        db.settingDao().get(KEY_TTS_ENGINE)?.value
            ?.takeIf { it == TTS_ENGINE_SYSTEM || it == TTS_ENGINE_LOCAL || it == TTS_ENGINE_EDGE }
            ?: TTS_ENGINE_EDGE

    suspend fun saveTtsEngineMode(mode: String) {
        if (mode != TTS_ENGINE_SYSTEM && mode != TTS_ENGINE_LOCAL && mode != TTS_ENGINE_EDGE) return
        db.settingDao().upsert(SettingEntity(KEY_TTS_ENGINE, mode))
    }

    // ---- Edge 在线朗读音色：晓晓（默认）/ 云希 ----

    suspend fun ttsEdgeVoice(): String =
        db.settingDao().get(KEY_TTS_EDGE_VOICE)?.value
            ?.takeIf { it == EDGE_VOICE_XIAOXIAO || it == EDGE_VOICE_YUNXI } ?: EDGE_VOICE_XIAOXIAO

    suspend fun saveTtsEdgeVoice(voice: String) {
        if (voice != EDGE_VOICE_XIAOXIAO && voice != EDGE_VOICE_YUNXI) return
        db.settingDao().upsert(SettingEntity(KEY_TTS_EDGE_VOICE, voice))
    }

    // ---- 复习到期提醒（功能补齐轮）：默认开，"false" 显式关闭；StudyApp 按此注册每日查询 ----

    suspend fun reviewReminderEnabled(): Boolean =
        db.settingDao().get(KEY_REVIEW_REMINDER)?.value != "false"

    suspend fun saveReviewReminderEnabled(enabled: Boolean) {
        db.settingDao().upsert(SettingEntity(KEY_REVIEW_REMINDER, if (enabled) "true" else "false"))
    }

    // ---- 扫描书本地识别（P6b S4）：开关 / 用户反馈计数 / OCR 版本标记 ----

    /** OCR 开关即点即存（off = 扫描书走现状路径，OCR 产出缓存不删） */
    suspend fun saveOcrEnabled(enabled: Boolean) {
        db.settingDao().upsert(SettingEntity(KEY_OCR_ENABLED, if (enabled) "true" else "false"))
    }

    /**
     * 用户反馈「识别结果有误？」计数 +1（v1.3 P1-1 回滚评估入口）：
     * 全局累计 + 按书（书名 key）双记；返回全局累计数，≥3 时导入完成提示升级
     * 「建议在设置中关闭 OCR」。
     */
    suspend fun addOcrFeedback(bookKey: String): Int {
        val total = (db.settingDao().get(KEY_OCR_FEEDBACK_TOTAL)?.value?.toIntOrNull() ?: 0) + 1
        val perBookKey = "$KEY_OCR_FEEDBACK_BOOKPrefix$bookKey"
        val perBook = (db.settingDao().get(perBookKey)?.value?.toIntOrNull() ?: 0) + 1
        db.withTransaction {
            db.settingDao().upsert(SettingEntity(KEY_OCR_FEEDBACK_TOTAL, total.toString()))
            db.settingDao().upsert(SettingEntity(perBookKey, perBook.toString()))
        }
        return total
    }

    suspend fun ocrFeedbackTotal(): Int =
        db.settingDao().get(KEY_OCR_FEEDBACK_TOTAL)?.value?.toIntOrNull() ?: 0

    /** 记录本次导入使用的 OCR 产出版本（引擎版本升级时供重导提示比对，v1.5 S1 条款） */
    suspend fun saveLastOcrVersion(version: Int) {
        db.settingDao().upsert(SettingEntity(KEY_OCR_LAST_VERSION, version.toString()))
    }

    suspend fun lastOcrVersion(): Int =
        db.settingDao().get(KEY_OCR_LAST_VERSION)?.value?.toIntOrNull() ?: TextSourceRow.PROD_OCR_V1

    // ---- 应用自更新（M7）：GitHub 只读令牌 / 自动检查开关 / 上次检查时间 ----

    /** 保存 GitHub 只读令牌：与 AI Key 同一套加密落库，明文不落盘 */
    suspend fun saveGithubToken(plain: String) {
        val payload = store.encrypt(plain.trim())
        db.settingDao().upsert(SettingEntity(KEY_GITHUB_TOKEN_ENC, payload))
    }

    suspend fun clearGithubToken() {
        db.settingDao().delete(KEY_GITHUB_TOKEN_ENC)
    }

    /** 解出 GitHub 令牌：解密失败返回 null 且不删密文（逻辑同 AI Key，仅结构性损坏才删） */
    suspend fun decryptGithubTokenOrNull(): String? {
        val payload = db.settingDao().get(KEY_GITHUB_TOKEN_ENC)?.value ?: return null
        if (isStructurallyCorrupt(payload)) {
            db.settingDao().delete(KEY_GITHUB_TOKEN_ENC)
            return null
        }
        return try {
            store.decrypt(payload)
        } catch (e: SecretCryptoException) {
            null
        }
    }

    /** 自动检查开关（即点即存） */
    suspend fun setUpdateAutoCheck(enabled: Boolean) {
        db.settingDao().upsert(SettingEntity(KEY_UPDATE_AUTO, if (enabled) "true" else "false"))
    }

    /** 每次真正联网检查后记录时间，自动检查据此节流（24 小时一次） */
    suspend fun markUpdateChecked() {
        db.settingDao().upsert(SettingEntity(KEY_UPDATE_LAST, System.currentTimeMillis().toString()))
    }

    suspend fun updateLastCheckedMs(): Long =
        db.settingDao().get(KEY_UPDATE_LAST)?.value?.toLongOrNull() ?: 0L

    /**
     * 组装视觉转写器列表（R3 多模型轮转）：开关开且至少一个档案可用才非空。
     * 主视觉 = VISION 角色档案（未指派跟随主力档案）；备用 = VISION2 角色档案
     * （未指派不参与，不回退主力——异家 Key 打异家 URL 无意义）。
     * 导入同步路径与探针取首个；后台队列 Worker 用全列表做多模型并行轮转。
     */
    suspend fun buildVisionTranscribers(): List<VisionTranscriber> {
        if (!load().visionEnabled) return emptyList()
        val list = mutableListOf<VisionTranscriber>()
        resolveLlm(LlmRole.VISION)?.let { list += VisionTranscriber(it.baseUrl, it.apiKey, it.model) }
        resolveLlm(LlmRole.VISION2)?.let { list += VisionTranscriber(it.baseUrl, it.apiKey, it.model) }
        return list
    }

    /** 单转写器入口（导入同步路径/目录探针共用，取轮转列表首个） */
    suspend fun buildVisionTranscriber(): VisionTranscriber? = buildVisionTranscribers().firstOrNull()

    /**
     * 组装目录探针（P3b-1）：与 [buildVisionTranscriber] 同源（VISION 角色档案），
     * 视觉不可用 → null（探针静默跳过）。cacheDir/renderFn 由调用方
     * 绑定（探针逐页渲染→调用→释放，内存峰值 ≤1 页图）。
     */
    suspend fun buildTocVisionParser(cacheDir: File, renderFn: (Int) -> String): TocVisionParser? {
        if (!load().visionEnabled) return null
        val llm = resolveLlm(LlmRole.VISION) ?: return null
        return TocVisionParser(llm.baseUrl, llm.apiKey, llm.model, cacheDir, renderFn = renderFn)
    }

    /**
     * 组装前置探针（P8）：与 [buildTocVisionParser] 同源与不可用语义（null=静默跳过，
     * 回退纯文本路线）。
     */
    suspend fun buildFrontMatterVisionParser(
        cacheDir: File,
        renderFn: (Int) -> String,
    ): FrontMatterVisionParser? {
        if (!load().visionEnabled) return null
        val llm = resolveLlm(LlmRole.VISION) ?: return null
        return FrontMatterVisionParser(llm.baseUrl, llm.apiKey, llm.model, cacheDir, renderFn = renderFn)
    }

    companion object {
        // 旧版 AI 配置键（迁移数据源，保留不写不读端点；见 migrateLegacyLlmIfNeeded）
        const val KEY_ENC = "api_key_enc"
        const val KEY_DETAIL = "note_detail"
        const val KEY_VISION_ENABLED = "vision_enabled"
        const val KEY_VISION_MODEL = "vision_model"
        const val KEY_VISION_BASE = "vision_base"
        const val KEY_VISION_KEY_ENC = "vision_key_enc"
        // 备用视觉组（R3 多模型轮转）
        const val KEY_VISION2_BASE = "vision2_base"
        const val KEY_VISION2_MODEL = "vision2_model"
        const val KEY_VISION2_KEY_ENC = "vision2_key_enc"
        // 讲解模型（按用途选模型）：段落讲解与粗读批注专用，空 = 跟随主力
        const val KEY_NOTE_MODEL = "note_model"
        // LLM 档案体系（2026-11-15 多模型轮）
        const val KEY_LLM_PROFILES = "llm_profiles"
        const val KEY_LLM_ASSIGN = "llm_assign"
        // 扫描书本地识别（P6b S4）
        const val KEY_OCR_ENABLED = "ocr_enabled"
        const val KEY_OCR_FEEDBACK_TOTAL = "ocr_feedback_total"
        const val KEY_OCR_FEEDBACK_BOOKPrefix = "ocr_feedback_book_"
        const val KEY_OCR_LAST_VERSION = "ocr_last_version"
        // 应用自更新（M7）
        const val KEY_GITHUB_TOKEN_ENC = "github_token_enc"
        const val KEY_UPDATE_AUTO = "update_auto_check"
        const val KEY_UPDATE_LAST = "update_last_check_ms"
        const val KEY_UPDATE_API_BASE = "update_api_base"
        // 阅读字号/行距（阅读页「Aa」弹层）
        const val KEY_READ_FONT_SIZE = "read_font_size"
        const val READ_FONT_MIN = 14
        const val READ_FONT_MAX = 30
        const val DEFAULT_READ_FONT_SIZE = 18
        const val KEY_READ_LINE_HEIGHT = "read_line_height"
        const val READ_LINE_MIN = 1.4f
        const val READ_LINE_MAX = 2.2f
        const val DEFAULT_READ_LINE_HEIGHT = 1.7f
        // 翻页模式（体验优化轮）：点屏幕两侧/左右滑翻页
        const val KEY_READ_PAGE_MODE = "read_page_mode"
        const val KEY_READ_THEME = "read_theme"
        const val KEY_READ_THEME_DEFAULT = "follow"
        const val KEY_READ_VOLUME_KEY = "read_volume_key"
        // 阅读字体（实景体验轮第七波）：default=系统 / lxgw=霞鹜文楷 Lite
        const val KEY_READ_FONT_FAMILY = "read_font_family"
        const val READ_FONT_DEFAULT = "default"
        const val READ_FONT_LXGW = "lxgw"
        // 阅读页亮度（实景体验轮第七波）：-1=跟随系统；0.15~1.0=固定亮度
        const val KEY_READ_BRIGHTNESS = "read_brightness"
        const val READ_BRIGHTNESS_MIN = 0.15f
        const val READ_BRIGHTNESS_MAX = 1.0f
        // 外观主题三态（system=跟随系统）
        const val KEY_THEME = "theme_mode"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        val THEME_MODES = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)
        // 朗读语速（正文听书）
        const val KEY_TTS_RATE = "tts_rate"
        const val TTS_RATE_MIN = 0.5f
        const val TTS_RATE_MAX = 2.0f
        const val DEFAULT_TTS_RATE = 1.0f
        const val KEY_TTS_ENGINE = "tts_engine"
        const val TTS_ENGINE_SYSTEM = "system"
        const val TTS_ENGINE_LOCAL = "local"
        const val TTS_ENGINE_EDGE = "edge"
        // Edge 在线朗读音色（值即微软神经语音名）
        const val KEY_TTS_EDGE_VOICE = "tts_edge_voice"
        const val EDGE_VOICE_XIAOXIAO = "zh-CN-XiaoxiaoNeural"
        const val EDGE_VOICE_YUNXI = "zh-CN-YunxiNeural"
        // 复习到期提醒（每日本地通知）
        const val KEY_REVIEW_REMINDER = "review_reminder"
        // 每日阅读目标分钟（P1-⑤ 激励统计：进度环/热力图色阶基准）
        const val KEY_READ_DAILY_GOAL = "read_daily_goal_minutes"
        const val READ_GOAL_MIN = 5
        const val READ_GOAL_MAX = 600
        const val DEFAULT_READ_GOAL = 30
        // 复习到期列表排序（P1 复习两小招：risk=最快要忘优先 / due=到期序 / random=随机）
        const val KEY_REVIEW_SORT = "review_sort_mode"
        val REVIEW_SORT_MODES = listOf("risk", "due", "random")
        val DETAIL_LEVELS = listOf("简略", "标准", "深入")
        const val DEFAULT_BASE = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        /** 视觉兜底默认模型（OPT-E）：龙猫 2.5 预览版，实测书页整页转写可用 */
        const val DEFAULT_VISION_MODEL = "LongCat-2.5-Preview"

        /** 仅供测试/协议演示生成密钥；线上走 AndroidKeyStore */
        fun newProtocolKey(): SecretKey = SecretCrypto.newKey()
    }
}
