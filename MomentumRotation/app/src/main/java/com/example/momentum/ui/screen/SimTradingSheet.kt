package com.example.momentum.ui.screen

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.data.SimTradingManager
import com.example.momentum.data.db.BidDataEntity
import com.example.momentum.engine.BidStrategy
import com.example.momentum.engine.SimDayRecord
import com.example.momentum.engine.SimOrder
import com.example.momentum.engine.SimTradingFeeConfig
import com.example.momentum.engine.priceQualityLabel
import com.example.momentum.engine.signalQualityLabel
import com.example.momentum.ui.theme.*
import com.example.momentum.util.FinanceFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimTradingSheet(
    todayDate: String,
    todayCandidates: List<BidDataEntity>,
    onDismiss: () -> Unit,
    onStockClick: (code: String, name: String) -> Unit = { _, _ -> },
    onOpenReturnCalendar: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val simManager = remember { SimTradingManager.get(context) }
    val accountState by simManager.accountState.collectAsState()
    var isBacktesting by remember { mutableStateOf(false) }
    var isOperating by remember { mutableStateOf(false) }
    var includeUnverified by remember { mutableStateOf(true) }
    var expandBacktest by remember { mutableStateOf(false) }
    val backtestReport by simManager.backtestReport.collectAsState()

    val todayRecord = accountState.todayRecord
    val isHoldingToday = todayRecord != null && todayRecord.date == todayDate && todayRecord.status == "HOLDING"
    val isSettledToday = todayRecord != null && todayRecord.date == todayDate && todayRecord.status == "SETTLED"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Color(0xFFF8FAFC)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 36.dp)
        ) {
            // 顶部标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AccountBalanceWallet,
                        contentDescription = null,
                        tint = Blue700,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "100万高胜率竞价模拟盘",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("完成", fontWeight = FontWeight.Bold, color = Blue700)
                }
            }

            Text(
                text = "模拟口径：沿用存盘早盘评分平均分仓，最新行情估算；有收盘行情凭证时标记已确认，含手续费与滑点",
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            TextButton(onClick = { onOpenReturnCalendar(false) }) { Text("收益日历", fontWeight = FontWeight.SemiBold) }

            // 1. 资产总览卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("模拟总资产 (初始 100.00 万)", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = String.format("¥ %,.2f", accountState.currentCapital),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (accountState.totalProfit >= 0) Red600 else Green600
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = String.format("%+.2f%%", accountState.totalReturnPct),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (accountState.totalProfit >= 0) Red600 else Green600
                            )
                            Text(
                                text = String.format("累计净盈亏 %+.2f元", accountState.totalProfit),
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFFF1F5F9))
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "交易天数: ${accountState.totalDayCount} 天",
                            fontSize = 12.sp,
                            color = Color(0xFF475569)
                        )
                        Text(
                            "盈利天数: ${accountState.winDayCount} 天",
                            fontSize = 12.sp,
                            color = Color(0xFF475569)
                        )
                        Text(
                            "胜率: ${String.format("%.1f%%", accountState.winRate)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (accountState.winRate >= 50) Red600 else Color.DarkGray
                        )
                    }

                    if (accountState.totalFeePaid > 0 || accountState.totalSlippagePaid > 0) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "累计规费: -¥${String.format("%.2f", accountState.totalFeePaid)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                                Text(
                                    "滑点摩擦: -¥${String.format("%.2f", accountState.totalSlippagePaid)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 2. 交易摩擦成本设置卡片 (手续费与滑点开关)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = null, tint = Blue700, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("交易摩擦成本设置 (实盘贴近度)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Blue700)
                    }

                    Spacer(Modifier.height(10.dp))

                    val feeCfg = accountState.feeConfig
                    // 手续费开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("扣除交易规费 (佣金+印花税)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("买入万2(佣金/过户) + 卖出万7(含千0.5印花税)", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = feeCfg.enableFee,
                            enabled = !isOperating,
                            onCheckedChange = {
                                simManager.updateFeeConfig(feeCfg.copy(enableFee = it))
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Green600)
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = Color(0xFFF1F5F9))
                    Spacer(Modifier.height(6.dp))

                    // 滑点开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("扣除买卖滑点冲击 (Slippage)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("单边滑点 0.1% (早盘买入+0.1%, 尾盘卖出-0.1%)", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = feeCfg.enableSlippage,
                            enabled = !isOperating,
                            onCheckedChange = {
                                simManager.updateFeeConfig(feeCfg.copy(enableSlippage = it))
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Green600)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text("费用开关用于新模拟和新回测；已有持仓使用买入时的费用配置。", fontSize = 10.sp, color = Color.Gray)

            // 3. 今日操作控制台
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚡ 今日实操 [$todayDate]",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Blue700
                        )
                        Text(
                            text = when {
                                isHoldingToday -> "● 持仓中（按保存行情估算）"
                                isSettledToday -> "✓ 今日已模拟结算"
                                else -> "○ 未建仓"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                isHoldingToday -> Amber600
                                isSettledToday -> Green600
                                else -> Color.Gray
                            }
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    if (!isHoldingToday && !isSettledToday) {
                        Text(
                            text = "今日高胜率条件筛选出: ${todayCandidates.size} 只标的",
                            fontSize = 13.sp,
                            color = Color.DarkGray
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    isOperating = true
                                    val (ok, msg) = try { simManager.buyToday(todayDate) } finally { isOperating = false }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = todayCandidates.isNotEmpty() && !isOperating,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue700),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (todayCandidates.isEmpty()) "今日暂无高胜率标的" else "按高胜率全额分仓买入 (${todayCandidates.size}只)",
                                fontSize = 13.sp
                            )
                        }
                    } else if (isHoldingToday) {
                        val holdingProfit = todayRecord?.orders?.sumOf { it.profit } ?: 0.0
                        val holdingReturn = todayRecord?.returnPct ?: 0.0
                        val dayFee = todayRecord?.orders?.sumOf { it.totalFee } ?: 0.0
                        Surface(
                            color = Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text("当前持仓标的: ${todayRecord?.orders?.size ?: 0} 只", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = String.format("买入时行情估算: %+.2f 元 (%+.2f%%)", holdingProfit, holdingReturn),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (holdingProfit >= 0) Red600 else Green600
                                )
                                if (dayFee > 0) {
                                    Text("预计双向规费: -¥${String.format("%.2f", dayFee)}", fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    isOperating = true
                                    val (ok, msg) = try { simManager.settleToday() } finally { isOperating = false }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isOperating,
                            colors = ButtonDefaults.buttonColors(containerColor = Red600),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("按保存的最新行情模拟结算", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // 今日已结算
                        Surface(
                            color = Color(0xFFF0FDF4),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("今日模拟已入账 · ${todayRecord?.priceQualityLabel()}", fontSize = 12.sp, color = Green600, fontWeight = FontWeight.Bold)
                                    if ((todayRecord?.totalFee ?: 0.0) > 0) {
                                        Text("规费印花税: -¥${String.format("%.2f", todayRecord?.totalFee)}", fontSize = 10.sp, color = Color.Gray)
                                    }
                                }
                                Text(
                                    text = String.format("%+.2f 元 (%+.2f%%)", todayRecord?.profit ?: 0.0, todayRecord?.returnPct ?: 0.0),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if ((todayRecord?.profit ?: 0.0) >= 0) Red600 else Green600
                                )
                            }
                        }

                        // 当今日全量候选股(12只)与旧订单数量不一致或需要重算时
                        if (todayCandidates.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isOperating = true
                                        val (ok, msg) = try { simManager.reSimulateToday(todayDate) } finally { isOperating = false }
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                enabled = !isOperating,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Blue700)
                            ) {
                                Text(
                                    text = "按早盘评分与最新行情重跑 (${todayCandidates.size}只)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 4. 今日持仓/交易明细清单 (展示手续费与滑点明细)
            if (todayRecord != null && todayRecord.orders.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("📋 今日交易明细清单 (${todayRecord.orders.size}只)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Blue700)
                        Text("${todayRecord.signalQualityLabel()} · ${todayRecord.priceQualityLabel()}", fontSize = 10.sp, color = Color.Gray)
                        Text(todayRecord.priceAsOf, fontSize = 10.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))

                        todayRecord.orders.forEachIndexed { index, order ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFFF1F5F9))
                            SimOrderItemRow(order = order, onStockClick = onStockClick)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 5. 历史回测与重置工具卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("🛠 策略回测与账户管理", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1E293B))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeUnverified, enabled = !isBacktesting,
                            onCheckedChange = { includeUnverified = it })
                        Text("包含未验证历史（保留存盘评分和价格，不重算评分）", fontSize = 11.sp, modifier = Modifier.weight(1f))
                    }
                    Text("关闭后只使用已冻结评分且收盘价已确认的数据。回测结果独立保存，不写模拟账本。", fontSize = 11.sp, color = Color.Gray)


                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                isBacktesting = true
                                scope.launch {
                                    try {
                                        val report = simManager.runBacktest(BidStrategy.BidConfig.HIGH_WIN_RATE, includeUnverified)
                                        expandBacktest = true
                                        Toast.makeText(context, "独立回测完成：历史累计 ${String.format("%+.2f%%", report.result.totalReturnPct)}，模拟账户未变更", Toast.LENGTH_LONG).show()
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (e: Exception) { Toast.makeText(context, "回测失败：${e.message}", Toast.LENGTH_LONG).show() }
                                    finally { isBacktesting = false }
                                }
                            },
                            enabled = !isBacktesting && !isOperating,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isBacktesting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("生成独立历史回测", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val ok = simManager.resetAccount(1_000_000.0)
                                Toast.makeText(context, if (ok) "已重置为 100 万元初始账户" else "账户操作中，请稍后再试", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isOperating && !isBacktesting
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重置为100万", fontSize = 12.sp)
                        }
                    }
                }
            }

            backtestReport?.let { report ->
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("独立历史回测报告", fontWeight = FontWeight.Bold, color = Blue700)
                        Text("生成时间 ${java.time.Instant.ofEpochMilli(report.generatedAt).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime()}", fontSize = 10.sp, color = Color.Gray)
                        Text("历史累计收益 ${String.format("%+.2f%%", report.result.totalReturnPct)} · ${report.result.historyRecords.size} 个日期", fontSize = 15.sp)
                        Text("初始本金 ${String.format("%,.2f", report.result.initialCapital)} 元 · 沿用存盘评分 · ${if (report.result.feeConfig.enableFee) "含手续费" else "不含手续费"} · ${if (report.result.feeConfig.enableSlippage) "含滑点" else "不含滑点"}", fontSize = 11.sp, color = Color.Gray)
                        Text("未验证 ${report.notes.size} 日 · 跳过 ${report.skipped.size} 日；不改变模拟账户资金。", fontSize = 11.sp, color = Color.Gray)
                        Row {
                            TextButton(onClick = { expandBacktest = !expandBacktest }) { Text(if (expandBacktest) "收起每日明细" else "展开每日明细") }
                            TextButton(onClick = { onOpenReturnCalendar(true) }) { Text("回测收益日历") }
                        }
                        if (expandBacktest) {
                            report.result.historyRecords.forEach { day ->
                                HorizontalDivider()
                                SimDayRecordRow(day)
                                Text(day.priceAsOf, fontSize = 10.sp, color = Color.Gray)
                            }
                            report.skipped.forEach { (date, reason) -> Text("跳过 $date：$reason", fontSize = 11.sp, color = Color(0xFF92400E)) }
                        }
                    }
                }
            }

            // 6. 历史交易日账本
            if (accountState.historyRecords.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("📅 历史日结账本 (${accountState.historyRecords.size}天)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Blue700)
                        Spacer(Modifier.height(8.dp))

                        accountState.historyRecords.take(15).forEachIndexed { index, day ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFFF1F5F9))
                            SimDayRecordRow(record = day)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SimOrderItemRow(
    order: SimOrder,
    onStockClick: (code: String, name: String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onStockClick(order.code, order.name) }
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(order.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Blue700)
                Spacer(Modifier.width(4.dp))
                Text(order.code, fontSize = 11.sp, color = Color.Gray)
            }
            Text(
                text = String.format("%+.2f元 (%+.2f%%)", order.profit, order.returnPct),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (order.profit >= 0) Red600 else Green600
            )
        }

        Spacer(Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "买入 ${FinanceFormatter.formatPrice(order.buyPrice)}  /  现价 ${FinanceFormatter.formatPrice(order.sellPrice)}",
                fontSize = 11.sp,
                color = Color.DarkGray
            )
            Text(
                text = "${order.shares}股 (¥${String.format("%,.0f", order.actualCost)})",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }

        if (order.totalFee > 0 || order.slippageCost > 0) {
            Spacer(Modifier.height(1.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("规费印花税: -¥${String.format("%.2f", order.totalFee)}", fontSize = 10.sp, color = Color(0xFF94A3B8))
                Text("滑点冲击: -¥${String.format("%.2f", order.slippageCost)}", fontSize = 10.sp, color = Color(0xFF94A3B8))
            }
        }
    }
}

@Composable
private fun SimDayRecordRow(record: SimDayRecord) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(record.date, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.DarkGray)
            Text("${record.signalQualityLabel()} · ${record.priceQualityLabel()}", fontSize = 10.sp, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选股 ${record.orders.size} 只", fontSize = 11.sp, color = Color.Gray)
                if (record.totalFee > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("规费 -¥${String.format("%.1f", record.totalFee)}", fontSize = 10.sp, color = Color(0xFF94A3B8))
                }
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = String.format("%+.2f%%", record.returnPct),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (record.profit >= 0) Red600 else Green600
            )
            Text(
                text = String.format("%+.2f元", record.profit),
                fontSize = 11.sp,
                color = if (record.profit >= 0) Red600 else Green600
            )
        }
    }
}
