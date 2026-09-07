package com.example.momentum.data

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 东方财富暗盘(大宗/场外)交易数据源。
 *
 * 接口: quotederivates.eastmoney.com/datacenter/darktrade
 * 支持按 code 查询单只股票某日的暗盘交易记录。
 * 该子域未被反爬(与 push2.eastmoney.com 行情子域不同)。
 */
object DarkTradeDataSource {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

    /**
     * 一笔暗盘交易记录。
     */
    data class DarkTradeRecord(
        val date: String,      // yyyy-MM-dd
        val attr: String,      // 属性(0/1, 方向)
        val buyAmount: Double,  // 买入额(元)
        val sellAmount: Double, // 卖出额(元)
        val totalAmount: Double // 总成交额(元)
    ) {
        val netAmount: Double get() = buyAmount + sellAmount  // 净额(买+卖, 卖为负则净卖出)
    }

    /**
     * 查询单只股票某日的暗盘交易记录。
     * @param code 6位股票代码
     * @param date yyyyMMdd
     */
    fun fetchDarkTrade(code: String, date: String): List<DarkTradeRecord> {
        val ts = System.currentTimeMillis()
        val url = "https://quotederivates.eastmoney.com/datacenter/darktrade" +
            "?version=100&date=$date&NumPerPage=50&sortflag=6&desc=1&market=0" +
            "&code=$code&label=%E4%B8%AA%E8%82%A1&StartPage=1&cb=cb&_=$ts"

        return try {
            val text = httpGet(url)
            // JSONP: cb({...})
            val start = text.indexOf('(')
            val end = text.lastIndexOf(')')
            if (start < 0 || end <= start) return emptyList()
            val jsonStr = text.substring(start + 1, end)
            val json = JSONObject(jsonStr)
            val dataArr = json.optJSONArray("data") ?: return emptyList()
            val records = ArrayList<DarkTradeRecord>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                records.add(
                    DarkTradeRecord(
                        date = formattedDate(date),
                        attr = item.optString("3", ""),
                        buyAmount = item.optDouble("6", 0.0),
                        sellAmount = item.optDouble("7", 0.0),
                        totalAmount = item.optDouble("8", 0.0)
                    )
                )
            }
            records
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 查询近 N 天的暗盘交易记录(逐日查询, 只保留有数据的日期)。
     * @param days 回看天数
     */
    fun fetchHistory(code: String, days: Int = 60): List<DarkTradeRecord> {
        val result = ArrayList<DarkTradeRecord>()
        val today = LocalDate.now()
        for (offset in 0..days) {
            val d = today.minusDays(offset.toLong())
            val dateStr = d.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            val records = fetchDarkTrade(code, dateStr)
            if (records.isNotEmpty()) {
                result.addAll(records)
            }
        }
        return result
    }

    private fun formattedDate(yyyymmdd: String): String {
        return if (yyyymmdd.length == 8) {
            "${yyyymmdd.substring(0, 4)}-${yyyymmdd.substring(4, 6)}-${yyyymmdd.substring(6, 8)}"
        } else yyyymmdd
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://data.eastmoney.com/")
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")
            val reader = BufferedReader(conn.inputStream.reader(Charsets.UTF_8))
            return reader.use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
