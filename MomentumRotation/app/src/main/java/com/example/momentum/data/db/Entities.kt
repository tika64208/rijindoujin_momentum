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
