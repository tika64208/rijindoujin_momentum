package com.example.momentum.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

internal val darkTradeZone: ZoneId = ZoneId.of("Asia/Shanghai")

internal fun darkTradeDate(date: String): String =
    LocalDate.parse(date.replace("-", ""), DateTimeFormatter.BASIC_ISO_DATE)
        .format(DateTimeFormatter.BASIC_ISO_DATE)

data class DarkTradeDay(
    val date: String,
    val records: List<DarkTradeDataSource.DarkTradeRecord>,
    val complete: Boolean,
    val totalCount: Int,
    val fetchedAt: Long
)

interface DarkTradeHistoryStore {
    suspend fun readDay(date: String): DarkTradeDay?
    suspend fun writeDay(day: DarkTradeDay)

    suspend fun readStockDays(code: String, dates: List<String>): Map<String, DarkTradeDay> =
        dates.mapNotNull { date ->
            readDay(date)?.let { date to it.copy(records = it.records.filter { r -> r.code == code }) }
        }.toMap()
}

data class DarkTradeHistory(
    val records: List<DarkTradeDataSource.DarkTradeRecord> = emptyList(),
    val pendingDates: List<String> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val totalDays: Int = 10
)

/** 列表与详情共用的按日缓存；同日并发请求复用结果，不同日最多两路请求。 */
class DarkTradeHistoryRepository(
    private val store: DarkTradeHistoryStore,
    private val download: suspend (String) -> DarkTradeDownload,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(darkTradeZone) }
) {
    private data class Attempt(val version: Long, val error: Throwable?)
    private val dateLocks = ConcurrentHashMap<String, Mutex>()
    private val attempts = ConcurrentHashMap<String, Attempt>()
    private val downloads = Semaphore(2)

    suspend fun readCachedDay(date: String): DarkTradeDay? = store.readDay(darkTradeDate(date))

    private fun isFresh(day: DarkTradeDay, now: ZonedDateTime): Boolean {
        if (!day.complete) return false
        val date = LocalDate.parse(day.date, DateTimeFormatter.BASIC_ISO_DATE)
        val marketNow = now.withZoneSameInstant(darkTradeZone)
        val fetched = java.time.Instant.ofEpochMilli(day.fetchedAt).atZone(darkTradeZone)
        if (date < marketNow.toLocalDate() && fetched.toLocalDate() > date) return true
        val age = marketNow.toInstant().toEpochMilli() - day.fetchedAt
        return age in 0 until 10 * 60 * 1000L
    }

    suspend fun loadDay(date: String, forceRefresh: Boolean = false): Result<DarkTradeDay> {
        val key = darkTradeDate(date)
        val version = attempts[key]?.version ?: 0L
        val cached = store.readDay(key)
        if (!forceRefresh && cached != null && isFresh(cached, clock())) return Result.success(cached)

        return dateLocks.getOrPut(key) { Mutex() }.withLock {
            // 等待者直接使用刚完成的请求结果，包括失败，避免排队后再次轰炸接口。
            attempts[key]?.takeIf { it.version != version }?.let { attempt ->
                attempt.error?.let { return@withLock Result.failure(it) }
                return@withLock Result.success(checkNotNull(store.readDay(key)))
            }
            val current = store.readDay(key)
            if (!forceRefresh && current != null && isFresh(current, clock())) {
                return@withLock Result.success(current)
            }
            val result = try {
                val remote = downloads.withPermit { download(key) }
                val day = DarkTradeDay(key, remote.records, remote.complete, remote.totalCount,
                    clock().toInstant().toEpochMilli())
                if (remote.complete) {
                    // 完整空结果也保存，后续才能知道“确认无数据”，并清除旧日快照残留。
                    store.writeDay(day)
                    Result.success(day)
                } else {
                    // 保留完整快照；部分缓存可用本次更完整的结果替换，避免始终卡在首批50条。
                    if (remote.records.isNotEmpty() && (current == null ||
                        (!current.complete && remote.records.size > current.records.size))) {
                        store.writeDay(day)
                    }
                    Result.failure(IllegalStateException(remote.error ?: "暗盘数据下载不完整"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            // 仅保留请求版本与错误，不在内存中长期积累多日全市场快照。
            attempts[key] = Attempt(version + 1, result.exceptionOrNull())
            result
        }
    }

    private fun dates(days: Int): List<String> {
        require(days in 1..60)
        var date = clock().withZoneSameInstant(darkTradeZone).toLocalDate()
        val result = ArrayList<String>()
        while (result.size < days) {
            if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
                result.add(date.format(DateTimeFormatter.BASIC_ISO_DATE))
            }
            date = date.minusDays(1)
        }
        return result
    }

    /** 只读本地；未知、未下载、失败的日期绝不伪造为零流入。 */
    suspend fun readCachedHistory(code: String, days: Int = 10): DarkTradeHistory {
        val dates = dates(days)
        val cached = store.readStockDays(code, dates)
        val now = clock()
        val records = ArrayList<DarkTradeDataSource.DarkTradeRecord>()
        val pending = ArrayList<String>()
        for (date in dates) {
            val day = cached[date]
            if (day == null || !isFresh(day, now)) pending.add(date)
            if (day == null) continue
            val isoDate = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE).toString()
            if (day.records.isNotEmpty()) {
                records.addAll(day.records.map { it.copy(date = isoDate) })
            } else if (day.complete) {
                // 仅完整全市场快照确认该股无记录时才允许补零。
                records.add(DarkTradeDataSource.DarkTradeRecord(
                    isoDate, "", code, "", "", "", 0.0, 0.0, 0.0, 0.0, 0.0
                ))
            }
        }
        return DarkTradeHistory(records.sortedBy { it.date }, pending, totalDays = days)
    }

    /** 用户显式补齐时逐日更新；每完成一天即回传，失败保留曲线并显示具体日期。 */
    suspend fun fillHistory(
        code: String,
        days: Int = 10,
        onUpdate: suspend (DarkTradeHistory) -> Unit = {}
    ): DarkTradeHistory {
        val errors = linkedMapOf<String, String>()
        val pending = readCachedHistory(code, days).pendingDates
        for (date in pending) {
            loadDay(date).exceptionOrNull()?.let { errors[date] = it.message ?: "获取失败" }
            onUpdate(readCachedHistory(code, days).copy(errors = errors.toMap()))
        }
        return readCachedHistory(code, days).copy(errors = errors.toMap())
    }
}
