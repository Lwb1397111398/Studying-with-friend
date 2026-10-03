package com.studyfriend.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.studyfriend.app.ui.screens.BookOverviewScreen
import com.studyfriend.app.ui.screens.ChapterAssetsScreen
import com.studyfriend.app.ui.screens.ChapterListScreen
import com.studyfriend.app.ui.screens.ImportScreen
import com.studyfriend.app.ui.screens.ImportViewModel
import com.studyfriend.app.ui.screens.ReadScreen
import com.studyfriend.app.ui.screens.ReviewScreen
import com.studyfriend.app.ui.screens.SettingsScreen
import com.studyfriend.app.ui.screens.SettingsViewModel
import com.studyfriend.app.ui.screens.ShelfScreen
import com.studyfriend.app.ui.screens.TocConfirmScreen
import com.studyfriend.app.ui.screens.UpdateDialog
import com.studyfriend.app.ui.screens.UpdateViewModel

object Routes {
    const val SHELF = "shelf"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val IMPORT = "import"
    const val TOC = "toc"
    const val BOOK = "book/{bookId}"
    // highlight 为可选参数（P5 章节树）：节行点击带节标题，ReadScreen 滚动定位到该段
    const val READ = "read/{chapterId}?highlight={highlight}"
    const val SUMMARY = "summary/{chapterId}"
    const val OVERVIEW = "overview/{bookId}"

    fun book(bookId: Long) = "book/$bookId"
    fun read(chapterId: Long, highlight: String? = null) =
        if (highlight.isNullOrBlank()) "read/$chapterId"
        else "read/$chapterId?highlight=${android.net.Uri.encode(highlight)}"
    fun summary(chapterId: Long) = "summary/$chapterId"
    fun overview(bookId: Long) = "overview/$bookId"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.SHELF, "书架", Icons.Filled.Home),
    TabItem(Routes.REVIEW, "复习", Icons.Filled.Refresh),
    TabItem(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Activity 级共享：Import 与 TocConfirm 两屏用同一份解析状态
    val importVm: ImportViewModel = viewModel()
    // 应用自更新（M7）：Activity 级单例，弹窗挂全局任何页面都能弹；进 App 静默检查一次
    val updateVm: UpdateViewModel = viewModel()
    LaunchedEffect(Unit) { updateVm.autoCheckIfNeeded() }

    Scaffold(
        bottomBar = {
            // 导入/确认目录是子流程，不显示底部导航
            if (currentRoute in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        UpdateDialog(vm = updateVm)
        NavHost(
            navController = navController,
            startDestination = Routes.SHELF,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SHELF) {
                ShelfScreen(
                    onImport = {
                        // 上次流程可能中途返回残留状态，重新进入一律从零开始
                        importVm.reset()
                        navController.navigate(Routes.IMPORT)
                    },
                    onOpenBook = { bookId -> navController.navigate(Routes.book(bookId)) },
                )
            }
            composable(Routes.REVIEW) { ReviewScreen() }
            composable(Routes.SETTINGS) {
                val settingsVm: SettingsViewModel = viewModel()
                SettingsScreen(vm = settingsVm, updateVm = updateVm)
            }
            composable(Routes.IMPORT) {
                ImportScreen(vm = importVm, onNext = { navController.navigate(Routes.TOC) })
            }
            composable(Routes.TOC) {
                TocConfirmScreen(
                    vm = importVm,
                    onDone = {
                        android.util.Log.i("P5Nav", "④ onDone → popBackStack 回书架")
                        navController.popBackStack(Routes.SHELF, inclusive = false)
                    },
                )
            }
            composable(
                Routes.BOOK,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: 0L
                ChapterListScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    // P5 章节树：节行点击带节标题（highlight），跳父章并滚动定位
                    onOpenChapter = { chapterId, highlight ->
                        navController.navigate(Routes.read(chapterId, highlight))
                    },
                    onOpenOverview = { navController.navigate(Routes.overview(bookId)) },
                )
            }
            composable(
                Routes.READ,
                arguments = listOf(
                    navArgument("chapterId") { type = NavType.LongType },
                    navArgument("highlight") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val chapterId = entry.arguments?.getLong("chapterId") ?: 0L
                val highlight = entry.arguments?.getString("highlight")
                ReadScreen(
                    chapterId = chapterId,
                    highlight = highlight,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSummary = { navController.navigate(Routes.summary(chapterId)) },
                    onOpenChapter = { navController.navigate(Routes.read(it)) },
                )
            }
            composable(
                Routes.SUMMARY,
                arguments = listOf(navArgument("chapterId") { type = NavType.LongType }),
            ) { entry ->
                val chapterId = entry.arguments?.getLong("chapterId") ?: 0L
                ChapterAssetsScreen(
                    chapterId = chapterId,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(
                Routes.OVERVIEW,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: 0L
                BookOverviewScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
        }
    }
}
