package com.example.momentum.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.momentum.MainActivity
import com.example.momentum.R
import java.util.concurrent.TimeUnit

/**
 * 调仓提醒 Worker: 按周期弹通知, 提醒用户"该重新做动量轮动调仓了"。
 */
class RebalanceReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        sendReminder()
        return Result.success()
    }

    private fun sendReminder() {
        val ctx = applicationContext
        // 无通知权限则跳过(保证 Worker 不崩)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val channelId = "rebalance_reminder"
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId, "调仓提醒", NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "动量轮动定期调仓提醒" }
            )
        }

        val intent = Intent(ctx, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("动量轮动 · 该调仓了")
            .setContentText("到了本轮调仓周期，打开 App 重新计算动量排名吧")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(1001, notification)
        } catch (_: SecurityException) {
            // 通知失败静默处理
        }
    }

    companion object {
        private const val WORK_NAME = "rebalance_reminder"

        /** 注册/更新周期提醒任务。 */
        fun schedule(context: Context, periodDays: Int, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<RebalanceReminderWorker>(
                periodDays.toLong(), TimeUnit.DAYS
            ).build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request
            )
        }
    }
}
