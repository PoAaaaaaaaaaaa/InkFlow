package com.inkflow.app.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import com.inkflow.core.ai.AiCapability
import com.inkflow.core.ai.AiEngine
import com.inkflow.core.ai.AiEngineException
import com.inkflow.core.ai.AiRequest
import com.inkflow.core.ai.AiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 引擎 2：LiteRT-LM（Google AI Edge）本地大模型。
 *
 * 与 Gemini Nano 的分工：Nano 是系统托管的固定小模型；
 * LiteRT-LM 允许用户**自行导入 .litertlm 模型**（如 Gemma 系列），
 * 获得更强的长文创作能力，并可通过 GPU / NPU 后端加速。
 *
 * 模型放置位置：应用私有目录 `files/models/` 下的 `.litertlm` 文件（用户可通过「导入模型」写入）。
 */
class LiteRtLmEngine(private val context: Context) : AiEngine {

    override val id: String = "litert-lm"
    override val displayName: String = "LiteRT-LM 本地模型"
    override val isOnDevice: Boolean = true

    @Volatile private var engine: Engine? = null
    @Volatile private var loadedPath: String? = null

    override fun capabilities(): Set<AiCapability> = setOf(
        AiCapability.LongFormGeneration,
        AiCapability.Summarization,
        AiCapability.Proofreading,
        AiCapability.Rewriting,
        AiCapability.StructuredOutput,
    )

    /** 扫描模型目录，返回可用的 .litertlm 文件。 */
    fun listModels(): List<File> {
        val dir = modelsDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(MODEL_EXT) }
            ?.sortedByDescending { it.length() }
            ?: emptyList()
    }

    fun modelsDir(): File = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        listModels().isNotEmpty()
    }

    override suspend fun complete(request: AiRequest): AiResponse = withContext(Dispatchers.IO) {
        val session = createSession(request)
        val start = System.currentTimeMillis()
        try {
            val prompt = composePrompt(request)
            val text = session.generateContent(listOf(InputData.Text(prompt)))
            if (text.isBlank()) throw AiEngineException("LiteRT-LM 返回空结果")
            AiResponse(
                text = text,
                engineId = id,
                latencyMs = System.currentTimeMillis() - start,
            )
        } catch (t: Throwable) {
            throw AiEngineException("LiteRT-LM 推理失败：${t.message}", t)
        } finally {
            runCatching { session.close() }
        }
    }

    override fun stream(request: AiRequest): Flow<String> = callbackFlow {
        val session = try {
            createSession(request)
        } catch (t: Throwable) {
            close(AiEngineException("LiteRT-LM 会话创建失败：${t.message}", t))
            return@callbackFlow
        }
        val prompt = composePrompt(request)
        try {
            session.generateContentStream(
                listOf(InputData.Text(prompt)),
                object : ResponseCallback {
                    override fun onNext(response: String) {
                        if (response.isNotEmpty()) trySend(response)
                    }

                    override fun onDone() {
                        close()
                    }

                    override fun onError(error: Throwable) {
                        close(AiEngineException("LiteRT-LM 流式推理失败：${error.message}", error))
                    }
                }
            )
        } catch (t: Throwable) {
            close(AiEngineException("LiteRT-LM 流式调用失败：${t.message}", t))
        }
        awaitClose {
            runCatching { session.cancelProcess() }
            runCatching { session.close() }
        }
    }.flowOn(Dispatchers.IO)

    private fun composePrompt(request: AiRequest): String {
        // LiteRT-LM 的 Session API 不区分 system/user，用分隔符拼接
        return buildString {
            if (request.system.isNotBlank()) {
                appendLine("<|system|>")
                appendLine(request.system)
            }
            appendLine("<|user|>")
            append(request.prompt)
            appendLine()
            append("<|assistant|>")
        }
    }

    private fun createSession(request: AiRequest): Session {
        val modelFile = listModels().firstOrNull()
            ?: throw AiEngineException("未找到本地模型，请在「设置 → 端侧模型」导入 .litertlm 文件")

        val eng = obtainEngine(modelFile)
        val sampler = SamplerConfig(
            /* topK = */ 40,
            /* topP = */ request.topP.toDouble(),
            /* temperature = */ request.temperature.toDouble(),
            /* seed = */ 0,
        )
        return eng.createSession(SessionConfig(sampler, null))
    }

    private fun obtainEngine(modelFile: File): Engine {
        val cached = engine
        if (cached != null && loadedPath == modelFile.absolutePath && cached.isInitialized()) return cached

        synchronized(this) {
            engine?.let { runCatching { it.close() } }
            val backend = pickBackend()
            Log.i(TAG, "初始化 LiteRT-LM：${modelFile.name} / backend=${backend.name}")
            val config = EngineConfig(
                /* modelPath = */ modelFile.absolutePath,
                /* backend = */ backend,
                /* visionBackend = */ null,
                /* audioBackend = */ null,
                /* maxNumTokens = */ 4096,
                /* maxNumImages = */ null,
                /* cacheDir = */ File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath,
            )
            val eng = try {
                Engine(config).also { it.initialize() }
            } catch (t: Throwable) {
                throw AiEngineException("LiteRT-LM 引擎初始化失败：${t.message}", t)
            }
            engine = eng
            loadedPath = modelFile.absolutePath
            return eng
        }
    }

    /**
     * 后端选择：优先 NPU（能效最优），其次 GPU（解码最快），最后 CPU。
     * 由于不同机型的 NPU 运行时库各异，这里只做保守探测，失败由上层回退。
     */
    private fun pickBackend(): Backend {
        val supportsNpu = runCatching {
            File(context.applicationInfo.nativeLibraryDir, "libLiteRtDispatch_Qualcomm.so").exists() ||
                File(context.applicationInfo.nativeLibraryDir, "libLiteRtDispatch_GoogleTensor.so").exists()
        }.getOrDefault(false)

        return when {
            supportsNpu -> runCatching { Backend.NPU(context.applicationInfo.nativeLibraryDir) }
                .getOrElse { Backend.GPU() }
            else -> runCatching { Backend.GPU() }.getOrElse { Backend.CPU() }
        }
    }

    /** 释放引擎，回收显存/NPU 资源。 */
    fun release() {
        synchronized(this) {
            runCatching { engine?.close() }
            engine = null
            loadedPath = null
        }
    }

    private companion object {
        const val TAG = "LiteRtLmEngine"
        const val MODEL_EXT = ".litertlm"
    }
}
