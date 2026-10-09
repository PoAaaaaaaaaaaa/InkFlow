package com.inkflow.app.ai

import com.inkflow.core.ai.AiCapability
import com.inkflow.core.ai.AiEngine
import com.inkflow.core.ai.AiRequest
import com.inkflow.core.ai.AiResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 引擎 4：离线模板引擎（最后兜底）。
 *
 * 为什么需要它：写作软件最怕「打开就报错」。
 * 当设备没有 AICore、用户没导入本地模型、也没配置云端时，
 * 本引擎仍然提供**结构化的可用产出**：
 *  - 依据细纲给出场景骨架与节奏提示；
 *  - 给出符合文风的起笔句式，作者可直接接着写；
 *  - 基于检索到的设定生成「本章要素清单」。
 *
 * 它明确标记 `degraded = true`，UI 会显著提示「当前为离线助手模式」，
 * 绝不假装这是 AI 生成的小说正文——诚实是产品底线。
 */
class TemplateEngine : AiEngine {

    override val id: String = "template"
    override val displayName: String = "离线写作助手（无需模型）"
    override val isOnDevice: Boolean = true

    override fun capabilities(): Set<AiCapability> = setOf(
        AiCapability.StructuredOutput,
        AiCapability.Summarization,
    )

    override suspend fun isAvailable(): Boolean = true

    override suspend fun complete(request: AiRequest): AiResponse {
        delay(180)  // 模拟极短的思考时间，避免 UI 闪烁
        val text = when {
            request.prompt.contains("交接笔记") -> buildHandoff(request.prompt)
            request.jsonSchemaHint != null -> "[]"
            else -> buildChapterScaffold(request)
        }
        return AiResponse(text = text, engineId = id, degraded = true)
    }

    override fun stream(request: AiRequest): Flow<String> = flow {
        val text = complete(request).text
        // 逐段吐出，保持与真流式一致的阅读体验
        text.chunked(24).forEach {
            emit(it)
            delay(28)
        }
    }

    /**
     * 生成「场景脚手架」：不是小说正文，而是可直接扩写的结构提示。
     */
    private fun buildChapterScaffold(request: AiRequest): String {
        val contextBlock = request.system
            .substringAfter("作品语境（检索自本地知识库）=====", "")
            .substringBefore("=====")
            .trim()

        val outline = request.prompt
            .substringAfter("本章细纲：", "")
            .substringBefore("上一章交接笔记", "")
            .trim()
            .ifBlank { request.prompt.take(400) }

        val sb = StringBuilder()
        sb.appendLine("【离线助手 · 本章写作骨架】")
        sb.appendLine()
        sb.appendLine("当前未启用任何 AI 引擎，以下内容依据你的细纲与设定库自动生成，供你直接落笔。")
        sb.appendLine()
        sb.appendLine("一、场景节拍（建议 5 拍，按此推进可保证信息量）")
        val beats = listOf(
            "开场：用一个具体动作或对话切入，避免环境铺陈开头",
            "张力：抛出本章核心冲突，让主角的目标受阻",
            "升级：加入意外变量（新人物/新情报/代价提高）",
            "转折：主角做出选择，付出代价或暴露弱点",
            "钩子：结尾留一个未解决的问题，指向下一章",
        )
        beats.forEachIndexed { i, b -> sb.appendLine("  ${i + 1}. $b") }
        sb.appendLine()

        if (outline.isNotBlank()) {
            sb.appendLine("二、你的细纲要点")
            outline.lines().filter { it.isNotBlank() }.take(12)
                .forEach { sb.appendLine("  · ${it.trim().take(120)}") }
            sb.appendLine()
        }

        if (contextBlock.isNotBlank()) {
            sb.appendLine("三、本章需照应的设定与伏笔")
            contextBlock.lines().filter { it.isNotBlank() }.take(14)
                .forEach { sb.appendLine("  · ${it.trim().take(140)}") }
            sb.appendLine()
        }

        sb.appendLine("四、可用的起笔句式（挑一个改写）")
        listOf(
            "「他推开门的时候，就知道事情不对。」",
            "「那句话她终究没有说出口。」",
            "「雨是在半夜停的。」",
            "「没有人注意到角落里的那个人。」",
        ).forEach { sb.appendLine("  $it") }
        sb.appendLine()
        sb.appendLine("提示：在「设置 → AI 引擎」中开启 Gemini Nano、导入本地模型或配置云端 API，即可让 AI 直接生成正文。")
        return sb.toString()
    }

    private fun buildHandoff(prompt: String): String {
        val body = prompt.substringAfter("正文：", "").trim()
        if (body.isBlank()) return "（本章尚未写作，暂无交接笔记）"
        // 取首尾各一段作为粗略摘要，并标注需人工确认
        val head = body.take(120).replace('\n', ' ')
        val tail = body.takeLast(120).replace('\n', ' ')
        return buildString {
            append("【离线摘要 · 建议人工补充】")
            append("开篇：$head ")
            append("结尾：$tail ")
            append("（当前为离线助手，无法进行语义级摘要；启用 AI 引擎后可自动生成含伏笔状态的交接笔记。）")
        }
    }
}
