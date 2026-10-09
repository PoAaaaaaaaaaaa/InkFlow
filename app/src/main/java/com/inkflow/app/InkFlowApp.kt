package com.inkflow.app

import android.app.Application
import androidx.room.Room
import com.inkflow.app.ai.EngineFactory
import com.inkflow.app.ai.EngineRouter
import com.inkflow.app.data.InkFlowDatabase
import com.inkflow.app.data.NovelRepository
import com.inkflow.app.data.SettingsStore
import com.inkflow.core.rag.NovelContextStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用入口：手工依赖装配。
 *
 * 规模可控时不引入 DI 框架——少一层魔法，启动更快，
 * 也让「数据往哪里流」在代码里一眼可见。
 */
class InkFlowApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: InkFlowDatabase by lazy {
        Room.databaseBuilder(this, InkFlowDatabase::class.java, "inkflow.db")
            // v1 -> v2：新增封面/视角/基调/受众/归档字段。
            // 这是纯粹的加列迁移，绝不能清库 —— 用户的稿子比什么都重要。
            .addMigrations(MIGRATION_1_2)
            // 仅作为未来未知版本升级的最后兜底（会清库），正常路径永远走上面的迁移
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    val repository: NovelRepository by lazy {
        NovelRepository(database).also {
            // 封面存到应用私有目录，随卸载自动清理，不需要存储权限
            it.setCoverDir(java.io.File(filesDir, "covers").absolutePath)
        }
    }

    val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    /**
     * 新建作品时的问答答案，供写作台首次生成蓝图时取用。
     *
     * 【为什么放在这里而不是随导航参数传递】
     * 答案是「作者 + 若干自由文本」的映射，序列化进 URL 既脆弱又难看，
     * 而它的生命周期恰好就是「创建完成 → 首次生成蓝图」这一段。
     * 用进程内的暂存把它绑定到 projectId，比塞进路由干净得多。
     *
     * 取用即清除（见 consumeIntakeAnswers），避免第二次打开作品时被旧答案污染。
     */
    private val intakeAnswers = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    fun stashIntakeAnswers(projectId: String, answers: Map<String, String>) {
        if (answers.isNotEmpty()) intakeAnswers[projectId] = answers
    }

    fun consumeIntakeAnswers(projectId: String): Map<String, String> =
        intakeAnswers.remove(projectId).orEmpty()

    /** 端侧写作语境仓库（本地 RAG）。全局唯一，跨页面复用索引。 */
    val contextStore: NovelContextStore by lazy { NovelContextStore() }

    /**
     * 引擎路由在首次使用时异步装配：先读用户设置里的云端配置与优先级，
     * 再构造路由。UI 通过 awaitRouter() 获取，避免竞态。
     */
    @Volatile
    private var routerInstance: EngineRouter? = null

    val router: EngineRouter
        get() = routerInstance ?: synchronized(this) {
            routerInstance ?: EngineFactory.createRouter(
                this,
                com.inkflow.app.ai.CloudConfig(),
                com.inkflow.app.ai.EnginePreference(),
            ).also { routerInstance = it }
        }

    companion object {
        /**
         * v1 → v2：为 projects 表补充向导新增的字段（封面/视角/基调/受众/归档）。
         *
         * 【为什么必须带 DEFAULT】
         * SQLite 的 `ALTER TABLE ... ADD COLUMN` 如果要加的是 NOT NULL 列，
         * **必须同时提供非 NULL 的默认值**，否则直接报
         * "Cannot add a NOT NULL column with default value NULL"。
         * 因此这里的 DEFAULT 不是可选的美化，而是让迁移能执行的前提。
         *
         * 【为什么带 DEFAULT 也能通过 Room 的 schema 校验】
         * Room 会拿「实体生成的期望 schema」与「迁移后的实际 schema」比对，
         * 其中 defaultValue 是原始 SQL 字符串比较。Kotlin 里 `val x: String = ""`
         * 只是语言层默认值，Room 记录的期望默认值是 `null`
         * （见生成的 InkFlowDatabase_Impl 中 TableInfo.Column(..., defaultValue = null)）。
         * 而 Room 的 Column.equals 在「期望值为 null」时会**跳过**默认值比较
         * （已反编译 androidx.room.util.TableInfoKt.equalsCommon 确认该分支），
         * 所以 DEFAULT '' 不会造成 schema 不匹配。
         *
         * 【为什么不用 RENAME + 重建表】
         * 加列迁移不触碰既有数据，风险最低。用户的稿子比什么都重要，
         * 能用 ADD COLUMN 就绝不重建表。
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1) 加列（无 DEFAULT，与 Room 期望的 defaultValue = null 一致）
                db.execSQL("ALTER TABLE projects ADD COLUMN coverPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE projects ADD COLUMN narrativePerson TEXT NOT NULL DEFAULT '第三人称'")
                db.execSQL("ALTER TABLE projects ADD COLUMN tone TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE projects ADD COLUMN audience TEXT NOT NULL DEFAULT '通用'")
                db.execSQL("ALTER TABLE projects ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 启动即装配引擎；设置读取完成后热更新云端配置
        appScope.launch {
            runCatching {
                val cloud = settingsStore.cloudConfig.first()
                val pref = settingsStore.enginePreference.first()
                val r = EngineFactory.createRouter(this@InkFlowApp, cloud, pref)
                synchronized(this@InkFlowApp) { routerInstance = r }
                r.probeAll()
            }
        }
    }

    /** 设置变更后重新装配路由，使新配置立即生效。 */
    fun reloadRouter() {
        appScope.launch {
            runCatching {
                val cloud = settingsStore.cloudConfig.first()
                val pref = settingsStore.enginePreference.first()
                val old = routerInstance
                val fresh = EngineFactory.createRouter(this@InkFlowApp, cloud, pref)
                synchronized(this@InkFlowApp) { routerInstance = fresh }
                old?.releaseAll()
                fresh.probeAll()
            }
        }
    }
}
