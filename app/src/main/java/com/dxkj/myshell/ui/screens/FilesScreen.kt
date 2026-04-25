package com.dxkj.myshell.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.dxkj.myshell.sftp.SftpClientManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FilesScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val vm: FilesViewModel = viewModel(factory = FilesViewModel.factory(context.applicationContext as Application))
    val hosts by vm.hosts.collectAsState()
    val ui by vm.ui.collectAsState()

    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    var path by remember { mutableStateOf("/") }
    var uploadUri by remember { mutableStateOf<Uri?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<RemoteEntryUi?>(null) }
    var pendingRename by remember { mutableStateOf<RemoteEntryUi?>(null) }
    var renameTo by remember { mutableStateOf("") }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uploadUri = uri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "文件（SFTP）", style = MaterialTheme.typography.headlineSmall)

        if (hosts.isEmpty()) {
            Text("请先在「主机」页添加一个主机")
            return@Column
        }

        Text("选择主机：")
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(hosts, key = { it.id }) { h ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { selectedHostId = h.id }) { Text(if (selectedHostId == h.id) "已选" else "选择") }
                    Text("${h.name} (${h.username}@${h.host}:${h.port})")
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
            ) { Text(if (ui.connecting) "连接中…" else "连接") }

            Button(onClick = { vm.disconnect() }, enabled = ui.connected) { Text("断开") }
        }

        if (ui.status != null) {
            Text(ui.status ?: "", color = if (ui.statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        if (ui.progressActive) {
            if (ui.progressValue != null) {
                LinearProgressIndicator(progress = { ui.progressValue ?: 0f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (ui.progressText != null) {
                Text(ui.progressText ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("路径") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = ui.connected,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.list(path) }, enabled = ui.connected && !ui.loading) { Text(if (ui.loading) "加载中…" else "列目录") }
            Button(
                onClick = {
                    path = vm.parentPath(path)
                    vm.list(path)
                },
                enabled = ui.connected,
            ) { Text("上级") }
            Button(onClick = { showMkdir = true }, enabled = ui.connected) { Text("新建目录") }
            Button(onClick = { pickUpload.launch(arrayOf("*/*")) }, enabled = ui.connected) { Text("选择上传文件") }
            Button(
                onClick = {
                    val uri = uploadUri ?: return@Button
                    vm.upload(path, uri, context.contentResolver)
                },
                enabled = ui.connected && uploadUri != null,
            ) { Text("上传") }
        }

        if (ui.entries.isNotEmpty()) {
            Text("目录内容：", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(ui.entries, key = { it.path }) { e ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(e.name)
                            Text(
                                "${if (e.isDir) "DIR" else "FILE"}  size=${e.size}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (e.isDir) {
                            Button(onClick = { path = e.path; vm.list(path) }) { Text("进入") }
                        } else {
                            Button(onClick = { vm.download(e.path, e.name, context.contentResolver) }) { Text("下载") }
                        }
                        Button(onClick = { pendingRename = e; renameTo = e.name }) { Text("重命名") }
                        Button(onClick = { pendingDelete = e }) { Text("删除") }
                    }
                }
            }
        } else {
            Text("（暂无列表）")
        }
    }

    if (showMkdir) {
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("新建目录") },
            text = {
                OutlinedTextField(
                    value = mkdirName,
                    onValueChange = { mkdirName = it },
                    label = { Text("目录名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val dir = if (path.endsWith("/")) path + mkdirName else "$path/$mkdirName"
                        vm.mkdir(dir)
                        mkdirName = ""
                        showMkdir = false
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("取消") } },
        )
    }

    if (pendingDelete != null) {
        val e = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除？") },
            text = { Text(e.path) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(e.path)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (pendingRename != null) {
        val e = pendingRename!!
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameTo,
                    onValueChange = { renameTo = it },
                    label = { Text("新名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parent = vm.parentPath(e.path)
                        val newPath = if (parent.endsWith("/")) parent + renameTo else "$parent/$renameTo"
                        vm.rename(e.path, newPath)
                        pendingRename = null
                    },
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text("取消") } },
        )
    }
}

data class RemoteEntryUi(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
)

data class FilesUi(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val loading: Boolean = false,
    val status: String? = null,
    val statusOk: Boolean = false,
    val entries: List<RemoteEntryUi> = emptyList(),
    val progressActive: Boolean = false,
    val progressText: String? = null,
    val progressValue: Float? = null,
)

class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val db = DbProvider.get(app)
    private val hostRepo = HostRepository(db.hostDao())
    private val keyRepo = KeyRepository(db.keyDao())
    private val sftp = SftpClientManager(keyRepo = keyRepo)

    val hosts: StateFlow<List<HostEntity>> =
        hostRepo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(FilesUi())
    val ui: StateFlow<FilesUi> = _ui

    fun connect(hostId: Long) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(connecting = true, status = null, statusOk = false)
            val host = withContext(Dispatchers.IO) { hostRepo.getById(hostId) }
            if (host == null) {
                _ui.value = _ui.value.copy(connecting = false, status = "主机不存在", statusOk = false)
                return@launch
            }
            val r = withContext(Dispatchers.IO) { sftp.connect(host) }
            _ui.value = _ui.value.copy(connecting = false, connected = r.ok, status = r.message, statusOk = r.ok)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            sftp.disconnect()
            _ui.value = _ui.value.copy(connected = false, status = "已断开", statusOk = true, entries = emptyList())
        }
    }

    fun list(path: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, status = null, entries = emptyList())
            val r = sftp.list(path)
            _ui.value = _ui.value.copy(
                loading = false,
                status = r.message,
                statusOk = r.ok,
                entries = r.entries,
            )
        }
    }

    fun mkdir(path: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(status = "创建中…", statusOk = true)
            val msg = sftp.mkdir(path)
            _ui.value = _ui.value.copy(status = msg, statusOk = msg.startsWith("创建目录成功"))
            list(parentPath(path))
        }
    }

    fun delete(remotePath: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(status = "删除中…", statusOk = true)
            val msg = sftp.rm(remotePath)
            _ui.value = _ui.value.copy(status = msg, statusOk = msg.startsWith("删除成功"))
            list(parentPath(remotePath))
        }
    }

    fun rename(oldPath: String, newPath: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(status = "重命名中…", statusOk = true)
            val msg = sftp.rename(oldPath, newPath)
            _ui.value = _ui.value.copy(status = msg, statusOk = msg.startsWith("重命名成功"))
            list(parentPath(newPath))
        }
    }

    fun parentPath(path: String): String {
        val p = path.trim().ifBlank { "/" }
        if (p == "/") return "/"
        val norm = if (p.endsWith("/")) p.dropLast(1) else p
        val idx = norm.lastIndexOf('/')
        return if (idx <= 0) "/" else norm.substring(0, idx)
    }

    fun download(remotePath: String, filename: String, resolver: android.content.ContentResolver) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(progressActive = true, progressText = "下载中…", progressValue = null)
            val msg = sftp.downloadToDownloads(remotePath, filename, resolver) { sent, total ->
                val v = if (total > 0) (sent.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                _ui.value = _ui.value.copy(
                    progressActive = true,
                    progressText = if (total > 0) "下载 ${sent}/${total} bytes" else "下载 ${sent} bytes",
                    progressValue = v,
                )
            }
            _ui.value = _ui.value.copy(status = msg, statusOk = msg.startsWith("下载完成"))
            _ui.value = _ui.value.copy(progressActive = false, progressText = null, progressValue = null)
        }
    }

    fun upload(remoteDir: String, uri: Uri, resolver: android.content.ContentResolver) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(progressActive = true, progressText = "上传中…", progressValue = null)
            val msg = sftp.uploadFromUri(remoteDir, uri, resolver) { sent, total ->
                val v = if (total > 0) (sent.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                _ui.value = _ui.value.copy(
                    progressActive = true,
                    progressText = if (total > 0) "上传 ${sent}/${total} bytes" else "上传 ${sent} bytes",
                    progressValue = v,
                )
            }
            _ui.value = _ui.value.copy(status = msg, statusOk = msg.startsWith("上传完成"))
            _ui.value = _ui.value.copy(progressActive = false, progressText = null, progressValue = null)
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return FilesViewModel(app) as T
                }
            }
    }
}

