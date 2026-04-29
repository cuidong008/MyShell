package com.dxkj.myshell.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dxkj.myshell.ui.theme.Dimens
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.db.HostEntity
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.data.repo.KeyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import android.content.Context

@Composable
fun TerminalScreen(
    contentPadding: PaddingValues,
    onOpenFullTerminal: (Long) -> Unit,
) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE) }
    val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(context.applicationContext as Application))
    val hosts by vm.hosts.collectAsState()
    val ui by vm.ui.collectAsState()

    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    val lastHostId = remember { prefs.getLong("lastHostId", -1L).takeIf { it > 0 } }
    val recentIds = remember {
        (prefs.getString("recentHostIds", "") ?: "")
            .split(',')
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toLongOrNull() }
            .filter { it > 0 }
    }
    LaunchedEffect(hosts.size) {
        if (selectedHostId == null && lastHostId != null && hosts.any { it.id == lastHostId }) {
            selectedHostId = lastHostId
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(
                horizontal = Dimens.ScreenPaddingH,
                vertical = if (isLandscape) 12.dp else Dimens.ScreenPaddingV,
            ),
        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 10.dp else Dimens.SpacingMd),
    ) {
        Text(text = "终端", style = MaterialTheme.typography.titleLarge)

        if (hosts.isEmpty()) {
            Text("请先在「主机」页添加一个主机")
            return@Column
        }

        Text("选择主机：")
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.Spacing2),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpacingXs),
        ) {
            items(hosts, key = { it.id }) { h ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { selectedHostId = h.id },
                    ) {
                        Text(if (selectedHostId == h.id) "已选" else "选择")
                    }
                    Column {
                        Text(h.name, style = MaterialTheme.typography.titleMedium)
                        Text("${h.username}@${h.host}:${h.port}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingMd)) {
            Button(
                onClick = {
                    val id = selectedHostId ?: return@Button
                    onOpenFullTerminal(id)
                },
                enabled = selectedHostId != null,
            ) { Text("打开全屏终端") }

            if (lastHostId != null && selectedHostId != lastHostId) {
                Button(
                    onClick = { onOpenFullTerminal(lastHostId) },
                    enabled = hosts.any { it.id == lastHostId },
                ) { Text("继续上次会话") }
            }
        }

        if (recentIds.isNotEmpty()) {
            Text("最近会话：", style = MaterialTheme.typography.titleMedium)
            val recentHosts = remember(hosts, recentIds) {
                val map = hosts.associateBy { it.id }
                recentIds.mapNotNull { map[it] }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.Spacing2),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpacingXs),
            ) {
                items(recentHosts, key = { it.id }) { h ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm, Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { onOpenFullTerminal(h.id) }) { Text("打开") }
                        Column {
                            Text(h.name, style = MaterialTheme.typography.titleMedium)
                            Text("${h.username}@${h.host}:${h.port}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (ui.status != null) {
            Text(ui.status ?: "", color = if (ui.statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}

data class TerminalUi(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val status: String? = null,
    val statusOk: Boolean = false,
)

class TerminalViewModel(app: Application) : AndroidViewModel(app) {
    private val db = DbProvider.get(app)
    private val hostRepo = HostRepository(db.hostDao())

    val hosts: StateFlow<List<HostEntity>> =
        hostRepo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(TerminalUi())
    val ui: StateFlow<TerminalUi> = _ui

    // 终端连接/断开已迁移到全屏页的 ViewModel；这里仅保留主机列表供选择。

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TerminalViewModel(app) as T
                }
            }
    }
}

