package com.example.momentum.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.engine.BacktestEngine
import com.example.momentum.engine.MomentumSignal
import com.example.momentum.engine.MomentumTrend
import com.example.momentum.ui.theme.*

private fun formatPct(v: Double): String =
    if (v.isNaN()) "—" else String.format("%+.1f%%", v * 100)

/**
 * 动量翻转预警面板。
 */
@Composable
fun MomentumTrendPanel(trends: List<MomentumTrend>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "动量翻转预警",
                fontWeight = FontWeight.Bold,
                color = Blue700,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            trends.forEach { t ->
                val (color, bg) = when (t.signal) {
                    MomentumSignal.ACCEL -> Green600 to GreenContainer
                    MomentumSignal.DECAY -> Amber600 to AmberContainer
                    MomentumSignal.REVERSE -> Red600 to RedContainer
                    MomentumSignal.WEAK -> Color.Gray to Color(0xFFF1F5F9)
                    MomentumSignal.INSUFFICIENT -> Color.Gray to Color(0xFFF1F5F9)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .background(bg, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(t.label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
                        Text(
                            "短窗${formatPct(t.shortReturn)} / 中窗${formatPct(t.midReturn)}",
                            fontSize = 10.sp, color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

/**
 * 回测收益对比面板。
 */
@Composable
fun BacktestPanel(result: BacktestEngine.BacktestResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "回测收益对比（近一年）",
                fontWeight = FontWeight.Bold,
                color = Blue700,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(8.dp))

            // 核心对比: 轮动 vs 等权
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    "轮动策略",
                    result.rotation,
                    Color(0xFFDBEAFE),
                    Blue700,
                    Modifier.weight(1f)
                )
                MetricCard(
                    "等权持有",
                    result.equalWeight,
                    Color(0xFFF1F5F9),
                    Color(0xFF334155),
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // 各单股
            Text("各单股表现", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            result.singles.forEach { (code, perf) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(code, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.width(70.dp))
                    Text(
                        "累计 ${formatPct(perf.totalReturn)}",
                        fontSize = 12.sp,
                        color = if (perf.totalReturn >= 0) Green600 else Red600,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "回撤 ${formatPct(perf.maxDrawdown)}",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "注：回测基于历史前复权数据，不代表未来收益；动量轮动在震荡市可能跑输。",
                fontSize = 10.sp, color = Color.Gray
            )
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    perf: BacktestEngine.Perf,
    bg: Color,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(bg, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(title, fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatPct(perf.totalReturn)}",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = accent
        )
        Text(
            "年化 ${formatPct(perf.annualReturn)}",
            fontSize = 11.sp,
            color = accent
        )
        Text(
            "最大回撤 ${formatPct(perf.maxDrawdown)}",
            fontSize = 11.sp,
            color = if (perf.maxDrawdown < -0.3) Red600 else Color.Gray
        )
    }
}
