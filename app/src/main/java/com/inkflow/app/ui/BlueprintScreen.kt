package com.inkflow.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkflow.core.corpus.Blueprint
import com.inkflow.core.corpus.ChapterPlan
import com.inkflow.core.corpus.VolumePlan

/**
 * 全书蓝图观览页。
 *
 * 【为什么要有独立的观览页而不是直接写库】
 * 一份 100 万字的蓝图是 300+ 章细纲。作者不可能在对话框里逐条确认，
 * 但也不能让它悄悄落库——规划是作品的地基，改地基要当事人点头。
 *
 * 所以流程是：生成 → 在这一页通读 → 确认写入 / 丢弃。
 * 页面按「世界观 / 角色 / 伏笔 / 分卷细纲」四个标签分块，
 * 每一块都能独立展开，方便作者只关心自己在意的那部分。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlueprintScreen(
    blueprint: Blueprint,
    generating: Boolean,
    stage: String,
    progress: Float,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.Close, contentDescription = "返回")
                        }
                    },
                    title = {
                        Column {
                            Text("全书蓝图", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "《${blueprint.title}》 · ${blueprint.volumes.size} 卷 / " +
                                    "${blueprint.totalChapters} 章",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                BlueprintActions(
                    generating = generating,
                    stage = stage,
                    progress = progress,
                    onApply = onApply,
                    onDiscard = onDiscard,
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
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("世界观") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("角色") })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("细纲") })
                }

                when (tab) {
                    0 -> OverviewTab(blueprint)
                    1 -> SettingsTab(blueprint)
                    2 -> CharactersTab(blueprint)
                    else -> ChaptersTab(blueprint)
                }
            }
        }
    }
}

@Composable
private fun BlueprintActions(
    generating: Boolean,
    stage: String,
    progress: Float,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            if (generating) {
                Text(
                    stage.ifBlank { "处理中…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("丢弃")
                    }
                    Button(onClick = onApply, modifier = Modifier.weight(1.6f)) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("写入作品")
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------

@Composable
private fun OverviewTab(blueprint: Blueprint) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp)) {
                    Text("规模", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("目标字数", "${blueprint.targetWords / 10000} 万字")
                    InfoRow("分卷", "${blueprint.structure.volumeCount} 卷")
                    InfoRow("总章数", "${blueprint.totalChapters} 章")
                    InfoRow("单章", "约 ${blueprint.structure.wordsPerChapter} 字")
                    InfoRow("每卷", "约 ${blueprint.structure.chaptersPerVolume} 章")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        blueprint.structure.actBreakdown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp)) {
                    Text("写作深度", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            blueprint.depth.tier.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${blueprint.depth.level}/100",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(blueprint.depth.tier.blurb, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("目标句长", "平均 ${blueprint.depth.sentenceLengthTarget} 字")
                    InfoRow("目标段落", "平均 ${blueprint.depth.paragraphLengthTarget} 字")
                    InfoRow("比喻密度", "每千字 ≤ ${String.format("%.1f", blueprint.depth.metaphorPer1kMax)} 次")
                    InfoRow("心理描写", "约 ${(blueprint.depth.introspectionRatio * 100).toInt()}%")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (blueprint.depth.analogies) "✓ 启用类比讲解（通俗向）" else "✓ 关闭类比讲解（深度向）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (blueprint.depth.ambiguity) "✓ 允许留白" else "✓ 不留白，每件事都交代清楚",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("内容统计", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("世界观设定", "${blueprint.settings.size} 条")
                    InfoRow("角色", "${blueprint.characters.size} 位")
                    InfoRow("伏笔", "${blueprint.foreshadows.size} 条")
                    InfoRow("已细化章节", "${blueprint.detailedChapters} / ${blueprint.totalChapters}")
                    if (blueprint.degraded) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠ 部分内容由兜底引擎或占位补齐，建议先通读一遍再写入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("分卷一览", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    blueprint.volumes.forEach { v ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(
                                "第${v.index + 1}卷",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(52.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(v.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "第 ${v.startOrder}-${v.endOrder} 章 · ${v.chapterCount} 章",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(blueprint: Blueprint) {
    if (blueprint.settings.isEmpty()) {
        EmptyHint("本次未生成世界观设定。可以直接写入，之后在记忆层里手动补充。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blueprint.settings.groupBy { it.category }.forEach { (category, list) ->
            item {
                Text(
                    category,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(list) { s ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (s.content.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(s.content, style = MaterialTheme.typography.bodySmall)
                        }
                        if (s.tags.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                s.tags,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharactersTab(blueprint: Blueprint) {
    if (blueprint.characters.isEmpty()) {
        EmptyHint("本次未生成角色。可以直接写入，之后在作品里手动添加。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(blueprint.characters) { c ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (c.role.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                c.role,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (c.personality.isNotBlank()) InfoRow("性格", c.personality)
                    if (c.goal.isNotBlank()) InfoRow("目标", c.goal)
                    if (c.arc.isNotBlank()) InfoRow("弧光", c.arc)
                    if (c.relationships.isNotBlank()) InfoRow("关系", c.relationships)
                    if (c.background.isNotBlank()) InfoRow("背景", c.background)
                }
            }
        }

        if (blueprint.foreshadows.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "伏笔台账",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(blueprint.foreshadows) { f ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "重要度 ${"★".repeat(f.importance)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "第 ${f.plantedAt} 章埋设 → 计划第 ${f.plannedResolveAt} 章回收",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (f.detail.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(f.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChaptersTab(blueprint: Blueprint) {
    if (blueprint.volumes.isEmpty()) {
        EmptyHint("本次未生成细纲。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blueprint.volumes.forEach { v ->
            item(key = "vol_${v.index}") {
                VolumeHeader(v)
            }
            items(v.chapters, key = { "ch_${it.order}" }) { c ->
                ChapterCard(c)
            }
        }
    }
}

@Composable
private fun VolumeHeader(volume: VolumePlan) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "第${volume.index + 1}卷  ${volume.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    "第 ${volume.startOrder}-${volume.endOrder} 章",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (volume.function.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        volume.function,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (volume.synopsis.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(volume.synopsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChapterCard(chapter: ChapterPlan) {
    var expanded by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第${chapter.order}章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(62.dp),
                )
                Text(
                    chapter.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (chapter.isDetailed) {
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (!chapter.isDetailed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "（细纲待生成）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                return@Column
            }

            Spacer(Modifier.height(4.dp))
            Text(
                chapter.event,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                if (chapter.conflict.isNotBlank()) InfoRow("冲突", chapter.conflict)
                if (chapter.emotionArc.isNotBlank()) InfoRow("情绪", chapter.emotionArc)
                if (chapter.characters.isNotEmpty()) {
                    InfoRow("人物", chapter.characters.joinToString("、"))
                }
                if (chapter.foreshadow.isNotBlank()) InfoRow("伏笔", chapter.foreshadow)
                if (chapter.hook.isNotBlank()) InfoRow("钩子", chapter.hook)
            } else if (chapter.hook.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "钩子：${chapter.hook}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(68.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}
