package com.example.momentum.data

import com.example.momentum.engine.BidStrategy
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 东方财富竞价数据源(push2dycalc, 对应仓鼠系统)。
 *
 * 接口: push2dycalc.eastmoney.com/api/qt/clist/get
 * 字段: f2(最新价), f3(涨跌幅), f4(今开), f5(成交量), f6(成交额), f8(换手率),
 *       f10(流通市值), f12(代码), f14(名称), f17(开盘参考), f18(最高), f20(总市值),
 *       f100(行业), f102(省份), f103(速动概念), f615(竞价涨幅), f630(异动标志)
 */
object EastmoneyBidDataSource {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    private const val BASE_URL = "https://push2dycalc.eastmoney.com/api/qt/clist/get"
    private const val FIELDS = "f2,f3,f4,f5,f6,f8,f10,f12,f14,f17,f18,f20,f100,f102,f103,f615,f630"
    private const val TOKEN = "c92c50e6b0fab2c17cd5e276e9a79c42"

    /**
     * 拉取全市场竞价数据(沪深主板/创业板/科创板)。
     * @return 未过滤的原始 BidItem 列表
     */
    fun fetchAll(): List<BidStrategy.BidItem> {
        // 对齐仓鼠原版 URL: fid=f3, pz=6000, ut=token
        val fs = "m:1+t:2,m:0+t:6,m:0+t:80,m:1+t:23"
        val url = "$BASE_URL?pn=1&pz=6000&po=1&np=1&fltt=2&invt=2&fid=f3" +
            "&fs=" + URLEncoder.encode(fs, "UTF-8") +
            "&fields=" + URLEncoder.encode(FIELDS, "UTF-8") +
            "&ut=" + URLEncoder.encode(TOKEN, "UTF-8")

        return try {
            val text = httpGet(url, "https://quote.eastmoney.com/")
            val json = JSONObject(text)
            val data = json.optJSONObject("data") ?: return emptyList()
            val diff = data.optJSONArray("diff") ?: return emptyList()
            val items = ArrayList<BidStrategy.BidItem>()
            for (i in 0 until diff.length()) {
                val row = diff.optJSONObject(i) ?: continue
                val f2 = optDouble(row, "f2")
                val f4 = optDouble(row, "f4")
                val f17 = optDouble(row, "f17")
                val openPrice = if (f17 > 0) f17 else f4

                items.add(
                    BidStrategy.BidItem(
                        code = row.optString("f12", ""),
                        name = row.optString("f14", ""),
                        latestPrice = f2,
                        realChange = optDouble(row, "f3"),
                        openPrice = openPrice,
                        volume = optInt(row, "f5"),
                        amount = optDouble(row, "f6"),
                        turnover = optDouble(row, "f8"),
                        circMv = optDouble(row, "f20"),  // 总市值(元), f10流通市值不可靠改用f20
                        highPrice = optDouble(row, "f18"),
                        totalMv = optDouble(row, "f20"),
                        bidChange = optDouble(row, "f615"),
                        warnFlag = optInt(row, "f630"),
                        industry = row.optString("f100", "-"),
                        province = row.optString("f102", "-"),
                        conceptQuick = row.optString("f103", "-")
                    )
                )
            }
            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun optDouble(row: JSONObject, key: String): Double {
        val v = row.opt(key)
        return when (v) {
            null, "-", "−", "" -> 0.0
            is Number -> v.toDouble()
            else -> try { v.toString().toDouble() } catch (_: Exception) { 0.0 }
        }
    }

    private fun optInt(row: JSONObject, key: String): Int {
        val v = row.opt(key)
        return when (v) {
            null, "-", "−", "" -> 0
            is Number -> v.toInt()
            else -> try { v.toString().toDouble().toInt() } catch (_: Exception) { 0 }
        }
    }

    private fun httpGet(urlStr: String, referer: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", referer)
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