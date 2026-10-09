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

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

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

    /** 容忍用户只填到 `/v1`，自动补全 chat/completions 路径。 */
    fun endpoint(): String {
        var base = baseUrl.trim().removeSuffix("/")
        if (!base.startsWith("http")) base = "https://$base"
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }
}
