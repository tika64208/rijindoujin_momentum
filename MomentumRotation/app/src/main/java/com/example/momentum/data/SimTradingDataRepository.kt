package com.example.momentum.data

import android.content.Context
import androidx.room.withTransaction
import com.example.momentum.data.db.*
import com.example.momentum.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class SimTradingDayData(
    val date: String,
    val rows: List<BidDataEntity>,
    val signalSource: String,
    val scoringVersion: String,
    val priceQuality: String,
    val priceAsOf: String,
    val issues: List<String> = emptyList()
) {
    fun metadata(record: SimDayRecord) = record.copy(signalSource = signalSource, scoringVersion = scoringVersion,
        priceQuality = priceQuality, priceAsOf = priceAsOf)
}

class SimTradingDataRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val dao = db.bidSimulationDao()

    suspend fun freezeOpening(archive: OpeningBidArchive) = withContext(Dispatchers.IO) {
        val existing = dao.signals(archive.date).count { it.source == "OPENING_0930" }
        if (existing == archive.rowCount) return@withContext
        val signals = BidSignalSnapshots.opening(archive)
        val items = EastmoneyBidDataSource.parseItems(archive.rawJson).associateBy { it.code }
        val prices = signals.map { s ->
            val item = items.getValue(s.signal.code)
            val quoteAt = org.json.JSONObject(s.rawInputJson).optLong("f124", 0L) * 1000L
            BidPriceSnapshotEntity(item.code, archive.date, item.latestPrice, item.realChange, item.volume, item.amount,
                item.turnover, quoteAt, archive.receivedAt, priceQuality(archive.date, quoteAt, archive.receivedAt))
        }
        db.withTransaction { dao.insertSignals(signals); dao.insertInitialPrices(prices) }
    }

    suspend fun dates(): List<String> = withContext(Dispatchers.IO) {
        (dao.dates() + db.openingBidDao().dates() + db.bidDao().getDates(Int.MAX_VALUE)).distinct().sorted()
    }

    suspend fun loadDay(date: String, config: BidStrategy.BidConfig = BidStrategy.BidConfig.HIGH_WIN_RATE): SimTradingDayData =
        withContext(Dispatchers.IO) {
            require(LocalDate.parse(date).dayOfWeek.value <= 5) { "非工作日数据不参与模拟回测" }
            val now = Instant.now().atZone(OpeningBidTime.zone)
            require(date <= now.toLocalDate().toString()) { "未来日期数据不参与回测" }
            require(date != now.toLocalDate().toString() || !now.toLocalTime().isBefore(LocalTime.of(9, 30))) {
                "09:30 前早盘评分尚未冻结"
            }
            // 旧版本已归档的 CSV 也从原分数导入，不重算。
            val openingMeta = db.openingBidDao().metadata(date)
            if (openingMeta != null && dao.signals(date).count { it.source == "OPENING_0930" } != openingMeta.rowCount) {
                db.openingBidDao().read(date)?.let { freezeOpening(it) }
            }
            db.withTransaction {
                var signals = dao.signals(date)
                if (signals.isEmpty()) {
                    val legacy = db.bidDao().getByDate(date).map(BidSignalSnapshots::legacy)
                    dao.insertSignals(legacy)
                    signals = legacy
                }
                val opening = signals.filter { it.source == "OPENING_0930" }
                require(signals.isNotEmpty()) { "缺少存盘的早盘评分" }
                require(openingMeta == null || opening.size == openingMeta.rowCount) { "09:30 冻结评分数据不完整" }
                if (opening.isNotEmpty()) signals = opening
                val source = when {
                    opening.isNotEmpty() -> "OPENING_0930"
                    signals.any { it.source == "UNVERIFIED" } -> "UNVERIFIED"
                    else -> "LEGACY_EARLY"
                }
                val selected = BidCandidateSelector.select(signals.map { it.signal }, config)
                val prices = dao.prices(date).associateBy { it.code }
                val missing = selected.filter { prices[it.code]?.price?.let { p -> p.isFinite() && p > 0 } != true }
                require(missing.isEmpty()) { "缺少 ${missing.joinToString { it.code }} 的有效结算价格" }
                val selectedPrices = selected.map { prices.getValue(it.code) }
                val quality = when {
                    selectedPrices.isEmpty() -> "NO_TRADES"
                    selectedPrices.all { it.quality == "CLOSE_CONFIRMED" } -> "CLOSE_CONFIRMED"
                    selectedPrices.any { it.quality == "LEGACY_PRICE_UNVERIFIED" } -> "LEGACY_PRICE_UNVERIFIED"
                    else -> "INTRADAY_ESTIMATE"
                }
                val times = selectedPrices.map { it.quoteAt }.filter { it > 0 }
                val asOf = if (times.size == selectedPrices.size && times.isNotEmpty()) {
                    "${Instant.ofEpochMilli(times.min()).atZone(OpeningBidTime.zone).toLocalDateTime()} ～ " +
                        "${Instant.ofEpochMilli(times.max()).atZone(OpeningBidTime.zone).toLocalDateTime()} 北京时间"
                } else "旧记录未保存行情时间"
                SimTradingDayData(date, selected.map { it.copy(latestPrice = prices.getValue(it.code).price) }, source,
                    signals.map { it.scoringVersion }.distinct().joinToString(), quality, asOf,
                    buildList { if (source != "OPENING_0930") add("早盘原始输入未验证，仅沿用存盘评分")
                        if (quality != "CLOSE_CONFIRMED" && quality != "NO_TRADES") add("未确认收盘价") })
            }
        }

    suspend fun pricesFor(date: String, codes: List<String>): List<BidPriceSnapshotEntity> = withContext(Dispatchers.IO) {
        val prices = dao.prices(date).associateBy { it.code }
        codes.map { code -> requireNotNull(prices[code]?.takeIf { it.price.isFinite() && it.price > 0 }) { "缺少 $code 有效价格" } }
    }

    companion object {
        fun priceQuality(date: String, quoteAt: Long, fetchedAt: Long): String {
            if (quoteAt <= 0 || fetchedAt <= 0) return "LEGACY_PRICE_UNVERIFIED"
            val quote = Instant.ofEpochMilli(quoteAt).atZone(OpeningBidTime.zone)
            val fetched = Instant.ofEpochMilli(fetchedAt).atZone(OpeningBidTime.zone)
            if (quote.toLocalDate().toString() != date || fetched.toLocalDate().toString() != date || quoteAt > fetchedAt + 5_000) return "LEGACY_PRICE_UNVERIFIED"
            return if (quote.dayOfWeek.value <= 5 && !quote.toLocalTime().isBefore(LocalTime.of(15, 0)) &&
                !fetched.toLocalTime().isBefore(LocalTime.of(15, 5))) "CLOSE_CONFIRMED" else "INTRADAY_ESTIMATE"
        }
    }
}
