package com.example.momentum.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.data.RebalancePeriod
import com.example.momentum.data.Settings
import com.example.momentum.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: MomentumViewModel
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("策略设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Blue700,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = SurfaceBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 波动率惩罚 λ
            SettingSlider(
                title = "波动率惩罚系数 λ",
                desc = "越大越求稳：高波动股票被降权更多",
                value = settings.volPenaltyLambda.toFloat(),
                range = 0f..1f,
                onValueChange = { viewModel.saveLambda(it.toDouble()) },
                valueText = String.format("%.2f", settings.volPenaltyLambda)
            )

            // 买入数量 Top N
            SettingSlider(
                title = "轮动选股数量",
                desc = "推荐排名前 N 只「买入」，末尾 N 只「卖出」",
                value = settings.topCount.toFloat(),
                range = 1f..5f,
                onValueChange = { viewModel.saveTopCount(it.toInt()) },
                valueText = "${settings.topCount} 只",
                steps = 3
            )

            // 调仓周期
            RebalancePeriodPicker(
                current = settings,
                onSelect = { viewModel.savePeriod(it.days) }
            )

            // 提醒开关
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("调仓提醒通知", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            "到期自动弹通知提醒你重新轮动",
                            fontSize = 12.sp, color = Color.Gray
                        )
                    }
                    Switch(
                        checked = settings.reminderOn,
                        onCheckedChange = { viewModel.saveReminderOn(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Green600)
                    )
                }
            }

            // 说明
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("参数说明", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Blue700)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "λ 越高，波动大的股票被惩罚越多，排名更偏向「涨得稳」。\n" +
                            "买入/卖出比例控制三档各有多少只，建议 15%~25%。\n" +
                            "调仓周期不宜过频，A股建议双周或每月。",
                        fontSize = 12.sp, color = Color.DarkGray, lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    desc: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueText: String,
    steps: Int = 0
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(valueText, color = Blue700, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text(desc, fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = Blue700,
                    activeTrackColor = Blue700,
                    inactiveTrackColor = Color(0xFFDBEAFE)
                )
            )
        }
    }
}

@Composable
private fun RebalancePeriodPicker(
    current: Settings,
    onSelect: (RebalancePeriod) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("调仓周期", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("多久提醒一次重新轮动", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RebalancePeriod.entries.forEach { period ->
                    val selected = current.periodDays == period.days
                    FilterChip(
                        selected = selected,
                        onClick = { onSelect(period) },
                        label = { Text(period.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Green600,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }
    }
}
