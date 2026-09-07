package com.example.momentum.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.momentum.MainActivity
import com.example.momentum.R
import com.example.momentum.data.SinaDataSource
import com.example.momentum.engine.MonitorEngine
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 实时行情监控 Worker: 交易时段定时轮询, 检测异常并通知。
 *
 * 上一周期快照存 SharedPreferences(JSON), 用于涨速/放量对比。
 */
class MonitorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 非交易时段直接跳过
        val now = Calendar.getInstance()
        if (!MonitorEngine.isTradingTime(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), now.get(Calendar.DAY_OF_WEEK))) {
            return Result.success()
        }

        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("monitor", Context.MODE_PRIVATE)

        // 监控标的(从自选股加载, 简化: 从偏好读)
        val watchSymbols = loadWatchSymbols(prefs)

        if (watchSymbols.isEmpty()) return Result.success()

        val curr = SinaDataSource.fetchQuotes(watchSymbols)
        if (curr.isEmpty()) return Result.success()

        // 读上一周期快照
        val prev = loadPrev(prefs, watchSymbols)

        val cfg = MonitorEngine.MonitorConfig(
            pctThreshold = prefs.getFloat("monitor_pct", 3.0f).toDouble(),
            speedThreshold = prefs.getFloat("monitor_speed", 0.8f).toDouble()
        )

        val alerts = MonitorEngine.checkAlerts(prev, curr, cfg)

        if (alerts.isNotEmpty()) {
            sendNotification(ctx, alerts)
        }

        // 保存当前快照为 prev
        savePrev(prefs, curr)

        return Result.success()
    }

    private fun loadWatchSymbols(prefs: android.content.SharedPreferences): List<String> {
        // 从偏好读自选股代码(逗号分隔), 转新浪格式 sh/sz
        val codesStr = prefs.getString("watch_codes", "") ?: ""
        if (codesStr.isBlank()) return emptyList()
        return codesStr.split(",").mapNotNull { code ->
            val c = code.trim()
            if (c.isEmpty()) null else when {
                c.startsWith("6") || c.startsWith("5") || c.startsWith("9") -> "sh$c"
                c.startsWith("0") || c.startsWith("3") || c.startsWith("2") -> "sz$c"
                else -> "sh$c"
            }
        }
    }

    private fun loadPrev(
        prefs: android.content.SharedPreferences,
        symbols: List<String>
    ): Map<String, SinaDataSource.Quote> {
        val jsonStr = prefs.getString("prev_quotes", "") ?: ""
        if (jsonStr.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(jsonStr)
            val result = HashMap<String, SinaDataSource.Quote>()
            for (sym in symbols) {
                obj.optJSONObject(sym)?.let { q ->
                    result[sym.removePrefix("sh").removePrefix("sz")] = SinaDataSource.Quote(
                        code = q.optString("code"),
                        name = q.optString("name"),
                        price = q.optDouble("price"),
                        prevClose = q.optDouble("prevClose"),
                        pct = q.optDouble("pct"),
                        amount = q.optDouble("amount"),
                        time = q.optString("time")
                    )
                }
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun savePrev(prefs: android.content.SharedPreferences, curr: Map<String, SinaDataSource.Quote>) {
        val obj = JSONObject()
        for ((code, q) in curr) {
            val qj = JSONObject()
            qj.put("code", q.code)
            qj.put("name", q.name)
            qj.put("price", q.price)
            qj.put("prevClose", q.prevClose)
            qj.put("pct", q.pct)
            qj.put("amount", q.amount)
            qj.put("time", q.time)
            obj.put(q.code, qj)
        }
        prefs.edit().putString("prev_quotes", obj.toString()).apply()
    }

    private fun sendNotification(ctx: Context, alerts: List<String>) {
        val channelId = "monitor_alerts"
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "行情监控告警", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "自选股实时异常告警" }
            )
        }

        val intent = Intent(ctx, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 取前 3 条告警展示
        val title = "行情监控告警"
        val content = alerts.take(3).joinToString("\n")
        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alerts.joinToString("\n")))
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(2001, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val WORK_NAME = "monitor_worker"

        /**
         * 注册监控周期任务(最小 15 分钟, 交易时段内实际按分钟轮询由 Worker 内部循环替代)。
         * 注: WorkManager 周期任务最小 15 分钟, 无法做到 monitor.py 的秒级轮询。
         */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request)
        }
    }
}
