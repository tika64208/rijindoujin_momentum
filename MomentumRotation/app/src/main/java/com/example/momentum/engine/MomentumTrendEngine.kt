package com.example.momentum.engine

import com.example.momentum.model.DailyBar
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 动量翻转预警信号。
 *
 * 判断每只股票的动量处于: 加速 / 衰减 / 转弱 / 弱势 / 数据不足。
 */
enum class MomentumSignal {
    ACCEL,   // 动量加速
    DECAY,   // 动量衰减
    REVERSE, // 动量转弱
    WEAK,    // 弱势区间
    INSUFFICIENT // 数据不足
}

data class MomentumTrend(
    val code: String,
    val name: String,
    val signal: MomentumSignal,
    val shortReturn: Double,   // 短窗(约2周)对数收益
    val midReturn: Double      // 中窗(约3月)对数收益
) {
    val label: String
        get() = when (signal) {
            MomentumSignal.ACCEL -> "动量加速"
            MomentumSignal.DECAY -> "动量衰减"
            MomentumSignal.REVERSE -> "动量转弱"
            MomentumSignal.WEAK -> "弱势区间"
            MomentumSignal.INSUFFICIENT -> "数据不足"
        }
}

object MomentumTrendEngine {

    private const val SHORT_DAYS = 10   // 约2周
    private const val MID_DAYS = 63     // 约3月

    fun compute(code: String, name: String, bars: List<DailyBar>): MomentumTrend {
        val closes = bars.map { it.close }
        if (closes.size < MID_DAYS + SHORT_DAYS + 2) {
            return MomentumTrend(code, name, MomentumSignal.INSUFFICIENT, Double.NaN, Double.NaN)
        }

        val rShort = ln(closes.last() / closes[closes.size - 1 - SHORT_DAYS])
        val rMid = ln(closes.last() / closes[closes.size - 1 - MID_DAYS])
        // 之前一段的3月动量(判断"由正转负")
        val rMidPrev = ln(
            closes[closes.size - 1 - SHORT_DAYS] /
                closes[closes.size - 1 - SHORT_DAYS - MID_DAYS]
        )

        val signal = when {
            rMid >= 0 && rShort >= 0 && rShort >= rMid -> MomentumSignal.ACCEL
            rMid >= 0 && rShort < rMid -> MomentumSignal.DECAY
            rMidPrev >= 0 && rMid < 0 -> MomentumSignal.REVERSE
            rShort < 0 && rMid >= 0 -> MomentumSignal.REVERSE
            else -> MomentumSignal.WEAK
        }
        return MomentumTrend(code, name, signal, rShort, rMid)
    }
}
