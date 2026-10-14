package com.example.momentum.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.data.BidRepository
import com.example.momentum.data.SimTradingManager
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.BidDataEntity
import com.example.momentum.engine.BidStrategy
import com.example.momentum.engine.SimDayRecord
import com.example.momentum.engine.SimOrder
import com.example.momentum.ui.theme.*
import com.example.momentum.util.FinanceFormatter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimTradingSheet(
    todayDate: String,
    todayCandidates: List<BidDataEntity>,
    onDismiss: () -> Unit,
    onStockClick: (code: String, name: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val simManager = remember { SimTradingManager.get(context) }
    val accountState by simManager.accountState.collectAsState()
    var isBacktesting by remember { mutableStateOf(false) }

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
                text = "规则: 早盘竞价(2%~4%)高胜率标的平均分仓买入，尾盘按收盘价全部卖出结算",
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

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
                                text = String.format("累计盈亏 %+.2f元", accountState.totalProfit),
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
                }
            }

            Spacer(Modifier.height(12.dp))

            // 2. 今日操作控制台
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
                                isHoldingToday -> "● 持仓中 (等待尾盘结算)"
                                isSettledToday -> "✓ 今日已结算"
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
                                    val (ok, msg) = simManager.buyToday(todayDate, todayCandidates)
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = todayCandidates.isNotEmpty(),
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
                        Surface(
                            color = Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text("当前持仓标的: ${todayRecord?.orders?.size ?: 0} 只", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = String.format("浮动盈亏: %+.2f 元 (%+.2f%%)", holdingProfit, holdingReturn),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (holdingProfit >= 0) Red600 else Green600
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    val (ok, msg) = simManager.settleToday()
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Red600),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("尾盘全部卖出结算平仓", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                                Text("今日结算已入账", fontSize = 12.sp, color = Green600, fontWeight = FontWeight.Bold)
                                Text(
                                    text = String.format("%+.2f 元 (%+.2f%%)", todayRecord?.profit ?: 0.0, todayRecord?.returnPct ?: 0.0),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if ((todayRecord?.profit ?: 0.0) >= 0) Red600 else Green600
                                )
                            }
                        }
                    }
                }
            }

            // 3. 今日持仓/交易明细清单
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
                        Spacer(Modifier.height(8.dp))

                        todayRecord.orders.forEachIndexed { index, order ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFFF1F5F9))
                            SimOrderItemRow(order = order, onStockClick = onStockClick)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 4. 历史回测与重置工具卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("🛠 策略回测与账户管理", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1E293B))
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                isBacktesting = true
                                scope.launch {
                                    val db = AppDatabase.get(context)
                                    val res = simManager.runBacktest(db, BidStrategy.BidConfig.HIGH_WIN_RATE)
                                    isBacktesting = false
                                    Toast.makeText(context, "历史回测完成: 收益率 ${String.format("%+.2f%%", res.totalReturnPct)}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isBacktesting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isBacktesting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("历史全量回测", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                simManager.resetAccount(1_000_000.0)
                                Toast.makeText(context, "已重置为 100 万元初始账户", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重置为100万", fontSize = 12.sp)
                        }
                    }
                }
            }

            // 5. 历史交易日账本
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
private fun SimOrderItemRow(
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
    }
}

@Composable
private fun SimDayRecordRow(record: SimDayRecord) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(record.date, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.DarkGray)
            Text("选股 ${record.orders.size} 只", fontSize = 11.sp, color = Color.Gray)
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
