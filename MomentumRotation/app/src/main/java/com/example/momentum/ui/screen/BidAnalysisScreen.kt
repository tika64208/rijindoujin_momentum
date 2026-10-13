package com.example.momentum.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.momentum.data.db.BidDataEntity
import com.example.momentum.engine.BidStrategy
import com.example.momentum.ui.component.TabDocSheet
import com.example.momentum.ui.component.TabDocs
import com.example.momentum.ui.theme.*
import com.example.momentum.util.FinanceFormatter

private fun formatPrice(v: Double): String = FinanceFormatter.formatPrice(v)
private fun formatPct(v: Double): String = FinanceFormatter.formatPct(v)
private fun formatMv(circMv: Double): String = FinanceFormatter.formatMarketCap(circMv)
private fun formatFilterVal(v: Double): String = FinanceFormatter.formatFilterNumber(v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BidAnalysisScreen(
    onBack: () -> Unit,
    onStockClick: (code: String, name: String) -> Unit = { _, _ -> },
    viewModel: BidAnalysisViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showBackupSheet by remember { mutableStateOf(false) }
    var showDocSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadFiltered()
        viewModel.loadDates()
    }

    if (showBackupSheet) {
        DailyBackupSheet(
            viewModel = viewModel,
            onDismiss = { showBackupSheet = false }
        )
    }

    if (showDocSheet) {
        TabDocSheet(
            data = TabDocs.BidDoc,
            onDismiss = { showDocSheet = false }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("竞价分析", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showDocSheet = true }) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "说明文档",
                            tint = Color.White
                        )
                    }
                    TextButton(onClick = { showBackupSheet = true }) {
                        Text("备份", color = Color.White, fontSize = 13.sp)
                    }
                    TextButton(
                        onClick = {
                            viewModel.exportFilteredData(context) { _, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !state.loading && state.results.isNotEmpty()
                    ) {
                        Text("导出", color = Color.White, fontSize = 13.sp)
                    }
                    TextButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                        Text("刷新", color = Color.White, fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Blue700, titleContentColor = Color.White
                )
            )
        },
        containerColor = SurfaceBg
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            // 数据时间提示
            if (state.savedAt.isNotEmpty() && !state.loading) {
                item {
                    Text("📊 数据时间: ${state.savedAt}", fontSize = 11.sp, color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                }
            }
            // 搜索框 + 历史查询
            item {
                SearchCodeBar(
                    code = state.searchCode,
                    loading = state.searchLoading,
                    onSearch = { viewModel.searchHistory(it) },
                    onClear = { viewModel.searchHistory("") }
                )
            }
            // 个股查询结果置顶卡片 (方案A: 紧随搜索框下方显示)
            if (state.searchCode.isNotEmpty()) {
                item {
                    SearchResultCard(
                        code = state.searchCode,
                        loading = state.searchLoading,
                        results = state.searchResults,
                        onClose = { viewModel.searchHistory("") },
                        onStockClick = onStockClick
                    )
                }
            }
            // 筛选参数面板
            item {
                FilterPanel(
                    config = state.config,
                    onConfigChange = { viewModel.setFilter(it) }
                )
            }
            if (!state.loading && state.results.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("共 ${state.results.size} 只", fontSize = 12.sp, color = Color.Gray)
                        TextButton(
                            onClick = {
                                viewModel.exportFilteredData(context) { _, msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("📥 导出完整数据(CSV)", fontSize = 12.sp, color = Blue700, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            when {
                state.loading -> {
                    item {
                        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text("获取竞价数据中…", color = Color.Gray, fontSize = 14.sp)
                            }
                        }
                    }
                }
                state.error != null -> {
                    item {
                        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("⚠ ${state.error}", color = Red600, fontSize = 14.sp)
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { viewModel.refresh() }) { Text("重试") }
                            }
                        }
                    }
                }
                state.results.isEmpty() -> {
                    item {
                        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                            Text("暂无竞价数据", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                }
                else -> {
                    val display = state.displayResults
                    item { BidTableHeader(state, { viewModel.setSort(it) }) }
                    items(display, key = { it.item.code }) { result ->
                        BidTableRow(result, onStockClick = onStockClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 12.sp, color = Color.Black),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
            .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(4.dp))
            .background(Color(0xFFFAFAFA), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { innerTextField ->
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 11.sp, color = Color.LightGray)
            }
            innerTextField()
        }
    )
}

@Composable
private fun RangeField(
    label: String,
    minVal: String, onMinChange: (String) -> Unit,
    maxVal: String, onMaxChange: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFF4B5563), modifier = Modifier.width(76.dp))
        CompactInput(minVal, onMinChange, "不限", Modifier.weight(1f))
        Text("~", fontSize = 12.sp, color = Color.Gray)
        CompactInput(maxVal, onMaxChange, "不限", Modifier.weight(1f))
    }
}

@Composable
private fun FilterPanel(config: BidStrategy.BidConfig, onConfigChange: (BidStrategy.BidConfig) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    fun clearAllParams() {
        val zeroConfig = config.copy(
            bidMin = 0.0, bidMax = 0.0,
            priceMin = 0.0, priceMax = 0.0,
            mvMin = 0.0, mvMax = 0.0,
            probMin = 0.0, probMax = 0.0,
            confMin = 0.0, confMax = 0.0,
            scoreMin = 0.0, scoreMax = 0.0
        )
        onConfigChange(zeroConfig)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                Modifier.clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("筛选参数(区间)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Blue700)
                val isDefault = config == BidStrategy.BidConfig.DEFAULT
                val isHighWinRate = config == BidStrategy.BidConfig.HIGH_WIN_RATE
                val isZero = config.bidMin == 0.0 && config.bidMax == 0.0 && config.probMin == 0.0 && config.probMax == 0.0 &&
                        config.confMin == 0.0 && config.confMax == 0.0 && config.mvMin == 0.0 && config.mvMax == 0.0 &&
                        config.priceMin == 0.0 && config.priceMax == 0.0 && config.scoreMin == 0.0 && config.scoreMax == 0.0
                if (isHighWinRate) {
                    Text(" [高胜率]", fontSize = 11.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                } else if (isZero) {
                    Text(" [已清零]", fontSize = 11.sp, color = Blue700, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                } else if (!isDefault) {
                    Text(" [已自定义]", fontSize = 11.sp, color = Amber600, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                } else {
                    Text(" [默认]", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(start = 4.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "▲" else "▼", fontSize = 11.sp, color = Color.Gray)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onConfigChange(BidStrategy.BidConfig.HIGH_WIN_RATE) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("高胜率", fontSize = 12.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = { clearAllParams() },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("清零", fontSize = 12.sp, color = Blue700, fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(if (expanded) "收起" else "展开", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            var bidMin by remember { mutableStateOf(formatFilterVal(config.bidMin)) }
            var bidMax by remember { mutableStateOf(formatFilterVal(config.bidMax)) }
            var priceMin by remember { mutableStateOf(formatFilterVal(config.priceMin)) }
            var priceMax by remember { mutableStateOf(formatFilterVal(config.priceMax)) }
            var mvMin by remember { mutableStateOf(formatFilterVal(config.mvMin)) }
            var mvMax by remember { mutableStateOf(formatFilterVal(config.mvMax)) }
            var probMin by remember { mutableStateOf(formatFilterVal(config.probMin)) }
            var probMax by remember { mutableStateOf(formatFilterVal(config.probMax)) }
            var confMin by remember { mutableStateOf(formatFilterVal(config.confMin)) }
            var confMax by remember { mutableStateOf(formatFilterVal(config.confMax)) }
            var scoreMin by remember { mutableStateOf(formatFilterVal(config.scoreMin)) }
            var scoreMax by remember { mutableStateOf(formatFilterVal(config.scoreMax)) }

            var st by remember { mutableStateOf(config.filterStSuspend) }
            var limitUp by remember { mutableStateOf(config.filterLimitUp) }
            var hs by remember { mutableStateOf(config.marketHs) }
            var cyb by remember { mutableStateOf(config.marketCyb) }
            var kcb by remember { mutableStateOf(config.marketKcb) }

            LaunchedEffect(config) {
                bidMin = formatFilterVal(config.bidMin)
                bidMax = formatFilterVal(config.bidMax)
                priceMin = formatFilterVal(config.priceMin)
                priceMax = formatFilterVal(config.priceMax)
                mvMin = formatFilterVal(config.mvMin)
                mvMax = formatFilterVal(config.mvMax)
                probMin = formatFilterVal(config.probMin)
                probMax = formatFilterVal(config.probMax)
                confMin = formatFilterVal(config.confMin)
                confMax = formatFilterVal(config.confMax)
                scoreMin = formatFilterVal(config.scoreMin)
                scoreMax = formatFilterVal(config.scoreMax)
                st = config.filterStSuspend
                limitUp = config.filterLimitUp
                hs = config.marketHs
                cyb = config.marketCyb
                kcb = config.marketKcb
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = st, onCheckedChange = { st = it })
                    Text("剔ST", fontSize = 12.sp, color = Color.DarkGray)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = limitUp, onCheckedChange = { limitUp = it })
                    Text("剔涨停", fontSize = 12.sp, color = Color.DarkGray)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = hs, onCheckedChange = { hs = it })
                    Text("沪深", fontSize = 12.sp, color = Color.DarkGray)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = cyb, onCheckedChange = { cyb = it })
                    Text("创业板", fontSize = 12.sp, color = Color.DarkGray)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = kcb, onCheckedChange = { kcb = it })
                    Text("科创板", fontSize = 12.sp, color = Color.DarkGray)
                }
            }
            Spacer(Modifier.height(4.dp))

            RangeField("竞价涨幅%", bidMin, { bidMin = it }, bidMax, { bidMax = it })
            RangeField("股价(元)", priceMin, { priceMin = it }, priceMax, { priceMax = it })
            RangeField("总市值(亿)", mvMin, { mvMin = it }, mvMax, { mvMax = it })
            RangeField("涨停概率", probMin, { probMin = it }, probMax, { probMax = it })
            RangeField("可信度", confMin, { confMin = it }, confMax, { confMax = it })
            RangeField("综合评分", scoreMin, { scoreMin = it }, scoreMax, { scoreMax = it })

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        onConfigChange(config.copy(
                            filterStSuspend = st,
                            filterLimitUp = limitUp,
                            marketHs = hs, marketCyb = cyb, marketKcb = kcb,
                            bidMin = bidMin.toDoubleOrNull() ?: 0.0,
                            bidMax = bidMax.toDoubleOrNull() ?: 0.0,
                            priceMin = priceMin.toDoubleOrNull() ?: 0.0,
                            priceMax = priceMax.toDoubleOrNull() ?: 0.0,
                            mvMin = mvMin.toDoubleOrNull() ?: 0.0,
                            mvMax = mvMax.toDoubleOrNull() ?: 0.0,
                            probMin = probMin.toDoubleOrNull() ?: 0.0,
                            probMax = probMax.toDoubleOrNull() ?: 0.0,
                            confMin = confMin.toDoubleOrNull() ?: 0.0,
                            confMax = confMax.toDoubleOrNull() ?: 0.0,
                            scoreMin = scoreMin.toDoubleOrNull() ?: 0.0,
                            scoreMax = scoreMax.toDoubleOrNull() ?: 0.0
                        ))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue700)
                ) { Text("应用", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        onConfigChange(BidStrategy.BidConfig.HIGH_WIN_RATE)
                    },
                    modifier = Modifier.weight(1.1f)
                ) { Text("高胜率", fontSize = 12.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = {
                        onConfigChange(BidStrategy.BidConfig.DEFAULT)
                    },
                    modifier = Modifier.weight(1.1f)
                ) { Text("重置默认", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { clearAllParams() },
                    modifier = Modifier.weight(1f)
                ) { Text("清零", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SearchCodeBar(
    code: String,
    loading: Boolean,
    onSearch: (String) -> Unit,
    onClear: () -> Unit
) {
    var text by remember(code) { mutableStateOf(code) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入代码、名称或拼音查历史…", fontSize = 13.sp, color = Color.Gray) },
            singleLine = true,
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        text = ""
                        onClear()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "清除", modifier = Modifier.size(18.dp), tint = Color.Gray)
                    }
                }
            },
            shape = RoundedCornerShape(10.dp)
        )
        Button(
            onClick = { onSearch(text) },
            enabled = text.isNotBlank() && !loading,
            colors = ButtonDefaults.buttonColors(containerColor = Blue700)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text("查询", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    code: String,
    loading: Boolean,
    results: List<BidDataEntity>,
    onClose: () -> Unit,
    onStockClick: (code: String, name: String) -> Unit
) {
    if (code.isBlank()) return

    val distinctCodes = remember(results) { results.map { it.code }.distinct() }
    val titleText = remember(code, results, distinctCodes) {
        if (results.isEmpty()) {
            "📋 \"$code\" 历史竞价"
        } else if (distinctCodes.size == 1) {
            val first = results.first()
            val nameDisplay = if (first.name.isNotBlank() && first.name != first.code) first.name else ""
            if (nameDisplay.isNotEmpty()) "📋 $nameDisplay (${first.code}) 历史竞价" else "📋 ${first.code} 历史竞价"
        } else {
            "📋 \"$code\" 匹配到 ${distinctCodes.size} 只股票历史"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F7FF)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFFBFDBFE))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = titleText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Blue700
                    )
                    if (results.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(共${results.size}条)",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Blue700)
                        Spacer(Modifier.width(8.dp))
                        Text("正在查询历史竞价…", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                results.isEmpty() -> {
                    Text(
                        text = "暂无匹配的历史竞价数据 (支持输入代码、中文名称如\"茅台\"或拼音缩写)",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                else -> {
                    var expandedAll by remember { mutableStateOf(false) }
                    val displayList = if (expandedAll) results else results.take(5)
                    val showStockInfo = distinctCodes.size > 1

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        displayList.forEach { entity ->
                            HistoryRow(entity, showStockInfo = showStockInfo, onStockClick = onStockClick)
                        }
                    }

                    if (results.size > 5) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(
                                onClick = { expandedAll = !expandedAll },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (expandedAll) "收起" else "查看更多 (共 ${results.size} 条记录) ▼",
                                    fontSize = 12.sp,
                                    color = Blue700,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entity: BidDataEntity,
    showStockInfo: Boolean = false,
    onStockClick: ((code: String, name: String) -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onStockClick != null) Modifier.clickable { onStockClick(entity.code, entity.name) } else Modifier),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showStockInfo && entity.name.isNotBlank()) {
                        Text(entity.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Blue700)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(entity.date.takeLast(5), fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Text(formatPrice(entity.latestPrice), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(formatPct(entity.realChange), fontSize = 12.sp,
                        color = if (entity.realChange >= 0) Red600 else Green600)
                    Spacer(Modifier.width(6.dp))
                    Text("竞价${formatPct(entity.bidChange)}", fontSize = 12.sp, color = Color(0xFF7C3AED))
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showStockInfo) {
                        Text(entity.code, fontSize = 11.sp, color = Color.Gray)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("市值${formatMv(entity.circMv)}", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.width(10.dp))
                    Text("可信度${entity.confidence}", fontSize = 11.sp, color = Color.Gray)
                }
            }
            Text("概率${entity.probability}%", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = if (entity.probability >= 70) Red600 else Amber600)
            Spacer(Modifier.width(8.dp))
            Text("评分${entity.score}", fontSize = 12.sp, color = Blue700)
        }
    }
}

@Composable
private fun BidTableHeader(state: BidAnalysisState, onSort: (BidSortField) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0xFFEFF3F8), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderCellBid("名称/代码", BidSortField.NAME, state, onSort, Modifier.weight(1.3f))
        HeaderCellBid("股价", BidSortField.PRICE, state, onSort, Modifier.weight(0.9f))
        HeaderCellBid("涨幅", BidSortField.REAL, state, onSort, Modifier.weight(0.85f))
        HeaderCellBid("竞价", BidSortField.BID, state, onSort, Modifier.weight(0.85f))
        HeaderCellBid("市值", BidSortField.MV, state, onSort, Modifier.weight(0.95f))
        HeaderCellBid("概率", BidSortField.PROB, state, onSort, Modifier.weight(0.75f))
        HeaderCellBid("可信", BidSortField.CONF, state, onSort, Modifier.weight(0.75f))
        HeaderCellBid("评分", BidSortField.SCORE, state, onSort, Modifier.weight(0.65f))
    }
}

@Composable
private fun HeaderCellBid(label: String, field: BidSortField, state: BidAnalysisState, onSort: (BidSortField) -> Unit, modifier: Modifier) {
    val active = state.sortField == field
    val arrow = if (active) (if (state.sortAscending) "▲" else "▼") else ""
    Row(modifier = modifier.clickable { onSort(field) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = if (active) Blue700 else Color.Gray,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold)
        if (arrow.isNotEmpty()) Text(arrow, fontSize = 8.sp, color = Blue700)
    }
}

@Composable
private fun BidTableRow(
    result: BidStrategy.BidResult,
    onStockClick: (code: String, name: String) -> Unit = { _, _ -> }
) {
    val s = result.item
    Row(
        Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(6.dp))
            .clickable { onStockClick(s.code, s.name) }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 名称 + 代码 (点击均可进入详情)
        Column(
            Modifier
                .weight(1.3f)
                .clickable { onStockClick(s.code, s.name) }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Blue700,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (result.warnText != "-") {
                    Text(result.warnText, fontSize = 9.sp, color = Red600,
                        modifier = Modifier.padding(start = 1.dp))
                }
            }
            Text(s.code, fontSize = 10.sp, color = Color.Gray)
        }
        Text(formatPrice(s.latestPrice), fontSize = 11.sp, color = Blue700, modifier = Modifier.weight(0.9f))
        PctBar(formatPct(s.realChange), s.realChange, Red600, Green600, Modifier.weight(0.85f))
        PctBar(formatPct(s.bidChange), s.bidChange, Color(0xFF7C3AED), Color(0xFF16A34A), Modifier.weight(0.85f))
        Text(formatMv(s.circMv), fontSize = 10.sp, color = Color(0xFF374151), modifier = Modifier.weight(0.95f))
        Text("${result.probability}%", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (result.probability >= 70) Red600 else if (result.probability >= 50) Amber600 else Color.Gray,
            modifier = Modifier.weight(0.75f))
        Text("${result.confidence}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = if (result.confidence >= 75) Blue700 else Color.DarkGray,
            modifier = Modifier.weight(0.75f))
        Text("${result.score}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = Blue700, modifier = Modifier.weight(0.65f))
    }
}
@Composable
private fun PctBar(text: String, value: Double, positiveColor: Color, negativeColor: Color, modifier: Modifier) {
    val absPct = kotlin.math.abs(value).coerceIn(0.0, 20.0)
    val fraction = (absPct / 20.0).toFloat()
    val barColor = if (value >= 0) positiveColor else negativeColor
    Box(modifier = modifier) {
        // 背景色条(宽度按百分比, 放在文字后面)
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    drawRect(barColor.copy(alpha = 0.22f), size = size.copy(width = size.width * fraction))
                }
        )
        Text(text, fontSize = 11.sp, color = barColor, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyBackupSheet(
    viewModel: BidAnalysisViewModel,
    onDismiss: () -> Unit
) {
    val backupFiles by viewModel.backupFiles.collectAsState()
    val context = LocalContext.current
    var isBackingUp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadBackupFiles()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("📁 每日全量数据备份", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Blue700)
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            Text(
                "公共存储路径: Download/MomentumRotation/daily_backup/",
                fontSize = 11.sp, color = Color.Gray,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            Button(
                onClick = {
                    isBackingUp = true
                    viewModel.backupTodayData { _, msg ->
                        isBackingUp = false
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBackingUp,
                colors = ButtonDefaults.buttonColors(containerColor = Blue700),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isBackingUp) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("💾 立即备份今日全量数据(CSV)", fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Text("历史备份文件 (${backupFiles.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.DarkGray)
            Spacer(Modifier.height(6.dp))

            if (backupFiles.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无本地备份文件，点击上方按钮即可生成", fontSize = 12.sp, color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(backupFiles, key = { it.name }) { info ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAFB)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(info.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text("大小: ${info.formattedSize}  ·  日期: ${info.date}", fontSize = 10.sp, color = Color.Gray)
                                }
                                IconButton(onClick = { viewModel.shareBackup(info.file) }) {
                                    Text("📤", fontSize = 14.sp)
                                }
                                IconButton(onClick = { viewModel.deleteBackup(info) }) {
                                    Text("🗑️", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
