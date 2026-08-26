package com.example.momentum.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.momentum.engine.StrategyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 调仓周期枚举。
 * 注: 2天为最短选项(短线轮动), WorkManager 周期任务支持。
 */
enum class RebalancePeriod(val days: Int, val label: String) {
    TWO_DAYS(2, "2天"),
    WEEKLY(7, "每周"),
    BIWEEKLY(14, "双周"),
    MONTHLY(30, "每月")
}

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 应用设置: 策略参数 + 提醒周期, 用 DataStore 持久化。
 */
class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_LAMBDA = doublePreferencesKey("vol_penalty_lambda")
        val KEY_TOP_COUNT = intPreferencesKey("top_count")
        val KEY_PERIOD_DAYS = intPreferencesKey("rebalance_period_days")
        val KEY_REMINDER_ON = booleanPreferencesKey("reminder_on")
    }

    /** 完整设置的响应式 Flow。 */
    val settingsFlow: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            volPenaltyLambda = prefs[KEY_LAMBDA] ?: 0.30,
            topCount = prefs[KEY_TOP_COUNT] ?: 3,
            periodDays = prefs[KEY_PERIOD_DAYS] ?: RebalancePeriod.TWO_DAYS.days,
            reminderOn = prefs[KEY_REMINDER_ON] ?: true
        )
    }

    suspend fun saveLambda(v: Double) = context.dataStore.edit { it[KEY_LAMBDA] = v }
    suspend fun saveTopCount(v: Int) = context.dataStore.edit { it[KEY_TOP_COUNT] = v }
    suspend fun savePeriod(days: Int) = context.dataStore.edit { it[KEY_PERIOD_DAYS] = days }
    suspend fun saveReminderOn(on: Boolean) = context.dataStore.edit { it[KEY_REMINDER_ON] = on }

    /** 把设置转成策略配置。 */
    fun toStrategyConfig(s: Settings): StrategyConfig = StrategyConfig(
        volPenaltyLambda = s.volPenaltyLambda,
        topCount = s.topCount
    )
}

data class Settings(
    val volPenaltyLambda: Double = 0.30,
    val topCount: Int = 3,
    val periodDays: Int = RebalancePeriod.TWO_DAYS.days,
    val reminderOn: Boolean = true
)
