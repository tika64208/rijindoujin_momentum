package com.example.momentum.data

import android.content.Context
import android.util.AtomicFile
import com.example.momentum.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalTime

/** 模拟账户与回测报告各自持久化；回测不写入账户或历史账本。 */
class SimTradingManager private constructor(private val context: Context) {
    private val mutex = Mutex()
    private val data = SimTradingDataRepository(context)
    private val _accountState = MutableStateFlow(SimAccountState())
    val accountState: StateFlow<SimAccountState> = _accountState.asStateFlow()
    private val _backtestReport = MutableStateFlow<SimBacktestReport?>(null)
    val backtestReport: StateFlow<SimBacktestReport?> = _backtestReport.asStateFlow()

    init {
        readJson("sim_trading_account.json")?.let { _accountState.value = SimAccountCodec.decode(it) }
        readJson("sim_backtest_report.json")?.let { _backtestReport.value = SimBacktestReport.fromJson(it) }
    }

    fun updateFeeConfig(config: SimTradingFeeConfig): Boolean {
        if (!mutex.tryLock()) return false
        try {
            val next = _accountState.value.copy(feeConfig = config)
            persistAccount(next)
            _accountState.value = next
            return true
        } finally { mutex.unlock() }
    }

    suspend fun buyToday(date: String): Pair<Boolean, String> = mutate {
        checkTradingDate(date)
        val current = _accountState.value
        require(current.todayRecord?.status != "HOLDING") { "已有持仓，请先完成现有持仓的模拟结算" }
        require(current.historyRecords.none { it.date == date }) { "今日已有账本，请使用今日重跑" }
        val day = data.loadDay(date)
        require(day.rows.isNotEmpty()) { "当前保存的早盘评分没有符合条件的股票" }
        val bought = day.metadata(SimTradingEngine.executeBuy(current.currentCapital, date, day.rows, current.feeConfig))
        val next = current.copy(todayRecord = bought,
            cash = current.currentCapital - bought.orders.sumOf { it.actualCost + it.buyFee })
        persistAccount(next); _accountState.value = next
        "已按保存的早盘评分分仓买入 ${bought.orders.size} 只；${bought.priceQualityLabel()}"
    }

    suspend fun settleToday(): Pair<Boolean, String> = mutate {
        val current = _accountState.value
        val holding = requireNotNull(current.todayRecord?.takeIf { it.status == "HOLDING" }) { "当前没有持仓可结算" }
        val prices = data.pricesFor(holding.date, holding.orders.map { it.code })
        val quality = when {
            prices.all { it.quality == "CLOSE_CONFIRMED" } -> "CLOSE_CONFIRMED"
            prices.any { it.quality == "LEGACY_PRICE_UNVERIFIED" } -> "LEGACY_PRICE_UNVERIFIED"
            else -> "INTRADAY_ESTIMATE"
        }
        val asOf = prices.filter { it.quoteAt > 0 }.maxOfOrNull { it.quoteAt }?.let {
            Instant.ofEpochMilli(it).atZone(OpeningBidTime.zone).toString() } ?: "旧记录未保存行情时间"
        val settled = SimTradingEngine.executeSettle(holding, prices.associate { it.code to it.price }, current.feeConfig)
            .copy(priceQuality = quality, priceAsOf = asOf)
        val next = SimTradingJournal.post(current, settled)
        persistAccount(next); _accountState.value = next
        "模拟结算完成：今日 ${String.format("%+.2f%%", settled.returnPct)} · ${settled.priceQualityLabel()}"
    }

    suspend fun reSimulateToday(date: String): Pair<Boolean, String> = mutate {
        checkTradingDate(date)
        val current = _accountState.value
        require(current.todayRecord?.let { it.status != "HOLDING" || it.date == date } != false) { "存在其他日期未结算持仓" }
        val day = data.loadDay(date)
        require(day.rows.isNotEmpty()) { "当前保存的早盘评分没有符合条件的股票" }
        val original = current.todayRecord?.takeIf { it.date == date } ?: current.historyRecords.firstOrNull { it.date == date }
        val base = original?.startCapital ?: current.currentCapital
        val settled = day.metadata(SimTradingEngine.simulateDay(base, date, day.rows, BidStrategy.BidConfig.HIGH_WIN_RATE, current.feeConfig))
        val next = SimTradingJournal.post(current, settled)
        persistAccount(next); _accountState.value = next
        "已按保存的早盘评分重跑 ${settled.orders.size} 只：今日 ${String.format("%+.2f%%", settled.returnPct)} · ${settled.priceQualityLabel()}"
    }

    suspend fun runBacktest(config: BidStrategy.BidConfig,
                            includeUnverified: Boolean = true): SimBacktestReport = withContext(Dispatchers.IO) {
        val account = _accountState.value
        val days = linkedMapOf<String, SimTradingDayData>()
        val skipped = linkedMapOf<String, String>()
        val notes = linkedMapOf<String, String>()
        // 全部已存盘日期，不再只读取最近 60 个日期。
        for (date in data.dates()) {
            try {
                val day = data.loadDay(date, config)
                if (!includeUnverified && day.issues.isNotEmpty()) {
                    skipped[date] = day.issues.joinToString("；")
                    continue
                }
                days[date] = day
                if (day.issues.isNotEmpty()) notes[date] = day.issues.joinToString("；")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { skipped[date] = e.message ?: "数据不可用" }
        }
        val result = SimTradingEngine.runBacktest(account.initialCapital, days.mapValues { it.value.rows }, config, account.feeConfig)
        val history = result.historyRecords.map { record -> days.getValue(record.date).metadata(record) }
        val report = SimBacktestReport(System.currentTimeMillis(), result.copy(historyRecords = history,
            todayRecord = history.firstOrNull()), notes, skipped, includeUnverified)
        // 只写独立报告，绝不把 result 赋给 _accountState。
        writeJson("sim_backtest_report.json", report.toJson())
        _backtestReport.value = report
        report
    }

    fun resetAccount(initialCapital: Double = 1_000_000.0): Boolean {
        if (!mutex.tryLock()) return false
        try {
            val next = SimAccountState(initialCapital = initialCapital, currentCapital = initialCapital, cash = initialCapital,
                feeConfig = _accountState.value.feeConfig)
            persistAccount(next); _accountState.value = next
            return true
        } finally { mutex.unlock() }
    }

    private suspend fun mutate(action: suspend () -> String): Pair<Boolean, String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try { true to action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { false to (e.message ?: "模拟操作失败，账本未变更") }
        }
    }

    private fun checkTradingDate(date: String) {
        val now = Instant.now().atZone(OpeningBidTime.zone)
        require(date == now.toLocalDate().toString() && now.dayOfWeek.value <= 5) { "只允许操作今日工作日模拟账户" }
        require(!now.toLocalTime().isBefore(LocalTime.of(9, 30))) { "请在 09:30 后使用保存的早盘评分模拟" }
    }

    private fun persistAccount(state: SimAccountState) = writeJson("sim_trading_account.json", SimAccountCodec.encode(state))
    private fun writeJson(name: String, json: JSONObject) {
        val atomic = AtomicFile(File(context.filesDir, name))
        val stream = atomic.startWrite()
        try { stream.write(json.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun readJson(name: String): JSONObject? {
        val file = File(context.filesDir, name)
        if (!file.exists()) return null
        return JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    }

    companion object {
        @Volatile private var INSTANCE: SimTradingManager? = null
        fun get(context: Context): SimTradingManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: SimTradingManager(context.applicationContext).also { INSTANCE = it }
        }
    }
}
