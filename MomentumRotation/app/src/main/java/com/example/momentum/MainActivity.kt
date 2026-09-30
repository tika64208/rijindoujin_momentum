package com.example.momentum

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.momentum.ui.screen.BidAnalysisScreen
import com.example.momentum.ui.screen.DarkTradeListScreen
import com.example.momentum.ui.screen.DownloadState
import com.example.momentum.ui.screen.DownloadViewModel
import com.example.momentum.ui.screen.MomentumScreen
import com.example.momentum.ui.screen.MomentumViewModel
import com.example.momentum.ui.screen.MyttScreen
import com.example.momentum.ui.screen.SettingsScreen
import com.example.momentum.ui.screen.StockDetailScreen
import com.example.momentum.ui.theme.SurfaceBg

/**
 * 底部导航的两个主视图 tab。
 */
private data class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val mainTabs = listOf(
    MainTab("main/watchlist", "自选股", Icons.AutoMirrored.Filled.List),
    MainTab("main/darktrade", "全量暗盘", Icons.AutoMirrored.Filled.ShowChart),
    MainTab("main/myatt", "MYTT选股", Icons.Filled.Search),
    MainTab("main/bid", "竞价分析", Icons.Filled.Star)
)

class MainActivity : ComponentActivity() {

    // Android 13+ 通知权限请求
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 请求通知权限(Android 13+), 低版本无需
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SurfaceBg
                ) {
                    AppNavHost()
                }
            }
        }
    }
}

/**
 * 应用导航: NavHost + 底部导航。
 * - 底部导航两个 tab: 自选股 / 全量暗盘
 * - 详情页(单股图表) 与设置页为二级 route
 * 返回栈由 Navigation Compose 自动管理, 状态可跨旋转/重建保留。
 */
@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 全 A 股日线增量下载(手动触发)
    val downloadVm: DownloadViewModel = viewModel()
    val downloadState by downloadVm.state.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val targetTab = (context as? android.app.Activity)?.intent?.getStringExtra("tab")
        if (targetTab != null) {
            val route = if (targetTab.startsWith("main/")) targetTab else "main/$targetTab"
            if (mainTabs.any { it.route == route }) {
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    // 底部导航仅在主 tab 页显示(详情/设置页隐藏)
    val showBottomBar = currentRoute == mainTabs[0].route || currentRoute == mainTabs[1].route 
        || currentRoute == mainTabs[2].route || currentRoute == mainTabs[3].route

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = SurfaceBg,
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        mainTabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = mainTabs[0].route,
                modifier = Modifier.padding(padding)
            ) {
            // 自选股 tab
            composable(mainTabs[0].route) {
                val viewModel: MomentumViewModel = viewModel()
                MomentumScreen(
                    viewModel = viewModel,
                    downloadVm = downloadVm,
                    downloadState = downloadState,
                    onOpenSettings = { navController.navigate("settings") },
                    onStockClick = { code, name ->
                        navController.navigate("detail/$code/$name")
                    }
                )
            }
            // 全量暗盘 tab
            composable(mainTabs[1].route) {
                DarkTradeListScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            // MYTT 选股 tab
            composable(mainTabs[2].route) {
                MyttScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            // 竞价分析 tab
            composable(mainTabs[3].route) {
                BidAnalysisScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            // 股票详情(二级)
            composable(
                route = "detail/{code}/{name}",
                arguments = listOf(
                    navArgument("code") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType }
                )
            ) { entry ->
                val code = entry.arguments?.getString("code") ?: ""
                val name = entry.arguments?.getString("name") ?: ""
                StockDetailScreen(
                    code = code,
                    name = name,
                    onBack = { navController.popBackStack() }
                )
            }
            // 设置(二级)
            composable("settings") {
                val viewModel: MomentumViewModel = viewModel()
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
        }
        }

        // 下载进度条(顶部覆盖, 仅下载中显示)
        if (downloadState.running) {
            DownloadProgressBar(downloadState)
        }
    }
}

@Composable
private fun DownloadProgressBar(state: DownloadState) {
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1D4ED8))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "更新行情 ${state.progressPercent}%  (${state.done}/${state.total})",
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            if (state.currentCode.isNotEmpty()) {
                Text(
                    "${state.currentName} ${state.currentCode}",
                    color = Color(0xFFBFDBFE), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { state.progressPercent / 100f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Color.White,
            trackColor = Color(0xFF3B82F6)
        )
    }
}
