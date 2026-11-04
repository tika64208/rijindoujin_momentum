package com.example.momentum.data

import com.example.momentum.data.net.HttpExecutor
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 东方财富暗盘(大宗/场外)交易数据源。
 *
 * 接口: quotederivates.eastmoney.com/datacenter/darktrade
 * 支持按 code 查询单只股票某日的暗盘交易记录。
 * 该子域未被反爬(与 push2.eastmoney.com 行情子域不同)。
 */
object DarkTradeDataSource {

    /** 一笔暗盘交易记录(东财 quotederivates darktrade 接口)。
     *
     * 字段映射:
     *   buyAmount   = 字段6 = 净买(带符号: 正=净买入, 负=净卖出)
     *   sellAmount  = 字段7 = 净卖(带符号)
     *   totalAmount = 字段8 = 后台 6+7
     *   netAmount   = |净买| - |净卖|  ← 用户要求的净流入口径
     */
    data class DarkTradeRecord(
        val date: String,      // yyyy-MM-dd
        val attr: String,      // 属性(0/1, 方向)
        val code: String,      // 6位代码(全市场列表才有, 按date+code查时为空)
        val name: String,      // 名称(全市场列表才有)
        val industry: String,  // 行业(全市场列表才有)
        val concept: String,   // 概念(全市场列表才有)
        val buyAmount: Double,  // 净买(字段6, 带符号)
        val sellAmount: Double, // 净卖(字段7, 带符号)
        val totalAmount: Double, // 字段8(后台6+7)
        val volume: Double,     // 成交量
        val ratio: Double      // 占流通比/占比
    ) {
        /** 净流入 = |净买| - |净卖| */
        val netAmount: Double get() = kotlin.math.abs(buyAmount) - kotlin.math.abs(sellAmount)
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
                        code = item.optString("4", ""),
                        name = item.optString("16", ""),
                        industry = item.optString("17", ""),
                        concept = item.optString("18", ""),
                        buyAmount = item.optDouble("6", 0.0),
                        sellAmount = item.optDouble("7", 0.0),
                        totalAmount = item.optDouble("8", 0.0),
                        volume = item.optDouble("13", 0.0),
                        ratio = item.optDouble("14", 0.0)
                    )
                )
            }
            records
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 拉取某日全市场暗盘列表的某一页(不传 code, 规避沪市 code 查不到的怪癖)。
     * @param date yyyyMMdd
     * @param page 页码(从 1 开始)
     * @param pageSize 每页条数(接口上限约 50, 超 50 会返回空, 故固定 50)
     * @return 该页记录; 同时通过 TotalCount 报告当日总条数(-1 表示未知)
     */
    data class DarkTradePage(
        val records: List<DarkTradeRecord>,
        val totalCount: Int,
        val page: Int,
        val error: String? = null
    )

    fun fetchAllDarkTradePage(date: String, page: Int, pageSize: Int = 50): DarkTradePage {
        val ts = System.currentTimeMillis()
        val url = "https://quotederivates.eastmoney.com/datacenter/darktrade" +
            // 全量下载使用默认顺序；按盘中买卖金额排序会在翻页时重排或产生大量同值页。
            "?version=100&date=$date&NumPerPage=$pageSize&sortflag=0&desc=0&market=0" +
            "&label=%E4%B8%AA%E8%82%A1&StartPage=$page&cb=cb&_=$ts"

        return try {
            val text = httpGet(url)
            val start = text.indexOf('(')
            val end = text.lastIndexOf(')')
            if (start < 0 || end <= start) return DarkTradePage(emptyList(), -1, page)
            val json = JSONObject(text.substring(start + 1, end))
            if (json.optString("1") != date) {
                return DarkTradePage(emptyList(), -1, page, "接口返回日期与请求日期不一致")
            }
            val totalCount = json.optInt("2", -1)
            val dataArr = json.optJSONArray("data") ?: return DarkTradePage(emptyList(), totalCount, page)
            val records = ArrayList<DarkTradeRecord>(dataArr.length())
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                records.add(
                    DarkTradeRecord(
                        date = formattedDate(date),
                        attr = item.optString("3", ""),
                        code = item.optString("4", ""),
                        name = item.optString("16", ""),
                        industry = item.optString("17", ""),
                        concept = item.optString("18", ""),
                        buyAmount = item.optDouble("6", 0.0),
                        sellAmount = item.optDouble("7", 0.0),
                        totalAmount = item.optDouble("8", 0.0),
                        volume = item.optDouble("13", 0.0),
                        ratio = item.optDouble("14", 0.0)
                    )
                )
            }
            DarkTradePage(records, totalCount, page)
        } catch (e: Exception) {
            DarkTradePage(emptyList(), -1, page, e.message ?: "网络或解析失败")
        }
    }

    /** 完整性由分页器校验，失败保留部分结果但不伪装成全量成功。 */
    suspend fun downloadAll(date: String): DarkTradeDownload = withContext(Dispatchers.IO) {
        val key = darkTradeDate(date)
        DarkTradePaginator().download(
            fetchPage = { page -> fetchAllDarkTradePage(key, page) },
            pause = { delay(150) }
        )
    }

    private fun formattedDate(yyyymmdd: String): String {
        return if (yyyymmdd.length == 8) {
            "${yyyymmdd.substring(0, 4)}-${yyyymmdd.substring(4, 6)}-${yyyymmdd.substring(6, 8)}"
        } else yyyymmdd
    }

    private fun httpGet(urlStr: String): String =
        HttpExecutor.get(
            urlStr = urlStr,
            referer = "https://data.eastmoney.com/",
            charset = HttpExecutor.GBK,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 10_000
        )
}
