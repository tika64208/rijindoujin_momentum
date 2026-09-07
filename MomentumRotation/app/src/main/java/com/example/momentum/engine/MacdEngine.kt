package com.example.momentum.engine

import com.example.momentum.model.DailyBar
import kotlin.math.max

/**
 * MACD 指标计算引擎。
 *
 * 标准公式:
 *   EMA(12) = EMA(Close, 12)
 *   EMA(26) = EMA(Close, 26)
 *   DIF = EMA(12) − EMA(26)
 *   DEA = EMA(DIF, 9)
 *   BAR = 2 × (DIF − DEA)
 *
 * 输出(按日期升序排列):
 *   每个日期对应一组 (DIF, DEA, BAR)。
 */
object MacdEngine {

    data class MacdPoint(
        val date: String,      // yyyy-MM-dd
        val dif: Double,
        val dea: Double,
        val bar: Double
    )

    /**
     * 从日线数据计算 MACD 指标。
     * 返回与输入 bars 一一对应的指标数组(bars 按日期升序)。
     * bars 不足时返回空。
     */
    fun compute(bars: List<DailyBar>): List<MacdPoint> {
        if (bars.size < 26) return emptyList()

        val closes = bars.map { it.close }
        val dates = bars.map { it.date }

        // EMA 12
        val ema12 = ema(closes, 12)
        // EMA 26
        val ema26 = ema(closes, 26)

        // DIF = EMA12 - EMA26
        val dif = DoubleArray(closes.size)
        for (i in 0 until closes.size) {
            dif[i] = if (ema12[i].isNaN() || ema26[i].isNaN()) Double.NaN
            else ema12[i] - ema26[i]
        }

        // DEA = EMA(DIF, 9), 从第26个之后开始
        val dea = ema(dif.toList().subList(25, closes.size), 9)

        // BAR = 2 * (DIF - DEA)
        val points = ArrayList<MacdPoint>(closes.size)
        for (i in 0 until closes.size) {
            val d = dif[i]
            val e = if (i >= 25) dea[i - 25] else Double.NaN
            val b = if (d.isNaN() || e.isNaN()) Double.NaN else 2.0 * (d - e)
            points.add(MacdPoint(dates[i], d, e, b))
        }
        return points
    }

    /**
     * 指数移动平均 EMA(N)。
     * 返回与输入等长的数组, 前 N-1 个值为 NaN。
     */
    private fun ema(values: List<Double>, n: Int): DoubleArray {
        val result = DoubleArray(values.size) { Double.NaN }
        if (values.size < n) return result

        // 初始 EMA = 前 N 个的简单平均
        var sum = 0.0
        for (i in 0 until n) sum += values[i]
        result[n - 1] = sum / n

        val alpha = 2.0 / (n + 1)
        for (i in n until values.size) {
            result[i] = values[i] * alpha + result[i - 1] * (1 - alpha)
        }
        return result
    }
}