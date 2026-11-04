package com.example.momentum.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

class DarkTradeHistoryRepositoryTest {
    private class Store : DarkTradeHistoryStore {
        val days = ConcurrentHashMap<String, DarkTradeDay>()
        override suspend fun readDay(date: String) = days[date]
        override suspend fun writeDay(day: DarkTradeDay) { days[day.date] = day }
    }
    private val now = ZonedDateTime.parse("2026-11-03T18:00:00+08:00")
    private fun record(code: String = "603131") = DarkTradeDataSource.DarkTradeRecord(
        "2026-11-03", "", code, code, "", "", 100.0, 20.0, 120.0, 1.0, 0.1
    )
    private fun day(date: String, complete: Boolean = true, records: List<DarkTradeDataSource.DarkTradeRecord> = listOf(record()),
                    fetchedAt: Long = now.toInstant().toEpochMilli()) =
        DarkTradeDay(date, records, complete, if (complete) records.size else -1, fetchedAt)
    private fun success() = DarkTradeDownload(listOf(record()), 1, true)

    @Test
    fun `旧版缓存打开详情不发任何网络请求`() = runBlocking {
        val store = Store()
        store.days["20261103"] = day("20261103", complete = false, fetchedAt = 0)
        var calls = 0
        val repo = DarkTradeHistoryRepository(store, { calls++; success() }, { now })
        val history = repo.readCachedHistory("603131")
        assertEquals(0, calls)
        assertEquals(1, history.records.size)
        assertEquals(10, history.pendingDates.size)
        assertEquals("2026-11-03", history.records.single().date)
    }

    @Test
    fun `完整历史跨仓库重建仍直接复用`() = runBlocking {
        val store = Store()
        var calls = 0
        DarkTradeHistoryRepository(store, { calls++; success() }, { now }).loadDay("2026-10-30").getOrThrow()
        val afterRestart = DarkTradeHistoryRepository(store, { calls++; success() }, { now.plusDays(3) })
        afterRestart.loadDay("20261030").getOrThrow()
        assertEquals(1, calls)
    }

    @Test
    fun `同日期含强制刷新并发仅下载一次`() = runBlocking {
        val store = Store()
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val repo = DarkTradeHistoryRepository(store, { calls++; gate.await(); success() }, { now })
        val a = async(start = CoroutineStart.UNDISPATCHED) { repo.loadDay("20261103", true) }
        val b = async(start = CoroutineStart.UNDISPATCHED) { repo.loadDay("2026-11-03", true) }
        assertEquals(1, calls)
        gate.complete(Unit)
        assertTrue(a.await().isSuccess)
        assertTrue(b.await().isSuccess)
        assertEquals(1, calls)
    }

    @Test
    fun `同日失败结果也被等待者复用避免连环重试`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repo = DarkTradeHistoryRepository(Store(), {
            calls++; gate.await(); DarkTradeDownload(emptyList(), -1, false, "限流")
        }, { now })
        val a = async(start = CoroutineStart.UNDISPATCHED) { repo.loadDay("20261103") }
        val b = async(start = CoroutineStart.UNDISPATCHED) { repo.loadDay("20261103") }
        gate.complete(Unit)
        assertTrue(a.await().isFailure)
        assertTrue(b.await().isFailure)
        assertEquals(1, calls)
    }

    @Test
    fun `不同日期最多两路下载`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val repo = DarkTradeHistoryRepository(Store(), {
            active++; peak = maxOf(peak, active)
            gate.await()
            active--
            success()
        }, { now })
        val jobs = listOf("20261103", "20261102", "20261030").map { date ->
            async(start = CoroutineStart.UNDISPATCHED) { repo.loadDay(date) }
        }
        assertEquals(2, active)
        gate.complete(Unit)
        jobs.forEach { it.await().getOrThrow() }
        assertEquals(2, peak)
    }

    @Test
    fun `已确认空日期不反复请求`() = runBlocking {
        val store = Store()
        var calls = 0
        val repo = DarkTradeHistoryRepository(store, { calls++; DarkTradeDownload(emptyList(), 0, true) }, { now })
        repo.loadDay("20261103").getOrThrow()
        repo.loadDay("20261103").getOrThrow()
        assertEquals(1, calls)
        assertTrue(store.days["20261103"]!!.complete)
        assertEquals(0.0, repo.readCachedHistory("603131", 1).records.single().netAmount, 0.0)
    }

    @Test
    fun `未知或失败日期不能补成零流入`() = runBlocking {
        val store = Store()
        store.days["20261103"] = day("20261103", complete = false, records = listOf(record("000001")), fetchedAt = 0)
        val repo = DarkTradeHistoryRepository(store, { DarkTradeDownload(emptyList(), -1, false, "失败") }, { now })
        assertTrue(repo.readCachedHistory("603131", 1).records.isEmpty())
        val result = repo.fillHistory("603131", 1)
        assertTrue(result.records.isEmpty())
        assertEquals(setOf("20261103"), result.errors.keys)
    }

    @Test
    fun `失败刷新保留旧的完整快照`() = runBlocking {
        val store = Store()
        val old = day("20261103")
        store.days[old.date] = old
        val repo = DarkTradeHistoryRepository(store, {
            DarkTradeDownload(listOf(record("000001")), 10, false, "中途断网")
        }, { now })
        assertTrue(repo.loadDay(old.date, true).isFailure)
        assertEquals(old, store.days[old.date])
    }

    @Test
    fun `失败时较完整的部分结果能推进缓存而非卡在首批`() = runBlocking {
        val store = Store()
        store.days["20261103"] = day("20261103", complete = false, records = listOf(record("A")))
        val repo = DarkTradeHistoryRepository(store, {
            DarkTradeDownload(listOf(record("A"), record("B")), 3, false, "后续页失败")
        }, { now })
        assertTrue(repo.loadDay("20261103").isFailure)
        assertEquals(listOf("A", "B"), store.days["20261103"]!!.records.map { it.code })
        assertFalse(store.days["20261103"]!!.complete)
    }

    @Test
    fun `完整刷新替换旧日数据包括变为空结果`() = runBlocking {
        val store = Store()
        store.days["20261103"] = day("20261103")
        val repo = DarkTradeHistoryRepository(store, { DarkTradeDownload(emptyList(), 0, true) }, { now })
        assertTrue(repo.loadDay("20261103", true).isSuccess)
        assertTrue(store.days["20261103"]!!.records.isEmpty())
    }

    @Test
    fun `当日十分钟过期后刷新而历史无需周期重刷`() = runBlocking {
        var clock = now
        var calls = 0
        val repo = DarkTradeHistoryRepository(Store(), { calls++; success() }, { clock })
        repo.loadDay("20261103").getOrThrow()
        clock = now.plusMinutes(9)
        repo.loadDay("20261103").getOrThrow()
        assertEquals(1, calls)
        clock = now.plusMinutes(10)
        repo.loadDay("20261103").getOrThrow()
        assertEquals(2, calls)
        clock = now.plusDays(1)
        repo.loadDay("20261103").getOrThrow()
        assertEquals(3, calls)
        clock = now.plusDays(20)
        repo.loadDay("20261103").getOrThrow()
        assertEquals(3, calls)
    }

    @Test
    fun `补齐只请求缺失或未验证日期并逐日回传`() = runBlocking {
        val store = Store()
        store.days["20261030"] = day("20261030")
        store.days["20261102"] = day("20261102", complete = false, fetchedAt = 0)
        val calls = mutableListOf<String>()
        var updates = 0
        val repo = DarkTradeHistoryRepository(store, { date ->
            calls.add(date)
            if (date == "20261103") DarkTradeDownload(emptyList(), -1, false, "断网") else success()
        }, { now })
        val result = repo.fillHistory("603131", 3) { updates++ }
        assertEquals(listOf("20261103", "20261102"), calls)
        assertEquals(2, updates)
        assertEquals(listOf("20261103"), result.pendingDates)
        assertEquals(setOf("20261103"), result.errors.keys)
        assertEquals(listOf("2026-10-30", "2026-11-02"), result.records.map { it.date })
    }

    @Test
    fun `取消下载不写入快照且后续可重试`() = runBlocking {
        val store = Store()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repo = DarkTradeHistoryRepository(store, {
            calls++
            if (calls == 1) gate.await()
            success()
        }, { now })
        val job = async(start = CoroutineStart.UNDISPATCHED) { repo.loadDay("20261103") }
        job.cancelAndJoin()
        assertTrue(store.days.isEmpty())
        assertTrue(repo.loadDay("20261103").isSuccess)
        assertEquals(2, calls)
    }
}
