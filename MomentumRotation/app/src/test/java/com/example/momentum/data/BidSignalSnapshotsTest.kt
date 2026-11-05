package com.example.momentum.data

import com.example.momentum.engine.BidCandidateSelector
import com.example.momentum.engine.BidStrategy
import org.junit.Assert.*
import org.junit.Test

class BidSignalSnapshotsTest {
    private val raw = """{"rc":0,"data":{"total":1,"diff":[{"f12":"688516","f14":"奥特维","f2":49.09,"f17":44.0,"f615":2.5,"f5":2000000000,"f6":200000000.0,"f20":5000000000,"f630":4}]}}"""
    private val csv = "\uFEFF股票代码[f12],冻结概率,冻结可信度,冻结评分,冻结竞价换手率,评分版本\n688516,63,82,34,0.01,archived-v1\n"
    private fun archive(text: String = csv) = OpeningBidArchive("2026-11-05", 1793842200000L,
        1793842201000L, 1, raw, text)

    @Test fun `原始成交量再大也使用归档分数和版本而非当前模型`() {
        val snapshot = BidSignalSnapshots.opening(archive()).single()
        assertEquals(34, snapshot.signal.score)
        assertEquals(63, snapshot.signal.probability)
        assertEquals("archived-v1", snapshot.scoringVersion)
        assertEquals("OPENING_0930", snapshot.source)
        assertTrue(snapshot.rawInputJson.contains("2000000000"))
        assertFalse(BidCandidateSelector.matches(snapshot.signal, BidStrategy.BidConfig.HIGH_WIN_RATE))
    }

    @Test fun `归档评分缺失不能用现在的模型补造`() {
        try { BidSignalSnapshots.opening(archive("f12,冻结概率\n688516,63\n")); fail("应失败") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `归档CSV数量不足不能假装全量冻结`() {
        try { BidSignalSnapshots.opening(archive().copy(rowCount = 2)); fail("应失败") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `归档CSV正确处理中文逗号换行引号与BOM`() {
        val parsed = BidSignalSnapshots.parseCsv("\uFEFF代码,概念,值\r\n000001,\"中文,概念\n\"\"第二行\"\"\",34\r\n")
        assertEquals(listOf("代码", "概念", "值"), parsed[0])
        assertEquals(listOf("000001", "中文,概念\n\"第二行\"", "34"), parsed[1])
    }
}
