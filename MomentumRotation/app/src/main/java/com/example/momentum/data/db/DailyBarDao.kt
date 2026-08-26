package com.example.momentum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DailyBarDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBars(bars: List<DailyBarEntity>)

    @Query("SELECT * FROM daily_bars WHERE symbol = :symbol AND date = :date LIMIT 1")
    suspend fun getBar(symbol: String, date: String): DailyBarEntity?

    @Query("SELECT * FROM daily_bars WHERE symbol = :symbol ORDER BY date ASC")
    suspend fun getBars(symbol: String): List<DailyBarEntity>

    @Query("SELECT COUNT(*) FROM daily_bars WHERE symbol = :symbol")
    suspend fun countBars(symbol: String): Int

    @Query("DELETE FROM daily_bars WHERE symbol = :symbol")
    suspend fun clearBars(symbol: String)

    @Query("SELECT MAX(date) FROM daily_bars WHERE symbol = :symbol")
    suspend fun latestDate(symbol: String): String?

    // ---- cache_meta ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: CacheMetaEntity)

    @Query("SELECT * FROM cache_meta WHERE symbol = :symbol LIMIT 1")
    suspend fun getMeta(symbol: String): CacheMetaEntity?
}
