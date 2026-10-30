package com.example.momentum.engine

import com.example.momentum.model.DailyBar
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class WanlongSignalEngineTest {
    private val bars: List<DailyBar> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/sh603131_202610.csv"))
        stream.bufferedReader().useLines { lines ->
            lines.drop(1).map { line ->
                val p = line.split(",")
                DailyBar(p[0], p[1].toDouble(), p[3].toDouble(), p[4].toDouble(), p[2].toDouble(), p[5].toDouble())
            }.toList()
        }
    }

    private fun at(value: String): ZonedDateTime = ZonedDateTime.parse(value + "+08:00")

    @Test
    fun `上海沪工28日成立29日不成立`() {
        val now = at("2026-10-30T17:00:00")
        val day28 = checkNotNull(WanlongSignalEngine.evaluate(bars, "2026-10-28", now))
        val day29 = checkNotNull(WanlongSignalEngine.evaluate(bars, "2026-10-29", now))
        assertTrue(day28.result.signal)
        assertEquals("2026-10-28", day28.date)
        assertEquals(14.5, day28.close, 1e-9)
        assertFalse(day29.result.sigWanshou)
        assertFalse(day29.result.sigXlj4)
        assertEquals(15.95, day29.close, 1e-9)
    }

    @Test
    fun `没有新K线不能将昨日信号登记为次日信号`() {
        val oldBars = bars.filter { it.date <= "2026-10-28" }
        val now = at("2026-10-29T17:00:00")
        val hits = listOf("2026-10-28", "2026-10-29").mapNotNull { date ->
            WanlongSignalEngine.evaluate(oldBars, date, now)?.takeIf { it.result.signal }
        }
        assertEquals(listOf("2026-10-28"), hits.map { it.date })
    }

    @Test
    fun `次日早晨可以确认昨日但不能确认今日`() {
        val now = at("2026-10-29T07:41:58")
        assertTrue(checkNotNull(WanlongSignalEngine.evaluate(bars, "2026-10-28", now)).result.signal)
        assertNull(WanlongSignalEngine.evaluate(bars, "2026-10-29", now))
    }

    @Test
    fun `盘中和收盘更新缓冲期的信号不得入账`() {
        for (time in listOf("09:25:00", "14:59:59", "15:00:00", "15:04:59")) {
            assertNull(WanlongSignalEngine.evaluate(bars, "2026-10-28", at("2026-10-28T$time")))
        }
        assertTrue(checkNotNull(WanlongSignalEngine.evaluate(bars, "2026-10-28", at("2026-10-28T15:05:00"))).result.signal)
    }

    @Test
    fun `收盘时间使用北京时间而非设备时区`() {
        assertNull(WanlongSignalEngine.evaluate(bars, "2026-10-28", ZonedDateTime.parse("2026-10-28T07:04:59Z")))
        assertNotNull(WanlongSignalEngine.evaluate(bars, "2026-10-28", ZonedDateTime.parse("2026-10-28T07:05:00Z")))
    }

    @Test
    fun `历史不足不得形成确认信号`() {
        assertNull(WanlongSignalEngine.evaluate(bars.takeLast(20), "2026-10-28", at("2026-10-30T17:00:00")))
    }

    @Test
    fun `早盘抓取过的数据收盘后仍需刷新`() {
        assertTrue(WanlongSignalEngine.isCacheFresh("2026-10-28", 400, null, at("2026-10-28T10:00:00"), 400))
        assertFalse(WanlongSignalEngine.isCacheFresh("2026-10-28", 400, null, at("2026-10-28T15:05:00"), 400))
        assertFalse(WanlongSignalEngine.isCacheFresh("2026-10-28", 400, "2026-10-27", at("2026-10-28T17:00:00"), 400))
        assertTrue(WanlongSignalEngine.isCacheFresh("2026-10-28", 400, "2026-10-28", at("2026-10-28T17:00:00"), 400))
    }

    @Test
    fun `昨日或不足长度的缓存不能跳过更新`() {
        val now = at("2026-10-29T17:00:00")
        assertFalse(WanlongSignalEngine.isCacheFresh("2026-10-28", 400, "2026-10-28", now, 400))
        assertFalse(WanlongSignalEngine.isCacheFresh("2026-10-29", 299, "2026-10-29", now, 400))
        assertFalse(WanlongSignalEngine.isCacheFresh(null, 0, null, now, 400))
    }
}
