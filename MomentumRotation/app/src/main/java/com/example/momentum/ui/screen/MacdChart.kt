package com.example.momentum.ui.screen

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.engine.MacdEngine

/**
 * MACD 副图组件(日线级别)。
 *
 * 显示: DIF(快线, 蓝), DEA(慢线, 橙), BAR(柱状, 红涨绿跌)。
 * 默认显示最近 60 个交易日。
 */
@Composable
fun MacdChart(
    points: List<MacdEngine.MacdPoint>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text("MACD 指标", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))

        if (points.size < 26) {
            Text("数据不足", fontSize = 12.sp, color = Color.Gray)
            return@Column
        }

        // 只取最近 60 天
        val n = points.size
        val slice = points.takeLast(60)

        Canvas(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(Color(0xFFFAFAFA), RoundedCornerShape(6.dp))
        ) {
            val w = size.width
            val h = size.height
            val padLeft = 46.dp.toPx()
            val padRight = 8.dp.toPx()
            val padTop = 6.dp.toPx()
            val padBottom = 16.dp.toPx()
            val chartW = w - padLeft - padRight
            val chartH = h - padTop - padBottom

            val m = slice.size
            if (m < 2) return@Canvas

            fun xTo(i: Int): Float = padLeft + chartW * (i.toFloat() / (m - 1))

            // Y 轴范围: 取 DIF/DEA/BAR 的全局 min/max
            var minV = Double.MAX_VALUE
            var maxV = Double.MIN_VALUE
            for (p in slice) {
                if (!p.dif.isNaN()) { minV = minOf(minV, p.dif); maxV = maxOf(maxV, p.dif) }
                if (!p.dea.isNaN()) { minV = minOf(minV, p.dea); maxV = maxOf(maxV, p.dea) }
                if (!p.bar.isNaN()) { minV = minOf(minV, p.bar); maxV = maxOf(maxV, p.bar) }
            }
            if (maxV <= minV) { maxV = minV + 1.0 }

            fun yTo(v: Double): Float = padTop + chartH * (1f - ((v - minV) / (maxV - minV)).toFloat())

            // 零轴
            if (0.0 in minV..maxV) {
                val y0 = yTo(0.0)
                drawLine(Color(0xFFCCCCCC), Offset(padLeft, y0), Offset(w - padRight, y0), 1f)
            }

            // Y 轴刻度(3 条线 + 零轴)
            val gridLines = 3
            for (g in 0..gridLines) {
                val frac = g.toFloat() / gridLines
                val y = padTop + chartH * (1 - frac)
                val valAt = minV + (maxV - minV) * frac
                drawLine(Color(0xFFEEEEEE), Offset(padLeft, y), Offset(w - padRight, y), 0.5f)
                drawContext.canvas.nativeCanvas.drawText(
                    String.format("%.2f", valAt),
                    padLeft - 4.dp.toPx(), y + 4.dp.toPx(),
                    Paint().apply {
                        color = android.graphics.Color.GRAY; textSize = 9.sp.toPx()
                        textAlign = Paint.Align.RIGHT
                    }
                )
            }

            // BAR 柱状图(红涨绿跌)
            val barW = chartW / m * 0.6f
            for (i in 0 until m) {
                val b = slice[i].bar
                if (b.isNaN()) continue
                val x = xTo(i)
                val y0 = yTo(0.0)
                val y1 = yTo(b)
                val color = if (b >= 0) Color(0xFFDC2626) else Color(0xFF16A34A)
                drawLine(color, Offset(x, y0), Offset(x, y1), barW)
            }

            // DIF 快线(蓝)
            val difPath = Path()
            var first = true
            for (i in 0 until m) {
                val d = slice[i].dif
                if (d.isNaN()) continue
                val x = xTo(i)
                val y = yTo(d)
                if (first) { difPath.moveTo(x, y); first = false }
                else difPath.lineTo(x, y)
            }
            drawPath(difPath, Color(0xFF1D4ED8), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))

            // DEA 慢线(橙)
            val deaPath = Path()
            first = true
            for (i in 0 until m) {
                val e = slice[i].dea
                if (e.isNaN()) continue
                val x = xTo(i)
                val y = yTo(e)
                if (first) { deaPath.moveTo(x, y); first = false }
                else deaPath.lineTo(x, y)
            }
            drawPath(deaPath, Color(0xFFEA580C), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))

            // X 轴日期(首/尾)
            drawContext.canvas.nativeCanvas.drawText(
                slice.first().date.takeLast(5), xTo(0), h - 2.dp.toPx(),
                Paint().apply { color = android.graphics.Color.GRAY; textSize = 9.sp.toPx(); textAlign = Paint.Align.LEFT }
            )
            drawContext.canvas.nativeCanvas.drawText(
                slice.last().date.takeLast(5), xTo(m - 1), h - 2.dp.toPx(),
                Paint().apply { color = android.graphics.Color.GRAY; textSize = 9.sp.toPx(); textAlign = Paint.Align.RIGHT }
            )
        }

        // 图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            LegendItem("DIF", Color(0xFF1D4ED8))
            LegendItem("DEA", Color(0xFFEA580C))
            LegendItem("BAR", Color(0xFFDC2626))
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.sp, color = Color.Gray)
    }
}