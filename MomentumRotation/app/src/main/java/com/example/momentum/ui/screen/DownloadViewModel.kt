package com.example.momentum.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.momentum.data.AStockDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 全 A 股历史日线下载进度状态。
 */
data class DownloadState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val currentCode: String = "",
    val currentName: String = "",
    val finished: Boolean = false,
    val downloaded: Int = 0,
    val skipped: Int = 0
) {
    val progressPercent: Int
        get() = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
}

/**
 * 下载器 ViewModel: 启动时触发增量下载, 暴露进度。
 */
class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state

    private val downloader = AStockDownloader(getApplication())

    /** 启动一次增量下载(若已在运行则忽略)。 */
    fun startIfIdle() {
        if (_state.value.running) return
        _state.value = DownloadState(running = true)
        viewModelScope.launch {
            try {
                val (downloaded, skipped) = downloader.run { p ->
                    _state.value = _state.value.copy(
                        total = p.total, done = p.done,
                        currentCode = p.currentCode, currentName = p.currentName
                    )
                }
                _state.value = _state.value.copy(
                    running = false, finished = true,
                    downloaded = downloaded, skipped = skipped
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, finished = true)
            }
        }
    }
}
