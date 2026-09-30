package com.example.momentum.data

import com.example.momentum.data.net.HttpExecutor
import org.json.JSONObject

/**
 * 个股资金流数据源(东方财富 push2his 历史资金流)。
 *
 * 接口: push2his.eastmoney.com/api/qt/stock/fflow/daykline/get
 * 字段(klines 逗号分隔, 单日一行):
 *   f52 = 主力净流入, f53 = 小单净流入, f54 = 中单净流入,
 *   f55 = 大单净流入, f56 = 超大单净流入,
 *   f57 = 主力占比%, f58 = 小单占比%
 * 数值单位: 元(占比为 %)
 */
object MainFundDataSource {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    data class FundFlow(
        val code: String,
        val mainNet: Double,      // 主力净流入(元)
        val smallNet: Double,     // 小单净流入(元)
        val midNet: Double,       // 中单净流入(元)
        val bigNet: Double,       // 大单净流入(元)
        val superNet: Double,     // 超大单净流入(元)
        val mainRatio: Double,    // 主力占比(%)
        val smallRatio: Double    // 小单占比(%)
    ) {
        val hasData: Boolean get() = mainNet != 0.0 || smallNet != 0.0 || midNet != 0.0
    }

    /**
     * 查询单只个股最近一日的资金流明细。
     * @param code 6位代码
     */
    fun fetchFundFlow(code: String): FundFlow {
        val market = if (code.startsWith("6") || code.startsWith("5")) "1" else "0"
        val secid = "$market.$code"
        val url = "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get" +
            "?secid=$secid&fields1=f1,f2,f3,f7" +
            "&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
            "&klt=101&lmt=1"

        // 重试 3 次, 间隔递增(防东财移动端间歇性断流)
        repeat(3) { attempt ->
            try {
                val text = httpGet(url)
                val json = JSONObject(text)
                val d = json.optJSONObject("data") ?: return@repeat
                val klines = d.optJSONArray("klines") ?: return@repeat
                if (klines.length() == 0) return@repeat
                // 取最新一行(通常就是当日/最近交易日)
                val cols = klines.getString(klines.length() - 1).split(",")
                if (cols.size < 8) return@repeat
                return FundFlow(
                    code = code,
                    mainNet = cols[1].toDoubleOrNull() ?: 0.0,
                    smallNet = cols[2].toDoubleOrNull() ?: 0.0,
                    midNet = cols[3].toDoubleOrNull() ?: 0.0,
                    bigNet = cols[4].toDoubleOrNull() ?: 0.0,
                    superNet = cols[5].toDoubleOrNull() ?: 0.0,
                    mainRatio = cols[6].toDoubleOrNull() ?: 0.0,
                    smallRatio = cols[7].toDoubleOrNull() ?: 0.0
                )
            } catch (e: Exception) {
                android.util.Log.w("MainFund", "code=$code 资金流请求失败(attempt ${attempt + 1}): ${e.message}")
                if (attempt < 2) {
                    try { Thread.sleep((attempt + 1) * 300L) } catch (_: InterruptedException) {}
                }
            }
        }
        return FundFlow(code, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    private fun httpGet(urlStr: String): String =
        HttpExecutor.get(
            urlStr = urlStr,
            referer = "https://data.eastmoney.com/",
            headers = mapOf("Connection" to "close", "Accept-Language" to "zh-CN,zh;q=0.9"),
            connectTimeoutMs = 15_000,
            readTimeoutMs = 15_000
        )
}
