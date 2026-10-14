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
    fun testSimTradingBuyAndSettle() {
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

        val initialCapital = 1_000_000.0
        val dayBuy = SimTradingEngine.executeBuy(initialCapital, "2026-10-14", candidates)

        assertEquals("HOLDING", dayBuy.status)
        assertEquals(2, dayBuy.orders.size)

        // 每只分配 50 万
        val order1 = dayBuy.orders[0]
        assertEquals("600519", order1.code)
        assertEquals(1250.0, order1.buyPrice, 0.001)
        // 500,000 / 1250 = 400 股
        assertEquals(400, order1.shares)

        val order2 = dayBuy.orders[1]
        assertEquals("002469", order2.code)
        assertEquals(6.5, order2.buyPrice, 0.001)
        // 500,000 / 6.5 = 76923 -> 76900 股
        assertEquals(76900, order2.shares)

        // 尾盘全部卖出结算
        val closeMap = mapOf("600519" to 1300.0, "002469" to 7.0)
        val settled = SimTradingEngine.executeSettle(dayBuy, closeMap)

        assertEquals("SETTLED", settled.status)
        // 茅台: 400 * (1300 - 1250) = 20000
        // 三维化学: 76900 * (7.0 - 6.5) = 38450
        val expectedProfit = 20000.0 + 38450.0
        assertEquals(expectedProfit, settled.profit, 0.01)
        assertTrue(settled.endCapital > initialCapital)
    }
}
