package com.example.momentum.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.momentum.ui.theme.Blue700
import com.example.momentum.ui.theme.Red600
import com.example.momentum.ui.theme.SurfaceBg
import com.example.momentum.util.CrashLogEntry
import com.example.momentum.util.CrashLogManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(CrashLogManager.getLogs()) }
    var showClearDialog by remember { mutableStateOf(false) }

    val crashCount = remember(logs) { logs.count { it.type == "CRASH" } }
    val errorCount = remember(logs) { logs.count { it.type != "CRASH" } }

    val copyAllLogs: () -> Unit = {
        if (logs.isEmpty()) {
            Toast.makeText(context, "暂无日志可复制", Toast.LENGTH_SHORT).show()
        } else {
            val sb = StringBuilder()
            sb.append("=== Momentum App 错误与崩溃日志导出 (共 ${logs.size} 条) ===\n\n")
            logs.forEachIndexed { index, entry ->
                sb.append("[#${index + 1}] [${entry.type}] ${entry.timeFormatted} (${entry.tag})\n")
                sb.append("信息: ${entry.message}\n")
                if (entry.deviceInfo.isNotBlank()) {
                    sb.append("环境:\n${entry.deviceInfo}\n")
                }
                sb.append("调用栈:\n${entry.stackTrace}\n")
                sb.append("--------------------------------------------------\n\n")
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CrashLogs", sb.toString()))
            Toast.makeText(context, "已复制全部日志到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("错误与崩溃日志", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { logs = CrashLogManager.getLogs() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = Color.White)
                    }
                    IconButton(onClick = copyAllLogs) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制全部", tint = Color.White)
                    }
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "清空", tint = Color.White)
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
        ) {
            // 统计仪表卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.BugReport,
                                contentDescription = null,
                                tint = if (crashCount > 0) Red600 else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("日志概览", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Text("共 ${logs.size} 条记录", fontSize = 12.sp, color = Color.Gray)
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Crash 统计
                        Surface(
                            color = if (crashCount > 0) Color(0xFFFEE2E2) else Color(0xFFF3F4F6),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("崩溃 (CRASH)", fontSize = 11.sp, color = if (crashCount > 0) Red600 else Color.Gray)
                                Text("$crashCount", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (crashCount > 0) Red600 else Color.DarkGray)
                            }
                        }
                        // 业务异常统计
                        Surface(
                            color = if (errorCount > 0) Color(0xFFFEF3C7) else Color(0xFFF3F4F6),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("异常 (ERROR)", fontSize = 11.sp, color = if (errorCount > 0) Color(0xFFD97706) else Color.Gray)
                                Text("$errorCount", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (errorCount > 0) Color(0xFFD97706) else Color.DarkGray)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "实时捕获未处理崩溃及网络/WAF等接口报错，便于溯源与排错。",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            // 日志列表
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无错误与崩溃日志", color = Color.Gray, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            CrashLogManager.recordError("TestTag", "这是一条手动记录的测试日志，功能正常！")
                            logs = CrashLogManager.getLogs()
                        }) {
                            Text("生成测试日志", fontSize = 12.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(logs, key = { it.id }) { item ->
                        ErrorLogItemCard(entry = item)
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空确认") },
            text = { Text("确定要清空所有已保存的崩溃和错误日志吗？") },
            confirmButton = {
                TextButton(onClick = {
                    CrashLogManager.clearLogs()
                    logs = emptyList()
                    showClearDialog = false
                    Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                }) {
                    Text("清空", color = Red600)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ErrorLogItemCard(entry: CrashLogEntry) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isCrash = entry.type == "CRASH"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            // 头部：级别徽章、时间、模块
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (isCrash) Red600 else Color(0xFFF59E0B),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = entry.type,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.tag,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Blue700
                    )
                }
                Text(
                    text = entry.timeFormatted,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            Spacer(Modifier.height(8.dp))

            // 错误主要信息
            Text(
                text = entry.message,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (isCrash) Color(0xFF991B1B) else Color.DarkGray,
                lineHeight = 18.sp
            )

            // 展开状态切换指示
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (expanded) "收起详情" else "展开详情",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }

            // 展开的详细内容（环境与调用栈）
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    if (entry.deviceInfo.isNotBlank()) {
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = entry.deviceInfo,
                                fontSize = 11.sp,
                                color = Color(0xFF475569),
                                modifier = Modifier.padding(8.dp),
                                lineHeight = 16.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = entry.stackTrace,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE2E8F0),
                            modifier = Modifier.padding(10.dp),
                            lineHeight = 15.sp
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val text = "[${entry.type}] ${entry.timeFormatted} (${entry.tag})\n${entry.message}\n${entry.deviceInfo}\n\n${entry.stackTrace}"
                                clipboard.setPrimaryClip(ClipData.newPlainText("StackTrace", text))
                                Toast.makeText(context, "已复制单条日志", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("复制本条", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
