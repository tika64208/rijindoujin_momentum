package com.example.momentum.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class OpeningBidSnapshotTest {
    private fun time(value: String) = ZonedDateTime.parse("${value}+08:00[Asia/Shanghai]").toInstant().toEpochMilli()
    private val start = time("2026-11-05T09:30:00")
    private fun response(total: Int = 2, codes: List<String> = listOf("000001", "600001"),
                         quotes: List<Long> = listOf(start, start)) = OpeningBidResponse("raw", total, codes, quotes)

    private class Store : OpeningBidStore {
        var archive: OpeningBidArchive? = null
        var writes = 0
        override suspend fun read(date: String) = archive?.takeIf { it.date == date }
        override suspend fun insertOnce(archive: OpeningBidArchive): OpeningBidArchive {
            writes++
            if (this.archive == null) this.archive = archive
            return this.archive!!
        }
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("应拒绝归档") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `北京时间窗口包含930排除931和周末`() {
        assertTrue(OpeningBidTime.inWindow(start))
        assertTrue(OpeningBidTime.inWindow(start + 59_999))
        assertFalse(OpeningBidTime.inWindow(start - 1))
        assertFalse(OpeningBidTime.inWindow(start + 60_000))
        assertFalse(OpeningBidTime.inWindow(time("2026-11-07T09:30:00")))
    }

    @Test fun `下次调度跳过周末并按北京时间计算`() {
        assertEquals(time("2026-11-09T09:30:00"), OpeningBidTime.nextTarget(time("2026-11-06T09:30:00")))
        assertEquals(start, OpeningBidTime.nextTarget(start - 1))
        assertEquals(time("2026-11-06T09:30:00"), OpeningBidTime.nextTarget(start))
    }

    @Test fun `完整当日数据通过校验`() { response().validate(start, start + 1000) }
    @Test fun `大体积原始响应分块存取不会改变中文与代理对`() {
        val original = "中".repeat(31_999) + "\uD83D\uDE80" + "全市场".repeat(1_000_000)
        val encodedChunks = openingBidPayloadChunks(original).map { it.toByteArray(Charsets.UTF_8) }
        assertTrue(encodedChunks.all { it.size < 100_000 })
        assertEquals(original, encodedChunks.joinToString("") { it.toString(Charsets.UTF_8) })
    }
    @Test fun `少页不归档`() { rejects { response(total = 3).validate(start, start + 1000) } }
    @Test fun `重复代码不归档`() { rejects { response(codes = listOf("000001", "000001")).validate(start, start + 1000) } }
    @Test fun `空代码不归档`() { rejects { response(codes = listOf("", "000001")).validate(start, start + 1000) } }
    @Test fun `非法代码不归档`() { rejects { response(codes = listOf("-", "000001")).validate(start, start + 1000) } }
    @Test fun `空行情不归档`() { rejects { response(0, emptyList(), emptyList()).validate(start, start) } }
    @Test fun `休市日返回昨日行情不归档`() {
        rejects { response(quotes = listOf(start - 86_400_000, start - 86_400_000)).validate(start, start + 1000) }
    }
    @Test fun `部分停牌旧报价可保留而不是删除股票`() {
        response(3, listOf("000001", "600001", "600002"), listOf(start, start, start - 86_400_000)).validate(start, start + 1000)
    }
    @Test fun `窗口前开始请求不能冒充930`() { rejects { response().validate(start - 1, start + 1000) } }
    @Test fun `响应超过一分钟不归档`() { rejects { response().validate(start, start + 60_000) } }
    @Test fun `未来行情时间不归档`() {
        rejects { response(quotes = listOf(start + 60_000, start + 60_000)).validate(start, start + 1000) }
    }
    @Test fun `当日过早缓存行情不归档`() {
        rejects { response(quotes = listOf(start - 600_000, start - 600_000)).validate(start, start + 1000) }
    }

    @Test fun `并发入口每天只请求保存一次`() = runBlocking {
        val store = Store()
        var fetches = 0
        val capture = OpeningBidCapture(store, { fetches++; response() }, { _, _, _ -> "csv" }, {}, { start })
        val archives = (1..5).map { async { capture.capture() } }.awaitAll()
        assertEquals(1, fetches)
        assertEquals(1, store.writes)
        assertEquals(1, archives.distinct().size)
    }

    @Test fun `文件失败仍保留冻结档案窗口外仅恢复文件`() = runBlocking {
        val store = Store()
        var now = start
        var fetches = 0
        var failExport = true
        val capture = OpeningBidCapture(store, { fetches++; response() }, { _, _, _ -> "original csv" }, {
            if (failExport) error("磁盘写入失败")
        }, { now })
        try { capture.capture(); fail("应导出失败") } catch (_: IllegalStateException) { }
        assertNotNull(store.archive)
        now += 3_600_000
        failExport = false
        assertEquals("original csv", capture.capture().csv)
        assertEquals(1, fetches)
        assertEquals(1, store.writes)
    }

    @Test fun `窗口外不会发网络请求或生成记录`() = runBlocking {
        val store = Store()
        val capture = OpeningBidCapture(store, { error("不应请求") }, { _, _, _ -> "csv" }, {}, { start + 60_000 })
        try { capture.capture(); fail("应拒绝") } catch (_: IllegalArgumentException) { }
        assertEquals(0, store.writes)
    }

    @Test fun `响应不完整可重试但不能提前固化`() = runBlocking {
        val store = Store()
        var calls = 0
        val capture = OpeningBidCapture(store, { if (++calls == 1) response(total = 3) else response() },
            { _, _, _ -> "csv" }, {}, { start })
        try { capture.capture(); fail("应拒绝") } catch (_: IllegalArgumentException) { }
        assertNull(store.archive)
        assertEquals(2, capture.capture().rowCount)
        assertEquals(1, store.writes)
    }

    @Test fun `请求失败不写空快照`() = runBlocking {
        val store = Store()
        val capture = OpeningBidCapture(store, { error("network") }, { _, _, _ -> "csv" }, {}, { start })
        try { capture.capture(); fail("应失败") } catch (_: IllegalStateException) { }
        assertNull(store.archive)
    }
}
