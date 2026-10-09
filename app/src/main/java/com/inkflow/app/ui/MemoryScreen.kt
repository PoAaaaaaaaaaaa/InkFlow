package com.inkflow.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkflow.app.InkFlowApp
import com.inkflow.core.rag.IndexEntry
import com.inkflow.core.rag.MemoryPreview
import com.inkflow.core.rag.MemoryStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记忆层：把「AI 到底记住了什么」摊开给作者看。
 *
 * 为什么这个界面很重要：
 * 长篇写作里作者最容易产生的不信任就是「AI 是不是忘了我的设定？」
 * 与其用一句「已启用本地检索」搪塞，不如让作者亲眼看到：
 * 索引里有多少条记忆、都记住了哪些角色与伏笔、
 * 以及下一章生成时**实际会注入哪一段上下文**。
 *
 * 所有计算都在本地完成，不调用任何 AI 引擎，因此这个页面完全没有等待成本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    app: InkFlowApp,
    projectId: String,
    projectTitle: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var stats by remember { mutableStateOf<MemoryStats?>(null) }
    var entries by remember { mutableStateOf<List<IndexEntry>>(emptyList()) }
    var kindFilter by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 检索预览
    var query by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<MemoryPreview?>(null) }
    var previewing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        loading = true
        withContext(Dispatchers.Default) {
            stats = app.contextStore.stats(projectId)
            entries = app.contextStore.browse(projectId, kindFilter, limit = 200)
        }
        loading = false
    }

    LaunchedEffect(projectId, kindFilter) { refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text("记忆层", style = MaterialTheme.typography.titleMedium)
                        Text(
                            projectTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("概览") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("记忆条目") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("召回预览") })
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            when (tab) {
                0 -> OverviewTab(
                    stats = stats,
                    onRebuild = { scope.launch { refresh() } },
                )

                1 -> EntriesTab(
                    entries = entries,
                    kindFilter = kindFilter,
                    onKindChange = { kindFilter = it },
                    kinds = stats?.byKind?.map { it.kind } ?: emptyList(),
                )

                else -> PreviewTab(
                    query = query,
                    onQueryChange = { query = it },
                    preview = preview,
                    previewing = previewing,
                    onRun = {
                        scope.launch {
                            previewing = true
                            preview = withContext(Dispatchers.Default) {
                                app.contextStore.preview(
                                    projectId = projectId,
                                    query = query.ifBlank { projectTitle },
                                )
                            }
                            previewing = false
                        }
                    },
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// 概览
// ----------------------------------------------------------------------

@Composable
private fun OverviewTab(stats: MemoryStats?, onRebuild: () -> Unit) {
    if (stats == null) return

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (stats.isEmpty) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("记忆库还是空的", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "写下正文章节、添加角色卡与世界设定后，这里会自动建立索引。"
                            + "索引完全在本地完成，不上传任何内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onRebuild, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("重建索引")
            }
            return@Column
        }

        // 总量卡片
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("记忆总量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BigStat("${stats.totalEntries}", "条记忆")
                    BigStat(formatWords(stats.totalChars), "总字数")
                    BigStat("${stats.byKind.size}", "个类别")
                }
            }
        }

        // 按类别分布
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("记忆构成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "AI 续写时会从这些类别里按语义相关性召回",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                val max = stats.byKind.maxOfOrNull { it.chars }?.coerceAtLeast(1) ?: 1
                stats.byKind.forEach { k ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${kindEmoji(k.kind)} ${k.kind}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${k.entries} 条 · ${formatWords(k.chars)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = { k.chars.toFloat() / max },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
        }

        // 索引结构（解释「为什么快」）
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("索引结构", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                InfoRow("总条目", "${stats.indexSize.entries}")
                InfoRow("倒排维度", "${stats.indexSize.dimensions}")
                InfoRow("倒排项（postings）", "${stats.indexSize.postings}")
                InfoRow("平均特征词/条", String.format("%.1f", stats.indexSize.avgTermsPerEntry))
                Spacer(Modifier.height(8.dp))
                Text(
                    "检索只扫描倒排命中的桶，不做全量向量点积 —— 这是十万字作品也能毫秒级召回的原因。"
                        + "全部计算在设备本地完成，零网络请求。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedButton(onClick = onRebuild, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("重建索引")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BigStat(value: String, label: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun kindEmoji(kind: String): String = when (kind) {
    "角色" -> "👤"
    "设定" -> "🌍"
    "伏笔" -> "🪝"
    "前文" -> "📖"
    "交接笔记" -> "📝"
    "细纲" -> "📋"
    else -> "•"
}

// ----------------------------------------------------------------------
// 条目浏览
// ----------------------------------------------------------------------

@Composable
private fun EntriesTab(
    entries: List<IndexEntry>,
    kindFilter: String?,
    onKindChange: (String?) -> Unit,
    kinds: List<String>,
) {
    Column(Modifier.fillMaxSize()) {
        // 类别筛选
        if (kinds.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = kindFilter == null,
                    onClick = { onKindChange(null) },
                    label = { Text("全部", style = MaterialTheme.typography.bodySmall) },
                )
                kinds.take(4).forEach { k ->
                    FilterChip(
                        selected = kindFilter == k,
                        onClick = { onKindChange(k) },
                        label = { Text(k, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
            HorizontalDivider()
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "该类别下暂无记忆",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    EntryCard(entry)
                }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: IndexEntry) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        "${kindEmoji(entry.kind)} ${entry.kind}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (entry.source.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        entry.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    "${entry.textLength}字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                entry.textPreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ----------------------------------------------------------------------
// 召回预览
// ----------------------------------------------------------------------

@Composable
private fun PreviewTab(
    query: String,
    onQueryChange: (String) -> Unit,
    preview: MemoryPreview?,
    previewing: Boolean,
    onRun: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "模拟一次写作前的召回，看看 AI 实际会读到什么。此操作不调用任何 AI 模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("模拟查询") },
            placeholder = { Text("留空则用作品名，或填入本章要点，如「林逸 断剑 青云门」") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onRun,
            enabled = !previewing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (previewing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text("运行召回")
        }

        preview?.let { p ->
            Spacer(Modifier.height(18.dp))

            if (p.isEmpty) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "没有召回到任何内容。可能原因：\n"
                            + "· 作品还没有正文 / 角色 / 设定\n"
                            + "· 查询词与已有内容没有共现词汇（本引擎基于词面匹配，不做同义扩展）\n"
                            + "建议先在写作台写一两章，或添加角色卡与设定。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                return@Column
            }

            Text(
                "命中 ${p.hits.size} 条 · 上下文 ${p.charCount} 字",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))

            // 相关性排序
            p.hits.forEach { hit ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${kindEmoji(hit.kind)} ${hit.kind}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (hit.source.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    hit.source,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            Text(
                                "相关度 ${hit.percent}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (hit.percent >= 30) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { hit.score.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(hit.preview, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "将注入 Prompt 的原文",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "下面这段就是生成下一章时真正放进提示词的内容，逐字可见。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        p.promptBlock,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
