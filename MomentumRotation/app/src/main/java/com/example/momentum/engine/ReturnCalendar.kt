package com.example.momentum.engine

import java.time.LocalDate
import java.time.YearMonth

data class ReturnCalendarMonth(
    val month: YearMonth,
    val cells: List<LocalDate?>,
    val records: Map<LocalDate, SimDayRecord>,
    val profit: Double,
    val returnPct: Double?,
    val tradeDays: Int,
    val winningDays: Int,
    val losingDays: Int,
    val holdingDays: Int
)

/** 仅展示已存账本，不重算订单；缺失日期、持仓估算与零收益交易日分别处理。 */
object ReturnCalendar {
    fun records(state: SimAccountState): Map<LocalDate, SimDayRecord> {
        val dates = linkedMapOf<LocalDate, SimDayRecord>()
        // 最新记录优先；todayRecord 覆盖同日历史，避免重复统计。
        state.historyRecords.asReversed().forEach { record -> parseDate(record.date)?.let { dates[it] = record } }
        state.todayRecord?.let { record -> parseDate(record.date)?.let { dates[it] = record } }
        return dates.toSortedMap()
    }

    fun month(month: YearMonth, records: Map<LocalDate, SimDayRecord>): ReturnCalendarMonth {
        val first = month.atDay(1)
        val leading = first.dayOfWeek.value - 1 // 周一至周日。
        val slots = ((leading + month.lengthOfMonth() + 6) / 7) * 7
        val days = records.filterKeys { YearMonth.from(it) == month }.toSortedMap()
        val settled = days.values.filter { it.status == "SETTLED" }
        val profit = settled.sumOf { it.profit }
        val capital = settled.firstOrNull()?.startCapital
        return ReturnCalendarMonth(month, List(slots) { index ->
            val day = index - leading + 1
            if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }, days, profit, capital?.takeIf { it > 0 && it.isFinite() }?.let { profit / it * 100 },
            settled.count { it.orders.isNotEmpty() }, settled.count { it.orders.isNotEmpty() && it.profit > 0 },
            settled.count { it.orders.isNotEmpty() && it.profit < 0 }, days.values.count { it.status == "HOLDING" })
    }

    fun parseDate(date: String): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()
}
