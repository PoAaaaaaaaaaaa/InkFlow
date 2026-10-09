package com.inkflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.inkflow.app.ui.MemoryScreen
import com.inkflow.app.ui.NovelListScreen
import com.inkflow.app.ui.SettingsScreen
import com.inkflow.app.ui.WriterScreen
import com.inkflow.app.ui.mvi.WriterViewModel
import com.inkflow.app.ui.theme.InkFlowTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as InkFlowApp

        setContent {
            InkFlowTheme {
                InkFlowNavHost(app)
            }
        }
    }
}

@Composable
private fun InkFlowNavHost(app: InkFlowApp) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            NovelListScreen(
                repo = app.repository,
                settings = app.settingsStore,
                onOpenProject = { projectId ->
                    app.appScope.let { }
                    navController.navigate(Routes.writer(projectId))
                },
                onOpenSettings = { navController.navigate(Routes.settings()) },
            )
        }

        composable(
            route = Routes.WRITER,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("chapterId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            val chapterId = entry.arguments?.getString("chapterId").orEmpty().ifBlank { null }

            val vm: WriterViewModel = viewModel(factory = WriterViewModel.factory(app))
            val readerPrefs by app.settingsStore.readerPrefs.collectAsState(
                initial = com.inkflow.app.data.ReaderPrefs()
            )
            WriterScreen(
                viewModel = vm,
                projectId = projectId,
                chapterId = chapterId,
                repo = app.repository,
                onBack = { navController.popBackStack() },
                onOpenSettings = {
                    navController.navigate(Routes.settings(projectId, vm.state.value.projectTitle))
                },
                onOpenMemory = {
                    navController.navigate(Routes.memory(projectId, vm.state.value.projectTitle))
                },
                readerPrefs = readerPrefs,
                onReaderPrefsChange = { updated ->
                    app.appScope.launch { app.settingsStore.saveReaderPrefs(updated) }
                },
            )
        }

        composable(
            route = Routes.MEMORY,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            val title = runCatching {
                java.net.URLDecoder.decode(entry.arguments?.getString("title").orEmpty(), "UTF-8")
            }.getOrDefault("")
            MemoryScreen(
                app = app,
                projectId = projectId,
                projectTitle = title,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.SETTINGS,
            arguments = listOf(
                navArgument("projectId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("projectTitle") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val pid = entry.arguments?.getString("projectId").orEmpty()
            val pTitle = runCatching {
                java.net.URLDecoder.decode(entry.arguments?.getString("projectTitle").orEmpty(), "UTF-8")
            }.getOrDefault("")
            SettingsScreen(
                app = app,
                onBack = { navController.popBackStack() },
                onOpenMemory = if (pid.isNotBlank()) {
                    { navController.navigate(Routes.memory(pid, pTitle)) }
                } else null,
            )
        }
    }
}

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings?projectId={projectId}&projectTitle={projectTitle}"
    const val MEMORY = "memory/{projectId}?title={title}"
    const val WRITER = "writer/{projectId}?chapterId={chapterId}"

    fun writer(projectId: String, chapterId: String? = null): String =
        "writer/$projectId?chapterId=${chapterId.orEmpty()}"

    fun memory(projectId: String, title: String): String =
        "memory/$projectId?title=${java.net.URLEncoder.encode(title, "UTF-8")}"

    fun settings(projectId: String = "", projectTitle: String = ""): String =
        "settings?projectId=$projectId&projectTitle=${java.net.URLEncoder.encode(projectTitle, "UTF-8")}"
}
