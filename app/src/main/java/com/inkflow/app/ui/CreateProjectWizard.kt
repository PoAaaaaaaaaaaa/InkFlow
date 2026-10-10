package com.inkflow.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.inkflow.app.InkFlowApp
import com.inkflow.app.data.NovelRepository
import com.inkflow.core.corpus.IntakeQuestionnaire
import com.inkflow.core.corpus.IntakeQuestion
import com.inkflow.core.corpus.IntakeSession
import com.inkflow.core.corpus.QuestionKind
import com.inkflow.core.domain.Project
import kotlinx.coroutines.launch
import java.io.File

/**
 * 新建作品向导（4 步）。
 *
 * 为什么不做成单个对话框：
 * 一篇长篇的「题材 / 视角 / 基调 / 目标读者 / 目标字数」会**直接决定**
 * AI 生成的大纲质量与文风走向。塞进一个表单里用户会一路跳过，
 * 结果是几千字的烂大纲。分步 + 每步解释「这一项影响什么」，
 * 让作者在创建阶段就完成世界观对齐，后面省下大量返工。
 */
private enum class WizardStep(val title: String, val subtitle: String) {
    Basic("基本信息", "作品叫什么，你希望读者一眼看到什么"),
    Genre("题材与受众", "决定 AI 往哪个方向构思情节"),
    Style("写作风格", "决定 AI 用什么语气落笔"),
    Scale("规模与封面", "决定大纲分卷节奏"),
    Intake("AI 提问", "这些问题的答案会直接喂给规划，决定大纲准不准"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProjectWizard(
    repo: NovelRepository,
    app: InkFlowApp,
    onDismiss: () -> Unit,
    onCreated: (Project, Map<String, String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var step by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }

    // ---- 表单状态 ----
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var logline by remember { mutableStateOf("") }
    var premise by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("") }
    var audience by remember { mutableStateOf("通用") }
    var narrativePerson by remember { mutableStateOf("第三人称") }
    var targetWords by remember { mutableStateOf(1_000_000L) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }

    // ---- AI 提问状态（第 5 步）----
    var intakeSession by remember { mutableStateOf<IntakeSession?>(null) }
    var intakeLoading by remember { mutableStateOf(false) }
    var intakeError by remember { mutableStateOf<String?>(null) }

    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) coverUri = uri }

    // 首次进入提问步时生成问题。只生成一次，返回上一步再回来不重复消耗引擎。
    fun steps_get(i: Int) = WizardStep.entries[i.coerceIn(0, WizardStep.entries.lastIndex)]

    LaunchedEffect(step) {
        if (steps_get(step) == WizardStep.Intake && intakeSession == null && !intakeLoading) {
            intakeLoading = true
            intakeError = null
            runCatching {
                val engine = app.router.activeEngine()
                com.inkflow.core.agent.AgentPipeline(engine).generateIntakeQuestions(
                    title = title,
                    genre = genre,
                    logline = logline,
                    premise = premise,
                    targetWords = if (targetWords > 0) targetWords.toString() else "",
                ).output
            }.onSuccess {
                intakeSession = IntakeQuestionnaire.build("", emptyMap(), it)
            }.onFailure {
                intakeError = it.message ?: "未知错误"
            }
            intakeLoading = false
        }
    }

    val steps = WizardStep.entries
    val canNext = when (steps[step]) {
        WizardStep.Basic -> title.isNotBlank()
        WizardStep.Genre -> genre.isNotBlank()
        WizardStep.Style -> true
        WizardStep.Scale -> true
        // 必答题答完才放行；没生成出问题（引擎不可用且本地也无缺口）时直接放行
        WizardStep.Intake -> intakeSession?.readyForBlueprint ?: true
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---------------- 顶部：进度 ----------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (step > 0) step-- else onDismiss()
                }) {
                    Icon(
                        if (step > 0) Icons.Default.Close else Icons.Default.Close,
                        contentDescription = if (step > 0) "上一步" else "取消",
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        steps[step].title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        steps[step].subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${step + 1}/${steps.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { (step + 1f) / steps.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            // ---------------- 主体 ----------------
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                when (steps[step]) {
                    WizardStep.Basic -> BasicStep(
                        title = title, onTitle = { title = it },
                        author = author, onAuthor = { author = it },
                        logline = logline, onLogline = { logline = it },
                        premise = premise, onPremise = { premise = it },
                    )

                    WizardStep.Genre -> GenreStep(
                        genre = genre, onGenre = { genre = it },
                        tone = tone, onTone = { tone = it },
                        audience = audience, onAudience = { audience = it },
                    )

                    WizardStep.Style -> StyleStep(
                        person = narrativePerson, onPerson = { narrativePerson = it },
                        premise = premise, onPremise = { premise = it },
                    )

                    WizardStep.Intake -> IntakeStep(
                        session = intakeSession,
                        loading = intakeLoading,
                        error = intakeError,
                        onAnswer = { field, value ->
                            intakeSession = intakeSession?.answer(field, value)
                        },
                        onRetry = {
                            scope.launch {
                                intakeLoading = true
                                intakeError = null
                                intakeSession = null
                            }
                        },
                    )

                    WizardStep.Scale -> ScaleStep(
                        targetWords = targetWords, onTargetWords = { targetWords = it },
                        coverUri = coverUri,
                        onPickCover = {
                            pickCover.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onClearCover = { coverUri = null },
                    )
                }
            }

            // ---------------- 底部按钮 ----------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (step > 0) {
                    TextButton(
                        onClick = { step-- },
                        modifier = Modifier.weight(1f),
                        enabled = !creating,
                    ) { Text("上一步") }
                }
                Button(
                    onClick = {
                        if (step < steps.lastIndex) {
                            step++
                        } else {
                            creating = true
                            scope.launch {
                                val project = repo.createProject(
                                    title = title,
                                    genre = genre,
                                    logline = logline,
                                    premise = premise,
                                    author = author,
                                    targetWords = targetWords,
                                    narrativePerson = narrativePerson,
                                    tone = tone,
                                    audience = audience,
                                )
                                // 封面在作品创建后写入（需要 projectId 作为文件名）
                                coverUri?.let { uri ->
                                    runCatching { repo.saveCoverFromUri(context, project.id, uri) }
                                }
                                // 自动建第一章，避免进入空白写作台
                                repo.createChapter(project.id, "第1章")
                                creating = false
                                onCreated(project, intakeSession?.answers.orEmpty())
                            }
                        }
                    },
                    modifier = Modifier.weight(if (step > 0) 1f else 2f),
                    enabled = canNext && !creating,
                ) {
                    if (creating) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else if (step == steps.lastIndex) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (step == steps.lastIndex) "创建并开始写作" else "下一步")
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 第 1 步：基本信息
// ----------------------------------------------------------------------

@Composable
private fun BasicStep(
    title: String, onTitle: (String) -> Unit,
    author: String, onAuthor: (String) -> Unit,
    logline: String, onLogline: (String) -> Unit,
    premise: String, onPremise: (String) -> Unit,
) {
    HintCard(
        "这几项是 AI 的**首要输入**。「一句话故事」写得好不好，"
            + "直接决定后面生成的大纲有没有戏。"
    )
    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = title,
        onValueChange = onTitle,
        label = { Text("作品名 *") },
        placeholder = { Text("例：青云剑歌") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = author,
        onValueChange = onAuthor,
        label = { Text("作者署名（可选）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = logline,
        onValueChange = onLogline,
        label = { Text("一句话故事") },
        placeholder = { Text("例：落魄少年捡到半柄断剑，却发现自己是被抹去的剑神转世") },
        minLines = 2,
        supportingText = {
            Text("包含「主角 + 困境 + 转折」，越具体越好", style = MaterialTheme.typography.bodySmall)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = premise,
        onValueChange = onPremise,
        label = { Text("核心设定 / 世界观") },
        placeholder = { Text("例：修真界以剑为尊，剑修分九境。三百年前剑神陨落，其道统被三大宗门联手抹除……") },
        minLines = 5,
        supportingText = {
            Text("世界的规则、力量体系、核心矛盾", style = MaterialTheme.typography.bodySmall)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ----------------------------------------------------------------------
// 第 2 步：题材与受众
// ----------------------------------------------------------------------

private val GENRE_PRESETS = listOf(
    "东方玄幻", "都市异能", "现代言情", "古代言情", "悬疑推理",
    "科幻末世", "历史军事", "武侠仙侠", "游戏竞技", "轻小说",
)

private val TONE_PRESETS = listOf(
    "热血", "爽文", "悬疑", "治愈", "虐心", "搞笑", "黑暗", "温情",
)

@Composable
private fun GenreStep(
    genre: String, onGenre: (String) -> Unit,
    tone: String, onTone: (String) -> Unit,
    audience: String, onAudience: (String) -> Unit,
) {
    HintCard("题材决定 AI 调用哪一套叙事套路（升级流？悬念链？感情线？）。")

    Spacer(Modifier.height(16.dp))
    SectionLabel("题材 *")
    ChipGroup(
        options = GENRE_PRESETS,
        selected = genre,
        onSelect = onGenre,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = genre,
        onValueChange = onGenre,
        label = { Text("自定义题材") },
        placeholder = { Text("也可以直接填写，如「克苏鲁修真」") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(20.dp))
    SectionLabel("基调")
    ChipGroup(options = TONE_PRESETS, selected = tone, onSelect = onTone)

    Spacer(Modifier.height(20.dp))
    SectionLabel("目标读者")
    ChipGroup(
        options = listOf("通用", "男频", "女频", "青年向", "全年龄"),
        selected = audience,
        onSelect = onAudience,
    )
}

// ----------------------------------------------------------------------
// 第 3 步：写作风格
// ----------------------------------------------------------------------

@Composable
private fun StyleStep(
    person: String, onPerson: (String) -> Unit,
    premise: String, onPremise: (String) -> Unit,
) {
    HintCard(
        "视角一旦定下，全书都要一致。AI 会严格按你选的视角落笔，"
            + "避免出现「第一人称写着写着变成第三人称」。"
    )
    Spacer(Modifier.height(16.dp))

    SectionLabel("叙述视角")
    ChipGroup(
        options = listOf("第三人称", "第一人称", "第三人称（多视角）"),
        selected = person,
        onSelect = onPerson,
    )

    Spacer(Modifier.height(20.dp))
    SectionLabel("补充设定（可稍后再写）")
    OutlinedTextField(
        value = premise,
        onValueChange = onPremise,
        label = { Text("还想让 AI 知道的事") },
        placeholder = { Text("例：主角有轻微社恐；本书不写感情线；每章结尾必须留钩子") },
        minLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ----------------------------------------------------------------------
// 第 5 步：AI 提问
// ----------------------------------------------------------------------

/**
 * 提问步。
 *
 * 这一页的价值在于**把作者脑子里的东西逼出来**。长篇写作最常见的失败不是文笔差，
 * 是动笔时根本没想清楚主角要什么、对抗是什么。这些问题把「想清楚」提前到创建阶段，
 * 成本最低，收益最大。
 *
 * 每个问题都带「为什么要问」——没有这一条，作者会一路跳过。
 */
@Composable
private fun IntakeStep(
    session: IntakeSession?,
    loading: Boolean,
    error: String?,
    onAnswer: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    HintCard(
        "下面的问题由你的填写内容推导得出。答完它们，AI 才能生成一份真正属于这本书的大纲——"
            + "否则只能给出一份泛泛的通用框架。"
    )
    Spacer(Modifier.height(14.dp))

    when {
        loading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在分析你的构思…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        error != null -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("提问生成遇到问题", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "不影响继续创建作品，可以直接下一步；之后在写作台里也能重新生成。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }

        session == null || session.questions.isEmpty() -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("没有发现明显缺口", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "你填的信息已经足够生成一份可用的大纲。也可以直接进入下一步，"
                            + "之后在写作台里再补充。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        else -> {
            val required = session.required
            val optional = session.questions.filterNot { it.isRequired }

            if (required.isNotEmpty()) {
                Text(
                    "必答（${required.count { it.field in session.answers }} / ${required.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                required.forEach { q ->
                    QuestionCard(q, session.answers[q.field].orEmpty(), onAnswer)
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (optional.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "选答（关系到细节质量）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                optional.forEach { q ->
                    QuestionCard(q, session.answers[q.field].orEmpty(), onAnswer)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    q: IntakeQuestion,
    answer: String,
    onAnswer: (String, String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(q.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    q.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))

            // 【降级保证】选择题必须真的有选项才渲染选项。
            //
            // 曾经出过一个线上问题：AI 出的选择题在界面上只有题干、下面什么都没有。
            // 根因是选项数组的解析走了「对象数组」通道，字符串数组被判空，
            // 于是 chunked(2) 得到空列表，一个控件都不渲染。
            //
            // 解析层已经修了（JsonLite.stringArrayOf），这里再加一道 UI 防线：
            // 选项为空就退化成文本输入框。用户至少能作答，
            // 而不是面对一道点不动的题——**静默失效比报错更糟**。
            val hasOptions = q.options.isNotEmpty()
            when {
                (q.kind == QuestionKind.Choice || q.kind == QuestionKind.MultiChoice) && hasOptions -> {
                    // 选项多时用换行布局，避免横向溢出
                    q.options.chunked(2).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { opt ->
                                FilterChip(
                                    selected = answer == opt,
                                    onClick = { onAnswer(q.field, opt) },
                                    label = {
                                        Text(opt, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    // 选择题也允许自己填，避免选项覆盖不到的情况
                    OutlinedTextField(
                        value = if (answer in q.options) "" else answer,
                        onValueChange = { if (it.isNotBlank()) onAnswer(q.field, it) },
                        label = { Text("或自己填写", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                q.kind == QuestionKind.LongText || q.kind == QuestionKind.Text || !hasOptions -> {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { onAnswer(q.field, it) },
                        placeholder = {
                            if (q.placeholder.isNotBlank()) {
                                Text(q.placeholder, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        minLines = if (q.kind == QuestionKind.LongText) 3 else 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 第 4 步：规模与封面
// ----------------------------------------------------------------------

@Composable
private fun ScaleStep(
    targetWords: Long, onTargetWords: (Long) -> Unit,
    coverUri: Uri?,
    onPickCover: () -> Unit,
    onClearCover: () -> Unit,
) {
    val context = LocalContext.current

    SectionLabel("目标字数")
    Text(
        formatWords(targetWords.toInt()) + "  ≈  ${targetWords / 3000} 章",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Slider(
        value = targetWords.toFloat(),
        onValueChange = { onTargetWords(it.toLong()) },
        valueRange = 100_000f..5_000_000f,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(300_000L to "短篇", 1_000_000L to "中长篇", 3_000_000L to "长篇").forEach { (n, label) ->
            FilterChip(
                selected = targetWords == n,
                onClick = { onTargetWords(n) },
                label = { Text("$label ${formatWords(n.toInt())}") },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "AI 会按这个规模规划分卷与章节数。之后可以在作品设置里改。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(24.dp))
    SectionLabel("封面（可选）")

    if (coverUri != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(coverUri).crossfade(true).build(),
                contentDescription = "封面预览",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 84.dp, height = 112.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("已选择封面", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "会自动裁剪为 3:4 并压缩保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPickCover) { Text("换一张") }
                    TextButton(onClick = onClearCover) { Text("移除") }
                }
            }
        }
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPickCover),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("从相册选择封面", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "无需存储权限，只复制一份到应用内",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    HintCard("创建后会先自动建好「第 1 章」。进入写作台点「AI 写作」，"
        + "可以一键生成角色卡、伏笔和全书大纲。")
}

// ----------------------------------------------------------------------
// 复用小组件
// ----------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun HintCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text.replace("**", ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** 可单选也可自填的标签组。 */
@Composable
private fun ChipGroup(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    // 用 FlowRow 语义手写两行布局，避免引入 experimental API
    val rows = options.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        label = {
                            Text(
                                option,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                }
            }
        }
    }
}
