package com.example.momentum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DailyBarEntity::class, CacheMetaEntity::class, BidDataEntity::class, DarkTradeDataEntity::class, DarkTradeCacheEntity::class, WanlongHitEntity::class, OpeningBidArchiveEntity::class, OpeningBidChunk::class, BidSignalSnapshotEntity::class, BidPriceSnapshotEntity::class],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailyBarDao(): DailyBarDao
    abstract fun bidDao(): BidDao
    abstract fun darkTradeDao(): DarkTradeDao
    abstract fun wanlongDao(): WanlongDao
    abstract fun openingBidDao(): OpeningBidDao
    abstract fun bidSimulationDao(): BidSimulationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2: daily_bars 主键从 id(自增) 改为 (symbol,date) 复合主键。
         * 重建 daily_bars 表并保留数据(去重后迁移)。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE daily_bars_new (" +
                        "symbol TEXT NOT NULL, date TEXT NOT NULL, " +
                        "open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, " +
                        "close REAL NOT NULL, volume REAL NOT NULL, " +
                        "PRIMARY KEY(symbol, date))"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO daily_bars_new (symbol, date, open, high, low, close, volume) " +
                        "SELECT symbol, date, open, high, low, close, volume FROM daily_bars"
                )
                db.execSQL("DROP TABLE daily_bars")
                db.execSQL("ALTER TABLE daily_bars_new RENAME TO daily_bars")
            }
        }

        /** v2 -> v3: 新增 bid_data 表。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bid_data (" +
                        "code TEXT NOT NULL, date TEXT NOT NULL, name TEXT NOT NULL, " +
                        "latestPrice REAL NOT NULL, realChange REAL NOT NULL, openPrice REAL NOT NULL, " +
                        "volume INTEGER NOT NULL, amount REAL NOT NULL, turnover REAL NOT NULL, " +
                        "circMv REAL NOT NULL, bidChange REAL NOT NULL, warnFlag INTEGER NOT NULL, " +
                        "industry TEXT NOT NULL, conceptQuick TEXT NOT NULL, " +
                        "probability INTEGER NOT NULL, confidence INTEGER NOT NULL, score INTEGER NOT NULL, " +
                        "bidTurnover REAL NOT NULL, entityChange REAL NOT NULL, warnText TEXT NOT NULL, " +
                        "reason TEXT NOT NULL, savedAt TEXT NOT NULL, " +
                        "PRIMARY KEY(code, date))"
                )
            }
        }

        /** v3 -> v4: 新增 dark_trade_data 表。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS dark_trade_data (" +
                        "code TEXT NOT NULL, date TEXT NOT NULL, name TEXT NOT NULL, " +
                        "industry TEXT NOT NULL, concept TEXT NOT NULL, " +
                        "buyAmount REAL NOT NULL, sellAmount REAL NOT NULL, totalAmount REAL NOT NULL, " +
                        "netAmount REAL NOT NULL, volume REAL NOT NULL, ratio REAL NOT NULL, " +
                        "savedAt TEXT NOT NULL, PRIMARY KEY(code, date))"
                )
            }
        }

        /** v4 -> v5: bid_data 加 excluded/excludeReason 列。 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bid_data ADD COLUMN excluded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bid_data ADD COLUMN excludeReason TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5 -> v6: 新增 wanlong_hits 表 (万龙选股历史结果持久化)。 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS wanlong_hits (" +
                        "code TEXT NOT NULL, date TEXT NOT NULL, name TEXT NOT NULL, " +
                        "close REAL NOT NULL, chgPct REAL NOT NULL DEFAULT 0.0, " +
                        "xlj4_7 REAL NOT NULL DEFAULT 0.0, savedAt TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(code, date))"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS dark_trade_cache (date TEXT NOT NULL, complete INTEGER NOT NULL, totalCount INTEGER NOT NULL, fetchedAt INTEGER NOT NULL, PRIMARY KEY(date))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_dark_trade_data_date ON dark_trade_data (date)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS opening_bid_archive (date TEXT NOT NULL, startedAt INTEGER NOT NULL, receivedAt INTEGER NOT NULL, rowCount INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, PRIMARY KEY(date))")
                db.execSQL("CREATE TABLE IF NOT EXISTS opening_bid_archive_chunk (date TEXT NOT NULL, kind TEXT NOT NULL, part INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(date, kind, part))")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS bid_signal_snapshot (code TEXT NOT NULL, date TEXT NOT NULL, name TEXT NOT NULL, latestPrice REAL NOT NULL, realChange REAL NOT NULL, openPrice REAL NOT NULL, volume INTEGER NOT NULL, amount REAL NOT NULL, turnover REAL NOT NULL, circMv REAL NOT NULL, bidChange REAL NOT NULL, warnFlag INTEGER NOT NULL, industry TEXT NOT NULL, conceptQuick TEXT NOT NULL, probability INTEGER NOT NULL, confidence INTEGER NOT NULL, score INTEGER NOT NULL, bidTurnover REAL NOT NULL, entityChange REAL NOT NULL, warnText TEXT NOT NULL, reason TEXT NOT NULL, savedAt TEXT NOT NULL, excluded INTEGER NOT NULL, excludeReason TEXT NOT NULL, source TEXT NOT NULL, scoringVersion TEXT NOT NULL, rawInputJson TEXT NOT NULL, frozenAt INTEGER NOT NULL, PRIMARY KEY(code, date, source))")
                db.execSQL("CREATE TABLE IF NOT EXISTS bid_price_snapshot (code TEXT NOT NULL, date TEXT NOT NULL, price REAL NOT NULL, realChange REAL NOT NULL, volume INTEGER NOT NULL, amount REAL NOT NULL, turnover REAL NOT NULL, quoteAt INTEGER NOT NULL, fetchedAt INTEGER NOT NULL, quality TEXT NOT NULL, PRIMARY KEY(code, date))")
                db.execSQL("INSERT OR IGNORE INTO bid_signal_snapshot (code, date, name, latestPrice, realChange, openPrice, volume, amount, turnover, circMv, bidChange, warnFlag, industry, conceptQuick, probability, confidence, score, bidTurnover, entityChange, warnText, reason, savedAt, excluded, excludeReason, source, scoringVersion, rawInputJson, frozenAt) SELECT code, date, name, openPrice, bidChange, openPrice, 0, 0, 0, circMv, bidChange, warnFlag, industry, conceptQuick, probability, confidence, score, bidTurnover, entityChange, warnText, reason, savedAt, excluded, excludeReason, CASE WHEN savedAt >= '09:15:00' AND savedAt <= '09:30:00' AND probability > 0 THEN 'LEGACY_EARLY' ELSE 'UNVERIFIED' END, 'legacy-stored', '', 0 FROM bid_data WHERE date < date('now','+8 hours') OR (date = date('now','+8 hours') AND time('now','+8 hours') >= '09:30:00')")
                db.execSQL("INSERT OR IGNORE INTO bid_price_snapshot (code,date,price,realChange,volume,amount,turnover,quoteAt,fetchedAt,quality) SELECT code,date,latestPrice,realChange,volume,amount,turnover,0,0,'LEGACY_PRICE_UNVERIFIED' FROM bid_data")
            }
        }

        /**
         * v9 -> v10: bid_data 记录逐行行情时间(f124)与冻结时的估算竞价量，供列表展示数据时点与评分拆解。
         * bid_signal_snapshot 以 @Embedded 内嵌 BidDataEntity，同一组列必须同步补齐。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bid_data ADD COLUMN quoteAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bid_data ADD COLUMN bidVolume INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bid_signal_snapshot ADD COLUMN quoteAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bid_signal_snapshot ADD COLUMN bidVolume INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "momentum.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .build().also { INSTANCE = it }
            }
    }
}
