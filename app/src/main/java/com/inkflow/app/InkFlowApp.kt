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
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
    }

    val repository: NovelRepository by lazy { NovelRepository(database) }

    val settingsStore: SettingsStore by lazy { SettingsStore(this) }

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
