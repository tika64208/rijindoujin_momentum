package com.example.momentum.engine

import com.example.momentum.data.SinaDataSource

/**
 * 实时监控引擎: 对比前后周期行情, 检测异常。
 * 移植自 monitor.py 的 check_alerts 逻辑。
 */
object MonitorEngine {

    data class MonitorConfig(
        val pctThreshold: Double = 3.0,     // 涨跌幅告警阈值 %
        val speedThreshold: Double = 0.8,   // 涨速突变阈值 %
        val limitPct: Double = 10.0,        // 默认涨跌停 10%(创业板20%需单独配)
        val amountRatio: Double = 2.5       // 成交额放大倍数阈值
    )

    /**
     * 检测异常, 返回告警文本列表。
     */
    fun checkAlerts(
        prev: Map<String, SinaDataSource.Quote>,
        curr: Map<String, SinaDataSource.Quote>,
        cfg: MonitorConfig
    ): List<String> {
        val alerts = ArrayList<String>()
        for ((code, c) in curr) {
            val name = c.name
            val limit = if (code.startsWith("300") || code.startsWith("301") || code.startsWith("688")) 20.0 else cfg.limitPct

            // 1. 涨跌幅超阈值
            if (kotlin.math.abs(c.pct) >= cfg.pctThreshold) {
                val d = if (c.pct > 0) "上涨" else "下跌"
                alerts.add("🔴 $name $d ${String.format("%+.2f", c.pct)}%（超阈值 ±${cfg.pctThreshold.toInt()}%）")
            }

            // 2. 涨速突变
            prev[code]?.let { p ->
                val dpct = c.pct - p.pct
                if (kotlin.math.abs(dpct) >= cfg.speedThreshold) {
                    val act = if (dpct > 0) "快速拉升" else "快速跳水"
                    alerts.add("⚡ $name $act ${String.format("%+.2f", dpct)}%（${String.format("%+.2f", p.pct)}% → ${String.format("%+.2f", c.pct)}%）")
                }
            }

            // 3. 逼近涨跌停
            if (c.pct >= limit * 0.9) {
                alerts.add("🚀 $name 逼近涨停 ${String.format("%+.2f", c.pct)}%")
            } else if (c.pct <= -limit * 0.9) {
                alerts.add("💥 $name 逼近跌停 ${String.format("%+.2f", c.pct)}%")
            }

            // 4. 成交额异常放大
            prev[code]?.let { p ->
                if (p.amount > 0) {
                    val ratio = c.amount / p.amount
                    if (ratio > cfg.amountRatio && c.amount > 1e8) {
                        alerts.add("📈 $name 成交额异常放大 ${String.format("%.1f", ratio)}倍（${String.format("%.1f", c.amount / 1e8)}亿）")
                    }
                }
            }
        }

        // 5. 个股走势背离(前两只)
        val codes = curr.keys.take(2)
        if (codes.size == 2) {
            val p1 = curr[codes[0]]?.pct ?: 0.0
            val p2 = curr[codes[1]]?.pct ?: 0.0
            if (kotlin.math.abs(p1 - p2) >= 3.0) {
                alerts.add("↔️ 走势背离：${curr[codes[0]]?.name} ${String.format("%+.2f", p1)}% vs ${curr[codes[1]]?.name} ${String.format("%+.2f", p2)}%")
            }
        }

        return alerts
    }

    /**
     * 判断是否交易时段(含集合竞价)。
     */
    fun isTradingTime(nowMinutesOfDay: Int, weekday: Int): Boolean {
        if (weekday >= 5) return false  // 周六日
        return (9 * 60 + 15 <= nowMinutesOfDay && nowMinutesOfDay <= 11 * 60 + 30) ||
            (13 * 60 <= nowMinutesOfDay && nowMinutesOfDay <= 15 * 60 + 5)
    }
}
