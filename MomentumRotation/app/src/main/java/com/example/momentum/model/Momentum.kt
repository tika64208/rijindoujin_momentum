package com.example.momentum.model

/**
 * 日 K 线数据(前复权)。
 * 注意: 腾讯 qfqday 字段顺序为 [date, open, close, high, low, volume], 解析时已标准化。
 */
data class DailyBar(
    val date: String,       // yyyy-MM-dd
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

/**
 * 单只股票的动量计算结果。
 */
data class StockMomentum(
    val code: String,
    val name: String,
    val score: Double = Double.NaN,               // 总动量分 M
    val layerScores: Map<Int, Double> = emptyMap(), // {窗口月数: M_k}
    val annualVol: Double = Double.NaN,             // 年化波动率
    val r12m: Double = Double.NaN,                  // 12个月对数收益
    val insufficient: Boolean = false               // 数据不足
)

/**
 * 轮动推荐档位。
 */
enum class Tier { BUY, HOLD, SELL }

/**
 * 一条轮动推荐结果。
 */
data class Recommendation(
    val code: String,
    val name: String,
    val score: Double,
    val tier: Tier,
    val annualVol: Double,
    val r12m: Double,
    val rank: Int
)
