package com.example.momentum.ui.screen

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.engine.EquityCurveEngine
import com.example.momentum.ui.theme.*

private val CURVE_COLORS = listOf(
    Color(0xFF1D4ED8),  // 蓝(动量)
    Color(0xFF94A3B8),  // 灰(均仓)
    Color(0xFF16A34A),  // 绿
    Color(0xFFEA580C),  // 橙
    Color(0xFF7C3AED),  // 紫
    Color(0xFFDC2626),  // 红
    Color(0xFF0891B2),  // 青
    Color(0xFFCA8A04),  // 黄
)

private fun formatMoney(v: Double): String =
    when {
        v >= 1e6 -> String.format("%.1f万", v / 1e4)
        else -> String.format("%.0f", v)
    }

private fun formatPct(v: Double): String = String.format("%+.1f%%", v)

/**
 * 收益曲线对比面板: 折线图 + 区间选择 + 区间收益表。
 */
@Composable
fun EquityCurvePanel(
    curveSet: EquityCurveEngine.CurveSet,
    onSegmentChange: (Int, Int) -> Unit,     // 回调区间(起止idx)
    segmentResults: List<EquityCurveEngine.SegmentResult>
) {
    // 区间选择状态(默认全区间)
    val n = curveSet.dates.size
    var range by remember(curveSet) { mutableStateOf(0f..n.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("收益曲线对比（100万本金）", fontWeight = FontWeight.Bold, color = Blue700, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))

            if (n > 0) {
                // 折线图
                EquityLineChart(curveSet)
                Spacer(Modifier.height(8.dp))

                // 区间选择提示
                Text("区间收益对比", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))

                // 快捷区间按钮
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val quickRanges = listOf(
                        "近1月" to 21,
                        "近3月" to 63,
                        "近6月" to 126,
                        "近1年" to n
                    )
                    quickRanges.forEach { (label, days) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                val end = n - 1
                                val start = (n - days).coerceAtLeast(0)
                                range = start.toFloat()..end.toFloat()
                                onSegmentChange(start, end)
                            },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))

                // 区间收益表
                if (segmentResults.isNotEmpty()) {
                    segmentResults.forEach { r ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(r.label, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                            Text(
                                formatPct(r.periodReturn),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (r.periodReturn >= 0) Green600 else Red600
                            )
                        }
                    }
                }
            } else {
                Text("暂无数据", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun EquityLineChart(set: EquityCurveEngine.CurveSet) {
    val curves = set.curves
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFFFAFAFA), RoundedCornerShape(8.dp))
    ) {
        if (curves.isEmpty()) return@Canvas

        val n = set.dates.size
        val w = size.width
        val h = size.height
        val padLeft = 44.dp.toPx()
        val padRight = 12.dp.toPx()
        val padTop = 12.dp.toPx()
        val padBottom = 24.dp.toPx()
        val chartW = w - padLeft - padRight
        val chartH = h - padTop - padBottom

        // 全局 min/max
        var minV = Double.MAX_VALUE
        var maxV = Double.MIN_VALUE
        for (c in curves) {
            for (v in c.values) {
                if (v.isFinite()) {
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
            }
        }
        if (maxV <= minV) { maxV = minV + 1.0 }

        fun xTo(i: Int): Float = padLeft + chartW * (i.toFloat() / (n - 1).coerceAtLeast(1))
        fun yTo(v: Double): Float = padTop + chartH * (1f - ((v - minV) / (maxV - minV)).toFloat())

        // 网格线 + Y 轴标签
        val gridLines = 4
        for (g in 0..gridLines) {
            val frac = g.toFloat() / gridLines
            val y = padTop + chartH * (1 - frac)
            val valAt = minV + (maxV - minV) * frac
            drawLine(Color(0xFFEEEEEE), Offset(padLeft, y), Offset(w - padRight, y), 1f)
            // Y 标签
            drawContext.canvas.nativeCanvas.drawText(
                formatMoney(valAt),
                padLeft - 4.dp.toPx(),
                y + 4.dp.toPx(),
                Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 10.sp.toPx()
                    textAlign = Paint.Align.RIGHT
                }
            )
        }

        // 各曲线
        curves.forEachIndexed { ci, c ->
            val color = CURVE_COLORS[ci % CURVE_COLORS.size]
            val path = Path()
            for (i in 0 until n) {
                val x = xTo(i)
                val y = yTo(c.values[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }

    // 图例
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        set.curves.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(CURVE_COLORS[i % CURVE_COLORS.size], RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(4.dp))
                Text(c.label, fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}
