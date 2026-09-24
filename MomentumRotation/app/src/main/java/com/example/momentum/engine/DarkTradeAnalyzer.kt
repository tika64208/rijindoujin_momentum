package com.example.momentum.engine

import com.example.momentum.data.DarkTradeDataSource

/**
 * 暗盘交易自动分析引擎。
 *
 * 根据暗盘历史记录, 生成人话版分析结论:
 * - 今日是否交易、净流向
 * - 近期连续净流入/流出天数
 * - 与近期均值对比(是否异常放量)
 */
object DarkTradeAnalyzer {

    data class Conclusion(
        val todayRecord: DarkTradeDataSource.DarkTradeRecord?,  // 今日记录
        val todayNet: Double,      // 今日净额(元)
        val streakDays: Int,       // 连续净流入(+)/流出(-)天数
        val streakDirection: String, // "连续净流入" / "连续净流出" / "无连续"
        val avgRecent: Double,     // 近10笔平均净额
        val isAbnormal: Boolean    // 是否异常放大(超均值3倍)
    )

    /**
     * 分析暗盘记录(按日期升序或降序均可, 内部会排序)。
     */
    fun analyze(records: List<DarkTradeDataSource.DarkTradeRecord>): Conclusion? {
        if (records.isEmpty()) return null

        // 按日期降序(最新在前)
        val sorted = records.sortedByDescending { it.date }
        val today = sorted.first()

        // 今日净额 = 后台字段8(净值)
        val todayNet = today.netAmount

        // 连续净流入/流出天数(从最新往前数同方向)
        var streak = 0
        var direction = 0  // 1流入, -1流出, 0=平
        for (r in sorted) {
            val net = r.netAmount
            val d = if (net > 0) 1 else if (net < 0) -1 else 0
            if (streak == 0) {
                direction = d
                streak = if (d != 0) 1 else 0
            } else {
                if (d == direction) streak++ else break
            }
        }

        // 近10笔平均净额
        val recent = sorted.take(10)
        val avgRecent = recent.map { it.netAmount }.average()

        // 异常判断: 今日净额绝对值超过均值(abs)的3倍
        val isAbnormal = avgRecent != 0.0 && kotlin.math.abs(todayNet) > kotlin.math.abs(avgRecent) * 3.0

        val streakDesc = when (direction) {
            1 -> "连续净流入 $streak 天"
            -1 -> "连续净流出 $streak 天"
            else -> "无连续方向"
        }

        return Conclusion(
            todayRecord = today,
            todayNet = todayNet,
            streakDays = streak,
            streakDirection = streakDesc,
            avgRecent = avgRecent,
            isAbnormal = isAbnormal
        )
    }

    /** 生成人话版结论文本。 */
    fun toText(c: Conclusion): String {
        val sb = StringBuilder()
        val netYi = c.todayNet / 1e8

        if (c.todayNet > 0) {
            sb.append("今日暗盘净流入 ${String.format("%.2f", netYi)} 亿，")
            sb.append("大资金收盘后净接货，短线偏积极。")
        } else if (c.todayNet < 0) {
            sb.append("今日暗盘净流出 ${String.format("%.2f", kotlin.math.abs(netYi))} 亿，")
            sb.append("大资金收盘后净减持，需警惕。")
        } else {
            sb.append("今日暗盘买卖平衡，无明显方向。")
        }

        if (c.streakDays >= 2) {
            sb.append(" 目前${c.streakDirection}。")
        }

        if (c.isAbnormal) {
            sb.append(" ⚠ 今日成交额异常放大（远超近期均值），值得重点关注。")
        }

        return sb.toString()
    }
}
