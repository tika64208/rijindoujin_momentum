package com.example.momentum.data

import com.example.momentum.data.net.HttpExecutor
import com.example.momentum.model.DailyBar
import org.json.JSONObject

/**
 * 全 A 股代码列表 + 东财历史日线数据源。
 *
 * 1) 代码列表: push2.eastmoney.com/api/qt/clist/get (全 A 股, 分页)
 * 2) 日线: push2his.eastmoney.com/api/qt/stock/kline/get
 *    支持 beg/end 日期范围, 可拉 5 年以上历史。
 */
object AStockDataSource {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    data class StockCode(val code: String, val name: String)

    /**
     * 拉取全 A 股代码列表(沪深两市)。
     * 东财 fs 参数: m:0 t:6(深主板) m:0 t:80(创业板) m:1 t:2(沪主板) m:1 t:23(科创板)
     */
    fun fetchAllStockCodes(): List<StockCode> {
        val result = ArrayList<StockCode>()
        var page = 1
        val pageSize = 500
        while (true) {
            val url = "https://push2.eastmoney.com/api/qt/clist/get" +
                "?pn=$page&pz=$pageSize&po=1&np=1&fltt=2&invt=2&fid=f12" +
                "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23" +
                "&fields=f12,f14"
            try {
                val text = HttpExecutor.get(url, referer = "https://quote.eastmoney.com/")
                val json = JSONObject(text)
                val data = json.optJSONObject("data") ?: break
                val total = data.optInt("total", 0)
                val diff = data.optJSONArray("diff")
                if (diff == null || diff.length() == 0) break
                for (i in 0 until diff.length()) {
                    val item = diff.optJSONObject(i) ?: continue
                    result.add(StockCode(item.optString("f12", ""), item.optString("f14", "")))
                }
                // 已拉完
                if (result.size >= total || diff.length() < pageSize) break
                page++
            } catch (e: Exception) {
                break
            }
        }
        return result
    }

    /**
     * 下载单只股票的历史日线(前复权), 支持指定起止日期。
     * @param code 6位代码
     * @param beg yyyyMMdd 起始(含), 如 20210101
     * @param end yyyyMMdd 结束(含), 如 20500101(未来=至今)
     */
    fun fetchDailyBars(code: String, beg: String, end: String = "20500101"): List<DailyBar> {
        val secid = "${if (code.startsWith("6")) "1" else "0"}.$code"
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
            "?secid=$secid&fields1=f1,f2,f3,f4,f5,f6" +
            "&fields2=f51,f52,f53,f54,f55,f56,f57" +
            "&klt=101&fqt=1&beg=$beg&end=$end"

        return try {
            val text = HttpExecutor.get(url, referer = "https://quote.eastmoney.com/")
            val json = JSONObject(text)
            val data = json.optJSONObject("data") ?: return emptyList()
            val klines = data.optJSONArray("klines") ?: return emptyList()
            val bars = ArrayList<DailyBar>(klines.length())
            for (i in 0 until klines.length()) {
                // kline 格式: "日期,开,收,高,低,成交量,成交额"
                val cols = klines.getString(i).split(",")
                if (cols.size >= 6) {
                    bars.add(
                        DailyBar(
                            date = cols[0],
                            open = cols[1].toDoubleOrNull() ?: 0.0,
                            close = cols[2].toDoubleOrNull() ?: 0.0,
                            high = cols[3].toDoubleOrNull() ?: 0.0,
                            low = cols[4].toDoubleOrNull() ?: 0.0,
                            volume = cols[5].toDoubleOrNull() ?: 0.0
                        )
                    )
                }
            }
            bars
        } catch (e: Exception) {
            emptyList()
        }
    }
}
