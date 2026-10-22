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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.model.DailyBar
import com.example.momentum.ui.theme.*

/** A股配色: 红涨绿跌 */
private val Rise = Color(0xFFDC2626)
private val Fall = Color(0xFF16A34A)
private val MA5Color = Color(0xFFF59E0B)   // 黄
private val MA10Color = Color(0xFF8B5CF6)  // 紫
private val MA20Color = Color(0xFF3B82F6)  // 蓝

/** 简单移动平均线计算(不足N天返回 null)。 */
private fun maValues(bars: List<DailyBar>, period: Int): List<Double?> {
    val closes = bars.map { it.close }
    return bars.indices.map { i ->
        if (i + 1 < period) null else closes.subList(i + 1 - period, i + 1).average()
    }
}

/**
 * 日K线蜡烛图 + MA均线 + 成交量副图 + 十字光标交互。
 *
 * 交互:
 * - 手指按住/拖动: 显示十字光标与该日 OHLC 数据气泡
 * - 松手后光标保留, 点击气泡外区域可再次定位
 * - 周期切换: 60 / 120 / 250 日
 */
@Composable
fun KLineChart(
    bars: List<DailyBar>,
    modifier: Modifier = Modifier.fillMaxWidth().height(300.dp),
    showDays: Int = 60
) {
    var selectedPeriod by remember { mutableStateOf(showDays) }
    var crossIndex by remember { mutableStateOf<Int?>(null) }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("日K线走势(前复权)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Blue700)
                // 周期切换
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(60, 120, 250).forEach { d ->
                        FilterChip(
                            selected = selectedPeriod == d,
                            onClick = {
                                selectedPeriod = d
                                crossIndex = null
                            },
                            label = { Text("${d}日", fontSize = 10.sp) },
                            modifier = Modifier.height(26.dp).padding(end = 2.dp)
                        )
                    }
                }
            }

            if (bars.size < 5) {
                Text("数据不足", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 20.dp))
            } else {
                // 用全部数据算均线, 再截取显示区间(保证窗口边缘均线有效)
                val ma5 = maValues(bars, 5)
                val ma10 = maValues(bars, 10)
                val ma20 = maValues(bars, 20)
                val total = bars.size
                val start = (total - selectedPeriod).coerceAtLeast(0)
                val slice = bars.subList(start, total)
                val localCross = crossIndex?.takeIf { it in slice.indices }

                // MA 图例(取显示区间最后一根的有效值)
                Row(Modifier.padding(vertical = 2.dp)) {
                    listOf("MA5" to ma5, "MA10" to ma10, "MA20" to ma20).forEachIndexed { idx, (label, arr) ->
                        val color = listOf(MA5Color, MA10Color, MA20Color)[idx]
                        val v = arr.lastOrNull()
                        Text(
                            "$label ${v?.let { String.format("%.2f", it) } ?: "-"}",
                            fontSize = 9.sp, color = color,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    CandleStickChart(
                        bars = slice,
                        ma5 = ma5.subList(start, total),
                        ma10 = ma10.subList(start, total),
                        ma20 = ma20.subList(start, total),
                        crossIndex = localCross,
                        onCross = { crossIndex = it },
                        modifier = Modifier.fillMaxWidth().weight(2.5f)
                    )
                    KLineVolumeChart(
                        bars = slice,
                        crossIndex = localCross,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
    }
}

/** 十字光标手势: 拖动/按住时回调索引。 */
private fun Modifier.chartCrossGesture(n: Int, onIndex: (Int) -> Unit): Modifier =
    this.pointerInput(n) {
        detectDragGestures(
            onDragStart = { off ->
                val i = (off.x.coerceAtLeast(0f) / size.width * n).toInt().coerceIn(0, n - 1)
                onIndex(i)
            },
            onDrag = { change, _ ->
                change.consume()
                val i = (change.position.x.coerceAtLeast(0f) / size.width * n).toInt().coerceIn(0, n - 1)
                onIndex(i)
            }
        )
    }.pointerInput(n) {
        detectTapGestures { off ->
            val i = (off.x.coerceAtLeast(0f) / size.width * n).toInt().coerceIn(0, n - 1)
            onIndex(i)
        }
    }

/** 蜡烛图区域 + 均线 + 十字光标数据气泡。 */
@Composable
private fun CandleStickChart(
    bars: List<DailyBar>,
    ma5: List<Double?>,
    ma10: List<Double?>,
    ma20: List<Double?>,
    crossIndex: Int?,
    onCross: (Int?) -> Unit,
    modifier: Modifier
) {
    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFAFAFA))
                .chartCrossGesture(bars.size) { onCross(it) }
        ) {
            val n = bars.size
            val w = size.width
            val h = size.height
            val padLeft = 44.dp.toPx()
            val padRight = 8.dp.toPx()
            val padTop = 6.dp.toPx()
            val padBottom = 16.dp.toPx()
            val chartW = w - padLeft - padRight
            val chartH = h - padTop - padBottom

            // Y 轴范围: 价格区间 + 均线极值
            val maMin = minOf(
                ma5.filterNotNull().minOrNull() ?: Double.MAX_VALUE,
                ma10.filterNotNull().minOrNull() ?: Double.MAX_VALUE,
                ma20.filterNotNull().minOrNull() ?: Double.MAX_VALUE
            )
            val maMax = maxOf(
                ma5.filterNotNull().maxOrNull() ?: 0.0,
                ma10.filterNotNull().maxOrNull() ?: 0.0,
                ma20.filterNotNull().maxOrNull() ?: 0.0
            )
            val minL = minOf(bars.minOf { it.low }, maMin).coerceAtLeast(0.01)
            val maxH = maxOf(bars.maxOf { it.high }, maMax)
            if (maxH <= minL) return@Canvas

            fun xTo(i: Int): Float = padLeft + chartW * ((i + 0.5f) / n)
            fun yTo(p: Double): Float = padTop + chartH * (1f - ((p - minL) / (maxH - minL)).toFloat())

            // 网格 + Y 轴价格刻度(4分位)
            for (g in 0..3) {
                val frac = g.toFloat() / 3
                val y = padTop + chartH * (1 - frac)
                val valAt = minL + (maxH - minL) * frac
                drawLine(Color(0xFFE2E8F0), Offset(padLeft, y), Offset(w - padRight, y), 0.6f)
                drawContext.canvas.nativeCanvas.drawText(
                    String.format("%.2f", valAt), padLeft - 4.dp.toPx(), y + 3.dp.toPx(),
                    Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.RIGHT }
                )
            }

            // 蜡烛
            val candleW = chartW / n * 0.7f
            for (i in 0 until n) {
                val b = bars[i]
                val x = xTo(i)
                val yHigh = yTo(b.high)
                val yLow = yTo(b.low)
                val yOpen = yTo(b.open)
                val yClose = yTo(b.close)

                drawLine(Color(0xFF94A3B8), Offset(x, yHigh), Offset(x, yLow), 1f)
                val rectTop = minOf(yOpen, yClose)
                val rectH = kotlin.math.abs(yOpen - yClose).coerceAtLeast(1f)
                val fillColor = if (b.close >= b.open) Rise else Fall
                drawLine(fillColor, Offset(x, rectTop), Offset(x, rectTop + rectH), candleW)
            }

            // MA 均线
            fun drawMa(series: List<Double?>, color: Color) {
                val path = Path()
                var started = false
                for (i in 0 until n) {
                    val v = series[i] ?: continue
                    val x = xTo(i); val y = yTo(v)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                if (started) drawPath(path, color, style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
            }
            drawMa(ma5, MA5Color)
            drawMa(ma10, MA10Color)
            drawMa(ma20, MA20Color)

            // X 轴日期(首中尾)
            if (n >= 3) {
                for (i in intArrayOf(0, n / 2, n - 1)) {
                    drawContext.canvas.nativeCanvas.drawText(
                        bars[i].date.takeLast(5), xTo(i), h - 2.dp.toPx(),
                        Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.CENTER }
                    )
                }
            }

            // ===== 十字光标 =====
            if (crossIndex != null && crossIndex in 0 until n) {
                val cx = xTo(crossIndex)
                val b = bars[crossIndex]
                drawLine(Color(0xFF475569), Offset(cx, padTop), Offset(cx, h - padBottom), 1f)
                val cy = yTo(b.close)
                drawLine(Color(0xFF475569), Offset(padLeft, cy), Offset(w - padRight, cy), 1f)
            }
        }

        // 数据气泡(十字光标 OHLC 悬浮卡)
        if (crossIndex != null && crossIndex in bars.indices) {
            val b = bars[crossIndex]
            val bubbleAtLeft = crossIndex > bars.size / 2  // 光标在右半边, 气泡放左侧避让
            Surface(
                color = Color(0xCC1E293B),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(if (bubbleAtLeft) Alignment.TopStart else Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(b.date, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("开 ${String.format("%.2f", b.open)}  高 ${String.format("%.2f", b.high)}", fontSize = 9.sp, color = Color(0xFFE2E8F0))
                    Text("低 ${String.format("%.2f", b.low)}  收 ${String.format("%.2f", b.close)}", fontSize = 9.sp, color = Color(0xFFE2E8F0))
                    val pct = if (crossIndex > 0) (b.close / bars[crossIndex - 1].close - 1) * 100 else 0.0
                    Text(
                        "涨跌 ${String.format("%+.2f%%", pct)}  量 ${String.format("%.0f万", b.volume / 10000)}",
                        fontSize = 9.sp,
                        color = if (pct >= 0) Color(0xFFFCA5A5) else Color(0xFF86EFAC)
                    )
                }
            }
        }
    }
}

/** K线成交量副图(红涨绿跌, 十字光标同步)。 */
@Composable
private fun KLineVolumeChart(
    bars: List<DailyBar>,
    crossIndex: Int?,
    modifier: Modifier
) {
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
            val color = if (b.close >= b.open) Rise else Fall
            drawLine(color, Offset(x, h - 2.dp.toPx()), Offset(x, h - 2.dp.toPx() - barH), barW)
        }

        // 成交量最大值
        drawContext.canvas.nativeCanvas.drawText(
            String.format("%.0f万", maxVol / 10000), padLeft, 10.dp.toPx(),
            Paint().apply { color = android.graphics.Color.GRAY; textSize = 8.sp.toPx(); textAlign = Paint.Align.LEFT }
        )

        // 十字光标垂直线同步
        if (crossIndex != null && crossIndex in 0 until n) {
            val cx = xTo(crossIndex)
            drawLine(Color(0xFF475569), Offset(cx, 0f), Offset(cx, h - padBottom), 1f)
        }
    }
}
