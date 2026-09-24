package com.example.momentum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DailyBarEntity::class, CacheMetaEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailyBarDao(): DailyBarDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "momentum.db"
                )
                    // 主键结构变更(改用 (symbol,date) 复合主键), 破坏性迁移即可:
                    // 旧缓存丢弃, 重新全量下载。
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
