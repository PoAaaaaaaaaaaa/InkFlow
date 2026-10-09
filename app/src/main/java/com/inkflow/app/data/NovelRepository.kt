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
        narrativePerson: String = "第三人称",
        tone: String = "",
        audience: String = "通用",
        coverPath: String = "",
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
            coverPath = coverPath,
            narrativePerson = narrativePerson,
            tone = tone,
            audience = audience,
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

    /**
     * 删除作品。Room 的外键 CASCADE 会自动清掉其下所有分卷/章节/角色/设定/伏笔/版本，
     * 这里额外清理封面文件，避免私有目录里留下孤儿图片。
     */
    suspend fun deleteProject(id: String): DeleteResult = withContext(Dispatchers.IO) {
        val project = projectDao.get(id)
        val chapterCount = chapterDao.count(id)
        val wordCount = chapterDao.listByProject(id).sumOf { it.wordCount }

        projectDao.delete(id)

        // 清理封面（仅限应用私有目录内的文件，避免误删用户相册里的原图）
        project?.coverPath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching {
                val f = java.io.File(path)
                if (f.exists() && f.absolutePath.contains("cover")) f.delete()
            }
        }
        DeleteResult(chapterCount, wordCount)
    }

    /** 归档 / 取消归档作品。 */
    suspend fun setProjectArchived(id: String, archived: Boolean) = withContext(Dispatchers.IO) {
        val p = projectDao.get(id) ?: return@withContext
        projectDao.upsert(p.copy(archived = archived, updatedAt = System.currentTimeMillis()))
    }

    /** 更新封面路径。 */
    suspend fun updateCover(projectId: String, coverPath: String) = withContext(Dispatchers.IO) {
        val p = projectDao.get(projectId) ?: return@withContext
        projectDao.upsert(p.copy(coverPath = coverPath, updatedAt = System.currentTimeMillis()))
    }

    /** 封面存放目录（应用私有，随卸载清理）。 */
    fun coverFile(projectId: String, ext: String = "jpg"): java.io.File {
        val dir = java.io.File(coverDirPath())
        if (!dir.exists()) dir.mkdirs()
        return java.io.File(dir, "cover_$projectId.$ext")
    }

    private fun coverDirPath(): String = coverDir

    /** 由 [com.inkflow.app.InkFlowApp] 在初始化时注入私有目录路径。 */
    var coverDir: String = ""
        private set

    fun setCoverDir(path: String) {
        coverDir = path
    }

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

    /**
     * 删除前预览影响范围（章节数 / 字数），供确认弹窗展示。
     * 不做任何写操作。
     */
    suspend fun inspectProject(id: String): DeleteResult = withContext(Dispatchers.IO) {
        val chapters = chapterDao.listByProject(id)
        DeleteResult(chapters.size, chapters.sumOf { it.wordCount })
    }

    /**
     * 从相册 URI 保存封面到应用私有目录。
     *
     * 为什么要「复制一份」而不是直接用 URI：
     *  - 相册 URI 的读取授权是临时的，重启后可能失效；
     *  - 用户可能在系统相册里删掉原图，导致封面丢失；
     *  - 私有目录不需要任何存储权限。
     *
     * 同时做下采样，避免把 4000x3000 的原图整张存进来。
     */
    suspend fun saveCoverFromUri(
        context: android.content.Context,
        projectId: String,
        uri: android.net.Uri,
        maxSize: Int = 1080,
    ): String = withContext(Dispatchers.IO) {
        val target = coverFile(projectId, "jpg")

        // 第一步：只读图片头部拿尺寸（inJustDecodeBounds 不会真正解码像素）
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw java.io.IOException("无法读取所选图片")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw java.io.IOException("所选文件不是有效图片")
        }

        // 第二步：算下采样比。目标是长边不超过 maxSize 的 2 倍，留出裁剪余量。
        var sample = 1
        while (bounds.outWidth / sample > maxSize * 2 || bounds.outHeight / sample > maxSize * 2) {
            sample *= 2
        }

        // 第三步：重新打开流做真正的解码（InputStream 只能消费一次，必须重新获取）
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, opts)
        } ?: throw java.io.IOException("无法解码所选图片")

        val cropped = try {
            centerCrop(bitmap, 3, 4)
        } finally {
            // centerCrop 可能返回原对象（无需裁剪时），所以用 identity 判断
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        try {
            java.io.FileOutputStream(target).use { out ->
                cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            }
        } finally {
            if (!cropped.isRecycled) cropped.recycle()
        }

        updateCover(projectId, target.absolutePath)
        target.absolutePath
    }

    /** 按目标宽高比居中裁剪。 */
    private fun centerCrop(
        src: android.graphics.Bitmap,
        ratioW: Int,
        ratioH: Int,
    ): android.graphics.Bitmap {
        val targetRatio = ratioW.toFloat() / ratioH
        val srcRatio = src.width.toFloat() / src.height
        val (cw, ch) = if (srcRatio > targetRatio) {
            (src.height * targetRatio).toInt() to src.height
        } else {
            src.width to (src.width / targetRatio).toInt()
        }
        val x = ((src.width - cw) / 2).coerceAtLeast(0)
        val y = ((src.height - ch) / 2).coerceAtLeast(0)
        // createBitmap 在参数等于原图尺寸时可能直接返回 src 本身，
        // 那样调用方 recycle 后会把原图也销毁。这里始终复制一份，语义清晰。
        return android.graphics.Bitmap.createBitmap(
            src, x, y, cw.coerceAtMost(src.width - x), ch.coerceAtMost(src.height - y),
        ).let { result ->
            if (result === src) src.copy(src.config ?: android.graphics.Bitmap.Config.ARGB_8888, false) else result
        }
    }

    data class DeleteResult(val chapters: Int, val words: Int)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun formatTime(ts: Long): String {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            return fmt.format(java.util.Date(ts))
        }
    }
}
