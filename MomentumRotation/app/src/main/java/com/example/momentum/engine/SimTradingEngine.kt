package com.example.momentum.engine

import com.example.momentum.data.db.BidDataEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手续费与滑点交易摩擦配置
 */
data class SimTradingFeeConfig(
    val enableFee: Boolean = true,          // 是否扣除佣金/印花税
    val feeRateBuy: Double = 0.0002,        // 买入佣金/过户费: 万分之二 (0.02%)
    val feeRateSell: Double = 0.0007,       // 卖出印花税(0.05%) + 佣金(0.02%): 万分之七 (0.07%)
    val enableSlippage: Boolean = true,     // 是否扣除成交滑点
    val slippageRate: Double = 0.001        // 单边滑点: 0.1% (千分之一)
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("enableFee", enableFee)
        o.put("feeRateBuy", feeRateBuy)
        o.put("feeRateSell", feeRateSell)
        o.put("enableSlippage", enableSlippage)
        o.put("slippageRate", slippageRate)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SimTradingFeeConfig {
            return SimTradingFeeConfig(
                enableFee = o.optBoolean("enableFee", true),
                feeRateBuy = o.optDouble("feeRateBuy", 0.0002),
                feeRateSell = o.optDouble("feeRateSell", 0.0007),
                enableSlippage = o.optBoolean("enableSlippage", true),
                slippageRate = o.optDouble("slippageRate", 0.001)
            )
        }
    }
}

/**
 * 模拟买入单只股票的订单明细 (含手续费与滑点)
 */
data class SimOrder(
    val code: String,
    val name: String,
    val buyPrice: Double,         // 早盘实际买入价 (含滑点)
    val baseBuyPrice: Double = buyPrice, // 早盘基准开盘价 (无滑点)
    val sellPrice: Double = 0.0,   // 尾盘实际卖出价 (扣滑点)
    val baseSellPrice: Double = sellPrice, // 尾盘基准收盘价 (无滑点)
    val shares: Int,              // 股数 (向下整百手)
    val allocatedCapital: Double, // 分配金额
    val actualCost: Double,       // 实际买入花费 buyPrice * shares
    val buyFee: Double = 0.0,     // 买入佣金
    val sellFee: Double = 0.0,    // 卖出印花税+佣金
    val slippageCost: Double = 0.0, // 滑点摩擦金额
    val profit: Double = 0.0,     // 净盈亏金额 (已扣除手续费和滑点)
    val returnPct: Double = 0.0   // 净收益率 %
) {
    val totalFee: Double get() = buyFee + sellFee

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("code", code)
        o.put("name", name)
        o.put("buyPrice", buyPrice)
        o.put("baseBuyPrice", baseBuyPrice)
        o.put("sellPrice", sellPrice)
        o.put("baseSellPrice", baseSellPrice)
        o.put("shares", shares)
        o.put("allocatedCapital", allocatedCapital)
        o.put("actualCost", actualCost)
        o.put("buyFee", buyFee)
        o.put("sellFee", sellFee)
        o.put("slippageCost", slippageCost)
        o.put("profit", profit)
        o.put("returnPct", returnPct)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SimOrder {
            val bp = o.optDouble("buyPrice", 0.0)
            val sp = o.optDouble("sellPrice", 0.0)
            return SimOrder(
                code = o.optString("code", ""),
                name = o.optString("name", ""),
                buyPrice = bp,
                baseBuyPrice = o.optDouble("baseBuyPrice", bp),
                sellPrice = sp,
                baseSellPrice = o.optDouble("baseSellPrice", sp),
                shares = o.optInt("shares", 0),
                allocatedCapital = o.optDouble("allocatedCapital", 0.0),
                actualCost = o.optDouble("actualCost", 0.0),
                buyFee = o.optDouble("buyFee", 0.0),
                sellFee = o.optDouble("sellFee", 0.0),
                slippageCost = o.optDouble("slippageCost", 0.0),
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
    val totalFee: Double = 0.0,
    val totalSlippage: Double = 0.0,
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
        o.put("totalFee", totalFee)
        o.put("totalSlippage", totalSlippage)
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
                totalFee = o.optDouble("totalFee", 0.0),
                totalSlippage = o.optDouble("totalSlippage", 0.0),
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
    val totalFeePaid: Double = 0.0,
    val totalSlippagePaid: Double = 0.0,
    val winDayCount: Int = 0,
    val totalDayCount: Int = 0,
    val feeConfig: SimTradingFeeConfig = SimTradingFeeConfig(),
    val todayRecord: SimDayRecord? = null,
    val historyRecords: List<SimDayRecord> = emptyList()
) {
    val winRate: Double
        get() = if (totalDayCount > 0) (winDayCount.toDouble() / totalDayCount) * 100.0 else 0.0
}

/**
 * 模拟交易量化策略计算核心 (完整计入手续费与滑点)
 */
object SimTradingEngine {

    /**
     * 早盘竞价结束模拟买入: 将当前可用总资金平均分仓买入所有高胜率候选股
     */
    fun executeBuy(
        currentCapital: Double,
        date: String,
        candidates: List<BidDataEntity>,
        feeConfig: SimTradingFeeConfig = SimTradingFeeConfig()
    ): SimDayRecord {
        if (candidates.isEmpty()) {
            return SimDayRecord(
                date = date,
                startCapital = currentCapital,
                endCapital = currentCapital,
                profit = 0.0,
                returnPct = 0.0,
                totalFee = 0.0,
                totalSlippage = 0.0,
                orders = emptyList(),
                status = "SETTLED"
            )
        }

        val n = candidates.size
        val perCapital = currentCapital / n
        val orders = mutableListOf<SimOrder>()

        for (item in candidates) {
            val baseBuy = if (item.openPrice > 0) item.openPrice else item.latestPrice
            if (baseBuy <= 0) continue

            // 计入滑点后的买入成交价 (抢筹高开)
            val actualBuy = if (feeConfig.enableSlippage) baseBuy * (1.0 + feeConfig.slippageRate) else baseBuy

            // 整百股向下取整
            val rawShares = (perCapital / actualBuy).toInt()
            val shares = (rawShares / 100) * 100
            val actualCost = shares * actualBuy

            // 买入佣金
            val buyFee = if (feeConfig.enableFee) actualCost * feeConfig.feeRateBuy else 0.0
            val buySlippage = if (feeConfig.enableSlippage) shares * (actualBuy - baseBuy) else 0.0

            // 盘中预估卖出价 (未开盘结算前以最新价计算)
            val baseSell = item.latestPrice
            val actualSell = if (feeConfig.enableSlippage) baseSell * (1.0 - feeConfig.slippageRate) else baseSell
            val revenue = shares * actualSell
            val sellFee = if (feeConfig.enableFee) revenue * feeConfig.feeRateSell else 0.0
            val sellSlippage = if (feeConfig.enableSlippage) shares * (baseSell - actualSell) else 0.0

            val totalOrderFee = buyFee + sellFee
            val totalOrderSlippage = buySlippage + sellSlippage
            val netProfit = (revenue - sellFee) - (actualCost + buyFee)
            val returnPct = if ((actualCost + buyFee) > 0) (netProfit / (actualCost + buyFee)) * 100.0 else 0.0

            orders.add(
                SimOrder(
                    code = item.code,
                    name = item.name.ifBlank { item.code },
                    buyPrice = actualBuy,
                    baseBuyPrice = baseBuy,
                    sellPrice = actualSell,
                    baseSellPrice = baseSell,
                    shares = shares,
                    allocatedCapital = perCapital,
                    actualCost = actualCost,
                    buyFee = buyFee,
                    sellFee = sellFee,
                    slippageCost = totalOrderSlippage,
                    profit = netProfit,
                    returnPct = returnPct
                )
            )
        }

        val totalProfit = orders.sumOf { it.profit }
        val totalFee = orders.sumOf { it.totalFee }
        val totalSlippage = orders.sumOf { it.slippageCost }
        val endCapital = currentCapital + totalProfit
        val returnPct = if (currentCapital > 0) (totalProfit / currentCapital) * 100.0 else 0.0

        return SimDayRecord(
            date = date,
            startCapital = currentCapital,
            endCapital = endCapital,
            profit = totalProfit,
            returnPct = returnPct,
            totalFee = totalFee,
            totalSlippage = totalSlippage,
            orders = orders,
            status = "HOLDING"
        )
    }

    /**
     * 尾盘结算: 按收盘价全部平仓结算 (精确扣减印花税、佣金与卖出滑点)
     */
    fun executeSettle(
        record: SimDayRecord,
        closePriceMap: Map<String, Double> = emptyMap(),
        feeConfig: SimTradingFeeConfig = SimTradingFeeConfig()
    ): SimDayRecord {
        val updatedOrders = record.orders.map { order ->
            val baseSell = closePriceMap[order.code] ?: if (order.baseSellPrice > 0) order.baseSellPrice else order.buyPrice
            val actualSell = if (feeConfig.enableSlippage) baseSell * (1.0 - feeConfig.slippageRate) else baseSell
            val revenue = order.shares * actualSell

            val sellFee = if (feeConfig.enableFee) revenue * feeConfig.feeRateSell else 0.0
            val sellSlippage = if (feeConfig.enableSlippage) order.shares * (baseSell - actualSell) else 0.0

            val totalOrderFee = order.buyFee + sellFee
            val totalOrderSlippage = (order.slippageCost / 2.0) + sellSlippage // 买入滑点 + 卖出滑点
            val netProfit = (revenue - sellFee) - (order.actualCost + order.buyFee)
            val returnPct = if ((order.actualCost + order.buyFee) > 0) (netProfit / (order.actualCost + order.buyFee)) * 100.0 else 0.0

            order.copy(
                sellPrice = actualSell,
                baseSellPrice = baseSell,
                sellFee = sellFee,
                slippageCost = totalOrderSlippage,
                profit = netProfit,
                returnPct = returnPct
            )
        }

        val totalProfit = updatedOrders.sumOf { it.profit }
        val totalFee = updatedOrders.sumOf { it.totalFee }
        val totalSlippage = updatedOrders.sumOf { it.slippageCost }
        val endCapital = record.startCapital + totalProfit
        val returnPct = if (record.startCapital > 0) (totalProfit / record.startCapital) * 100.0 else 0.0

        return record.copy(
            endCapital = endCapital,
            profit = totalProfit,
            returnPct = returnPct,
            totalFee = totalFee,
            totalSlippage = totalSlippage,
            orders = updatedOrders,
            status = "SETTLED"
        )
    }

    /**
     * 全历史多交易日自动回测 (考虑手续费与滑点)
     */
    fun runBacktest(
        initialCapital: Double,
        dateMap: Map<String, List<BidDataEntity>>,
        config: BidStrategy.BidConfig,
        feeConfig: SimTradingFeeConfig = SimTradingFeeConfig()
    ): SimAccountState {
        var capital = initialCapital
        val historyList = mutableListOf<SimDayRecord>()
        var winCount = 0
        var totalFeePaid = 0.0
        var totalSlippagePaid = 0.0

        val sortedDates = dateMap.keys.sorted()

        for (d in sortedDates) {
            val allItems = dateMap[d] ?: continue
            val candidates = allItems.filter { e ->
                val p = e.probability
                val c = e.confidence
                val s = e.score
                val chg = e.bidChange
                val mvYi = e.circMv / 100_000_000.0
                val pr = if (e.openPrice > 0) e.openPrice else e.latestPrice

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
                val dayRec = SimDayRecord(
                    date = d,
                    startCapital = capital,
                    endCapital = capital,
                    profit = 0.0,
                    returnPct = 0.0,
                    totalFee = 0.0,
                    totalSlippage = 0.0,
                    orders = emptyList(),
                    status = "SETTLED"
                )
                historyList.add(dayRec)
                continue
            }

            val dayBuy = executeBuy(capital, d, candidates, feeConfig)
            val closeMap = candidates.associate { it.code to it.latestPrice }
            val daySettled = executeSettle(dayBuy, closeMap, feeConfig)

            capital = daySettled.endCapital
            totalFeePaid += daySettled.totalFee
            totalSlippagePaid += daySettled.totalSlippage
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
            totalFeePaid = totalFeePaid,
            totalSlippagePaid = totalSlippagePaid,
            winDayCount = winCount,
            totalDayCount = tradeDays,
            feeConfig = feeConfig,
            todayRecord = historyList.lastOrNull(),
            historyRecords = historyList.reversed()
        )
    }
}
