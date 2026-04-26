package com.dxkj.myshell.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dxkj.myshell.ssh.LinuxHostMetricsSnapshot
import com.dxkj.myshell.ssh.RemoteLinuxMetrics
import com.dxkj.myshell.terminal.TerminalSessionPool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class OverviewHostMonitorCardUi(
    val hostId: Long,
    val title: String,
    val metrics: LinuxHostMetricsSnapshot?,
    val error: String?,
    val refreshing: Boolean,
    val lastUpdatedEpochMs: Long,
)

class OverviewViewModel(application: Application) : AndroidViewModel(application) {

    private val _hosts = MutableStateFlow<List<OverviewHostMonitorCardUi>>(emptyList())
    val hosts: StateFlow<List<OverviewHostMonitorCardUi>> = _hosts.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                pollOnce()
                delay(5_000L)
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { pollOnce() }
    }

    private suspend fun pollOnce() {
        val reps = TerminalSessionPool.sessions.value.filter { it.connected }.distinctBy { it.hostId }
        if (reps.isEmpty()) {
            _hosts.value = emptyList()
            return
        }
        _hosts.value = reps.map { s ->
            OverviewHostMonitorCardUi(
                hostId = s.hostId,
                title = TerminalSessionPool.getDisplayTitle(s),
                metrics = _hosts.value.firstOrNull { it.hostId == s.hostId }?.metrics,
                error = null,
                refreshing = true,
                lastUpdatedEpochMs = _hosts.value.firstOrNull { it.hostId == s.hostId }?.lastUpdatedEpochMs ?: 0L,
            )
        }
        val next = ArrayList<OverviewHostMonitorCardUi>(reps.size)
        for (s in reps) {
            val r = RemoteLinuxMetrics.collect(s.ssh)
            next.add(
                OverviewHostMonitorCardUi(
                    hostId = s.hostId,
                    title = TerminalSessionPool.getDisplayTitle(s),
                    metrics = r.getOrNull(),
                    error = r.exceptionOrNull()?.message,
                    refreshing = false,
                    lastUpdatedEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        _hosts.value = next
    }
}
