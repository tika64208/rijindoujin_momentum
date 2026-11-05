package com.example.momentum.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.momentum.data.BidDistributionRepository
import com.example.momentum.engine.BidDistribution
import com.example.momentum.util.DailyBackupManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BidDistributionState(
    val dates: List<String> = emptyList(),
    val selectedDate: String? = null,
    val report: BidDistribution? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

class BidDistributionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BidDistributionRepository(application)
    private val _state = MutableStateFlow(BidDistributionState())
    val state: StateFlow<BidDistributionState> = _state
    private var loadJob: Job? = null

    init { refresh() }

    /** 切换日期立即清除旧图，避免异步请求结束后串到另一个日期。 */
    fun selectDate(date: String) = load(date)
    fun refresh() = load(_state.value.selectedDate)

    private fun load(requestedDate: String?) {
        loadJob?.cancel()
        _state.update { it.copy(selectedDate = requestedDate, report = null, loading = true, error = null, message = null) }
        loadJob = viewModelScope.launch {
            try {
                val dates = repository.dates()
                val selected = requestedDate?.takeIf { it in dates } ?: dates.firstOrNull()
                val report = selected?.let { repository.load(it) }
                _state.update { it.copy(dates = dates, selectedDate = selected, report = report, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "读取分布失败：${e.message}") }
            }
        }
    }

    fun save(shareAfter: Boolean = false) {
        val report = _state.value.report ?: return
        if (_state.value.saving || _state.value.loading) return
        _state.update { it.copy(saving = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val saved = repository.save(report)
                _state.update { current ->
                    if (current.selectedDate == report.date && current.report?.source == report.source) {
                        current.copy(report = saved, saving = false, message = "已存盘 CSV / JSON，可离线选择日期查看")
                    } else current.copy(saving = false)
                }
                if (shareAfter) DailyBackupManager.shareBackupFile(getApplication(), repository.csvFile(saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { current ->
                    if (current.selectedDate == report.date) current.copy(saving = false,
                        error = "分布文件导出失败：${e.message}；可点击存盘重试")
                    else current.copy(saving = false)
                }
            }
        }
    }
}
