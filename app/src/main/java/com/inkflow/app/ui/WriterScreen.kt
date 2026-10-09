package com.inkflow.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkflow.app.data.NovelRepository
import com.inkflow.app.ui.mvi.TextBlock
import com.inkflow.app.ui.mvi.WriterEffect
import com.inkflow.app.ui.mvi.WriterIntent
import com.inkflow.app.ui.mvi.WriterViewModel
import com.inkflow.app.ui.theme.BodySerif
import com.inkflow.core.domain.ChapterStatus
import kotlinx.coroutines.launch

/**
 * 写作台：全应用的核心界面。
 *
 * 布局（对应需求中的双栏/网格命名区域）：
 *  - 顶部：章节标题、字数、质量分、引擎状态
 *  - 主体：正文流（LazyColumn + 增量文本块，10 万字章节只渲染可见行）
 *  - 底栏：AI 操作（生成/续写/润色/校对/审阅/一致性）
 *  - 抽屉：章节目录 + 大纲/伏笔面板
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriterScreen(
    viewModel: WriterViewModel,
    projectId: String,
    chapterId: String?,
    repo: NovelRepository,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var showAiPanel by remember { mutableStateOf(false) }
    var showChapterList by remember { mutableStateOf(false) }
    var showContinueDialog by remember { mutableStateOf(false) }

    LaunchedEffect(projectId, chapterId) {
        viewModel.onIntent(WriterIntent.Load(projectId, chapterId))
    }

    // 一次性副作用
    LaunchedEffect(Unit) {
        viewModel.effects.collect { eff ->
            when (eff) {
                is WriterEffect.ShowToast -> snackbarHost.showSnackbar(eff.text)
                is WriterEffect.ScrollToBottom -> {
                    val last = listState.layoutInfo.totalItemsCount - 1
                    if (last >= 0) listState.animateScrollToItem(last)
                }
                is WriterEffect.NavigateToQuality -> Unit
            }
        }
    }

    // 错误以 Snackbar 呈现并自动清除，不阻塞写作
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHost.showSnackbar(it)
            viewModel.onIntent(WriterIntent.DismissError)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ChapterDrawer(
                chapters = state.chapters,
                currentId = state.currentChapter?.id,
                onSelect = { id ->
                    scope.launch { drawerState.close() }
                    viewModel.onIntent(WriterIntent.Load(projectId, id))
                },
                onAdd = { viewModel.onIntent(WriterIntent.AddChapter("")) },
                onOpenOutline = {
                    scope.launch { drawerState.close() }
                    showAiPanel = true
                },
                projectId = projectId,
                repo = repo,
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                state.chapterTitle.ifBlank { "写作台" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${formatWords(state.wordCount)} · ${state.currentChapter?.status?.label ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val score = state.qualityReport?.totalScore ?: state.currentChapter?.qualityScore ?: -1
                                if (score > 0) {
                                    Spacer(Modifier.width(8.dp))
                                    ScorePill(score)
                                }
                                if (state.dirty) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "未保存",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "章节目录")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            floatingActionButton = {
                if (!state.isGenerating) {
                    ExtendedFloatingActionButton(
                        onClick = { showAiPanel = !showAiPanel },
                        icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                        text = { Text("AI 写作") },
                    )
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (state.isGenerating) {
                    GenerationBar(
                        label = state.generationLabel.ifBlank { "正在生成…" },
                        onCancel = { viewModel.onIntent(WriterIntent.CancelGeneration) },
                    )
                }

                // 生成预览：先给用户看，确认后再并入正文
                AnimatedVisibility(visible = state.streamingText.isNotBlank()) {
                    StreamingPreview(
                        text = state.streamingText,
                        degraded = state.degraded,
                        onAccept = { viewModel.onIntent(WriterIntent.AcceptStreaming) },
                        onDiscard = { viewModel.onIntent(WriterIntent.DiscardStreaming) },
                    )
                }

                EditorBody(
                    state = state,
                    listState = listState,
                    modifier = Modifier.weight(1f),
                    onContentChange = { viewModel.onIntent(WriterIntent.EditContent(it)) },
                )

                if (showAiPanel) {
                    AiActionPanel(
                        state = state,
                        onIntent = viewModel::onIntent,
                        onContinue = { showContinueDialog = true },
                        onOpenMemory = onOpenMemory,
                        onClose = { showAiPanel = false },
                    )
                }
            }
        }
    }

    if (showContinueDialog) {
        ContinueDialog(
            onDismiss = { showContinueDialog = false },
            onConfirm = { instruction, length ->
                showContinueDialog = false
                viewModel.onIntent(WriterIntent.Continue(instruction, length))
            },
        )
    }
}

@Composable
private fun ScorePill(score: Int) {
    val color = when {
        score >= 85 -> MaterialTheme.colorScheme.primary
        score >= 70 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            "$score 分",
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun GenerationBar(label: String, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Stop, contentDescription = "停止生成")
            }
        }
    }
}

/**
 * 正文编辑器。
 *
 * 用 LazyColumn + 段落块渲染：10 万字章节下也只有可见的十几个块参与组合与测量，
 * 从根本上避免整篇重排导致的卡顿。编辑时走 BasicTextField，
 * 由 ViewModel 统一维护文本与撤销状态。
 */
@Composable
private fun EditorBody(
    state: com.inkflow.app.ui.mvi.WriterUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    onContentChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "正文",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = editing,
                onClick = { editing = !editing },
                label = { Text(if (editing) "编辑中" else "阅读") },
                leadingIcon = {
                    Icon(
                        if (editing) Icons.Default.Edit else Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }

        if (editing) {
            BasicTextField(
                value = state.currentChapter?.content.orEmpty(),
                onValueChange = onContentChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                textStyle = BodySerif.copy(color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize()) {
                        if (state.currentChapter?.content.isNullOrEmpty()) {
                            Text(
                                "在这里开始写，或点右下角让 AI 起草…",
                                style = BodySerif,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        inner()
                    }
                },
            )
        } else {
            if (state.contentBlocks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "本章还没有内容\n点右下角「AI 写作」开始",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    items(state.contentBlocks, key = { it.index }) { block ->
                        Text(
                            text = block.text,
                            style = if (block.isHeading) {
                                BodySerif.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 19.sp,
                                )
                            } else {
                                BodySerif
                            },
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    item { Spacer(Modifier.height(120.dp)) }
                }
            }
        }
    }
}

/**
 * AI 生成预览卡：明确区分「AI 产出」与「我的正文」。
 * 用户必须显式确认才会并入，避免 AI 文本悄悄污染作者手稿。
 */
@Composable
private fun StreamingPreview(
    text: String,
    degraded: Boolean,
    onAccept: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (degraded) "离线助手产出（非 AI 正文）" else "AI 生成预览",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text.take(1500) + if (text.length > 1500) "\n…（共 ${text.length} 字）" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 18,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDiscard) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("丢弃")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onAccept) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("插入正文")
                }
            }
        }
    }
}

/**
 * AI 操作面板：把 8 个 Agent 能力暴露成一组语义清晰的动作。
 */
@Composable
private fun AiActionPanel(
    state: com.inkflow.app.ui.mvi.WriterUiState,
    onIntent: (WriterIntent) -> Unit,
    onContinue: () -> Unit,
    onOpenMemory: () -> Unit,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "写作 Agent",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    state.activeEngineName.ifBlank { "探测中…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.2f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "收起")
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionChip("生成整章", Icons.Default.PlayArrow, Modifier.weight(1f)) {
                    onIntent(WriterIntent.GenerateChapter)
                }
                ActionChip("智能续写", Icons.Default.Edit, Modifier.weight(1f), onClick = onContinue)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionChip("润色", Icons.Default.AutoFixHigh, Modifier.weight(1f)) {
                    onIntent(WriterIntent.Polish)
                }
                ActionChip("校对", Icons.Default.Check, Modifier.weight(1f)) {
                    onIntent(WriterIntent.Proofread)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionChip("主编审阅", Icons.Default.Visibility, Modifier.weight(1f)) {
                    onIntent(WriterIntent.Review)
                }
                ActionChip("设定一致性", Icons.Default.Check, Modifier.weight(1f)) {
                    onIntent(WriterIntent.CheckConsistency)
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionChip("质检", Icons.Default.Check, Modifier.weight(1f)) {
                    onIntent(WriterIntent.RunQualityCheck)
                }
                ActionChip("交接笔记", Icons.Default.Edit, Modifier.weight(1f)) {
                    onIntent(WriterIntent.GenerateHandoff)
                }
                ActionChip("文风蒸馏", Icons.Default.AutoFixHigh, Modifier.weight(1f)) {
                    onIntent(WriterIntent.RebuildStyleDna)
                }
            }

            Spacer(Modifier.height(8.dp))
            ActionChip("记忆层", Icons.Default.Memory, Modifier.fillMaxWidth(), onClick = onOpenMemory)

            // 上次生成实际注入了什么上下文，让作者可核对
            if (state.lastContextPreview.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                var expanded by remember { mutableStateOf(false) }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "上次注入的上下文（${state.lastContextPreview.length} 字）",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (expanded) "收起" else "展开",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (expanded) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                state.lastContextPreview.take(1600),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.qualityReport?.let { report ->
                Spacer(Modifier.height(12.dp))
                QualitySummaryCard(report)
            }
            state.reviewVerdict?.let { verdict ->
                Spacer(Modifier.height(8.dp))
                ReviewCard(verdict)
            }
            if (state.consistencyFindings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ConsistencyCard(state.consistencyFindings)
            }
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        modifier = modifier,
    )
}

@Composable
private fun QualitySummaryCard(report: com.inkflow.core.quality.QualityReport) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("质量评估", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            ScorePill(report.totalScore)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DimensionText("连贯", report.coherence.displayValue)
            DimensionText("流畅", report.fluency.displayValue)
            DimensionText("文风", report.style.displayValue)
            DimensionText("连续", report.continuity.displayValue)
            DimensionText("伏笔", report.foreshadow.active)
        }
        if (report.issues.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            report.issues.take(5).forEach { issue ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        issue.severityLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (issue.severity) {
                            4, 3 -> MaterialTheme.colorScheme.error
                            2 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.width(32.dp),
                    )
                    Text(
                        issue.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DimensionText(label: String, value: Int) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (value < 0) "—" else value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ReviewCard(verdict: com.inkflow.core.agent.ReviewVerdict) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("主编审阅", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (verdict.score >= 0) ScorePill(verdict.score)
            }
            if (verdict.verdict.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(verdict.verdict, style = MaterialTheme.typography.bodySmall)
            }
            if (verdict.aiFlavorRisk > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "AI 腔风险：${verdict.aiFlavorRisk}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (verdict.aiFlavorRisk > 50) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            verdict.problems.take(4).forEach { p ->
                Spacer(Modifier.height(4.dp))
                Text("· ${p.issue}", style = MaterialTheme.typography.bodySmall)
                if (p.fix.isNotBlank()) {
                    Text(
                        "  建议：${p.fix}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsistencyCard(findings: List<com.inkflow.core.agent.ConsistencyFinding>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("设定冲突（${findings.size}）", style = MaterialTheme.typography.labelLarge)
            findings.take(5).forEach { f ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "【${f.type}】${f.conflict}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (f.evidence.isNotBlank()) {
                    Text(
                        "原文：${f.evidence.take(60)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (f.fix.isNotBlank()) {
                    Text("修改：${f.fix}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ContinueDialog(onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var instruction by remember { mutableStateOf("") }
    var length by remember { mutableStateOf(800) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("智能续写") },
        text = {
            Column {
                Text(
                    "AI 会读取前文末尾与检索到的设定，接着往下写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("续写要求（可选）") },
                    placeholder = { Text("例：让主角发现脚印，气氛转为紧张") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("续写长度：$length 字", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(400, 800, 1500, 2500).forEach { n ->
                        FilterChip(
                            selected = length == n,
                            onClick = { length = n },
                            label = { Text("$n") },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(instruction, length) }) { Text("开始续写") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 章节目录抽屉：章节目录 + 伏笔台账速览。
 */
@Composable
private fun ChapterDrawer(
    chapters: List<com.inkflow.core.domain.Chapter>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenOutline: () -> Unit,
    projectId: String,
    repo: NovelRepository,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("目录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onAdd) { Text("新建章") }
            }
            Text(
                "${chapters.size} 章 · ${formatWords(chapters.sumOf { it.wordCount })}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenOutline) { Text("AI 规划 / 伏笔台账") }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.weight(1f)) {
                items(chapters, key = { it.id }) { chapter ->
                    val selected = chapter.id == currentId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(chapter.id) }
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatWords(chapter.wordCount)} · ${chapter.status.label}" +
                                    if (chapter.qualityScore > 0) " · ${chapter.qualityScore}分" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (chapter.qualityScore in 1..69) {
                            Badge { Text("!") }
                        }
                    }
                }
            }
        }
    }
}
