package com.example.momentum.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * 单条日线记录(前复权), 按 (symbol, date) 唯一。
 */
@Entity(
    tableName = "daily_bars",
    primaryKeys = ["symbol", "date"]
)
data class DailyBarEntity(
    val symbol: String,      // 如 sh603991
    val date: String,        // yyyy-MM-dd
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

/**
 * 每只股票的缓存元数据: 记录最后更新的交易日。
 */
@Entity(tableName = "cache_meta")
data class CacheMetaEntity(
    @PrimaryKey val symbol: String,
    val lastUpdateDate: String,   // 最后联网更新的日期
    val barCount: Int             // 缓存条数
)

fun todayStr(): String = LocalDate.now().toString()

/**
 * 暗盘数据快照(按 date 分组, 全量保存)。
 */
@Entity(tableName = "dark_trade_data", primaryKeys = ["code", "date"])
data class DarkTradeDataEntity(
    val code: String,
    val date: String,
    val name: String,
    val industry: String,
    val concept: String,
    val buyAmount: Double,
    val sellAmount: Double,
    val totalAmount: Double,
    val netAmount: Double,
    val volume: Double,
    val ratio: Double,
    val savedAt: String
)

/**
 * 竞价分析数据快照(按 date 分组, 9:30 固定保存全量)。
 */
@Entity(tableName = "bid_data", primaryKeys = ["code", "date"])
data class BidDataEntity(
    val code: String,
    val date: String,
    val name: String,
    val latestPrice: Double,
    val realChange: Double,
    val openPrice: Double,
    val volume: Int,
    val amount: Double,
    val turnover: Double,
    val circMv: Double,
    val bidChange: Double,
    val warnFlag: Int,
    val industry: String,
    val conceptQuick: String,
    val probability: Int,
    val confidence: Int,
    val score: Int,
    val bidTurnover: Double,
    val entityChange: Double,
    val warnText: String,
    val reason: String,
    val savedAt: String,
    val excluded: Boolean = false,
    val excludeReason: String = ""
)

/**
 * 万龙选股(万手单 AND 寻龙诀4号 双共振)结果实体，永久存 DB。
 */
@Entity(tableName = "wanlong_hits", primaryKeys = ["code", "date"])
data class WanlongHitEntity(
    val code: String,          // 6 位股票代码
    val date: String,          // 信号日期 yyyy-MM-dd
    val name: String,          // 股票名称
    val close: Double,         // 当日收盘价
    val chgPct: Double = 0.0,  // 当日涨幅%
    val xlj4_7: Double = 0.0,  // 距前次寻龙诀周期
    val savedAt: String = ""   // 保存时间戳
)
