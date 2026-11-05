package com.example.momentum.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.momentum.MainActivity
import com.example.momentum.R
import com.example.momentum.data.OpeningBidRepository
import com.example.momentum.data.OpeningBidTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OpeningBidService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "09:30 竞价归档", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification("正在冻结全市场竞价数据"))
        if (job?.isActive == true) return START_NOT_STICKY
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:OpeningBid").apply { acquire(90_000) }
        job = scope.launch {
            val repository = OpeningBidRepository(applicationContext)
            var message = "未完成采集"
            try {
                // 只在这一分钟内重试，不允许晚到的数据被标作 09:30。
                while (OpeningBidTime.inWindow(System.currentTimeMillis())) {
                    try {
                        val archive = repository.capture()
                        message = "已冻结 ${archive.rowCount} 只，CSV / JSON 已存盘"
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = e.message ?: "采集失败"
                        repository.recordFailure(message)
                        delay(2_000)
                    }
                }
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                runCatching { manager.notify(ID, notification(message)) }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel()
        stopSelf()
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("09:30 全市场竞价归档")
        .setContentText(text)
        .setContentIntent(PendingIntent.getActivity(this, 930,
            Intent(this, MainActivity::class.java).putExtra("tab", "bid"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setAutoCancel(true).build()

    companion object {
        private const val CHANNEL = "opening_bid_archive"
        private const val ID = 930
    }
}
