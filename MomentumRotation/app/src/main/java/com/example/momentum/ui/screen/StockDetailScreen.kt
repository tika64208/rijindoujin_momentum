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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.data.DarkTradeDataSource
import com.example.momentum.data.MoneyFlowDataSource
import com.example.momentum.data.TencentDataSource
import com.example.momentum.engine.MacdEngine
import com.example.momentum.model.DailyBar
import com.example.momentum.model.Stock
import com.example.momentum.ui.theme.*
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
    onBack: () -> Unit
) {
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
    var dailyBars by remember { mutableStateOf<List<DailyBar>>(emptyList()) }
    var showKLine by remember { mutableStateOf(false) }  // false=分时, true=日K
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(code) {
        loading = true
        scope.launch {
            val stock = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TencentDataSource.fetchMinuteData(symbol)
            }
            stockData = stock
            // 下半部分: 板块/行业指数分时(腾讯接口, CSSW电子等)
            val bench = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TencentDataSource.fetchMinuteData(benchSymbol)
            }
            boardData = bench
            // 个股 MACD: 用日线数据计算(近一年)
            val bars = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TencentDataSource.fetchDailyBars(symbol, 252)
            }
            dailyBars = bars
            macdData = MacdEngine.compute(bars)
            // 板块 MACD: 用指数日线数据计算(近一年)
            val boardBars = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TencentDataSource.fetchDailyBars(benchSymbol, 252)
            }
            boardMacdData = MacdEngine.compute(boardBars)
            loading = false
        }
        // 暗盘数据(近10日, 无数据日补零)
        scope.launch {
            darkLoading = true
            val records = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                DarkTradeDataSource.fetchRecent10Days(code)
            }
            darkTrades = records
            darkLoading = false
        }
        // 主力资金独立异步加载(单次请求, 限流防反爬)
        scope.launch {
            kotlinx.coroutines.delay(800)  // 稍延迟, 避免与暗盘并发触发东财反爬
            flowLoading = true
            val flows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MoneyFlowDataSource.fetchFlow(code, 30)
            }
            moneyFlows = flows
            flowLoading = false
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
                // 个股图表区(分时/日K可切换)
                if (showKLine) {
                    KLineChart(
                        bars = dailyBars,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    MinuteChartCard(
                        title = "$name 分时走势",
                        points = stockData,
                        modifier = Modifier.fillMaxWidth()
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
                // 最新价 + 涨跌幅
                val first = points.first().price
                val last = points.last().price
                val chg = if (first > 0) (last / first - 1.0) * 100 else 0.0
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
                Column {
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
        val minP = minOf(prices.min(), avgs.filter { it > 0 }.min())
        val maxP = maxOf(prices.max(), avgs.max())
        if (maxP <= minP) return@Canvas

        fun xTo(i: Int): Float = padLeft + chartW * (i.toFloat() / (n - 1))
        fun yTo(p: Double): Float = padTop + chartH * (1f - ((p - minP) / (maxP - minP)).toFloat())

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

        // 昨收线(开盘价)
        val open = points.first().price
        if (open in minP..maxP) {
            val yRef = yTo(open)
            drawLine(Color(0xFF94A3B8), Offset(padLeft, yRef), Offset(w - padRight, yRef), 1f)
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