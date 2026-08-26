package com.example.momentum.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.momentum.data.DataOrigin
import com.example.momentum.data.RebalancePeriod
import com.example.momentum.data.Settings
import com.example.momentum.data.SettingsRepository
import com.example.momentum.data.StockRepository
import com.example.momentum.data.TencentDataSource
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.engine.BacktestEngine
import com.example.momentum.engine.MomentumEngine
import com.example.momentum.engine.MomentumTrend
import com.example.momentum.model.Recommendation
import com.example.momentum.model.Stock
import com.example.momentum.notify.RebalanceReminderWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val watchlist: List<Stock> = SAMPLE_WATCHLIST,
    val inputCode: String = "",
    val inputName: String = "",
    val recommendations: List<Recommendation> = emptyList(),
    val meta: MomentumEngine.RankMeta? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val dataOrigin: DataOrigin? = null,
    val trends: List<MomentumTrend> = emptyList(),
    val backtest: BacktestEngine.BacktestResult? = null,
    val analyzing: Boolean = false,
    val searchResults: List<TencentDataSource.SearchResult> = emptyList(),
    val searching: Boolean = false
) {
    companion object {
        val SAMPLE_WATCHLIST = listOf(
            Stock("603991", "领先股份"),
            Stock("300285", "国瓷材料"),
            Stock("600183", "生益科技"),
            Stock("300903", "科翔股份"),
            Stock("300054", "鼎龙股份")
        )
    }
}

class MomentumViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val app get() = getApplication<Application>()

    private val settingsRepo: SettingsRepository by lazy { SettingsRepository(app) }
    private val repository: StockRepository by lazy {
        StockRepository(AppDatabase.get(app))
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** 设置的响应式流, 供设置页绑定。 */
    val settings: StateFlow<Settings> = settingsRepo.settingsFlow
        .let { flow ->
            val s = MutableStateFlow(Settings())
            viewModelScope.launch {
                flow.collect { s.value = it }
            }
            s
        }

    private var searchJob: Job? = null

    /** 输入变化: 更新输入框, 若输入的是名称(非纯数字)则触发搜索防抖。 */
    fun updateInput(keyword: String) {
        _state.update { it.copy(inputCode = keyword, inputName = "") }
        searchJob?.cancel()
        val kw = keyword.trim()
        // 纯数字(代码)不搜索; 名称/拼音才搜索
        if (kw.isEmpty() || (kw.length <= 6 && kw.all { it.isDigit() })) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        if (kw.length < 2) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            delay(350)  // 防抖
            try {
                val results = repository.searchStocks(kw)
                _state.update { it.copy(searchResults = results, searching = false) }
            } catch (e: Exception) {
                _state.update { it.copy(searchResults = emptyList(), searching = false) }
            }
        }
    }

    /** 从搜索候选点选, 直接加入自选池。 */
    fun addStockFromSearch(result: TencentDataSource.SearchResult) {
        val s = _state.value
        if (s.watchlist.any { it.code == result.code }) {
            _state.update { it.copy(error = "该股票已在自选池中", inputCode = "", searchResults = emptyList()) }
            return
        }
        _state.update {
            it.copy(
                watchlist = it.watchlist + Stock(result.code, result.name),
                inputCode = "",
                inputName = "",
                searchResults = emptyList(),
                error = null
            )
        }
    }

    fun addStock() {
        val s = _state.value
        val code = s.inputCode.trim()
        val name = s.inputName.trim().ifBlank { code }
        if (code.length != 6 || code.any { !it.isDigit() }) {
            _state.update { it.copy(error = "请输入 6 位代码, 或用名称搜索后点选") }
            return
        }
        if (s.watchlist.any { it.code == code }) {
            _state.update { it.copy(error = "该股票已在自选池中") }
            return
        }
        _state.update {
            it.copy(
                watchlist = it.watchlist + Stock(code, name),
                inputCode = "",
                inputName = "",
                searchResults = emptyList(),
                error = null
            )
        }
    }

    fun removeStock(code: String) {
        _state.update { it.copy(watchlist = it.watchlist.filter { s -> s.code != code }) }
    }

    fun runRanking() {
        val stocks = _state.value.watchlist
        if (stocks.size < 2) {
            _state.update { it.copy(error = "至少需要 2 只自选股才能轮动比较") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                // 用当前设置构造策略配置
                val cfg = settingsRepo.toStrategyConfig(settings.value)
                val (recs, meta, origin) = repository.rankWatchlist(stocks, cfg)
                _state.update {
                    it.copy(
                        recommendations = recs,
                        meta = meta,
                        dataOrigin = origin,
                        loading = false
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "计算失败: ${e.message}") }
            }
        }
    }

    // ---- 设置保存 ----
    fun saveLambda(v: Double) {
        viewModelScope.launch { settingsRepo.saveLambda(v) }
    }

    fun saveTopCount(v: Int) {
        viewModelScope.launch { settingsRepo.saveTopCount(v) }
    }

    // ---- 动量翻转预警 ----
    fun runTrendAnalysis() {
        val stocks = _state.value.watchlist
        viewModelScope.launch {
            _state.update { it.copy(analyzing = true, error = null) }
            try {
                val trends = repository.computeTrends(stocks)
                _state.update { it.copy(trends = trends, analyzing = false) }
            } catch (e: Exception) {
                _state.update { it.copy(analyzing = false, error = "分析失败: ${e.message}") }
            }
        }
    }

    // ---- 回测对比 ----
    fun runBacktest() {
        val stocks = _state.value.watchlist
        viewModelScope.launch {
            _state.update { it.copy(analyzing = true, error = null) }
            try {
                val result = repository.backtest(stocks)
                _state.update { it.copy(backtest = result, analyzing = false) }
            } catch (e: Exception) {
                _state.update { it.copy(analyzing = false, error = "回测失败: ${e.message}") }
            }
        }
    }

    fun savePeriod(days: Int) {
        viewModelScope.launch {
            settingsRepo.savePeriod(days)
            rescheduleReminder()
        }
    }

    fun saveReminderOn(on: Boolean) {
        viewModelScope.launch {
            settingsRepo.saveReminderOn(on)
            rescheduleReminder()
        }
    }

    /** 按当前设置重排调仓提醒任务。 */
    fun rescheduleReminder() {
        viewModelScope.launch {
            val s = settings.value
            RebalanceReminderWorker.schedule(app, s.periodDays, s.reminderOn)
        }
    }
}
