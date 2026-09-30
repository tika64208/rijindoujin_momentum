package com.example.momentum.ui.screen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.momentum.data.EastmoneyBidDataSource
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.BidDataEntity
import com.example.momentum.engine.BidStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class BidAnalysisState(
    val loading: Boolean = false,
    val results: List<BidStrategy.BidResult> = emptyList(),
    val error: String? = null,
    val config: BidStrategy.BidConfig = BidStrategy.BidConfig.DEFAULT,
    // 排序
    val sortField: BidSortField = BidSortField.PROB,
    val sortAscending: Boolean = false,
    // 数据时间
    val savedAt: String = "",
    // 历史查询
    val searchCode: String = "",
    val searchResults: List<BidDataEntity> = emptyList(),
    val searchLoading: Boolean = false,
    val availableDates: List<String> = emptyList()
) {
    val displayResults: List<BidStrategy.BidResult>
        get() {
            val list = results
            return when (sortField) {
                BidSortField.PROB -> list.sortedBy { if (sortAscending) it.probability else -it.probability }
                BidSortField.SCORE -> list.sortedBy { if (sortAscending) it.score else -it.score }
                BidSortField.BID -> list.sortedBy { if (sortAscending) it.item.bidChange else -it.item.bidChange }
                BidSortField.PRICE -> list.sortedBy { if (sortAscending) it.item.latestPrice else -it.item.latestPrice }
                BidSortField.REAL -> list.sortedBy { if (sortAscending) it.item.realChange else -it.item.realChange }
                BidSortField.CONF -> list.sortedBy { if (sortAscending) it.confidence else -it.confidence }
                BidSortField.MV -> list.sortedBy { if (sortAscending) it.item.circMv else -it.item.circMv }
                BidSortField.CODE -> if (sortAscending) list.sortedBy { it.item.code } else list.sortedByDescending { it.item.code }
                BidSortField.NAME -> if (sortAscending) list.sortedBy { it.item.name } else list.sortedByDescending { it.item.name }
            }
        }
}

enum class BidSortField(val label: String) {
    PROB("概率"), SCORE("评分"), BID("竞价"), PRICE("股价"),
    REAL("涨幅"), CONF("可信度"), MV("总市值"), CODE("代码"), NAME("名称")
}

class BidAnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(BidAnalysisState())
    val state: StateFlow<BidAnalysisState> = _state

    private val database get() = AppDatabase.get(getApplication())
    private val prefs = getApplication<Application>()
        .getSharedPreferences("bid_config", Context.MODE_PRIVATE)
    private var job: Job? = null

    init {
        // 加载保存的筛选配置
        loadSavedConfig()
    }

    private fun loadSavedConfig() {
        val defaultCfg = BidStrategy.BidConfig.DEFAULT
        val saved = BidStrategy.BidConfig(
            bidMin = prefs.getFloat("bidMin", defaultCfg.bidMin.toFloat()).toDouble(),
            bidMax = prefs.getFloat("bidMax", defaultCfg.bidMax.toFloat()).toDouble(),
            priceMin = prefs.getFloat("priceMin", defaultCfg.priceMin.toFloat()).toDouble(),
            priceMax = prefs.getFloat("priceMax", defaultCfg.priceMax.toFloat()).toDouble(),
            mvMin = prefs.getFloat("mvMin", defaultCfg.mvMin.toFloat()).toDouble(),
            mvMax = prefs.getFloat("mvMax", defaultCfg.mvMax.toFloat()).toDouble(),
            probMin = prefs.getFloat("probMin", defaultCfg.probMin.toFloat()).toDouble(),
            probMax = prefs.getFloat("probMax", defaultCfg.probMax.toFloat()).toDouble(),
            confMin = prefs.getFloat("confMin", defaultCfg.confMin.toFloat()).toDouble(),
            confMax = prefs.getFloat("confMax", defaultCfg.confMax.toFloat()).toDouble(),
            scoreMin = prefs.getFloat("scoreMin", defaultCfg.scoreMin.toFloat()).toDouble(),
            scoreMax = prefs.getFloat("scoreMax", defaultCfg.scoreMax.toFloat()).toDouble(),
            filterStSuspend = prefs.getBoolean("stSuspend", defaultCfg.filterStSuspend),
            filterLimitUp = prefs.getBoolean("limitUp", defaultCfg.filterLimitUp),
            marketHs = prefs.getBoolean("marketHs", defaultCfg.marketHs),
            marketCyb = prefs.getBoolean("marketCyb", defaultCfg.marketCyb),
            marketKcb = prefs.getBoolean("marketKcb", defaultCfg.marketKcb)
        )
        if (prefs.contains("bidMin")) {
            // 若为旧版残留的过严默认配置(bidMin==7.0且probMax==65.0)，自动重置为新默认值
            if (saved.bidMin == 7.0 && saved.probMax == 65.0) {
                val clean = BidStrategy.BidConfig.DEFAULT
                saveConfig(clean)
                _state.update { it.copy(config = clean) }
            } else {
                _state.update { it.copy(config = saved) }
            }
        } else {
            _state.update { it.copy(config = BidStrategy.BidConfig.DEFAULT) }
        }
    }

    private fun saveConfig(cfg: BidStrategy.BidConfig) {
        prefs.edit()
            .putFloat("bidMin", cfg.bidMin.toFloat())
            .putFloat("bidMax", cfg.bidMax.toFloat())
            .putFloat("priceMin", cfg.priceMin.toFloat())
            .putFloat("priceMax", cfg.priceMax.toFloat())
            .putFloat("mvMin", cfg.mvMin.toFloat())
            .putFloat("mvMax", cfg.mvMax.toFloat())
            .putFloat("probMin", cfg.probMin.toFloat())
            .putFloat("probMax", cfg.probMax.toFloat())
            .putFloat("confMin", cfg.confMin.toFloat())
            .putFloat("confMax", cfg.confMax.toFloat())
            .putFloat("scoreMin", cfg.scoreMin.toFloat())
            .putFloat("scoreMax", cfg.scoreMax.toFloat())
            .putBoolean("stSuspend", cfg.filterStSuspend)
            .putBoolean("limitUp", cfg.filterLimitUp)
            .putBoolean("marketHs", cfg.marketHs)
            .putBoolean("marketCyb", cfg.marketCyb)
            .putBoolean("marketKcb", cfg.marketKcb)
            .apply()
    }

    /** 判断是否在竞价时段(9:15~9:30, 周一~周五) */
    fun isBidSession(): Boolean {
        val now = LocalTime.now()
        val dow = LocalDate.now().dayOfWeek
        if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) return false
        return !now.isBefore(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(9, 32))
    }

    /** 拉取竞价数据 → 计算评分 → 全量(含排除)存入 DB。
     * 9:32 后今天已有数据则不再覆盖(保护竞价快照)。 */
    fun refresh() {
        android.util.Log.d("BidVM", "refresh() called, loading=${_state.value.loading}")
        if (_state.value.loading) return
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val today = LocalDate.now().toString()
                val now = LocalTime.now()
                val existingCount = withContext(Dispatchers.IO) {
                    database.bidDao().countByDate(today)
                }
                // 竞价时段(9:15~9:30)可覆盖; 9:32后已有数据则锁定
                if (!isBidSession() && existingCount > 0 && now.isAfter(LocalTime.of(9, 32))) {
                    android.util.Log.d("BidVM", "非竞价时段且已有数据,跳过覆盖")
                    _state.update { it.copy(loading = false) }
                    loadFiltered()
                    return@launch
                }

                android.util.Log.d("BidVM", "开始拉取竞价数据...")
                val rawItems = withContext(Dispatchers.IO) {
                    EastmoneyBidDataSource.fetchAll()
                }
                android.util.Log.d("BidVM", "拉取完成, 原始数据条数=${rawItems.size}")
                val cfg = _state.value.config
                val savedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

                withContext(Dispatchers.IO) {
                    val entities = rawItems.map { item ->
                        val r = BidStrategy.compute(item, cfg)
                        BidDataEntity(
                            code = item.code,
                            date = today,
                            name = item.name,
                            latestPrice = item.latestPrice,
                            realChange = item.realChange,
                            openPrice = item.openPrice,
                            volume = item.volume,
                            amount = item.amount,
                            turnover = item.turnover,
                            circMv = item.circMv,
                            bidChange = item.bidChange,
                            warnFlag = item.warnFlag,
                            industry = item.industry,
                            conceptQuick = item.conceptQuick,
                            probability = r.probability,
                            confidence = r.confidence,
                            score = r.score,
                            bidTurnover = r.bidTurnover,
                            entityChange = r.entityChange,
                            warnText = r.warnText,
                            reason = r.reason,
                            savedAt = savedAt,
                            excluded = r.excluded,
                            excludeReason = r.excludeReason
                        )
                    }
                    // 全量存入 DB (含 excluded, REPLACE 覆盖同日)
                    database.bidDao().insertAll(entities)
                }
                _state.update { it.copy(loading = false) }
                // 重新按当前筛选加载展示
                loadFiltered()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "获取竞价数据失败: ${e.message}") }
            }
        }
    }

    /** 从 DB 按当前筛选条件加载展示列表 */
    fun loadFiltered() {
        android.util.Log.d("BidVM", "loadFiltered() called")
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val cfg = _state.value.config
            val entities = withContext(Dispatchers.IO) {
                database.bidDao().getByDate(today)
            }
            android.util.Log.d("BidVM", "DB返回条数=${entities.size}, cfg: bidMin=${cfg.bidMin} probMax=${cfg.probMax} mvMin=${cfg.mvMin} priceMin=${cfg.priceMin}")
            // 动态过滤（不再用 compute 的 excluded，而是按 config 实时筛）
            val results = entities.mapNotNull { e ->
                if (applyFilter(e, cfg)) {
                    BidStrategy.BidResult(
                        item = BidStrategy.BidItem(
                            code = e.code, name = e.name, latestPrice = e.latestPrice,
                            realChange = e.realChange, openPrice = e.openPrice,
                            volume = e.volume, amount = e.amount, turnover = e.turnover,
                            circMv = e.circMv, highPrice = 0.0, totalMv = 0.0,
                            bidChange = e.bidChange, warnFlag = e.warnFlag,
                            industry = e.industry, province = "", conceptQuick = e.conceptQuick
                        ),
                        probability = e.probability, confidence = e.confidence,
                        bidTurnover = e.bidTurnover, entityChange = e.entityChange,
                        score = e.score, bidVolume = 0, warnText = e.warnText,
                        excluded = false, excludeReason = "", reason = e.reason
                    )
                } else null
            }.sortedByDescending { it.probability }
            // 取第一条的 savedAt 显示数据时间; 限制 500 条首屏渲染(防 OOM)
            val saved = entities.firstOrNull()?.savedAt ?: ""
            _state.update { it.copy(results = results.take(500), savedAt = saved) }
        }
    }

    /** 实时筛选判断（对齐仓鼠竞价系统V3.2） */
    private fun applyFilter(e: com.example.momentum.data.db.BidDataEntity, cfg: BidStrategy.BidConfig): Boolean {
        // ST/停牌剔除
        if (cfg.filterStSuspend && e.name.contains("ST", ignoreCase = true)) return false
        if (cfg.filterStSuspend && e.name.contains("停", ignoreCase = true)) return false
        // 昨日涨停剔除（用 warnFlag >= 5 近似）
        if (cfg.filterLimitUp && e.warnFlag >= 5) return false
        // 市场筛选
        if (!cfg.marketHs && !cfg.marketCyb && !cfg.marketKcb) return false
        val isHs = (e.code.startsWith("6") && !e.code.startsWith("688")) || e.code.startsWith("00")
        val isCyb = e.code.startsWith("3")
        val isKcb = e.code.startsWith("688")
        if (isHs && !cfg.marketHs) return false
        if (isCyb && !cfg.marketCyb) return false
        if (isKcb && !cfg.marketKcb) return false
        // 竞价涨幅: min ~ max
        if (cfg.bidMin > 0 && e.bidChange < cfg.bidMin) return false
        if (cfg.bidMax > 0 && e.bidChange > cfg.bidMax) return false
        // 涨停概率: min ~ max
        if (cfg.probMin > 0 && e.probability < cfg.probMin) return false
        if (cfg.probMax > 0 && e.probability > cfg.probMax) return false
        // 可信度: min ~ max
        if (cfg.confMin > 0 && e.confidence < cfg.confMin) return false
        if (cfg.confMax > 0 && e.confidence > cfg.confMax) return false
        // 评分: min ~ max
        if (cfg.scoreMin > 0 && e.score < cfg.scoreMin) return false
        if (cfg.scoreMax > 0 && e.score > cfg.scoreMax) return false
        // 流通市值/总市值: min ~ max（circMv 是 f20 总市值(元)，/1e8 转亿）
        if (cfg.mvMin > 0 && e.circMv / 1e8 < cfg.mvMin) return false
        if (cfg.mvMax > 0 && e.circMv / 1e8 > cfg.mvMax) return false
        // 股价: min ~ max
        if (cfg.priceMin > 0 && e.latestPrice < cfg.priceMin) return false
        if (cfg.priceMax > 0 && e.latestPrice > cfg.priceMax) return false
        // 竞价手数大单筛选
        if (cfg.filterLargeVolume && e.volume < cfg.filterLargeVolumeThreshold) return false
        return true
    }

    /** 更新筛选配置并重新加载 */
    fun setFilter(config: BidStrategy.BidConfig) {
        _state.update { it.copy(config = config) }
        saveConfig(config)
        loadFiltered()
    }

    fun setSort(field: BidSortField) {
        val s = _state.value
        if (s.sortField == field) {
            _state.update { it.copy(sortAscending = !it.sortAscending) }
        } else {
            _state.update { it.copy(sortField = field, sortAscending = false) }
        }
    }

    /** 查询指定股票的历史竞价数据 */
    fun searchHistory(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList(), searchCode = "") }
            return
        }
        _state.update { it.copy(searchCode = trimmed, searchLoading = true) }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                database.bidDao().getHistory(trimmed, 30)
            }
            _state.update { it.copy(searchResults = results, searchLoading = false) }
        }
    }

    /** 加载有竞价数据的日期列表 */
    fun loadDates() {
        viewModelScope.launch {
            val dates = withContext(Dispatchers.IO) {
                database.bidDao().getDates(30)
            }
            _state.update { it.copy(availableDates = dates) }
        }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}