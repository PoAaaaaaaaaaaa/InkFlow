package com.inkflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
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
            WriterScreen(
                viewModel = vm,
                projectId = projectId,
                chapterId = chapterId,
                repo = app.repository,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                app = app,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val WRITER = "writer/{projectId}?chapterId={chapterId}"

    fun writer(projectId: String, chapterId: String? = null): String =
        "writer/$projectId?chapterId=${chapterId.orEmpty()}"
}
