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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.db.HostEntity
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.data.repo.KeyRepository
import com.dxkj.myshell.ssh.SshSessionManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TerminalScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(context.applicationContext as Application))
    val hosts by vm.hosts.collectAsState()
    val ui by vm.ui.collectAsState()

    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "终端", style = MaterialTheme.typography.headlineSmall)

        if (hosts.isEmpty()) {
            Text("请先在「主机」页添加一个主机")
            return@Column
        }

        Text("选择主机：")
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(hosts, key = { it.id }) { h ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
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

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val id = selectedHostId
                    if (id != null) vm.connect(id)
                },
                enabled = !ui.connecting && !ui.connected,
            ) { Text(if (ui.connecting) "连接中…" else if (ui.connected) "已连接" else "连接") }

            Button(
                onClick = { vm.disconnect() },
                enabled = ui.connected,
            ) { Text("断开") }
        }

        if (ui.status != null) {
            Text(ui.status ?: "", color = if (ui.statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }

        Divider()

        Text("输出：", style = MaterialTheme.typography.titleMedium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll),
        ) {
            Text(
                text = ui.output,
                fontFamily = FontFamily.Monospace,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("输入") },
                singleLine = true,
                enabled = ui.connected,
            )
            Button(
                onClick = {
                    vm.send(input + "\n")
                    input = ""
                },
                enabled = ui.connected && input.isNotBlank(),
            ) { Text("发送") }
        }
    }
}

data class TerminalUi(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val status: String? = null,
    val statusOk: Boolean = false,
    val output: String = "",
)

class TerminalViewModel(app: Application) : AndroidViewModel(app) {
    private val db = DbProvider.get(app)
    private val hostRepo = HostRepository(db.hostDao())
    private val keyRepo = KeyRepository(db.keyDao())
    private val session = SshSessionManager(keyRepo = keyRepo)

    val hosts: StateFlow<List<HostEntity>> =
        hostRepo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(TerminalUi())
    val ui: StateFlow<TerminalUi> = _ui

    fun connect(hostId: Long) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(connecting = true, status = null, statusOk = false)
            val host = withContext(Dispatchers.IO) { hostRepo.getById(hostId) }
            if (host == null) {
                _ui.value = _ui.value.copy(connecting = false, status = "主机不存在", statusOk = false)
                return@launch
            }
            val result = withContext(Dispatchers.IO) { session.connect(host) }
            _ui.value = _ui.value.copy(
                connecting = false,
                connected = result.ok,
                status = result.message,
                statusOk = result.ok,
            )
            if (result.ok) {
                session.startShell(
                    onOutput = { chunk ->
                        _ui.value = _ui.value.copy(output = (_ui.value.output + chunk).takeLast(50_000))
                    },
                    onClosed = { msg ->
                        _ui.value = _ui.value.copy(connected = false, status = msg, statusOk = false)
                    },
                )
            }
        }
    }

    fun send(text: String) {
        viewModelScope.launch {
            session.send(text)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            session.disconnect()
            _ui.value = _ui.value.copy(connected = false, status = "已断开", statusOk = true)
        }
    }

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

