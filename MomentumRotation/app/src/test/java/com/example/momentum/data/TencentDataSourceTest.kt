package com.example.momentum.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 数据源工具方法单元测试。
 */
class TencentDataSourceTest {

    @Test
    fun `unicode转义解码为中文`() {
        val input = "\\u9886\\u5148\\u80a1\\u4efd"
        val result = TencentDataSource.unescapeUnicode(input)
        assertEquals("领先股份", result)
    }

    @Test
    fun `无转义序列原样返回`() {
        assertEquals("贵州茅台", TencentDataSource.unescapeUnicode("贵州茅台"))
        assertEquals("600519", TencentDataSource.unescapeUnicode("600519"))
    }

    @Test
    fun `混合内容正确解码`() {
        // 混合普通字符和转义
        val result = TencentDataSource.unescapeUnicode("\\u4e2d\\u56fd" + "平安")
        assertEquals("中国平安", result)
    }

    @Test
    fun `空字符串返回空`() {
        assertEquals("", TencentDataSource.unescapeUnicode(""))
    }
}
