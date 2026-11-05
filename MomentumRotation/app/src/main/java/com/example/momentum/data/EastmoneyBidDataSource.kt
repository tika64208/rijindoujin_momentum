package com.example.momentum.data

import com.example.momentum.data.net.HttpExecutor
import com.example.momentum.engine.BidStrategy
import org.json.JSONObject
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
    data class MarketSnapshot(val items: List<BidStrategy.BidItem>, val quoteTimes: Map<String, Long>, val receivedAt: Long)

    fun fetchMarket(): MarketSnapshot {
        val response = request()
        val receivedAt = System.currentTimeMillis()
        val rows = JSONObject(response).getJSONObject("data").getJSONArray("diff")
        val times = (0 until rows.length()).associate { i -> rows.getJSONObject(i).let {
            it.getString("f12") to it.optLong("f124", 0L) * 1000L
        } }
        return MarketSnapshot(parseItems(response), times, receivedAt)
    }

    private const val BASE_URL = "https://push2dycalc.eastmoney.com/api/qt/clist/get"
    private const val FIELDS = "f2,f3,f4,f5,f6,f8,f10,f12,f14,f17,f18,f20,f100,f102,f103,f124,f615,f630"
    private const val TOKEN = "c92c50e6b0fab2c17cd5e276e9a79c42"

    /**
     * 拉取全市场竞价数据(沪深主板/创业板/科创板)。
     * @return 未过滤的原始 BidItem 列表
     */
    fun fetchAll(): List<BidStrategy.BidItem> {
        return try { parseItems(request()) } catch (e: Exception) { emptyList() }
    }

    /** 归档必须验证 total 和唯一代码；原始响应逐字保留，不经过数值格式化。 */
    fun fetchOpening(): OpeningBidResponse {
        val raw = request()
        val json = JSONObject(raw)
        require(json.optInt("rc", -1) == 0) { "竞价接口返回错误" }
        val data = requireNotNull(json.optJSONObject("data")) { "竞价接口缺少 data" }
        val rows = requireNotNull(data.optJSONArray("diff")) { "竞价接口缺少 diff" }
        return OpeningBidResponse(raw, data.optInt("total", -1),
            (0 until rows.length()).map { rows.getJSONObject(it).optString("f12", "") },
            (0 until rows.length()).map { rows.getJSONObject(it).optLong("f124", 0L) * 1000L })
    }

    private fun request(): String {
        // 对齐仓鼠原版 URL: fid=f3, pz=6000, ut=token
        val fs = "m:1+t:2,m:0+t:6,m:0+t:80,m:1+t:23"
        val url = "$BASE_URL?pn=1&pz=6000&po=1&np=1&fltt=2&invt=2&fid=f3" +
            "&fs=" + URLEncoder.encode(fs, "UTF-8") +
            "&fields=" + URLEncoder.encode(FIELDS, "UTF-8") +
            "&ut=" + URLEncoder.encode(TOKEN, "UTF-8") + "&_=" + System.currentTimeMillis()

        return HttpExecutor.get(url, referer = "https://quote.eastmoney.com/",
            connectTimeoutMs = 8_000, readTimeoutMs = 8_000, headers = mapOf("Cache-Control" to "no-cache"))
    }

    internal fun parseItems(text: String): List<BidStrategy.BidItem> {
        val json = JSONObject(text)
        val data = json.optJSONObject("data") ?: return emptyList()
        val diff = data.optJSONArray("diff") ?: return emptyList()
        val items = ArrayList<BidStrategy.BidItem>()
        for (i in 0 until diff.length()) {
            val row = diff.optJSONObject(i) ?: continue
            val f2 = optDouble(row, "f2")
            val f17 = optDouble(row, "f17")
            val openPrice = if (f17 > 0) f17 else f2

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
        return items
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
}
