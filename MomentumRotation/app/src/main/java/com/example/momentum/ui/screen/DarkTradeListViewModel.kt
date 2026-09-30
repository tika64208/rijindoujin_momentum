package com.example.momentum.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.momentum.data.DarkTradeDataSource
import com.example.momentum.data.MainFundDataSource
import com.example.momentum.data.MarginDataSource
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.DarkTradeDataEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.HashMap
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 单只股票的展开详情(暗盘 + 资金流 + 两融)。
 */
data class StockDetailData(
    val code: String,
    val name: String,
    // 暗盘(现有 darktrade 字段 6/7/8)
    val darkBuy: Double,       // 暗盘净买
    val darkSell: Double,      // 暗盘净卖
    val darkNet: Double,       // 暗盘净流入
    // 资金流(push2his)
    val mainNet: Double,       // 主力净流入
    val smallNet: Double,      // 小单净流入
    val midNet: Double,        // 中单净流入
    val bigNet: Double,        // 大单净流入
    val superNet: Double,      // 超大单净流入
    val mainRatio: Double,     // 主力占比%
    val smallRatio: Double,    // 小单占比%
    // 两融
    val marginBuy: Double,     // 融资买入
    val marginRepay: Double,   // 融资偿还
    val marginNet: Double,     // 融资净买入
    val marginBalance: Double, // 融资余额
    val loadingDetail: Boolean = false
)

/**
 * 全量暗盘列表页状态。
 */
data class DarkTradeListState(
    val records: List<DarkTradeDataSource.DarkTradeRecord> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val date: String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")),
    val nextPage: Int = 1,
    val totalCount: Int = -1,
    val hasMore: Boolean = true,
    // 展开的股票代码 -> 详情数据
    val expandedCode: String? = null,
    val detail: StockDetailData? = null,
    // 排序 + 搜索
    val sortField: DarkSortField = DarkSortField.NET,
    val sortAscending: Boolean = false,
    val searchKeyword: String = ""
) {
    /** 排序/搜索后的展示列表(对已加载的 records 做本地过滤排序)。 */
    val displayRecords: List<DarkTradeDataSource.DarkTradeRecord>
        get() {
            var list = records
            // 搜索过滤(按代码或名称)
            if (searchKeyword.isNotBlank()) {
                val kw = searchKeyword.trim()
                list = list.filter {
                    it.code.contains(kw) || it.name.contains(kw, ignoreCase = true)
                }
            }
            // 排序
            return when (sortField) {
                DarkSortField.NET -> list.sortedBy { if (sortAscending) it.netAmount else -it.netAmount }
                DarkSortField.BUY -> list.sortedBy { if (sortAscending) it.buyAmount else -it.buyAmount }
                DarkSortField.SELL -> list.sortedBy { if (sortAscending) it.sellAmount else -it.sellAmount }
                DarkSortField.TOTAL -> list.sortedBy { if (sortAscending) it.totalAmount else -it.totalAmount }
                DarkSortField.VOLUME -> list.sortedBy { if (sortAscending) it.volume else -it.volume }
                DarkSortField.CODE -> if (sortAscending) list.sortedBy { it.code } else list.sortedByDescending { it.code }
                DarkSortField.NAME -> if (sortAscending) list.sortedBy { it.name } else list.sortedByDescending { it.name }
            }
        }
}

/** 可排序字段。 */
enum class DarkSortField(val label: String) {
    CODE("代码"),
    NAME("名称"),
    BUY("暗盘买入"),
    SELL("暗盘卖出"),
    NET("净流入"),
    TOTAL("总成交"),
    VOLUME("成交量")
}

/**
 * 全量暗盘列表 ViewModel:
 * - 拉取全市场(不传 code), 支持翻页加载更多;
 * - 点击展开某只股票时, 并行拉取主力 + 两融数据, 组装 4 组详情。
 */
class DarkTradeListViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DarkTradeListState())
    val state: StateFlow<DarkTradeListState> = _state

    private val database get() = AppDatabase.get(getApplication())
    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private var saveJob: Job? = null

    // 展开详情内存缓存(code → StockDetailData), App 存活期间有效
    private val detailCache = HashMap<String, StockDetailData>()

    /** 全量下载当日暗盘并存入 DB。 */
    fun refreshAndSave() {
        if (_state.value.loading) return
        saveJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        saveJob = viewModelScope.launch {
            try {
                val date = _state.value.date
                // 清内存缓存, 强制重新从接口拉最新数据
                DarkTradeDataSource.clearCache()
                val all = withContext(Dispatchers.IO) {
                    DarkTradeDataSource.fetchAllDarkTrade(date)
                }
                // 存入 DB
                val today = LocalDate.now().toString()
                val savedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                withContext(Dispatchers.IO) {
                    val entities = all.map { r ->
                        DarkTradeDataEntity(
                            code = r.code, date = today, name = r.name,
                            industry = r.industry, concept = r.concept,
                            buyAmount = r.buyAmount, sellAmount = r.sellAmount,
                            totalAmount = r.totalAmount, netAmount = r.netAmount,
                            volume = r.volume, ratio = r.ratio, savedAt = savedAt
                        )
                    }
                    database.darkTradeDao().insertAll(entities)
                }
                _state.update {
                    it.copy(records = all, loading = false, totalCount = all.size, hasMore = false)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "暗盘下载失败: ${e.message}") }
            }
        }
    }

    /** 更换日期: 一次性全量拉取当日全部暗盘记录。 */
    fun loadDate(date: String) {
        loadJob?.cancel()
        _state.update { DarkTradeListState(date = date, loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val all = withContext(Dispatchers.IO) {
                    DarkTradeDataSource.fetchAllDarkTrade(date)
                }
                _state.update {
                    it.copy(
                        records = all,
                        loading = false,
                        totalCount = all.size,
                        hasMore = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "加载失败: ${e.message}") }
            }
        }
    }

    /** 加载下一页(已改为全量加载, 此方法保留为 no-op 以防 UI 仍引用)。 */
    fun loadMore() {
        // 全量模式下无需分页
    }

    /**
     * 点击某只股票: 切换展开/收起, 展开时拉取主力 + 两融详情。
     */
    fun toggleExpand(record: DarkTradeDataSource.DarkTradeRecord) {
        val s = _state.value
        // 已展开同一只 → 收起
        if (s.expandedCode == record.code) {
            _state.update { it.copy(expandedCode = null, detail = null) }
            detailJob?.cancel()
            return
        }
        // 先查内存缓存(当日已拉过一次就不再重拉)
        val cached = detailCache[record.code]
        if (cached != null) {
            _state.update { it.copy(expandedCode = record.code, detail = cached.copy(loadingDetail = false)) }
            return
        }
        // 先展示暗盘已有字段, 详情资金流/两融置为加载中
        val stub = StockDetailData(
            code = record.code,
            name = record.name,
            darkBuy = record.buyAmount,
            darkSell = record.sellAmount,
            darkNet = record.netAmount,
            mainNet = 0.0, smallNet = 0.0, midNet = 0.0, bigNet = 0.0, superNet = 0.0,
            mainRatio = 0.0, smallRatio = 0.0,
            marginBuy = 0.0, marginRepay = 0.0, marginNet = 0.0, marginBalance = 0.0,
            loadingDetail = true
        )
        _state.update { it.copy(expandedCode = record.code, detail = stub) }
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            // 并行拉取资金流 + 两融
            val flow = withContext(Dispatchers.IO) { MainFundDataSource.fetchFundFlow(record.code) }
            val margin = withContext(Dispatchers.IO) { MarginDataSource.fetchMargin(record.code) }
            android.util.Log.d("DarkTrade", "展开 ${record.code}: mainNet=${flow.mainNet} smallNet=${flow.smallNet} marginBuy=${margin.buy}")
            _state.update {
                // 用户可能已收起或展开别的股票, 只更新仍展开的那只
                if (it.expandedCode == record.code) {
                    val detail = StockDetailData(
                        code = record.code,
                        name = record.name,
                        darkBuy = record.buyAmount,
                        darkSell = record.sellAmount,
                        darkNet = record.netAmount,
                        mainNet = flow.mainNet,
                        smallNet = flow.smallNet,
                        midNet = flow.midNet,
                        bigNet = flow.bigNet,
                        superNet = flow.superNet,
                        mainRatio = flow.mainRatio,
                        smallRatio = flow.smallRatio,
                        marginBuy = margin.buy,
                        marginRepay = margin.repay,
                        marginNet = margin.netBuy,
                        marginBalance = margin.balance,
                        loadingDetail = false
                    )
                    // 写入内存缓存(当日有效)
                    detailCache[record.code] = detail
                    it.copy(detail = detail)
                } else it
            }
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        detailJob?.cancel()
        saveJob?.cancel()
        super.onCleared()
    }

    /** 点击表头排序: 同字段则切换升降序, 换字段则默认降序。 */
    fun setSort(field: DarkSortField) {
        val s = _state.value
        if (s.sortField == field) {
            _state.update { it.copy(sortAscending = !it.sortAscending) }
        } else {
            _state.update { it.copy(sortField = field, sortAscending = false) }
        }
    }

    /** 更新搜索关键字。 */
    fun setSearch(keyword: String) {
        _state.update { it.copy(searchKeyword = keyword) }
    }
}
