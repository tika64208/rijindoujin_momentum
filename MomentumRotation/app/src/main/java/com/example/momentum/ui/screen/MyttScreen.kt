package com.example.momentum.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.momentum.data.WatchlistStore
import com.example.momentum.ui.component.TabDocSheet
import com.example.momentum.ui.component.TabDocs
import com.example.momentum.ui.theme.*
import android.widget.Toast

private fun formatClose(v: Double): String = String.format("%.2f", v)

private fun formatPct(v: Double?): String {
    if (v == null) return "-"
    val sign = if (v > 0) "+" else ""
    return "$sign${String.format("%.1f", v)}%"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyttScreen(
    onBack: () -> Unit,
    onStockClick: (code: String, name: String) -> Unit = { _, _ -> },
    viewModel: MyttViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var showDocSheet by remember { mutableStateOf(false) }

    if (showDocSheet) {
        TabDocSheet(
            data = TabDocs.MyttDoc,
            onDismiss = { showDocSheet = false }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("万龙选股 (万手单 × 寻龙诀)", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showDocSheet = true }) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "说明文档",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Blue700, titleContentColor = Color.White
                )
            )
        },
        containerColor = SurfaceBg
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // 顶部后验统计看板
            state.stats?.let { stats ->
                if (stats.totalSignals > 0) {
                    WanlongStatsBanner(stats)
                }
            }

            // 运行/增量刷新按钮
            Button(
                onClick = { viewModel.runSelect() },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue700),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("选股中 ${state.currentDate} ${state.scannedCount}/${state.candidateCount}", fontSize = 13.sp)
                } else {
                    Text("运行万龙选股 / 增量刷新", fontSize = 14.sp)
                }
            }

            state.error?.let {
                Text("⚠ $it", color = Red600, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }

            if (state.loading) {
                LinearProgressIndicator(
                    progress = { if (state.candidateCount > 0) state.scannedCount.toFloat() / state.candidateCount else 0f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    color = Blue700
                )
            }

            if (!state.loading && state.dayResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("点击上方按钮，即可极速扫描并缓存万龙共振股票", color = Color.Gray, fontSize = 13.sp)
                }
            }

            if (state.dayResults.isNotEmpty()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 🐉 双龙出水独立表格: 任意两次万龙信号间隔≤90天的股票单独成表(不要求是否今天)。
                    // 置于可滚动列表首项: 支持任意行数, 行多时可滚动查看, 不会挤爆屏幕或顶掉按钮。
                    if ((state.stats?.totalSignals ?: 0) > 0) {
                        item(key = "double_dragon") {
                            DoubleDragonCard(items = state.doubleDragonItems, onStockClick = onStockClick)
                        }
                    }
                    items(state.dayResults, key = { it.date }) { day ->
                        WanlongDayCard(day, onStockClick = onStockClick)
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

/**
 * 🐉 双龙出水独立表格: 同一股票任意两次万龙信号间隔 ≤90 天(不要求是否今天, 历史信号同样计入)。
 * 双龙先后出水代表庄家试盘明显、近期有动作 —— 宜择机进场;
 * 信号可靠性显著高于单次触发, 因此在页面顶部单独成表呈现, 并支持一键全部加入自选。
 */
@Composable
private fun DoubleDragonCard(
    items: List<DoubleDragonItem>,
    onStockClick: (code: String, name: String) -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            // 标题行: 双龙出水 + 只数 + 一键全部加入自选(空态时隐藏按钮)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🐉 双龙出水", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFC2410C))
                    Spacer(Modifier.width(4.dp))
                    Text("${items.size}只", fontSize = 11.sp, color = Color(0xFF9A3412))
                }
                if (items.isNotEmpty()) {
                    Surface(
                        color = Color(0xFFEA580C),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable {
                            val added = WatchlistStore.addAll(context, items.map { it.code to it.name })
                            val msg = if (added > 0) "已将 $added 只双龙出水加入自选" else "双龙出水股票均已在本页自选中"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text(
                            "＋ 全部自选",
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Text("庄家试盘明显 · 近期有动作 · 择机进场 —— 任意两次万龙信号间隔 ≤90 天即上榜(不限是否今天)", fontSize = 10.sp, color = Color(0xFF9A3412))

            Spacer(Modifier.height(6.dp))

            if (items.isEmpty()) {
                // 空态: 无二次命中股票时常驻提示, 而非整块消失
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFEDD5), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🌊", fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "暂无双龙出水股票 — 同一股票任意两次万龙信号间隔 ≤90 天即自动上榜(不限是否今天)",
                        fontSize = 11.sp, color = Color(0xFF9A3412), lineHeight = 15.sp
                    )
                }
            } else {
                // 表格表头
                DoubleDragonHeaderRow()
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color(0xFFFED7AA))

                // 表格行
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Color(0xFFFFEDD5))
                    }
                    DoubleDragonRow(item, onStockClick = onStockClick)
                }
            }
        }
    }
}

/** 双龙出水表头: 股票 | 次数 | 信号日 | 最新价 | 次日 (与数据行列宽严格对齐)。 */
@Composable
private fun DoubleDragonHeaderRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("股票", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A3412), modifier = Modifier.weight(1.3f))
        Text("次数", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A3412), modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
        Text("信号日", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A3412), modifier = Modifier.weight(1.7f))
        Text("最新价", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A3412), modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        Text("次日", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A3412), modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
    }
}

/** 双龙出水表格行: 名称/代码、次数徽章、信号日期、最新信号日收盘价、最近信号日 T+1 表现。 */
@Composable
private fun DoubleDragonRow(
    item: DoubleDragonItem,
    onStockClick: (code: String, name: String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onStockClick(item.code, item.name) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 列1 股票: 名称 + 代码
        Column(Modifier.weight(1.3f)) {
            Text(
                item.name,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Blue700,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(item.code, fontSize = 9.sp, color = Color.Gray)
        }
        // 列2 次数徽章
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            Surface(
                color = if (item.hitCount >= 3) Color(0xFFDC2626) else Color(0xFFEA580C),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "${item.hitCount}次",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        // 列3 信号日(新→旧, 最多展示2个, 更多以 +N 折叠)
        Row(
            Modifier.weight(1.7f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item.dates.take(2).forEach { d ->
                Text(
                    d.takeLast(5),
                    fontSize = 10.sp, color = Color(0xFF9A3412),
                    modifier = Modifier.background(Color(0xFFFFEDD5), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            if (item.dates.size > 2) {
                Text("+${item.dates.size - 2}", fontSize = 10.sp, color = Color.Gray)
            }
        }
        // 列4 最新信号日收盘价
        Text(
            formatClose(item.lastClose),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(48.dp), textAlign = TextAlign.End
        )
        // 列5 最近信号日的次日(T+1)表现
        val t1 = item.latestT1
        Text(
            t1?.let { formatPct(it) } ?: "—",
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = when {
                t1 == null -> Color.Gray
                t1 >= 0 -> Red600
                else -> Green600
            },
            modifier = Modifier.width(48.dp), textAlign = TextAlign.End
        )
    }
}

/**
 * 历史信号后验表现统计看板。
 */
@Composable
private fun WanlongStatsBanner(stats: WanlongStats) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📈 万龙信号后验收益统计", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Blue700)
                Text("样本: ${stats.totalSignals}只 (已评估${stats.evaluatedCount}只)", fontSize = 10.sp, color = Color.Gray)
            }

            Spacer(Modifier.height(6.dp))

            if (stats.evaluatedCount > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatMetricColumn("T+1 胜率", "${String.format("%.1f", stats.t1WinRate)}%", "均益 ${formatPct(stats.t1AvgReturn)}")
                    StatMetricColumn("T+2 胜率", "${String.format("%.1f", stats.t2WinRate)}%", "均益 ${formatPct(stats.t2AvgReturn)}")
                    StatMetricColumn("T+3 胜率", "${String.format("%.1f", stats.t3WinRate)}%", "均益 ${formatPct(stats.t3AvgReturn)}")
                    StatMetricColumn("最高冲高", "+${String.format("%.1f", stats.avgMaxReturn)}%", "区间最大")
                }
            } else {
                Text("暂无后续交易日数据 (近期最新选出)", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun StatMetricColumn(title: String, mainVal: String, subVal: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 11.sp, color = Color.DarkGray)
        Text(mainVal, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Red600)
        Text(subVal, fontSize = 10.sp, color = Color.Gray)
    }
}

/**
 * 某一交易日万龙选股卡片。
 */
@Composable
private fun WanlongDayCard(
    day: WanlongDayResult,
    onStockClick: (code: String, name: String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📅 ${day.date}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Blue700)
                Text("共振命中: ${day.items.size} 只", fontSize = 12.sp, color = if (day.items.isNotEmpty()) Green600 else Color.Gray, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(8.dp))

            if (day.items.isEmpty()) {
                Text("当天无同时命中万手单与寻龙诀的股票", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
            } else {
                day.items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFFF1F5F9))
                    }
                    WanlongItemRow(item, onStockClick = onStockClick)
                }
            }
        }
    }
}

@Composable
private fun WanlongItemRow(
    item: WanlongItem,
    onStockClick: (code: String, name: String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onStockClick(item.code, item.name) }
            .padding(vertical = 4.dp)
    ) {
        // 第一行：股票基础信息
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("★", color = Green600, fontSize = 14.sp)
            Spacer(Modifier.width(4.dp))
            Text(
                text = item.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Blue700,
                modifier = Modifier.weight(1f)
            )
            Text(item.code, fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.width(10.dp))
            Text("${formatClose(item.close)} 元", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (item.chgPct != 0.0) {
                Spacer(Modifier.width(4.dp))
                Text(formatPct(item.chgPct), fontSize = 11.sp, color = Red600)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = Color.LightGray,
                modifier = Modifier.size(12.dp)
            )
        }

        Spacer(Modifier.height(4.dp))

        // 第二行：信号后 N 日真实涨跌表现跟踪标签
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.t1Return == null && item.t2Return == null && item.maxReturn == null) {
                ReturnBadge("T+1 跟踪中...", Color.Gray, Color(0xFFF1F5F9))
            } else {
                item.t1Return?.let {
                    ReturnBadge("T+1: ${formatPct(it)}", if (it >= 0) Red600 else Green600, if (it >= 0) RedContainer else GreenContainer)
                }
                item.t2Return?.let {
                    ReturnBadge("T+2: ${formatPct(it)}", if (it >= 0) Red600 else Green600, if (it >= 0) RedContainer else GreenContainer)
                }
                item.t3Return?.let {
                    ReturnBadge("T+3: ${formatPct(it)}", if (it >= 0) Red600 else Green600, if (it >= 0) RedContainer else GreenContainer)
                }
                item.maxReturn?.let {
                    ReturnBadge("最高: +${String.format("%.1f", it)}%", Amber600, AmberContainer)
                }
            }
        }
    }
}

@Composable
private fun ReturnBadge(text: String, textColor: Color, bgColor: Color) {
    Text(
        text = text,
        color = textColor,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
