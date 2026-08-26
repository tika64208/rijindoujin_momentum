package com.example.momentum.engine

import com.example.momentum.model.DailyBar
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * 回测引擎: 动量轮动策略 vs 等权持有 vs 各单股。
 *
 * 简化回测:
 * - 每 rebalanceDays 个交易日, 按过去 lookback 窗口对数收益选最强一只持有;
 * - 等权组合 = 全部等权;
 * - 输出累计收益 / 年化 / 最大回撤。
 */
object BacktestEngine {

    data class Perf(
        val totalReturn: Double,    // 累计收益
        val annualReturn: Double,   // 年化收益
        val maxDrawdown: Double     // 最大回撤(负数)
    )

    data class BacktestResult(
        val rotation: Perf,
        val equalWeight: Perf,
        val singles: Map<String, Perf>
    )

    fun run(
        stocks: List<Pair<String, List<DailyBar>>>,
        rebalanceDays: Int = 10,
        lookback: Int = 250
    ): BacktestResult {
        // 对齐到最短长度
        val closesMap = LinkedHashMap<String, List<Double>>()
        for ((code, bars) in stocks) {
            closesMap[code] = bars.map { it.close }
        }
        val minLen = closesMap.values.minOf { it.size }
        if (minLen < 30) {
            return BacktestResult(
                rotation = Perf(0.0, 0.0, 0.0),
                equalWeight = Perf(0.0, 0.0, 0.0),
                singles = emptyMap()
            )
        }
        val aligned = closesMap.mapValues { (_, c) -> c.takeLast(minLen) }

        // 日收益率
        fun dailyReturns(closes: List<Double>): DoubleArray {
            val r = DoubleArray(closes.size)
            for (i in 1 until closes.size) {
                val prev = closes[i - 1]
                r[i] = if (prev > 0) closes[i] / prev - 1.0 else 0.0
            }
            return r
        }
        val retsMap = aligned.mapValues { (_, c) -> dailyReturns(c) }
        val codes = aligned.keys.toList()

        // 等权组合日收益
        val eqDaily = DoubleArray(minLen)
        for (i in 0 until minLen) {
            var sum = 0.0
            for (code in codes) sum += retsMap[code]!![i]
            eqDaily[i] = sum / codes.size
        }

        // 轮动策略日收益
        val rotDaily = DoubleArray(minLen)
        var currentPick: String? = null
        for (i in 0 until minLen) {
            if (i == 0) continue
            if (i % rebalanceDays == 0 || currentPick == null) {
                // 选截至 i-1 动量最强的
                var best = -Double.MAX_VALUE
                var bestCode = codes[0]
                for (code in codes) {
                    val c = aligned[code]!!
                    val start = max(0, i - lookback)
                    if (i - start < 30) continue
                    val score = ln(c[i - 1] / c[start])
                    if (score > best) {
                        best = score
                        bestCode = code
                    }
                }
                currentPick = bestCode
            }
            rotDaily[i] = retsMap[currentPick]!![i]
        }

        fun perf(daily: DoubleArray): Perf {
            var cum = 1.0
            var peak = 1.0
            var maxDd = 0.0
            for (i in 0 until daily.size) {
                cum *= (1.0 + daily[i])
                peak = max(peak, cum)
                val dd = cum / peak - 1.0
                if (dd < maxDd) maxDd = dd
            }
            val total = cum - 1.0
            val nYears = daily.size / 252.0
            val annual = if (nYears > 0 && total > -1.0) {
                (1.0 + total).pow(1.0 / nYears) - 1.0
            } else 0.0
            return Perf(total, annual, maxDd)
        }

        val singles = LinkedHashMap<String, Perf>()
        for (code in codes) {
            singles[code] = perf(retsMap[code]!!)
        }

        return BacktestResult(
            rotation = perf(rotDaily),
            equalWeight = perf(eqDaily),
            singles = singles
        )
    }
}
