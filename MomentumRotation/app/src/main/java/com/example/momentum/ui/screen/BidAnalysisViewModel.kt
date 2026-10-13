package com.example.momentum.ui.screen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.momentum.data.BidRepository
import com.example.momentum.data.db.BidDataEntity
import com.example.momentum.engine.BidStrategy
import com.example.momentum.util.BackupFileInfo
import com.example.momentum.util.DailyBackupManager
import com.example.momentum.util.DataExporter
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 竞价分析页面状态。
 */
data class BidAnalysisState(
    val loading: Boolean = false,
    val results: List<BidStrategy.BidResult> = emptyList(),
    val error: String? = null,
    val config: BidStrategy.BidConfig = BidStrategy.BidConfig.DEFAULT,
    val sortField: BidSortField = BidSortField.PROB,
    val sortAscending: Boolean = false,
    val savedAt: String = "",
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

/**
 * 竞价分析 ViewModel。
 * 纯粹负责 UI StateFlow 驱动与用户交互处理，数据处理统一委托给 BidRepository。
 */
class BidAnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: BidRepository = BidRepository.create(application)

    private val _state = MutableStateFlow(BidAnalysisState())
    val state: StateFlow<BidAnalysisState> = _state

    private var job: Job? = null

    private val _backupFiles = MutableStateFlow<List<BackupFileInfo>>(emptyList())
    val backupFiles: StateFlow<List<BackupFileInfo>> = _backupFiles

    init {
        _state.update { it.copy(config = repository.loadConfig()) }
        loadBackupFiles()
    }

    /**
     * 读取本地所有每日备份文件。
     */
    fun loadBackupFiles() {
        viewModelScope.launch {
            val files = DailyBackupManager.listBackupFiles(getApplication())
            _backupFiles.value = files
        }
    }

    /**
     * 手动触发今日全量竞价数据备份。
     */
    fun backupTodayData(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = DailyBackupManager.backupTodayBidData(getApplication())
            res.onSuccess { info ->
                loadBackupFiles()
                onResult(true, "已成功备份今日全量数据: ${info.name}")
            }.onFailure { err ->
                onResult(false, "备份失败: ${err.message}")
            }
        }
    }

    /**
     * 发送分享指定的备份文件。
     */
    fun shareBackup(file: File) {
        DailyBackupManager.shareBackupFile(getApplication(), file)
    }

    /**
     * 删除指定的备份文件。
     */
    fun deleteBackup(info: BackupFileInfo) {
        DailyBackupManager.deleteBackupFile(getApplication(), info)
        loadBackupFiles()
    }

    fun isBidSession(): Boolean = repository.isBidSession()

    /**
     * 拉取全市场竞价与盘中最新行情并更新本地快照。
     */
    fun refresh() {
        if (_state.value.loading) return
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            val result = repository.refresh(_state.value.config)
            result.onSuccess {
                _state.update { it.copy(loading = false) }
                loadFiltered(autoRefreshIfStale = false)
            }.onFailure { err ->
                _state.update { it.copy(loading = false, error = "获取竞价数据失败: ${err.message}") }
            }
        }
    }

    /**
     * 从本地 DB 按当前筛选条件加载展示列表。
     * 若数据为空，或已保存的数据属于早盘开盘前(<=09:30)且当前已经开盘，自动后台拉取一次最新盘中行情。
     */
    fun loadFiltered(autoRefreshIfStale: Boolean = true) {
        viewModelScope.launch {
            val (results, savedAt) = repository.getFilteredResults(_state.value.config)
            _state.update {
                it.copy(results = results.take(500), savedAt = savedAt)
            }
            if (autoRefreshIfStale) {
                val now = java.time.LocalTime.now()
                val isTradingOrAfter = now.isAfter(java.time.LocalTime.of(9, 30))
                val isBeforeOpenSnapshot = savedAt.isNotEmpty() && savedAt < "09:30:00"
                if (results.isEmpty() || (isTradingOrAfter && isBeforeOpenSnapshot)) {
                    refresh()
                }
            }
        }
    }

    /**
     * 更新筛选配置并持久化，即时刷新展示。
     */
    fun setFilter(config: BidStrategy.BidConfig) {
        _state.update { it.copy(config = config) }
        repository.saveConfig(config)
        loadFiltered()
    }

    /**
     * 切换排序字段或排序方向。
     */
    fun setSort(field: BidSortField) {
        val s = _state.value
        if (s.sortField == field) {
            _state.update { it.copy(sortAscending = !it.sortAscending) }
        } else {
            _state.update { it.copy(sortField = field, sortAscending = false) }
        }
    }

    /**
     * 检索指定股票代码的历史竞价数据。
     */
    fun searchHistory(code: String) {
        val clean = code.trim()
        if (clean.isEmpty()) {
            _state.update { it.copy(searchCode = "", searchResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searchCode = clean, searchLoading = true) }
            val history = repository.searchHistory(clean)
            _state.update { it.copy(searchResults = history, searchLoading = false) }
        }
    }

    /**
     * 读取已有竞价数据的历史日期列表。
     */
    fun loadDates() {
        viewModelScope.launch {
            val dates = repository.getAvailableDates()
            _state.update { it.copy(availableDates = dates) }
        }
    }

    /**
     * 导出当前筛选配置下的完整数据为 CSV 并唤起系统分享。
     */
    fun exportFilteredData(context: Context, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val fullResults = repository.getAllFilteredResults(_state.value.config)
                if (fullResults.isEmpty()) {
                    onResult(false, "当前筛选条件下无数据可导出")
                    return@launch
                }
                val csv = DataExporter.generateBidCsv(fullResults)
                val exportRes = DataExporter.exportAndShare(context, "竞价分析筛选结果", csv)
                exportRes.onSuccess { name ->
                    onResult(true, "已导出 ${fullResults.size} 只完整数据 ($name)")
                }.onFailure { err ->
                    onResult(false, "导出失败: ${err.message}")
                }
            } catch (e: Exception) {
                onResult(false, "导出异常: ${e.message}")
            }
        }
    }
}
