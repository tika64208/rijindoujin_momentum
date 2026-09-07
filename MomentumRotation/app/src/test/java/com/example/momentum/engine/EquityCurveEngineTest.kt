package com.example.momentum.engine

import com.example.momentum.model.DailyBar
import org.junit.Assert.*
import org.junit.Test

/**
 * 收益曲线引擎单元测试。
 */
class EquityCurveEngineTest {

    private fun bars(closes: List<Double>): List<DailyBar> =
        closes.mapIndexed { i, c ->
            DailyBar("2024-01-%02d".format(i + 1), c, c, c, c, 1000.0)
        }

    @Test
    fun `净值曲线以100万本金起始`() {
        // 两只平盘股票
        val stocks = listOf(
            "000001" to bars(List(30) { 100.0 }),
            "000002" to bars(List(30) { 200.0 })
        )
        val set = EquityCurveEngine.build(stocks, years = 0.1)
        assertTrue(set.dates.isNotEmpty())
        // 所有曲线首值都应是 100万
        for (c in set.curves) {
            assertEquals("曲线 ${c.label} 起始应为100万", 1_000_000.0, c.values[0], 1.0)
        }
    }

    @Test
    fun `均线上涨股票净值上升`() {
        // 一只股票持续上涨, 均仓=该股, 净值应上升
        val closes = (0..30).map { 100.0 * Math.pow(1.02, it.toDouble()) }
        val stocks = listOf("000001" to bars(closes))
        val set = EquityCurveEngine.build(stocks, years = 0.1)
        val nav = set.curves.first { it.label == "均仓持有" }
        assertTrue("上涨股票净值应 > 100万, 实际=${nav.values.last()}", nav.values.last() > 1_000_000.0)
    }

    @Test
    fun `区间对比计算正确`() {
        val set = EquityCurveEngine.CurveSet(
            dates = (1..5).map { "d$it" },
            curves = listOf(
                EquityCurveEngine.Curve("A", doubleArrayOf(100.0, 110.0, 121.0, 133.1, 146.41))
            )
        )
        val seg = EquityCurveEngine.segmentCompare(set, 0, 4)
        assertEquals(1, seg.size)
        // (146.41/100 - 1) * 100 = 46.41%
        assertEquals(46.41, seg[0].periodReturn, 0.01)
    }

    @Test
    fun `动量策略与均仓曲线并存`() {
        val stocks = listOf(
            "000001" to bars((0..30).map { 100.0 * Math.pow(1.01, it.toDouble()) }),
            "000002" to bars((0..30).map { 100.0 }),
            "000003" to bars((0..30).map { 100.0 * Math.pow(0.99, it.toDouble()) })
        )
        val set = EquityCurveEngine.build(stocks, years = 0.1)
        val labels = set.curves.map { it.label }
        assertTrue(labels.contains("动量策略"))
        assertTrue(labels.contains("均仓持有"))
        assertTrue(labels.contains("000001"))
        assertTrue(labels.contains("000002"))
        assertTrue(labels.contains("000003"))
    }
}
