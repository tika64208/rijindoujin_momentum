package com.example.momentum.data

import com.example.momentum.model.DailyBar
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 腾讯 ifzq 复权 K 线数据源(主源)。
 *
 * 接口: web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=<sym>,day,,,<N>,qfq
 * 返回 qfqday 字段, 每行顺序为 [date, open, close, high, low, volume]。
 */
object TencentDataSource {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 下载前复权日线, 失败抛异常。 */
    fun fetchDailyBars(symbol: String, days: Int): List<DailyBar> {
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get" +
            "?param=$symbol,day,,,$days,qfq"
        val json = httpGetJson(url, "https://gu.qq.com/")

        val node = json.optJSONObject("data")?.optJSONObject(symbol) ?: return emptyList()
        val rows = node.optJSONArray("qfqday") ?: node.optJSONArray("day") ?: return emptyList()

        val bars = ArrayList<DailyBar>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONArray(i)
            // 腾讯字段顺序: [date, open, close, high, low, volume]
            bars.add(
                DailyBar(
                    date = row.getString(0),
                    open = row.getDouble(1),
                    high = row.getDouble(3),
                    low = row.getDouble(4),
                    close = row.getDouble(2),
                    volume = row.getDouble(5)
                )
            )
        }
        return bars
    }

    /** 股票名称, 失败返回符号本身。 */
    fun fetchStockName(symbol: String): String {
        return try {
            val text = httpGetText("https://qt.gtimg.cn/q=$symbol")
            // 形如 v_sh603991="1~领先股份~603991~...~";
            val parts = text.substringAfter("~\"").split("~")
            if (parts.size > 1) parts[1].replace(" ", "") else symbol
        } catch (e: Exception) {
            symbol
        }
    }

    private fun httpGetJson(url: String, referer: String? = null): JSONObject {
        val text = httpGetText(url, referer)
        return JSONObject(text)
    }

    private fun httpGetText(urlStr: String, referer: String? = null): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", referer ?: "https://gu.qq.com/")
            conn.setRequestProperty("Accept", "*/*")

            val code = conn.responseCode
            if (code != 200) {
                throw RuntimeException("HTTP $code")
            }
            val reader = BufferedReader(conn.inputStream.reader(Charsets.UTF_8))
            return reader.use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
