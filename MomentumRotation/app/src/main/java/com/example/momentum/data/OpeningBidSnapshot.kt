package com.example.momentum.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** 以请求/接收的真实时间定义采集窗口，禁止盘中补采冒充早盘。 */
object OpeningBidTime {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    val target: LocalTime = LocalTime.of(9, 30)

    fun inWindow(epochMillis: Long): Boolean {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return time.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) &&
            !time.toLocalTime().isBefore(target) && time.toLocalTime().isBefore(target.plusMinutes(1))
    }

    fun date(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    fun nextTarget(epochMillis: Long): Long {
        val now = Instant.ofEpochMilli(epochMillis).atZone(zone)
        var next = now.toLocalDate().atTime(target).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        while (next.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }
}

data class OpeningBidResponse(
    val rawJson: String,
    val total: Int,
    val codes: List<String>,
    val quoteTimes: List<Long>
) {
    fun validate(startedAt: Long, receivedAt: Long) {
        require(OpeningBidTime.inWindow(startedAt) && OpeningBidTime.inWindow(receivedAt) &&
            receivedAt >= startedAt && OpeningBidTime.date(startedAt) == OpeningBidTime.date(receivedAt)) {
            "已错过 09:30 采集窗口，不补造早盘快照"
        }
        require(total > 0 && codes.size == total &&
            codes.all { code -> code.length == 6 && code.all { it in '0'..'9' } } && codes.toSet().size == total) {
            "全市场数据不完整：${codes.toSet().size}/$total"
        }
        val date = OpeningBidTime.date(startedAt)
        val earliestQuote = Instant.ofEpochMilli(startedAt).atZone(OpeningBidTime.zone)
            .toLocalDate().atTime(9, 25).atZone(OpeningBidTime.zone).toInstant().toEpochMilli()
        // 停牌股票允许带旧报价；过半股票须有当日竞价结束后的行情。
        require(quoteTimes.size == total && quoteTimes.count {
            it >= earliestQuote && it <= receivedAt + 5_000 && OpeningBidTime.date(it) == date
        } > total / 2) {
            "接口未返回足够的当日行情，可能休市或数据尚未更新"
        }
    }
}

data class OpeningBidArchive(
    val date: String,
    val startedAt: Long,
    val receivedAt: Long,
    val rowCount: Int,
    val rawJson: String,
    val csv: String,
    val schemaVersion: Int = 1
)

interface OpeningBidStore {
    suspend fun read(date: String): OpeningBidArchive?
    /** 原子插入且不覆盖已有日期，返回最终保存的档案。 */
    suspend fun insertOnce(archive: OpeningBidArchive): OpeningBidArchive
}

/** 与盘中 bid_data 完全分离；同进程所有入口共用互斥锁，数据库再做唯一约束。 */
class OpeningBidCapture(
    private val store: OpeningBidStore,
    private val fetch: suspend () -> OpeningBidResponse,
    private val makeCsv: (OpeningBidResponse, Long, Long) -> String,
    private val export: suspend (OpeningBidArchive) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun capture(): OpeningBidArchive = mutex.withLock {
        val date = OpeningBidTime.date(clock())
        val existing = store.read(date)
        if (existing != null) {
            export(existing) // 文件失败可从冻结库恢复，不再请求行情。
            return@withLock existing
        }
        val startedAt = clock()
        require(OpeningBidTime.date(startedAt) == date && OpeningBidTime.inWindow(startedAt)) {
            "未处于 09:30–09:31 采集窗口，不能补采"
        }
        val response = fetch()
        val receivedAt = clock()
        response.validate(startedAt, receivedAt)
        val archive = store.insertOnce(OpeningBidArchive(
            date, startedAt, receivedAt, response.total, response.rawJson,
            makeCsv(response, startedAt, receivedAt)
        ))
        export(archive)
        archive
    }

    companion object {
        private val mutex = Mutex()
    }
}

/** SQLite 分块不得截断 UTF-16 代理对，否则原始 JSON 中的字符会被改变。 */
internal fun openingBidPayloadChunks(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = (start + 32_000).coerceAtMost(text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        add(text.substring(start, end))
        start = end
    }
}
