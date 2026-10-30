package com.example.momentum.engine

import com.example.momentum.model.DailyBar
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 日线信号只归属实际 K 线日期，收盘数据更新后才允许确认当日信号。 */
object WanlongSignalEngine {
    val MARKET_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val CONFIRM_TIME: LocalTime = LocalTime.of(15, 5)

    fun now(): ZonedDateTime = ZonedDateTime.now(MARKET_ZONE)

    fun lastConfirmedDate(now: ZonedDateTime): LocalDate {
        val marketNow = now.withZoneSameInstant(MARKET_ZONE)
        return if (marketNow.toLocalTime() < CONFIRM_TIME) {
            marketNow.toLocalDate().minusDays(1)
        } else {
            marketNow.toLocalDate()
        }
    }

    /** 老版本的“当天抓取过”不能证明已取得收盘数据，必须有收盘后抓取标记。 */
    fun isCacheFresh(
        lastUpdateDate: String?,
        barCount: Int,
        confirmedFetchDate: String?,
        now: ZonedDateTime,
        requiredBars: Int
    ): Boolean {
        val today = now.withZoneSameInstant(MARKET_ZONE).toLocalDate()
        return lastUpdateDate == today.toString() && barCount >= requiredBars &&
            (lastConfirmedDate(now) < today || confirmedFetchDate == today.toString())
    }

    data class Evaluation(
        val date: String,
        val close: Double,
        val chgPct: Double,
        val result: MyttEngine.MyttResult
    )

    /** null 表示日期未收盘、缺少当天 K 线或历史不足，不能沿用上一根的信号。 */
    fun evaluate(
        bars: List<DailyBar>,
        date: String,
        now: ZonedDateTime
    ): Evaluation? {
        val targetDate = LocalDate.parse(date)
        if (targetDate > lastConfirmedDate(now)) return null
        val history = bars.filter { it.date <= date }.sortedBy { it.date }
        if (history.size < 300 || history.last().date != date) return null
        val result = MyttEngine.computeRecent(
            history.map { it.close }.toDoubleArray(),
            history.map { it.open }.toDoubleArray(),
            history.map { it.high }.toDoubleArray(),
            history.map { it.low }.toDoubleArray(),
            history.map { it.volume }.toDoubleArray(),
            lookbackDays = 1
        ).lastOrNull() ?: return null
        val close = history.last().close
        val prevClose = history[history.lastIndex - 1].close
        return Evaluation(date, close, if (prevClose > 0) (close / prevClose - 1) * 100 else 0.0, result)
    }
}
