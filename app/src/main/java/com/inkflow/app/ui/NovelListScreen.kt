package com.inkflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.inkflow.app.data.NovelRepository
import com.inkflow.app.data.SettingsStore
import com.inkflow.core.domain.Project
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 作品书架：离线优先的入口页。
 * 每张卡片直接展示创作进度，让作者一进来就知道「写到哪了」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelListScreen(
    repo: NovelRepository,
    settings: SettingsStore,
    onOpenProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val projects by repo.observeProjects().collectAsState(initial = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Map<String, ProgressSnapshot>>(emptyMap()) }

    // 加载每个作品的进度快照（字数/章节数）
    LaunchedEffect(projects.map { it.id }) {
        val map = mutableMapOf<String, ProgressSnapshot>()
        for (p in projects) {
            val chapters = repo.listChapters(p.id)
            map[p.id] = ProgressSnapshot(
                chapterCount = chapters.size,
                words = chapters.sumOf { it.wordCount },
            )
        }
        progress = map
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("墨流 · 书架") },
                actions = {
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
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建作品") },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (projects.isEmpty()) {
            EmptyLibrary(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        snapshot = progress[project.id],
                        onClick = { onOpenProject(project.id) },
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { title, genre, logline, premise ->
                scope.launch {
                    val project = repo.createProject(title, genre, logline, premise)
                    // 新作品自动建第一章，避免空书架 → 空写作台
                    repo.createChapter(project.id, "第1章")
                    showCreate = false
                    onOpenProject(project.id)
                }
            },
        )
    }
}

private data class ProgressSnapshot(val chapterCount: Int, val words: Int)

@Composable
private fun ProjectCard(project: Project, snapshot: ProgressSnapshot?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoStories,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    project.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (project.genre.isNotBlank() || project.logline.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    listOf(project.genre, project.logline).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(12.dp))

            val words = snapshot?.words ?: 0
            val ratio = (words.toFloat() / project.targetWords.coerceAtLeast(1)).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${snapshot?.chapterCount ?: 0} 章 · ${formatWords(words)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "目标 ${formatWords(project.targetWords.toInt())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(16.dp))
            Text("还没有作品", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "点击右下角新建，AI 会帮你搭大纲、写正文、查伏笔",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var logline by remember { mutableStateOf("") }
    var premise by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建作品") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("作品名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("题材（如 东方玄幻）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = logline,
                    onValueChange = { logline = it },
                    label = { Text("一句话故事") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = premise,
                    onValueChange = { premise = it },
                    label = { Text("核心设定 / 梗概") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title, genre, logline, premise) },
                enabled = title.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 字数显示：万字以上用「万」，符合中文网文习惯。 */
fun formatWords(words: Int): String = when {
    words >= 10_000 -> String.format("%.1f万字", words / 10_000.0)
    words >= 1_000 -> String.format("%.1f千字", words / 1_000.0)
    else -> "${words}字"
}
