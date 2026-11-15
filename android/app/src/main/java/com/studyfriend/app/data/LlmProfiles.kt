package com.studyfriend.app.data

import kotlinx.serialization.Serializable

/**
 * LLM 档案（2026-11-15 多模型轮）：一个档案 = 一家服务商的一套连接配置
 * （名称 + API 地址 + 默认模型 + 温度）。API Key 密文单独落 settings 表
 * （`llm_key_<id>_enc`，沿用 `_enc` 后缀——备份导出自动排除、恢复不碰），
 * 不进 JSON，也永不明文出库。
 */
@Serializable
data class LlmProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val temperature: Double = 0.3,
)

/**
 * 功能 → 档案分配：
 * - main 空 = 用第一个档案（档案列表永不为空，见 SettingsRepository 迁移）
 * - note / vision 空 = 跟随主力（保留旧版「讲解/视觉跟主力」语义）
 * - vision2 空 = 不启用备用视觉轮转
 */
@Serializable
data class LlmAssign(
    val main: String = "",
    val note: String = "",
    val vision: String = "",
    val vision2: String = "",
)

/** 有 AI 调用的功能角色 */
enum class LlmRole { MAIN, NOTE, VISION, VISION2 }

/** 角色解析结果：端点 + 已解密 Key 明文（只在内存流转，不落库不打日志） */
data class ResolvedLlm(
    val profileId: String,
    val profileName: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
)

/** 档案页快照：档案列表 + 分配 + 哪些档案已存 Key（明文不出库，keyIds 供 UI 显示状态） */
data class LlmSettings(
    val profiles: List<LlmProfile>,
    val assign: LlmAssign,
    val keyIds: Set<String>,
)
