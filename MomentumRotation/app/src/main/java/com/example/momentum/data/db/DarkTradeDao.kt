package com.example.momentum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

data class DarkTradeCacheSnapshot(
    val records: List<DarkTradeDataEntity>,
    val states: List<DarkTradeCacheEntity>,
    val dates: List<String>
)

@Dao
interface DarkTradeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DarkTradeDataEntity>)

    @Query("SELECT * FROM dark_trade_data WHERE date = :date ORDER BY netAmount DESC")
    suspend fun getByDate(date: String): List<DarkTradeDataEntity>

    @Query("SELECT COUNT(*) FROM dark_trade_data WHERE date = :date")
    suspend fun countByDate(date: String): Int

    @Query("SELECT DISTINCT date FROM dark_trade_data ORDER BY date DESC LIMIT :limit")
    suspend fun getDates(limit: Int = 30): List<String>

    @Query("SELECT * FROM dark_trade_data WHERE date IN (:dates)")
    suspend fun getByDates(dates: List<String>): List<DarkTradeDataEntity>

    @Query("SELECT * FROM dark_trade_data WHERE code = :code AND date IN (:dates)")
    suspend fun getByCodeAndDates(code: String, dates: List<String>): List<DarkTradeDataEntity>

    @Query("SELECT DISTINCT date FROM dark_trade_data WHERE date IN (:dates)")
    suspend fun getCachedDates(dates: List<String>): List<String>

    @Query("SELECT * FROM dark_trade_cache WHERE date IN (:dates)")
    suspend fun getCacheStates(dates: List<String>): List<DarkTradeCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCacheState(state: DarkTradeCacheEntity)

    @Query("DELETE FROM dark_trade_data WHERE date IN (:dates)")
    suspend fun deleteDates(dates: List<String>)

    @Transaction
    suspend fun replaceDay(state: DarkTradeCacheEntity, items: List<DarkTradeDataEntity>, aliases: List<String>) {
        deleteDates(aliases)
        insertAll(items)
        upsertCacheState(state)
    }

    @Transaction
    suspend fun readSnapshot(dates: List<String>, code: String? = null): DarkTradeCacheSnapshot =
        DarkTradeCacheSnapshot(
            if (code == null) getByDates(dates) else getByCodeAndDates(code, dates),
            getCacheStates(dates), getCachedDates(dates)
        )
}
