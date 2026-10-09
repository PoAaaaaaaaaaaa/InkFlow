package com.inkflow.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.inkflow.app.ai.CloudConfig
import com.inkflow.app.ai.EnginePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "inkflow_settings")

/**
 * 写作偏好与应用设置。
 *
 * API Key 仅存于本机 DataStore（应用私有目录），
 * 不写入日志、不参与任何同步、不进入云构建产物。
 */
class SettingsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val cloudConfig: Flow<CloudConfig> = context.dataStore.data.map { p ->
        CloudConfig(
            baseUrl = p[KEY_CLOUD_BASE] ?: "https://api.openai.com/v1",
            apiKey = p[KEY_CLOUD_KEY] ?: "",
            model = p[KEY_CLOUD_MODEL] ?: "gpt-4o-mini",
            displayName = p[KEY_CLOUD_NAME] ?: "",
            timeoutSeconds = (p[KEY_CLOUD_TIMEOUT] ?: 120).toLong(),
            enabled = p[KEY_CLOUD_ENABLED] ?: false,
        )
    }

    val enginePreference: Flow<EnginePreference> = context.dataStore.data.map { p ->
        EnginePreference(
            order = p[KEY_ENGINE_ORDER]?.split(',')?.filter { it.isNotBlank() }
                ?: listOf("nano", "litert-lm", "cloud", "template"),
            preferCloudForGeneration = p[KEY_PREFER_CLOUD] ?: false,
        )
    }

    /** 写作偏好：目标字数、温度、自动交接笔记等 */
    val writingPrefs: Flow<WritingPrefs> = context.dataStore.data.map { p ->
        WritingPrefs(
            chapterTargetWords = p[KEY_CHAPTER_WORDS] ?: 3000,
            temperature = p[KEY_TEMPERATURE] ?: 0.85f,
            autoQualityCheck = p[KEY_AUTO_QUALITY] ?: true,
            autoHandoff = p[KEY_AUTO_HANDOFF] ?: true,
            autoSaveIntervalMs = (p[KEY_AUTOSAVE] ?: 8000).toLong(),
            qualityGateScore = p[KEY_GATE_SCORE] ?: 70,
            typewriterEffect = p[KEY_TYPEWRITER] ?: true,
        )
    }

    val lastProjectId: Flow<String?> = context.dataStore.data.map { it[KEY_LAST_PROJECT] }

    /** 上次成功使用的模型名，用于自动回填 */
    val lastUsedModel: Flow<String> = context.dataStore.data.map { it[KEY_LAST_MODEL] ?: "" }

    suspend fun setLastUsedModel(model: String) {
        context.dataStore.edit { it[KEY_LAST_MODEL] = model }
    }

    suspend fun saveCloudConfig(config: CloudConfig) {
        context.dataStore.edit { p ->
            p[KEY_CLOUD_BASE] = config.baseUrl
            p[KEY_CLOUD_KEY] = config.apiKey
            p[KEY_CLOUD_MODEL] = config.model
            p[KEY_CLOUD_NAME] = config.displayName
            p[KEY_CLOUD_TIMEOUT] = config.timeoutSeconds.toInt()
            p[KEY_CLOUD_ENABLED] = config.enabled
        }
    }

    suspend fun saveEnginePreference(pref: EnginePreference) {
        context.dataStore.edit { p ->
            p[KEY_ENGINE_ORDER] = pref.order.joinToString(",")
            p[KEY_PREFER_CLOUD] = pref.preferCloudForGeneration
        }
    }

    suspend fun saveWritingPrefs(prefs: WritingPrefs) {
        context.dataStore.edit { p ->
            p[KEY_CHAPTER_WORDS] = prefs.chapterTargetWords
            p[KEY_TEMPERATURE] = prefs.temperature
            p[KEY_AUTO_QUALITY] = prefs.autoQualityCheck
            p[KEY_AUTO_HANDOFF] = prefs.autoHandoff
            p[KEY_AUTOSAVE] = prefs.autoSaveIntervalMs.toInt()
            p[KEY_GATE_SCORE] = prefs.qualityGateScore
            p[KEY_TYPEWRITER] = prefs.typewriterEffect
        }
    }

    suspend fun setLastProject(id: String) {
        context.dataStore.edit { it[KEY_LAST_PROJECT] = id }
    }

    /** 序列化后的文风 DNA 快照 */
    suspend fun saveStyleDnaCache(projectId: String, json: String) {
        context.dataStore.edit { it[styleKey(projectId)] = json }
    }

    suspend fun loadStyleDnaCache(projectId: String): String? {
        var value: String? = null
        context.dataStore.edit { p -> value = p[styleKey(projectId)] }
        return value
    }

    private fun styleKey(projectId: String) = stringPreferencesKey("style_dna_$projectId")

    private companion object {
        val KEY_CLOUD_BASE = stringPreferencesKey("cloud_base_url")
        val KEY_CLOUD_KEY = stringPreferencesKey("cloud_api_key")
        val KEY_CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val KEY_CLOUD_NAME = stringPreferencesKey("cloud_display_name")
        val KEY_CLOUD_TIMEOUT = intPreferencesKey("cloud_timeout")
        val KEY_CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")

        val KEY_ENGINE_ORDER = stringPreferencesKey("engine_order")
        val KEY_PREFER_CLOUD = booleanPreferencesKey("prefer_cloud")

        val KEY_CHAPTER_WORDS = intPreferencesKey("chapter_target_words")
        val KEY_TEMPERATURE = floatPreferencesKey("temperature")
        val KEY_AUTO_QUALITY = booleanPreferencesKey("auto_quality")
        val KEY_AUTO_HANDOFF = booleanPreferencesKey("auto_handoff")
        val KEY_AUTOSAVE = intPreferencesKey("autosave_interval")
        val KEY_GATE_SCORE = intPreferencesKey("gate_score")
        val KEY_TYPEWRITER = booleanPreferencesKey("typewriter")

        val KEY_LAST_PROJECT = stringPreferencesKey("last_project")
        val KEY_LAST_MODEL = stringPreferencesKey("last_used_model")
    }
}

data class WritingPrefs(
    val chapterTargetWords: Int = 3000,
    val temperature: Float = 0.85f,
    val autoQualityCheck: Boolean = true,
    val autoHandoff: Boolean = true,
    val autoSaveIntervalMs: Long = 8000,
    val qualityGateScore: Int = 70,
    val typewriterEffect: Boolean = true,
)
