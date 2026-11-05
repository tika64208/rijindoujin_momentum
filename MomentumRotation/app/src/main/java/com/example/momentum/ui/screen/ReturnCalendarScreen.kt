package com.example.momentum.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.data.OpeningBidTime
import com.example.momentum.data.SimTradingManager
import com.example.momentum.engine.*
import com.example.momentum.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReturnCalendarScreen(onBack: () -> Unit, initialBacktest: Boolean = false,
                         onStockClick: (String, String) -> Unit = { _, _ -> }) {
    val context = LocalContext.current
    val manager = remember { SimTradingManager.get(context) }
    val account by manager.accountState.collectAsState()
    val report by manager.backtestReport.collectAsState()
    var useBacktest by rememberSaveable { mutableStateOf(initialBacktest) }
    var showDayDetails by rememberSaveable { mutableStateOf(false) }
    val state = if (useBacktest) report?.result else account
    val records = remember(state) { state?.let(ReturnCalendar::records).orEmpty() }
    val skipped = if (useBacktest) report?.skipped.orEmpty() else emptyMap()
    val today = LocalDate.now(OpeningBidTime.zone)
    val relevantDates = records.keys + skipped.keys.mapNotNull(ReturnCalendar::parseDate)
    val earliest = relevantDates.minOrNull()?.let(YearMonth::from) ?: YearMonth.from(today)
    val latest = maxOf(YearMonth.from(today), relevantDates.maxOrNull()?.let(YearMonth::from) ?: YearMonth.from(today))
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(records.keys.maxOrNull() ?: today).toString()) }
    var selectedText by rememberSaveable { mutableStateOf((records.keys.maxOrNull() ?: today).toString()) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)
    val data = remember(month, records) { ReturnCalendar.month(month, records) }
    val selectedRecord = records[selected]

    fun changeMonth(target: YearMonth) {
        monthText = target.toString()
        selectedText = records.keys.filter { YearMonth.from(it) == target }.maxOrNull()?.toString()
            ?: target.atDay(1).toString()
    }

    if (showDayDetails) {
        ReturnCalendarDaySheet(selected, selectedRecord, useBacktest, skipped[selected.toString()],
            onDismiss = { showDayDetails = false }, onStockClick = { code, name ->
                showDayDetails = false
                onStockClick(code, name)
            })
    }

    Scaffold(containerColor = SurfaceBg, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { Text("收益日历", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Blue700, titleContentColor = Color.White,
                navigationIconContentColor = Color.White))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !useBacktest, onClick = { useBacktest = false }, label = { Text("模拟账本") })
                    FilterChip(selected = useBacktest, onClick = { useBacktest = true }, label = { Text("独立回测") })
                    TextButton(onClick = { changeMonth(YearMonth.from(today)); selectedText = today.toString() }) { Text("本月") }
                }
                Text(if (useBacktest) "仅展示已生成的独立回测报告，未验证数据沿用报告标记。" else "仅展示已存模拟账本，估算和未验证记录保留原标记。",
                    fontSize = 10.sp, lineHeight = 14.sp, color = Color.Gray)
            }
            if (useBacktest && report == null) item {
                Text("暂无独立回测报告，请先在模拟盘生成历史回测。", color = Color.Gray, fontSize = 13.sp)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { changeMonth(month.minusMonths(1)) }, enabled = month > earliest) { Text("‹ 上月") }
                    Text("${month.year}年${month.monthValue}月", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { changeMonth(month.plusMonths(1)) }, enabled = month < latest) { Text("下月 ›") }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("月内记录盈亏", fontSize = 11.sp, lineHeight = 14.sp, color = Color.Gray)
                                Text(String.format(Locale.ROOT, "%+,.2f 元", data.profit), color = returnColor(data.profit), fontWeight = FontWeight.Bold, fontSize = 21.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("月收益率", fontSize = 11.sp, lineHeight = 14.sp, color = Color.Gray)
                                Text(data.returnPct?.let(::calendarPct) ?: "—", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = returnColor(data.profit))
                            }
                        }
                        Text("交易 ${data.tradeDays} 天 · 盈利 ${data.winningDays} 天 · 亏损 ${data.losingDays} 天" +
                            if (data.holdingDays > 0) " · 持仓 ${data.holdingDays} 天" else "", fontSize = 10.sp, lineHeight = 14.sp, color = Color.Gray)
                        Text("月收益率＝月内已结算净盈亏 ÷ 月内首条已结算记录的期初资金；持仓估算不计入。",
                            fontSize = 10.sp, lineHeight = 14.sp, color = Color.Gray)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            listOf("一", "二", "三", "四", "五", "六", "日").forEach { name ->
                                Box(Modifier.weight(1f).height(24.dp), contentAlignment = Alignment.Center) {
                                    Text(name, fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                        data.cells.chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                week.forEach { date ->
                                    if (date == null) Spacer(Modifier.weight(1f).height(60.dp))
                                    else CalendarDay(date, records[date], skipped[date.toString()], date == selected, date == today,
                                        Modifier.weight(1f)) { selectedText = date.toString(); showDayDetails = true }
                                }
                            }
                        }
                    }
                }
                Text("红：盈利  绿：亏损  灰：零收益 / 无交易；“—”为无记录，点击日期查看明细。",
                    fontSize = 10.sp, lineHeight = 14.sp, color = Color.Gray, modifier = Modifier.padding(top = 5.dp))
            }

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReturnCalendarDaySheet(date: LocalDate, record: SimDayRecord?, backtest: Boolean, skipped: String?,
                                   onDismiss: () -> Unit, onStockClick: (String, String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$date · ${if (backtest) "独立回测" else "模拟账本"}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
                if (record != null) {
                    if (record.status == "HOLDING") Text("持仓中 · 估算未计入月收益", fontSize = 12.sp, color = Color.Gray)
                    Text("${calendarPct(record.returnPct)} · ${String.format(Locale.ROOT, "%+,.2f 元", record.profit)} · ${record.orders.size} 只",
                        color = returnColor(record.profit), fontWeight = FontWeight.SemiBold)
                    Text("${record.signalQualityLabel()} · ${record.priceQualityLabel()}", fontSize = 10.sp, lineHeight = 14.sp, color = Color.Gray)
                    Text(record.priceAsOf, fontSize = 10.sp, lineHeight = 14.sp, color = Color.Gray)
                    if (record.orders.isEmpty()) Text("该日无模拟交易。", fontSize = 12.sp, color = Color.Gray)
                } else Text(skipped?.let { "回测跳过：$it" } ?: "该日暂无已存收益记录。", fontSize = 12.sp, color = Color.Gray)
            }
            items(record?.orders.orEmpty(), key = { it.code }) { order ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Box(Modifier.padding(10.dp)) { SimOrderItemRow(order, onStockClick) }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(date: LocalDate, record: SimDayRecord?, skipped: String?, selected: Boolean, today: Boolean,
                        modifier: Modifier, onClick: () -> Unit) {
    val color = returnColor(record?.profit ?: 0.0)
    val background = if (record != null && record.status == "SETTLED" && record.orders.isNotEmpty()) color.copy(alpha = .09f) else Color(0xFFF8FAFC)
    Column(modifier.heightIn(min = 60.dp).clip(RoundedCornerShape(6.dp)).background(background)
        .border(BorderStroke(if (selected) 1.5.dp else .5.dp, if (selected) Blue700 else Color(0xFFE2E8F0)), RoundedCornerShape(6.dp))
        .clickable(onClick = onClick).padding(horizontal = 1.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (today) "${date.dayOfMonth}·今" else date.dayOfMonth.toString(), fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Normal, color = if (selected || today) Blue700 else Color.DarkGray)
        val label = when {
            record?.status == "HOLDING" -> "持仓中"
            record != null && record.orders.isEmpty() -> "未交易"
            record != null -> calendarPct(record.returnPct)
            skipped != null -> "跳过"
            date.dayOfWeek.value > 5 -> "周末"
            else -> "—"
        }
        Text(label, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (record != null) FontWeight.SemiBold else FontWeight.Normal, color = color)
        Text(if (record != null && record.orders.isNotEmpty() && record.status == "SETTLED") compactProfit(record.profit) else "",
            fontSize = 9.sp, lineHeight = 12.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun returnColor(profit: Double) = when { profit > 0 -> Red600; profit < 0 -> Green600; else -> Color(0xFF64748B) }
private fun calendarPct(value: Double) = String.format(Locale.ROOT, "%+.2f%%", value)
private fun compactProfit(value: Double) = if (abs(value) >= 10_000) String.format(Locale.ROOT, "%+.2f万", value / 10_000)
    else String.format(Locale.ROOT, "%+.0f", value)
