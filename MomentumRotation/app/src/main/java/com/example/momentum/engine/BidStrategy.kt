package com.example.momentum.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 仓鼠量化竞价系统 V3.2 的策略引擎(Kotlin 翻译)。
 *
 * 包含:
 *   - 五因子涨停概率算法 (computeProbability)
 *   - V2 评分算法 (computeV2Score)
 *   - 竞价量估算 (estimateBidVolume)
 *   - 排除判断 (getExcludeStatus)
 *
 * 输入: 东财 push2dycalc 接口的竞价数据字段
 */
object BidStrategy {

    data class BidItem(
        val code: String,
        val name: String,
        val latestPrice: Double = 0.0,    // f2 最新价
        val realChange: Double = 0.0,      // f3 涨跌幅%
        val openPrice: Double = 0.0,       // f17 开盘价(f17>0) else f4
        val volume: Int = 0,               // f5 成交量
        val amount: Double = 0.0,          // f6 成交额
        val turnover: Double = 0.0,        // f8 换手率%
        val circMv: Double = 0.0,          // f10 流通市值
        val highPrice: Double = 0.0,       // f18 最高价
        val totalMv: Double = 0.0,         // f20 总市值
        val bidChange: Double = 0.0,       // f615 竞价涨幅%
        val warnFlag: Int = 0,             // f630 异动标志(3-5)
        val industry: String = "",
        val province: String = "",
        val conceptQuick: String = ""      // f103 速动概念
    )

    data class BidResult(
        val item: BidItem,
        val probability: Int = 0,          // 涨停概率 0-100
        val confidence: Int = 0,           // 可信度 55-90
        val bidTurnover: Double = 0.0,     // 竞价换手率%
        val entityChange: Double = 0.0,    // 实体涨幅%
        val score: Int = 0,               // V2评分 0-100
        val bidVolume: Int = 0,           // 估算竞价量
        val warnText: String = "-",       // 异动文本
        val excluded: Boolean = false,
        val excludeReason: String = "",
        val reason: String = ""           // 入选理由
    )

    // ===== 筛选配置（默认不过滤，重置时用仓鼠默认） =====
    data class BidConfig(
        val filterStSuspend: Boolean = true,
        val filterLimitUp: Boolean = false,
        val bidMin: Double = 0.0,
        val bidMax: Double = 0.0,
        val probMin: Double = 0.0,
        val probMax: Double = 0.0,
        val confMin: Double = 0.0,
        val confMax: Double = 0.0,
        val mvMin: Double = 0.0,
        val mvMax: Double = 0.0,
        val priceMin: Double = 0.0,
        val priceMax: Double = 0.0,
        val scoreMin: Double = 0.0,
        val scoreMax: Double = 0.0,
        val marketHs: Boolean = true,
        val marketCyb: Boolean = true,
        val marketKcb: Boolean = true,
        val filterLargeVolume: Boolean = false,
        val filterLargeVolumeThreshold: Int = 100000,
        val filterLocked: Boolean = false
    ) {
        companion object {
            /** 默认重置配置: 竞价涨幅{1%,7%}, 概率{55%,100}, 可信度{55,100}, 市值>100亿, 股价>3元 */
            val DEFAULT = BidConfig(
                bidMin = 1.0,
                bidMax = 7.0,
                probMin = 55.0,
                probMax = 100.0,
                confMin = 55.0,
                confMax = 100.0,
                mvMin = 100.0,
                mvMax = 0.0,
                priceMin = 3.0,
                priceMax = 0.0,
                scoreMin = 0.0,
                scoreMax = 0.0,
                filterStSuspend = true,
                filterLimitUp = false,
                marketHs = true,
                marketCyb = true,
                marketKcb = true
            )

            /** 高胜率预设配置(追求尾盘胜率): 竞价{2%,4%}, 概率{60,100}, 可信度{70,100}, 评分>=35, 市值{30,200亿}, 股价{5,60元}, 剔ST剔涨停 */
            val HIGH_WIN_RATE = BidConfig(
                bidMin = 2.0,
                bidMax = 4.0,
                probMin = 60.0,
                probMax = 100.0,
                confMin = 70.0,
                confMax = 100.0,
                scoreMin = 35.0,
                scoreMax = 0.0,
                mvMin = 30.0,
                mvMax = 200.0,
                priceMin = 5.0,
                priceMax = 60.0,
                filterStSuspend = true,
                filterLimitUp = true,
                marketHs = true,
                marketCyb = true,
                marketKcb = true
            )
        }
    }

    // ===== 五因子涨停概率 =====
    fun computeProbability(item: BidItem): Pair<Int, Int> {
        val bidChange = item.bidChange
        val bidTurnover = computeBidTurnover(item)
        val warnType = item.warnFlag
        val circMvYi = item.circMv / 100_000_000.0  // circMv 是总市值(元)，转亿
        val yesterday = item.realChange

        // 因子1: 竞价涨幅(权重34%)
        val bidScore = when {
            bidChange >= 3.0 && bidChange <= 5.5 -> 1.0
            bidChange >= 2.0 -> 0.88
            bidChange >= 1.5 -> 0.65
            bidChange > 0 -> 0.4
            else -> 0.1
        }
        // 因子2: 竞价活跃度(竞价换手率, 权重32%)
        val activityScore = when {
            bidTurnover >= 0.8 -> 1.0
            bidTurnover >= 0.4 -> 0.88
            bidTurnover >= 0.2 -> 0.72
            bidTurnover >= 0.08 -> 0.5
            bidTurnover > 0 -> 0.3
            else -> 0.1
        }
        // 因子3: 异动等级(权重17%)
        val warnScore = when (warnType) {
            5 -> 1.0
            4 -> 0.85
            3 -> 0.6
            else -> 0.18
        }
        // 因子4: 流通市值(权重11%)
        val marketScore = when {
            circMvYi < 30 -> 1.0
            circMvYi < 60 -> 0.88
            circMvYi < 120 -> 0.68
            circMvYi < 250 -> 0.45
            else -> 0.22
        }
        // 因子5: 昨日涨幅(权重6%)
        val yesterdayScore = when {
            yesterday >= 3.0 && yesterday < 9.5 -> 0.9
            yesterday >= 1.0 -> 0.65
            yesterday >= 0 -> 0.4
            yesterday > -3.0 -> 0.25
            else -> 0.15
        }

        val base = bidScore * 0.34 + activityScore * 0.32 + warnScore * 0.17 +
                   marketScore * 0.11 + yesterdayScore * 0.06
        val prob = max(5, min(95, (base * 100).roundToInt()))

        var conf = 65
        if (warnType >= 4) conf += 10
        if (bidTurnover >= 0.4) conf += 8
        if (bidChange in 2.0..6.5) conf += 7
        conf = min(90, max(55, conf))

        return Pair(prob, conf)
    }

    // ===== V2 评分 =====
    fun computeV2Score(item: BidItem, bidVolume: Int): Int {
        val bidScore = max(0.0, min(item.bidChange / 9.0, 1.0)) * 50
        val realScore = max(0.0, min(item.realChange / 10.0, 1.0)) * 20
        val warnScore = when {
            item.warnFlag == 5 -> 20
            item.warnFlag == 4 -> 16
            item.bidChange >= 4 -> 10
            else -> 0
        }
        val vol = if (bidVolume > 0) bidVolume else estimateBidVolume(item)
        val volumeScore = max(0.0, min(vol / 200000.0, 1.0)) * 10
        val total = (bidScore + realScore + warnScore + volumeScore).roundToInt()
        return max(0, min(total, 100))
    }

    // ===== 竞价换手率 =====
    fun computeBidTurnover(item: BidItem): Double {
        if (item.circMv <= 0 || item.volume <= 0) return 0.0
        val v = item.volume * item.latestPrice / (item.circMv * 100)
        return if (v.isNaN() || v.isInfinite()) 0.0 else v
    }

    // ===== 实体涨幅 =====
    fun computeEntityChange(item: BidItem): Double {
        if (item.openPrice <= 0) return 0.0
        return (item.latestPrice - item.openPrice) / item.openPrice * 100
    }

    // ===== 竞价量估算 =====
    fun estimateBidVolume(item: BidItem): Int {
        var ratio = 0.035
        if (item.bidChange >= 5) ratio = 0.055
        else if (item.bidChange >= 3) ratio = 0.045
        else if (item.bidChange <= 0) ratio = 0.018
        val v = (item.volume * ratio).toInt()
        return max(min(v, 3_000_000), 500)
    }

    // ===== 排除判断 =====
    fun getExcludeStatus(item: BidItem, config: BidConfig): Pair<Boolean, String> {
        // 无有效报价
        if (item.latestPrice <= 0 && item.openPrice <= 0)
            return Pair(true, "无有效报价")

        // ST 过滤
        if (config.filterStSuspend && item.name.contains("ST", ignoreCase = true))
            return Pair(true, "ST")

        // 涨停过滤
        if (config.filterLimitUp && item.realChange >= 9.5)
            return Pair(true, "已涨停")

        // 竞价涨幅区间过滤
        if (config.bidMin > 0 && item.bidChange < config.bidMin)
            return Pair(true, "竞价涨幅<${config.bidMin}%")
        if (config.bidMax > 0 && item.bidChange > config.bidMax)
            return Pair(true, "竞价涨幅>${config.bidMax}%")

        // 股价区间过滤
        if (config.priceMin > 0 && item.latestPrice < config.priceMin)
            return Pair(true, "股价<${config.priceMin}")
        if (config.priceMax > 0 && item.latestPrice > config.priceMax)
            return Pair(true, "股价>${config.priceMax}")

        // 流通市值区间过滤
        val circMvYi = item.circMv / 100_000_000.0  // circMv 是总市值(元)，转亿
        if (config.mvMin > 0 && circMvYi < config.mvMin)
            return Pair(true, "流通市值<${config.mvMin}亿")
        if (config.mvMax > 0 && circMvYi > config.mvMax)
            return Pair(true, "流通市值>${config.mvMax}亿")

        return Pair(false, "")
    }

    // ===== 异动文本 =====
    fun getWarnText(flag: Int): String = when {
        flag >= 5 -> "🔥强"
        flag == 4 -> "⚡中"
        flag == 3 -> "↑弱"
        else -> "-"
    }

    // ===== 综合计算一只股票 =====
    fun compute(item: BidItem, config: BidConfig): BidResult {
        val bidTurnover = computeBidTurnover(item)
        val entityChange = computeEntityChange(item)
        val bidVolume = estimateBidVolume(item)
        val (prob, conf) = computeProbability(item)
        val score = computeV2Score(item, bidVolume)
        val (excluded, reason) = getExcludeStatus(item, config)
        val warnText = getWarnText(item.warnFlag)

        // 入选理由(简版)
        val reasons = mutableListOf<String>()
        if (item.bidChange >= 3) reasons.add("竞价+${String.format("%.1f", item.bidChange)}%")
        if (bidTurnover >= 0.3) reasons.add("活跃${String.format("%.3f", bidTurnover)}")
        if (item.warnFlag >= 4) reasons.add("异动${warnText}")
        if (prob >= 70) reasons.add("概率${prob}")

        return BidResult(
            item = item,
            probability = prob,
            confidence = conf,
            bidTurnover = bidTurnover,
            entityChange = entityChange,
            score = score,
            bidVolume = bidVolume,
            warnText = warnText,
            excluded = excluded,
            excludeReason = reason,
            reason = reasons.joinToString(" · ").ifBlank { "-" }
        )
    }
}