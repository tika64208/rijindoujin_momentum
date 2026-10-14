package com.example.momentum.data

import android.content.Context
import android.content.SharedPreferences
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.BidDataEntity
import com.example.momentum.data.db.toBidResult
import com.example.momentum.data.db.toEntity
import com.example.momentum.engine.BidStrategy
import com.example.momentum.util.DailyBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 竞价分析数据仓库。
 *
 * 职责：
 * - 协调竞价数据网络拉取与 Room 数据库快照持久化
 * - 9:32 时间锁：早盘竞价快照保护，防盘中/盘后被日内行情覆写
 * - 多维条件实时筛选与内存/DB 双层检索
 * - 历史个股 30 日竞价追溯
 * - 筛选参数 SharedPreferences 本地持久化
 */
class BidRepository(
    private val database: AppDatabase,
    private val prefs: SharedPreferences,
    private val appContext: Context? = null
) {
    private val bidDao get() = database.bidDao()

    /**
     * 判断当前是否处于集合竞价交易时间段 (工作日 9:15 ~ 9:32)。
     */
    fun isBidSession(): Boolean {
        val now = LocalTime.now()
        val dow = LocalDate.now().dayOfWeek
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false
        return !now.isBefore(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(9, 32))
    }

    /**
     * 读取保存的筛选配置。
     */
    fun loadConfig(): BidStrategy.BidConfig {
        val defaultCfg = BidStrategy.BidConfig.DEFAULT
        val saved = BidStrategy.BidConfig(
            bidMin = prefs.getFloat("bidMin", defaultCfg.bidMin.toFloat()).toDouble(),
            bidMax = prefs.getFloat("bidMax", defaultCfg.bidMax.toFloat()).toDouble(),
            priceMin = prefs.getFloat("priceMin", defaultCfg.priceMin.toFloat()).toDouble(),
            priceMax = prefs.getFloat("priceMax", defaultCfg.priceMax.toFloat()).toDouble(),
            mvMin = prefs.getFloat("mvMin", defaultCfg.mvMin.toFloat()).toDouble(),
            mvMax = prefs.getFloat("mvMax", defaultCfg.mvMax.toFloat()).toDouble(),
            probMin = prefs.getFloat("probMin", defaultCfg.probMin.toFloat()).toDouble(),
            probMax = prefs.getFloat("probMax", defaultCfg.probMax.toFloat()).toDouble(),
            confMin = prefs.getFloat("confMin", defaultCfg.confMin.toFloat()).toDouble(),
            confMax = prefs.getFloat("confMax", defaultCfg.confMax.toFloat()).toDouble(),
            scoreMin = prefs.getFloat("scoreMin", defaultCfg.scoreMin.toFloat()).toDouble(),
            scoreMax = prefs.getFloat("scoreMax", defaultCfg.scoreMax.toFloat()).toDouble(),
            filterStSuspend = prefs.getBoolean("stSuspend", defaultCfg.filterStSuspend),
            filterLimitUp = prefs.getBoolean("limitUp", defaultCfg.filterLimitUp),
            marketHs = prefs.getBoolean("marketHs", defaultCfg.marketHs),
            marketCyb = prefs.getBoolean("marketCyb", defaultCfg.marketCyb),
            marketKcb = prefs.getBoolean("marketKcb", defaultCfg.marketKcb)
        )
        if (prefs.contains("bidMin")) {
            // 若为旧版本残留的过严默认配置(bidMin==7.0且probMax==65.0)，重置为默认值
            return if (saved.bidMin == 7.0 && saved.probMax == 65.0) {
                saveConfig(defaultCfg)
                defaultCfg
            } else {
                saved
            }
        }
        return defaultCfg
    }

    /**
     * 持久化保存筛选配置。
     */
    fun saveConfig(cfg: BidStrategy.BidConfig) {
        prefs.edit()
            .putFloat("bidMin", cfg.bidMin.toFloat())
            .putFloat("bidMax", cfg.bidMax.toFloat())
            .putFloat("priceMin", cfg.priceMin.toFloat())
            .putFloat("priceMax", cfg.priceMax.toFloat())
            .putFloat("mvMin", cfg.mvMin.toFloat())
            .putFloat("mvMax", cfg.mvMax.toFloat())
            .putFloat("probMin", cfg.probMin.toFloat())
            .putFloat("probMax", cfg.probMax.toFloat())
            .putFloat("confMin", cfg.confMin.toFloat())
            .putFloat("confMax", cfg.confMax.toFloat())
            .putFloat("scoreMin", cfg.scoreMin.toFloat())
            .putFloat("scoreMax", cfg.scoreMax.toFloat())
            .putBoolean("stSuspend", cfg.filterStSuspend)
            .putBoolean("limitUp", cfg.filterLimitUp)
            .putBoolean("marketHs", cfg.marketHs)
            .putBoolean("marketCyb", cfg.marketCyb)
            .putBoolean("marketKcb", cfg.marketKcb)
            .apply()
    }

    /**
     * 刷新并持久化全市场竞价与盘中最新行情。
     * - 早盘竞价时段 (9:15 ~ 9:30)：正常拉取全市场竞价快照，计算竞价因子、评分、胜率并全额持久化入库；
     * - 开盘后时段 (9:30 以后)：严格锁定早盘竞价数据！
     *   * 竞价涨幅 (bidChange)、开盘价 (openPrice)、竞价预测胜率 (probability)、评分 (score)、
     *     竞价换手 (bidTurnover)、实体预期 (entityChange) 严格继承早盘快照，绝不覆写！
     *   * 仅同步更新盘中最新成交价 (latestPrice)、盘中实时涨幅 (realChange)、最新成交量与成交额。
     *   * 若当日早盘尚未生成快照而在盘中首次打开，以接口数据固化并生成首份快照，后续刷新立即锁定。
     * @return 实际存入的股票条数
     */
    suspend fun refresh(config: BidStrategy.BidConfig, force: Boolean = true): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val today = LocalDate.now().toString()
                val now = LocalTime.now()
                val isAfterOpen = now.isAfter(LocalTime.of(9, 30))

                val rawItems = EastmoneyBidDataSource.fetchAll()
                if (rawItems.isEmpty()) {
                    return@withContext Result.failure(RuntimeException("未拉取到竞价数据"))
                }

                // 读取已有快照中的竞价数值与早盘模型评分
                val existingMap = bidDao.getByDate(today).associateBy { it.code }
                val hasMorningSnapshot = existingMap.isNotEmpty()

                // 若 9:30 后已有早盘快照，固定时间戳为早盘时间；否则记录当前时间
                val savedAt = if (hasMorningSnapshot && isAfterOpen) {
                    val firstSavedAt = existingMap.values.first().savedAt
                    if (firstSavedAt.isNotBlank()) firstSavedAt else now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                } else {
                    now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                }

                val entities = rawItems.map { item ->
                    val existing = existingMap[item.code]

                    if (existing != null && isAfterOpen) {
                        // 9:30 之后严格锁定：竞价相关的所有因子与评分严格锁定，仅更新盘中最新价与实时涨幅
                        existing.copy(
                            latestPrice = item.latestPrice,
                            realChange = item.realChange,
                            volume = item.volume,
                            amount = item.amount,
                            turnover = item.turnover
                        )
                    } else {
                        // 早盘竞价时段 (<= 9:30) 或当日首次入库：正常计算竞价涨幅与模型评分
                        val finalBidChange = if (existing != null && existing.bidChange != 0.0 && isAfterOpen) {
                            existing.bidChange
                        } else if (item.bidChange != 0.0) {
                            item.bidChange
                        } else {
                            existing?.bidChange ?: 0.0
                        }

                        val finalOpenPrice = if (existing != null && existing.openPrice > 0 && isAfterOpen) {
                            existing.openPrice
                        } else if (item.openPrice > 0) {
                            item.openPrice
                        } else {
                            existing?.openPrice ?: item.latestPrice
                        }

                        val mergedItem = item.copy(
                            bidChange = finalBidChange,
                            openPrice = finalOpenPrice
                        )
                        // 关键修复：计算竞价模型评分时，必须以开盘竞价涨幅为基准，绝不能使用盘中实时波动率污染评分！
                        val computeItem = mergedItem.copy(
                            realChange = finalBidChange,
                            latestPrice = finalOpenPrice
                        )
                        val result = BidStrategy.compute(computeItem, config)
                        mergedItem.toEntity(today, result, savedAt)
                    }
                }
                bidDao.insertAll(entities)

                // 自动每日备份至本地 CSV 文件
                appContext?.let { ctx ->
                    try {
                        DailyBackupManager.backupTodayBidData(ctx, today)
                    } catch (_: Exception) {}
                }

                Result.success(entities.size)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 从 DB 读取并按当前配置动态过滤，返回过滤后的列表与时间快照。
     */
    suspend fun getFilteredResults(
        config: BidStrategy.BidConfig,
        date: String = LocalDate.now().toString()
    ): Pair<List<BidStrategy.BidResult>, String> =
        withContext(Dispatchers.IO) {
            val entities = bidDao.getByDate(date)
            val sanitized = entities.map { sanitizeEntity(it, config) }
            val filtered = sanitized.filter { applyFilter(it, config) }
                .map { it.toBidResult() }
                .sortedByDescending { it.probability }
            val savedAt = entities.firstOrNull()?.savedAt ?: ""
            filtered to savedAt
        }

    /**
     * 锁定筛选模式：仅读取锁定股票代码池中的标的，保证盘中刷新不增不减。
     */
    suspend fun getLockedResults(
        lockedCodes: Set<String>,
        date: String = LocalDate.now().toString(),
        config: BidStrategy.BidConfig = BidStrategy.BidConfig.DEFAULT
    ): Pair<List<BidStrategy.BidResult>, String> =
        withContext(Dispatchers.IO) {
            val entities = bidDao.getByDate(date)
            val sanitized = entities.map { sanitizeEntity(it, config) }
            val filtered = sanitized.filter { it.code in lockedCodes }
                .map { it.toBidResult() }
                .sortedByDescending { it.probability }
            val savedAt = entities.firstOrNull()?.savedAt ?: ""
            filtered to savedAt
        }

    /**
     * 对实体进行竞价纯净性校准：确保评分和概率完全由早盘竞价数据决定，不受盘中股价波动污染。
     */
    private fun sanitizeEntity(e: BidDataEntity, config: BidStrategy.BidConfig): BidDataEntity {
        val basePrice = if (e.openPrice > 0) e.openPrice else e.latestPrice
        val rawItem = BidStrategy.BidItem(
            code = e.code,
            name = e.name,
            latestPrice = basePrice,
            realChange = e.bidChange, // 严格以竞价涨幅为基准
            openPrice = e.openPrice,
            volume = e.volume,
            amount = e.amount,
            turnover = e.turnover,
            circMv = e.circMv,
            bidChange = e.bidChange,
            warnFlag = e.warnFlag,
            industry = e.industry,
            conceptQuick = e.conceptQuick
        )
        val res = BidStrategy.compute(rawItem, config)
        return e.copy(
            probability = res.probability,
            confidence = res.confidence,
            score = res.score
        )
    }

    /**
     * 持久化锁定的股票池代码
     */
    fun saveLockedCodes(codes: Set<String>) {
        val today = LocalDate.now().toString()
        prefs.edit()
            .putStringSet("locked_codes_$today", codes)
            .putBoolean("is_locked_$today", codes.isNotEmpty())
            .apply()
    }

    /**
     * 读取今日锁定的股票池代码
     */
    fun loadLockedCodes(): Pair<Boolean, Set<String>> {
        val today = LocalDate.now().toString()
        val isLocked = prefs.getBoolean("is_locked_$today", false)
        val codes = prefs.getStringSet("locked_codes_$today", emptySet()) ?: emptySet()
        return isLocked to codes
    }

    /**
     * 获取当前筛选条件下的全部完整匹配结果(不截断, 用于完整数据导出)。
     */
    suspend fun getAllFilteredResults(
        config: BidStrategy.BidConfig,
        date: String = LocalDate.now().toString()
    ): List<BidStrategy.BidResult> =
        withContext(Dispatchers.IO) {
            val entities = bidDao.getByDate(date)
            val sanitized = entities.map { sanitizeEntity(it, config) }
            sanitized.filter { applyFilter(it, config) }
                .map { it.toBidResult() }
                .sortedByDescending { it.probability }
        }

    /**
     * 获取指定日期符合高胜率配置的候选股票实体列表 (用于模拟交易分仓买入)
     */
    suspend fun getHighWinRateCandidates(date: String = LocalDate.now().toString()): List<BidDataEntity> =
        withContext(Dispatchers.IO) {
            val entities = bidDao.getByDate(date)
            val sanitized = entities.map { sanitizeEntity(it, BidStrategy.BidConfig.HIGH_WIN_RATE) }
            sanitized.filter { applyFilter(it, BidStrategy.BidConfig.HIGH_WIN_RATE) }
        }

    /**
     * 多维条件实时筛选判断。
     */
    fun applyFilter(e: BidDataEntity, cfg: BidStrategy.BidConfig): Boolean {
        // ST / 停牌剔除
        if (cfg.filterStSuspend && (e.name.contains("ST", ignoreCase = true) || e.name.contains("停", ignoreCase = true))) {
            return false
        }
        // 昨日涨停剔除
        if (cfg.filterLimitUp && e.warnFlag >= 5) return false

        // 市场筛选
        if (!cfg.marketHs && !cfg.marketCyb && !cfg.marketKcb) return false
        val isHs = (e.code.startsWith("6") && !e.code.startsWith("688")) || e.code.startsWith("00")
        val isCyb = e.code.startsWith("3")
        val isKcb = e.code.startsWith("688")
        if (isHs && !cfg.marketHs) return false
        if (isCyb && !cfg.marketCyb) return false
        if (isKcb && !cfg.marketKcb) return false

        // 区间条件判断
        if (cfg.bidMin > 0 && e.bidChange < cfg.bidMin) return false
        if (cfg.bidMax > 0 && e.bidChange > cfg.bidMax) return false
        if (cfg.probMin > 0 && e.probability < cfg.probMin) return false
        if (cfg.probMax > 0 && e.probability > cfg.probMax) return false
        if (cfg.confMin > 0 && e.confidence < cfg.confMin) return false
        if (cfg.confMax > 0 && e.confidence > cfg.confMax) return false
        if (cfg.scoreMin > 0 && e.score < cfg.scoreMin) return false
        if (cfg.scoreMax > 0 && e.score > cfg.scoreMax) return false
        if (cfg.mvMin > 0 && e.circMv / 1e8 < cfg.mvMin) return false
        if (cfg.mvMax > 0 && e.circMv / 1e8 > cfg.mvMax) return false
        val checkPrice = if (e.openPrice > 0) e.openPrice else e.latestPrice
        if (cfg.priceMin > 0 && checkPrice < cfg.priceMin) return false
        if (cfg.priceMax > 0 && checkPrice > cfg.priceMax) return false
        if (cfg.filterLargeVolume && e.volume < cfg.filterLargeVolumeThreshold) return false

        return true
    }

    /**
     * 查询个股最近历史竞价记录。
     * 支持：股票代码(如 600519)、中文名称(如 贵州茅台 / 茅台)、或拼音缩写(如 gzmt)。
     */
    suspend fun searchHistory(query: String, limit: Int = 50): List<BidDataEntity> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isEmpty()) return@withContext emptyList()

            // 1. 本地 DB 查询 (支持代码或中文名称模糊匹配)
            val localList = bidDao.searchHistory(clean, limit)
            if (localList.isNotEmpty()) {
                return@withContext localList
            }

            // 2. 本地未命中时，尝试调用腾讯股票搜索服务解析中文或拼音(如 "gzmt" -> 600519)
            try {
                val candidates = TencentDataSource.searchStocks(clean)
                for (c in candidates) {
                    val hit = bidDao.getHistory(c.code, limit)
                    if (hit.isNotEmpty()) {
                        return@withContext hit
                    }
                }
            } catch (e: Exception) {
                // 忽略解析网络异常
            }

            emptyList()
        }

    /**
     * 获取已有竞价数据的历史日期列表。
     */
    suspend fun getAvailableDates(limit: Int = 30): List<String> =
        withContext(Dispatchers.IO) {
            bidDao.getDates(limit)
        }

    companion object {
        fun create(context: Context): BidRepository {
            val db = AppDatabase.get(context)
            val prefs = context.getSharedPreferences("bid_config", Context.MODE_PRIVATE)
            return BidRepository(db, prefs, context.applicationContext)
        }
    }
}
