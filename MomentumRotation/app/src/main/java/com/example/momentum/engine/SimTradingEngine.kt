package com.example.momentum.engine

import com.example.momentum.data.db.BidDataEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模拟买入单只股票的订单明细
 */
data class SimOrder(
    val code: String,
    val name: String,
    val buyPrice: Double,         // 早盘开盘价买入 (9:25竞价撮合价)
    val sellPrice: Double = 0.0,   // 尾盘最新价/收盘价卖出
    val shares: Int,              // 股数 (向下整百手)
    val allocatedCapital: Double, // 分配金额
    val actualCost: Double,       // 实际买入花费 buyPrice * shares
    val profit: Double = 0.0,     // 盈亏金额 (sellPrice - buyPrice) * shares
    val returnPct: Double = 0.0   // 收益率 %
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("code", code)
        o.put("name", name)
        o.put("buyPrice", buyPrice)
        o.put("sellPrice", sellPrice)
        o.put("shares", shares)
        o.put("allocatedCapital", allocatedCapital)
        o.put("actualCost", actualCost)
        o.put("profit", profit)
        o.put("returnPct", returnPct)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SimOrder {
            return SimOrder(
                code = o.optString("code", ""),
                name = o.optString("name", ""),
                buyPrice = o.optDouble("buyPrice", 0.0),
                sellPrice = o.optDouble("sellPrice", 0.0),
                shares = o.optInt("shares", 0),
                allocatedCapital = o.optDouble("allocatedCapital", 0.0),
                actualCost = o.optDouble("actualCost", 0.0),
                profit = o.optDouble("profit", 0.0),
                returnPct = o.optDouble("returnPct", 0.0)
            )
        }
    }
}

/**
 * 单个交易日的模拟交易与结算记录
 */
data class SimDayRecord(
    val date: String,
    val startCapital: Double,
    val endCapital: Double,
    val profit: Double,
    val returnPct: Double,
    val orders: List<SimOrder>,
    val status: String // "HOLDING" (持仓中) 或 "SETTLED" (已尾盘结算)
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("date", date)
        o.put("startCapital", startCapital)
        o.put("endCapital", endCapital)
        o.put("profit", profit)
        o.put("returnPct", returnPct)
        o.put("status", status)
        val arr = JSONArray()
        orders.forEach { arr.put(it.toJson()) }
        o.put("orders", arr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SimDayRecord {
            val arr = o.optJSONArray("orders") ?: JSONArray()
            val orderList = mutableListOf<SimOrder>()
            for (i in 0 until arr.length()) {
                orderList.add(SimOrder.fromJson(arr.getJSONObject(i)))
            }
            return SimDayRecord(
                date = o.optString("date", ""),
                startCapital = o.optDouble("startCapital", 1_000_000.0),
                endCapital = o.optDouble("endCapital", 1_000_000.0),
                profit = o.optDouble("profit", 0.0),
                returnPct = o.optDouble("returnPct", 0.0),
                orders = orderList,
                status = o.optString("status", "SETTLED")
            )
        }
    }
}

/**
 * 模拟账户状态
 */
data class SimAccountState(
    val initialCapital: Double = 1_000_000.0,
    val currentCapital: Double = 1_000_000.0,
    val cash: Double = 1_000_000.0,
    val totalProfit: Double = 0.0,
    val totalReturnPct: Double = 0.0,
    val winDayCount: Int = 0,
    val totalDayCount: Int = 0,
    val todayRecord: SimDayRecord? = null,
    val historyRecords: List<SimDayRecord> = emptyList()
) {
    val winRate: Double
        get() = if (totalDayCount > 0) (winDayCount.toDouble() / totalDayCount) * 100.0 else 0.0
}

/**
 * 模拟交易量化策略计算核心
 */
object SimTradingEngine {

    /**
     * 早盘竞价结束模拟买入: 将当前可用总资金平均分仓买入所有高胜率候选股 (整百手买入)
     */
    fun executeBuy(
        currentCapital: Double,
        date: String,
        candidates: List<BidDataEntity>
    ): SimDayRecord {
        if (candidates.isEmpty()) {
            return SimDayRecord(
                date = date,
                startCapital = currentCapital,
                endCapital = currentCapital,
                profit = 0.0,
                returnPct = 0.0,
                orders = emptyList(),
                status = "SETTLED"
            )
        }

        val n = candidates.size
        val perCapital = currentCapital / n
        val orders = mutableListOf<SimOrder>()
        var totalActualCost = 0.0

        for (item in candidates) {
            val buyPrice = if (item.openPrice > 0) item.openPrice else item.latestPrice
            if (buyPrice <= 0) continue

            // A股整百股向下取整
            val rawShares = (perCapital / buyPrice).toInt()
            val shares = (rawShares / 100) * 100
            val actualCost = shares * buyPrice
            totalActualCost += actualCost

            // 初始当前价格 = buyPrice，收益为 0
            orders.add(
                SimOrder(
                    code = item.code,
                    name = item.name.ifBlank { item.code },
                    buyPrice = buyPrice,
                    sellPrice = item.latestPrice, // 盘中最新价，尾盘结算时为收盘价
                    shares = shares,
                    allocatedCapital = perCapital,
                    actualCost = actualCost,
                    profit = shares * (item.latestPrice - buyPrice),
                    returnPct = if (buyPrice > 0) ((item.latestPrice - buyPrice) / buyPrice) * 100.0 else 0.0
                )
            )
        }

        val totalProfit = orders.sumOf { it.profit }
        val endCapital = currentCapital + totalProfit
        val returnPct = if (currentCapital > 0) (totalProfit / currentCapital) * 100.0 else 0.0

        return SimDayRecord(
            date = date,
            startCapital = currentCapital,
            endCapital = endCapital,
            profit = totalProfit,
            returnPct = returnPct,
            orders = orders,
            status = "HOLDING" // 标记为持仓中
        )
    }

    /**
     * 尾盘结算: 按收盘价/最新价全部平仓结算
     */
    fun executeSettle(
        record: SimDayRecord,
        closePriceMap: Map<String, Double> = emptyMap()
    ): SimDayRecord {
        val updatedOrders = record.orders.map { order ->
            val sellPrice = closePriceMap[order.code] ?: if (order.sellPrice > 0) order.sellPrice else order.buyPrice
            val profit = order.shares * (sellPrice - order.buyPrice)
            val returnPct = if (order.buyPrice > 0) ((sellPrice - order.buyPrice) / order.buyPrice) * 100.0 else 0.0
            order.copy(
                sellPrice = sellPrice,
                profit = profit,
                returnPct = returnPct
            )
        }

        val totalProfit = updatedOrders.sumOf { it.profit }
        val endCapital = record.startCapital + totalProfit
        val returnPct = if (record.startCapital > 0) (totalProfit / record.startCapital) * 100.0 else 0.0

        return record.copy(
            endCapital = endCapital,
            profit = totalProfit,
            returnPct = returnPct,
            orders = updatedOrders,
            status = "SETTLED"
        )
    }

    /**
     * 全历史多交易日自动回测:
     * 从最早日期到最新日期，按日期循环执行: 高胜率竞价分仓买入 -> 尾盘收盘价卖出，滚存计算资金曲线
     */
    fun runBacktest(
        initialCapital: Double,
        dateMap: Map<String, List<BidDataEntity>>, // date -> 该日所有竞价数据
        config: BidStrategy.BidConfig
    ): SimAccountState {
        var capital = initialCapital
        val historyList = mutableListOf<SimDayRecord>()
        var winCount = 0

        val sortedDates = dateMap.keys.sorted() // 按日期升序

        for (d in sortedDates) {
            val allItems = dateMap[d] ?: continue
            // 筛选出该日符合高胜率配置的股票
            val candidates = allItems.filter { e ->
                val p = e.probability
                val c = e.confidence
                val s = e.score
                val chg = e.bidChange
                val mvYi = e.circMv / 100_000_000.0
                val pr = e.latestPrice

                if (config.bidMin > 0 && chg < config.bidMin) return@filter false
                if (config.bidMax > 0 && chg > config.bidMax) return@filter false
                if (config.probMin > 0 && p < config.probMin) return@filter false
                if (config.probMax > 0 && p > config.probMax) return@filter false
                if (config.confMin > 0 && c < config.confMin) return@filter false
                if (config.confMax > 0 && c > config.confMax) return@filter false
                if (config.scoreMin > 0 && s < config.scoreMin) return@filter false
                if (config.scoreMax > 0 && s > config.scoreMax) return@filter false
                if (config.mvMin > 0 && mvYi < config.mvMin) return@filter false
                if (config.mvMax > 0 && mvYi > config.mvMax) return@filter false
                if (config.priceMin > 0 && pr < config.priceMin) return@filter false
                if (config.priceMax > 0 && pr > config.priceMax) return@filter false
                if (config.filterStSuspend && (e.warnFlag and 1 != 0 || e.name.contains("ST"))) return@filter false
                if (config.filterLimitUp && (e.warnFlag and 2 != 0 || chg >= 9.5)) return@filter false

                true
            }

            if (candidates.isEmpty()) {
                // 当天空仓
                val dayRec = SimDayRecord(
                    date = d,
                    startCapital = capital,
                    endCapital = capital,
                    profit = 0.0,
                    returnPct = 0.0,
                    orders = emptyList(),
                    status = "SETTLED"
                )
                historyList.add(dayRec)
                continue
            }

            // 当天有高胜率标的，执行买入与尾盘卖出
            val dayBuy = executeBuy(capital, d, candidates)
            val closeMap = candidates.associate { it.code to it.latestPrice }
            val daySettled = executeSettle(dayBuy, closeMap)

            capital = daySettled.endCapital
            if (daySettled.profit > 0) winCount++
            historyList.add(daySettled)
        }

        val totalProfit = capital - initialCapital
        val totalReturnPct = if (initialCapital > 0) (totalProfit / initialCapital) * 100.0 else 0.0
        val tradeDays = historyList.count { it.orders.isNotEmpty() }

        return SimAccountState(
            initialCapital = initialCapital,
            currentCapital = capital,
            cash = capital,
            totalProfit = totalProfit,
            totalReturnPct = totalReturnPct,
            winDayCount = winCount,
            totalDayCount = tradeDays,
            todayRecord = historyList.lastOrNull(),
            historyRecords = historyList.reversed() // 倒序呈现给界面
        )
    }
}
