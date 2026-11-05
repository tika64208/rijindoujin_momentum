package com.example.momentum.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.momentum.engine.BidDistribution
import com.example.momentum.engine.BidDistributionBin
import com.example.momentum.engine.BidDistributionSource
import com.example.momentum.ui.theme.Blue700
import com.example.momentum.ui.theme.Green600
import com.example.momentum.ui.theme.Red600
import com.example.momentum.ui.theme.SurfaceBg
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BidDistributionScreen(onBack: () -> Unit, viewModel: BidDistributionViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var datesExpanded by remember { mutableStateOf(false) }
    var showMethodology by remember { mutableStateOf(false) }
    val selectedIndex = state.dates.indexOf(state.selectedDate)
    val report = state.report

    if (showMethodology) {
        AlertDialog(onDismissRequest = { showMethodology = false }, title = { Text("统计口径") },
            text = { Text("每 1% 分档，0% 单列；区间左端包含、右端不含，(0,1) 不包含 0。占比以全部样本为分母，均值和中位数只使用有效涨幅。\n\n普通历史未经 09:30 完整性校验，旧记录中的 0 可能包含当时接口缺失值。\n\n看板只读取本地全量数据，不受筛选条件影响；刷新只重读本地数据，存盘数据不变。") },
            confirmButton = { TextButton(onClick = { showMethodology = false }) { Text("知道了") } })
    }

    Scaffold(
        containerColor = SurfaceBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("竞价涨幅分布", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                } },
                actions = { IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                    Icon(Icons.Filled.Refresh, "重新读取本地数据")
                } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Blue700,
                    titleContentColor = Color.White, navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White)
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.selectDate(state.dates[selectedIndex + 1]) },
                        enabled = selectedIndex >= 0 && selectedIndex < state.dates.lastIndex && !state.loading) { Text("‹ 前一日") }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { datesExpanded = true }, enabled = state.dates.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) { Text(state.selectedDate ?: "选择历史日期") }
                        DropdownMenu(expanded = datesExpanded, onDismissRequest = { datesExpanded = false },
                            modifier = Modifier.heightIn(max = 360.dp)) {
                            state.dates.forEach { date -> DropdownMenuItem(text = { Text(
                                if (date == state.selectedDate) "$date  ✓" else date) }, onClick = {
                                datesExpanded = false
                                viewModel.selectDate(date)
                            }) }
                        }
                    }
                    TextButton(onClick = { viewModel.selectDate(state.dates[selectedIndex - 1]) },
                        enabled = selectedIndex > 0 && !state.loading) { Text("后一日 ›") }
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { Text(error, color = Color(0xFFB91C1C), fontSize = 12.sp) } }
            state.message?.let { message -> item { Text(message, color = Blue700, fontSize = 12.sp) } }
            if (!state.loading && report == null && state.error == null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("暂无本地竞价历史", fontWeight = FontWeight.Bold)
                            Text("09:30 全市场采集存盘后即可在这里按日期查看；已有的普通竞价历史也会自动显示。",
                                color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (report != null) {
                item { DistributionOverview(report) }
                item { DistributionTable(report, onMethodology = { showMethodology = true }) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { viewModel.save() }, enabled = !state.saving && !state.loading,
                            modifier = Modifier.weight(1f)) {
                            Text(if (state.saving) "正在存盘…" else if (report.savedAt == null) "存盘 CSV / JSON" else "恢复存盘文件")
                        }
                        OutlinedButton(onClick = { viewModel.save(shareAfter = true) },
                            enabled = !state.saving && !state.loading) { Text("分享分布") }
                    }
                    Text("存盘目录：Download/MomentumRotation/bid_distribution/", color = Color.Gray,
                        fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun DistributionOverview(report: BidDistribution) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(report.source.title, fontWeight = FontWeight.Bold, lineHeight = 20.sp, color = Blue700)
                Text(if (report.savedAt == null) "可存盘" else "分布已存盘", fontSize = 12.sp, color = Color.Gray)
            }
            Text("全样本 ${report.total} 只 · 原数据时间 ${report.sourceTime.ifBlank { "未记录" }}",
                fontSize = 11.sp, lineHeight = 14.sp, color = Color.Gray)
            if (report.source == BidDistributionSource.LEGACY) {
                Text("普通历史 · 未验证 09:30 完整性",
                    fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFF92400E))
            } else {
                Text("09:30 冻结样本，后续盘中刷新不会覆盖。", fontSize = 11.sp, lineHeight = 14.sp, color = Color.Gray)
            }
            Row(Modifier.fillMaxWidth()) {
                DistributionStat("高开", report.up, report, Red600, Modifier.weight(1f))
                DistributionStat("平开", report.flat, report, Blue700, Modifier.weight(1f))
                DistributionStat("低开", report.down, report, Green600, Modifier.weight(1f))
                DistributionStat("缺失", report.missing, report, Color.Gray, Modifier.weight(1f))
            }
            HorizontalDivider()
            Text("平均竞价涨幅 ${pct(report.average)}   ·   中位数 ${pct(report.median)}",
                fontSize = 12.sp, lineHeight = 16.sp, color = Color.DarkGray)
        }
    }
}

@Composable
private fun DistributionStat(label: String, count: Int, report: BidDistribution, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 11.sp)
        Text(count.toString(), fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 22.sp, color = color)
        Text(String.format(Locale.ROOT, "%.1f%%", report.percentage(count)), fontSize = 10.sp, lineHeight = 13.sp, color = color)
    }
}

@Composable
private fun DistributionTable(report: BidDistribution, onMethodology: () -> Unit) {
    // 极端区间并排，逐行向 0% 靠拢；0% 和缺失单列在末行，全部 24 档均保留。
    val falling = report.bins.filter { it.direction < 0 }
    val rising = report.bins.filter { it.direction > 0 }.reversed()
    val neutral = report.bins.filter { it.direction == 0 }
    val peak = report.bins.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("区间分布（%）", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                TextButton(onClick = onMethodology, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("口径说明", fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("低开 · 数量 / 占比", color = Green600, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
                Text("高开 · 数量 / 占比", color = Red600, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
            }
            for (i in 0 until maxOf(falling.size, rising.size)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DistributionCell(falling.getOrNull(i), report, peak, Modifier.weight(1f))
                    DistributionCell(rising.getOrNull(i), report, peak, Modifier.weight(1f))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                neutral.forEach { DistributionCell(it, report, peak, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DistributionCell(bin: BidDistributionBin?, report: BidDistribution, peak: Int, modifier: Modifier) {
    if (bin == null) { Spacer(modifier); return }
    val color = when (bin.direction) { 1 -> Red600; -1 -> Green600; else -> Color(0xFF64748B) }
    Box(modifier.heightIn(min = 24.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.04f)),
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth(bin.count.toFloat() / peak).height(18.dp).background(color.copy(alpha = 0.14f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(bin.label.removeSuffix("%").replace(", ", ",").replace(" / ", "/"), fontSize = 11.sp, lineHeight = 14.sp,
                color = Color.DarkGray, maxLines = 1)
            Text(String.format(Locale.ROOT, "%d/%.1f%%", bin.count, report.percentage(bin.count)),
                fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 1)
        }
    }
}

private fun pct(value: Double?): String = value?.let { String.format(Locale.ROOT, "%+.2f%%", it) } ?: "—"
