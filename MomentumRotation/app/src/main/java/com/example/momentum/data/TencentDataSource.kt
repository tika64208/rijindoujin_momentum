package com.example.momentum.data

import com.example.momentum.model.DailyBar
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

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

    private const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

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
            // qt.gtimg.cn 返回 GBK 编码, 必须按 GBK 读, 否则名称乱码
            val text = httpGetText("https://qt.gtimg.cn/q=$symbol", charset = Charset.forName("GBK"))
            // 形如 v_sh603991="1~领先股份~603991~...~";
            // 取 ="... 之间的内容, 再按 ~ 分割, 第2段是名称
            val body = text.substringAfter("=\"").substringBefore("\"")
            val parts = body.split("~")
            if (parts.size > 1) parts[1].replace(" ", "") else symbol
        } catch (e: Exception) {
            symbol
        }
    }

    /**
     * 分时数据点。
     */
    data class MinutePoint(
        val time: String,    // 如 "09:30"
        val price: Double,
        val volume: Double,
        val amount: Double,
        val avgPrice: Double = price,  // 均价(个股无此字段, 板块有)
        val preClose: Double = 0.0     // 昨日收盘价
    )

    /**
     * 获取个股分时图数据(腾讯接口)。
     * 返回今日分时数据点列表。
     */
    fun fetchMinuteData(symbol: String): List<MinutePoint> {
        return try {
            val json = httpGetJson("https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=$symbol")
            val node = json.optJSONObject("data")?.optJSONObject(symbol) ?: return emptyList()
            val rows = node.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()
            val qtArr = node.optJSONObject("qt")?.optJSONArray(symbol)
            val preClose = qtArr?.optString(4)?.toDoubleOrNull() ?: 0.0
            val points = ArrayList<MinutePoint>(rows.length())
            val isKcb = symbol.startsWith("sh688") || symbol.startsWith("688")
            var prevVol = 0.0
            var prevAmt = 0.0
            for (i in 0 until rows.length()) {
                val cols = rows.getString(i).split(" ")
                if (cols.size >= 4) {
                    val price = cols[1].toDoubleOrNull() ?: 0.0
                    val cumVol = cols[2].toDoubleOrNull() ?: 0.0
                    val cumAmt = cols[3].toDoubleOrNull() ?: 0.0
                    val minuteVol = if (i > 0 && cumVol >= prevVol) cumVol - prevVol else cumVol
                    val minuteAmt = if (i > 0 && cumAmt >= prevAmt) cumAmt - prevAmt else cumAmt
                    prevVol = cumVol
                    prevAmt = cumAmt

                    // 均价计算：科创板(688)成交量单位为股，主板/创业板为手(需*100)
                    // 同时校验: 若 cumAmt / (cumVol * price) 远小于 10，则单位为股
                    val isUnitShare = isKcb || (cumVol > 0 && price > 0 && (cumAmt / (cumVol * price)) < 10.0)
                    val avg = when {
                        cumVol <= 0 -> price
                        isUnitShare -> cumAmt / cumVol
                        else -> cumAmt / (cumVol * 100.0)
                    }
                    points.add(
                        MinutePoint(
                            time = cols[0].substring(0..1) + ":" + cols[0].substring(2..3),
                            price = price,
                            volume = minuteVol,
                            amount = minuteAmt,
                            avgPrice = avg,
                            preClose = preClose
                        )
                    )
                }
            }
            points
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取板块分时图数据(东财接口)。
     * @param boardCode 东财板块代码, 如 "BK0477"
     */
    fun fetchBoardMinuteData(boardCode: String): List<MinutePoint> {
        if (boardCode.isBlank()) return emptyList()
        return try {
            val text = httpGetText(
                "https://push2.eastmoney.com/api/qt/stock/trends2/get" +
                    "?secid=90.$boardCode&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57" +
                    "&iscr=0&ndays=1",
                referer = "https://quote.eastmoney.com/",
                userAgent = UA_MOBILE
            )
            val json = JSONObject(text)
            val dataObj = json.optJSONObject("data") ?: return emptyList()
            val preClose = dataObj.optDouble("preClose", 0.0)
            val trends = dataObj.optJSONArray("trends") ?: return emptyList()
            val points = ArrayList<MinutePoint>(trends.length())
            for (i in 0 until trends.length()) {
                val cols = trends.getString(i).split(",")
                if (cols.size >= 7) {
                    val timeFull = cols[0].trim()    // "2026-09-01 09:30"
                    val time = if (timeFull.length >= 16) timeFull.substring(11, 16) else timeFull
                    points.add(
                        MinutePoint(
                            time = time,
                            price = cols[1].toDoubleOrNull() ?: 0.0,
                            volume = cols[5].toDoubleOrNull() ?: 0.0,
                            amount = cols[6].toDoubleOrNull() ?: 0.0,
                            avgPrice = cols[2].toDoubleOrNull() ?: 0.0,
                            preClose = preClose
                        )
                    )
                }
            }
            points
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 板块映射表: 股票代码 → 东财板块代码(静态映射)。
     * 覆盖常见股票, 未知股票返回空(板块分时图不显示)。
     */
    fun getBoardCode(code: String): String {
        return when (code) {
            // 领先科技 5 股(电子/元器件/PCB)
            "603991" -> "BK0448"  // 电子元件
            "300285" -> "BK0448"  // 电子元件
            "600183" -> "BK1036"  // 半导体
            "300903" -> "BK1036"  // 半导体
            "300054" -> "BK0448"  // 电子元件
            // 示例蓝筹
            "600519" -> "BK0477"  // 酿酒
            "000858" -> "BK0477"  // 酿酒
            "601318" -> "BK0451"  // 保险
            "600036" -> "BK0476"  // 银行
            "000333" -> "BK2020"  // 家电
            "600900" -> "BK0420"  // 电力
            "002594" -> "BK1036"  // 半导体
            "300750" -> "BK1036"  // 半导体
            "601899" -> "BK0473"  // 贵金属
            "600276" -> "BK0465"  // 医药
            else -> ""  // 未知股票
        }
    }

    fun getBoardName(boardCode: String): String = when (boardCode) {
        "BK0448" -> "电子元件"
        "BK1036" -> "半导体"
        "BK0477" -> "酿酒"
        "BK0451" -> "保险"
        "BK0476" -> "银行"
        "BK2020" -> "家电"
        "BK0420" -> "电力"
        "BK0473" -> "贵金属"
        "BK0465" -> "医药"
        else -> boardCode
    }

    /**
     * 股票所属的行业/板块指数(用腾讯行业大类指数近似, 分时接口稳定可用)。
     * 返回 (指数symbol, 指数名称)。
     */
    fun getBenchmarkIndex(code: String): Pair<String, String> = when {
        // 电子/元器件/半导体/PCB → CSSW电子指数(申万电子, 有分时)
        code in setOf("603991", "300285", "600183", "300903", "300054", "002594", "300750") ->
            "sz399811" to "CSSW电子"
        // 白酒/食品 → 细分消费
        code in setOf("600519", "000858") -> "sh000811" to "细分消费"
        // 金融 → 上证金融
        code in setOf("601318", "600036") -> "sh000038" to "上证金融"
        // 家用电器 → 可选消费
        code == "000333" -> "sh000989" to "全指可选(家电)"
        // 电力 → 绿色电力
        code == "600900" -> "sh000827" to "中证环保(电力)"
        // 有色/贵金属
        code == "601899" -> "sh000819" to "有色金属"
        // 医药
        code == "600276" -> "sh000933" to "中证医药"
        // 默认按市场
        code.startsWith("688") -> "sh000688" to "科创50"
        code.startsWith("300") || code.startsWith("301") -> "sz399006" to "创业板指"
        code.startsWith("6") -> "sh000001" to "上证指数"
        else -> "sz399001" to "深证成指"
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
    internal fun unescapeUnicode(input: String): String {
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

    private fun httpGetText(
        urlStr: String,
        referer: String? = null,
        userAgent: String = UA,
        charset: Charset = Charsets.UTF_8
    ): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Referer", referer ?: "https://gu.qq.com/")
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")

            val code = conn.responseCode
            if (code != 200) {
                val extraHint = when (code) {
                    501 -> " (触发腾讯网关WAF反爬验证重定向)"
                    403 -> " (腾讯接口403防盗链/反爬)"
                    else -> ""
                }
                throw RuntimeException("HTTP $code$extraHint [URL: $urlStr]")
            }
            val reader = BufferedReader(conn.inputStream.reader(charset))
            return reader.use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
