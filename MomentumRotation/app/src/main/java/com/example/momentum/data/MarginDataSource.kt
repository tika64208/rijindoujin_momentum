package com.example.momentum.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 融资融券(两融)数据源(东方财富官方数据中心)。
 *
 * 接口: datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPTA_WEB_RZRQ_GGMX
 * 字段(当日):
 *   RZMRE = 融资买入额
 *   RZCHE = 融资偿还额
 *   RZJME = 融资净买入( = RZMRE - RZCHE )
 *   RZYE  = 融资余额
 *   RZRQYE= 融资融券余额
 * 数值单位: 元
 */
object MarginDataSource {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    data class MarginInfo(
        val code: String,
        val buy: Double,      // 融资买入(元)
        val repay: Double,    // 融资偿还(元)
        val netBuy: Double,   // 融资净买入(元)
        val balance: Double   // 融资余额(元)
    ) {
        val hasData: Boolean get() = buy != 0.0 || balance != 0.0
    }

    /**
     * 查询单只个股最新一日的两融明细。
     * @param code 6位代码
     */
    fun fetchMargin(code: String): MarginInfo {
        val filter = "(SCODE=\"$code\")"
        val url = "https://datacenter-web.eastmoney.com/api/data/v1/get" +
            "?reportName=RPTA_WEB_RZRQ_GGMX&columns=ALL" +
            "&filter=" + URLEncoder.encode(filter, "UTF-8") +
            "&pageNumber=1&pageSize=1&sortTypes=-1&sortColumns=DATE"

        return try {
            val text = httpGet(url)
            val json = JSONObject(text)
            val result = json.optJSONObject("result") ?: return MarginInfo(code, 0.0, 0.0, 0.0, 0.0)
            val arr: JSONArray = result.optJSONArray("data") ?: return MarginInfo(code, 0.0, 0.0, 0.0, 0.0)
            if (arr.length() == 0) return MarginInfo(code, 0.0, 0.0, 0.0, 0.0)
            val d = arr.getJSONObject(0)
            MarginInfo(
                code = code,
                buy = d.optDouble("RZMRE", 0.0),
                repay = d.optDouble("RZCHE", 0.0),
                netBuy = d.optDouble("RZJME", 0.0),
                balance = d.optDouble("RZYE", 0.0)
            )
        } catch (e: Exception) {
            MarginInfo(code, 0.0, 0.0, 0.0, 0.0)
        }
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
