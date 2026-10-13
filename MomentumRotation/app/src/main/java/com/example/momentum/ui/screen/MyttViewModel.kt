package com.example.momentum.ui.screen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.momentum.data.EastmoneyZtDataSource
import com.example.momentum.data.TencentDataSource
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.CacheMetaEntity
import com.example.momentum.data.db.DailyBarEntity
import com.example.momentum.data.db.WanlongHitEntity
import com.example.momentum.engine.MyttEngine
import com.example.momentum.model.Stock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 单只万龙共振命中项 (含出信号后 N 日真实走势统计)。
 */
data class WanlongItem(
    val code: String,
    val name: String,
    val date: String,
    val close: Double,
    val chgPct: Double = 0.0,
    val xlj4_7: Double = 0.0,
    // 后续 N 日真实表现跟踪
    val t1Return: Double? = null,  // T+1 累计涨跌幅%
    val t2Return: Double? = null,  // T+2 累计%
    val t3Return: Double? = null,  // T+3 累计%
    val t5Return: Double? = null,  // T+5 累计%
    val maxReturn: Double? = null  // T+1~T+5 期间最高冲高%
)

/** 某一交易日的万龙选股结果。 */
data class WanlongDayResult(
    val date: String,
    val items: List<WanlongItem> = emptyList()
)

/** 历史信号后验统计看板数据。 */
data class WanlongStats(
    val totalSignals: Int = 0,       // 总信号样本数
    val evaluatedCount: Int = 0,     // 已产出 T+1 数据的样本数
    val t1WinRate: Double = 0.0,     // T+1 胜率% (T+1收盘>买入价)
    val t1AvgReturn: Double = 0.0,   // T+1 平均收益%
    val t2WinRate: Double = 0.0,     // T+2 胜率%
    val t2AvgReturn: Double = 0.0,   // T+2 平均收益%
    val t3WinRate: Double = 0.0,     // T+3 胜率%
    val t3AvgReturn: Double = 0.0,   // T+3 平均收益%
    val avgMaxReturn: Double = 0.0   // 信号后平均最高冲高%
)

data class MyttState(
    val loading: Boolean = false,
    val dayResults: List<WanlongDayResult> = emptyList(),
    val stats: WanlongStats? = null,
    val error: String? = null,
    val currentDate: String = "",
    val candidateCount: Int = 0,
    val scannedCount: Int = 0
)

class MyttViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MyttState())
    val state: StateFlow<MyttState> = _state

    private val database get() = AppDatabase.get(getApplication())
    private val prefs get() = getApplication<Application>().getSharedPreferences("mytt_cache", Context.MODE_PRIVATE)
    private var scanJob: Job? = null

    companion object {
        // 320 根日线完全覆盖 89 周期与 150 周期过滤，精度 100% 对齐
        private const val TARGET_BAR_COUNT = 320
        private const val CONCURRENT_DOWNLOADS = 5
    }

    init {
        // 页面打开时立即从本地 DB 加载已存结果与统计数据，0 秒呈现
        loadFromDb()
    }

    /**
     * 直接从本地 SQLite 读取已持久化的万龙选股数据及后验收益。
     */
    fun loadFromDb() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val entities = database.wanlongDao().getAll()
                if (entities.isNotEmpty()) {
                    val grouped = buildDayResultsAndStats(entities)
                    _state.update {
                        it.copy(
                            dayResults = grouped.first,
                            stats = grouped.second,
                            loading = false
                        )
                    }
                }
            }
        }
    }

    fun runSelect() {
        if (_state.value.loading) return
        scanJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        scanJob = viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { scanByDay() }
                _state.update { it.copy(loading = false, dayResults = results.first, stats = results.second, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "选股失败: ${e.message}") }
            }
        }
    }

    private suspend fun scanByDay(): Pair<List<WanlongDayResult>, WanlongStats?> {
        val today = LocalDate.now().toString()
        val nameMap = loadNameMap().toMutableMap()
        val candidateMap = mutableMapOf<String, String>() // code -> name
        val dateToCandidates = mutableMapOf<String, List<EastmoneyZtDataSource.ZtCandidate>>()
        val recentDates = mutableListOf<String>()

        _state.update { it.copy(currentDate = "检查涨停池与本地缓存...", candidateCount = 5, scannedCount = 0) }

        // 1. 获取最近 5 个实际交易日 (自动跳过周末与法定节假日，带本地持久化缓存)
        var cur = LocalDate.now()
        var daysBack = 0
        while (recentDates.size < 5 && daysBack < 20) {
            if (cur.dayOfWeek != DayOfWeek.SATURDAY && cur.dayOfWeek != DayOfWeek.SUNDAY) {
                val dStr = cur.toString()
                val date8 = dStr.replace("-", "")
                val pool = getOrFetchZtPool(dStr, date8)
                if (pool.isNotEmpty()) {
                    recentDates.add(dStr)
                    dateToCandidates[dStr] = pool
                    pool.forEach {
                        candidateMap[it.code] = it.name
                        nameMap[it.code] = it.name
                    }
                    _state.update { it.copy(scannedCount = recentDates.size) }
                }
            }
            cur = cur.minusDays(1)
            daysBack++
        }

        // 离线兜底
        if (recentDates.isEmpty()) {
            val allDbDates = database.dailyBarDao().getAllMeta().flatMap { meta ->
                database.dailyBarDao().getBars(meta.symbol).takeLast(5).map { it.date }
            }.filter { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            recentDates.addAll(allDbDates.distinct().sortedDescending().take(5))
        }

        if (recentDates.isEmpty()) {
            return emptyList<WanlongDayResult>() to null
        }

        // 2. 日线数据严格「存 DB，当日已更新则不重复下载」
        val uniqueCandidates = candidateMap.keys.toList()
        val neededToFetch = mutableListOf<String>()

        for (code in uniqueCandidates) {
            val symbol = Stock.toMarketPrefix(code)
            val meta = database.dailyBarDao().getMeta(symbol)
            val isFresh = meta != null && meta.barCount >= 300 && meta.lastUpdateDate == today
            if (!isFresh) {
                neededToFetch.add(code)
            }
        }

        // 3. 极速 5 路并发增量补充 320 根 K 线
        if (neededToFetch.isNotEmpty()) {
            _state.update { it.copy(currentDate = "极速同步缺失日线至本地DB...", candidateCount = neededToFetch.size, scannedCount = 0) }

            val semaphore = Semaphore(CONCURRENT_DOWNLOADS)
            var finishedCount = 0

            withContext(Dispatchers.IO) {
                neededToFetch.map { code ->
                    async {
                        semaphore.withPermit {
                            val symbol = Stock.toMarketPrefix(code)
                            try {
                                val bars = TencentDataSource.fetchDailyBars(symbol, TARGET_BAR_COUNT)
                                if (bars.isNotEmpty()) {
                                    val entities = bars.map { b ->
                                        DailyBarEntity(
                                            symbol = symbol, date = b.date, open = b.open,
                                            high = b.high, low = b.low, close = b.close, volume = b.volume
                                        )
                                    }
                                    database.dailyBarDao().insertBars(entities)
                                    database.dailyBarDao().upsertMeta(
                                        CacheMetaEntity(
                                            symbol = symbol,
                                            lastUpdateDate = today,
                                            barCount = database.dailyBarDao().countBars(symbol)
                                        )
                                    )
                                }
                            } catch (_: Exception) {}

                            synchronized(neededToFetch) {
                                finishedCount++
                                val c = finishedCount
                                _state.update { it.copy(scannedCount = c) }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // 4. 计算并持久化「万龙选股 (万手单 AND 寻龙诀4号)」结果
        val newEntitiesToInsert = mutableListOf<WanlongHitEntity>()
        val timeNow = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        for (date in recentDates) {
            _state.update { it.copy(currentDate = date, scannedCount = 0) }

            val candidates = dateToCandidates[date]
            val targetCodes: List<Pair<String, String>> = if (!candidates.isNullOrEmpty()) {
                candidates.map { it.code to it.name }
            } else {
                database.dailyBarDao().getAllMeta().filter { it.barCount >= 300 }.map {
                    val code = codeFromSymbol(it.symbol)
                    code to (nameMap[code] ?: code)
                }
            }
            _state.update { it.copy(candidateCount = targetCodes.size) }

            targetCodes.forEachIndexed { idx, (code, name) ->
                val symbol = Stock.toMarketPrefix(code)
                val bars = database.dailyBarDao().getBars(symbol)
                    .filter { it.date <= date }
                    .sortedBy { it.date }

                if (bars.size >= 300) {
                    val result = computeOnDate(bars)
                    // 仅保留万龙双共振信号！
                    if (result != null && result.signal) {
                        val prevClose = if (bars.size >= 2) bars[bars.size - 2].close else bars.last().close
                        val chgPct = if (prevClose > 0) (bars.last().close - prevClose) / prevClose * 100.0 else 0.0
                        newEntitiesToInsert.add(
                            WanlongHitEntity(
                                code = code,
                                date = date,
                                name = name,
                                close = bars.last().close,
                                chgPct = chgPct,
                                xlj4_7 = result.xlj4_7,
                                savedAt = timeNow
                            )
                        )
                    }
                }
                _state.update { it.copy(scannedCount = idx + 1) }
            }
        }

        // 写入本地 Room 数据库持久化保存
        if (newEntitiesToInsert.isNotEmpty()) {
            database.wanlongDao().insertAll(newEntitiesToInsert)
        }

        // 5. 重新整合全量历史实体，计算 N 日后走势与总体胜率统计
        val allEntities = database.wanlongDao().getAll()
        return buildDayResultsAndStats(allEntities)
    }

    /**
     * 计算并组装各交易日结果及出信号后 N 日涨跌幅跟踪与胜率看板。
     */
    private suspend fun buildDayResultsAndStats(entities: List<WanlongHitEntity>): Pair<List<WanlongDayResult>, WanlongStats?> {
        val groupedByDate = entities.groupBy { it.date }.toSortedMap(reverseOrder())
        val dayResults = mutableListOf<WanlongDayResult>()
        val allEvaluatedItems = mutableListOf<WanlongItem>()

        for ((date, hitEntities) in groupedByDate) {
            val itemsForDay = hitEntities.map { entity ->
                val symbol = Stock.toMarketPrefix(entity.code)
                val allBars = database.dailyBarDao().getBars(symbol).sortedBy { it.date }
                val idx = allBars.indexOfFirst { it.date == entity.date }

                var t1: Double? = null
                var t2: Double? = null
                var t3: Double? = null
                var t5: Double? = null
                var maxR: Double? = null

                if (idx != -1 && entity.close > 0) {
                    val baseClose = entity.close
                    val barT1 = allBars.getOrNull(idx + 1)
                    val barT2 = allBars.getOrNull(idx + 2)
                    val barT3 = allBars.getOrNull(idx + 3)
                    val barT5 = allBars.getOrNull(idx + 5)

                    if (barT1 != null) t1 = (barT1.close - baseClose) / baseClose * 100.0
                    if (barT2 != null) t2 = (barT2.close - baseClose) / baseClose * 100.0
                    if (barT3 != null) t3 = (barT3.close - baseClose) / baseClose * 100.0
                    if (barT5 != null) t5 = (barT5.close - baseClose) / baseClose * 100.0

                    val futureBars = allBars.subList(idx + 1, minOf(idx + 6, allBars.size))
                    if (futureBars.isNotEmpty()) {
                        val maxHigh = futureBars.maxOf { it.high }
                        maxR = (maxHigh - baseClose) / baseClose * 100.0
                    }
                }

                val item = WanlongItem(
                    code = entity.code,
                    name = entity.name,
                    date = entity.date,
                    close = entity.close,
                    chgPct = entity.chgPct,
                    xlj4_7 = entity.xlj4_7,
                    t1Return = t1,
                    t2Return = t2,
                    t3Return = t3,
                    t5Return = t5,
                    maxReturn = maxR
                )
                if (t1 != null) {
                    allEvaluatedItems.add(item)
                }
                item
            }
            dayResults.add(WanlongDayResult(date, itemsForDay.sortedByDescending { it.close }))
        }

        // 统计汇总
        val stats = if (allEvaluatedItems.isNotEmpty()) {
            val t1Items = allEvaluatedItems.filter { it.t1Return != null }
            val t2Items = allEvaluatedItems.filter { it.t2Return != null }
            val t3Items = allEvaluatedItems.filter { it.t3Return != null }
            val maxItems = allEvaluatedItems.filter { it.maxReturn != null }

            WanlongStats(
                totalSignals = entities.size,
                evaluatedCount = t1Items.size,
                t1WinRate = if (t1Items.isNotEmpty()) t1Items.count { it.t1Return!! > 0 } * 100.0 / t1Items.size else 0.0,
                t1AvgReturn = if (t1Items.isNotEmpty()) t1Items.map { it.t1Return!! }.average() else 0.0,
                t2WinRate = if (t2Items.isNotEmpty()) t2Items.count { it.t2Return!! > 0 } * 100.0 / t2Items.size else 0.0,
                t2AvgReturn = if (t2Items.isNotEmpty()) t2Items.map { it.t2Return!! }.average() else 0.0,
                t3WinRate = if (t3Items.isNotEmpty()) t3Items.count { it.t3Return!! > 0 } * 100.0 / t3Items.size else 0.0,
                t3AvgReturn = if (t3Items.isNotEmpty()) t3Items.map { it.t3Return!! }.average() else 0.0,
                avgMaxReturn = if (maxItems.isNotEmpty()) maxItems.map { it.maxReturn!! }.average() else 0.0
            )
        } else if (entities.isNotEmpty()) {
            WanlongStats(totalSignals = entities.size, evaluatedCount = 0)
        } else null

        return dayResults to stats
    }

    private fun getOrFetchZtPool(dStr: String, date8: String): List<EastmoneyZtDataSource.ZtCandidate> {
        val todayStr = LocalDate.now().toString()
        val cached = prefs.getString("zt_$date8", null)
        if (!cached.isNullOrBlank()) {
            return cached.split("\n").mapNotNull { line ->
                val p = line.split(",", limit = 2)
                if (p.size >= 2 && p[0].isNotBlank()) EastmoneyZtDataSource.ZtCandidate(p[0], p[1]) else null
            }
        }
        val pool = EastmoneyZtDataSource.fetchZtPool(date8)
        if (pool.isNotEmpty() && dStr != todayStr) {
            val sb = StringBuilder()
            pool.forEach { sb.append(it.code).append(',').append(it.name).append('\n') }
            prefs.edit().putString("zt_$date8", sb.toString()).apply()
        }
        return pool
    }

    private fun computeOnDate(bars: List<DailyBarEntity>): MyttEngine.MyttResult? {
        val close = DoubleArray(bars.size) { bars[it].close }
        val open = DoubleArray(bars.size) { bars[it].open }
        val high = DoubleArray(bars.size) { bars[it].high }
        val low = DoubleArray(bars.size) { bars[it].low }
        val volume = DoubleArray(bars.size) { bars[it].volume }
        return try {
            val recent = MyttEngine.computeRecent(close, open, high, low, volume, 1)
            recent.lastOrNull()
        } catch (_: Exception) { null }
    }

    private fun codeFromSymbol(s: String) = s.removePrefix("sh").removePrefix("sz").removePrefix("bj")

    private fun loadNameMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val p = getApplication<Application>()
            .getSharedPreferences("astock_cache", Context.MODE_PRIVATE)
        p.getString("codes", "")?.let { raw ->
            for (line in raw.split("\n")) {
                val parts = line.split(",", limit = 2)
                if (parts.size >= 2) map[parts[0]] = parts[1]
            }
        }
        return map
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}
