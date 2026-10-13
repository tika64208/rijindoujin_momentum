package com.example.momentum.data

import com.example.momentum.data.net.HttpExecutor
import org.json.JSONObject

/**
 * 东方财富涨停板/异动池数据源。
 * 对应 Python 版 ak.stock_zt_pool_em(date=...)，用于快速锁定万手单与寻龙诀的候选股池。
 */
object EastmoneyZtDataSource {

    data class ZtCandidate(
        val code: String,
        val name: String
    )

    /**
     * 获取指定日期的涨停股池。
     * @param date8 形如 "20261008"
     */
    fun fetchZtPool(date8: String): List<ZtCandidate> {
        val url = "https://push2ex.eastmoney.com/getTopicZTPool" +
            "?ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt&Pageindex=0&pagesize=1000&sort=fbt:asc&date=$date8"
        return try {
            val text = HttpExecutor.get(url, referer = "https://quote.eastmoney.com/")
            val json = JSONObject(text)
            val data = json.optJSONObject("data") ?: return emptyList()
            val pool = data.optJSONArray("pool") ?: return emptyList()
            val list = ArrayList<ZtCandidate>(pool.length())
            for (i in 0 until pool.length()) {
                val row = pool.optJSONObject(i) ?: continue
                val code = row.optString("c", "")
                val name = row.optString("n", "")
                if (code.isNotBlank()) {
                    list.add(ZtCandidate(code, name))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
