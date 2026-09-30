package com.mytt.selector

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * SinaClient.kt —— 免费行情数据源(新浪)
 * =====================================
 * 用新浪日K接口拉取单只股票的不复权日线, 足够计算 MYTT 信号。
 * 接口: quotes.sina.cn /cn/api/jsonp_v2.php/CN_MarketDataService.getKLineData
 *   ?symbol=sz000001&scale=240&ma=no&datalen=1023
 */
object SinaClient {

    /** 代码 -> 新浪 symbol (sh600000 / sz000001 / bj...). */
    fun toSymbol(code: String): String {
        val c = code.trim()
        return when {
            c.startsWith("6") -> "sh$c"
            c.startsWith(("0")) || c.startsWith("3") -> "sz$c"
            else -> "sh$c" // 退而求其次
        }
    }

    /**
     * 拉取日线(升序)。失败抛异常。
     * @param code 6位股票代码
     * @param datalen 需要的K线根数(默认 1023, 覆盖公式最长 lookback)
     */
    fun fetchDaily(code: String, datalen: Int = 1023): Mytt.Kline {
        val symbol = toSymbol(code)
        val urlStr = "https://quotes.sina.cn/cn/api/jsonp_v2.php/=/CN_MarketDataService.getKLineData" +
                "?symbol=$symbol&scale=240&ma=no&datalen=$datalen"
        val text = httpGet(urlStr)
        // 响应形如:  ...=([{...},...]);  提取 ( 与 ); 之间的 JSON
        val start = text.indexOf("=(") + 2
        val end = text.lastIndexOf(");")
        if (start < 0 || end < start) throw IllegalStateException("接口返回格式异常")
        val arr = JSONArray(text.substring(start, end))

        val n = arr.length()
        val date = Array(n) { "" }
        val open = DoubleArray(n); val high = DoubleArray(n); val low = DoubleArray(n)
        val close = DoubleArray(n); val vol = DoubleArray(n); val amount = DoubleArray(n)
        for (i in 0 until n) {
            val o = arr.getJSONObject(i)
            date[i] = o.optString("day", "")
            open[i] = o.optDouble("open", 0.0)
            high[i] = o.optDouble("high", 0.0)
            low[i] = o.optDouble("low", 0.0)
            close[i] = o.optDouble("close", 0.0)
            vol[i] = o.optDouble("volume", 0.0)
            amount[i] = 0.0 // 新浪该接口无成交额, 公式不用 amount 的死代码
        }
        return Mytt.Kline(date, open, high, low, close, vol, amount)
    }

    private fun httpGet(urlStr: String, timeoutMs: Int = 20000): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Referer", "https://finance.sina.com.cn")
            conn.connect()
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { r ->
                var line: String?
                while (true) {
                    line = r.readLine() ?: break
                    sb.append(line)
                }
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }
}
