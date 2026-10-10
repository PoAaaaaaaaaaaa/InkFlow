package com.inkflow.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.inkflow.app.InkFlowApp
import androidx.compose.foundation.clickable
import com.inkflow.core.corpus.CorpusEngine
import com.inkflow.core.corpus.DepthProfile
import com.inkflow.core.corpus.DepthTier
import com.inkflow.core.corpus.VoiceLibrary
import com.inkflow.app.ai.CloudConfig
import com.inkflow.app.ai.EnginePreference
import com.inkflow.app.data.WritingPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设置页：AI 引擎、隐私、写作偏好。
 *
 * 隐私原则在界面上如实呈现：每个引擎都标注「是否离线」，
 * 云端未开启时绝不上传任何文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: InkFlowApp,
    onBack: () -> Unit,
    onOpenMemory: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val cloud by app.settingsStore.cloudConfig.collectAsState(initial = CloudConfig())
    val pref by app.settingsStore.enginePreference.collectAsState(initial = EnginePreference())
    val writing by app.settingsStore.writingPrefs.collectAsState(initial = WritingPrefs())

    var cloudDraft by remember { mutableStateOf(cloud) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var diagnoseResult by remember { mutableStateOf<com.inkflow.app.ai.DiagnosticResult?>(null) }
    var status by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var testing by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }

    LaunchedEffect(cloud) { cloudDraft = cloud }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------------- 记忆层入口 ----------------
            if (onOpenMemory != null) {
                SectionCard("记忆层") {
                    Text(
                        "查看 AI 到底记住了什么：索引了多少条记忆、有哪些角色与伏笔、"
                            + "以及下一章生成时实际会注入哪段上下文。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenMemory, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("打开记忆层")
                    }
                }
            }

            // ---------------- 引擎状态 ----------------
            SectionCard("端侧 AI 引擎") {
                Text(
                    "墨流默认离线优先：能在这台手机上跑的，绝不把稿子发到网上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                EngineRow(
                    icon = Icons.Default.PhoneAndroid,
                    title = "Gemini Nano（系统 AICore）",
                    subtitle = "完全离线 · 数据不出设备",
                    available = status["nano"],
                )
                EngineRow(
                    icon = Icons.Default.Memory,
                    title = "LiteRT-LM 本地模型",
                    subtitle = "需在 files/models 放入 .litertlm 模型文件",
                    available = status["litert-lm"],
                )
                EngineRow(
                    icon = Icons.Default.Cloud,
                    title = "云端模型（OpenAI 兼容）",
                    subtitle = if (cloud.isUsable) "已启用 · 文本将发送到你配置的服务" else "未启用",
                    available = status["cloud"],
                )
                EngineRow(
                    icon = Icons.Default.CheckCircle,
                    title = "离线写作助手",
                    subtitle = "无模型也可用 · 生成写作骨架",
                    available = true,
                )

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            probing = true
                            status = app.router.probeAll()
                            probing = false
                            snackbar.showSnackbar("引擎探测完成")
                        }
                    },
                    enabled = !probing,
                ) {
                    if (probing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("重新探测引擎")
                }
            }

            // ---------------- 云端配置 ----------------
            SectionCard("云端模型配置（可选）") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "开启后，章节文本与设定会发送到你所填写的服务地址。API Key 仅保存在本机，不会同步或上传。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用云端模型", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = cloudDraft.enabled,
                        onCheckedChange = { cloudDraft = cloudDraft.copy(enabled = it) },
                    )
                }

                // ---- 常见服务快速预设 ----
                Spacer(Modifier.height(6.dp))
                Text("快速填充", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val presets = listOf(
                    Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
                    Triple("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
                    Triple("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
                    Triple("Ollama(本机)", "http://127.0.0.1:11434/v1", "qwen2.5:7b"),
                )
                presets.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                        row.forEach { (name, url, model) ->
                            FilterChip(
                                selected = cloudDraft.baseUrl == url,
                                onClick = {
                                    cloudDraft = cloudDraft.copy(
                                        baseUrl = url,
                                        model = cloudDraft.model.ifBlank { model },
                                        displayName = name,
                                    )
                                },
                                label = { Text(name, style = MaterialTheme.typography.bodySmall) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = cloudDraft.baseUrl,
                    onValueChange = { cloudDraft = cloudDraft.copy(baseUrl = it) },
                    label = { Text("接口地址") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            if (cloudDraft.isCleartext())
                                "⚠ 当前使用明文 HTTP 传输，仅建议用于本机或可信内网"
                            else "留到 /v1 即可，会自动补全路径",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (cloudDraft.isCleartext()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cloudDraft.apiKey,
                    onValueChange = { cloudDraft = cloudDraft.copy(apiKey = it) },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-...（本地 Ollama 可留空）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                // ---- 模型：自动获取 + 手动输入 ----
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模型", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (models.isNotEmpty()) {
                        Text(
                            "已获取 ${models.size} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                fetchingModels = true
                                modelFetchError = null
                                app.settingsStore.saveCloudConfig(cloudDraft)
                                app.reloadRouter()
                                kotlinx.coroutines.delay(250)
                                val engine = com.inkflow.app.ai.CloudEngine(cloudDraft)
                                val result = engine.fetchModels()
                                fetchingModels = false
                                result.fold(
                                    onSuccess = { list ->
                                        models = list
                                        // 只有一个模型时直接选中，省一步操作
                                        if (list.size == 1) cloudDraft = cloudDraft.copy(model = list.first())
                                        snackbar.showSnackbar("已获取 ${list.size} 个可用模型")
                                    },
                                    onFailure = { e ->
                                        models = emptyList()
                                        modelFetchError = e.message ?: "获取失败"
                                        snackbar.showSnackbar("获取模型失败：${e.message?.take(60)}")
                                    },
                                )
                            }
                        },
                        enabled = !fetchingModels && cloudDraft.baseUrl.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (fetchingModels) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (models.isEmpty()) "自动获取模型" else "重新获取")
                    }
                    OutlinedButton(
                        onClick = { showModelPicker = true },
                        enabled = models.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("从列表选择")
                    }
                }

                if (modelFetchError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        modelFetchError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cloudDraft.model,
                    onValueChange = { cloudDraft = cloudDraft.copy(model = it) },
                    label = { Text("模型名") },
                    placeholder = { Text("gpt-4o-mini / deepseek-chat / qwen-plus") },
                    singleLine = true,
                    supportingText = {
                        Text("服务不支持列举模型时，可直接手填", style = MaterialTheme.typography.bodySmall)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                app.settingsStore.saveCloudConfig(cloudDraft)
                                app.settingsStore.setLastUsedModel(cloudDraft.model)
                                app.reloadRouter()
                                snackbar.showSnackbar("已保存云端配置")
                            }
                        },
                    ) { Text("保存") }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                testing = true
                                diagnoseResult = null
                                app.settingsStore.saveCloudConfig(cloudDraft)
                                app.reloadRouter()
                                kotlinx.coroutines.delay(250)
                                val engine = com.inkflow.app.ai.CloudEngine(cloudDraft)
                                diagnoseResult = engine.diagnose()
                                testing = false
                            }
                        },
                        enabled = !testing && cloudDraft.baseUrl.isNotBlank(),
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("测试连接")
                    }
                }

                // ---- 诊断结果：分步告知哪一步失败 ----
                diagnoseResult?.let { d ->
                    Spacer(Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (d.allOk) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (d.allOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (d.allOk) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    d.summary(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "① 列举模型：" + if (d.modelsOk) "成功（${d.models.size} 个）" else "失败 — ${d.modelsError.orEmpty().take(100)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "② 实际对话：" + if (d.chatOk) "成功" else "失败 — ${d.chatError.orEmpty().take(100)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (d.modelsOk && d.models.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "可用模型：" + d.models.take(8).joinToString("、") +
                                        if (d.models.size > 8) " 等 ${d.models.size} 个" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ---------------- 引擎优先级 ----------------
            SectionCard("引擎优先级") {
                Text(
                    "靠前的引擎优先使用，失败会自动降级到下一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("长篇生成优先用云端（质量优先）", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = pref.preferCloudForGeneration,
                        onCheckedChange = { checked ->
                            scope.launch {
                                app.settingsStore.saveEnginePreference(
                                    pref.copy(preferCloudForGeneration = checked)
                                )
                                app.reloadRouter()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前顺序：" + pref.effectiveOrder().joinToString(" → ") {
                        when (it) {
                            "nano" -> "Nano"
                            "litert-lm" -> "本地模型"
                            "cloud" -> "云端"
                            else -> "离线助手"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---------------- 写作偏好 ----------------
            SectionCard("写作偏好") {
                Text("单章目标字数：${writing.chapterTargetWords}", style = MaterialTheme.typography.bodyMedium)
                PrefSlider(
                    storedValue = writing.chapterTargetWords.toFloat(),
                    valueRange = 800f..8000f,
                    steps = 17,
                    onCommit = { v ->
                        scope.launch {
                            app.settingsStore.updateWritingPrefs { it.copy(chapterTargetWords = v.toInt()) }
                        }
                    },
                )

                Text(
                    "生成温度：${String.format("%.2f", writing.temperature)}（越高越发散）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PrefSlider(
                    storedValue = writing.temperature,
                    valueRange = 0.1f..1.4f,
                    onCommit = { v ->
                        scope.launch { app.settingsStore.updateWritingPrefs { it.copy(temperature = v) } }
                    },
                )

                Text(
                    "质量门禁线：${writing.qualityGateScore} 分（低于此分不允许定稿）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PrefSlider(
                    storedValue = writing.qualityGateScore.toFloat(),
                    valueRange = 50f..95f,
                    steps = 8,
                    onCommit = { v ->
                        scope.launch {
                            app.settingsStore.updateWritingPrefs { it.copy(qualityGateScore = v.toInt()) }
                        }
                    },
                )

                ToggleRow("保存时自动质检", writing.autoQualityCheck) { v ->
                    scope.launch { app.settingsStore.updateWritingPrefs { it.copy(autoQualityCheck = v) } }
                }
                ToggleRow("章节完成后自动生成交接笔记", writing.autoHandoff) { v ->
                    scope.launch { app.settingsStore.updateWritingPrefs { it.copy(autoHandoff = v) } }
                }
            }

            // ---------------- 写作深度 ----------------
            SectionCard("写作深度") {
                val depth = DepthProfile(
                    level = writing.depthLevel,
                    analogies = writing.depthAnalogies,
                    ambiguity = writing.depthAmbiguity,
                )
                Text(
                    "深度不是一个形容词，是一组可以执行的参数。拖动滑块会同时改变" +
                        "目标句长、段落长度、比喻密度、心理描写占比——生成时逐条注入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        depth.tier.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${depth.level}/100",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(depth.tier.blurb, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))

                PrefSlider(
                    storedValue = writing.depthLevel.toFloat(),
                    valueRange = 5f..95f,
                    onCommit = { v ->
                        scope.launch {
                            app.settingsStore.updateWritingPrefs { it.copy(depthLevel = v.toInt()) }
                        }
                    },
                )

                // 具名档快捷跳转：数值滑块给精细控制，档位按钮给「我知道自己要什么」
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DepthTier.entries.take(3).forEach { tier ->
                        FilterChip(
                            selected = depth.tier == tier,
                            onClick = {
                                scope.launch {
                                    app.settingsStore.updateWritingPrefs { it.copy(depthLevel = tier.level) }
                                }
                            },
                            label = { Text(tier.label, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DepthTier.entries.drop(3).forEach { tier ->
                        FilterChip(
                            selected = depth.tier == tier,
                            onClick = {
                                scope.launch {
                                    app.settingsStore.updateWritingPrefs { it.copy(depthLevel = tier.level) }
                                }
                            },
                            label = { Text(tier.label, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                Text("执行标准（会逐条注入提示词）", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                AboutRow("目标句长", "平均 ${depth.sentenceLengthTarget} 字")
                AboutRow("目标段落", "平均 ${depth.paragraphLengthTarget} 字")
                AboutRow("比喻密度", "每千字 ≤ ${String.format("%.1f", depth.metaphorPer1kMax)} 次")
                AboutRow("心理描写", "约 ${(depth.introspectionRatio * 100).toInt()}%")
                AboutRow("专业术语", "每千字 ≤ ${String.format("%.1f", depth.jargonPer1kMax)} 个")
                Spacer(Modifier.height(10.dp))

                ToggleRow("类比讲解（通俗向）", writing.depthAnalogies) { v ->
                    scope.launch { app.settingsStore.updateWritingPrefs { it.copy(depthAnalogies = v) } }
                }
                Text(
                    "开启后，出现设定或专业概念时 AI 会当场用生活化的方式解释一遍，" +
                        "让没有背景知识的读者也能跟上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                ToggleRow("允许留白（深度向）", writing.depthAmbiguity) { v ->
                    scope.launch { app.settingsStore.updateWritingPrefs { it.copy(depthAmbiguity = v) } }
                }
                Text(
                    "开启后 AI 可以不把话说完：动机不写明、结局不给答案。对追求即时满足的读者是伤害，默认关闭。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------- 声纹参照 ----------------
            SectionCard("声纹参照") {
                Text(
                    "声纹是「同类型作品怎么写」的可测量特征：句子呼吸长度、对话占比、标点习惯、用词层级。" +
                        "它不是原文——五本长篇合计上千万字，既装不进上下文，就算装进去也学不到「该怎么写这一章」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                val profiles = VoiceLibrary.profiles
                FilterChip(
                    selected = writing.voiceProfileName.isBlank(),
                    onClick = {
                        scope.launch { app.settingsStore.updateWritingPrefs { it.copy(voiceProfileName = "") } }
                    },
                    label = { Text("自动匹配", style = MaterialTheme.typography.bodySmall) },
                )
                Spacer(Modifier.height(6.dp))

                profiles.forEach { v ->
                    val selected = writing.voiceProfileName == v.name
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable {
                                scope.launch {
                                    app.settingsStore.updateWritingPrefs {
                                        it.copy(voiceProfileName = if (selected) "" else v.name)
                                    }
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "《${v.name}》",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                            Text(
                                "${v.author} · ${v.genre}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "句长 ${String.format("%.1f", v.avgSentenceLength)} 字 · " +
                                    "短句 ${(v.shortRatio * 100).toInt()}% · " +
                                    "对话 ${(v.dialogueRatio * 100).toInt()}% · " +
                                    "口语 ${v.colloquialism.toInt()}/100",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                v.reliabilityLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (v.isReliable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary,
                            )
                            if (v.source.isNotBlank()) {
                                Text(
                                    v.source,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (writing.voiceProfileName.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已选中「${writing.voiceProfileName}」。生成时会额外注入它的节奏特征，" +
                            "并与当前深度档做一致性校准——两者冲突时以深度为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ---------------- 语料库自检 ----------------
            SectionCard("AI 语料库") {
                val corpus = CorpusEngine.stats()
                Text(
                    "生成时注入的三层参照：先给可以写什么（具体化选词），再给别写什么（AI 腔禁忌），"
                        + "最后给同类作品的留人机制（拆书）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                AboutRow("AI 腔词条", "${corpus.slopEntries} 条")
                AboutRow("句式模板", "${corpus.slopPatterns} 条")
                AboutRow("具体化选词", "${corpus.vaultEntries} 组 / ${corpus.vaultConcreteCount} 条")
                AboutRow("拆书作品", "${corpus.booksVerified} / ${corpus.booksTotal} 本已验证")
                Spacer(Modifier.height(6.dp))
                Text(
                    "未核实的书目不会给出拆解结论——宁可少给，不给假的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------- 关于 ----------------
            SectionCard("关于墨流") {
                AboutRow("架构", "MVI + Kotlin Multiplatform 就绪的 core 层")
                AboutRow("UI", "Jetpack Compose（BOM 2026.09 / Compose 1.12）")
                AboutRow("检索", "端侧向量索引，零 native 依赖，离线可用的本地 RAG")
                AboutRow("写作引擎", "多智能体流水线：规划 → 生成 → 一致性 → 审阅")
                AboutRow("数据", "全部本地存储，支持章节历史版本回滚")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "墨流不会自动上传你的稿件。所有联网行为都必须由你在上方显式开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // 模型列表选择弹窗
    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("选择模型（${models.size} 个）") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(models) { m ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    cloudDraft = cloudDraft.copy(model = m)
                                    showModelPicker = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = cloudDraft.model == m,
                                onClick = {
                                    cloudDraft = cloudDraft.copy(model = m)
                                    showModelPicker = false
                                },
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(m, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelPicker = false }) { Text("关闭") }
            },
        )
    }
}

/**
 * 偏好滑块。
 *
 * 【为什么不能直接把 value 绑到 DataStore 的值上】
 * 这是「写作深度设置点击无效果」的真正手感来源：
 * 每次 onValueChange 都发起一次异步写，而显示值要等 DataStore 回传才更新。
 * 手指还在拖，UI 用的是上一帧的旧值——滑块被反复弹回，看起来就是「拖不动」。
 *
 * 正确做法是把滑块和持久化解耦：
 *  - 拖动期间只用本地 draft，UI 立刻跟手；
 *  - 松手（onValueChangeFinished）才落盘一次。
 *
 * 副作用是写入次数从「每帧一次」降到「一次拖动一次」，对 DataStore 也友好得多。
 */
@Composable
private fun PrefSlider(
    storedValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onCommit: (Float) -> Unit,
) {
    // 用 storedValue 作 key：外部值变化（例如档位按钮跳转）时同步本地状态；
    // 拖动过程中外部值不变，所以不会打断拖动。
    var draft by remember(storedValue) { mutableStateOf(storedValue) }
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onCommit(draft) },
        valueRange = valueRange,
        steps = steps,
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun EngineRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    available: Boolean?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when (available) {
                true -> "可用"
                false -> "不可用"
                null -> "未探测"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (available) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.onSurfaceVariant
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
