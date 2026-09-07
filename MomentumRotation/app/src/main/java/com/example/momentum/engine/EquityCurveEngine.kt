package com.example.momentum.engine

import com.example.momentum.model.DailyBar
import kotlin.math.ln

/**
 * 收益曲线引擎: 动量策略 vs 均仓 vs 个股, 以 100 万本金计。
 *
 * 输出净值曲线(nav)序列, 供 UI 绘制对比图, 支持任意区间对比。
 */
object EquityCurveEngine {

    const val INITIAL_CAPITAL = 1_000_000.0  // 100万本金

    /**
     * 一条净值曲线。
     * @param label 曲线名称
     * @param values 净值序列(与 dates 一一对应)
     */
    data class Curve(
        val label: String,
        val values: DoubleArray
    )

    data class CurveSet(
        val dates: List<String>,        // yyyy-MM-dd
        val curves: List<Curve>         // 动量策略 / 均仓 / 个股...
    )

    data class SegmentResult(
        val label: String,
        val periodReturn: Double        // 区间收益(百分比, 如 12.3 表示 +12.3%)
    )

    /**
     * 构建近 [years] 年的净值曲线。
     *
     * @param stocks 股票代码 + 日线数据
     * @param rebalanceDays 调仓周期(交易日)
     * @param lookback 动量回看窗口(交易日)
     * @param topN 动量策略持有 top N 只(等权)
     */
    fun build(
        stocks: List<Pair<String, List<DailyBar>>>,
        years: Double = 1.0,
        rebalanceDays: Int = 2,
        lookback: Int = 5,
        topN: Int = 3
    ): CurveSet {
        if (stocks.isEmpty()) return CurveSet(emptyList(), emptyList())

        // 对齐日期, 取近 ~years 年
        val dateSets = stocks.map { (_, bars) -> bars.map { it.date }.toSet() }
        val commonDates = dateSets.reduce { acc, set -> acc.intersect(set) }
            .sorted()

        val n = (252 * years).toInt().coerceAtLeast(1)
        val dates = commonDates.takeLast(n)
        if (dates.isEmpty()) return CurveSet(emptyList(), emptyList())

        // 每只股票的收盘价序列(与 dates 对齐)
        val closeMap = LinkedHashMap<String, DoubleArray>()
        for ((code, bars) in stocks) {
            val map = bars.associateBy { it.date }
            val arr = DoubleArray(dates.size)
            for (i in dates.indices) {
                arr[i] = map[dates[i]]?.close ?: Double.NaN
            }
            // 前向填充 NaN
            var last = Double.NaN
            for (i in arr.indices) {
                if (arr[i].isNaN()) arr[i] = last else last = arr[i]
            }
            closeMap[code] = arr
        }

        // 日收益率
        val retMap = LinkedHashMap<String, DoubleArray>()
        for ((code, closes) in closeMap) {
            val r = DoubleArray(closes.size)
            for (i in 1 until closes.size) {
                r[i] = if (closes[i - 1] > 0 && closes[i] > 0) closes[i] / closes[i - 1] - 1.0 else 0.0
            }
            retMap[code] = r
        }

        val codes = stocks.map { it.first }
        val m = dates.size

        // 均仓: 每日平均收益
        val eqRets = DoubleArray(m)
        for (i in 0 until m) {
            var sum = 0.0
            for (code in codes) sum += retMap[code]!![i]
            eqRets[i] = sum / codes.size
        }

        // 动量策略: 每 rebalance 天按 lookback 动量选 topN 持有
        val rotRets = DoubleArray(m)
        var holdings: List<String>? = null
        for (i in 0 until m) {
            if (i == 0) continue
            if (i % rebalanceDays == 0 || holdings == null) {
                val scores = HashMap<String, Double>()
                for (code in codes) {
                    val closes = closeMap[code]!!
                    val start = (i - lookback).coerceAtLeast(0)
                    if (i - start >= lookback && closes[start] > 0 && closes[i - 1] > 0) {
                        scores[code] = ln(closes[i - 1] / closes[start])
                    } else {
                        scores[code] = Double.NEGATIVE_INFINITY
                    }
                }
                holdings = scores.entries.sortedByDescending { it.value }
                    .take(topN).map { it.key }
            }
            var sum = 0.0
            for (code in holdings!!) sum += retMap[code]!![i]
            rotRets[i] = sum / holdings!!.size
        }

        // 转净值(100万本金)
        fun toNav(daily: DoubleArray): DoubleArray {
            val nav = DoubleArray(m)
            var cum = 1.0
            for (i in 0 until m) {
                cum *= (1.0 + daily[i])
                nav[i] = INITIAL_CAPITAL * cum
            }
            return nav
        }

        val curves = ArrayList<Curve>()
        curves.add(Curve("动量策略", toNav(rotRets)))
        curves.add(Curve("均仓持有", toNav(eqRets)))
        for (code in codes) {
            curves.add(Curve(code, toNav(retMap[code]!!)))
        }

        return CurveSet(dates, curves)
    }

    /**
     * 区间对比: 返回各曲线在 [startIdx, endIdx] 的区间收益(百分比)。
     */
    fun segmentCompare(set: CurveSet, startIdx: Int, endIdx: Int): List<SegmentResult> {
        if (set.curves.isEmpty() || startIdx < 0 || endIdx >= set.dates.size || startIdx > endIdx) {
            return emptyList()
        }
        return set.curves.map { c ->
            val s = c.values[startIdx]
            val e = c.values[endIdx]
            val ret = if (s > 0) (e / s - 1.0) * 100.0 else 0.0
            SegmentResult(c.label, ret)
        }
    }
}
