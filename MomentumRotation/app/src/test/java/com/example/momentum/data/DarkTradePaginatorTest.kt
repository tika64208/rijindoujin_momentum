package com.example.momentum.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DarkTradePaginatorTest {
    private fun record(code: String) = DarkTradeDataSource.DarkTradeRecord(
        "2026-11-02", "", code, code, "", "", 100.0, 20.0, 120.0, 1.0, 0.1
    )
    private fun page(n: Int, total: Int, vararg codes: String) =
        DarkTradeDataSource.DarkTradePage(codes.map { record(it) }, total, n)

    @Test
    fun `空页重试当前页而非跳页`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = DarkTradePaginator().download({ n ->
            calls.add(n)
            when {
                n == 1 -> page(n, 2, "A")
                calls.count { it == 2 } == 1 -> page(n, -1)
                else -> page(n, 2, "B")
            }
        })
        assertTrue(result.complete)
        assertEquals(listOf(1, 2, 2), calls)
        assertEquals(listOf("A", "B"), result.records.map { it.code })
    }

    @Test
    fun `连续失败保留部分结果但不能报告完整`() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = DarkTradePaginator().download({ n ->
            calls.add(n)
            if (n == 1) page(n, 2, "A") else page(n, 2)
        })
        assertFalse(result.complete)
        assertEquals(listOf(1, 2, 2, 2), calls)
        assertEquals(1, result.records.size)
        assertNotNull(result.error)
    }

    @Test
    fun `明确声明零条才是完整空结果`() = runBlocking {
        var calls = 0
        val empty = DarkTradePaginator().download({ n -> calls++; page(n, 0) })
        assertTrue(empty.complete)
        assertTrue(empty.records.isEmpty())
        assertEquals(1, calls)
        val invalid = DarkTradePaginator().download({ n -> page(n, -1) })
        assertFalse(invalid.complete)
    }

    @Test
    fun `重复页面不能冒充下载齐全`() = runBlocking {
        val result = DarkTradePaginator().download({ n -> page(n, 2, "A") })
        assertFalse(result.complete)
        assertEquals(1, result.records.size)
    }

    @Test
    fun `部分跨页重叠去重后收齐总数即可成功`() = runBlocking {
        val result = DarkTradePaginator().download({ n ->
            if (n == 1) page(n, 3, "A", "B") else page(n, 3, "B", "C")
        })
        assertTrue(result.complete)
        assertEquals(listOf("A", "B", "C"), result.records.map { it.code })
    }

    @Test
    fun `跨页去重后仍有缺口不得报告完整`() = runBlocking {
        val result = DarkTradePaginator().download({ n ->
            when (n) {
                1 -> page(n, 4, "A", "B")
                2 -> page(n, 4, "B", "C")
                else -> page(n, 4, "C")
            }
        })
        assertFalse(result.complete)
        assertEquals(3, result.records.size)
    }

    @Test
    fun `下载期间总数变化必须重试而非拼接两个快照`() = runBlocking {
        val result = DarkTradePaginator().download({ n ->
            if (n == 1) page(n, 2, "A") else page(n, 3, "B")
        })
        assertFalse(result.complete)
        assertEquals(listOf("A"), result.records.map { it.code })
    }

    @Test
    fun `HTTP异常不是无数据`() = runBlocking {
        val result = DarkTradePaginator().download({ throw java.io.IOException("HTTP 503") })
        assertFalse(result.complete)
        assertTrue(result.error.orEmpty().contains("503"))
    }

    @Test
    fun `取消后停止分页且不转换成失败结果`() = runBlocking {
        var calls = 0
        try {
            DarkTradePaginator().download({ calls++; throw CancellationException("离开页面") })
            fail("应传播取消")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `分页上限不等于完整下载`() = runBlocking {
        val result = DarkTradePaginator(maxPages = 2).download({ n -> page(n, 3, n.toString()) })
        assertFalse(result.complete)
        assertEquals(2, result.records.size)
    }
}
