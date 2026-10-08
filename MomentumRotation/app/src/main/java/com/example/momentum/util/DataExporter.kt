package com.example.momentum.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.momentum.engine.BidStrategy
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 结构化金融数据导出工具。
 *
 * 功能：
 * - 生成带 UTF-8 BOM 的标准 CSV，支持 Excel / WPS / Python 直接解析
 * - 将导出的文件存入系统公共下载目录 (Downloads)
 * - 唤起原生分享面板（支持一键转发到微信、QQ、WPS、文件管理器等）
 */
object DataExporter {

    /**
     * 将竞价分析的完整结果导出为 CSV 文本。
     */
    fun generateBidCsv(results: List<BidStrategy.BidResult>): String {
        val sb = StringBuilder()
        // 写入 UTF-8 BOM 标记，防止 Windows / Excel 打开中文乱码
        sb.append("\uFEFF")
        // 表头
        sb.append("代码,名称,现价,涨幅%,竞价涨幅%,总市值(亿),涨停概率%,可信度,综合评分,异动状态,行业,概念,成交量(手),成交额(万),换手率%\n")

        for (r in results) {
            val item = r.item
            val code = item.code
            val name = cleanCsv(item.name)
            val price = FinanceFormatter.formatPrice(item.latestPrice)
            val realChange = String.format("%.2f", item.realChange)
            val bidChange = String.format("%.2f", item.bidChange)
            val mvYi = String.format("%.2f", item.circMv / 1e8)
            val prob = r.probability.toString()
            val conf = r.confidence.toString()
            val score = r.score.toString()
            val warnText = cleanCsv(r.warnText)
            val industry = cleanCsv(item.industry)
            val concept = cleanCsv(item.conceptQuick)
            val volumeHand = (item.volume / 100).toString()
            val amountWan = String.format("%.2f", item.amount / 10_000.0)
            val turnover = String.format("%.2f", item.turnover)

            // 代码附加 \t 制表符，防止 Excel 将 0 开头的股票代码识别为数字吞掉前导 0
            sb.append("\t$code,$name,$price,$realChange,$bidChange,$mvYi,$prob,$conf,$score,$warnText,$industry,$concept,$volumeHand,$amountWan,$turnover\n")
        }
        return sb.toString()
    }

    private fun cleanCsv(str: String): String {
        if (str.isEmpty()) return ""
        val clean = str.replace("\"", "\"\"")
        return if (clean.contains(",") || clean.contains("\n") || clean.contains("\"")) {
            "\"$clean\""
        } else {
            clean
        }
    }

    /**
     * 将 CSV 文件保存至公共下载目录并唤起系统分享。
     * @return 保存成功的文件名
     */
    fun exportAndShare(
        context: Context,
        fileNamePrefix: String,
        csvContent: String
    ): Result<String> {
        return try {
            val timeTag = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val fileName = "${fileNamePrefix}_$timeTag.csv"
            val bytes = csvContent.toByteArray(Charsets.UTF_8)

            // 1. 保存到内部缓存区以便通过 FileProvider 唤起分享
            val cacheFile = File(context.cacheDir, fileName)
            cacheFile.writeBytes(bytes)

            // 2. 尝试保存到公共 Download 目录 (Android 10+ MediaStore 或传统目录)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
            } else {
                @Suppress("DEPRECATION")
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (publicDir.exists() || publicDir.mkdirs()) {
                    File(publicDir, fileName).writeBytes(bytes)
                }
            }

            // 3. 唤起系统分享面板 (微信、QQ、邮件、WPS、保存到文件等)
            val fileUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "导出数据: $fileName").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)

            Result.success(fileName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
