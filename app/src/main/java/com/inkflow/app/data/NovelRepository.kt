package com.inkflow.app.data

import com.inkflow.core.domain.Chapter
import com.inkflow.core.domain.ChapterStatus
import com.inkflow.core.domain.Character
import com.inkflow.core.domain.Foreshadow
import com.inkflow.core.domain.Project
import com.inkflow.core.domain.Volume
import com.inkflow.core.domain.WorldSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 作品数据仓库：把 Room 的实体流转换成领域模型流。
 *
 * 全部写操作都跑在 IO 线程；章节保存时自动写入历史版本，
 * 支撑「时光机回滚」——长篇创作里误删整章是灾难性的。
 */
class NovelRepository(
    private val db: InkFlowDatabase,
) {
    private val projectDao = db.projectDao()
    private val volumeDao = db.volumeDao()
    private val chapterDao = db.chapterDao()
    private val characterDao = db.characterDao()
    private val settingDao = db.worldSettingDao()
    private val foreshadowDao = db.foreshadowDao()
    private val versionDao = db.chapterVersionDao()

    // ---------------- 作品 ----------------

    fun observeProjects(): Flow<List<Project>> =
        projectDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeProject(id: String): Flow<Project?> =
        projectDao.observe(id).map { it?.toDomain() }

    suspend fun getProject(id: String): Project? = withContext(Dispatchers.IO) {
        projectDao.get(id)?.toDomain()
    }

    suspend fun getProjectEntity(id: String): ProjectEntity? = withContext(Dispatchers.IO) {
        projectDao.get(id)
    }

    suspend fun createProject(
        title: String,
        genre: String,
        logline: String,
        premise: String,
        author: String = "",
        targetWords: Long = 1_000_000L,
    ): Project = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val project = Project(
            id = newId(),
            title = title.ifBlank { "未命名作品" },
            author = author,
            genre = genre,
            logline = logline,
            premise = premise,
            targetWords = targetWords,
            createdAt = now,
            updatedAt = now,
        )
        projectDao.upsert(project.toEntity())
        project
    }

    suspend fun saveProject(project: Project) = withContext(Dispatchers.IO) {
        val existing = projectDao.get(project.id)
        projectDao.upsert(
            project.copy(updatedAt = System.currentTimeMillis())
                .toEntity(styleJson = existing?.styleDnaJson, recap = existing?.globalRecap ?: "")
        )
    }

    suspend fun saveStyleDna(projectId: String, json: String) = withContext(Dispatchers.IO) {
        projectDao.saveStyleDna(projectId, json, System.currentTimeMillis())
    }

    suspend fun saveGlobalRecap(projectId: String, recap: String) = withContext(Dispatchers.IO) {
        projectDao.saveGlobalRecap(projectId, recap, System.currentTimeMillis())
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) { projectDao.delete(id) }

    // ---------------- 分卷 ----------------

    fun observeVolumes(projectId: String): Flow<List<Volume>> =
        volumeDao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }

    suspend fun listVolumes(projectId: String): List<Volume> = withContext(Dispatchers.IO) {
        volumeDao.listByProject(projectId).map { it.toDomain() }
    }

    suspend fun saveVolume(volume: Volume) = withContext(Dispatchers.IO) {
        volumeDao.upsert(volume.toEntity())
    }

    suspend fun createVolume(projectId: String, title: String, synopsis: String = ""): Volume =
        withContext(Dispatchers.IO) {
            val order = volumeDao.listByProject(projectId).size
            val v = Volume(newId(), projectId, title, synopsis, order)
            volumeDao.upsert(v.toEntity())
            v
        }

    suspend fun deleteVolume(id: String) = withContext(Dispatchers.IO) { volumeDao.delete(id) }

    // ---------------- 章节 ----------------

    fun observeChapters(projectId: String): Flow<List<Chapter>> =
        chapterDao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }

    /** 只含元信息（content 为空）的列表流，用于大纲/列表页。 */
    fun observeChapterMeta(projectId: String): Flow<List<Chapter>> =
        chapterDao.observeMetaByProject(projectId).map { list -> list.map { it.toDomain() } }

    fun observeChapter(id: String): Flow<Chapter?> =
        chapterDao.observe(id).map { it?.toDomain() }

    suspend fun getChapter(id: String): Chapter? = withContext(Dispatchers.IO) {
        chapterDao.get(id)?.toDomain()
    }

    suspend fun listChapters(projectId: String): List<Chapter> = withContext(Dispatchers.IO) {
        chapterDao.listByProject(projectId).map { it.toDomain() }
    }

    suspend fun previousChapters(projectId: String, order: Int, limit: Int = 2): List<Chapter> =
        withContext(Dispatchers.IO) {
            chapterDao.previousChapters(projectId, order, limit).map { it.toDomain() }
        }

    fun observeTotalWords(projectId: String): Flow<Int> = chapterDao.observeTotalWords(projectId)

    suspend fun createChapter(
        projectId: String,
        title: String,
        volumeId: String? = null,
        outline: String = "",
        content: String = "",
    ): Chapter = withContext(Dispatchers.IO) {
        val nextOrder = chapterDao.maxOrder(projectId) + 1
        val chapter = Chapter(
            id = newId(),
            projectId = projectId,
            volumeId = volumeId,
            title = title.ifBlank { "第${nextOrder}章" },
            order = nextOrder,
            content = content,
            outline = outline,
            wordCount = Chapter.countWords(content),
            updatedAt = System.currentTimeMillis(),
        )
        chapterDao.upsert(chapter.toEntity())
        chapter
    }

    /**
     * 保存章节正文。会先把旧正文写入历史版本表（最多保留 40 版），
     * 从而支持「时光机」回滚。
     */
    suspend fun saveChapterContent(
        chapterId: String,
        content: String,
        reason: String = "编辑",
        keepVersions: Int = 40,
    ) = withContext(Dispatchers.IO) {
        val old = chapterDao.get(chapterId) ?: return@withContext
        if (old.content.isNotEmpty() && old.content != content) {
            versionDao.insert(
                ChapterVersionEntity(
                    chapterId = chapterId,
                    content = old.content,
                    wordCount = old.wordCount,
                    createdAt = System.currentTimeMillis(),
                    reason = reason,
                )
            )
            versionDao.trim(chapterId, keepVersions)
        }
        chapterDao.upsert(
            old.copy(
                content = content,
                wordCount = Chapter.countWords(content),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun saveChapter(chapter: Chapter) = withContext(Dispatchers.IO) {
        chapterDao.upsert(
            chapter.copy(
                wordCount = Chapter.countWords(chapter.content),
                updatedAt = System.currentTimeMillis(),
            ).toEntity()
        )
    }

    suspend fun saveChapters(chapters: List<Chapter>) = withContext(Dispatchers.IO) {
        chapterDao.upsertAll(
            chapters.map {
                it.copy(wordCount = Chapter.countWords(it.content), updatedAt = System.currentTimeMillis()).toEntity()
            }
        )
    }

    suspend fun updateChapterScore(chapterId: String, score: Int) = withContext(Dispatchers.IO) {
        chapterDao.updateScore(chapterId, score)
    }

    suspend fun setChapterStatus(chapterId: String, status: ChapterStatus) = withContext(Dispatchers.IO) {
        val c = chapterDao.get(chapterId) ?: return@withContext
        chapterDao.upsert(c.copy(status = status.name, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteChapter(id: String) = withContext(Dispatchers.IO) { chapterDao.delete(id) }

    suspend fun chapterCount(projectId: String): Int = withContext(Dispatchers.IO) {
        chapterDao.count(projectId)
    }

    // ---------------- 历史版本 ----------------

    suspend fun listVersions(chapterId: String, limit: Int = 30): List<ChapterVersionEntity> =
        withContext(Dispatchers.IO) { versionDao.list(chapterId, limit) }

    fun observeVersions(chapterId: String, limit: Int = 30): Flow<List<ChapterVersionEntity>> =
        versionDao.observe(chapterId, limit)

    /** 回滚到指定版本；当前内容会先被存为一个新版本，因此回滚本身也可撤销。 */
    suspend fun rollback(chapterId: String, versionId: Long) = withContext(Dispatchers.IO) {
        val versions = versionDao.list(chapterId, 100)
        val target = versions.firstOrNull { it.id == versionId } ?: return@withContext
        saveChapterContent(chapterId, target.content, reason = "回滚到 ${formatTime(target.createdAt)}")
    }

    // ---------------- 角色 ----------------

    fun observeCharacters(projectId: String): Flow<List<Character>> =
        characterDao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }

    suspend fun listCharacters(projectId: String): List<Character> = withContext(Dispatchers.IO) {
        characterDao.listByProject(projectId).map { it.toDomain() }
    }

    suspend fun saveCharacter(character: Character) = withContext(Dispatchers.IO) {
        characterDao.upsert(character.toEntity())
    }

    suspend fun saveCharacters(characters: List<Character>) = withContext(Dispatchers.IO) {
        characterDao.upsertAll(characters.map { it.toEntity() })
    }

    suspend fun deleteCharacter(id: String) = withContext(Dispatchers.IO) { characterDao.delete(id) }

    // ---------------- 世界设定 ----------------

    fun observeSettings(projectId: String): Flow<List<WorldSetting>> =
        settingDao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }

    suspend fun listSettings(projectId: String): List<WorldSetting> = withContext(Dispatchers.IO) {
        settingDao.listByProject(projectId).map { it.toDomain() }
    }

    suspend fun saveSetting(setting: WorldSetting) = withContext(Dispatchers.IO) {
        settingDao.upsert(setting.toEntity())
    }

    suspend fun saveSettings(settings: List<WorldSetting>) = withContext(Dispatchers.IO) {
        settingDao.upsertAll(settings.map { it.toEntity() })
    }

    suspend fun deleteSetting(id: String) = withContext(Dispatchers.IO) { settingDao.delete(id) }

    // ---------------- 伏笔 ----------------

    fun observeForeshadows(projectId: String): Flow<List<Foreshadow>> =
        foreshadowDao.observeByProject(projectId).map { list -> list.map { it.toDomain() } }

    suspend fun listForeshadows(projectId: String): List<Foreshadow> = withContext(Dispatchers.IO) {
        foreshadowDao.listByProject(projectId).map { it.toDomain() }
    }

    suspend fun getForeshadow(id: String): Foreshadow? = withContext(Dispatchers.IO) {
        foreshadowDao.get(id)?.toDomain()
    }

    suspend fun saveForeshadow(foreshadow: Foreshadow) = withContext(Dispatchers.IO) {
        foreshadowDao.upsert(foreshadow.toEntity())
    }

    suspend fun saveForeshadows(list: List<Foreshadow>) = withContext(Dispatchers.IO) {
        foreshadowDao.upsertAll(list.map { it.toEntity() })
    }

    suspend fun deleteForeshadow(id: String) = withContext(Dispatchers.IO) { foreshadowDao.delete(id) }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun formatTime(ts: Long): String {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            return fmt.format(java.util.Date(ts))
        }
    }
}
