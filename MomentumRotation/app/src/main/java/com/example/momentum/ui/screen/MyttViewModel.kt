package com.example.momentum.ui.screen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.engine.MyttEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MyttHit(
    val code: String,
    val name: String,
    val sigWanshou: Boolean,
    val sigXlj4: Boolean,
    val signal: Boolean,
    val close: Double,
    val date: String
)

/** 某一天的选股结果。 */
data class DayResult(
    val date: String,
    val combined: List<MyttHit> = emptyList(),
    val wanshou: List<MyttHit> = emptyList(),
    val xlj4: List<MyttHit> = emptyList()
)

data class MyttState(
    val loading: Boolean = false,
    val dayResults: List<DayResult> = emptyList(),
    val error: String? = null,
    val currentDate: String = "",
    val candidateCount: Int = 0,
    val scannedCount: Int = 0
)

class MyttViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MyttState())
    val state: StateFlow<MyttState> = _state

    private val database get() = AppDatabase.get(getApplication())
    private var scanJob: Job? = null

    fun runSelect() {
        if (_state.value.loading) return
        scanJob?.cancel()
        _state.update { MyttState(loading = true) }
        scanJob = viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { scanByDay() }
                _state.update { it.copy(loading = false, dayResults = results, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "选股失败: ${e.message}") }
            }
        }
    }

    private suspend fun scanByDay(): List<DayResult> {
        val metas = database.dailyBarDao().getAllMeta()
        val candidates = metas.filter { it.barCount >= 300 }
        if (candidates.isEmpty()) return emptyList()
        _state.update { it.copy(candidateCount = candidates.size) }

        val nameMap = loadNameMap()

        // 找最近5个交易日
        val recentDates = candidates.flatMap { meta ->
            database.dailyBarDao().getBars(meta.symbol).map { it.date }.filter { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        }.distinct().sortedDescending().take(5)
        if (recentDates.isEmpty()) return emptyList()

        val results = mutableListOf<DayResult>()
        for (date in recentDates) {
            _state.update { it.copy(currentDate = date, scannedCount = 0) }
            val combined = mutableListOf<MyttHit>()
            val wanshou = mutableListOf<MyttHit>()
            val xlj4 = mutableListOf<MyttHit>()

            candidates.forEachIndexed { idx, meta ->
                val bars = database.dailyBarDao().getBars(meta.symbol)
                    .filter { it.date <= date }
                    .sortedBy { it.date }
                if (bars.size < 300) return@forEachIndexed

                val result = computeOnDate(bars)
                if (result != null) {
                    val code = codeFromSymbol(meta.symbol)
                    val hit = MyttHit(
                        code = code,
                        name = nameMap[code] ?: code,
                        sigWanshou = result.sigWanshou,
                        sigXlj4 = result.sigXlj4,
                        signal = result.signal,
                        close = bars.last().close,
                        date = date
                    )
                    if (hit.signal) combined.add(hit)
                    if (hit.sigWanshou) wanshou.add(hit)
                    if (hit.sigXlj4) xlj4.add(hit)
                }
                _state.update { it.copy(scannedCount = idx + 1) }
                if (idx % 50 == 0 && idx > 0) delay(30)
            }
            results.add(DayResult(date, combined, wanshou, xlj4))
        }
        return results
    }

    private fun computeOnDate(bars: List<com.example.momentum.data.db.DailyBarEntity>): MyttEngine.MyttResult? {
        val close = DoubleArray(bars.size) { bars[it].close }
        val open = DoubleArray(bars.size) { bars[it].open }
        val high = DoubleArray(bars.size) { bars[it].high }
        val low = DoubleArray(bars.size) { bars[it].low }
        val volume = DoubleArray(bars.size) { bars[it].volume }
        return try {
            val recent = MyttEngine.computeRecent(close, open, high, low, volume, 1)
            recent.lastOrNull()
        } catch (e: Exception) { null }
    }

    private fun codeFromSymbol(s: String) = s.removePrefix("sh").removePrefix("sz").removePrefix("bj")

    private fun loadNameMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val prefs = getApplication<Application>()
            .getSharedPreferences("astock_cache", Context.MODE_PRIVATE)
        prefs.getString("codes", "")?.let { raw ->
            for (line in raw.split("\n")) {
                val p = line.split(",", limit = 2)
                if (p.size >= 2) map[p[0]] = p[1]
            }
        }
        return map
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}