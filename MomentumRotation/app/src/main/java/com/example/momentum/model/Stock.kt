package com.example.momentum.model

/**
 * 单只股票的基础信息。
 */
data class Stock(
    val code: String,      // 6 位代码, 如 "603991"
    val name: String,      // 名称
    val marketPrefix: String = toMarketPrefix(code) // sh/sz/bj
) {
    companion object {
        /** 6 位代码 -> 带市场前缀 (sh/sz/bj) */
        fun toMarketPrefix(code: String): String = when {
            code.startsWith("920") || code.startsWith("4") || code.startsWith("8") -> "bj$code"
            code.startsWith("6") || code.startsWith("9") || code.startsWith("5") -> "sh$code"
            code.startsWith("0") || code.startsWith("2") || code.startsWith("3") -> "sz$code"
            else -> "sh$code"
        }
    }
}
