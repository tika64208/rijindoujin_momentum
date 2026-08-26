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

    /**
     * 搜索结果条目。
     */
    data class SearchResult(
        val symbol: String,   // 带前缀 sh/sz/bj
        val code: String,     // 6位代码
        val name: String
    )

    /**
     * 按关键词搜索 A股股票(支持中文名称/拼音/代码)。
     *
     * 接口: smartbox.gtimg.cn/s3/?v=2&q=<kw>&t=gp
     * 返回格式: v_hint="市场~代码~名称~拼音~GP-A^市场~代码~..."
     */
    fun searchStocks(keyword: String): List<SearchResult> {
        if (keyword.isBlank()) return emptyList()
        return try {
            val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
            val text = httpGetText("https://smartbox.gtimg.cn/s3/?v=2&q=$encoded&t=gp")
            // 剥离 v_hint="..." 包装
            val body = text.substringAfter("v_hint=\"").substringBeforeLast("\"")
            if (body.isEmpty()) return emptyList()

            val results = ArrayList<SearchResult>()
            for (item in body.split("^")) {
                val parts = item.split("~")
                if (parts.size < 5) continue
                val market = parts[0].trim()
                val code = parts[1].trim()
                val name = unescapeUnicode(parts[2].trim())
                val type = parts[4].trim()
                // 只保留 A股(GP-A)
                if (type == "GP-A" && code.length == 6 && code.all { it.isDigit() }) {
                    results.add(
                        SearchResult(
                            symbol = "$market$code",
                            code = code,
                            name = name
                        )
                    )
                }
            }
            // 去重(按 code)
            results.distinctBy { it.code }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 把 \uXXXX 转义序列解码为真实 Unicode 字符。
     * 例: "\u9886\u5148\u80a1\u4efd" -> "领先股份"
     */
    private fun unescapeUnicode(input: String): String {
        if (!input.contains("\\u")) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\' && i + 1 < input.length && input[i + 1] == 'u' && i + 5 < input.length) {
                val hex = input.substring(i + 2, i + 6)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
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
