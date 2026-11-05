package com.example.momentum.data

import org.junit.Assert.*
import org.junit.Test

class OpeningBidCsvTest {
    private val raw = """{"rc":0,"data":{"total":1,"diff":[{"f12":"000001","f14":"测试,股票\"A\"","f2":12.3456789,"f17":12.1,"f3":2.9,"f5":12345,"f6":12345678.1234,"f8":0.123456,"f20":5000000000,"f615":2.5,"f124":1793842200,"f102":"广东","f103":"概念\n第二行","extra":null}]}}"""

    @Test fun `原始字段保留精度缺失值和省份并正确转义`() {
        val csv = OpeningBidCsv.generate(OpeningBidResponse(raw, 1, listOf("000001"), emptyList()), 1793842200000, 1793842201000)
        assertTrue(csv.startsWith("\uFEFF交易日期,"))
        assertTrue(csv.contains("12.3456789"))
        assertTrue(csv.contains("12345678.1234"))
        assertTrue(csv.contains("000001"))
        assertTrue(csv.contains("广东"))
        assertTrue(csv.contains("\"测试,股票\"\"A\"\"\""))
        assertTrue(csv.contains("\"概念\n第二行\""))
        assertTrue(csv.contains("冻结评分"))
        assertTrue(csv.contains("f124"))
        assertTrue(csv.contains("股票代码[f12]"))
        assertTrue(csv.contains("最新价[f2]"))
    }

    @Test fun `原有解析可读取带报价时间的新响应`() {
        val item = EastmoneyBidDataSource.parseItems(raw).single()
        assertEquals("000001", item.code)
        assertEquals(12.3456789, item.latestPrice, 0.0)
        assertEquals(12.1, item.openPrice, 0.0)
        assertEquals(12345, item.volume)
    }
}
