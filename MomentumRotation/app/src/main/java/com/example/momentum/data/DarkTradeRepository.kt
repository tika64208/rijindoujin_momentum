package com.example.momentum.data

import android.content.Context
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.toEntity
import com.example.momentum.data.db.toRecord
import com.example.momentum.ui.screen.StockDetailData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 全量暗盘与深度资金数据仓库。
 *
 * 职责：
 * - 协调全量暗盘接口拉取与本地 Room 数据库持久化
 * - 维护个股深度信息（主力资金 + 两融明细）的内存二级缓存
 * - 统一暗盘数据流向，隔离 UI 与底层接口细节
 */
class DarkTradeRepository(
    private val database: AppDatabase,
    private val darkTradeDataSource: DarkTradeDataSource = DarkTradeDataSource,
    private val mainFundDataSource: MainFundDataSource = MainFundDataSource,
    private val marginDataSource: MarginDataSource = MarginDataSource
) {
    private val darkTradeDao get() = database.darkTradeDao()

    // 展开详情内存缓存(code → StockDetailData), App 存活期间有效
    private val detailCache = ConcurrentHashMap<String, StockDetailData>()

    /**
     * 获取指定日期的暗盘交易记录：
     * 1. 若非强制刷新且本地 DB 有该日数据，优先读 DB；
     * 2. 否则通过网络全量拉取，并回写至 DB 与内存。
     */
    suspend fun getDarkTradeRecords(
        date: String,
        forceRefresh: Boolean = false
    ): Result<List<DarkTradeDataSource.DarkTradeRecord>> =
        withContext(Dispatchers.IO) {
            try {
                if (!forceRefresh) {
                    val dbEntities = darkTradeDao.getByDate(date)
                    if (dbEntities.isNotEmpty()) {
                        return@withContext Result.success(dbEntities.map { it.toRecord() })
                    }
                }

                if (forceRefresh) {
                    darkTradeDataSource.clearCache()
                }

                val remoteRecords = darkTradeDataSource.fetchAllDarkTrade(date)
                if (remoteRecords.isNotEmpty()) {
                    val savedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    val entities = remoteRecords.map { it.toEntity(date, savedAt) }
                    darkTradeDao.insertAll(entities)
                }
                Result.success(remoteRecords)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 加载单只股票的展开详情（暗盘 + 主力资金流 + 两融）。
     * 内部使用 ConcurrentHashMap 实现会话内热点缓存。
     */
    suspend fun getStockDetail(
        record: DarkTradeDataSource.DarkTradeRecord
    ): StockDetailData =
        withContext(Dispatchers.IO) {
            detailCache[record.code]?.let { return@withContext it }

            val fund = mainFundDataSource.fetchFundFlow(record.code)
            val margin = marginDataSource.fetchMargin(record.code)

            val detail = StockDetailData(
                code = record.code,
                name = record.name,
                darkBuy = record.buyAmount,
                darkSell = record.sellAmount,
                darkNet = record.netAmount,
                mainNet = fund.mainNet,
                smallNet = fund.smallNet,
                midNet = fund.midNet,
                bigNet = fund.bigNet,
                superNet = fund.superNet,
                mainRatio = fund.mainRatio,
                smallRatio = fund.smallRatio,
                marginBuy = margin.buy,
                marginRepay = margin.repay,
                marginNet = margin.netBuy,
                marginBalance = margin.balance,
                loadingDetail = false
            )
            detailCache[record.code] = detail
            detail
        }

    fun getCachedDetail(code: String): StockDetailData? = detailCache[code]

    fun clearCache() {
        detailCache.clear()
        darkTradeDataSource.clearCache()
    }

    companion object {
        fun create(context: Context): DarkTradeRepository {
            return DarkTradeRepository(AppDatabase.get(context))
        }
    }
}
