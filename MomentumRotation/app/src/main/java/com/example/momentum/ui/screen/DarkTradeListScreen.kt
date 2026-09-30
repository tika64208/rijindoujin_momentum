package com.example.momentum.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.momentum.data.DarkTradeDataSource
import com.example.momentum.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun fmtYi(v: Double): String = String.format("%+.2f亿", v / 1e8)
private fun fmtYiAbs(v: Double): String = String.format("%.2f亿", kotlin.math.abs(v) / 1e8)

/**
 * 全量暗盘列表页: 表格样式行 + 点击展开 4 组资金详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkTradeListScreen(
    onBack: () -> Unit,
    viewModel: DarkTradeListViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (state.records.isEmpty() && !state.loading) {
            viewModel.loadDate(state.date)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("全量暗盘", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
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
            SummaryBar(state, onDateChange = { viewModel.loadDate(it) },
                onRefresh = { viewModel.refreshAndSave() })

            // 搜索框
            SearchBar(
                keyword = state.searchKeyword,
                onKeywordChange = { viewModel.setSearch(it) }
            )

            state.error?.let { err ->
                Text("⚠ $err", color = Red600, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.records.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("当日暂无暗盘数据", color = Color.Gray, fontSize = 14.sp)
                    }
                }
                else -> {
                    val display = state.displayRecords
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 表头(可点击排序)
                        item {
                            TableHeader(
                                state = state,
                                onSort = { viewModel.setSort(it) }
                            )
                        }
                        if (display.isEmpty()) {
                            item {
                                Text("无匹配结果", color = Color.Gray, fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        itemsIndexed(
                            items = display,
                            key = { index, r -> "${state.date}-${r.code}-$index" }
                        ) { _, record ->
                            val expanded = state.expandedCode == record.code
                            TableRow(
                                record = record,
                                expanded = expanded,
                                detail = if (expanded) state.detail else null,
                                onClick = { viewModel.toggleExpand(record) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryBar(state: DarkTradeListState, onDateChange: (String) -> Unit, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("数据日期", fontSize = 13.sp, color = Blue700, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("◀", fontSize = 18.sp, color = Blue700,
                modifier = Modifier.clickable { onDateChange(shiftDate(state.date, -1)) })
            Spacer(Modifier.width(8.dp))
            Text(formatDisplayDate(state.date), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("▶", fontSize = 18.sp, color = Blue700,
                modifier = Modifier.clickable { onDateChange(shiftDate(state.date, 1)) })
            Spacer(Modifier.weight(1f))
            if (state.totalCount > 0) {
                Text("共 ${state.totalCount} 条", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text("刷新", fontSize = 13.sp, color = Blue700)
            }
        }
    }
}

@Composable
private fun TableHeader(state: DarkTradeListState, onSort: (DarkSortField) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0xFFEFF3F8), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        HeaderCell("代码", DarkSortField.CODE, state, onSort, Modifier.weight(1f))
        HeaderCell("名称", DarkSortField.NAME, state, onSort, Modifier.weight(1.4f))
        HeaderCell("暗盘买入", DarkSortField.BUY, state, onSort, Modifier.weight(1.2f))
        HeaderCell("暗盘卖出", DarkSortField.SELL, state, onSort, Modifier.weight(1.2f))
        HeaderCell("净流入", DarkSortField.NET, state, onSort, Modifier.weight(1.2f), endAligned = true)
    }
}

@Composable
private fun HeaderCell(
    label: String,
    field: DarkSortField,
    state: DarkTradeListState,
    onSort: (DarkSortField) -> Unit,
    modifier: Modifier = Modifier,
    endAligned: Boolean = false
) {
    val active = state.sortField == field
    val arrow = if (active) (if (state.sortAscending) "▲" else "▼") else ""
    Row(
        modifier = modifier.clickable { onSort(field) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (endAligned) Arrangement.End else Arrangement.Start
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (active) Blue700 else Color.Gray,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold
        )
        if (arrow.isNotEmpty()) {
            Text(arrow, fontSize = 8.sp, color = Blue700)
        }
    }
}

@Composable
private fun SearchBar(keyword: String, onKeywordChange: (String) -> Unit) {
    OutlinedTextField(
        value = keyword,
        onValueChange = onKeywordChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        placeholder = { Text("搜索代码或名称…", fontSize = 13.sp, color = Color.Gray) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
private fun TableRow(
    record: DarkTradeDataSource.DarkTradeRecord,
    expanded: Boolean,
    detail: StockDetailData?,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (expanded) Color(0xFFEAF1FB) else Color.White, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        // 一行表格
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(record.code, fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.weight(1f))
            Text(record.name.ifBlank { record.code }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.4f))
            Text(fmtYiAbs(record.buyAmount), fontSize = 11.sp, color = Red600, modifier = Modifier.weight(1.2f))
            Text(fmtYiAbs(record.sellAmount), fontSize = 11.sp, color = Green600, modifier = Modifier.weight(1.2f))
            Text(fmtYi(record.netAmount), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (record.netAmount >= 0) Red600 else Green600,
                modifier = Modifier.weight(1.2f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        // 展开详情
        if (expanded) {
            DetailPanel(detail)
        }
    }
}

@Composable
private fun DetailPanel(detail: StockDetailData?) {
    if (detail == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        if (detail.loadingDetail) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("加载资金流/两融数据…", fontSize = 12.sp, color = Color.Gray)
            }
        } else {
            // 暗盘
            GroupBlock("暗盘", detail.darkBuy, detail.darkSell, detail.darkNet)
            Divider(Modifier.padding(vertical = 6.dp))
            // 资金流(主力/小单/中单/大单/超大单净流入 + 占比)
            FundFlowBlock(detail)
            Divider(Modifier.padding(vertical = 6.dp))
            GroupBlock("两融(融资)", detail.marginBuy, detail.marginRepay, detail.marginNet)
            if (detail.marginBalance != 0.0) {
                Spacer(Modifier.height(4.dp))
                Text("融资余额: ${fmtYiAbs(detail.marginBalance)}", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun FundFlowBlock(detail: StockDetailData) {
    Column {
        Text("资金流", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Blue700)
        Spacer(Modifier.height(4.dp))
        FlowRow("主力净流入", detail.mainNet)
        FlowRow("小单净流入", detail.smallNet)
        FlowRow("中单净流入", detail.midNet)
        FlowRow("大单净流入", detail.bigNet)
        FlowRow("超大单净流入", detail.superNet)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            MetricCell("主力占比", detail.mainRatio, Color.DarkGray, Modifier.weight(1f), isPercent = true)
            MetricCell("小单占比", detail.smallRatio, Color.DarkGray, Modifier.weight(1f), isPercent = true)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun FlowRow(label: String, value: Double) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
        Text(
            fmtYi(value),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (value >= 0) Red600 else Green600
        )
    }
}

@Composable
private fun GroupBlock(title: String, buy: Double, sell: Double, net: Double) {
    Column {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Blue700)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            MetricCell("买入", buy, Red600, Modifier.weight(1f))
            MetricCell("卖出", sell, Green600, Modifier.weight(1f))
            MetricCell("净流入", net, if (net >= 0) Red600 else Green600, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCell(label: String, value: Double, color: Color, modifier: Modifier = Modifier, isPercent: Boolean = false) {
    Column(modifier) {
        Text(label, fontSize = 10.sp, color = Color.Gray)
        val text = if (isPercent) String.format("%.2f%%", value) else fmtYi(value)
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

private fun shiftDate(yyyymmdd: String, deltaDays: Long): String {
    return try {
        val d = LocalDate.parse(yyyymmdd, DateTimeFormatter.ofPattern("yyyyMMdd"))
        d.plusDays(deltaDays).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    } catch (e: Exception) {
        yyyymmdd
    }
}

private fun formatDisplayDate(yyyymmdd: String): String {
    return try {
        val d = LocalDate.parse(yyyymmdd, DateTimeFormatter.ofPattern("yyyyMMdd"))
        d.format(DateTimeFormatter.ofPattern("MM-dd"))
    } catch (e: Exception) {
        yyyymmdd
    }
}
