package com.example.momentum.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.momentum.data.db.AppDatabase
import com.example.momentum.data.db.toBidResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * 备份文件元信息。
 */
data class BackupFileInfo(
    val file: File,
    val name: String,
    val date: String,
    val sizeBytes: Long,
    val lastModified: Long
) {
    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> String.format("%.1f KB", sizeBytes / 1024.0)
            else -> String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0))
        }
}

/**
 * 每日数据本地 CSV 自动备份管理器。
 *
 * 目录规划：
 * - 公共下载目录：Download/MomentumRotation/daily_backup/ (文件管理器直接可见)
 * - 应用外部沙盒：Android/data/.../files/daily_backup/ (安全双重保障)
 */
object DailyBackupManager {

    private const val BACKUP_SUBDIR = "MomentumRotation/daily_backup"

    /**
     * 获取公共存储备份目录。
     */
    fun getPublicBackupDir(): File {
        @Suppress("DEPRECATION")
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloadDir, BACKUP_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取应用内部沙盒备份目录。
     */
    fun getAppBackupDir(context: Context): File {
        val dir = File(context.getExternalFilesDir("daily_backup") ?: context.filesDir, "bid")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 执行全量备份今日竞价数据至本地 CSV 文件。
     * 自动写入公共下载目录与沙盒目录，并写入 MediaStore 索引。
     */
    suspend fun backupTodayBidData(
        context: Context,
        date: String = LocalDate.now().toString()
    ): Result<BackupFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.get(context)
                val entities = db.bidDao().getByDate(date)
                if (entities.isEmpty()) {
                    return@withContext Result.failure(RuntimeException("今日暂无竞价数据，请先点击【刷新】获取"))
                }

                val fullResults = entities.map { it.toBidResult() }
                val csvContent = DataExporter.generateBidCsv(fullResults)
                val fileName = "竞价全量备份_$date.csv"
                val bytes = csvContent.toByteArray(Charsets.UTF_8)

                // 1. 保存到应用沙盒备份目录
                val appFile = File(getAppBackupDir(context), fileName)
                appFile.writeBytes(bytes)

                // 2. 保存到公共 Download/MomentumRotation/daily_backup/
                val publicDir = getPublicBackupDir()
                val publicFile = File(publicDir, fileName)
                try {
                    publicFile.writeBytes(bytes)
                } catch (_: Exception) {}

                // 3. Android 10+ 同步更新系统下载媒体库索引
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_SUBDIR")
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
                    } catch (_: Exception) {}
                }

                val targetFile = if (publicFile.exists()) publicFile else appFile
                val info = BackupFileInfo(
                    file = targetFile,
                    name = fileName,
                    date = date,
                    sizeBytes = targetFile.length(),
                    lastModified = targetFile.lastModified()
                )
                Result.success(info)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 获取所有已备份的本地 CSV 文件列表（按修改时间倒序）。
     */
    fun listBackupFiles(context: Context): List<BackupFileInfo> {
        val publicFiles = getPublicBackupDir().listFiles { f -> f.extension.equals("csv", ignoreCase = true) } ?: emptyArray()
        val appFiles = getAppBackupDir(context).listFiles { f -> f.extension.equals("csv", ignoreCase = true) } ?: emptyArray()

        val allFiles = (publicFiles.toList() + appFiles.toList())
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }

        return allFiles.map { f ->
            val dateStr = f.name.substringAfter("竞价全量备份_").substringBefore(".csv")
            BackupFileInfo(
                file = f,
                name = f.name,
                date = dateStr,
                sizeBytes = f.length(),
                lastModified = f.lastModified()
            )
        }
    }

    /**
     * 唤起系统分享发送指定的备份 CSV 文件。
     */
    fun shareBackupFile(context: Context, file: File) {
        val fileUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "发送备份文件: ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * 删除指定的备份文件。
     */
    fun deleteBackupFile(context: Context, info: BackupFileInfo): Boolean {
        var ok = false
        val publicFile = File(getPublicBackupDir(), info.name)
        if (publicFile.exists()) ok = publicFile.delete() || ok
        val appFile = File(getAppBackupDir(context), info.name)
        if (appFile.exists()) ok = appFile.delete() || ok
        return ok
    }
}
