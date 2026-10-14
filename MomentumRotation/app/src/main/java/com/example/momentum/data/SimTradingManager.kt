package com.example.momentum.data

import android.content.Context
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.BidDataEntity
import com.example.momentum.engine.BidStrategy
import com.example.momentum.engine.SimAccountState
import com.example.momentum.engine.SimDayRecord
import com.example.momentum.engine.SimTradingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 模拟买入与实盘结算全局管理器 (支持 100 万初始本金分仓与尾盘平仓结算)
 */
class SimTradingManager private constructor(private val context: Context) {

    private val _accountState = MutableStateFlow(SimAccountState())
    val accountState: StateFlow<SimAccountState> = _accountState.asStateFlow()

    init {
        loadPersistedState()
    }

    /**
     * 今日早盘模拟买入: 高胜率选股结果平均分仓买入
     */
    suspend fun buyToday(date: String, candidates: List<BidDataEntity>): Pair<Boolean, String> {
        val current = _accountState.value
        if (current.todayRecord != null && current.todayRecord.status == "HOLDING" && current.todayRecord.date == date) {
            return false to "今日已模拟买入持仓中，请勿重复买入"
        }
        if (candidates.isEmpty()) {
            return false to "当前高胜率选股无符合条件的股票"
        }

        val dayRecord = SimTradingEngine.executeBuy(
            currentCapital = current.currentCapital,
            date = date,
            candidates = candidates
        )

        _accountState.update { state ->
            val cashLeft = state.currentCapital - dayRecord.orders.sumOf { it.actualCost }
            state.copy(
                todayRecord = dayRecord,
                cash = cashLeft
            )
        }
        persistState()
        return true to "已使用当前资金平均分仓买入 ${dayRecord.orders.size} 只高胜率股票"
    }

    /**
     * 尾盘结算: 全部平仓结算
     */
    suspend fun settleToday(closePriceMap: Map<String, Double> = emptyMap()): Pair<Boolean, String> {
        val current = _accountState.value
        val today = current.todayRecord
        if (today == null || today.status != "HOLDING") {
            return false to "当前暂无持仓中记录可结算"
        }

        val settled = SimTradingEngine.executeSettle(today, closePriceMap)
        val profit = settled.profit
        val newCapital = settled.endCapital
        val totalProfit = current.totalProfit + profit
        val totalReturnPct = if (current.initialCapital > 0) (totalProfit / current.initialCapital) * 100.0 else 0.0

        val newHistory = mutableListOf<SimDayRecord>()
        newHistory.add(settled)
        newHistory.addAll(current.historyRecords.filter { it.date != settled.date })

        val winCount = newHistory.count { it.profit > 0 }
        val totalDays = newHistory.count { it.orders.isNotEmpty() }

        _accountState.update {
            it.copy(
                currentCapital = newCapital,
                cash = newCapital,
                totalProfit = totalProfit,
                totalReturnPct = totalReturnPct,
                winDayCount = winCount,
                totalDayCount = totalDays,
                todayRecord = settled,
                historyRecords = newHistory
            )
        }
        persistState()
        return true to "已按最新/收盘价全部平仓结算，今日盈亏: ${String.format("%+.2f元 (%+.2f%%)", profit, settled.returnPct)}"
    }

    /**
     * 历史全量数据一键回测:
     * 提取本地 bid_data 中所有日期数据，按高胜率策略进行日内回测模拟
     */
    suspend fun runBacktest(db: AppDatabase, config: BidStrategy.BidConfig): SimAccountState =
        withContext(Dispatchers.IO) {
            val dates = db.bidDao().getDates(60)
            if (dates.isEmpty()) return@withContext _accountState.value

            val dateMap = mutableMapOf<String, List<BidDataEntity>>()
            for (d in dates) {
                val list = db.bidDao().getByDate(d)
                if (list.isNotEmpty()) {
                    dateMap[d] = list
                }
            }

            val backtestResult = SimTradingEngine.runBacktest(
                initialCapital = _accountState.value.initialCapital,
                dateMap = dateMap,
                config = config
            )

            _accountState.value = backtestResult
            persistState()
            backtestResult
        }

    /**
     * 重置账户资金回 100 万
     */
    fun resetAccount(initialCapital: Double = 1_000_000.0) {
        _accountState.value = SimAccountState(
            initialCapital = initialCapital,
            currentCapital = initialCapital,
            cash = initialCapital,
            totalProfit = 0.0,
            totalReturnPct = 0.0,
            winDayCount = 0,
            totalDayCount = 0,
            todayRecord = null,
            historyRecords = emptyList()
        )
        persistState()
    }

    private fun getStorageFile(): File {
        return File(context.filesDir, "sim_trading_account.json")
    }

    private fun persistState() {
        try {
            val state = _accountState.value
            val root = JSONObject()
            root.put("initialCapital", state.initialCapital)
            root.put("currentCapital", state.currentCapital)
            root.put("cash", state.cash)
            root.put("totalProfit", state.totalProfit)
            root.put("totalReturnPct", state.totalReturnPct)
            root.put("winDayCount", state.winDayCount)
            root.put("totalDayCount", state.totalDayCount)

            state.todayRecord?.let {
                root.put("todayRecord", it.toJson())
            }

            val histArr = JSONArray()
            state.historyRecords.forEach { histArr.put(it.toJson()) }
            root.put("historyRecords", histArr)

            getStorageFile().writeText(root.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadPersistedState() {
        val file = getStorageFile()
        if (!file.exists()) {
            _accountState.value = SimAccountState(1_000_000.0, 1_000_000.0, 1_000_000.0)
            return
        }
        try {
            val text = file.readText()
            if (text.isBlank()) return
            val root = JSONObject(text)
            val todayObj = root.optJSONObject("todayRecord")
            val todayRecord = if (todayObj != null) SimDayRecord.fromJson(todayObj) else null

            val histArr = root.optJSONArray("historyRecords") ?: JSONArray()
            val histList = mutableListOf<SimDayRecord>()
            for (i in 0 until histArr.length()) {
                histList.add(SimDayRecord.fromJson(histArr.getJSONObject(i)))
            }

            _accountState.value = SimAccountState(
                initialCapital = root.optDouble("initialCapital", 1_000_000.0),
                currentCapital = root.optDouble("currentCapital", 1_000_000.0),
                cash = root.optDouble("cash", 1_000_000.0),
                totalProfit = root.optDouble("totalProfit", 0.0),
                totalReturnPct = root.optDouble("totalReturnPct", 0.0),
                winDayCount = root.optInt("winDayCount", 0),
                totalDayCount = root.optInt("totalDayCount", 0),
                todayRecord = todayRecord,
                historyRecords = histList
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SimTradingManager? = null

        fun get(context: Context): SimTradingManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SimTradingManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
