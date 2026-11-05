package com.example.momentum.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class BidDistributionTest {
    private fun calculate(vararg changes: Double?) = BidDistributionEngine.calculate(
        "2026-11-05", BidDistributionSource.LEGACY, "09:29:30", changes.toList())

    @Test fun `边界值不重叠不遗漏且零单列`() {
        val report = calculate(-20.0, -10.0, -9.0, -1.0, -0.001, -0.0, 0.0,
            0.001, 0.999, 1.0, 2.0, 9.999, 10.0, 20.0)
        val counts = report.bins.associate { it.label to it.count }
        assertEquals(1, counts["< -10%"])
        assertEquals(1, counts["[-10, -9)%"])
        assertEquals(1, counts["[-9, -8)%"])
        assertEquals(2, counts["[-1, 0)%"])
        assertEquals(2, counts["0%"])
        assertEquals(2, counts["(0, 1)%"])
        assertEquals(1, counts["[1, 2)%"])
        assertEquals(1, counts["[2, 3)%"])
        assertEquals(1, counts["[9, 10)%"])
        assertEquals(2, counts["≥ 10%"])
        assertEquals(14, report.bins.sumOf { it.count })
        assertEquals(5, report.down)
        assertEquals(2, report.flat)
        assertEquals(7, report.up)
    }

    @Test fun `非有限值和缺失单列且不污染均值中位数`() {
        val report = calculate(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -2.0, 4.0)
        assertEquals(4, report.missing)
        assertEquals(0, report.flat)
        assertEquals(1.0, report.average!!, 0.0)
        assertEquals(1.0, report.median!!, 0.0)
        assertEquals(100.0 / 6, report.percentage(report.up), 0.00001)
    }

    @Test fun `无数据不返回虚构的零涨幅均值`() {
        for (report in listOf(calculate(), calculate(null, Double.NaN))) {
            assertNull(report.average)
            assertNull(report.median)
            assertEquals(0, report.up + report.down + report.flat)
            assertEquals(report.total, report.bins.sumOf { it.count })
            assertTrue(report.percentage(0).isFinite())
        }
    }

    @Test fun `奇数中位数先排序`() { assertEquals(3.0, calculate(9.0, -1.0, 3.0).median!!, 0.0) }

    @Test fun `样本超过五百条仍全部统计`() {
        val changes = (0 until 5563).map { if (it < 500) 2.0 else -2.0 }
        val report = BidDistributionEngine.calculate("2026-11-05", BidDistributionSource.LEGACY, "", changes)
        assertEquals(5563, report.total)
        assertEquals(500, report.up)
        assertEquals(5063, report.down)
        assertEquals(report.total, report.bins.sumOf { it.count })
    }

    @Test fun `原始接口只使用竞价涨幅且保留无效值`() {
        val values = listOf<Any?>(2.5, -1.0, 0, "1.25", "-", null, "NaN", "Infinity", "bad")
        val rows = JSONArray().apply { values.forEach { value -> put(JSONObject().apply {
            put("f615", value ?: JSONObject.NULL)
            put("f3", 99.0)
        }) } }
        val raw = JSONObject().put("data", JSONObject().put("total", values.size).put("diff", rows)).toString()
        val report = BidDistributionEngine.fromRaw("2026-11-05", "09:30:01", raw)
        assertEquals(2, report.up)
        assertEquals(1, report.down)
        assertEquals(1, report.flat)
        assertEquals(5, report.missing)
        assertEquals(BidDistributionSource.OPENING_0930, report.source)
    }

    @Test fun `没有竞价字段不能用实时涨幅填补`() {
        val raw = """{"data":{"total":1,"diff":[{"f3":8.0}]}}"""
        val report = BidDistributionEngine.fromRaw("2026-11-05", "09:30:01", raw)
        assertEquals(1, report.missing)
        assertEquals(0, report.flat)
    }

    @Test fun `损坏的原始全量档案不生成分布`() {
        try {
            BidDistributionEngine.fromRaw("2026-11-05", "09:30:01", """{"data":{"total":2,"diff":[{"f615":1}]}}""")
            fail("应拒绝不完整数据")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `存盘往返保留日期来源口径和所有计数`() {
        val original = calculate(-10.0, 0.0, 2.0, null).copy(savedAt = 123456789L)
        assertEquals(original, BidDistributionFormat.parse(BidDistributionFormat.json(original)))
    }

    @Test fun `全缺失的存盘可还原空均值`() {
        val original = calculate(null, null).copy(savedAt = 123L)
        assertEquals(original, BidDistributionFormat.parse(BidDistributionFormat.json(original)))
    }

    @Test fun `计数损坏的存盘被拒绝`() {
        val json = JSONObject(BidDistributionFormat.json(calculate(1.0))).put("total", 99)
        try { BidDistributionFormat.parse(json.toString()); fail("应拒绝") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `CSV 使用固定小数点转义区间中的逗号且含真实来源`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val csv = BidDistributionFormat.csv(calculate(-1.0, 0.0, 1.0).copy(savedAt = 123L))
            assertTrue(csv.startsWith("\uFEFF交易日期"))
            assertTrue(csv.contains("\"[1, 2)%\""))
            assertTrue(csv.contains("33.333333"))
            assertTrue(csv.contains("普通竞价历史"))
            assertEquals(25, csv.lineSequence().filter { it.isNotEmpty() }.count())
        } finally { Locale.setDefault(previous) }
    }
}
