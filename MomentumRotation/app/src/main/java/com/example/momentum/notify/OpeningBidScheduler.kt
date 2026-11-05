package com.example.momentum.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.momentum.data.OpeningBidRepository
import com.example.momentum.data.OpeningBidTime

/** 北京时间工作日 09:30；休市日由响应中的实际报价日期进一步校验。 */
object OpeningBidScheduler {
    const val ACTION_CAPTURE = "com.example.momentum.CAPTURE_OPENING_BID"

    fun canSchedule(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context) {
        if (!canSchedule(context)) return
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 930,
            Intent(context, OpeningBidAlarmReceiver::class.java).setAction(ACTION_CAPTURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                OpeningBidTime.nextTarget(System.currentTimeMillis()), pending)
        } catch (_: SecurityException) {
            // 用户撤回权限后，页面会显示未授权；不退化为不准点的“成功采集”。
        }
    }

    /** 仅在活动可见或准点闹钟触发时调用，后台服务显示采集通知。 */
    fun captureIfDue(context: Context) {
        if (!OpeningBidTime.inWindow(System.currentTimeMillis())) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, OpeningBidService::class.java))
        } catch (e: Exception) {
            OpeningBidRepository(context).recordFailure("采集服务启动失败：${e.message}")
        }
    }
}

class OpeningBidAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OpeningBidScheduler.schedule(context)
        if (intent.action == OpeningBidScheduler.ACTION_CAPTURE) {
            if (OpeningBidTime.inWindow(System.currentTimeMillis())) OpeningBidScheduler.captureIfDue(context)
            else OpeningBidRepository(context).recordFailure("系统唤醒超过 09:30 窗口，未补造快照")
        }
        // 重启/升级/时钟调整/权限授予只重设闹钟，不从 BOOT_COMPLETED 启动 dataSync 服务。
    }
}
