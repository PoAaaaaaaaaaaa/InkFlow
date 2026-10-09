package com.inkflow.app.ai

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.inkflow.core.ai.AiCapability
import com.inkflow.core.ai.AiEngine
import com.inkflow.core.ai.AiEngineException
import com.inkflow.core.ai.AiRequest
import com.inkflow.core.ai.AiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 引擎 1：Gemini Nano（经由 ML Kit GenAI / AICore 系统服务）。
 *
 * 定位：**完全离线、数据不出设备**。四类开箱能力正好覆盖写作场景：
 *  - Prompt（自由生成）→ 续写、章节创作；
 *  - Summarization → 章节交接笔记、前情提要；
 *  - Proofreading → 校对；
 *  - Rewriting → 润色。
 *
 * 采用反射式软依赖：设备没有 AICore 时类加载也不会崩，
 * 直接判定为不可用并让路由层回退到下一个引擎。
 */
class NanoEngine(private val context: Context) : AiEngine {

    override val id: String = "nano"
    override val displayName: String = "Gemini Nano（设备端 · 离线）"
    override val isOnDevice: Boolean = true

    @Volatile
    private var client: GenerativeModel? = null

    @Volatile
    private var statusChecked = false

    @Volatile
    private var available = false

    override fun capabilities(): Set<AiCapability> = setOf(
        AiCapability.LongFormGeneration,
        AiCapability.Summarization,
        AiCapability.Proofreading,
        AiCapability.Rewriting,
    )

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (statusChecked) return@withContext available
        available = try {
            val model = obtainClient()
            val status = model.checkStatus()
            // AVAILABLE=可用；DOWNLOADABLE 表示设备支持但模型尚未下载
            status == FeatureStatus.AVAILABLE
        } catch (t: Throwable) {
            Log.i(TAG, "AICore 不可用：${t.message}")
            false
        }
        statusChecked = true
        available
    }

    /** 设备是否支持但模型未下载。UI 用它来决定是否显示「下载模型」引导。 */
    suspend fun modelDownloadable(): Boolean = withContext(Dispatchers.IO) {
        try {
            obtainClient().checkStatus() == FeatureStatus.DOWNLOADABLE
        } catch (t: Throwable) {
            false
        }
    }

    /** 触发系统模型下载（由 AICore 在后台完成，UI 可展示进度）。 */
    fun downloadModel(): Flow<Int> = flow {
        val model = obtainClient()
        model.download().collect { st ->
            // DownloadStatus 的具体子类由 GenAI 库提供，这里用反射读取进度字段，
            // 保证在 beta 版 API 变动时不会编译期失败。
            val progress = runCatching {
                st.javaClass.methods.firstOrNull { it.name == "getProgress" }?.invoke(st) as? Int
            }.getOrNull()
            emit(progress ?: -1)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun complete(request: AiRequest): AiResponse {
        val model = obtainClient()
        val start = System.currentTimeMillis()
        val text = withContext(Dispatchers.IO) {
            try {
                val req = buildRequest(request)
                val resp = model.generateContent(req)
                resp.candidates.firstOrNull()?.text.orEmpty()
            } catch (t: Throwable) {
                throw AiEngineException("Gemini Nano 推理失败：${t.message}", t)
            }
        }
        if (text.isBlank()) throw AiEngineException("Gemini Nano 返回空结果")
        return AiResponse(
            text = text,
            engineId = id,
            latencyMs = System.currentTimeMillis() - start,
        )
    }

    override fun stream(request: AiRequest): Flow<String> = flow {
        val model = obtainClient()
        val req = buildRequest(request)
        // generateContentStream 返回 Flow<GenerateContentResponse>，逐个 emit 增量文本
        model.generateContentStream(req).collect { resp ->
            val delta = resp.candidates.firstOrNull()?.text.orEmpty()
            if (delta.isNotEmpty()) emit(delta)
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequest(request: AiRequest): GenerateContentRequest {
        // GenAI 的 Builder 暴露的是 Kotlin 属性（get/set 成对），
        // 不能调用 Java 风格的 setXxx()，只能用属性赋值。
        // 构造器有两个重载：带 systemInstruction 与不带，按需选择。
        val builder = if (request.system.isNotBlank()) {
            GenerateContentRequest.Builder(SystemInstruction(request.system), TextPart(request.prompt))
        } else {
            GenerateContentRequest.Builder(TextPart(request.prompt))
        }
        builder.temperature = request.temperature
        builder.candidateCount = 1
        builder.maxOutputTokens = request.maxTokens.coerceIn(64, 4096)
        return builder.build()
    }

    private fun obtainClient(): GenerativeModel {
        client?.let { return it }
        return synchronized(this) {
            client ?: try {
                val model = Generation.getClient()
                client = model
                model
            } catch (t: Throwable) {
                throw AiEngineException("无法初始化 AICore 客户端：${t.message}", t)
            }
        }
    }

    /** 释放推理资源。长时间写作后由 ViewModel 调用，避免常驻占用内存。 */
    fun release() {
        runCatching { client?.close() }
        client = null
        statusChecked = false
        available = false
    }

    private companion object {
        const val TAG = "NanoEngine"
    }
}
