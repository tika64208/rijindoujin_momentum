package com.example.momentum.ui.screen

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.data.AStockDataSource
import com.example.momentum.data.DarkTradeDataSource
import com.example.momentum.data.MoneyFlowDataSource
import com.example.momentum.data.TencentDataSource
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.BidDataEntity
import com.example.momentum.engine.MacdEngine
import com.example.momentum.model.DailyBar
import com.example.momentum.model.Stock
import com.example.momentum.ui.theme.*
import com.example.momentum.util.CrashLogManager
import com.example.momentum.util.FinanceFormatter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 股票详情页: 上面是股票分时图, 下面是板块日K线走势(近5日)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    code: String,
    name: String,
    onBack: () -> Unit,
    onOpenLogs: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val symbol = remember(code) { Stock.toMarketPrefix(code) }
    val (benchSymbol, benchName) = remember(code) { TencentDataSource.getBenchmarkIndex(code) }

    var stockData by remember { mutableStateOf<List<TencentDataSource.MinutePoint>>(emptyList()) }
    var boardData by remember { mutableStateOf<List<TencentDataSource.MinutePoint>>(emptyList()) }
    var macdData by remember { mutableStateOf<List<MacdEngine.MacdPoint>>(emptyList()) }
    var boardMacdData by remember { mutableStateOf<List<MacdEngine.MacdPoint>>(emptyList()) }
    var darkTrades by remember { mutableStateOf<List<DarkTradeDataSource.DarkTradeRecord>>(emptyList()) }
    var darkLoading by remember { mutableStateOf(true) }
    var moneyFlows by remember { mutableStateOf<List<MoneyFlowDataSource.FlowPoint>>(emptyList()) }
    var flowLoading by remember { mutableStateOf(true) }
    var bidHistory by remember { mutableStateOf<List<BidDataEntity>>(emptyList()) }
    var bidLoading by remember { mutableStateOf(true) }
    var dailyBars by remember { mutableStateOf<List<DailyBar>>(emptyList()) }
    var showKLine by remember { mutableStateOf(false) }  // false=分时, true=日K
    var loading by remember { mutableStateOf(true) }
    var errorNotice by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(code, refreshTrigger) {
        loading = true
        errorNotice = null
        scope.launch {
            try {
                val stock = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        TencentDataSource.fetchMinuteData(symbol)
                    } catch (e: Exception) {
                        CrashLogManager.recordError("StockDetailScreen", "个股分时加载失败($symbol): ${e.message}", e)
                        emptyList()
                    }
                }
                stockData = stock

                // 下半部分: 板块/行业指数分时(腾讯接口, CSSW电子等)
                val bench = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        TencentDataSource.fetchMinuteData(benchSymbol)
                    } catch (e: Exception) {
                        CrashLogManager.recordError("StockDetailScreen", "板块分时加载失败($benchSymbol): ${e.message}", e)
                        emptyList()
                    }
                }
                boardData = bench

                // 个股 MACD: 优先腾讯日线，遇到501/WAF或网络异常时自动降级至东方财富日线备用源
                val bars = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        TencentDataSource.fetchDailyBars(symbol, 252)
                    } catch (e: Exception) {
                        CrashLogManager.recordError(
                            "StockDetailScreen",
                            "腾讯日K接口获取失败($symbol): ${e.message}，自动尝试东财备用源",
                            e
                        )
                        try {
                            // 降级东方财富日线
                            val fallbackBars = AStockDataSource.fetchDailyBars(code, "20240101")
                            if (fallbackBars.isEmpty()) {
                                errorNotice = "部分日K数据加载受限 (已记录错误日志)"
                            }
                            fallbackBars
                        } catch (e2: Exception) {
                            CrashLogManager.recordError(
                                "StockDetailScreen",
                                "东财备用日K源也失败($code): ${e2.message}",
                                e2
                            )
                            errorNotice = "日K数据加载失败: ${e.message?.take(30)}"
                            emptyList()
                        }
                    }
                }
                dailyBars = bars
                macdData = MacdEngine.compute(bars)

                // 板块 MACD: 用指数日线数据计算(近一年)
                val boardBars = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        TencentDataSource.fetchDailyBars(benchSymbol, 252)
                    } catch (e: Exception) {
                        CrashLogManager.recordError(
                            "StockDetailScreen",
                            "板块日K接口获取失败($benchSymbol): ${e.message}",
                            e
                        )
                        emptyList()
                    }
                }
                boardMacdData = MacdEngine.compute(boardBars)
            } catch (t: Throwable) {
                CrashLogManager.recordError(
                    "StockDetailScreen",
                    "详情页加载异常: ${t.message}",
                    t
                )
                errorNotice = "加载出错: ${t.message?.take(40)}"
            } finally {
                loading = false
            }
        }

        // 暗盘数据(近10日, 无数据日补零)
        scope.launch {
            darkLoading = true
            try {
                val records = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DarkTradeDataSource.fetchRecent10Days(code)
                }
                darkTrades = records
            } catch (e: Exception) {
                CrashLogManager.recordError("StockDetailScreen", "暗盘数据加载失败($code): ${e.message}", e)
            } finally {
                darkLoading = false
            }
        }

        // 主力资金独立异步加载(单次请求, 限流防反爬)
        scope.launch {
            kotlinx.coroutines.delay(800)  // 稍延迟, 避免与暗盘并发触发东财反爬
            flowLoading = true
            try {
                val flows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    MoneyFlowDataSource.fetchFlow(code, 30)
                }
                moneyFlows = flows
            } catch (e: Exception) {
                CrashLogManager.recordError("StockDetailScreen", "主力资金加载失败($code): ${e.message}", e)
            } finally {
                flowLoading = false
            }
        }

        // 竞价历史数据异步加载
        scope.launch {
            bidLoading = true
            try {
                val db = AppDatabase.get(context)
                val history = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    db.bidDao().getHistory(code, 30)
                }
                bidHistory = history
            } catch (e: Exception) {
                CrashLogManager.recordError("StockDetailScreen", "竞价数据加载失败($code): ${e.message}", e)
            } finally {
                bidLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("$name ($code) 分时图", fontWeight = FontWeight.Bold) },
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
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 异常提示横幅 (若接口失败或降级)
                if (errorNotice != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = errorNotice ?: "",
                                color = Color(0xFFDC2626),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (onOpenLogs != null) {
                                TextButton(onClick = onOpenLogs) {
                                    Text("查看日志", fontSize = 12.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                                }
                            }
                            TextButton(onClick = { refreshTrigger++ }) {
                                Text("重试", fontSize = 12.sp, color = Blue700)
                            }
                        }
                    }
                }

                // 个股图表区(分时/日K可切换)
                if (showKLine) {
                    KLineChart(
                        bars = dailyBars,
                        modifier = Modifier.fillMaxWidth().height(240.dp)
                    )
                } else {
                    MinuteChartCard(
                        title = "$name 分时走势",
                        points = stockData,
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                }
                // 切换按钮
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    androidx.compose.material3.FilterChip(
                        selected = !showKLine,
                        onClick = { showKLine = false },
                        label = { Text("分时", fontSize = 11.sp) }
                    )
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.FilterChip(
                        selected = showKLine,
                        onClick = { showKLine = true },
                        label = { Text("日K", fontSize = 11.sp) }
                    )
                }

                // 个股 MACD 副图
                MacdChart(
                    points = macdData,
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )

                // 板块指数分时图
                MinuteChartCard(
                    title = "$benchName 分时走势",
                    points = boardData,
                    modifier = Modifier.fillMaxWidth().height(220.dp)
                )

                // 板块 MACD 副图
                MacdChart(
                    points = boardMacdData,
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )

                // 主力资金直方图
                if (flowLoading) {
                    Text("加载主力资金…", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                } else {
                    MoneyFlowChart(
                        flows = moneyFlows,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 暗盘交易趋势图
                if (darkLoading) {
                    Text("加载暗盘数据…", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                } else {
                    DarkTradeTrendChart(
                        records = darkTrades,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 集合竞价分析与胜率卡片
                BidDetailCard(
                    bidHistory = bidHistory,
                    loading = bidLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MinuteChartCard(
    title: String,
    points: List<TencentDataSource.MinutePoint>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Blue700)
            Spacer(Modifier.height(4.dp))
            if (points.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无数据", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                // 最新价 + 涨跌幅 (优先以昨日收盘价 preClose 为基准计算真实涨跌幅)
                val preClose = points.firstOrNull { it.preClose > 0 }?.preClose ?: 0.0
                val last = points.last().price
                val base = if (preClose > 0) preClose else points.first().price
                val chg = if (base > 0) (last / base - 1.0) * 100 else 0.0
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        String.format("%.2f", last),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = if (chg >= 0) Red600 else Green600
                    )
                    Text(
                        String.format("%+.2f%%", chg),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (chg >= 0) Red600 else Green600
                    )
                }
                Spacer(Modifier.height(4.dp))
                // 价格图(含均价线) + 成交量副图
                Column(Modifier.fillMaxSize()) {
                    PriceChart(points, Modifier.fillMaxWidth().weight(3f))
                    VolumeChart(points, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

/** 价格折线 + 均价线(黄) + 昨收线(灰)。 */
@Composable
private fun PriceChart(points: List<TencentDataSource.MinutePoint>, modifier: Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFAFAFA))
    ) {
        val n = points.size
        if (n < 2) return@Canvas
        val w = size.width
        val h = size.height
        val padLeft = 40.dp.toPx()
        val padRight = 8.dp.toPx()
        val padTop = 4.dp.toPx()
        val padBottom = 4.dp.toPx()
        val chartW = w - padLeft - padRight
        val chartH = h - padTop - padBottom

        val prices = points.map { it.price }
        val avgs = points.map { it.avgPrice }
        val priceMin = prices.min()
        val priceMax = prices.max()
        val preClose = points.firstOrNull { it.preClose > 0 }?.preClose ?: 0.0
        val refPrice = if (preClose > 0) preClose else points.first().price

        // 过滤掉偏离真实股价过大的均价点，防止异常点撑爆 Y 轴
        val validAvgs = avgs.filter { it > 0 && it >= priceMin * 0.7 && it <= priceMax * 1.3 }
        val rawMin = if (validAvgs.isNotEmpty()) minOf(priceMin, validAvgs.min(), refPrice) else minOf(priceMin, refPrice)
        val rawMax = if (validAvgs.isNotEmpty()) maxOf(priceMax, validAvgs.max(), refPrice) else maxOf(priceMax, refPrice)

        // 若波动极小或平盘，增加保底视距缓冲(至少 ±0.5%)，上下留白避免贴边或画成死直线
        val diff = (rawMax - rawMin).coerceAtLeast(refPrice * 0.005)
        val padY = diff * 0.08
        val minP = (rawMin - padY).coerceAtLeast(0.01)
        val maxP = rawMax + padY
        if (maxP <= minP) return@Canvas

        fun xTo(i: Int): Float = padLeft + chartW * (i.toFloat() / (n - 1))
        fun yTo(p: Double): Float = padTop + chartH * (1f - ((p.coerceIn(minP, maxP) - minP) / (maxP - minP)).toFloat())

        // Y 轴刻度(3条, 左侧价格区间)
        val gridLines = 3
        for (g in 0..gridLines) {
            val frac = g.toFloat() / gridLines
            val y = padTop + chartH * (1 - frac)
            val valAt = minP + (maxP - minP) * frac
            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.2f", valAt),
                padLeft - 4.dp.toPx(), y + 3.dp.toPx(),
                Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.RIGHT }
            )
        }

        // 昨收参考线(灰色虚线)
        if (refPrice in minP..maxP) {
            val yRef = yTo(refPrice)
            drawLine(
                color = Color(0xFF94A3B8),
                start = Offset(padLeft, yRef),
                end = Offset(w - padRight, yRef),
                strokeWidth = 1f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )
        }

        // 均价线(黄色)
        val avgPath = Path()
        for (i in 0 until n) {
            if (points[i].avgPrice <= 0) continue
            val x = xTo(i)
            val y = yTo(points[i].avgPrice)
            if (i == 0) avgPath.moveTo(x, y) else avgPath.lineTo(x, y)
        }
        drawPath(avgPath, Color(0xFFF59E0B), style = Stroke(width = 1.5.dp.toPx()))

        // 价格线(蓝色)
        val path = Path()
        for (i in 0 until n) {
            val x = xTo(i)
            val y = yTo(points[i].price)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Blue700, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        // X 轴时间
        for (i in intArrayOf(0, n / 2, n - 1)) {
            if (i < n) {
                drawContext.canvas.nativeCanvas.drawText(
                    points[i].time, xTo(i), h - 1.dp.toPx(),
                    Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.CENTER }
                )
            }
        }
    }
}

/** 成交量柱状副图。 */
@Composable
private fun VolumeChart(points: List<TencentDataSource.MinutePoint>, modifier: Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFAFAFA))
    ) {
        val n = points.size
        if (n < 2) return@Canvas
        val w = size.width
        val h = size.height
        val padLeft = 40.dp.toPx()
        val padRight = 8.dp.toPx()
        val chartW = w - padLeft - padRight

        val maxVol = points.maxOf { it.volume }.coerceAtLeast(1.0)
        val barW = chartW / n * 0.7f
        for (i in 0 until n) {
            val x = padLeft + chartW * (i.toFloat() / (n - 1))
            val v = points[i].volume
            val barH = (v / maxVol * (h - 4.dp.toPx())).toFloat()
            val rise = points[i].price >= points[i].avgPrice
            val color = if (rise) Color(0xFFDC2626) else Color(0xFF16A34A)
            drawLine(color, Offset(x, h), Offset(x, h - barH), barW)
        }
        // 时间轴
        drawContext.canvas.nativeCanvas.drawText(
            points[0].time, padLeft, h - 1.dp.toPx(),
            Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.LEFT }
        )
        drawContext.canvas.nativeCanvas.drawText(
            points[n - 1].time, w - padRight, h - 1.dp.toPx(),
            Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.RIGHT }
        )
    }
}

@Composable
private fun BidDetailCard(
    bidHistory: List<BidDataEntity>,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("📊 集合竞价分析与胜率", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Blue700)
                if (bidHistory.isNotEmpty()) {
                    Text("近${bidHistory.size}天记录", fontSize = 11.sp, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                loading -> {
                    Text("正在加载竞价数据…", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                }
                bidHistory.isEmpty() -> {
                    Text("暂无近期集合竞价盘口记录 (交易日 09:25 开盘自动捕获全市场竞价快照)", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                }
                else -> {
                    val latest = bidHistory.first()
                    // 最新一笔竞价摘要看板
                    Surface(
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("最近竞价日: ${latest.date}", fontSize = 11.sp, color = Color.Gray)
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = FinanceFormatter.formatPct(latest.bidChange),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (latest.bidChange >= 0) Red600 else Green600
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "开盘 ${FinanceFormatter.formatPrice(latest.openPrice)}",
                                            fontSize = 12.sp,
                                            color = Color.DarkGray
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("预测胜率", fontSize = 11.sp, color = Color.Gray)
                                        Text(
                                            text = "${latest.probability}%",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (latest.probability >= 70) Red600 else Amber600
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("综合评分", fontSize = 11.sp, color = Color.Gray)
                                        Text(
                                            text = "${latest.score}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Blue700
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("竞价换手: ${String.format("%.2f%%", latest.bidTurnover)}", fontSize = 11.sp, color = Color(0xFF475569))
                                Text("实体预期: ${FinanceFormatter.formatPct(latest.entityChange)}", fontSize = 11.sp, color = Color(0xFF475569))
                                Text("可信度: ${latest.confidence}", fontSize = 11.sp, color = Color(0xFF475569))
                            }
                        }
                    }

                    // 历史竞价列表 (展示最近 5 天)
                    if (bidHistory.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        Text("历史竞价与收盘对照", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.DarkGray)
                        Spacer(Modifier.height(4.dp))
                        bidHistory.take(5).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.date.takeLast(5), fontSize = 11.sp, color = Color.Gray)
                                Text("竞价 ${FinanceFormatter.formatPct(item.bidChange)}", fontSize = 11.sp, color = if (item.bidChange >= 0) Red600 else Green600)
                                Text("收盘 ${FinanceFormatter.formatPct(item.realChange)}", fontSize = 11.sp, color = if (item.realChange >= 0) Red600 else Green600)
                                Text("胜率 ${item.probability}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (item.probability >= 70) Red600 else Color.DarkGray)
                            }
                        }
                    }
                }
            }
        }
    }
}