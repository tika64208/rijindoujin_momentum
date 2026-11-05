package com.example.momentum.data

import android.content.Context
import android.util.AtomicFile
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.engine.BidDistribution
import com.example.momentum.engine.BidDistributionEngine
import com.example.momentum.engine.BidDistributionFormat
import com.example.momentum.engine.BidDistributionSource
import com.example.momentum.util.BackupFileInfo
import com.example.momentum.util.PublicDownloadWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** 历史看板仅查询本地全量数据，绝不把列表的筛选结果或前 500 条当作样本。 */
class BidDistributionRepository(private val context: Context) {
    private val db = AppDatabase.get(context)

    suspend fun dates(): List<String> = withContext(Dispatchers.IO) {
        (db.openingBidDao().dates() + db.bidDao().getDates(Int.MAX_VALUE) +
            files(context).map { it.date }).distinct().sortedDescending()
    }

    suspend fun load(date: String): BidDistribution? = withContext(Dispatchers.IO) {
        readSaved(date, BidDistributionSource.OPENING_0930)?.let { return@withContext it }
        val raw = db.openingBidDao().raw(date)
        if (raw != null) return@withContext BidDistributionEngine.fromRaw(date,
            beijingTime(raw.receivedAt), raw.rawJson)
        readSaved(date, BidDistributionSource.LEGACY)?.let { return@withContext it }
        val rows = db.bidDao().getByDate(date)
        if (rows.isEmpty()) return@withContext null
        BidDistributionEngine.calculate(date, BidDistributionSource.LEGACY,
            rows.map { it.savedAt }.distinct().sorted().joinToString(" / "), rows.map { it.bidChange })
    }

    /** 同日同来源首次存盘后不被刷新覆盖；普通历史和 09:30 冻结档案分别保存。 */
    suspend fun save(report: BidDistribution): BidDistribution = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = readSaved(report.date, report.source) ?: report.copy(savedAt = System.currentTimeMillis())
            writePrivate(File(directory(context), fileName(saved, "json")), BidDistributionFormat.json(saved))
            writePrivate(File(directory(context), fileName(saved, "csv")), BidDistributionFormat.csv(saved))
            for (extension in listOf("csv", "json")) {
                val file = File(directory(context), fileName(saved, extension))
                PublicDownloadWriter.write(context, "bid_distribution", file.name, file.readBytes(),
                    if (extension == "csv") "text/csv" else "application/json")
            }
            saved
        }
    }

    suspend fun saveOpening(archive: OpeningBidArchive) = save(BidDistributionEngine.fromRaw(
        archive.date, beijingTime(archive.receivedAt), archive.rawJson))

    fun csvFile(report: BidDistribution) = File(directory(context), fileName(report, "csv"))

    private fun readSaved(date: String, source: BidDistributionSource): BidDistribution? {
        val file = File(directory(context), "竞价涨幅分布_${date}_${source.fileTag}.json")
        if (!file.exists()) return null
        val report = BidDistributionFormat.parse(file.readText())
        require(report.date == date && report.source == source && report.savedAt != null) { "分布档案日期或来源不一致" }
        return report
    }

    private fun writePrivate(file: File, content: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    companion object {
        private val mutex = Mutex()
        private fun directory(context: Context) = File(context.filesDir, "bid_distribution").apply { mkdirs() }
        private fun fileName(report: BidDistribution, extension: String) =
            "竞价涨幅分布_${report.date}_${report.source.fileTag}.$extension"
        private fun beijingTime(millis: Long) = Instant.ofEpochMilli(millis).atZone(OpeningBidTime.zone)
            .toLocalDateTime().toString() + " 北京时间"
        fun files(context: Context): List<BackupFileInfo> = directory(context).listFiles().orEmpty()
            .filter { it.extension in setOf("csv", "json") && it.name.startsWith("竞价涨幅分布_") }
            .sortedByDescending { it.name }
            .map { BackupFileInfo(it, it.name, it.name.substringAfter("竞价涨幅分布_").substringBefore('_'), it.length(), it.lastModified()) }
    }
}
