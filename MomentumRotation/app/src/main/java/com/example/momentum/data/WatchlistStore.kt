package com.example.momentum.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 自选股存取工具 (跨 Tab 共享)。
 *
 * 存储格式与 MomentumViewModel 保持一致:
 * - SharedPreferences "monitor": watch_codes / watch_names 逗号分隔
 * - version 流: 任何写入后自增, 供自选股页监听刷新(免重启)
 */
object WatchlistStore {

    /** 自选股版本号: 外部写入后 +1, 自选股页 collect 到变化即重载。 */
    val version = MutableStateFlow(0)

    fun getPrefs(context: Context) =
        context.getSharedPreferences("monitor", Context.MODE_PRIVATE)

    fun readAll(context: Context): List<Pair<String, String>> {
        val prefs = getPrefs(context)
        val codes = (prefs.getString("watch_codes", "") ?: "").split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }
        val names = (prefs.getString("watch_names", "") ?: "").split(",")
        return codes.mapIndexed { i, c -> c to (names.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: c) }
    }

    /**
     * 追加自选股(自动去重, 保留原顺序)。
     * @return 本次实际新增的数量
     */
    fun addAll(context: Context, stocks: List<Pair<String, String>>): Int {
        val existing = readAll(context).toMutableList()
        val existingCodes = existing.map { it.first }.toHashSet()
        var added = 0
        for ((code, name) in stocks) {
            if (code.isNotBlank() && code !in existingCodes) {
                existing.add(code to name)
                existingCodes.add(code)
                added++
            }
        }
        if (added > 0) {
            getPrefs(context).edit()
                .putString("watch_codes", existing.joinToString(",") { it.first })
                .putString("watch_names", existing.joinToString(",") { it.second })
                .apply()
            version.value += 1
        }
        return added
    }
}
