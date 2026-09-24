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
import com.example.momentum.data.DarkTradeDataSource
import com.example.momentum.ui.theme.*

private fun fmtYi(v: Double): String = String.format("%.2f亿", v / 1e8)

/**
 * 暗盘交易历史面板。
 */
@Composable
fun DarkTradePanel(
    records: List<DarkTradeDataSource.DarkTradeRecord>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("暗盘交易记录(近10日)", fontWeight = FontWeight.Bold, color = Blue700, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))

            if (records.isEmpty()) {
                Text("近期无暗盘交易", fontSize = 13.sp, color = Color.Gray)
            } else {
                // 表头
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("日期", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                    Text("融资买入", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                    Text("融券卖出", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                    Text("净流入", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                }
                // 数据行(按日期降序)
                records.sortedByDescending { it.date }.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(r.date, fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.weight(1f))
                        Text(
                            fmtYi(r.buyAmount),
                            fontSize = 12.sp, color = Red600, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            fmtYi(r.sellAmount),
                            fontSize = 12.sp, color = Green600, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        val net = r.netAmount  // 直接采用后台字段8(净值)
                        Text(
                            fmtYi(r.netAmount),
                            fontSize = 12.sp, color = if (net >= 0) Red600 else Green600,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "红=融资买入 绿=融券卖出  ·  暗盘为盘后融资融券数据",
                    fontSize = 10.sp, color = Color.Gray
                )
            }
        }
    }
}
