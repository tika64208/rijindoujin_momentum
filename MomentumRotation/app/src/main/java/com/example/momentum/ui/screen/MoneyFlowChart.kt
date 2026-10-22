package com.example.momentum.ui.screen

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.data.DarkTradeDataSource
import com.example.momentum.data.MoneyFlowDataSource
import com.example.momentum.ui.theme.*

private fun fmtYi(v: Double): String = String.format("%+.2f亿", v / 1e8)

private val Rise = Color(0xFFDC2626)
private val Fall = Color(0xFF16A34A)

/**
 * 暗盘交易趋势图(近10日):
 * - 每日双柱并列: 左红=净买额, 右绿=净卖额, 主力方向一眼对比
 * - 蓝色折线: 净流入趋势(netAmount = |净买|-|净卖|)
 * - 十字光标: 按住/拖动显示当日净买/净卖/净流入气泡
 * - 缺数据日: 灰色虚线占位
 */
@Composable
fun DarkTradeTrendChart(
    records: List<DarkTradeDataSource.DarkTradeRecord>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("暗盘交易趋势(近10日)", fontWeight = FontWeight.Bold, color = Blue700, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            if (records.isEmpty()) {
                Text("近期无暗盘交易", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 20.dp))
            } else {
                val sorted = records.sortedBy { it.date }
                var cross by remember(records) { mutableStateOf<Int?>(null) }
                val localCross = cross?.takeIf { it in sorted.indices }

                Box {
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(Color(0xFFFAFAFA), RoundedCornerShape(6.dp))
                            .pointerInput(sorted.size) {
                                detectDragGestures(
                                    onDragStart = { off ->
                                        val i = (off.x.coerceIn(0f, size.width.toFloat()) / size.width * (sorted.size - 1)).toInt()
                                        cross = i
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val i = (change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * (sorted.size - 1)).toInt()
                                        cross = i
                                    }
                                )
                            }
                            .pointerInput(sorted.size) {
                                detectTapGestures { off ->
                                    val i = (off.x.coerceIn(0f, size.width.toFloat()) / size.width * (sorted.size - 1)).toInt()
                                    cross = i
                                }
                            }
                    ) {
                        val n = sorted.size
                        val w = size.width
                        val h = size.height
                        val padLeft = 46.dp.toPx()
                        val padRight = 8.dp.toPx()
                        val padTop = 6.dp.toPx()
                        val padBottom = 18.dp.toPx()
                        val chartW = w - padLeft - padRight
                        val chartH = h - padTop - padBottom

                        val values = sorted.map { it.totalAmount }
                        val buys = sorted.map { kotlin.math.abs(it.buyAmount) }
                        val sells = sorted.map { kotlin.math.abs(it.sellAmount) }
                        val absMax = (buys + sells).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

                        fun xTo(i: Int): Float = padLeft + chartW * (i.toFloat() / (n - 1).coerceAtLeast(1))
                        fun yAbs(v: Double): Float = padTop + chartH * (1f - (v / absMax).toFloat())

                        // 网格 + Y 轴(净买/净卖绝对额刻度)
                        for (g in 0..2) {
                            val frac = g.toFloat() / 2
                            val y = padTop + chartH * (1 - frac)
                            drawLine(Color(0xFFE2E8F0), Offset(padLeft, y), Offset(w - padRight, y), 0.6f)
                            drawContext.canvas.nativeCanvas.drawText(
                                String.format("%.1f亿", absMax * frac / 1e8), padLeft - 4.dp.toPx(), y + 3.dp.toPx(),
                                Paint().apply { color = android.graphics.Color.GRAY; textSize = 9.sp.toPx(); textAlign = Paint.Align.RIGHT }
                            )
                        }

                        // 双柱: 左净买(红) 右净卖(绿); 缺数据日灰色虚线
                        val barW = chartW / n * 0.3f
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                        for (i in 0 until n) {
                            val x = xTo(i)
                            val isDummy = values[i] == 0.0 && sorted[i].buyAmount == 0.0 && sorted[i].sellAmount == 0.0
                            if (isDummy) {
                                drawLine(
                                    Color(0xFFBBBBBB),
                                    Offset(x, padTop + chartH / 2 - 20.dp.toPx()),
                                    Offset(x, padTop + chartH / 2 + 20.dp.toPx()),
                                    barW, pathEffect = dashEffect
                                )
                            } else {
                                val b = buys[i]
                                if (b > 0) drawLine(
                                    Rise, Offset(x - barW * 0.55f, padTop + chartH), Offset(x - barW * 0.55f, yAbs(b)), barW
                                )
                                val s = sells[i]
                                if (s > 0) drawLine(
                                    Fall, Offset(x + barW * 0.55f, padTop + chartH), Offset(x + barW * 0.55f, yAbs(s)), barW
                                )
                            }
                        }

                        // 净流入趋势折线(蓝: netAmount 归一化到 ±absMax)
                        val netValues = sorted.map { it.netAmount }
                        fun yNet(v: Double): Float = padTop + chartH * (1f - (v / absMax / 2 + 0.5f).toFloat())
                        val path = Path()
                        var started = false
                        for (i in 0 until n) {
                            val isDummy = values[i] == 0.0 && sorted[i].buyAmount == 0.0 && sorted[i].sellAmount == 0.0
                            if (!isDummy) {
                                val x = xTo(i); val y = yNet(netValues[i])
                                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                            }
                        }
                        if (started) drawPath(path, Blue700, style = Stroke(width = 2.dp.toPx()))

                        // X 轴日期(首/中/尾)
                        for (i in intArrayOf(0, n / 2, n - 1)) {
                            if (i in sorted.indices) {
                                drawContext.canvas.nativeCanvas.drawText(
                                    sorted[i].date.takeLast(5), xTo(i), h - 2.dp.toPx(),
                                    Paint().apply {
                                        color = android.graphics.Color.GRAY; textSize = 9.sp.toPx()
                                        textAlign = when (i) {
                                            0 -> Paint.Align.LEFT
                                            n - 1 -> Paint.Align.RIGHT
                                            else -> Paint.Align.CENTER
                                        }
                                    }
                                )
                            }
                        }

                        // 十字光标垂直线
                        if (localCross != null && localCross in 0 until n) {
                            val cx = xTo(localCross)
                            drawLine(Color(0xFF475569), Offset(cx, padTop), Offset(cx, h - padBottom), 1f)
                        }
                    }

                    // 十字光标气泡
                    if (localCross != null) {
                        val r = sorted[localCross]
                        val atLeft = localCross > sorted.size / 2
                        Surface(
                            color = Color(0xCC1E293B),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(if (atLeft) Alignment.TopStart else Alignment.TopEnd)
                                .padding(6.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                                Text(r.date, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(
                                    "买 ${fmtYi(r.buyAmount)}  卖 ${fmtYi(r.sellAmount)}",
                                    fontSize = 9.sp, color = Color(0xFFE2E8F0)
                                )
                                Text(
                                    "净流入 ${fmtYi(r.netAmount)}",
                                    fontSize = 9.sp,
                                    color = if (r.netAmount >= 0) Color(0xFFFCA5A5) else Color(0xFF86EFAC)
                                )
                            }
                        }
                    }
                }

                // 自动分析结论
                Spacer(Modifier.height(8.dp))
                val conclusion = com.example.momentum.engine.DarkTradeAnalyzer.analyze(records)
                if (conclusion != null) {
                    val text = com.example.momentum.engine.DarkTradeAnalyzer.toText(conclusion)
                    val bg = if (conclusion.todayNet > 0) Color(0xFFDCFCE7)
                        else if (conclusion.todayNet < 0) Color(0xFFFEE2E2)
                        else Color(0xFFF1F5F9)
                    val fg = if (conclusion.todayNet > 0) Color(0xFF166534)
                        else if (conclusion.todayNet < 0) Color(0xFF991B1B)
                        else Color(0xFF334155)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(bg, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📊 ", fontSize = 14.sp)
                        Text(
                            text,
                            fontSize = 12.sp,
                            color = fg,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 主力资金净流入图(近30日):
 * - 叠加柱: 主力净额(实心红/绿) + 超大单(半透明底柱), 资金结构一眼可见
 * - 收盘价折线(蓝色, 右轴): 看资金与股价是否背离
 * - MA5资金均线(橙色虚线): 主力动向平滑趋势
 * - 十字光标: 按住/拖动显示当日主力/超大单/收盘/涨跌幅气泡
 * - 汇总统计条: 累计净流入 + 净流入天数占比
 */
@Composable
fun MoneyFlowChart(
    flows: List<MoneyFlowDataSource.FlowPoint>,
    modifier: Modifier = Modifier
) {
    var crossIndex by remember(flows) { mutableStateOf<Int?>(null) }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("主力资金净流入(近30日)", fontWeight = FontWeight.Bold, color = Blue700, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            if (flows.isEmpty()) {
                Text("暂无资金数据", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 20.dp))
            } else {
                // ===== 汇总统计条 =====
                val totalNet = flows.sumOf { it.mainNet }
                val inflowDays = flows.count { it.mainNet > 0 }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                totalNet > 0 -> Color(0xFFFEF2F2)
                                totalNet < 0 -> Color(0xFFF0FDF4)
                                else -> Color(0xFFF1F5F9)
                            },
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "累计 ${fmtYi(totalNet)}",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = when {
                            totalNet > 0 -> Color(0xFF991B1B)
                            totalNet < 0 -> Color(0xFF166534)
                            else -> Color(0xFF334155)
                        }
                    )
                    Text("净流入 ${inflowDays}/${flows.size} 天", fontSize = 11.sp, color = Color(0xFF475569))
                }
                Spacer(Modifier.height(8.dp))

                val localCross = crossIndex?.takeIf { it in flows.indices }
                Box {
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(Color(0xFFFAFAFA), RoundedCornerShape(6.dp))
                            .pointerInput(flows.size) {
                                detectDragGestures(
                                    onDragStart = { off ->
                                        val i = (off.x.coerceIn(0f, size.width.toFloat()) / size.width * (flows.size - 1)).toInt()
                                        crossIndex = i
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val i = (change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * (flows.size - 1)).toInt()
                                        crossIndex = i
                                    }
                                )
                            }
                            .pointerInput(flows.size) {
                                detectTapGestures { off ->
                                    val i = (off.x.coerceIn(0f, size.width.toFloat()) / size.width * (flows.size - 1)).toInt()
                                    crossIndex = i
                                }
                            }
                    ) {
                        val n = flows.size
                        val w = size.width
                        val h = size.height
                        val padLeft = 46.dp.toPx()
                        val padRight = 46.dp.toPx()   // 右侧留收盘价刻度
                        val padTop = 6.dp.toPx()
                        val padBottom = 18.dp.toPx()
                        val chartW = w - padLeft - padRight
                        val chartH = h - padTop - padBottom

                        val values = flows.map { it.mainNet }
                        val supers = flows.map { it.superNet }
                        val closes = flows.map { it.close }
                        val minV = values.min().coerceAtMost(0.0)
                        val maxV = values.max().coerceAtLeast(0.0)
                        val minC = closes.min().coerceAtLeast(0.01)
                        val maxC = closes.max()
                        if (maxV <= minV || maxC <= minC) return@Canvas

                        fun xTo(i: Int): Float = padLeft + chartW * (i.toFloat() / (n - 1).coerceAtLeast(1))
                        fun yTo(v: Double): Float = padTop + chartH * (1f - ((v - minV) / (maxV - minV)).toFloat())
                        fun yClose(c: Double): Float = padTop + chartH * (1f - ((c - minC) / (maxC - minC)).toFloat())

                        // 网格 + 左轴(资金亿) + 右轴(收盘价)
                        for (g in 0..2) {
                            val frac = g.toFloat() / 2
                            val y = padTop + chartH * (1 - frac)
                            drawLine(Color(0xFFE2E8F0), Offset(padLeft, y), Offset(w - padRight, y), 0.6f)
                            val valAt = minV + (maxV - minV) * frac
                            drawContext.canvas.nativeCanvas.drawText(
                                String.format("%.1f亿", valAt / 1e8), padLeft - 4.dp.toPx(), y + 3.dp.toPx(),
                                Paint().apply { color = android.graphics.Color.GRAY; textSize = 9.sp.toPx(); textAlign = Paint.Align.RIGHT }
                            )
                            val cAt = minC + (maxC - minC) * frac
                            drawContext.canvas.nativeCanvas.drawText(
                                String.format("%.2f", cAt), w - padRight + 4.dp.toPx(), y + 3.dp.toPx(),
                                Paint().apply { color = android.graphics.Color.rgb(37, 99, 235); textSize = 9.sp.toPx(); textAlign = Paint.Align.LEFT }
                            )
                        }

                        // 零轴
                        val y0 = yTo(0.0)
                        drawLine(Color(0xFF999999), Offset(padLeft, y0), Offset(w - padRight, y0), 1f)

                        // MA5 资金均线(橙色虚线)
                        if (n >= 5) {
                            val maPath = Path()
                            for (i in 4 until n) {
                                val ma = values.subList(i - 4, i + 1).average()
                                val x = xTo(i); val y = yTo(ma)
                                if (i == 4) maPath.moveTo(x, y) else maPath.lineTo(x, y)
                            }
                            drawPath(
                                maPath, Color(0xFFF59E0B),
                                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f), 0f))
                            )
                        }

                        // 柱状: 超大单(半透明) + 主力净额(实心)
                        val barW = chartW / n * 0.7f
                        for (i in 0 until n) {
                            val x = xTo(i)
                            val sv = supers[i]
                            val sy = yTo(sv)
                            drawLine(
                                if (sv >= 0) Color(0x66DC2626) else Color(0x6616A34A),
                                Offset(x, y0), Offset(x, sy), barW
                            )
                            val v = values[i]
                            val y = yTo(v)
                            drawLine(
                                if (v >= 0) Rise else Fall,
                                Offset(x, y0), Offset(x, y), barW
                            )
                        }

                        // 收盘价折线(蓝, 右轴)
                        val cPath = Path()
                        for (i in 0 until n) {
                            val x = xTo(i); val y = yClose(closes[i])
                            if (i == 0) cPath.moveTo(x, y) else cPath.lineTo(x, y)
                        }
                        drawPath(cPath, Color(0xFF2563EB), style = Stroke(width = 1.8.dp.toPx()))

                        // X 轴日期(首/中/尾)
                        for (i in intArrayOf(0, n / 2, n - 1)) {
                            drawContext.canvas.nativeCanvas.drawText(
                                flows[i].date.takeLast(5), xTo(i), h - 2.dp.toPx(),
                                Paint().apply {
                                    color = android.graphics.Color.GRAY; textSize = 9.sp.toPx()
                                    textAlign = when (i) {
                                        0 -> Paint.Align.LEFT
                                        n - 1 -> Paint.Align.RIGHT
                                        else -> Paint.Align.CENTER
                                    }
                                }
                            )
                        }

                        // 十字光标垂直线
                        if (localCross != null && localCross in 0 until n) {
                            val cx = xTo(localCross)
                            drawLine(Color(0xFF475569), Offset(cx, padTop), Offset(cx, h - padBottom), 1f)
                        }
                    }

                    // 十字光标气泡
                    if (localCross != null) {
                        val f = flows[localCross]
                        val atLeft = localCross > flows.size / 2
                        Surface(
                            color = Color(0xCC1E293B),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(if (atLeft) Alignment.TopStart else Alignment.TopEnd)
                                .padding(6.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                                Text(f.date, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(
                                    "主力 ${fmtYi(f.mainNet)}  超大 ${fmtYi(f.superNet)}",
                                    fontSize = 9.sp, color = if (f.mainNet >= 0) Color(0xFFFCA5A5) else Color(0xFF86EFAC)
                                )
                                Text(
                                    "收盘 ${String.format("%.2f", f.close)}  ${String.format("%+.2f%%", f.chgPct)}",
                                    fontSize = 9.sp, color = Color(0xFFE2E8F0)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
