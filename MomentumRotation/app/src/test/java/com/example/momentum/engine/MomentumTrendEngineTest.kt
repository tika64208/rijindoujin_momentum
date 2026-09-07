package com.example.momentum.engine

import com.example.momentum.model.DailyBar
import org.junit.Assert.*
import org.junit.Test

/**
 * 动量翻转信号单元测试。
 */
class MomentumTrendEngineTest {

    private fun bars(vararg closes: Double): List<DailyBar> =
        closes.mapIndexed { i, c ->
            DailyBar("2024-01-%02d".format(i + 1), c, c, c, c, 1000.0)
        }

    @Test
    fun `数据不足返回 insufficient`() {
        val t = MomentumTrendEngine.compute("000001", "测试", bars(100.0, 101.0))
        assertEquals(MomentumSignal.INSUFFICIENT, t.signal)
    }

    @Test
    fun `持续上涨且近期加速返回 accel`() {
        // 前段几乎横盘, 后段连续涨停 → 短窗动量 >> 中窗动量, 应为 ACCEL
        val closes = (0..80).map { i ->
            if (i < 70) 100.0 * (1.0 + 0.00001 * i)      // 基本横盘
            else 100.0 * Math.pow(1.10, (i - 70).toDouble()) // 近10天连续涨停(+10%/天)
        }
        val t = MomentumTrendEngine.compute("000001", "测试", bars(*closes.toDoubleArray()))
        assertTrue(
            "连续涨停应 ACCEL, 实际=${t.signal} (short=${t.shortReturn}, mid=${t.midReturn})",
            t.signal == MomentumSignal.ACCEL
        )
    }

    @Test
    fun `先涨后跌转弱返回 reverse`() {
        // 中段上涨, 近期下跌 → 由正转负
        val closes = (0..80).map { i ->
            when {
                i < 40 -> 100.0 * (1.0 + 0.005 * i)      // 上涨
                else -> 100.0 * (1.0 + 0.005 * 40) * Math.pow(0.99, (i - 40).toDouble()) // 下跌
            }
        }
        val t = MomentumTrendEngine.compute("000001", "测试", bars(*closes.toDoubleArray()))
        assertTrue(
            "应为 REVERSE 或 WEAK, 实际=${t.signal}",
            t.signal == MomentumSignal.REVERSE || t.signal == MomentumSignal.WEAK
        )
    }
}
