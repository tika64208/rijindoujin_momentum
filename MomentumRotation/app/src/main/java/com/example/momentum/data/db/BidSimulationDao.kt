package com.example.momentum.data.db

import androidx.room.*

/** 只存早盘评分和选股输入。旧数据的盘中量额清零，不能冒充早盘原始输入。 */
@Entity(tableName = "bid_signal_snapshot", primaryKeys = ["code", "date", "source"])
data class BidSignalSnapshotEntity(
    @Embedded val signal: BidDataEntity,
    val source: String,
    val scoringVersion: String,
    val rawInputJson: String,
    val frozenAt: Long
)

@Entity(tableName = "bid_price_snapshot", primaryKeys = ["code", "date"])
data class BidPriceSnapshotEntity(
    val code: String,
    val date: String,
    val price: Double,
    val realChange: Double,
    val volume: Int,
    val amount: Double,
    val turnover: Double,
    val quoteAt: Long,
    val fetchedAt: Long,
    val quality: String
)

@Dao
interface BidSimulationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSignals(signals: List<BidSignalSnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrices(prices: List<BidPriceSnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInitialPrices(prices: List<BidPriceSnapshotEntity>)

    @Query("SELECT * FROM bid_signal_snapshot WHERE date = :date ORDER BY probability DESC, code")
    suspend fun signals(date: String): List<BidSignalSnapshotEntity>

    @Query("SELECT * FROM bid_price_snapshot WHERE date = :date")
    suspend fun prices(date: String): List<BidPriceSnapshotEntity>

    @Query("SELECT DISTINCT date FROM bid_signal_snapshot ORDER BY date DESC")
    suspend fun dates(): List<String>

}
