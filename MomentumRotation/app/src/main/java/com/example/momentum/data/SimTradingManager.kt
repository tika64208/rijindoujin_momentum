package com.example.momentum.data

import android.content.Context
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.BidDataEntity
import com.example.momentum.engine.*
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
 * 模拟买入与实盘结算全局管理器 (支持 100 万初始本金分仓、精确扣除印花税/佣金手续费与滑点)
 */
class SimTradingManager private constructor(private val context: Context) {

    private val _accountState = MutableStateFlow(SimAccountState())
    val accountState: StateFlow<SimAccountState> = _accountState.asStateFlow()

    init {
        loadPersistedState()
    }

    /**
     * 更新手续费与滑点交易摩擦配置
     */
    fun updateFeeConfig(config: SimTradingFeeConfig) {
        _accountState.update { it.copy(feeConfig = config) }
        persistState()
    }

    /**
     * 今日早盘模拟买入: 高胜率选股结果平均分仓买入 (含买入滑点与买入佣金)
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
            candidates = candidates,
            feeConfig = current.feeConfig
        )

        _accountState.update { state ->
            val cashLeft = state.currentCapital - dayRecord.orders.sumOf { it.actualCost + it.buyFee }
            state.copy(
                todayRecord = dayRecord,
                cash = cashLeft
            )
        }
        persistState()
        return true to "已使用当前资金平均分仓买入 ${dayRecord.orders.size} 只高胜率股票"
    }

    /**
     * 尾盘结算: 全部平仓结算 (精确扣除卖出印花税0.05%、佣金与卖出滑点)
     */
    suspend fun settleToday(closePriceMap: Map<String, Double> = emptyMap()): Pair<Boolean, String> {
        val current = _accountState.value
        val today = current.todayRecord
        if (today == null || today.status != "HOLDING") {
            return false to "当前暂无持仓中记录可结算"
        }

        val settled = SimTradingEngine.executeSettle(
            record = today,
            closePriceMap = closePriceMap,
            feeConfig = current.feeConfig
        )
        val profit = settled.profit
        val newCapital = settled.endCapital
        val totalProfit = current.totalProfit + profit
        val totalReturnPct = if (current.initialCapital > 0) (totalProfit / current.initialCapital) * 100.0 else 0.0

        val newHistory = mutableListOf<SimDayRecord>()
        newHistory.add(settled)
        newHistory.addAll(current.historyRecords.filter { it.date != settled.date })

        val winCount = newHistory.count { it.profit > 0 }
        val totalDays = newHistory.count { it.orders.isNotEmpty() }
        val totalFee = newHistory.sumOf { it.totalFee }
        val totalSlippage = newHistory.sumOf { it.totalSlippage }

        _accountState.update {
            it.copy(
                currentCapital = newCapital,
                cash = newCapital,
                totalProfit = totalProfit,
                totalReturnPct = totalReturnPct,
                totalFeePaid = totalFee,
                totalSlippagePaid = totalSlippage,
                winDayCount = winCount,
                totalDayCount = totalDays,
                todayRecord = settled,
                historyRecords = newHistory
            )
        }
        persistState()
        return true to "已按最新/收盘价全部平仓结算 (扣除印花税/佣金/滑点)，今日净盈亏: ${String.format("%+.2f元 (%+.2f%%)", profit, settled.returnPct)}"
    }

    /**
     * 重新模拟今日: 用今日全部最新高胜率候选股票(如全量 12 只)重新模拟买入并尾盘平仓结算
     */
    suspend fun reSimulateToday(
        date: String,
        candidates: List<BidDataEntity>,
        closePriceMap: Map<String, Double> = emptyMap()
    ): Pair<Boolean, String> {
        if (candidates.isEmpty()) {
            return false to "当前无符合条件的股票可用于重算"
        }
        val current = _accountState.value
        val todayProfit = current.todayRecord?.takeIf { it.date == date && it.status == "SETTLED" }?.profit ?: 0.0
        val baseCapital = current.currentCapital - todayProfit

        // 用今日真实全量标的买入
        val dayBuy = SimTradingEngine.executeBuy(
            currentCapital = baseCapital,
            date = date,
            candidates = candidates,
            feeConfig = current.feeConfig
        )

        // 尾盘平仓结算
        val settled = SimTradingEngine.executeSettle(
            record = dayBuy,
            closePriceMap = closePriceMap,
            feeConfig = current.feeConfig
        )

        val newCapital = settled.endCapital
        val totalProfit = (baseCapital - current.initialCapital) + settled.profit
        val totalReturnPct = if (current.initialCapital > 0) (totalProfit / current.initialCapital) * 100.0 else 0.0

        val newHistory = mutableListOf<SimDayRecord>()
        newHistory.add(settled)
        newHistory.addAll(current.historyRecords.filter { it.date != date })

        val winCount = newHistory.count { it.profit > 0 }
        val totalDays = newHistory.count { it.orders.isNotEmpty() }
        val totalFee = newHistory.sumOf { it.totalFee }
        val totalSlippage = newHistory.sumOf { it.totalSlippage }

        _accountState.update {
            it.copy(
                currentCapital = newCapital,
                cash = newCapital,
                totalProfit = totalProfit,
                totalReturnPct = totalReturnPct,
                totalFeePaid = totalFee,
                totalSlippagePaid = totalSlippage,
                winDayCount = winCount,
                totalDayCount = totalDays,
                todayRecord = settled,
                historyRecords = newHistory
            )
        }
        persistState()
        return true to "已使用今日全量 ${candidates.size} 只高胜率股票重新完成分仓模拟与结算！"
    }

    /**
     * 历史全量数据一键回测 (考虑手续费与滑点摩擦，并进行实体竞价纯净性校准)
     */
    suspend fun runBacktest(db: AppDatabase, config: BidStrategy.BidConfig): SimAccountState =
        withContext(Dispatchers.IO) {
            val dates = db.bidDao().getDates(60)
            if (dates.isEmpty()) return@withContext _accountState.value

            val repository = BidRepository.create(context)
            val dateMap = mutableMapOf<String, List<BidDataEntity>>()
            for (d in dates) {
                val list = db.bidDao().getByDate(d)
                if (list.isNotEmpty()) {
                    // 校准实体确保历史和今日都使用纯净竞价评分
                    val sanitized = list.map { e ->
                        val basePrice = if (e.openPrice > 0) e.openPrice else e.latestPrice
                        val rawItem = BidStrategy.BidItem(
                            code = e.code,
                            name = e.name,
                            latestPrice = basePrice,
                            realChange = e.bidChange,
                            openPrice = e.openPrice,
                            volume = e.volume,
                            amount = e.amount,
                            turnover = e.turnover,
                            circMv = e.circMv,
                            bidChange = e.bidChange,
                            warnFlag = e.warnFlag,
                            industry = e.industry,
                            conceptQuick = e.conceptQuick
                        )
                        val res = BidStrategy.compute(rawItem, config)
                        e.copy(
                            probability = res.probability,
                            confidence = res.confidence,
                            score = res.score
                        )
                    }
                    dateMap[d] = sanitized
                }
            }

            val backtestResult = SimTradingEngine.runBacktest(
                initialCapital = _accountState.value.initialCapital,
                dateMap = dateMap,
                config = config,
                feeConfig = _accountState.value.feeConfig
            )

            _accountState.value = backtestResult
            persistState()
            backtestResult
        }

    /**
     * 重置账户资金回 100 万
     */
    fun resetAccount(initialCapital: Double = 1_000_000.0) {
        val currentCfg = _accountState.value.feeConfig
        _accountState.value = SimAccountState(
            initialCapital = initialCapital,
            currentCapital = initialCapital,
            cash = initialCapital,
            totalProfit = 0.0,
            totalReturnPct = 0.0,
            totalFeePaid = 0.0,
            totalSlippagePaid = 0.0,
            winDayCount = 0,
            totalDayCount = 0,
            feeConfig = currentCfg,
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
            root.put("totalFeePaid", state.totalFeePaid)
            root.put("totalSlippagePaid", state.totalSlippagePaid)
            root.put("winDayCount", state.winDayCount)
            root.put("totalDayCount", state.totalDayCount)
            root.put("feeConfig", state.feeConfig.toJson())

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

            val feeObj = root.optJSONObject("feeConfig")
            val feeConfig = if (feeObj != null) SimTradingFeeConfig.fromJson(feeObj) else SimTradingFeeConfig()

            _accountState.value = SimAccountState(
                initialCapital = root.optDouble("initialCapital", 1_000_000.0),
                currentCapital = root.optDouble("currentCapital", 1_000_000.0),
                cash = root.optDouble("cash", 1_000_000.0),
                totalProfit = root.optDouble("totalProfit", 0.0),
                totalReturnPct = root.optDouble("totalReturnPct", 0.0),
                totalFeePaid = root.optDouble("totalFeePaid", 0.0),
                totalSlippagePaid = root.optDouble("totalSlippagePaid", 0.0),
                winDayCount = root.optInt("winDayCount", 0),
                totalDayCount = root.optInt("totalDayCount", 0),
                feeConfig = feeConfig,
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
