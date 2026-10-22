package com.example.momentum.data

import com.example.momentum.data.net.HttpExecutor
import org.json.JSONObject

/**
 * 东方财富主力资金流向数据源(个股历史)。
 *
 * 接口: push2his.eastmoney.com/api/qt/stock/fflow/daykline/get
 * 返回 klines: 每行 "日期,主力净流入,小单净流入,..."
 * 注意: 该接口需限流, 不能高频连续请求。
 */
object MoneyFlowDataSource {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"

    /**
     * 单日资金流向记录。
     */
    data class FlowPoint(
        val date: String,        // yyyy-MM-dd
        val mainNet: Double,     // 主力净流入(元) f52 = 大单+超大单
        val smallNet: Double,    // 小单净流入(元) f53
        val midNet: Double,      // 中单净流入(元) f54
        val bigNet: Double,      // 大单净流入(元) f55
        val superNet: Double,    // 超大单净流入(元) f56
        val mainRatio: Double,   // 主力净流入占比(%) f57
        val close: Double,       // 收盘价 f62
        val chgPct: Double       // 当日涨跌幅(%) f63
    )

    /**
     * 查询个股近 N 日的主力资金流向。
     * @param code 6位股票代码
     * @param days 回看天数(<=60)
     */
    fun fetchFlow(code: String, days: Int = 30): List<FlowPoint> {
        // secid 格式: 0.代码(深) / 1.代码(沪)
        val market = if (code.startsWith("6") || code.startsWith("5")) "1" else "0"
        val secid = "$market.$code"
        val url = "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get" +
            "?secid=$secid&fields1=f1,f2,f3,f7" +
            "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65" +
            "&klt=101&lmt=$days"

        return try {
            val text = httpGet(url)
            val json = JSONObject(text)
            val data = json.optJSONObject("data") ?: return emptyList()
            val klines = data.optJSONArray("klines") ?: return emptyList()
            val result = ArrayList<FlowPoint>(klines.length())
            for (i in 0 until klines.length()) {
                val parts = klines.getString(i).split(",")
                if (parts.size >= 7) {
                    result.add(
                        FlowPoint(
                            date = parts[0],
                            mainNet = parts[1].toDoubleOrNull() ?: 0.0,
                            smallNet = parts[2].toDoubleOrNull() ?: 0.0,
                            midNet = parts[3].toDoubleOrNull() ?: 0.0,
                            bigNet = parts[4].toDoubleOrNull() ?: 0.0,
                            superNet = parts[5].toDoubleOrNull() ?: 0.0,
                            mainRatio = parts[6].toDoubleOrNull() ?: 0.0,  // f57 主力净流入占比
                            close = parts[11].toDoubleOrNull() ?: 0.0,      // f62 收盘价
                            chgPct = parts[12].toDoubleOrNull() ?: 0.0      // f63 当日涨跌幅
                        )
                    )
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun httpGet(urlStr: String): String =
        HttpExecutor.get(
            urlStr = urlStr,
            referer = "https://data.eastmoney.com/",
            connectTimeoutMs = 10_000,
            readTimeoutMs = 10_000
        )
}
