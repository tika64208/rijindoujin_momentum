package com.example.momentum.data

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 同花顺(10jqka)行业/概念板块数据源。
 *
 * 接口: q.10jqka.com.cn/thshy/detail/code/<code>/ (行业)
 *       q.10jqka.com.cn/gn/detail/code/<code>/    (概念)
 * 返回 HTML 页面, 含板块涨幅/涨跌家数/资金净流入/成交额等指标。
 * 用正则解析, 不引入 JSoup 依赖。
 */
object ThsDataSource {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 板块快照指标。
     */
    data class BoardSnapshot(
        val name: String,          // 板块名称
        val pct: Double,           // 板块涨幅 %
        val upCount: Int,          // 上涨家数
        val downCount: Int,        // 下跌家数
        val netInflow: Double,     // 资金净流入(亿)
        val amount: Double,        // 成交额(亿)
        val rank: String           // 涨幅排名, 如 "111/390"
    )

    /**
     * 股票 → 同花顺行业板块代码映射。
     */
    fun getBoardCode(code: String): String = when (code) {
        // 领先科技 5 股(电子/PCB/元器件 → 光学光电子/其他电子)
        "603991" -> "881122"  // 光学光电子
        "300285" -> "881123"  // 其他电子
        "600183" -> "881123"  // 其他电子(覆铜板)
        "300903" -> "881123"  // 其他电子(PCB)
        "300054" -> "881123"  // 其他电子
        // 示例蓝筹
        "600519" -> "881273"  // 白酒
        "000858" -> "881273"  // 白酒
        "601318" -> "881156"  // 保险
        "600036" -> "881155"  // 银行
        "000333" -> "881131"  // 白色家电
        "600900" -> "881145"  // 电力
        "002594" -> "881121"  // 半导体
        "300750" -> "881121"  // 半导体
        "601899" -> "881169"  // 贵金属
        "600276" -> "881140"  // 化学制药
        else -> ""            // 未知
    }

    /**
     * 股票所属的概念板块代码(更细分, 如 PCB概念)。
     * 与行业板块二选一或叠加展示。
     */
    fun getConceptCode(code: String): String = when (code) {
        "600183", "300903", "300054" -> "308832"  // PCB概念
        "603991", "300285" -> "308832"             // PCB概念(材料)
        "600519", "000858" -> "301496"             // 白酒概念
        "002594", "300750" -> "301085"             // 芯片概念
        else -> ""
    }

    /**
     * 获取行业板块快照。
     */
    fun fetchBoardSnapshot(boardCode: String): BoardSnapshot? {
        if (boardCode.isBlank()) return null
        return try {
            val html = httpGetHtml("http://q.10jqka.com.cn/thshy/detail/code/$boardCode/")
            parseBoardHtml(html)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取概念板块快照(PCB概念等)。
     */
    fun fetchConceptSnapshot(conceptCode: String): BoardSnapshot? {
        if (conceptCode.isBlank()) return null
        return try {
            val html = httpGetHtml("http://q.10jqka.com.cn/gn/detail/code/$conceptCode/")
            parseBoardHtml(html)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析同花顺板块 HTML, 提取板块指标。
     */
    private fun parseBoardHtml(html: String): BoardSnapshot? {
        fun parseValue(key: String): String? {
            // <dt>板块涨幅</dt><dd class="...">1.10%</dd>
            val m = Regex(">${Regex.escape(key)}</dt>\\s*<dd[^>]*>([^<]*)</dd>").find(html)
            return m?.groupValues?.get(1)?.trim()
        }

        val pctStr = parseValue("板块涨幅") ?: return null
        val pct = pctStr.replace("%", "").trim().toDoubleOrNull() ?: 0.0

        // 涨跌家数: <span class="arr-rise-s">30</span><span class="arr-fall-s">4</span>
        var up = 0
        var down = 0
        Regex("arr-rise-s\">(\\d+)</span>").find(html)?.let { up = it.groupValues[1].toIntOrNull() ?: 0 }
        Regex("arr-fall-s\">(\\d+)</span>").find(html)?.let { down = it.groupValues[1].toIntOrNull() ?: 0 }

        val inflow = parseValue("资金净流入(亿)")?.toDoubleOrNull() ?: 0.0
        val amount = parseValue("成交额(亿)")?.toDoubleOrNull() ?: 0.0
        val rank = parseValue("涨幅排名") ?: "-"

        return BoardSnapshot(
            name = "",
            pct = pct,
            upCount = up,
            downCount = down,
            netInflow = inflow,
            amount = amount,
            rank = rank
        )
    }

    private fun httpGetHtml(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "http://q.10jqka.com.cn/")

            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")
            // 同花顺页面是 gbk 编码
            val bytes = conn.inputStream.readBytes()
            return String(bytes, Charset.forName("GBK"))
        } finally {
            conn.disconnect()
        }
    }
}
