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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.inkflow.app.data.NovelRepository
import com.inkflow.app.data.SettingsStore
import com.inkflow.core.domain.Project
import kotlinx.coroutines.launch
import java.io.File

/**
 * 作品书架。支持：
 *  - 封面展示与更换（相册选图，走 PickVisualMedia 无需存储权限）
 *  - 菜单删除（二次确认，如实告知将删除的章节数与字数，并提供「改为归档」）
 *  - 归档分区（进行中 / 已归档）
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
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val projects by repo.observeProjects().collectAsState(initial = emptyList())

    var showCreate by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Map<String, ProgressSnapshot>>(emptyMap()) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var deleteStat by remember { mutableStateOf<NovelRepository.DeleteResult?>(null) }
    var showArchived by remember { mutableStateOf(false) }

    // 正在更换封面的作品 id，用于把选图结果写回正确的作品
    var coverTargetId by remember { mutableStateOf<String?>(null) }

    val pickCover = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val target = coverTargetId
        coverTargetId = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching { repo.saveCoverFromUri(context, target, uri) }.isSuccess
            snackbar.showSnackbar(if (ok) "封面已更新" else "封面保存失败，请换一张图片试试")
        }
    }

    LaunchedEffect(projects.map { it.id to it.updatedAt }) {
        val map = mutableMapOf<String, ProgressSnapshot>()
        for (p in projects) {
            val chapters = repo.listChapters(p.id)
            map[p.id] = ProgressSnapshot(
                chapterCount = chapters.size,
                words = chapters.sumOf { it.wordCount },
                lastChapterTitle = chapters.maxByOrNull { it.order }?.title.orEmpty(),
                lockedCount = chapters.count { it.status == com.inkflow.core.domain.ChapterStatus.Locked },
            )
        }
        progress = map
    }

    val visible = projects.filter { it.archived == showArchived }
    val archivedCount = projects.count { it.archived }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (showArchived) "已归档" else "墨流 · 书架") },
                actions = {
                    if (archivedCount > 0 || showArchived) {
                        IconButton(onClick = { showArchived = !showArchived }) {
                            Icon(
                                if (showArchived) Icons.Default.AutoStories else Icons.Default.Archive,
                                contentDescription = if (showArchived) "返回书架" else "查看归档（$archivedCount）",
                            )
                        }
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
            if (!showArchived) {
                ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("新建作品") },
                )
            }
        },
    ) { padding ->
        if (visible.isEmpty()) {
            EmptyLibrary(Modifier.padding(padding), archived = showArchived)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visible, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        snapshot = progress[project.id],
                        onClick = { onOpenProject(project.id) },
                        onChangeCover = {
                            coverTargetId = project.id
                            pickCover.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onArchive = {
                            scope.launch {
                                repo.setProjectArchived(project.id, !project.archived)
                                snackbar.showSnackbar(
                                    if (project.archived) "已恢复到书架" else "已归档《${project.title}》"
                                )
                            }
                        },
                        onDelete = {
                            deleteTarget = project
                            scope.launch { deleteStat = repo.inspectProject(project.id) }
                        },
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateProjectWizard(
            repo = repo,
            onDismiss = { showCreate = false },
            onCreated = { project ->
                showCreate = false
                onOpenProject(project.id)
            },
        )
    }

    // 删除二次确认：如实告知影响范围，并给出更温和的「归档」选项
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null; deleteStat = null },
            icon = {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("删除《${target.title}》？") },
            text = {
                Column {
                    val stat = deleteStat
                    Text(
                        if (stat == null) "正在统计…"
                        else "将永久删除 ${stat.chapters} 个章节、约 ${formatWords(stat.words)}正文，" +
                            "以及全部角色卡、世界设定与伏笔台账。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "此操作不可撤销。如果只是暂时不想看到，可以选「改为归档」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        val title = target.title
                        deleteTarget = null
                        deleteStat = null
                        scope.launch {
                            val stat = repo.deleteProject(id)
                            snackbar.showSnackbar("已删除《$title》（${stat.chapters} 章 / ${formatWords(stat.words)}）")
                        }
                    },
                ) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val id = target.id
                    val title = target.title
                    deleteTarget = null
                    deleteStat = null
                    scope.launch {
                        repo.setProjectArchived(id, true)
                        snackbar.showSnackbar("已归档《$title》，可在归档区找回")
                    }
                }) { Text("改为归档") }
            },
        )
    }
}

data class ProgressSnapshot(
    val chapterCount: Int,
    val words: Int,
    val lastChapterTitle: String = "",
    val lockedCount: Int = 0,
)

@Composable
private fun ProjectCard(
    project: Project,
    snapshot: ProgressSnapshot?,
    onClick: () -> Unit,
    onChangeCover: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(12.dp)) {
            CoverThumb(
                coverPath = project.coverPath,
                title = project.title,
                onClick = onChangeCover,
            )

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        project.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (project.coverPath.isBlank()) "添加封面" else "更换封面") },
                                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                onClick = { menuOpen = false; onChangeCover() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (project.archived) "取消归档" else "归档") },
                                leadingIcon = {
                                    Icon(
                                        if (project.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                                        contentDescription = null,
                                    )
                                },
                                onClick = { menuOpen = false; onArchive() },
                            )
                            DropdownMenuItem(
                                text = { Text("删除作品", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                }

                val meta = listOfNotNull(
                    project.genre.takeIf { it.isNotBlank() },
                    project.tone.takeIf { it.isNotBlank() },
                    project.audience.takeIf { it.isNotBlank() && it != "通用" },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (project.logline.isNotBlank()) {
                    Text(
                        project.logline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(10.dp))
                val words = snapshot?.words ?: 0
                val ratio = (words.toFloat() / project.targetWords.coerceAtLeast(1)).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(5.dp))
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
                        "${(ratio * 100).toInt()}% / ${formatWords(project.targetWords.toInt())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                snapshot?.lastChapterTitle?.takeIf { it.isNotBlank() }?.let { last ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "最新：$last",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 封面缩略图：未设置时显示首字占位，点击即可更换。 */
@Composable
private fun CoverThumb(coverPath: String, title: String, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier
            .size(width = 76.dp, height = 104.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val file = coverPath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (file != null && file.exists()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file)
                    .crossfade(true)
                    .build(),
                contentDescription = "$title 封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title.take(1),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Icon(
                    Icons.Default.Image,
                    contentDescription = "添加封面",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier, archived: Boolean = false) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (archived) Icons.Default.Archive else Icons.Default.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (archived) "没有已归档的作品" else "还没有作品",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (archived) "归档的作品会出现在这里"
                else "点击右下角新建，AI 会帮你搭大纲、写正文、查伏笔",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 字数显示：万字以上用「万」，符合中文网文习惯。 */
fun formatWords(words: Int): String = when {
    words >= 10_000 -> String.format("%.1f万字", words / 10_000.0)
    words >= 1_000 -> String.format("%.1f千字", words / 1_000.0)
    else -> "${words}字"
}
