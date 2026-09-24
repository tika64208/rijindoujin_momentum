package com.example.momentum.ui.screen

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.data.DarkTradeDataSource
import com.example.momentum.data.MoneyFlowDataSource
import com.example.momentum.ui.theme.*

private fun fmtYi(v: Double): String = String.format("%+.2f亿", v / 1e8)

/**
 * 暗盘交易趋势图(折线: 总成交额)。
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
                // 按日期升序
                val sorted = records.sortedBy { it.date }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(Color(0xFFFAFAFA), RoundedCornerShape(6.dp))
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
                    val minV = values.min().coerceAtMost(0.0)
                    val maxV = values.max().coerceAtLeast(0.0)
                    if (maxV <= minV) return@Canvas

                    fun xTo(i: Int): Float = padLeft + chartW * (i.toFloat() / (n - 1).coerceAtLeast(1))
                    fun yTo(v: Double): Float = padTop + chartH * (1f - ((v - minV) / (maxV - minV)).toFloat())

                    // 零轴
                    if (0.0 in minV..maxV) {
                        val y0 = yTo(0.0)
                        drawLine(Color(0xFFCCCCCC), Offset(padLeft, y0), Offset(w - padRight, y0), 1f)
                    }

                    // 网格 + Y 轴
                    for (g in 0..2) {
                        val frac = g.toFloat() / 2
                        val y = padTop + chartH * (1 - frac)
                        val valAt = minV + (maxV - minV) * frac
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format("%.1f亿", valAt / 1e8), padLeft - 4.dp.toPx(), y + 3.dp.toPx(),
                            Paint().apply { color = android.graphics.Color.GRAY; textSize = 9.sp.toPx(); textAlign = Paint.Align.RIGHT }
                        )
                    }

                    // 柱状(总成交额, 红正绿负, 缺数据日灰色虚线)
                    val barW = chartW / n * 0.5f
                    val dashEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                    for (i in 0 until n) {
                        val v = values[i]
                        val x = xTo(i)
                        val y0 = yTo(0.0)
                        val isDummy = v == 0.0 && sorted[i].buyAmount == 0.0 && sorted[i].sellAmount == 0.0
                        if (isDummy) {
                            // 无数据: 灰色虚线(表示请求失败或当日无交易)
                            drawLine(
                                Color(0xFFBBBBBB),
                                Offset(x, y0 - 20.dp.toPx()), Offset(x, y0 + 20.dp.toPx()),
                                barW, pathEffect = dashEffect
                            )
                        } else {
                            val y = yTo(v)
                            drawLine(
                                if (v >= 0) Color(0xFFDC2626) else Color(0xFF16A34A),
                                Offset(x, y0), Offset(x, y), barW
                            )
                        }
                    }

                    // 折线(趋势, 跳过缺数据日)
                    val path = Path()
                    var pathStarted = false
                    for (i in 0 until n) {
                        val isDummy = values[i] == 0.0 && sorted[i].buyAmount == 0.0 && sorted[i].sellAmount == 0.0
                        if (!isDummy) {
                            val x = xTo(i)
                            val y = yTo(values[i])
                            if (!pathStarted) { path.moveTo(x, y); pathStarted = true }
                            else path.lineTo(x, y)
                        }
                    }
                    if (pathStarted) drawPath(path, Blue700, style = Stroke(width = 2.dp.toPx()))

                    // X 轴日期(首尾)
                    drawContext.canvas.nativeCanvas.drawText(
                        sorted.first().date.takeLast(5), xTo(0), h - 2.dp.toPx(),
                        Paint().apply { color = android.graphics.Color.GRAY; textSize = 9.sp.toPx(); textAlign = Paint.Align.LEFT }
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        sorted.last().date.takeLast(5), xTo(n - 1), h - 2.dp.toPx(),
                        Paint().apply { color = android.graphics.Color.GRAY; textSize = 9.sp.toPx(); textAlign = Paint.Align.RIGHT }
                    )
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
 * 主力资金流向直方图(红净流入, 绿净流出)。
 */
@Composable
fun MoneyFlowChart(
    flows: List<MoneyFlowDataSource.FlowPoint>,
    modifier: Modifier = Modifier
) {
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
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(Color(0xFFFAFAFA), RoundedCornerShape(6.dp))
                ) {
                    val n = flows.size
                    val w = size.width
                    val h = size.height
                    val padLeft = 46.dp.toPx()
                    val padRight = 8.dp.toPx()
                    val padTop = 6.dp.toPx()
                    val padBottom = 18.dp.toPx()
                    val chartW = w - padLeft - padRight
                    val chartH = h - padTop - padBottom

                    val values = flows.map { it.mainNet }
                    val minV = values.min().coerceAtMost(0.0)
                    val maxV = values.max().coerceAtLeast(0.0)
                    if (maxV <= minV) return@Canvas

                    fun xTo(i: Int): Float = padLeft + chartW * (i.toFloat() / (n - 1).coerceAtLeast(1))
                    fun yTo(v: Double): Float = padTop + chartH * (1f - ((v - minV) / (maxV - minV)).toFloat())

                    // 零轴
                    val y0 = yTo(0.0)
                    drawLine(Color(0xFF999999), Offset(padLeft, y0), Offset(w - padRight, y0), 1f)

                    // Y 轴刻度
                    for (g in 0..2) {
                        val frac = g.toFloat() / 2
                        val y = padTop + chartH * (1 - frac)
                        val valAt = minV + (maxV - minV) * frac
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format("%.1f亿", valAt / 1e8), padLeft - 4.dp.toPx(), y + 3.dp.toPx(),
                            Paint().apply { color = android.graphics.Color.GRAY; textSize = 9.sp.toPx(); textAlign = Paint.Align.RIGHT }
                        )
                    }

                    // 直方图(红净流入, 绿净流出)
                    val barW = chartW / n * 0.7f
                    for (i in 0 until n) {
                        val v = values[i]
                        val x = xTo(i)
                        val y = yTo(v)
                        drawLine(
                            if (v >= 0) Color(0xFFDC2626) else Color(0xFF16A34A),
                            Offset(x, y0), Offset(x, y), barW
                        )
                    }

                    // X 轴日期(首尾)
                    drawContext.canvas.nativeCanvas.drawText(
                        flows.first().date.takeLast(5), xTo(0), h - 2.dp.toPx(),
                        Paint().apply { color = android.graphics.Color.GRAY; textSize = 9.sp.toPx(); textAlign = Paint.Align.LEFT }
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        flows.last().date.takeLast(5), xTo(n - 1), h - 2.dp.toPx(),
                        Paint().apply { color = android.graphics.Color.GRAY; textSize = 9.sp.toPx(); textAlign = Paint.Align.RIGHT }
                    )
                }
            }
        }
    }
}
