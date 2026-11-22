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
import androidx.compose.runtime.DisposableEffect
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
import com.studyfriend.app.data.db.DbValues
import com.studyfriend.app.ui.screens.BookOverviewScreen
import com.studyfriend.app.ui.screens.ChapterAssetsScreen
import com.studyfriend.app.ui.screens.ChapterListScreen
import com.studyfriend.app.ui.screens.ImportScreen
import com.studyfriend.app.ui.screens.ImportViewModel
import com.studyfriend.app.ui.screens.ModelsScreen
import com.studyfriend.app.ui.screens.ModelsViewModel
import com.studyfriend.app.ui.screens.ReadScreen
import com.studyfriend.app.ui.screens.ReviewScreen
import com.studyfriend.app.ui.screens.SearchScreen
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
    // 模型页（设置页入口的二级页）：全部 AI 模型配置收拢于此
    const val MODELS = "models"
    const val IMPORT = "import"
    const val TOC = "toc"
    const val BOOK = "book/{bookId}"
    // highlight 为可选参数（P5 章节树）：节行点击带节标题，ReadScreen 滚动定位到该段
    // paraId 为可选参数（功能补齐轮全书搜索）：点搜索结果直接滚到命中段，优先级高于 highlight
    // fresh 为可选参数（第九波切章落章头）：true=箭头/章尾卡/抽屉跳章，落章头不恢复旧位置
    const val READ = "read/{chapterId}?highlight={highlight}&paraId={paraId}&fresh={fresh}"
    const val SUMMARY = "summary/{chapterId}"
    const val OVERVIEW = "overview/{bookId}"
    // v7 全书搜索页：bookId 限定搜索范围
    const val SEARCH = "search/{bookId}"

    fun book(bookId: Long) = "book/$bookId"
    fun read(chapterId: Long, highlight: String? = null, paraId: Long = -1L, fresh: Boolean = false): String {
        val params = buildList {
            if (!highlight.isNullOrBlank()) add("highlight=${android.net.Uri.encode(highlight)}")
            if (paraId >= 0) add("paraId=$paraId")
            if (fresh) add("fresh=true")
        }
        return if (params.isEmpty()) "read/$chapterId" else "read/$chapterId?${params.joinToString("&")}"
    }
    fun summary(chapterId: Long) = "summary/$chapterId"
    fun overview(bookId: Long) = "overview/$bookId"
    fun search(bookId: Long) = "search/$bookId"
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

    // 批B：屏幕常亮从 ImportScreen 上移到应用级——OCR 期间用户可离开导入页让识别
    // 继续跑（App 在前台任意页面都算），黑屏进程被冻结就会中断；busy 结束自动解除
    val keepOnView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(importVm.busy) {
        if (importVm.busy) keepOnView.keepScreenOn = true
        onDispose { keepOnView.keepScreenOn = false }
    }
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
                    // 体验优化轮：点书直达上次阅读位置——有阅读记忆（解析完成+lastReadChapterId）
                    // 直接进阅读页（进页后位置记忆恢复到段）；没读过的书进章目录页挑选
                    onOpenBook = { book ->
                        val cid = book.lastReadChapterId
                        if (book.status == DbValues.BOOK_READY && cid != null) {
                            navController.navigate(Routes.read(cid))
                        } else {
                            navController.navigate(Routes.book(book.id))
                        }
                    },
                    // 长按菜单「查看章节目录」：全书搜索/总览的入口还在章目录页
                    onOpenChapters = { bookId -> navController.navigate(Routes.book(bookId)) },
                    onContinueRead = { chapterId -> navController.navigate(Routes.read(chapterId)) },
                )
            }
            composable(Routes.REVIEW) {
                ReviewScreen(
                    // 空态续读卡（第八波）：与书架「继续读」同款直达——Routes.read 进页后位置记忆恢复到段
                    onContinueRead = { chapterId -> navController.navigate(Routes.read(chapterId)) },
                )
            }
            composable(Routes.SETTINGS) {
                val settingsVm: SettingsViewModel = viewModel()
                SettingsScreen(
                    vm = settingsVm,
                    updateVm = updateVm,
                    onOpenModels = { navController.navigate(Routes.MODELS) },
                )
            }
            composable(Routes.MODELS) {
                val modelsVm: ModelsViewModel = viewModel()
                ModelsScreen(vm = modelsVm, onBack = { navController.popBackStack() })
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
                    onOpenSearch = { navController.navigate(Routes.search(bookId)) },
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
                    navArgument("paraId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    // 第九波切章落章头：箭头/章尾卡/抽屉跳章（paraId=-1 口径）落章头不恢复旧位置
                    navArgument("fresh") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                val chapterId = entry.arguments?.getLong("chapterId") ?: 0L
                val highlight = entry.arguments?.getString("highlight")
                val paraId = entry.arguments?.getLong("paraId") ?: -1L
                val freshOpen = entry.arguments?.getBoolean("fresh") ?: false
                ReadScreen(
                    chapterId = chapterId,
                    highlight = highlight,
                    scrollParaId = paraId,
                    freshOpen = freshOpen,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSummary = { navController.navigate(Routes.summary(chapterId)) },
                    onOpenChapter = { target, pid ->
                        // paraId=-1 = 箭头/章尾卡/抽屉跳章：fresh 落章头；带段锚（书签/批注）照旧定位
                        navController.navigate(Routes.read(target, paraId = pid, fresh = pid < 0))
                    },
                )
            }
            composable(
                Routes.SEARCH,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: 0L
                SearchScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onOpenHit = { chapterId, paraId ->
                        navController.navigate(Routes.read(chapterId, paraId = paraId))
                    },
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
                    onOpenChapter = { cid -> navController.navigate(Routes.read(cid)) },
                    // P1-③ 引用溯源：总结/串联胶囊跳阅读页指定段（ReadScreen scrollParaId 消费）
                    onOpenPara = { cid, pid -> navController.navigate(Routes.read(cid, paraId = pid)) },
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
                    onOpenChapter = { chapterId -> navController.navigate(Routes.read(chapterId)) },
                )
            }
        }
    }
}
