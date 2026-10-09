package com.inkflow.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.inkflow.core.domain.Chapter
import com.inkflow.core.domain.ChapterStatus
import com.inkflow.core.domain.Character
import com.inkflow.core.domain.Foreshadow
import com.inkflow.core.domain.ForeshadowStatus
import com.inkflow.core.domain.Project
import com.inkflow.core.domain.Volume
import com.inkflow.core.domain.WorldSetting
import kotlinx.coroutines.flow.Flow

// ----------------------------------------------------------------------
// 实体：作品 → 分卷 → 章节 的层级大纲，外加角色卡 / 世界设定 / 伏笔台账
// ----------------------------------------------------------------------

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val genre: String = "",
    val logline: String = "",
    val premise: String = "",
    val targetWords: Long = 1_000_000L,
    /** 封面图本地路径（应用私有目录） */
    val coverPath: String = "",
    val narrativePerson: String = "第三人称",
    val tone: String = "",
    val audience: String = "通用",
    val archived: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** 文风 DNA 的序列化快照（JSON），避免每次分析全本 */
    val styleDnaJson: String? = null,
    /** 全书前情提要（LOOM 全局反馈） */
    val globalRecap: String = "",
)

@Entity(
    tableName = "volumes",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("projectId")],
)
data class VolumeEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val synopsis: String = "",
    @ColumnInfo(name = "sortOrder") val order: Int = 0,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("projectId"), Index("volumeId"), Index(value = ["projectId", "sortOrder"])],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val volumeId: String? = null,
    val title: String,
    @ColumnInfo(name = "sortOrder") val order: Int = 0,
    val content: String = "",
    val outline: String = "",
    val handoffNote: String = "",
    val status: String = ChapterStatus.Draft.name,
    val wordCount: Int = 0,
    val qualityScore: Int = -1,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "characters",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("projectId")],
)
data class CharacterEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val aliases: String = "",
    val role: String = "",
    val gender: String = "",
    val age: String = "",
    val appearance: String = "",
    val personality: String = "",
    val background: String = "",
    val goal: String = "",
    val arc: String = "",
    val relationships: String = "",
    val firstAppearChapter: Int = 0,
)

@Entity(
    tableName = "world_settings",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("projectId")],
)
data class WorldSettingEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val category: String = "",
    val name: String,
    val content: String = "",
    val tags: String = "",
)

@Entity(
    tableName = "foreshadows",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("projectId"), Index("status")],
)
data class ForeshadowEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val detail: String = "",
    val plantedAt: Int = 0,
    val advancedAt: Int = 0,
    val plannedResolveAt: Int = 0,
    val status: String = ForeshadowStatus.Planted.name,
    val importance: Int = 2,
    val notes: String = "",
)

/** 章节历史快照，支撑「时光机版本回滚」。 */
@Entity(
    tableName = "chapter_versions",
    foreignKeys = [ForeignKey(
        entity = ChapterEntity::class,
        parentColumns = ["id"],
        childColumns = ["chapterId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("chapterId")],
)
data class ChapterVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: String,
    val content: String,
    val wordCount: Int,
    val createdAt: Long,
    val reason: String = "",
)

// ----------------------------------------------------------------------
// 类型转换
// ----------------------------------------------------------------------

class Converters {
    @TypeConverter fun statusToString(s: ChapterStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): ChapterStatus =
        ChapterStatus.entries.firstOrNull { it.name == s } ?: ChapterStatus.Draft

    @TypeConverter fun fsToString(s: ForeshadowStatus): String = s.name
    @TypeConverter fun stringToFs(s: String): ForeshadowStatus =
        ForeshadowStatus.entries.firstOrNull { it.name == s } ?: ForeshadowStatus.Planted
}

// ----------------------------------------------------------------------
// 领域映射
// ----------------------------------------------------------------------

fun ProjectEntity.toDomain() = Project(
    id = id, title = title, author = author, genre = genre, logline = logline,
    premise = premise, targetWords = targetWords, coverPath = coverPath,
    narrativePerson = narrativePerson, tone = tone, audience = audience,
    archived = archived, createdAt = createdAt, updatedAt = updatedAt,
)

fun Project.toEntity(styleJson: String? = null, recap: String = "") = ProjectEntity(
    id = id, title = title, author = author, genre = genre, logline = logline,
    premise = premise, targetWords = targetWords, coverPath = coverPath,
    narrativePerson = narrativePerson, tone = tone, audience = audience,
    archived = archived, createdAt = createdAt, updatedAt = updatedAt,
    styleDnaJson = styleJson, globalRecap = recap,
)

fun VolumeEntity.toDomain() = Volume(id, projectId, title, synopsis, order)
fun Volume.toEntity() = VolumeEntity(id, projectId, title, synopsis, order)

fun ChapterEntity.toDomain() = Chapter(
    id = id, projectId = projectId, volumeId = volumeId, title = title, order = order,
    content = content, outline = outline, handoffNote = handoffNote,
    status = ChapterStatus.entries.firstOrNull { it.name == status } ?: ChapterStatus.Draft,
    wordCount = wordCount, qualityScore = qualityScore, updatedAt = updatedAt,
)

fun Chapter.toEntity() = ChapterEntity(
    id = id, projectId = projectId, volumeId = volumeId, title = title, order = order,
    content = content, outline = outline, handoffNote = handoffNote,
    status = status.name, wordCount = wordCount, qualityScore = qualityScore, updatedAt = updatedAt,
)

fun CharacterEntity.toDomain() = Character(
    id, projectId, name, aliases, role, gender, age, appearance, personality,
    background, goal, arc, relationships, firstAppearChapter,
)

fun Character.toEntity() = CharacterEntity(
    id, projectId, name, aliases, role, gender, age, appearance, personality,
    background, goal, arc, relationships, firstAppearChapter,
)

fun WorldSettingEntity.toDomain() = WorldSetting(id, projectId, category, name, content, tags)
fun WorldSetting.toEntity() = WorldSettingEntity(id, projectId, category, name, content, tags)

fun ForeshadowEntity.toDomain() = Foreshadow(
    id, projectId, title, detail, plantedAt, advancedAt, plannedResolveAt,
    ForeshadowStatus.entries.firstOrNull { it.name == status } ?: ForeshadowStatus.Planted,
    importance, notes,
)

fun Foreshadow.toEntity() = ForeshadowEntity(
    id, projectId, title, detail, plantedAt, advancedAt, plannedResolveAt,
    status.name, importance, notes,
)

// ----------------------------------------------------------------------
// DAO
// ----------------------------------------------------------------------

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Upsert suspend fun upsert(project: ProjectEntity)
    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: String)

    @Query("UPDATE projects SET styleDnaJson = :json, updatedAt = :now WHERE id = :id")
    suspend fun saveStyleDna(id: String, json: String, now: Long)

    @Query("UPDATE projects SET globalRecap = :recap, updatedAt = :now WHERE id = :id")
    suspend fun saveGlobalRecap(id: String, recap: String, now: Long)
}

@Dao
interface VolumeDao {
    @Query("SELECT * FROM volumes WHERE projectId = :projectId ORDER BY sortOrder ASC")
    fun observeByProject(projectId: String): Flow<List<VolumeEntity>>

    @Query("SELECT * FROM volumes WHERE projectId = :projectId ORDER BY sortOrder ASC")
    suspend fun listByProject(projectId: String): List<VolumeEntity>

    @Upsert suspend fun upsert(volume: VolumeEntity)
    @Query("DELETE FROM volumes WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE projectId = :projectId ORDER BY sortOrder ASC")
    fun observeByProject(projectId: String): Flow<List<ChapterEntity>>

    /** 章节列表页只需要元信息，避免把十万字正文拖进内存。 */
    @Query(
        """
        SELECT id, projectId, volumeId, title, sortOrder, '' AS content, outline, handoffNote,
               status, wordCount, qualityScore, updatedAt
        FROM chapters WHERE projectId = :projectId ORDER BY sortOrder ASC
        """
    )
    fun observeMetaByProject(projectId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE id = :id")
    fun observe(id: String): Flow<ChapterEntity?>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun get(id: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE projectId = :projectId ORDER BY sortOrder ASC")
    suspend fun listByProject(projectId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE projectId = :projectId AND sortOrder < :order ORDER BY sortOrder DESC LIMIT :limit")
    suspend fun previousChapters(projectId: String, order: Int, limit: Int = 2): List<ChapterEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM chapters WHERE projectId = :projectId")
    suspend fun maxOrder(projectId: String): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE projectId = :projectId")
    suspend fun count(projectId: String): Int

    @Query("SELECT COALESCE(SUM(wordCount), 0) FROM chapters WHERE projectId = :projectId")
    fun observeTotalWords(projectId: String): Flow<Int>

    @Upsert suspend fun upsert(chapter: ChapterEntity)
    @Upsert suspend fun upsertAll(chapters: List<ChapterEntity>)
    @Query("DELETE FROM chapters WHERE id = :id") suspend fun delete(id: String)

    @Query("UPDATE chapters SET qualityScore = :score WHERE id = :id")
    suspend fun updateScore(id: String, score: Int)
}

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters WHERE projectId = :projectId ORDER BY firstAppearChapter ASC, name ASC")
    fun observeByProject(projectId: String): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE projectId = :projectId")
    suspend fun listByProject(projectId: String): List<CharacterEntity>

    @Upsert suspend fun upsert(character: CharacterEntity)
    @Upsert suspend fun upsertAll(characters: List<CharacterEntity>)
    @Query("DELETE FROM characters WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface WorldSettingDao {
    @Query("SELECT * FROM world_settings WHERE projectId = :projectId ORDER BY category ASC, name ASC")
    fun observeByProject(projectId: String): Flow<List<WorldSettingEntity>>

    @Query("SELECT * FROM world_settings WHERE projectId = :projectId")
    suspend fun listByProject(projectId: String): List<WorldSettingEntity>

    @Upsert suspend fun upsert(setting: WorldSettingEntity)
    @Upsert suspend fun upsertAll(settings: List<WorldSettingEntity>)
    @Query("DELETE FROM world_settings WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface ForeshadowDao {
    @Query("SELECT * FROM foreshadows WHERE projectId = :projectId ORDER BY status ASC, plantedAt ASC")
    fun observeByProject(projectId: String): Flow<List<ForeshadowEntity>>

    @Query("SELECT * FROM foreshadows WHERE projectId = :projectId")
    suspend fun listByProject(projectId: String): List<ForeshadowEntity>

    @Query("SELECT * FROM foreshadows WHERE id = :id")
    suspend fun get(id: String): ForeshadowEntity?

    @Upsert suspend fun upsert(foreshadow: ForeshadowEntity)
    @Upsert suspend fun upsertAll(foreshadows: List<ForeshadowEntity>)
    @Query("DELETE FROM foreshadows WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface ChapterVersionDao {
    @Query("SELECT * FROM chapter_versions WHERE chapterId = :chapterId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun list(chapterId: String, limit: Int = 30): List<ChapterVersionEntity>

    @Query("SELECT * FROM chapter_versions WHERE chapterId = :chapterId ORDER BY createdAt DESC LIMIT :limit")
    fun observe(chapterId: String, limit: Int = 30): Flow<List<ChapterVersionEntity>>

    @Insert
    suspend fun insert(version: ChapterVersionEntity)

    @Query("DELETE FROM chapter_versions WHERE chapterId = :chapterId AND id NOT IN (SELECT id FROM chapter_versions WHERE chapterId = :chapterId ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trim(chapterId: String, keep: Int = 30)
}

@Database(
    entities = [
        ProjectEntity::class, VolumeEntity::class, ChapterEntity::class,
        CharacterEntity::class, WorldSettingEntity::class, ForeshadowEntity::class,
        ChapterVersionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class InkFlowDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun volumeDao(): VolumeDao
    abstract fun chapterDao(): ChapterDao
    abstract fun characterDao(): CharacterDao
    abstract fun worldSettingDao(): WorldSettingDao
    abstract fun foreshadowDao(): ForeshadowDao
    abstract fun chapterVersionDao(): ChapterVersionDao
}
