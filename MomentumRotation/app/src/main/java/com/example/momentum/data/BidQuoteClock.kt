package com.example.momentum.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 「这批行情属于哪个交易日」的判定。
 *
 * 东财 push2 的竞价接口是"当前快照"接口，没有日期参数：零点之后、周末与法定休市日，它仍然返回
 * 上一交易日的收盘快照。若按系统时钟的日期入库，就会把昨天的行情记成今天的数据（例如 00:01 抓到
 * 的是昨日 15:34 的报价，却写成 date=今天）。所以日期一律取自报价时间 f124，而不是本机时间。
 */
object BidQuoteClock {

    /** 早盘集合竞价的"宽限"窗口：这段时间允许行情推送比系统时间慢一步。 */
    private val GRACE_START: LocalTime = LocalTime.of(9, 10)
    private val GRACE_END: LocalTime = LocalTime.of(9, 40)

    /**
     * 报价日期多数决：忽略没有报价时间(<=0)的行，取出现次数最多的日期。
     * 返回 null 表示接口没有任何有效报价时间，无法判定（此时不做拦截）。
     */
    fun dominantDate(quoteTimes: Iterable<Long>, zone: java.time.ZoneId = OpeningBidTime.zone): LocalDate? =
        quoteTimes.asSequence()
            .filter { it > 0 }
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key

    /**
     * 是否处于集合竞价宽限期：工作日 09:10–09:40，且至少 1% 的报价已经是当日。
     *
     * 这样即使接口在 09:15–09:30 之间推送滞后（多数股票 f124 仍是昨日），核心的早盘窗口也不会被拦；
     * 而休市日没有任何当日报价，1% 的条件不成立，仍会被判为"上一交易日的行情"。
     */
    fun inAuctionGrace(
        quoteTimes: Iterable<Long>,
        now: ZonedDateTime,
        zone: java.time.ZoneId = OpeningBidTime.zone
    ): Boolean {
        if (now.dayOfWeek.value > 5) return false
        val time = now.toLocalTime()
        if (time.isBefore(GRACE_START) || !time.isBefore(GRACE_END)) return false
        val quotes = quoteTimes.filter { it > 0 }
        if (quotes.isEmpty()) return false
        val today = now.toLocalDate()
        val todayCount = quotes.count { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today }
        return todayCount * 100 >= quotes.size
    }

    /**
     * 本次快照是否可以写入 [today]。
     * @return null 表示可以写；非 null 是拒绝原因（用于界面提示）
     */
    fun rejectReason(
        quoteTimes: Iterable<Long>,
        now: ZonedDateTime,
        zone: java.time.ZoneId = OpeningBidTime.zone
    ): String? {
        val quoteDate = dominantDate(quoteTimes, zone) ?: return null
        val today = now.toLocalDate()
        if (quoteDate == today) return null
        if (inAuctionGrace(quoteTimes, now, zone)) return null
        return "接口返回的是 $quoteDate 的行情（今日未开盘或休市），已跳过写入，未把上一交易日的快照记到 $today"
    }
}
