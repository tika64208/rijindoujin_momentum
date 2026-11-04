package com.example.momentum.data

import com.example.momentum.data.db.DarkTradeCacheEntity
import com.example.momentum.data.db.DarkTradeDao
import com.example.momentum.data.db.toEntity
import com.example.momentum.data.db.toRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class RoomDarkTradeHistoryStore(private val dao: DarkTradeDao) : DarkTradeHistoryStore {
    private fun aliases(date: String): List<String> = listOf(
        darkTradeDate(date), LocalDate.parse(darkTradeDate(date), DateTimeFormatter.BASIC_ISO_DATE).toString()
    )

    override suspend fun readDay(date: String): DarkTradeDay? = withContext(Dispatchers.IO) {
        val key = darkTradeDate(date)
        val aliases = aliases(key)
        val snapshot = dao.readSnapshot(aliases)
        val state = snapshot.states.firstOrNull()
        val records = snapshot.records.map { it.toRecord() }.distinctBy { it.code }
        if (state == null && records.isEmpty()) return@withContext null
        DarkTradeDay(key, records, state?.complete ?: false, state?.totalCount ?: -1, state?.fetchedAt ?: 0)
    }

    override suspend fun writeDay(day: DarkTradeDay) = withContext(Dispatchers.IO) {
        val key = darkTradeDate(day.date)
        val savedAt = Instant.ofEpochMilli(day.fetchedAt).atZone(darkTradeZone)
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        dao.replaceDay(
            DarkTradeCacheEntity(key, day.complete, day.totalCount, day.fetchedAt),
            day.records.map { it.toEntity(key, savedAt) }, aliases(key)
        )
    }

    override suspend fun readStockDays(code: String, dates: List<String>): Map<String, DarkTradeDay> =
        withContext(Dispatchers.IO) {
            val allAliases = dates.flatMap { aliases(it) }
            val snapshot = dao.readSnapshot(allAliases, code)
            val states = snapshot.states.associateBy { darkTradeDate(it.date) }
            val existing = snapshot.dates.map { darkTradeDate(it) }.toSet()
            // 只查询目标股票，不为十日曲线加载十份全市场记录。
            val records = snapshot.records.groupBy { darkTradeDate(it.date) }
            dates.mapNotNull { date ->
                val state = states[date]
                if (state == null && date !in existing) null else date to DarkTradeDay(
                    date, records[date].orEmpty().map { it.toRecord() }.distinctBy { it.code },
                    state?.complete ?: false, state?.totalCount ?: -1, state?.fetchedAt ?: 0
                )
            }.toMap()
        }
}
