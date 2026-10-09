package com.inkflow.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * 端侧/云端 AI 引擎的统一抽象。
 *
 * 上层多智能体管线只依赖这个接口，因此：
 *  - 有 AICore(Gemini Nano) 时走系统离线推理；
 *  - 有 LiteRT-LM / GenieX 时走本地大模型；
 *  - 都没有时降级到云端 OpenAI 兼容接口；
 *  - 全部不可用时仍有 [TemplateFallback] 保证产品可用（离线优先）。
 */
interface AiEngine {
    /** 稳定标识，用于路由与埋点 */
    val id: String

    /** 展示名，会出现在设置页让用户知道当前用的是什么引擎 */
    val displayName: String

    /** 是否为完全离线（数据不出设备）。UI 需要如实告知用户。 */
    val isOnDevice: Boolean

    /** 能力集合，路由层据此挑选引擎 */
    fun capabilities(): Set<AiCapability>

    /** 运行时探测可用性（例如 AICore 是否已下载模型） */
    suspend fun isAvailable(): Boolean

    /** 一次性补全 */
    suspend fun complete(request: AiRequest): AiResponse

    /**
     * 流式补全。长篇正文必须流式，用户才能一边生成一边阅读、随时打断。
     * 实现需保证在取消时及时释放推理资源。
     */
    fun stream(request: AiRequest): Flow<String>
}

enum class AiCapability {
    /** 通用长文本生成（章节正文） */
    LongFormGeneration,

    /** 摘要（章节交接笔记、前情提要） */
    Summarization,

    /** 校对（错别字、病句） */
    Proofreading,

    /** 改写润色 */
    Rewriting,

    /** 结构化输出（大纲/角色卡 JSON） */
    StructuredOutput,
}

data class AiRequest(
    val system: String = "",
    val prompt: String,
    val temperature: Float = 0.85f,
    val topP: Float = 0.95f,
    val maxTokens: Int = 2048,
    /** 停止词，防止模型把「第X章」标题继续编下去 */
    val stop: List<String> = emptyList(),
    /** 期望的结构化输出约束，为空表示自由文本 */
    val jsonSchemaHint: String? = null,
)

data class AiResponse(
    val text: String,
    val engineId: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val latencyMs: Long = 0L,
    /** 是否为降级结果（例如模板兜底）。UI 据此提示用户。 */
    val degraded: Boolean = false,
)

/** 引擎不可用或推理失败时抛出，由路由层捕获并切换到下一优先级引擎。 */
class AiEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
