package com.example.momentum.data

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 新浪实时行情数据源(轻量, 用于实时监控)。
 *
 * 接口: hq.sinajs.cn/list=<codes>
 * 返回: var hq_str_sh600183="名称,今开,昨收,现价,最高,最低,...,成交量,成交额,...,时间";
 */
object SinaDataSource {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0 Safari/537.36"

    /**
     * 实时行情快照。
     */
    data class Quote(
        val code: String,
        val name: String,
        val price: Double,
        val prevClose: Double,
        val pct: Double,       // 涨跌幅 %
        val amount: Double,    // 成交额(元)
        val time: String
    )

    /**
     * 批量获取实时行情。
     */
    fun fetchQuotes(symbols: List<String>): Map<String, Quote> {
        if (symbols.isEmpty()) return emptyMap()
        return try {
            val url = "https://hq.sinajs.cn/list=" + symbols.joinToString(",")
            val text = httpGetGbk(url, "https://finance.sina.com.cn")
            val result = HashMap<String, Quote>()
            for (line in text.split("\n")) {
                if ("=\"" !in line) continue
                val code = line.substringBefore("=").substringAfterLast("_").trim()
                val data = line.substringAfter("\"").substringBefore("\"").split(",")
                if (data.size < 32) continue
                try {
                    val price = data[3].toDoubleOrNull() ?: continue
                    val prevClose = data[2].toDoubleOrNull() ?: 0.0
                    val pct = if (prevClose > 0) (price / prevClose - 1) * 100 else 0.0
                    result[code] = Quote(
                        code = code,
                        name = data[0],
                        price = price,
                        prevClose = prevClose,
                        pct = pct,
                        amount = data[9].toDoubleOrNull() ?: 0.0,
                        time = data[31]
                    )
                } catch (e: Exception) {
                    // 跳过解析失败的行
                }
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun httpGetGbk(urlStr: String, referer: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", referer)
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")
            val bytes = conn.inputStream.readBytes()
            return String(bytes, Charset.forName("GBK"))
        } finally {
            conn.disconnect()
        }
    }
}
