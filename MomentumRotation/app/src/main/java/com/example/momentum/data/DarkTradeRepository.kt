package com.example.momentum.data

import android.content.Context
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.ui.screen.StockDetailData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val history: DarkTradeHistoryRepository,
    private val mainFundDataSource: MainFundDataSource = MainFundDataSource,
    private val marginDataSource: MarginDataSource = MarginDataSource
) {
    private data class DetailEntry(val detail: StockDetailData, val fetchedAt: Long)
    private val detailCache = ConcurrentHashMap<String, DetailEntry>()

    /**
     * 加载单只股票的展开详情（暗盘 + 主力资金流 + 两融）。
     * 内部使用 ConcurrentHashMap 实现会话内热点缓存。
     */
    suspend fun getStockDetail(
        record: DarkTradeDataSource.DarkTradeRecord
    ): StockDetailData =
        withContext(Dispatchers.IO) {
            val key = "${darkTradeDate(record.date)}:${record.code}"
            getCachedDetail(record)?.let { return@withContext it }

            val fund = mainFundDataSource.fetchFundFlow(record.code)
            currentCoroutineContext().ensureActive()
            val margin = marginDataSource.fetchMargin(record.code)
            currentCoroutineContext().ensureActive()

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
            detailCache[key] = DetailEntry(detail, System.currentTimeMillis())
            detail
        }

    fun getCachedDetail(record: DarkTradeDataSource.DarkTradeRecord): StockDetailData? =
        detailCache["${darkTradeDate(record.date)}:${record.code}"]
            ?.takeIf { System.currentTimeMillis() - it.fetchedAt in 0 until 10 * 60 * 1000L }
            ?.detail?.copy(
            darkBuy = record.buyAmount, darkSell = record.sellAmount, darkNet = record.netAmount
        )

    companion object {
        @Volatile private var shared: DarkTradeRepository? = null

        fun create(context: Context): DarkTradeRepository {
            return shared ?: synchronized(this) {
                shared ?: run {
                    val database = AppDatabase.get(context.applicationContext)
                    DarkTradeRepository(DarkTradeHistoryRepository(
                        RoomDarkTradeHistoryStore(database.darkTradeDao()),
                        download = { DarkTradeDataSource.downloadAll(it) }
                    )).also { shared = it }
                }
            }
        }
    }
}
