package com.example.momentum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BidDao {

    /** 批量写入竞价数据(重复 code+date 覆盖) */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<BidDataEntity>)

    /** 查询某天全量竞价数据 */
    @Query("SELECT * FROM bid_data WHERE date = :date ORDER BY probability DESC")
    suspend fun getByDate(date: String): List<BidDataEntity>

    /** 查询指定股票某天竞价数据 */
    @Query("SELECT * FROM bid_data WHERE code = :code AND date = :date LIMIT 1")
    suspend fun getByCodeDate(code: String, date: String): BidDataEntity?

    /** 查询指定股票近 N 天竞价数据 */
    @Query("SELECT * FROM bid_data WHERE code = :code ORDER BY date DESC LIMIT :limit")
    suspend fun getHistory(code: String, limit: Int = 30): List<BidDataEntity>

    /** 某天是否有已保存数据 */
    @Query("SELECT COUNT(*) FROM bid_data WHERE date = :date")
    suspend fun countByDate(date: String): Int

    /** 获取有竞价数据的日期列表(最近 N 天) */
    @Query("SELECT DISTINCT date FROM bid_data ORDER BY date DESC LIMIT :limit")
    suspend fun getDates(limit: Int = 30): List<String>
}