package com.example.momentum.util

import kotlin.math.abs

/**
 * 证券与金融数据通用格式化工具。
 */
object FinanceFormatter {

    /**
     * 格式化股价，保留两位小数。
     * 如: 12.34
     */
    fun formatPrice(v: Double): String = String.format("%.2f", v)

    /**
     * 格式化涨跌幅百分比。
     * @param withSign 是否强制带符号 (+/-)
     * 如: +3.45% 或 -1.20%
     */
    fun formatPct(v: Double, withSign: Boolean = true): String {
        return if (withSign) {
            String.format("%+.2f%%", v)
        } else {
            String.format("%.2f%%", v)
        }
    }

    /**
     * 格式化金额为“亿元”单位。
     * @param v 金额(元)
     * @param abs 是否取绝对值
     * 如: +1.23亿 或 1.23亿
     */
    fun formatAmountYi(v: Double, abs: Boolean = false): String {
        val target = if (abs) abs(v) else v
        val yi = target / 100_000_000.0
        return if (abs) {
            String.format("%.2f亿", yi)
        } else {
            String.format("%+.2f亿", yi)
        }
    }

    /**
     * 格式化总市值/流通市值。
     * @param circMv 市值(元)
     */
    fun formatMarketCap(circMv: Double): String {
        val yi = circMv / 100_000_000.0
        if (yi <= 0) return "-"
        return if (yi >= 1000) {
            String.format("%.0f亿", yi)
        } else {
            String.format("%.1f亿", yi)
        }
    }

    /**
     * 格式化筛选参数输入值：
     * - 0.0 转为空字符串（展示“不限”占位）
     * - 整数转为无小数位字符串（如 55.0 -> "55"）
     * - 浮点数保留实际小数位
     */
    fun formatFilterNumber(v: Double): String {
        if (v == 0.0) return ""
        return if (v == v.toLong().toDouble()) {
            v.toLong().toString()
        } else {
            v.toString()
        }
    }
}
