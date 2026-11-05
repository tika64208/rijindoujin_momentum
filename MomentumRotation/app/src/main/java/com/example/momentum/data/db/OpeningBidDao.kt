package com.example.momentum.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.example.momentum.data.OpeningBidArchive
import com.example.momentum.data.openingBidPayloadChunks

@Entity(tableName = "opening_bid_archive")
data class OpeningBidArchiveEntity(
    @PrimaryKey val date: String,
    val startedAt: Long,
    val receivedAt: Long,
    val rowCount: Int,
    val schemaVersion: Int
)

// 全市场 JSON 可能超过 CursorWindow 的单行大小，分块存储，避免历史查询失败。
@Entity(tableName = "opening_bid_archive_chunk", primaryKeys = ["date", "kind", "part"])
data class OpeningBidChunk(val date: String, val kind: String, val part: Int, val payload: String)

data class OpeningBidRaw(val receivedAt: Long, val rawJson: String)

@Dao
interface OpeningBidDao {
    @Query("SELECT * FROM opening_bid_archive WHERE date = :date LIMIT 1")
    suspend fun metadata(date: String): OpeningBidArchiveEntity?

    @Query("SELECT * FROM opening_bid_archive_chunk WHERE date = :date AND kind = :kind ORDER BY part")
    suspend fun chunks(date: String, kind: String): List<OpeningBidChunk>

    @Transaction
    suspend fun read(date: String): OpeningBidArchive? {
        val meta = metadata(date) ?: return null
        return OpeningBidArchive(meta.date, meta.startedAt, meta.receivedAt, meta.rowCount,
            chunks(date, "json").joinToString("") { it.payload },
            chunks(date, "csv").joinToString("") { it.payload }, meta.schemaVersion)
    }

    @Transaction
    suspend fun raw(date: String): OpeningBidRaw? {
        val meta = metadata(date) ?: return null
        return OpeningBidRaw(meta.receivedAt, chunks(date, "json").joinToString("") { it.payload })
    }

    @Query("SELECT date FROM opening_bid_archive ORDER BY date DESC")
    suspend fun dates(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMetadata(archive: OpeningBidArchiveEntity): Long

    @Insert
    suspend fun insertChunks(chunks: List<OpeningBidChunk>)

    @Transaction
    suspend fun insertOnce(archive: OpeningBidArchive) {
        val id = insertMetadata(OpeningBidArchiveEntity(archive.date, archive.startedAt, archive.receivedAt,
            archive.rowCount, archive.schemaVersion))
        if (id == -1L) return
        for ((kind, content) in listOf("json" to archive.rawJson, "csv" to archive.csv)) {
            insertChunks(openingBidPayloadChunks(content).mapIndexed { index, part -> OpeningBidChunk(archive.date, kind, index, part) })
        }
    }
}
