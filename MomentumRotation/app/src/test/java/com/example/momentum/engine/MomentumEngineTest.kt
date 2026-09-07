package com.example.momentum.engine

import com.example.momentum.model.DailyBar
import com.example.momentum.model.StockMomentum
import com.example.momentum.model.Tier
import org.junit.Assert.*
import org.junit.Test

/**
 * 动量引擎单元测试。
 */
class MomentumEngineTest {

    private fun makeBars(closes: List<Double>): List<DailyBar> =
        closes.mapIndexed { i, c ->
            DailyBar(
                date = "2024-01-%02d".format(i + 1),
                open = c, high = c, low = c, close = c, volume = 1000.0
            )
        }

    @Test
    fun `上涨股票动量为正`() {
        val cfg = StrategyConfig()
        // 持续上涨: 每天涨 1%
        val closes = (0..40).map { 100.0 * Math.pow(1.01, it.toDouble()) }
        val m = MomentumEngine.computeStockMomentum("000001", "测试", makeBars(closes), cfg)
        assertFalse(m.insufficient)
        assertTrue("上涨股票动量分应 > 0, 实际=${m.score}", m.score > 0)
    }

    @Test
    fun `下跌股票动量为负`() {
        val cfg = StrategyConfig()
        // 持续下跌: 每天跌 1%
        val closes = (0..40).map { 100.0 * Math.pow(0.99, it.toDouble()) }
        val m = MomentumEngine.computeStockMomentum("000001", "测试", makeBars(closes), cfg)
        assertFalse(m.insufficient)
        assertTrue("下跌股票动量分应 < 0, 实际=${m.score}", m.score < 0)
    }

    @Test
    fun `数据不足标记 insufficient`() {
        val cfg = StrategyConfig()
        // 只有 3 天数据(小于 minHistoryDays=10)
        val m = MomentumEngine.computeStockMomentum("000001", "测试", makeBars(listOf(100.0, 101.0, 102.0)), cfg)
        assertTrue(m.insufficient)
    }

    @Test
    fun `横截面排名正确选出前3买入`() {
        val cfg = StrategyConfig(topCount = 3)
        val stocks = listOf(
            StockMomentum("1", "A", score = 5.0),
            StockMomentum("2", "B", score = 1.0),
            StockMomentum("3", "C", score = 3.0),
            StockMomentum("4", "D", score = 0.0),
            StockMomentum("5", "E", score = 2.0)
        )
        val (recs, meta) = MomentumEngine.rankAndRecommend(stocks, cfg)

        assertEquals(5, meta.total)
        assertEquals(3, meta.topN)

        // 买入 = 排名前3(5.0, 3.0, 2.0)
        val buys = recs.filter { it.tier == Tier.BUY }.map { it.code }.toSet()
        assertEquals(setOf("1", "3", "5"), buys)

        // 卖出 = 排名末3(2.0, 1.0, 0.0) — 注意 topN=3 且 n=5, 末3与首3重叠
        // 这里 n=5, topN=3, bottomN=3, 重叠: 排名3(score2.0)既是buy又是sell
        // 验证排序正确即可
        assertEquals(listOf("1", "3", "5", "2", "4"), recs.map { it.code })
    }

    @Test
    fun `默认配置观察窗口为5天`() {
        val cfg = StrategyConfig()
        assertArrayEquals(intArrayOf(5), cfg.windowsDays)
        assertArrayEquals(doubleArrayOf(1.0), cfg.windowWeights, 1e-9)
        assertEquals(3, cfg.topCount)
    }
}
