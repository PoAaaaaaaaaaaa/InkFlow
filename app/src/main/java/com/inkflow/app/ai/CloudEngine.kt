package com.inkflow.app.ai

import com.inkflow.core.ai.AiCapability
import com.inkflow.core.ai.AiEngine
import com.inkflow.core.ai.AiEngineException
import com.inkflow.core.ai.AiRequest
import com.inkflow.core.ai.AiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 引擎 3：云端 OpenAI 兼容接口。
 *
 * 离线优先不等于拒绝云端——当本机没有 AICore、也没有导入本地模型时，
 * 用户仍可以配置自己的 API（OpenAI / DeepSeek / 通义 / 本地 Ollama 等），
 * 用质量最高的模型来完成关键章节。
 *
 * 隐私策略：**仅当用户显式开启并配置后才启用**，且请求只包含当前章节所需上下文。
 */
class CloudEngine(
    private val config: CloudConfig,
) : AiEngine {

    override val id: String = "cloud"
    override val displayName: String = config.displayName.ifBlank { "云端模型（${config.model}）" }

    /** 云端引擎明确不是离线，UI 必须如实告知用户。 */
    override val isOnDevice: Boolean = false

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun capabilities(): Set<AiCapability> = setOf(
        AiCapability.LongFormGeneration,
        AiCapability.Summarization,
        AiCapability.Proofreading,
        AiCapability.Rewriting,
        AiCapability.StructuredOutput,
    )

    override suspend fun isAvailable(): Boolean = config.isUsable

    override suspend fun complete(request: AiRequest): AiResponse = withContext(Dispatchers.IO) {
        if (!config.isUsable) throw AiEngineException("云端模型未配置（缺少 API Key 或接口地址）")
        val start = System.currentTimeMillis()
        val payload = buildPayload(request, stream = false)
        val httpRequest = Request.Builder()
            .url(config.endpoint())
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        try {
            client.newCall(httpRequest).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw AiEngineException("云端返回 ${resp.code}：${body.take(300)}")
                }
                val parsed = json.parseToJsonElement(body).jsonObject
                val text = parsed["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    .orEmpty()
                if (text.isBlank()) throw AiEngineException("云端返回空内容")
                val usage = parsed["usage"]?.jsonObject
                AiResponse(
                    text = text,
                    engineId = id,
                    promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    latencyMs = System.currentTimeMillis() - start,
                )
            }
        } catch (e: AiEngineException) {
            throw e
        } catch (e: IOException) {
            throw AiEngineException("网络请求失败：${e.message}", e)
        } catch (e: Exception) {
            throw AiEngineException("云端响应解析失败：${e.message}", e)
        }
    }

    override fun stream(request: AiRequest): Flow<String> = callbackFlow {
        if (!config.isUsable) {
            close(AiEngineException("云端模型未配置"))
            return@callbackFlow
        }
        val payload = buildPayload(request, stream = true)
        val httpRequest = Request.Builder()
            .url(config.endpoint())
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    close(); return
                }
                val delta = runCatching {
                    json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.content
                }.getOrNull()
                if (!delta.isNullOrEmpty()) trySend(delta)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(AiEngineException("流式连接失败：${t?.message ?: response?.code}", t))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val source = EventSources.createFactory(client).newEventSource(httpRequest, listener)
        awaitClose { source.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun buildPayload(request: AiRequest, stream: Boolean): String {
        val obj: JsonObject = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                if (request.system.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", request.system)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", request.prompt)
                })
            }
            put("temperature", request.temperature.toDouble())
            put("top_p", request.topP.toDouble())
            put("max_tokens", request.maxTokens)
            put("stream", stream)
            if (request.stop.isNotEmpty()) {
                putJsonArray("stop") { request.stop.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            }
            request.jsonSchemaHint?.let {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }
        return obj.toString()
    }

    /** 连通性测试，供设置页「测试连接」按钮使用。 */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = complete(
                AiRequest(
                    prompt = "请只回复两个字：正常",
                    maxTokens = 16,
                    temperature = 0f,
                )
            )
            resp.text.take(60)
        }.recoverCatching { t -> throw AiEngineException(t.message ?: "未知错误", t) }
    }

    /**
     * 拉取服务端可用模型列表（GET {base}/models，OpenAI 兼容协议的标配端点）。
     *
     * 兼容性处理：
     *  - 部分服务（如某些 One-API 部署）返回 {"data":[{...}]}，也有返回裸数组的；
     *  - 有的只返回 {"object":"list","data":[]}，即"能连上但拿不到列表"；
     *  - Ollama 的 /v1/models 与原生 /api/tags 字段结构不同，这里统一兼容。
     *
     * @return 成功时返回模型 id 列表（已去重排序）；失败抛出带原因的诊断信息。
     */
    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (config.baseUrl.isBlank()) throw AiEngineException("请先填写接口地址")

            val url = config.modelsEndpoint()
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw AiEngineException(
                        when (resp.code) {
                            401, 403 -> "鉴权失败（${resp.code}）：API Key 可能不正确或没有权限"
                            404 -> "该地址没有 /models 端点（404）。请确认地址是否为 OpenAI 兼容接口，" +
                                "例如应以 /v1 结尾；若服务不支持列举模型，可手动填写模型名"
                            else -> "服务返回 ${resp.code}：${body.take(200)}"
                        }
                    )
                }
                parseModelIds(body)
            }
        }
    }

    /** 解析模型列表响应，兼容多种常见结构。 */
    internal fun parseModelIds(body: String): List<String> {
        if (body.isBlank()) throw AiEngineException("服务返回了空响应")

        val element = runCatching { json.parseToJsonElement(body) }.getOrElse {
            throw AiEngineException("返回内容不是合法 JSON，可能不是 OpenAI 兼容接口")
        }

        val ids = LinkedHashSet<String>()

        fun collectFromArray(arr: kotlinx.serialization.json.JsonArray) {
            arr.forEach { item ->
                // 结构 A: [{"id":"gpt-4o"}, ...]
                val obj = item as? JsonObject
                if (obj != null) {
                    val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe()
                        ?: obj["name"]?.jsonPrimitive?.contentOrNullSafe()
                        ?: obj["model"]?.jsonPrimitive?.contentOrNullSafe()
                    if (!id.isNullOrBlank()) ids.add(id)
                } else {
                    // 结构 B: ["gpt-4o", ...]
                    item.jsonPrimitive.contentOrNullSafe()?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
                }
            }
        }

        when (element) {
            is JsonObject -> {
                // 结构 C: {"data":[...]} 或 {"models":[...]}
                val arr = element["data"] as? kotlinx.serialization.json.JsonArray
                    ?: element["models"] as? kotlinx.serialization.json.JsonArray
                if (arr != null) collectFromArray(arr)
                else throw AiEngineException("响应中没有找到模型列表字段（data / models）")
            }
            is kotlinx.serialization.json.JsonArray -> collectFromArray(element)
            else -> throw AiEngineException("无法识别的响应结构")
        }

        if (ids.isEmpty()) {
            throw AiEngineException(
                "连接成功，但服务未返回任何模型。请手动填写模型名" +
                    "（部分服务需要先在后台开通模型权限）"
            )
        }
        return ids.sorted()
    }

    /**
     * 一次性诊断：先列模型，再试一次真实对话。
     * 设置页用它给出「具体哪一步失败」的提示，而不是笼统的"连接失败"。
     */
    suspend fun diagnose(): DiagnosticResult = withContext(Dispatchers.IO) {
        val models = fetchModels()
        if (models.isFailure) {
            val err = models.exceptionOrNull()
            return@withContext DiagnosticResult(
                modelsOk = false,
                chatOk = false,
                models = emptyList(),
                modelsError = err?.message ?: "未知错误",
                chatError = "未执行（模型列表获取失败）",
            )
        }
        val list = models.getOrDefault(emptyList())
        val chat = testConnection()
        DiagnosticResult(
            modelsOk = true,
            chatOk = chat.isSuccess,
            models = list,
            modelsError = null,
            chatError = chat.exceptionOrNull()?.message,
        )
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** 诊断结果：分别说明「列模型」与「真实对话」两步的成败。 */
data class DiagnosticResult(
    val modelsOk: Boolean,
    val chatOk: Boolean,
    val models: List<String>,
    val modelsError: String? = null,
    val chatError: String? = null,
) {
    val allOk: Boolean get() = modelsOk && chatOk

    /** 给用户看的一句话结论。 */
    fun summary(): String = when {
        allOk -> "连接正常，可用模型 ${models.size} 个"
        modelsOk && !chatOk -> "能列出模型，但对话失败：${chatError.orEmpty().take(120)}"
        else -> modelsError.orEmpty().take(160)
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()

/**
 * 云端模型配置。存在 DataStore 中，API Key 不进入日志、不上传。
 */
@Serializable
data class CloudConfig(
    @SerialName("baseUrl") val baseUrl: String = "https://api.openai.com/v1",
    @SerialName("apiKey") val apiKey: String = "",
    @SerialName("model") val model: String = "gpt-4o-mini",
    @SerialName("displayName") val displayName: String = "",
    @SerialName("timeoutSeconds") val timeoutSeconds: Long = 120L,
    @SerialName("enabled") val enabled: Boolean = false,
) {
    val isUsable: Boolean get() = enabled && apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /** 规范化 base：补协议头、去掉尾部斜杠。 */
    private fun normalizedBase(): String {
        var base = baseUrl.trim().removeSuffix("/")
        if (base.isEmpty()) return base
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            // 内网地址用 http 更常见（Ollama/内网网关），公网域名用 https
            base = if (looksLikePrivateHost(base)) "http://$base" else "https://$base"
        }
        return base
    }

    /** 判断是否像内网/本机地址，用于选择默认协议并给出安全提示。 */
    fun looksLikePrivateHost(raw: String = baseUrl): Boolean {
        val host = raw.trim()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')
            .lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2") return true
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".internal")) return true
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31)
        }
        return false
    }

    /** 当前配置是否会以明文发送（用于 UI 如实告知用户）。 */
    fun isCleartext(): Boolean = normalizedBase().startsWith("http://")

    /** 容忍用户只填到 `/v1`，自动补全 chat/completions 路径。 */
    fun endpoint(): String {
        val base = normalizedBase()
        if (base.isEmpty()) return base
        if (base.endsWith("/chat/completions")) return base
        // 已带版本段或已是完整路径时，直接接 chat/completions
        return "$base/chat/completions"
    }

    /** 模型列表端点：把 chat/completions 换成 models。 */
    fun modelsEndpoint(): String {
        val base = normalizedBase()
        if (base.isEmpty()) return base
        return when {
            base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/models"
            base.endsWith("/models") -> base
            else -> "$base/models"
        }
    }
}
