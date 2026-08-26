package com.example.momentum.engine

/**
 * 策略参数配置。
 *
 * 观察窗口用「交易日天数」表达(默认 5 天, 即可配为任意天数)。
 * 支持多窗口加权, 但当前默认单窗口(5天)用于短线轮动。
 */
data class StrategyConfig(
    // 观察窗口: 用交易日天数(5 = 约1周)
    val windowsDays: IntArray = intArrayOf(5),
    // 各窗口权重(与 windowsDays 等长)
    val windowWeights: DoubleArray = doubleArrayOf(1.0),
    val volPenaltyLambda: Double = 0.30,
    val riskFreeAnnual: Double = 0.015,
    // 推荐只选 top N 只买入
    val topCount: Int = 3,
    val minHistoryDays: Int = 10
) {
    init {
        require(windowsDays.size == windowWeights.size) { "窗口数与权重数必须一致" }
        require(kotlin.math.abs(windowWeights.sum() - 1.0) < 1e-6) { "权重之和应为 1" }
        require(topCount > 0) { "topCount 必须为正" }
    }

    companion object {
        const val TRADING_DAYS_PER_YEAR = 252
    }
}
