package com.example.momentum.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.momentum.data.DataOrigin
import com.example.momentum.model.Recommendation
import com.example.momentum.model.Tier
import com.example.momentum.ui.component.TabDocSheet
import com.example.momentum.ui.component.TabDocs
import com.example.momentum.ui.theme.*
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentumScreen(
    viewModel: MomentumViewModel = viewModel(),
    downloadVm: DownloadViewModel = viewModel(),
    downloadState: DownloadState = DownloadState(),
    onOpenSettings: () -> Unit = {},
    onStockClick: (code: String, name: String) -> Unit = { _, _ -> }
) {
    val state by viewModel.state.collectAsState()
    var showDocSheet by remember { mutableStateOf(false) }

    if (showDocSheet) {
        TabDocSheet(
            data = TabDocs.WatchlistDoc,
            onDismiss = { showDocSheet = false }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("动量轮动选股助手", fontWeight = FontWeight.Bold)
                },
                actions = {
                    // 下载日线按钮
                    if (downloadState.running) {
                        Text("更新 ${downloadState.progressPercent}%", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                    }
                    TextButton(
                        onClick = { downloadVm.startIfIdle() },
                        enabled = !downloadState.running
                    ) {
                        Text(if (downloadState.running) "下载中…" else "下载日线", fontSize = 11.sp, color = Color.White)
                    }
                    IconButton(onClick = { showDocSheet = true }) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "说明文档",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Blue700,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = SurfaceBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // 输入区(支持名称/代码搜索)
            AddStockBar(
                keyword = state.inputCode,
                searching = state.searching,
                searchResults = state.searchResults,
                onKeywordChange = { viewModel.updateInput(it) },
                onAdd = { viewModel.addStock() },
                onPickResult = { viewModel.addStockFromSearch(it) }
            )

            state.error?.let { err ->
                Text(
                    text = "⚠ $err",
                    color = Red600,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // 运行按钮
            Button(
                onClick = { viewModel.runRanking() },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue700)
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("计算中…")
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("轮动计算")
                }
            }

            // 分析按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.runTrendAnalysis() },
                    enabled = !state.analyzing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber600)
                ) {
                    if (state.analyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("动量预警", fontSize = 13.sp)
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.runBacktest() },
                    enabled = !state.analyzing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Green600)
                ) {
                    if (state.analyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("回测对比", fontSize = 13.sp)
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.runEquityCurve() },
                    enabled = !state.analyzing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Blue700)
                ) {
                    if (state.analyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("收益曲线", fontSize = 13.sp)
                    }
                }
            }

            // 主内容
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item { WatchlistSection(state.watchlist, viewModel::removeStock, onStockClick) }

                if (state.recommendations.isNotEmpty()) {
                    item { DataOriginBadge(state.dataOrigin) }
                    item { RecommendationSection(state.recommendations) }
                }

                if (state.trends.isNotEmpty()) {
                    item { MomentumTrendPanel(state.trends) }
                }

                state.backtest?.let { bt ->
                    item { BacktestPanel(bt) }
                }

                state.equityCurve?.let { curve ->
                    item {
                        EquityCurvePanel(
                            curveSet = curve,
                            onSegmentChange = { start, end ->
                                viewModel.updateCurveSegment(start, end)
                            },
                            segmentResults = state.curveSegment
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddStockBar(
    keyword: String,
    searching: Boolean,
    searchResults: List<com.example.momentum.data.TencentDataSource.SearchResult>,
    onKeywordChange: (String) -> Unit,
    onAdd: () -> Unit,
    onPickResult: (com.example.momentum.data.TencentDataSource.SearchResult) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier.weight(1f),
                label = { Text("搜索股票名称/代码", fontSize = 13.sp) },
                trailingIcon = {
                    if (searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )
            FilledIconButton(
                onClick = onAdd,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Green600)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加(按代码)", tint = Color.White)
            }
        }

        // 搜索候选下拉
        if (searchResults.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    searchResults.take(8).forEach { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickResult(r) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                r.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(r.code, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataOriginBadge(origin: DataOrigin?) {
    if (origin == null) return
    val (text, color, bg) = when (origin) {
        DataOrigin.FRESH -> Triple("已联网更新", Green600, GreenContainer)
        DataOrigin.CACHE -> Triple("本地缓存(当日已更新)", Blue700, Color(0xFFDBEAFE))
        DataOrigin.OFFLINE -> Triple("离线模式(使用本地缓存数据)", Amber600, AmberContainer)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WatchlistSection(
    watchlist: List<com.example.momentum.model.Stock>,
    onRemove: (String) -> Unit,
    onClick: (code: String, name: String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "自选股池 (${watchlist.size}) (点击查看分时图)",
                fontWeight = FontWeight.SemiBold,
                color = Blue700,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            if (watchlist.isEmpty()) {
                Text("暂无自选股，请在上面添加", fontSize = 13.sp, color = Color.Gray)
            }
            watchlist.forEach { stock ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClick(stock.code, stock.name) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stock.name,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(stock.code, fontSize = 12.sp, color = Color.Gray)
                    IconButton(onClick = { onRemove(stock.code) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationSection(recs: List<Recommendation>) {
    val groups = recs.groupBy { it.tier }
    val order = listOf(Tier.BUY, Tier.HOLD, Tier.SELL)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEach { tier ->
            val list = groups[tier] ?: return@forEach
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (tier) {
                        Tier.BUY -> GreenContainer
                        Tier.HOLD -> AmberContainer
                        Tier.SELL -> RedContainer
                    }
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    val (label, color) = when (tier) {
                        Tier.BUY -> "🟢 买入/加仓" to Green600
                        Tier.HOLD -> "🟡 持有/观察" to Amber600
                        Tier.SELL -> "🔴 卖出/减仓" to Red600
                    }
                    Text(
                        "$label (${list.size})",
                        fontWeight = FontWeight.Bold,
                        color = color,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    list.sortedByDescending { it.score }.forEach { r ->
                        RecommendationRow(r)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationRow(r: Recommendation) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${r.rank}.",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.width(28.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(r.code, fontSize = 11.sp, color = Color.Gray)
            }
            Text(
                "动量分 ${formatScore(r.score)}  ·  12月收益 ${formatPct(r.r12m)}",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
        Text(
            formatPct(r.r12m),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (r.r12m >= 0) Green600 else Red600
        )
    }
}

private fun formatScore(v: Double): String =
    if (v.isNaN()) "—" else String.format("%.2f", v)

private fun formatPct(v: Double): String =
    if (v.isNaN()) "—" else String.format("%+.1f%%", v * 100)
