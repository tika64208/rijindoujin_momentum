package com.example.momentum.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竞价快照的日期归属：零点和休市日不能把上一交易日的行情记成今天。
 */
class BidQuoteClockTest {

    private val zone: ZoneId = OpeningBidTime.zone // Asia/Shanghai

    private fun ts(date: String, time: String): Long =
        LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun now(date: String, time: String) =
        LocalDateTime.parse("${date}T$time").atZone(zone)

    /** 一批全是上一交易日 15:34 的报价。 */
    private fun yesterdayQuotes(date: String = "2026-11-05") =
        List(100) { ts(date, "15:34:00") }

    @Test
    fun `零点后抓到的昨日行情不写入今日`() {
        val reason = BidQuoteClock.rejectReason(
            yesterdayQuotes(),
            now("2026-11-06", "00:01:12")
        )
        assertTrue(reason != null)
        assertTrue(reason!!.contains("2026-11-05"))
        assertTrue(reason.contains("2026-11-06"))
    }

    @Test
    fun `周末上午的行情仍是周五的，不写入`() {
        val reason = BidQuoteClock.rejectReason(
            yesterdayQuotes("2026-11-06"),
            now("2026-11-07", "09:20:00") // 周六
        )
        assertTrue(reason != null)
    }

    @Test
    fun `交易日集合竞价窗口内正常写入`() {
        val quotes = List(100) { ts("2026-11-06", "09:20:00") }
        assertNull(BidQuoteClock.rejectReason(quotes, now("2026-11-06", "09:20:05")))
    }

    @Test
    fun `集合竞价窗口内容忍少量滞后报价`() {
        // 99% 还是昨日，仅 1% 已更新为今日 —— 属于推送延迟，不应拦截
        val quotes = yesterdayQuotes("2026-11-05").toMutableList()
        quotes[0] = ts("2026-11-06", "09:19:59")
        assertNull(BidQuoteClock.rejectReason(quotes, now("2026-11-06", "09:20:00")))
    }

    @Test
    fun `开盘后数据源仍是昨日行情则拒绝`() {
        val reason = BidQuoteClock.rejectReason(
            yesterdayQuotes("2026-11-05"),
            now("2026-11-06", "10:00:00")
        )
        assertTrue(reason != null)
    }

    @Test
    fun `没有任何报价时间时不拦截`() {
        assertNull(BidQuoteClock.rejectReason(List(50) { 0L }, now("2026-11-06", "00:05:00")))
    }

    @Test
    fun `收盘后当日行情正常写入`() {
        val quotes = List(100) { ts("2026-11-05", "15:34:00") }
        assertNull(BidQuoteClock.rejectReason(quotes, now("2026-11-05", "20:00:00")))
    }

    @Test
    fun `多数决取出现次数最多的日期`() {
        val quotes = List(9) { ts("2026-11-05", "15:00:00") } + List(1) { ts("2026-11-06", "09:20:00") }
        assertEquals(LocalDate.parse("2026-11-05"), BidQuoteClock.dominantDate(quotes))
    }
}
