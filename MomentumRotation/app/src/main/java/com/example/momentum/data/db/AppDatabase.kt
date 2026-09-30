package com.example.momentum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DailyBarEntity::class, CacheMetaEntity::class, BidDataEntity::class, DarkTradeDataEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailyBarDao(): DailyBarDao
    abstract fun bidDao(): BidDao
    abstract fun darkTradeDao(): DarkTradeDao

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
                        MIGRATION_4_5
                    )
                    .build().also { INSTANCE = it }
            }
    }
}