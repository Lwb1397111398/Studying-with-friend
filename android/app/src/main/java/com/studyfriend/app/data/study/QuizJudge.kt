package com.studyfriend.app.data.study

import android.content.Context
import com.studyfriend.app.data.LlmRole
import com.studyfriend.app.data.SettingsRepository
import com.studyfriend.app.data.ai.AiBudgets
import com.studyfriend.app.data.ai.AiClient
import com.studyfriend.app.data.ai.AiMessage
import com.studyfriend.app.data.ai.ChatRequest
import com.studyfriend.app.data.ai.PromptLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// AI 判卷（学习闭环轮）：自测题作答后可选让搭子对照参考答案判分——
// 哪些得分点答到了、漏了什么、一句改进建议。单次调用走讲解模型，用户手动触发。

/** 判卷结果：covered=答到的得分点；missing=漏掉的关键点；advice=一句改进建议 */
@Serializable
data class QuizJudgeResult(
    val covered: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val advice: String = "",
)

object QuizJudge {
    const val PROMPT_JUDGE = "quiz_judge"

    /** 输出规整：过滤空白、每条截 60 字、covered/missing 各截 6 条、advice 截 80 字 */
    fun normalize(r: QuizJudgeResult): QuizJudgeResult = QuizJudgeResult(
        covered = r.covered.map { it.trim() }.filter { it.isNotEmpty() }.take(6).map { it.take(60) },
        missing = r.missing.map { it.trim() }.filter { it.isNotEmpty() }.take(6).map { it.take(60) },
        advice = r.advice.trim().take(80),
    )

    /** user 消息 JSON 组装（题干/参考答案/解析/我的作答，值均经 jsonEsc 转义） */
    fun userJson(question: String, myAnswer: String, reference: String, explain: String): String =
        buildString {
            append("{\"question\":").append(jsonEsc(question))
            append(",\"reference_answer\":").append(jsonEsc(reference))
            append(",\"explain\":").append(jsonEsc(explain))
            append(",\"my_answer\":").append(jsonEsc(myAnswer))
            append('}')
        }

    /** 单次判卷调用：讲解模型（低成本档），temp 低保证判分稳定；抛 MissingKeyException=无 Key */
    suspend fun judge(
        repo: SettingsRepository,
        context: Context,
        question: String,
        myAnswer: String,
        reference: String,
        explain: String,
    ): QuizJudgeResult = withContext(Dispatchers.IO) {
        // 讲解角色档案（2026-11-15 多模型轮）：判卷是逐题轻任务，跟讲解走
        val llm = repo.resolveLlm(LlmRole.NOTE)
            ?: throw MissingKeyException("API Key 已失效或未保存，请先到设置页填写")
        val systemPrompt = PromptLoader.load(context, PROMPT_JUDGE)
        normalize(
            AiClient.chatJson(
                ChatRequest(
                    baseUrl = llm.baseUrl,
                    apiKey = llm.apiKey,
                    model = llm.model,
                    temperature = 0.2,
                    maxTokens = AiBudgets.OUTPUT_TOKENS_JUDGE,
                    // P0-② 降档梯子：JSON mode 首选，网关拒收/解析不过自动降纯 prompt 档
                    jsonMode = true,
                    source = "AI 判卷",
                    messages = listOf(
                        AiMessage("system", systemPrompt),
                        AiMessage("user", userJson(question, myAnswer, reference, explain)),
                    ),
                ),
                QuizJudgeResult.serializer(),
                onDelta = { },
                validate = { r ->
                    // 结构校验（P0-② 调研评审：字段级白名单防「合格形状的空结果」）——
                    // 全空的判卷没有信息量，带纠正语重试而不是直接展示。
                    // 注：不用「条目必须逐字出现在作答里」校验——判卷 prompt 第 4 条明确
                    // 「意思相同但用词不同也算覆盖」，逐字比对会把合法转述误杀
                    if (r.covered.isEmpty() && r.missing.isEmpty() && r.advice.isBlank()) {
                        "covered/missing/advice 全为空"
                    } else {
                        null
                    }
                },
            ),
        )
    }
}
