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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.model.DailyBar
import com.example.momentum.ui.theme.*

/**
 * 日K线蜡烛图 + 成交量副图。
 */
@Composable
fun KLineChart(
    bars: List<DailyBar>,
    modifier: Modifier = Modifier.fillMaxWidth().height(240.dp),
    showDays: Int = 60  // 显示最近N根K线
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Text("日K线走势(前复权)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Blue700)
            Spacer(Modifier.height(4.dp))

            if (bars.size < 5) {
                Text("数据不足", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 20.dp))
            } else {
                val slice = bars.takeLast(showDays)

                // 蜡烛图(2/3高度)
                CandleStickChart(slice, Modifier.fillMaxWidth().weight(2f))
                // 成交量(1/3高度)
                KLineVolumeChart(slice, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** 蜡烛图区域。 */
@Composable
private fun CandleStickChart(bars: List<DailyBar>, modifier: Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFAFAFA))
    ) {
        val n = bars.size
        val w = size.width
        val h = size.height
        val padLeft = 44.dp.toPx()
        val padRight = 8.dp.toPx()
        val padTop = 6.dp.toPx()
        val padBottom = 18.dp.toPx()
        val chartW = w - padLeft - padRight
        val chartH = h - padTop - padBottom

        val minL = bars.minOf { it.low }
        val maxH = bars.maxOf { it.high }
        if (maxH <= minL) return@Canvas

        fun xTo(i: Int): Float = padLeft + chartW * ((i + 0.5f) / n)
        fun yTo(p: Double): Float = padTop + chartH * (1f - ((p - minL) / (maxH - minL)).toFloat())

        // Y 轴价格刻度
        for (g in 0..3) {
            val frac = g.toFloat() / 3
            val y = padTop + chartH * (1 - frac)
            val valAt = minL + (maxH - minL) * frac
            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.2f", valAt), padLeft - 4.dp.toPx(), y + 3.dp.toPx(),
                Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.RIGHT }
            )
        }

        // 蜡烛实体宽度(留10%间隙)
        val candleW = chartW / n * 0.7f

        for (i in 0 until n) {
            val b = bars[i]
            val x = xTo(i)
            val open = b.open
            val close = b.close
            val high = b.high
            val low = b.low

            val yHigh = yTo(high)
            val yLow = yTo(low)
            val yOpen = yTo(open)
            val yClose = yTo(close)

            // 影线(最高→最低)
            drawLine(Color(0xFF64748B), Offset(x, yHigh), Offset(x, yLow), 1f)

            // 实体(开→收)
            val rectTop = minOf(yOpen, yClose)
            val rectH = kotlin.math.abs(yOpen - yClose).coerceAtLeast(1f)
            val fillColor = if (close >= open) Color(0xFFDC2626) else Color(0xFF16A34A)
            drawLine(fillColor, Offset(x, rectTop), Offset(x, rectTop + rectH), candleW)
        }

        // X 轴日期(首中尾)
        if (n >= 3) {
            for (i in intArrayOf(0, n / 2, n - 1)) {
                drawContext.canvas.nativeCanvas.drawText(
                    bars[i].date.takeLast(5), xTo(i), h - 2.dp.toPx(),
                    Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.CENTER }
                )
            }
        }
    }
}

/** K线成交量副图(红涨绿跌)。 */
@Composable
private fun KLineVolumeChart(bars: List<DailyBar>, modifier: Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFAFAFA))
    ) {
        val n = bars.size
        val w = size.width
        val h = size.height
        val padLeft = 44.dp.toPx()
        val padRight = 8.dp.toPx()
        val padBottom = 10.dp.toPx()
        val chartW = w - padLeft - padRight
        val chartH = h - padBottom

        val maxVol = bars.maxOf { it.volume }.coerceAtLeast(1.0)

        fun xTo(i: Int): Float = padLeft + chartW * ((i + 0.5f) / n)

        val barW = chartW / n * 0.6f
        for (i in 0 until n) {
            val b = bars[i]
            val x = xTo(i)
            val barH = (b.volume / maxVol * chartH).toFloat()
            val rise = b.close >= b.open
            val color = if (rise) Color(0xFFDC2626) else Color(0xFF16A34A)
            drawLine(color, Offset(x, h - 2.dp.toPx()), Offset(x, h - 2.dp.toPx() - barH), barW)
        }

        // 成交量最大值
        drawContext.canvas.nativeCanvas.drawText(
            String.format("%.0f万", maxVol / 10000), padLeft, chartH + 10.dp.toPx(),
            Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.LEFT }
        )
    }
}