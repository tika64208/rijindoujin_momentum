package com.example.momentum.engine

import org.json.JSONArray
import org.json.JSONObject

object SimTradingJournal {
    fun post(current: SimAccountState, settled: SimDayRecord): SimAccountState {
        require(settled.status == "SETTLED")
        val history = (listOf(settled) + current.historyRecords.filter { it.date != settled.date }).sortedByDescending { it.date }
        val totalProfit = settled.endCapital - current.initialCapital
        return current.copy(currentCapital = settled.endCapital, cash = settled.endCapital, totalProfit = totalProfit,
            totalReturnPct = if (current.initialCapital > 0) totalProfit / current.initialCapital * 100 else 0.0,
            totalFeePaid = history.sumOf { it.totalFee }, totalSlippagePaid = history.sumOf { it.totalSlippage },
            winDayCount = history.count { it.orders.isNotEmpty() && it.profit > 0 },
            totalDayCount = history.count { it.orders.isNotEmpty() }, todayRecord = settled, historyRecords = history)
    }
}

object SimAccountCodec {
    fun encode(state: SimAccountState): JSONObject = JSONObject().apply {
        put("initialCapital", state.initialCapital); put("currentCapital", state.currentCapital); put("cash", state.cash)
        put("totalProfit", state.totalProfit); put("totalReturnPct", state.totalReturnPct)
        put("totalFeePaid", state.totalFeePaid); put("totalSlippagePaid", state.totalSlippagePaid)
        put("winDayCount", state.winDayCount); put("totalDayCount", state.totalDayCount)
        put("feeConfig", state.feeConfig.toJson())
        state.todayRecord?.let { put("todayRecord", it.toJson()) }
        put("historyRecords", JSONArray().apply { state.historyRecords.forEach { put(it.toJson()) } })
    }

    fun decode(root: JSONObject): SimAccountState {
        val histories = root.optJSONArray("historyRecords") ?: JSONArray()
        val initial = root.optDouble("initialCapital", 1_000_000.0)
        return SimAccountState(initialCapital = initial, currentCapital = root.optDouble("currentCapital", initial),
            cash = root.optDouble("cash", initial), totalProfit = root.optDouble("totalProfit", 0.0),
            totalReturnPct = root.optDouble("totalReturnPct", 0.0), totalFeePaid = root.optDouble("totalFeePaid", 0.0),
            totalSlippagePaid = root.optDouble("totalSlippagePaid", 0.0), winDayCount = root.optInt("winDayCount", 0),
            totalDayCount = root.optInt("totalDayCount", 0), feeConfig = root.optJSONObject("feeConfig")?.let {
                SimTradingFeeConfig.fromJson(it) } ?: SimTradingFeeConfig(),
            todayRecord = root.optJSONObject("todayRecord")?.let { SimDayRecord.fromJson(it) },
            historyRecords = (0 until histories.length()).map { SimDayRecord.fromJson(histories.getJSONObject(it)) })
    }
}

data class SimBacktestReport(
    val generatedAt: Long,
    val result: SimAccountState,
    val notes: Map<String, String>,
    val skipped: Map<String, String>,
    val includeUnverified: Boolean,
    val definitionVersion: String = "stored-signal-unified-v1"
) {
    fun toJson() = JSONObject().apply {
        put("generatedAt", generatedAt); put("result", SimAccountCodec.encode(result))
        put("notes", JSONObject(notes)); put("skipped", JSONObject(skipped))
        put("includeUnverified", includeUnverified); put("definitionVersion", definitionVersion)
    }
    companion object {
        fun fromJson(root: JSONObject): SimBacktestReport {
            fun map(key: String): Map<String, String> = root.getJSONObject(key).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            return SimBacktestReport(root.getLong("generatedAt"), SimAccountCodec.decode(root.getJSONObject("result")),
                map("notes"), map("skipped"), root.optBoolean("includeUnverified", true), root.getString("definitionVersion"))
        }
    }
}

fun SimDayRecord.priceQualityLabel(): String = when (priceQuality) {
    "CLOSE_CONFIRMED" -> "收盘价已确认"
    "INTRADAY_ESTIMATE" -> "行情估算"
    "NO_TRADES" -> "无交易"
    else -> "历史价格未验证"
}

fun SimDayRecord.signalQualityLabel(): String = when (signalSource) {
    "OPENING_0930" -> "09:30 冻结评分"
    "LEGACY_EARLY" -> "沿用早盘评分 · 原始输入未验证"
    else -> "历史评分未验证"
}
