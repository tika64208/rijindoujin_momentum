package com.example.momentum.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DarkTradeDownload(
    val records: List<DarkTradeDataSource.DarkTradeRecord>,
    val totalCount: Int,
    val complete: Boolean,
    val error: String? = null
)

/** 只有逐页收齐已声明总数的记录，才认为全量下载成功。 */
class DarkTradePaginator(
    private val maxPages: Int = 250,
    private val maxAttempts: Int = 3
) {
    suspend fun download(
        fetchPage: suspend (Int) -> DarkTradeDataSource.DarkTradePage,
        pause: suspend () -> Unit = {}
    ): DarkTradeDownload {
        val records = linkedMapOf<String, DarkTradeDataSource.DarkTradeRecord>()
        var expected = -1
        for (page in 1..maxPages) {
            var accepted: DarkTradeDataSource.DarkTradePage? = null
            var error = "第${page}页获取失败"
            for (attempt in 1..maxAttempts) {
                currentCoroutineContext().ensureActive()
                try {
                    val value = fetchPage(page)
                    currentCoroutineContext().ensureActive()
                    when {
                        value.error != null -> error = "第${page}页获取失败: ${value.error}"
                        value.totalCount < 0 -> error = "第${page}页返回无效数据"
                        expected >= 0 && value.totalCount != expected -> error = "下载期间数据总数变化，请重试"
                        value.records.any { it.code.isBlank() } -> error = "第${page}页缺少股票代码"
                        value.records.map { it.code }.distinct().size != value.records.size -> error = "第${page}页记录重复"
                        value.records.isNotEmpty() && value.records.all { it.code in records } ->
                            error = "第${page}页未返回新记录，已获取${records.size}/${value.totalCount}条"
                        value.records.isEmpty() && !(page == 1 && value.totalCount == 0) -> error = "第${page}页数据缺失"
                        else -> accepted = value
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "第${page}页获取失败: ${e.message}"
                }
                if (accepted != null) break
                if (attempt < maxAttempts) pause()
            }
            val value = accepted ?: return DarkTradeDownload(records.values.toList(), expected, false, error)
            expected = value.totalCount
            value.records.forEach { records[it.code] = it }
            if (records.size == expected) return DarkTradeDownload(records.values.toList(), expected, true)
            if (records.size > expected) return DarkTradeDownload(records.values.toList(), expected, false, "记录数超过声明总数")
            pause()
        }
        return DarkTradeDownload(records.values.toList(), expected, false, "超过分页上限，数据未下载完整")
    }
}
