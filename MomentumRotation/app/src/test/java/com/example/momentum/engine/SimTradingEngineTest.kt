package com.example.momentum.engine

import com.example.momentum.data.db.BidDataEntity
import org.junit.Assert.*
import org.junit.Test

class SimTradingEngineTest {

    @Test
    fun testHighWinRateDefaultParams() {
        val config = BidStrategy.BidConfig.HIGH_WIN_RATE
        assertEquals(2.0, config.bidMin, 0.001)
        assertEquals(4.0, config.bidMax, 0.001)
        assertTrue(config.filterStSuspend)
        assertTrue(config.filterLimitUp)
    }

    @Test
    fun testSimTradingWithoutFriction() {
        val candidates = listOf(
            BidDataEntity(
                code = "600519", date = "2026-10-14", name = "贵州茅台",
                latestPrice = 1300.0, realChange = 3.5, openPrice = 1250.0,
                volume = 1000, amount = 1e8, turnover = 1.0, circMv = 1e11,
                bidChange = 2.5, warnFlag = 0, industry = "白酒", conceptQuick = "",
                probability = 80, confidence = 85, score = 40,
                bidTurnover = 0.5, entityChange = 2.0, warnText = "-", reason = "", savedAt = "09:25:00"
            ),
            BidDataEntity(
                code = "002469", date = "2026-10-14", name = "三维化学",
                latestPrice = 7.0, realChange = 5.0, openPrice = 6.5,
                volume = 2000, amount = 2e7, turnover = 2.0, circMv = 5e9,
                bidChange = 3.2, warnFlag = 0, industry = "化工", conceptQuick = "",
                probability = 75, confidence = 80, score = 38,
                bidTurnover = 0.8, entityChange = 2.5, warnText = "-", reason = "", savedAt = "09:25:00"
            )
        )

        val noFriction = SimTradingFeeConfig(enableFee = false, enableSlippage = false)
        val initialCapital = 1_000_000.0
        val dayBuy = SimTradingEngine.executeBuy(initialCapital, "2026-10-14", candidates, noFriction)

        assertEquals("HOLDING", dayBuy.status)
        assertEquals(2, dayBuy.orders.size)

        val order1 = dayBuy.orders[0]
        assertEquals(1250.0, order1.buyPrice, 0.001)
        assertEquals(400, order1.shares)

        val order2 = dayBuy.orders[1]
        assertEquals(6.5, order2.buyPrice, 0.001)
        assertEquals(76900, order2.shares)

        val closeMap = mapOf("600519" to 1300.0, "002469" to 7.0)
        val settled = SimTradingEngine.executeSettle(dayBuy, closeMap, noFriction)

        assertEquals("SETTLED", settled.status)
        val expectedProfit = 400 * (1300.0 - 1250.0) + 76900 * (7.0 - 6.5)
        assertEquals(expectedProfit, settled.profit, 0.01)
    }

    @Test
    fun testSimTradingWithFeeAndSlippage() {
        val candidates = listOf(
            BidDataEntity(
                code = "600519", date = "2026-10-14", name = "贵州茅台",
                latestPrice = 1300.0, realChange = 3.5, openPrice = 1000.0,
                volume = 1000, amount = 1e8, turnover = 1.0, circMv = 1e11,
                bidChange = 2.5, warnFlag = 0, industry = "白酒", conceptQuick = "",
                probability = 80, confidence = 85, score = 40,
                bidTurnover = 0.5, entityChange = 2.0, warnText = "-", reason = "", savedAt = "09:25:00"
            )
        )

        // 开启手续费: 买万2, 卖万7(含印花税); 滑点单边 0.1%
        val feeCfg = SimTradingFeeConfig(
            enableFee = true,
            feeRateBuy = 0.0002,
            feeRateSell = 0.0007,
            enableSlippage = true,
            slippageRate = 0.001
        )
        val initialCapital = 1_000_000.0
        val dayBuy = SimTradingEngine.executeBuy(initialCapital, "2026-10-14", candidates, feeCfg)

        val order = dayBuy.orders[0]
        // 开盘价 1000 元，滑点 0.1% -> 实际买入价 1001.0 元
        assertEquals(1001.0, order.buyPrice, 0.001)
        assertEquals(1000.0, order.baseBuyPrice, 0.001)

        // 1,000,000 / 1001.0 = 999 股 -> 向下取整 900 股
        assertEquals(900, order.shares)
        val actualCost = 900 * 1001.0
        assertEquals(actualCost, order.actualCost, 0.001)

        // 买入佣金: actualCost * 0.0002
        val expectedBuyFee = actualCost * 0.0002
        assertEquals(expectedBuyFee, order.buyFee, 0.01)

        // 尾盘收盘价 1100 元结算
        val closeMap = mapOf("600519" to 1100.0)
        val settled = SimTradingEngine.executeSettle(dayBuy, closeMap, feeCfg)

        val settledOrder = settled.orders[0]
        // 收盘价 1100 元，卖出扣滑点 0.1% -> 实际卖出价 1098.9 元
        assertEquals(1098.9, settledOrder.sellPrice, 0.001)
        val revenue = 900 * 1098.9
        // 卖出佣金+印花税: revenue * 0.0007
        val expectedSellFee = revenue * 0.0007
        assertEquals(expectedSellFee, settledOrder.sellFee, 0.01)

        // 净利润: (收入 - 卖出费) - (成本 + 买入费)
        val expectedNetProfit = (revenue - expectedSellFee) - (actualCost + expectedBuyFee)
        assertEquals(expectedNetProfit, settled.profit, 0.01)
        assertTrue(settled.totalFee > 0)
        assertTrue(settled.totalSlippage > 0)
    }
}
