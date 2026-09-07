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
import com.example.momentum.data.ThsDataSource
import com.example.momentum.ui.theme.*

/**
 * 板块实时指标面板(同花顺数据源)。
 * 展示: 板块涨幅 / 涨跌家数(横条) / 资金净流入 / 成交额 / 涨幅排名。
 */
@Composable
fun BoardSnapshotPanel(
    title: String,
    snapshot: ThsDataSource.BoardSnapshot?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Blue700)
            Spacer(Modifier.height(8.dp))

            if (snapshot == null) {
                Text("板块数据加载中/暂无", fontSize = 13.sp, color = Color.Gray)
            } else {
                // 板块涨幅(大字)
                val riseColor = if (snapshot.pct >= 0) Red600 else Green600
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format("%+.2f%%", snapshot.pct),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = riseColor
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "板块涨幅",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))

                // 涨跌家数横条
                val total = snapshot.upCount + snapshot.downCount
                if (total > 0) {
                    val upRatio = snapshot.upCount.toFloat() / total
                    Text(
                        "涨跌家数: ${snapshot.upCount}涨 / ${snapshot.downCount}跌",
                        fontSize = 12.sp, color = Color.Gray
                    )
                    Spacer(Modifier.height(4.dp))
                    // 横条: 红(涨) 绿(跌)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(7.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .weight(upRatio.coerceIn(0f, 1f))
                                .background(Red600, RoundedCornerShape(7.dp))
                        )
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .weight((1f - upRatio).coerceIn(0f, 1f))
                                .background(Green600, RoundedCornerShape(7.dp))
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 资金净流入
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("资金净流入", fontSize = 12.sp, color = Color.Gray)
                    val flowColor = if (snapshot.netInflow >= 0) Red600 else Green600
                    Text(
                        String.format("%+.2f亿", snapshot.netInflow),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = flowColor
                    )
                }
                Spacer(Modifier.height(4.dp))

                // 成交额
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("成交额", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        String.format("%.1f亿", snapshot.amount),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(4.dp))

                // 涨幅排名
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("涨幅排名", fontSize = 12.sp, color = Color.Gray)
                    Text(snapshot.rank, fontSize = 14.sp, color = Blue700)
                }
            }
        }
    }
}
