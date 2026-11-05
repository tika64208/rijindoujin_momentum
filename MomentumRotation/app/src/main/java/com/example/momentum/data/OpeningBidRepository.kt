package com.example.momentum.data

import android.content.Context
import android.util.AtomicFile
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.engine.BidStrategy
import com.example.momentum.util.BackupFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** 不读取或修改会随盘中刷新变化的 bid_data。DB 是档案原件，JSON/CSV 可重建。 */
class OpeningBidRepository(private val context: Context) {
    private val dao = AppDatabase.get(context).openingBidDao()
    private val store = object : OpeningBidStore {
        override suspend fun read(date: String) = dao.read(date)
        override suspend fun insertOnce(archive: OpeningBidArchive): OpeningBidArchive {
            dao.insertOnce(archive)
            return requireNotNull(read(archive.date))
        }
    }

    suspend fun capture(): OpeningBidArchive = withContext(Dispatchers.IO) {
        OpeningBidCapture(store, { EastmoneyBidDataSource.fetchOpening() },
            OpeningBidCsv::generate, { export(it) }).capture()
    }

    suspend fun restoreFiles() = withContext(Dispatchers.IO) {
        for (date in dao.dates()) dao.read(date)?.let { export(it) }
    }

    suspend fun todayStatus(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val archive = dao.metadata(OpeningBidTime.date(now))
        if (archive != null) {
            val time = Instant.ofEpochMilli(archive.receivedAt).atZone(OpeningBidTime.zone).toLocalTime().toString()
            val error = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("attempt_${archive.date}", null)
            "今日已冻结 ${archive.rowCount} 只 · 接收 $time（北京时间）" +
                if (error != null) "；文件导出待重试：$error" else ""
        } else {
            val local = Instant.ofEpochMilli(now).atZone(OpeningBidTime.zone)
            val error = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("attempt_${local.toLocalDate()}", null)
            when {
                error != null -> "今日未归档：$error"
                local.dayOfWeek.value > 5 -> "今日周末；下个工作日 09:30 采集"
                local.toLocalTime().isBefore(OpeningBidTime.target) -> "今日待采集 · 北京时间 09:30"
                OpeningBidTime.inWindow(now) -> "09:30 采集中，完成后显示冻结条数"
                else -> "今日缺少 09:30 档案，不能用盘中行情补造"
            }
        }
    }

    fun recordFailure(message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("attempt_${OpeningBidTime.date(System.currentTimeMillis())}", message).apply()
    }

    private suspend fun export(archive: OpeningBidArchive) {
        SimTradingDataRepository(context).freezeOpening(archive)
        val base = "竞价0930冻结_${archive.date}"
        val json = JSONObject().apply {
            put("schemaVersion", archive.schemaVersion)
            put("date", archive.date)
            put("timezone", "Asia/Shanghai")
            put("targetTime", "09:30:00")
            put("requestStartedAt", Instant.ofEpochMilli(archive.startedAt).toString())
            put("responseReceivedAt", Instant.ofEpochMilli(archive.receivedAt).toString())
            put("rowCount", archive.rowCount)
            put("marketScope", "沪深主板/创业板/科创板；不含北交所")
            put("scoringVersion", "BidStrategy-v1")
            put("fieldDictionary", JSONObject(OpeningBidCsv.fieldNames))
            // 字符串原样存储，以便完全还原接口响应。
            put("rawResponse", archive.rawJson)
        }.toString(2)
        for ((extension, content) in listOf("csv" to archive.csv, "json" to json)) {
            val name = "$base.$extension"
            val file = File(directory(context), name)
            val atomic = AtomicFile(file)
            val bytes = content.toByteArray(Charsets.UTF_8)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
            com.example.momentum.util.PublicDownloadWriter.write(context, "opening_0930", name, bytes,
                if (extension == "csv") "text/csv" else "application/json")
        }
        BidDistributionRepository(context).saveOpening(archive)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("attempt_${archive.date}").apply()
    }

    companion object {
        private const val PREFS = "opening_bid_archive"
        fun directory(context: Context) = File(context.filesDir, "opening_bid_snapshots").apply { mkdirs() }
        fun files(context: Context): List<BackupFileInfo> = directory(context).listFiles()
            .orEmpty().filter { it.extension in setOf("csv", "json") }.sortedByDescending { it.name }
            .map { BackupFileInfo(it, it.name, it.name.substringAfter("竞价0930冻结_").substringBeforeLast('.'), it.length(), it.lastModified()) }
    }
}

/** 原始字段不舍入，额外记录时间、冻结评分和版本；CSV 转义支持 Excel/Python。 */
object OpeningBidCsv {
    val fieldNames = mapOf(
        "f2" to "最新价", "f3" to "涨跌幅%", "f4" to "涨跌额", "f5" to "成交量_接口原值",
        "f6" to "成交额_元", "f8" to "换手率%", "f12" to "股票代码", "f14" to "股票名称",
        "f17" to "开盘价", "f20" to "总市值_元", "f100" to "行业", "f102" to "省份",
        "f103" to "概念", "f124" to "报价时间_Unix秒", "f615" to "竞价涨幅%", "f630" to "异动标志"
    )

    fun generate(response: OpeningBidResponse, startedAt: Long, receivedAt: Long): String {
        val rows = JSONObject(response.rawJson).getJSONObject("data").getJSONArray("diff")
        val fields = (0 until rows.length()).flatMap { i -> rows.getJSONObject(i).keys().asSequence().toList() }.toSortedSet().toList()
        val items = EastmoneyBidDataSource.parseItems(response.rawJson)
        val results = items.associate { item ->
            item.code to BidStrategy.compute(item.copy(latestPrice = item.openPrice, realChange = item.bidChange),
                BidStrategy.BidConfig.HIGH_WIN_RATE)
        }
        val headers = listOf("交易日期", "目标时间_北京时间", "请求开始_UTC", "响应接收_UTC", "全市场条数", "评分版本") +
            fields.map { fieldNames[it]?.let { name -> "$name[$it]" } ?: it } +
            listOf("冻结概率", "冻结可信度", "冻结评分", "冻结竞价换手率", "冻结估算竞价量")
        return buildString {
            append('\uFEFF')
            append(headers.joinToString(",", transform = ::cell)).append('\n')
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val result = requireNotNull(results[row.getString("f12")])
                val values = listOf(OpeningBidTime.date(startedAt), "09:30:00", Instant.ofEpochMilli(startedAt).toString(),
                    Instant.ofEpochMilli(receivedAt).toString(), response.total.toString(), "BidStrategy-v1") +
                    fields.map { if (row.isNull(it)) "" else row.get(it).toString() } +
                    listOf(result.probability.toString(), result.confidence.toString(), result.score.toString(),
                        result.bidTurnover.toString(), result.bidVolume.toString())
                append(values.joinToString(",", transform = ::cell)).append('\n')
            }
        }
    }

    private fun cell(value: String): String = if (value.any { it in ",\"\n\r" })
        "\"${value.replace("\"", "\"\"")}\"" else value
}
