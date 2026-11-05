package com.example.momentum.engine

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

enum class BidDistributionSource(val title: String, val fileTag: String) {
    OPENING_0930("09:30 冻结档案", "0930"),
    LEGACY("普通竞价历史", "普通历史")
}

data class BidDistributionBin(val label: String, val count: Int, val direction: Int)

data class BidDistribution(
    val date: String,
    val source: BidDistributionSource,
    val sourceTime: String,
    val bins: List<BidDistributionBin>,
    val total: Int,
    val up: Int,
    val flat: Int,
    val down: Int,
    val missing: Int,
    val average: Double?,
    val median: Double?,
    val savedAt: Long? = null,
    val definitionVersion: Int = 1
) {
    fun percentage(count: Int): Double = if (total > 0) count * 100.0 / total else 0.0
}

/** 只统计竞价涨幅 f615；不能用实时涨幅 f3 代替，也不能把无效值当作平开。 */
object BidDistributionEngine {
    fun fromRaw(date: String, sourceTime: String, rawJson: String): BidDistribution {
        val data = JSONObject(rawJson).getJSONObject("data")
        val rows = data.getJSONArray("diff")
        require(data.getInt("total") == rows.length()) { "冻结档案全市场条数不一致" }
        val changes = (0 until rows.length()).map { i ->
            val value = rows.getJSONObject(i).opt("f615")
            when (value) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()
                else -> null
            }?.takeIf { it.isFinite() }
        }
        return calculate(date, BidDistributionSource.OPENING_0930, sourceTime, changes)
    }

    fun calculate(date: String, source: BidDistributionSource, sourceTime: String,
                  changes: List<Double?>): BidDistribution {
        val counts = IntArray(24)
        val valid = mutableListOf<Double>()
        for (value in changes) {
            val v = value?.takeIf { it.isFinite() }
            val index = when {
                v == null -> 23
                v < -10.0 -> 0
                v < 0.0 -> kotlin.math.floor(v).toInt() + 11
                v == 0.0 -> 11
                v < 1.0 -> 12
                v < 10.0 -> kotlin.math.floor(v).toInt() + 12
                else -> 22
            }
            counts[index]++
            if (v != null) valid.add(v)
        }
        val bins = buildList {
            add(BidDistributionBin("< -10%", counts[0], -1))
            for (lower in -10..-1) add(BidDistributionBin("[$lower, ${lower + 1})%", counts[lower + 11], -1))
            add(BidDistributionBin("0%", counts[11], 0))
            add(BidDistributionBin("(0, 1)%", counts[12], 1))
            for (lower in 1..9) add(BidDistributionBin("[$lower, ${lower + 1})%", counts[lower + 12], 1))
            add(BidDistributionBin("≥ 10%", counts[22], 1))
            add(BidDistributionBin("缺失 / 无效", counts[23], 0))
        }
        val sorted = valid.sorted()
        val median = if (sorted.isEmpty()) null else {
            val mid = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[mid] else sorted[mid - 1] / 2.0 + sorted[mid] / 2.0
        }
        return BidDistribution(date, source, sourceTime, bins, changes.size,
            valid.count { it > 0 }, valid.count { it == 0.0 }, valid.count { it < 0 }, counts[23],
            valid.takeIf { it.isNotEmpty() }?.let { values -> values.sumOf { it / values.size } }, median)
    }
}

/** 存盘使用固定口径和原始计数，不从显示百分比反推数量。 */
object BidDistributionFormat {
    fun json(report: BidDistribution): String = JSONObject().apply {
        put("definitionVersion", report.definitionVersion)
        put("date", report.date)
        put("source", report.source.name)
        put("sourceTime", report.sourceTime)
        put("savedAt", report.savedAt)
        put("total", report.total)
        put("up", report.up); put("flat", report.flat); put("down", report.down); put("missing", report.missing)
        put("average", report.average ?: JSONObject.NULL)
        put("median", report.median ?: JSONObject.NULL)
        put("bins", JSONArray().apply {
            report.bins.forEach { bin -> put(JSONObject().apply {
                put("label", bin.label); put("count", bin.count); put("direction", bin.direction)
            }) }
        })
    }.toString(2)

    fun parse(text: String): BidDistribution {
        val obj = JSONObject(text)
        require(obj.getInt("definitionVersion") == 1) { "不支持的分布存盘版本" }
        val bins = obj.getJSONArray("bins")
        val report = BidDistribution(obj.getString("date"), BidDistributionSource.valueOf(obj.getString("source")),
            obj.getString("sourceTime"), (0 until bins.length()).map { i -> bins.getJSONObject(i).let {
                BidDistributionBin(it.getString("label"), it.getInt("count"), it.getInt("direction"))
            } }, obj.getInt("total"), obj.getInt("up"), obj.getInt("flat"), obj.getInt("down"), obj.getInt("missing"),
            if (obj.isNull("average")) null else obj.getDouble("average"),
            if (obj.isNull("median")) null else obj.getDouble("median"),
            if (obj.isNull("savedAt")) null else obj.getLong("savedAt"))
        require(report.total >= 0 && report.bins.size == 24 && report.bins.all { it.count >= 0 } &&
            report.bins.sumOf { it.count } == report.total && report.up >= 0 && report.flat >= 0 &&
            report.down >= 0 && report.missing >= 0 && report.up + report.flat + report.down + report.missing == report.total) {
            "分布存盘计数不一致"
        }
        return report
    }

    fun csv(report: BidDistribution): String = buildString {
        append('\uFEFF').append("交易日期,数据来源,原数据时间,存盘时间_UTC,统计口径版本,全样本数,区间,股票数量,占全样本比例%\n")
        val saved = report.savedAt?.let { Instant.ofEpochMilli(it).toString() }.orEmpty()
        for (bin in report.bins) {
            append(listOf(report.date, report.source.title, report.sourceTime, saved, report.definitionVersion.toString(),
                report.total.toString(), bin.label, bin.count.toString(),
                String.format(Locale.ROOT, "%.6f", report.percentage(bin.count)))
                .joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" }).append('\n')
        }
    }
}
