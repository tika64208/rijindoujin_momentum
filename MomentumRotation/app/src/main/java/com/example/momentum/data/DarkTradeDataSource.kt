package com.example.momentum.data

import com.example.momentum.data.net.HttpExecutor
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.HashMap

/**
 * 东方财富暗盘(大宗/场外)交易数据源。
 *
 * 接口: quotederivates.eastmoney.com/datacenter/darktrade
 * 支持按 code 查询单只股票某日的暗盘交易记录。
 * 该子域未被反爬(与 push2.eastmoney.com 行情子域不同)。
 */
object DarkTradeDataSource {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"


    /**
     * 内存级缓存: date(yyyyMMdd) → 当天全市场暗盘列表。
     * App 存活期间有效, 避免反复翻页拉全量。
     */
    private val cacheByDate = HashMap<String, List<DarkTradeRecord>>()

    /** 清空缓存(日期切换/手动刷新时调用)。 */
    fun clearCache() { cacheByDate.clear() }

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
        val page: Int
    )

    fun fetchAllDarkTradePage(date: String, page: Int, pageSize: Int = 50): DarkTradePage {
        val ts = System.currentTimeMillis()
        val url = "https://quotederivates.eastmoney.com/datacenter/darktrade" +
            "?version=100&date=$date&NumPerPage=$pageSize&sortflag=6&desc=1&market=0" +
            "&label=%E4%B8%AA%E8%82%A1&StartPage=$page&cb=cb&_=$ts"

        return try {
            val text = httpGet(url)
            val start = text.indexOf('(')
            val end = text.lastIndexOf(')')
            if (start < 0 || end <= start) return DarkTradePage(emptyList(), -1, page)
            val json = JSONObject(text.substring(start + 1, end))
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
            DarkTradePage(emptyList(), -1, page)
        }
    }

    /**
     * 一次性拉取某日全市场暗盘的全部记录(自动翻页到完)。
     * 翻页途中若遇空页(反爬/限流)会重试, 而不是提前中断。
     * @param date yyyyMMdd
     */
    fun fetchAllDarkTrade(date: String): List<DarkTradeRecord> {
        // 缓存命中直接返回
        cacheByDate[date]?.let { if (it.isNotEmpty()) return it }
        val result = ArrayList<DarkTradeRecord>()
        var page = 1
        var emptyStreak = 0
        var totalPages = Int.MAX_VALUE

        while (page <= totalPages) {
            val p = fetchAllDarkTradePage(date, page)

            if (p.records.isEmpty()) {
                // 空页: 连续空页计数, 超过阈值才放弃(容忍偶发限流)
                emptyStreak++
                if (emptyStreak >= 3) break
                page++
                continue
            }

            emptyStreak = 0
            result.addAll(p.records)

            // 首次拿到总数时, 推算总页数(每页50)
            if (totalPages == Int.MAX_VALUE && p.totalCount > 0) {
                totalPages = (p.totalCount + 49) / 50  // 向上取整
            }
            // 已拿满总数则结束
            if (p.totalCount > 0 && result.size >= p.totalCount) break
            page++
            if (page > 250) break
            // 页间延迟: 降低东财反爬风险
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
        }
        if (result.isNotEmpty()) cacheByDate[date] = result
        return result
    }

    /**
     * 查询近 N 天的暗盘交易记录(逐日查询, 只保留有数据的日期)。
     * @param days 回看天数
     */
    fun fetchHistory(code: String, days: Int = 60): List<DarkTradeRecord> {
        val result = ArrayList<DarkTradeRecord>()
        val today = LocalDate.now()
        for (offset in 0..days) {
            val d = today.minusDays(offset.toLong())
            val dateStr = d.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            val records = fetchDarkTrade(code, dateStr)
            if (records.isNotEmpty()) {
                result.addAll(records)
            }
        }
        return result
    }

    /**
     * 从某日全市场暗盘列表中找出目标股票的记录(不传 code 接口, 规避沪市 code 查不到的怪癖)。
     *
     * 翻页直到命中目标 code 即返回(单日全市场约 107 页, 但目标股通常靠前一页就能命中);
     * 翻满仍未命中则返回空。
     *
     * @param code 6位代码
     * @param date yyyyMMdd
     * @param maxPages 最多翻多少页(防单股请求无限放大, 默认 30)
     */
    fun fetchDarkTradeByCode(code: String, date: String, maxPages: Int = 120): List<DarkTradeRecord> {
        // 优先从缓存拿(缓存当日全量, 本地过滤秒出)
        val cached = cacheByDate[date]
        if (cached != null && cached.isNotEmpty()) {
            return cached.filter { it.code == code }
        }
        // 缓存miss: 全量翻页拉取并回写缓存
        val all = fetchAllDarkTrade(date)
        if (all.isNotEmpty()) {
            cacheByDate[date] = all
            return all.filter { it.code == code }
        }
        // 全量拉取失败: 逐页翻找(退化)
        var page = 1
        var emptyStreak = 0
        while (page <= maxPages) {
            val p = fetchAllDarkTradePage(date, page)
            if (p.records.isEmpty()) {
                emptyStreak++
                if (emptyStreak >= 5) return emptyList()
                page++
                continue
            }
            emptyStreak = 0
            val hit = p.records.filter { it.code == code }
            if (hit.isNotEmpty()) return hit
            // 已拿满当日总数仍未命中
            if (p.totalCount > 0 && (page * 50) >= p.totalCount) return emptyList()
            page++
        }
        return emptyList()
    }

    /**
     * 用全市场接口 + 本地过滤, 查询近 N 日目标股票的暗盘记录。
     * 相比 fetchHistory(单股 code 接口, 沪市会空), 本方法沪市深市都能查到; 且跳过周末。
     * @param days 回看自然日天数(内部跳过周六日)
     */
    fun fetchHistoryByCode(code: String, days: Int = 60): List<DarkTradeRecord> {
        val result = ArrayList<DarkTradeRecord>()
        val today = LocalDate.now()
        for (offset in 0..days) {
            val d = today.minusDays(offset.toLong())
            // 跳过周末(暗盘仅交易日有数据)
            val dow = d.dayOfWeek
            if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) continue
            val dateStr = d.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            result.addAll(fetchDarkTradeByCode(code, dateStr))
        }
        return result
    }

    /**
     * 查询最近10个交易日的暗盘记录(无数据的日期补 netAmount=0, 用于图表绘制)。
     * @param code 6位代码
     */
    fun fetchRecent10Days(code: String): List<DarkTradeRecord> {
        val result = ArrayList<DarkTradeRecord>()
        var d = LocalDate.now()
        var count = 0
        while (count < 10) {
            // 跳过周末
            val dow = d.dayOfWeek
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                val dateStr = d.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                val records = fetchDarkTradeByCode(code, dateStr)
                if (records.isNotEmpty()) {
                    result.addAll(records)
                } else {
                    // 无数据: 补一条 totalAmount=0 的虚记录
                    result.add(DarkTradeRecord(
                        date = formattedDate(dateStr),
                        attr = "", code = code, name = "", industry = "", concept = "",
                        buyAmount = 0.0, sellAmount = 0.0, totalAmount = 0.0,
                        volume = 0.0, ratio = 0.0
                    ))
                }
                count++
            }
            d = d.minusDays(1)
        }
        return result
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
