package com.example.momentum.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.momentum.ui.theme.*

private fun formatClose(v: Double): String = String.format("%.2f", v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyttScreen(
    onBack: () -> Unit,
    viewModel: MyttViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("MYTT 选股", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Blue700, titleContentColor = Color.White
                )
            )
        },
        containerColor = SurfaceBg
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Button(
                onClick = { viewModel.runSelect() },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue700)
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("选股中 ${state.currentDate} ${state.scannedCount}/${state.candidateCount}")
                } else {
                    Text("运行选股", fontSize = 15.sp)
                }
            }

            state.error?.let {
                Text("⚠ $it", color = Red600, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            if (state.loading) {
                LinearProgressIndicator(
                    progress = { if (state.candidateCount > 0) state.scannedCount.toFloat() / state.candidateCount else 0f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    color = Blue700
                )
            }

            if (!state.loading && state.dayResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("点击\"运行选股\"开始扫描", color = Color.Gray, fontSize = 14.sp)
                }
            }

            if (state.dayResults.isNotEmpty()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    state.dayResults.forEach { day ->
                        item { DaySection(day) }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DaySection(day: DayResult) {
    Column {
        Text(
            "📅 ${day.date}",
            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Blue700,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        if (day.combined.isEmpty() && day.wanshou.isEmpty() && day.xlj4.isEmpty()) {
            Text("  当天无命中信号", fontSize = 12.sp, color = Color.Gray)
        }
        if (day.combined.isNotEmpty()) {
            HitGroupHeader("★ 双信号命中", day.combined.size, Green600, GreenContainer)
            day.combined.forEach { HitRow(it, isStar = true) }
            Spacer(Modifier.height(4.dp))
        }
        if (day.wanshou.isNotEmpty()) {
            HitGroupHeader("万手单命中", day.wanshou.size, Amber600, AmberContainer)
            day.wanshou.forEach { HitRow(it, isStar = false) }
            Spacer(Modifier.height(4.dp))
        }
        if (day.xlj4.isNotEmpty()) {
            HitGroupHeader("寻龙诀4号命中", day.xlj4.size, Blue700, Color(0xFFDBEAFE))
            day.xlj4.forEach { HitRow(it, isStar = false) }
            Spacer(Modifier.height(4.dp))
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun HitGroupHeader(title: String, count: Int, color: Color, bg: Color) {
    Text(
        "$title ($count)",
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun HitRow(hit: MyttHit, isStar: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isStar) {
                Text("★", color = Green600, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(hit.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(hit.code, fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.width(8.dp))
            Text(formatClose(hit.close), fontSize = 13.sp, color = Color.DarkGray)
        }
    }
}