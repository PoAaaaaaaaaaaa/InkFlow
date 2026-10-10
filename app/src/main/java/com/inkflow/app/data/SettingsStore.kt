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

/**
 * 全部偏好项的 preference key。
 *
 * 【为什么提到顶层】
 * 它们原先挂在 `SettingsStore` 的私有 companion 里，只服务一个类时没问题。
 * 但 [WritingPrefs] 的读写映射需要同一批 key，而它是顶层类，
 * 够不到私有 companion —— 于是「读写映射集中一处」这个重构就做不成，
 * 只能退回两边各写一份，也就是当初漏接线的那个结构。
 *
 * 提取到顶层后：key 只有一份，读写在同一个类里，编译器帮忙盯着。
 */
internal object PrefKeys {
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
    val KEY_DEPTH_LEVEL = intPreferencesKey("depth_level")
    val KEY_DEPTH_ANALOGIES = booleanPreferencesKey("depth_analogies")
    val KEY_DEPTH_AMBIGUITY = booleanPreferencesKey("depth_ambiguity")
    val KEY_VOICE_PROFILE = stringPreferencesKey("voice_profile")
    val KEY_LAST_PROJECT = stringPreferencesKey("last_project")
    val KEY_LAST_MODEL = stringPreferencesKey("last_used_model")
    // 阅读/写作排版
    val KEY_READER_FONT_SIZE = floatPreferencesKey("reader_font_size")
    val KEY_READER_LINE_HEIGHT = floatPreferencesKey("reader_line_height")
    val KEY_READER_FONT = stringPreferencesKey("reader_font")
    val KEY_READER_PARA_SPACING = floatPreferencesKey("reader_para_spacing")
    val KEY_READER_PADDING = floatPreferencesKey("reader_padding")
    val KEY_READER_SCHEME = stringPreferencesKey("reader_scheme")
    val KEY_READER_INDENT = booleanPreferencesKey("reader_indent")
    val KEY_READER_KEEP_ON = booleanPreferencesKey("reader_keep_on")
}

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
            baseUrl = p[PrefKeys.KEY_CLOUD_BASE] ?: "https://api.openai.com/v1",
            apiKey = p[PrefKeys.KEY_CLOUD_KEY] ?: "",
            model = p[PrefKeys.KEY_CLOUD_MODEL] ?: "gpt-4o-mini",
            displayName = p[PrefKeys.KEY_CLOUD_NAME] ?: "",
            timeoutSeconds = (p[PrefKeys.KEY_CLOUD_TIMEOUT] ?: 120).toLong(),
            enabled = p[PrefKeys.KEY_CLOUD_ENABLED] ?: false,
        )
    }

    val enginePreference: Flow<EnginePreference> = context.dataStore.data.map { p ->
        EnginePreference(
            order = p[PrefKeys.KEY_ENGINE_ORDER]?.split(',')?.filter { it.isNotBlank() }
                ?: listOf("nano", "litert-lm", "cloud", "template"),
            preferCloudForGeneration = p[PrefKeys.KEY_PREFER_CLOUD] ?: false,
        )
    }

    /**
     * 写作偏好。
     *
     * 读取逻辑集中在 [WritingPrefs.from] 一处，写入集中在 [WritingPrefs.writeTo]。
     * 这样新增字段时，编译器会强制你同时改读写两侧——
     * 而不再是「定义了 key、加了字段、忘了接线」然后静默丢数据。
     */
    val writingPrefs: Flow<WritingPrefs> =
        context.dataStore.data.map { p -> WritingPrefs.from(p) }

    val lastProjectId: Flow<String?> = context.dataStore.data.map { it[PrefKeys.KEY_LAST_PROJECT] }

    /** 阅读/写作区的排版偏好。 */
    val readerPrefs: Flow<ReaderPrefs> = context.dataStore.data.map { p ->
        ReaderPrefs(
            fontSizeSp = p[PrefKeys.KEY_READER_FONT_SIZE] ?: 17f,
            lineHeightMultiplier = p[PrefKeys.KEY_READER_LINE_HEIGHT] ?: 1.85f,
            fontChoice = ReaderFont.entries.firstOrNull { it.name == p[PrefKeys.KEY_READER_FONT] } ?: ReaderFont.Serif,
            paragraphSpacingDp = p[PrefKeys.KEY_READER_PARA_SPACING] ?: 10f,
            horizontalPaddingDp = p[PrefKeys.KEY_READER_PADDING] ?: 20f,
            colorScheme = ReaderScheme.entries.firstOrNull { it.name == p[PrefKeys.KEY_READER_SCHEME] } ?: ReaderScheme.FollowSystem,
            indentFirstLine = p[PrefKeys.KEY_READER_INDENT] ?: false,
            keepScreenOn = p[PrefKeys.KEY_READER_KEEP_ON] ?: false,
        )
    }

    suspend fun saveReaderPrefs(prefs: ReaderPrefs) {
        context.dataStore.edit { p ->
            p[PrefKeys.KEY_READER_FONT_SIZE] = prefs.fontSizeSp
            p[PrefKeys.KEY_READER_LINE_HEIGHT] = prefs.lineHeightMultiplier
            p[PrefKeys.KEY_READER_FONT] = prefs.fontChoice.name
            p[PrefKeys.KEY_READER_PARA_SPACING] = prefs.paragraphSpacingDp
            p[PrefKeys.KEY_READER_PADDING] = prefs.horizontalPaddingDp
            p[PrefKeys.KEY_READER_SCHEME] = prefs.colorScheme.name
            p[PrefKeys.KEY_READER_INDENT] = prefs.indentFirstLine
            p[PrefKeys.KEY_READER_KEEP_ON] = prefs.keepScreenOn
        }
    }

    /** 上次成功使用的模型名，用于自动回填 */
    val lastUsedModel: Flow<String> = context.dataStore.data.map { it[PrefKeys.KEY_LAST_MODEL] ?: "" }

    suspend fun setLastUsedModel(model: String) {
        context.dataStore.edit { it[PrefKeys.KEY_LAST_MODEL] = model }
    }

    suspend fun saveCloudConfig(config: CloudConfig) {
        context.dataStore.edit { p ->
            p[PrefKeys.KEY_CLOUD_BASE] = config.baseUrl
            p[PrefKeys.KEY_CLOUD_KEY] = config.apiKey
            p[PrefKeys.KEY_CLOUD_MODEL] = config.model
            p[PrefKeys.KEY_CLOUD_NAME] = config.displayName
            p[PrefKeys.KEY_CLOUD_TIMEOUT] = config.timeoutSeconds.toInt()
            p[PrefKeys.KEY_CLOUD_ENABLED] = config.enabled
        }
    }

    suspend fun saveEnginePreference(pref: EnginePreference) {
        context.dataStore.edit { p ->
            p[PrefKeys.KEY_ENGINE_ORDER] = pref.order.joinToString(",")
            p[PrefKeys.KEY_PREFER_CLOUD] = pref.preferCloudForGeneration
        }
    }

    /** 整体覆盖写作偏好。 */
    suspend fun saveWritingPrefs(prefs: WritingPrefs) {
        context.dataStore.edit { p -> WritingPrefs.writeTo(prefs, p) }
    }

    /**
     * 局部更新写作偏好。
     *
     * 【为什么需要它】整体覆盖的写法必须先 `.first()` 读一次再写回，
     * 在这个窗口里若另一处也触发了保存，先写的那次就被后写覆盖——
     * 典型表现是「快速拖动两个滑块，只有一个生效」。
     * DataStore 的 `edit` 是串行化的原子操作，在回调里基于**最新值**改一个字段，
     * 从根上消除这类竞争。
     */
    suspend fun updateWritingPrefs(transform: (WritingPrefs) -> WritingPrefs) {
        context.dataStore.edit { p ->
            WritingPrefs.writeTo(transform(WritingPrefs.from(p)), p)
        }
    }

    suspend fun setLastProject(id: String) {
        context.dataStore.edit { it[PrefKeys.KEY_LAST_PROJECT] = id }
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

}

/**
 * 阅读/写作排版偏好。
 *
 * 为什么做成可调：写作与阅读对排版的需求是矛盾的 ——
 * 码字时希望一屏看到更多信息（小字号、紧行距），
 * 通读检查时希望眼睛轻松（大字号、松行距）。
 * 固定一套排版必然得罪其中一种场景。
 */
data class ReaderPrefs(
    /** 正文字号（sp） */
    val fontSizeSp: Float = 17f,
    /** 行高倍数，相对字号 */
    val lineHeightMultiplier: Float = 1.85f,
    val fontChoice: ReaderFont = ReaderFont.Serif,
    /** 段间距（dp） */
    val paragraphSpacingDp: Float = 10f,
    /** 左右留白（dp） */
    val horizontalPaddingDp: Float = 20f,
    /** 配色方案 */
    val colorScheme: ReaderScheme = ReaderScheme.FollowSystem,
    /** 段首缩进两字符 */
    val indentFirstLine: Boolean = false,
    /** 写作时保持屏幕常亮 */
    val keepScreenOn: Boolean = false,
) {
    val lineHeightSp: Float get() = fontSizeSp * lineHeightMultiplier
}

enum class ReaderFont(val label: String) {
    Serif("宋体/衬线"),
    Sans("黑体/无衬线"),
    Monospace("等宽"),
}

/**
 * 阅读配色。长时间阅读对底色很敏感：
 * 暖黄（护眼）适合夜间，纯白适合白天强光，深色适合暗环境。
 */
enum class ReaderScheme(val label: String) {
    FollowSystem("跟随系统"),
    Paper("纸白"),
    Warm("暖黄护眼"),
    Sepia("米褐"),
    Night("夜间深色"),
}

/**
 * 写作偏好的**唯一读写映射**。
 *
 * 【为什么读写放在同一个类里】
 * v1.4.0 发布后收到反馈「写作深度设置点击无效果」。根因是深度四个字段
 * 只加了数据类字段和 preference key，却漏了读写映射——值被静默丢弃，
 * 滑块拖完立刻弹回原位。
 *
 * 这类 bug 的成因是读写两侧分散在两处，加字段时容易只改一边。
 * 现在把 `from` 与 `writeTo` 摆在同一个类的正下方，字段、读取、写入三件事
 * 物理相邻，漏接线的概率被压到最低。
 */
data class WritingPrefs(
    val chapterTargetWords: Int = 3000,
    val temperature: Float = 0.85f,
    val autoQualityCheck: Boolean = true,
    val autoHandoff: Boolean = true,
    val autoSaveIntervalMs: Long = 8000,
    val qualityGateScore: Int = 70,
    val typewriterEffect: Boolean = true,

    /**
     * 写作深度 5..95。默认 55 = 平衡档。
     *
     * 存数值而不是档位名：作者可以把它拖到两档之间，
     * 这正是「比通俗再厚一点」这类需求的表达方式。
     */
    val depthLevel: Int = 55,
    /** 是否启用类比讲解（通俗向优先） */
    val depthAnalogies: Boolean = true,
    /** 是否允许留白（深度向优先） */
    val depthAmbiguity: Boolean = false,
    /** 参照的声纹来源作品名，空表示按深度自动匹配 */
    val voiceProfileName: String = "",
) : com.inkflow.core.corpus.PreferenceMapping {

    /**
     * 字段清单，与 [from] / [writeTo] 三处必须同步。
     *
     * 这份清单是给测试用的：测试会逐个字段改值走一遍写→读→比对，
     * 任何漏接线的字段都会在 core 的单元测试里立刻红掉——
     * 而不是等到用户反馈「点了没反应」。
     */
    override val fieldNames: List<String> = listOf(
        "chapterTargetWords", "temperature", "autoQualityCheck", "autoHandoff",
        "autoSaveIntervalMs", "qualityGateScore", "typewriterEffect",
        com.inkflow.core.corpus.DepthSettingFields.LEVEL,
        com.inkflow.core.corpus.DepthSettingFields.ANALOGIES,
        com.inkflow.core.corpus.DepthSettingFields.AMBIGUITY,
        com.inkflow.core.corpus.DepthSettingFields.VOICE,
    )

    companion object {
        /** 从 DataStore 读取。字段缺失时用默认值，不抛异常。 */
        fun from(p: Preferences): WritingPrefs = WritingPrefs(
            chapterTargetWords = p[PrefKeys.KEY_CHAPTER_WORDS] ?: 3000,
            temperature = p[PrefKeys.KEY_TEMPERATURE] ?: 0.85f,
            autoQualityCheck = p[PrefKeys.KEY_AUTO_QUALITY] ?: true,
            autoHandoff = p[PrefKeys.KEY_AUTO_HANDOFF] ?: true,
            autoSaveIntervalMs = (p[PrefKeys.KEY_AUTOSAVE] ?: 8000).toLong(),
            qualityGateScore = p[PrefKeys.KEY_GATE_SCORE] ?: 70,
            typewriterEffect = p[PrefKeys.KEY_TYPEWRITER] ?: true,
            depthLevel = p[PrefKeys.KEY_DEPTH_LEVEL] ?: 55,
            depthAnalogies = p[PrefKeys.KEY_DEPTH_ANALOGIES] ?: true,
            depthAmbiguity = p[PrefKeys.KEY_DEPTH_AMBIGUITY] ?: false,
            voiceProfileName = p[PrefKeys.KEY_VOICE_PROFILE] ?: "",
        )

        /** 写入 DataStore。新增字段时必须同步加在这里，否则读回来就是默认值。 */
        fun writeTo(prefs: WritingPrefs, p: androidx.datastore.preferences.core.MutablePreferences) {
            p[PrefKeys.KEY_CHAPTER_WORDS] = prefs.chapterTargetWords
            p[PrefKeys.KEY_TEMPERATURE] = prefs.temperature
            p[PrefKeys.KEY_AUTO_QUALITY] = prefs.autoQualityCheck
            p[PrefKeys.KEY_AUTO_HANDOFF] = prefs.autoHandoff
            p[PrefKeys.KEY_AUTOSAVE] = prefs.autoSaveIntervalMs.toInt()
            p[PrefKeys.KEY_GATE_SCORE] = prefs.qualityGateScore
            p[PrefKeys.KEY_TYPEWRITER] = prefs.typewriterEffect
            p[PrefKeys.KEY_DEPTH_LEVEL] = prefs.depthLevel
            p[PrefKeys.KEY_DEPTH_ANALOGIES] = prefs.depthAnalogies
            p[PrefKeys.KEY_DEPTH_AMBIGUITY] = prefs.depthAmbiguity
            p[PrefKeys.KEY_VOICE_PROFILE] = prefs.voiceProfileName
        }
    }
}
