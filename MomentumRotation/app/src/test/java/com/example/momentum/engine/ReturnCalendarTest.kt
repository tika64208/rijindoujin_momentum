package com.example.momentum.engine

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ReturnCalendarTest {
    private fun day(date: String, start: Double = 1000.0, profit: Double = 100.0, status: String = "SETTLED",
                    trade: Boolean = true) = SimDayRecord(date, start, start + profit, profit, profit / start * 100,
        orders = if (trade) listOf(SimOrder("000001", "样例", 10.0, shares = 100, allocatedCapital = start, actualCost = start)) else emptyList(), status = status)

    @Test fun `11月周日开头日期正确对齐且显示30日`() {
        val month = ReturnCalendar.month(YearMonth.of(2026, 11), emptyMap())
        assertEquals(42, month.cells.size)
        assertTrue(month.cells.take(6).all { it == null })
        assertEquals(LocalDate.of(2026, 11, 1), month.cells[6])
        assertEquals(LocalDate.of(2026, 11, 30), month.cells[35])
        assertEquals(30, month.cells.filterNotNull().size)
    }

    @Test fun `闰年2月29日可展示且空白不变成零收益记录`() {
        val month = ReturnCalendar.month(YearMonth.of(2028, 2), emptyMap())
        assertTrue(month.cells.contains(LocalDate.of(2028, 2, 29)))
        assertEquals(29, month.cells.filterNotNull().size)
        assertTrue(month.records.isEmpty())
        assertNull(month.returnPct)
    }

    @Test fun `月收益用盈亏与月初记录资金计算而非累加每日收益率`() {
        val state = SimAccountState(historyRecords = listOf(day("2026-11-02", 1000.0, 100.0), day("2026-11-03", 1100.0, 110.0)))
        val month = ReturnCalendar.month(YearMonth.of(2026, 11), ReturnCalendar.records(state))
        assertEquals(210.0, month.profit, 0.0)
        assertEquals(21.0, month.returnPct!!, 1e-12)
        assertEquals(2, month.winningDays)
    }

    @Test fun `今日记录覆盖同日历史且不重复统计`() {
        val state = SimAccountState(historyRecords = listOf(day("2026-11-05", profit = 10.0)),
            todayRecord = day("2026-11-05", profit = 20.0))
        val records = ReturnCalendar.records(state)
        assertEquals(1, records.size)
        assertEquals(20.0, ReturnCalendar.month(YearMonth.of(2026, 11), records).profit, 0.0)
    }

    @Test fun `持仓估算不计入月盈亏但在日历保留`() {
        val state = SimAccountState(historyRecords = listOf(day("2026-11-02")), todayRecord = day("2026-11-05", profit = 200.0, status = "HOLDING"))
        val month = ReturnCalendar.month(YearMonth.of(2026, 11), ReturnCalendar.records(state))
        assertEquals(100.0, month.profit, 0.0)
        assertEquals(1, month.tradeDays)
        assertEquals(1, month.holdingDays)
        assertEquals(2, month.records.size)
    }

    @Test fun `未交易与有交易零收益以及缺少记录分别保留`() {
        val state = SimAccountState(historyRecords = listOf(day("2026-11-02", profit = 0.0, trade = false), day("2026-11-03", profit = 0.0)))
        val month = ReturnCalendar.month(YearMonth.of(2026, 11), ReturnCalendar.records(state))
        assertEquals(2, month.records.size)
        assertEquals(1, month.tradeDays)
        assertEquals(0, month.winningDays + month.losingDays)
        assertNull(month.records[LocalDate.of(2026, 11, 4)])
    }

    @Test fun `不同月份和来源不会混入月汇总`() {
        val account = SimAccountState(historyRecords = listOf(day("2026-10-30", profit = 500.0), day("2026-11-02", profit = -50.0)))
        val report = SimAccountState(historyRecords = listOf(day("2026-11-02", profit = 200.0)))
        val month = YearMonth.of(2026, 11)
        assertEquals(-50.0, ReturnCalendar.month(month, ReturnCalendar.records(account)).profit, 0.0)
        assertEquals(200.0, ReturnCalendar.month(month, ReturnCalendar.records(report)).profit, 0.0)
        assertEquals(1, ReturnCalendar.month(month, ReturnCalendar.records(account)).losingDays)
    }

    @Test fun `异常日期不生成收益单元格且不改变原账本`() {
        val invalid = day("invalid", profit = 999.0)
        val original = SimAccountState(historyRecords = listOf(invalid))
        assertTrue(ReturnCalendar.records(original).isEmpty())
        assertEquals(listOf(invalid), original.historyRecords)
    }
}
