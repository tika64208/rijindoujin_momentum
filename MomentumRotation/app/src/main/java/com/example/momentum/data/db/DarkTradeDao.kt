package com.example.momentum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
}