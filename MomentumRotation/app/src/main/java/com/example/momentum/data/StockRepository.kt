package com.example.momentum.data

import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.CacheMetaEntity
import com.example.momentum.data.db.DailyBarEntity
import com.example.momentum.engine.BacktestEngine
import com.example.momentum.engine.EquityCurveEngine
import com.example.momentum.engine.MomentumEngine
import com.example.momentum.engine.MomentumTrend
import com.example.momentum.engine.MomentumTrendEngine
import com.example.momentum.engine.StrategyConfig
import com.example.momentum.model.DailyBar
import com.example.momentum.model.Recommendation
import com.example.momentum.model.Stock
import com.example.momentum.model.StockMomentum
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 数据源标记: 本次计算用的数据来自本地缓存还是联网。
 */
enum class DataOrigin { FRESH, CACHE, OFFLINE }

/**
 * 股票数据仓库: 下载日线 + 本地缓存 + 离线兜底。
 *
 * 读优先序:
 *   1. 本地缓存(当日已更新) → 直接用, 不联网;
 *   2. 尝试联网下载并回写缓存 → 用最新数据;
 *   3. 联网失败但有旧缓存 → 离线兜底, 用旧缓存算。
 */
class StockRepository(
    private val database: AppDatabase,
    private val dataSource: TencentDataSource = TencentDataSource
) {

    private val dao get() = database.dailyBarDao()

    /** 单只股票: 取日线(带缓存), 并返回数据来源。 */
    suspend fun loadMomentum(
        stock: Stock,
        config: StrategyConfig
    ): Pair<StockMomentum, DataOrigin> =
        withContext(Dispatchers.IO) {
            val symbol = stock.marketPrefix
            val name = stock.name

            // 1) 本地缓存是否"当日已更新"
            val meta = dao.getMeta(symbol)
            val isFresh = meta != null && meta.lastUpdateDate == LocalDate.now().toString()

            if (!isFresh) {
                // 2) 尝试联网下载
                try {
                    val fetched = dataSource.fetchDailyBars(symbol, 2 * 252 + 30)
                    if (fetched.isNotEmpty()) {
                        val entities = fetched.map { b ->
                            DailyBarEntity(
                                symbol = symbol, date = b.date, open = b.open,
                                high = b.high, low = b.low, close = b.close, volume = b.volume
                            )
                        }
                        // 回写缓存(先清旧再插新, 保证无重复)
                        dao.clearBars(symbol)
                        dao.insertBars(entities)
                        dao.upsertMeta(
                            CacheMetaEntity(
                                symbol = symbol,
                                lastUpdateDate = LocalDate.now().toString(),
                                barCount = entities.size
                            )
                        )
                        // 名称: 优先用自选股池里的中文名; 仅当 name 是纯代码时才查接口
                        val finalName = if (name.isEmpty() || name.all { it.isDigit() }) {
                            dataSource.fetchStockName(symbol).ifBlank { name.ifBlank { stock.code } }
                        } else {
                            name
                        }
                        val m = MomentumEngine.computeStockMomentum(stock.code, finalName, fetched, config)
                        return@withContext m to DataOrigin.FRESH
                    }
                } catch (e: Exception) {
                    // 联网失败, 落到离线兜底
                }
            }

            // 3) 读本地缓存(当日已更新, 或联网失败兜底)
            val entities = dao.getBars(symbol)
            if (entities.isEmpty()) {
                return@withContext StockMomentum(stock.code, name, insufficient = true) to DataOrigin.OFFLINE
            }
            val bars = entities.map {
                DailyBar(it.date, it.open, it.high, it.low, it.close, it.volume)
            }
            val m = MomentumEngine.computeStockMomentum(stock.code, name, bars, config)
            val origin = if (isFresh) DataOrigin.CACHE else DataOrigin.OFFLINE
            return@withContext m to origin
        }

    /** 整个自选池: 并行取数并计算动量, 再横截面排名。 */
    suspend fun rankWatchlist(
        stocks: List<Stock>,
        config: StrategyConfig
    ): Triple<List<Recommendation>, MomentumEngine.RankMeta, DataOrigin> =
        coroutineScope {
            val deferred = stocks.map { stock ->
                async { loadMomentum(stock, config) }
            }
            val results = deferred.awaitAll()
            val momentums = results.map { it.first }
            val origins = results.map { it.second }
            // 整体数据来源: 有联网成功的记为 FRESH, 否则 CACHE/OFFLINE
            val overall = when {
                origins.any { it == DataOrigin.FRESH } -> DataOrigin.FRESH
                origins.any { it == DataOrigin.CACHE } -> DataOrigin.CACHE
                else -> DataOrigin.OFFLINE
            }
            val (recs, meta) = MomentumEngine.rankAndRecommend(momentums, config)
            Triple(recs, meta, overall)
        }

    /** 单只股票: 取缓存的日线(优先本地, 不足则联网下载)。 */
    suspend fun loadBars(stock: Stock): List<DailyBar> = withContext(Dispatchers.IO) {
        val symbol = stock.marketPrefix
        val cached = dao.getBars(symbol)
        if (cached.isNotEmpty()) {
            return@withContext cached.map {
                DailyBar(it.date, it.open, it.high, it.low, it.close, it.volume)
            }
        }
        // 无缓存则联网下载(不入库, 仅供本次分析)
        try {
            dataSource.fetchDailyBars(symbol, 2 * 252 + 30)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 动量翻转信号: 每只股票的趋势判断。 */
    suspend fun computeTrends(stocks: List<Stock>): List<MomentumTrend> =
        withContext(Dispatchers.IO) {
            stocks.map { stock ->
                val bars = loadBars(stock)
                MomentumTrendEngine.compute(stock.code, stock.name, bars)
            }
        }

    /** 回测对比。 */
    suspend fun backtest(stocks: List<Stock>): BacktestEngine.BacktestResult =
        withContext(Dispatchers.IO) {
            val pairs = stocks.map { stock ->
                stock.code to loadBars(stock)
            }.filter { it.second.isNotEmpty() }
            BacktestEngine.run(pairs)
        }

    /** 按关键词搜索 A股股票(中文名/拼音/代码)。 */
    suspend fun searchStocks(keyword: String): List<TencentDataSource.SearchResult> =
        withContext(Dispatchers.IO) {
            dataSource.searchStocks(keyword)
        }

    /** 收益曲线(动量 vs 均仓 vs 个股, 100万本金)。 */
    suspend fun buildEquityCurve(stocks: List<Stock>): EquityCurveEngine.CurveSet =
        withContext(Dispatchers.IO) {
            val pairs = stocks.map { stock ->
                stock.code to loadBars(stock)
            }.filter { it.second.isNotEmpty() }
            EquityCurveEngine.build(pairs)
        }
}
