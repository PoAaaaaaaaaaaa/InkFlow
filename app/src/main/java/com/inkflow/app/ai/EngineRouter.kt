package com.inkflow.app.ai

import android.content.Context
import android.util.Log
import com.inkflow.core.ai.AiCapability
import com.inkflow.core.ai.AiEngine
import com.inkflow.core.ai.AiEngineException
import com.inkflow.core.ai.AiRequest
import com.inkflow.core.ai.AiResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 引擎路由：按用户设定的优先级串联多个引擎，失败自动降级。
 *
 * 优先级（默认）：Gemini Nano（离线） → LiteRT-LM（本地大模型） → 云端 API → 离线模板。
 * 依据 [AiCapability] 挑选：例如校对只挑声明支持 Proofreading 的引擎。
 *
 * 关键行为：
 *  - **不静默降级**：每次结果都带 `engineId` 与 `degraded`，UI 如实展示；
 *  - **失败可观测**：记录最后一次失败原因，供设置页诊断；
 *  - **离线优先**：默认顺序把端侧引擎排在云端之前，保护稿件隐私。
 */
class EngineRouter(
    private val nano: NanoEngine,
    private val litert: LiteRtLmEngine,
    private val template: TemplateEngine,
) {
    @Volatile
    private var cloud: CloudEngine? = null

    @Volatile
    var preference: EnginePreference = EnginePreference()

    /** 最近一次各引擎的可用性快照，设置页用它渲染状态。 */
    @Volatile
    var engineStatus: Map<String, Boolean> = emptyMap()
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun updateCloud(config: CloudConfig) {
        cloud = if (config.isUsable) CloudEngine(config) else null
    }

    /** 诊断所有引擎可用性。设置页与首次启动引导都会调用。 */
    suspend fun probeAll(): Map<String, Boolean> {
        val result = linkedMapOf(
            nano.id to runCatching { nano.isAvailable() }.getOrDefault(false),
            litert.id to runCatching { litert.isAvailable() }.getOrDefault(false),
            cloud?.id.orEmpty() to (cloud?.let { runCatching { it.isAvailable() }.getOrDefault(false) } ?: false),
            template.id to true,
        ).filterKeys { it.isNotEmpty() }
        engineStatus = result
        return result
    }

    suspend fun activeEngine(): AiEngine = pick(null)

    suspend fun activeEngineId(): String = activeEngine().id

    /**
     * 选出一个可用引擎。若指定的 [preferredId] 可用则优先使用。
     */
    suspend fun pick(preferredId: String?, capability: AiCapability? = null): AiEngine {
        val ordered = orderedCandidates()
        if (preferredId != null) {
            ordered.firstOrNull { it.id == preferredId }?.let { preferred ->
                if (capability == null || capability in preferred.capabilities()) {
                    if (runCatching { preferred.isAvailable() }.getOrDefault(false)) return preferred
                }
            }
        }
        for (engine in ordered) {
            if (capability != null && capability !in engine.capabilities()) continue
            val ok = runCatching { engine.isAvailable() }.getOrDefault(false)
            if (ok) return engine
        }
        return template
    }

    private fun orderedCandidates(): List<AiEngine> {
        val map = mapOf(
            nano.id to nano,
            litert.id to litert,
            cloud?.id.orEmpty() to cloud,
            template.id to template,
        )
        val order = preference.order.ifEmpty { listOf(nano.id, litert.id, "cloud", template.id) }
        val result = mutableListOf<AiEngine>()
        for (id in order) map[id]?.let { result.add(it) }
        // 补齐未在顺序中出现的引擎，保证不会漏
        map.values.filterNotNull().forEach { if (it !in result) result.add(it) }
        return result
    }

    /**
     * 带自动降级的补全。上层业务只调用这一个方法。
     */
    suspend fun complete(request: AiRequest, preferredId: String? = null): AiResponse {
        val ordered = orderedCandidates().filter { preferredId == null || it.id == preferredId || it.id != preferredId }
        val start = System.currentTimeMillis()
        var lastFailure: Throwable? = null

        for (engine in ordered) {
            if (runCatching { engine.isAvailable() }.getOrDefault(false).not()) continue
            try {
                val resp = engine.complete(request)
                lastError = null
                return resp
            } catch (t: Throwable) {
                lastFailure = t
                lastError = "${engine.displayName}：${t.message}"
                Log.w(TAG, "引擎 ${engine.id} 失败，尝试下一个：${t.message}")
            }
        }

        // 理论上 template 永远可用；走到这里说明连兜底都异常
        throw AiEngineException(
            "所有 AI 引擎均不可用：${lastFailure?.message ?: "未知原因"}",
            lastFailure,
        ).also { Log.e(TAG, "全部引擎失败", it) }
    }

    /** 流式补全：优先使用能流式的引擎，失败则退回非流式一次性输出。 */
    fun stream(request: AiRequest, preferredId: String? = null): Flow<String> = flow {
        val engine = pick(preferredId, null)
        try {
            var emitted = false
            engine.stream(request).collect {
                emitted = true
                emit(it)
            }
            if (!emitted) {
                // 引擎没吐出任何内容，退回一次性补全
                emit(complete(request, preferredId).text)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "流式失败，退回一次性补全：${t.message}")
            lastError = "${engine.displayName} 流式失败：${t.message}"
            emit(complete(request, preferredId).text)
        }
    }

    fun releaseAll() {
        runCatching { nano.release() }
        runCatching { litert.release() }
    }

    private companion object {
        const val TAG = "EngineRouter"
    }
}

/**
 * 引擎优先级配置，持久化在 DataStore。
 */
data class EnginePreference(
    val order: List<String> = listOf("nano", "litert-lm", "cloud", "template"),
    /** 为 true 时即使有端侧引擎也优先用云端（追求质量） */
    val preferCloudForGeneration: Boolean = false,
) {
    fun effectiveOrder(): List<String> =
        if (preferCloudForGeneration) listOf("cloud") + order.filter { it != "cloud" } else order

    companion object {
        fun from(order: List<String>?, preferCloud: Boolean): EnginePreference =
            EnginePreference(order ?: listOf("nano", "litert-lm", "cloud", "template"), preferCloud)
    }
}

/**
 * 引擎工厂：集中依赖装配，避免 Context 到处传递。
 */
object EngineFactory {
    fun createRouter(context: Context, cloud: CloudConfig, preference: EnginePreference): EngineRouter {
        val router = EngineRouter(
            nano = NanoEngine(context.applicationContext),
            litert = LiteRtLmEngine(context.applicationContext),
            template = TemplateEngine(),
        )
        router.updateCloud(cloud)
        router.preference = preference
        return router
    }
}
