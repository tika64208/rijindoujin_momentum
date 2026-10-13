package com.example.momentum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WanlongDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(hits: List<WanlongHitEntity>)

    @Query("SELECT * FROM wanlong_hits ORDER BY date DESC, close DESC")
    suspend fun getAll(): List<WanlongHitEntity>

    @Query("SELECT * FROM wanlong_hits WHERE date = :date ORDER BY close DESC")
    suspend fun getByDate(date: String): List<WanlongHitEntity>

    @Query("SELECT DISTINCT date FROM wanlong_hits ORDER BY date DESC")
    suspend fun getAllDates(): List<String>

    @Query("DELETE FROM wanlong_hits WHERE date = :date")
    suspend fun deleteByDate(date: String)
}
