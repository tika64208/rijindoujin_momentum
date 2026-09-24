package com.example.momentum.data

import android.content.Context
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.CacheMetaEntity
import com.example.momentum.data.db.DailyBarEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 全 A 股历史日线增量下载器。
 *
 * 策略:
 *  - 全 A 股代码列表: 首次联网拉取后缓存到本地(SharedPreferences),
 *    之后启动优先用缓存, 每天最多联网刷新一次(避免高频触发东财反爬);
 *  - 对每只股票, 若 cache_meta.lastUpdateDate == 今天 则跳过(不重复下载);
 *  - 否则只拉「上次更新日之后」的增量(通常最新 1 根日线), REPLACE 复合主键去重。
 *
 * 进度通过 [onProgress] 回调。
 */
class AStockDownloader(private val context: Context) {

    private val database get() = AppDatabase.get(context)
    private val prefs get() = context.getSharedPreferences("astock_cache", Context.MODE_PRIVATE)

    data class Progress(
        val total: Int,
        val done: Int,
        val currentCode: String,
        val currentName: String
    )

    /** 每次日线请求之间的间隔(毫秒), 降低反爬风险。 */
    private val requestDelayMs = 120L

    /**
     * 执行一次增量下载。
     */
    suspend fun run(onProgress: (Progress) -> Unit = {}): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            // 1. 获取全 A 股代码列表(优先缓存, 每日最多联网刷新一次)
            val allCodes = getStockCodes()
            if (allCodes.isEmpty()) return@withContext 0 to 0

            // 2. 已下载的 symbol -> meta (用于去重)
            val existing = database.dailyBarDao().getAllMeta().associateBy { it.symbol }
            val today = LocalDate.now().toString()

            var downloaded = 0
            var skipped = 0
            val total = allCodes.size

            // 5 年前(首次基线用)
            val beg = LocalDate.now().minusYears(5).format(DateTimeFormatter.ofPattern("yyyyMMdd"))

            allCodes.forEachIndexed { index, sc ->
                val symbol = toSymbol(sc.code)
                val meta = existing[symbol]
                if (meta != null && meta.lastUpdateDate == today) {
                    skipped++
                    onProgress(Progress(total, index + 1, sc.code, sc.name))
                    return@forEachIndexed
                }

                // 增量: 已有数据只拉「上次更新日之后」; 首次拉 5 年基线。
                val bars = if (meta != null && meta.lastUpdateDate.isNotBlank()) {
                    val begInc = meta.lastUpdateDate.replace("-", "").take(8)
                    AStockDataSource.fetchDailyBars(sc.code, begInc)
                } else {
                    AStockDataSource.fetchDailyBars(sc.code, beg)
                }

                if (bars.isNotEmpty()) {
                    val entities = bars.map { b ->
                        DailyBarEntity(
                            symbol = symbol, date = b.date, open = b.open,
                            high = b.high, low = b.low, close = b.close, volume = b.volume
                        )
                    }
                    val dao = database.dailyBarDao()
                    dao.insertBars(entities)
                    dao.upsertMeta(
                        CacheMetaEntity(
                            symbol = symbol,
                            lastUpdateDate = today,
                            barCount = dao.countBars(symbol)
                        )
                    )
                    downloaded++
                }
                onProgress(Progress(total, index + 1, sc.code, sc.name))
                delay(requestDelayMs)  // 限流
            }

            downloaded to skipped
        }

    /**
     * 获取全 A 股代码列表: 本地缓存优先, 缓存为空或超过 1 天则联网刷新。
     */
    private suspend fun getStockCodes(): List<AStockDataSource.StockCode> {
        val today = LocalDate.now().toString()
        val cachedDate = prefs.getString("codes_date", "")
        val cachedCount = prefs.getInt("codes_count", 0)

        // 缓存且是今天的 → 直接用缓存
        if (cachedDate == today && cachedCount > 0) {
            return readCodesFromCache()
        }

        // 联网刷新
        val fresh = AStockDataSource.fetchAllStockCodes()
        if (fresh.isNotEmpty()) {
            // 写缓存: code|name 用 \n 分隔, code/name 用逗号分隔
            val sb = StringBuilder()
            fresh.forEach { sc -> sb.append(sc.code).append(',').append(sc.name).append('\n') }
            prefs.edit()
                .putString("codes", sb.toString())
                .putString("codes_date", today)
                .putInt("codes_count", fresh.size)
                .apply()
            return fresh
        }

        // 联网失败 → 用缓存兜底
        return readCodesFromCache()
    }

    private fun readCodesFromCache(): List<AStockDataSource.StockCode> {
        val raw = prefs.getString("codes", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(",", limit = 2)
                if (parts.size >= 1 && parts[0].isNotBlank()) {
                    AStockDataSource.StockCode(parts[0], parts.getOrElse(1) { "" })
                } else null
            }
    }

    private fun toSymbol(code: String): String = when {
        code.startsWith("6") -> "sh$code"
        code.startsWith("0") || code.startsWith("3") -> "sz$code"
        else -> "sh$code"
    }
}
