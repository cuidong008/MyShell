package com.dxkj.myshell.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.lifecycle.asFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.db.HostEntity
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.data.repo.KeyRepository
import com.dxkj.myshell.sftp.SftpClientManager
import com.dxkj.myshell.transfer.DownloadWorker
import com.dxkj.myshell.transfer.TransferKeys
import com.dxkj.myshell.transfer.UploadWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FilesScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val vm: FilesViewModel = viewModel(factory = FilesViewModel.factory(context.applicationContext as Application))
    val hosts by vm.hosts.collectAsState()
    val ui by vm.ui.collectAsState()

    var selectedHostId by remember { mutableStateOf<Long?>(null) }
    // current path is now driven by ui.currentPath (auto set to home on connect)
    var uploadUri by remember { mutableStateOf<Uri?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<RemoteEntryUi?>(null) }
    var pendingRename by remember { mutableStateOf<RemoteEntryUi?>(null) }
    var renameTo by remember { mutableStateOf("") }
    var pendingEdit by remember { mutableStateOf<RemoteEntryUi?>(null) }
    var editText by remember { mutableStateOf("") }
    var editLoading by remember { mutableStateOf(false) }
    var showHostMenu by remember { mutableStateOf(false) }
    var showTransfers by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var sortDesc by remember { mutableStateOf(false) }
    var entryMenuFor by remember { mutableStateOf<RemoteEntryUi?>(null) }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uploadUri = uri
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
            vm.setStatus("已选择上传文件", ok = true)
        }
    }

    val selectedHost = hosts.firstOrNull { it.id == selectedHostId }
    val filteredEntries = remember(ui.entries, query, sortMode, sortDesc) {
        val q = query.trim().lowercase()
        val base = if (q.isBlank()) ui.entries else ui.entries.filter { it.name.lowercase().contains(q) }
        val comparator = when (sortMode) {
            SortMode.NAME -> compareBy<RemoteEntryUi> { it.name.lowercase() }
            SortMode.SIZE -> compareBy { it.size }
            SortMode.TYPE -> compareByDescending<RemoteEntryUi> { it.isDir }.thenBy { it.name.lowercase() }
        }
        val sorted = base.sortedWith(comparator)
        if (sortDesc) sorted.reversed() else sorted
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        // 顶部工具条（ShellBean 风格：紧凑 + 图标操作）
        Surface(tonalElevation = 2.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        FilledIconButton(onClick = { showHostMenu = true }) {
                            Icon(imageVector = Icons.Outlined.Folder, contentDescription = "host")
                        }
                        DropdownMenu(expanded = showHostMenu, onDismissRequest = { showHostMenu = false }) {
                            hosts.forEach { h ->
                                DropdownMenuItem(
                                    text = { Text(h.name) },
                                    onClick = {
                                        selectedHostId = h.id
                                        showHostMenu = false
                                        vm.connect(h.id) // 选择即自动连接
                                    },
                                )
                            }
                        }
                    }

                    Text(
                        text = selectedHost?.name ?: "选择主机",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )

                    IconButton(
                        onClick = { if (ui.connected) vm.disconnect() },
                        enabled = ui.connected,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "disconnect",
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 路径（面包屑替代：单行可编辑 + 上级按钮）
                    FilledIconButton(
                        onClick = {
                            val p = vm.parentPath(ui.currentPath)
                            vm.list(p)
                        },
                        enabled = ui.connected,
                    ) {
                        Icon(imageVector = Icons.Outlined.ArrowUpward, contentDescription = "up")
                    }
                    Text(
                        text = ui.currentPath,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { vm.list(ui.currentPath) }, enabled = ui.connected && !ui.loading) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "refresh")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("搜索") },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.Search, contentDescription = "search") },
                    )

                    Box {
                        IconButton(onClick = { sortDesc = !sortDesc }) {
                            Icon(imageVector = Icons.Outlined.Sort, contentDescription = "sort")
                        }
                        // 简化：点击排序图标切换排序字段（按 ShellBean 常用逻辑）
                    }

                    FilledIconButton(onClick = { showMkdir = true }, enabled = ui.connected) {
                        Icon(imageVector = Icons.Outlined.CreateNewFolder, contentDescription = "mkdir")
                    }
                    FilledIconButton(onClick = { pickUpload.launch(arrayOf("*/*")) }, enabled = ui.connected) {
                        Icon(imageVector = Icons.Outlined.CloudUpload, contentDescription = "pick upload")
                    }
                    FilledIconButton(
                        onClick = {
                            val id = selectedHostId ?: return@FilledIconButton
                            val uri = uploadUri
                            if (uri == null) {
                                vm.setStatus("请先选择要上传的文件", ok = false)
                                return@FilledIconButton
                            }
                            vm.enqueueUpload(hostId = id, remoteDir = ui.currentPath, uri = uri)
                        },
                        enabled = selectedHostId != null && uploadUri != null,
                    ) {
                        Icon(imageVector = Icons.Outlined.CloudUpload, contentDescription = "bg upload")
                    }
                    FilledIconButton(
                        onClick = { showTransfers = !showTransfers },
                        enabled = ui.transfers.isNotEmpty(),
                    ) {
                        Icon(imageVector = Icons.Outlined.CloudDownload, contentDescription = "transfers")
                    }
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

                if (showTransfers && ui.transfers.isNotEmpty()) {
                    Divider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("传输队列", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { vm.clearFinishedTransfers() }) { Text("清除") }
                        }
                        ui.transfers.take(6).forEach { t ->
                            Text("${t.kind} · ${t.title} · ${t.state} · ${t.progress}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // 文件列表（ShellBean 风格：更紧凑的条目 + 右侧更多操作）
        if (!ui.connected) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text("未连接", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .simpleVerticalScrollbar(listState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredEntries, key = { it.path }) { e ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (e.isDir) {
                                    vm.list(e.path)
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = if (e.isDir) Icons.Outlined.Folder else Icons.Outlined.Description,
                                contentDescription = "type",
                                modifier = Modifier.size(22.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(e.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    (if (e.isDir) "文件夹" else "文件") + " · " + (if (e.isDir) "-" else "${e.size} bytes"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            if (!e.isDir) {
                                IconButton(
                                    onClick = { vm.enqueueDownload(hostId = selectedHostId ?: 0L, remotePath = e.path, filename = e.name) },
                                    enabled = selectedHostId != null,
                                ) {
                                    Icon(imageVector = Icons.Outlined.CloudDownload, contentDescription = "bg download")
                                }
                                IconButton(onClick = {
                                    pendingEdit = e
                                    editLoading = true
                                    vm.readRemoteText(e.path) { ok, textOrErr ->
                                        editLoading = false
                                        editText = if (ok) textOrErr else "加载失败：$textOrErr"
                                    }
                                }) {
                                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = "edit")
                                }
                            }

                            Box {
                                IconButton(onClick = { entryMenuFor = e }) {
                                    Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = "more")
                                }
                                DropdownMenu(
                                    expanded = entryMenuFor?.path == e.path,
                                    onDismissRequest = { entryMenuFor = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = {
                                            pendingRename = e
                                            renameTo = e.name
                                            entryMenuFor = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除") },
                                        onClick = {
                                            pendingDelete = e
                                            entryMenuFor = null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(60.dp))
                }
            }
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
                        val base = ui.currentPath
                        val dir = if (base.endsWith("/")) base + mkdirName else "$base/$mkdirName"
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

    if (pendingEdit != null) {
        val e = pendingEdit!!
        AlertDialog(
            onDismissRequest = { pendingEdit = null },
            title = { Text("编辑：${e.name}") },
            text = {
                if (editLoading) {
                    Text("加载中…")
                } else {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 10,
                        label = { Text("内容") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.writeRemoteText(e.path, editText) { ok, msg ->
                            pendingEdit = null
                        }
                    },
                    enabled = ui.connected && !editLoading,
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { pendingEdit = null }) { Text("取消") } },
        )
    }
}

private enum class SortMode { NAME, SIZE, TYPE }

private fun Modifier.simpleVerticalScrollbar(
    state: androidx.compose.foundation.lazy.LazyListState,
    thickness: Int = 4,
    paddingEnd: Int = 2,
): Modifier {
    return this.drawWithContent {
        drawContent()
        val layoutInfo = state.layoutInfo
        val total = layoutInfo.totalItemsCount
        if (total <= 0) return@drawWithContent
        val visible = layoutInfo.visibleItemsInfo.size
        if (visible <= 0 || visible >= total) return@drawWithContent

        val firstIndex = layoutInfo.visibleItemsInfo.first().index
        val viewportHeight = size.height
        val thumbHeight = (viewportHeight * (visible.toFloat() / total.toFloat())).coerceAtLeast(24f)
        val maxOffset = (viewportHeight - thumbHeight).coerceAtLeast(0f)
        val thumbOffset = (maxOffset * (firstIndex.toFloat() / (total - visible).toFloat())).coerceIn(0f, maxOffset)

        val x = size.width - paddingEnd - thickness
        drawRoundRect(
            color = androidx.compose.ui.graphics.Color(0x66FFFFFF),
            topLeft = Offset(x.toFloat(), thumbOffset),
            size = Size(thickness.toFloat(), thumbHeight),
            cornerRadius = CornerRadius(thickness.toFloat(), thickness.toFloat()),
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
    val connectedHostId: Long? = null,
    val currentPath: String = "/",
    val loading: Boolean = false,
    val status: String? = null,
    val statusOk: Boolean = false,
    val entries: List<RemoteEntryUi> = emptyList(),
    val progressActive: Boolean = false,
    val progressText: String? = null,
    val progressValue: Float? = null,
    val transfers: List<TransferUi> = emptyList(),
)

data class TransferUi(
    val id: String,
    val kind: String,
    val title: String,
    val state: String,
    val progress: String,
)

class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val db = DbProvider.get(app)
    private val hostRepo = HostRepository(db.hostDao())
    private val keyRepo = KeyRepository(db.keyDao())
    private val sftp = SftpClientManager(keyRepo = keyRepo)
    private val work = WorkManager.getInstance(app)
    private val nm = NotificationManagerCompat.from(app)
    private val notified = HashSet<String>()

    init {
        viewModelScope.launch {
            work.getWorkInfosByTagLiveData(TransferKeys.TAG).asFlow().collect { infos ->
                // notify on completion (once)
                infos.forEach { wi ->
                    val id = wi.id.toString()
                    if (wi.state.isFinished && notified.add(id)) {
                        val msg = wi.outputData.getString(TransferKeys.KEY_MESSAGE)
                            ?: wi.progress.getString(TransferKeys.KEY_MESSAGE)
                            ?: (if (wi.state == WorkInfo.State.SUCCEEDED) "传输完成" else "传输失败")
                        val ok = wi.state == WorkInfo.State.SUCCEEDED
                        _ui.value = _ui.value.copy(status = msg, statusOk = ok)
                        try {
                            nm.notify(
                                id.hashCode(),
                                NotificationCompat.Builder(getApplication(), "transfer")
                                    .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                                    .setContentTitle(if (ok) "文件传输完成" else "文件传输失败")
                                    .setContentText(msg)
                                    .setAutoCancel(true)
                                    .build(),
                            )
                        } catch (_: Throwable) {
                        }
                    }
                }
                val list = infos.sortedByDescending { it.runAttemptCount }.map { wi ->
                    val kind = wi.progress.getString(TransferKeys.KEY_KIND)
                        ?: wi.outputData.getString(TransferKeys.KEY_KIND)
                        ?: wi.tags.firstOrNull { it == "upload" || it == "download" }
                        ?: "transfer"
                    val title = wi.progress.getString(TransferKeys.KEY_FILENAME)
                        ?: wi.outputData.getString(TransferKeys.KEY_FILENAME)
                        ?: wi.id.toString()
                    val sent = wi.progress.getLong(TransferKeys.KEY_PROGRESS_SENT, -1L)
                    val total = wi.progress.getLong(TransferKeys.KEY_PROGRESS_TOTAL, -1L)
                    val prog = if (sent >= 0 && total > 0) "${sent}/${total}" else if (sent >= 0) "${sent}" else "-"
                    TransferUi(
                        id = wi.id.toString(),
                        kind = kind,
                        title = title,
                        state = wi.state.name,
                        progress = prog,
                    )
                }
                _ui.value = _ui.value.copy(transfers = list.take(20))
            }
        }
    }

    fun clearFinishedTransfers() {
        viewModelScope.launch {
            try {
                work.pruneWork()
            } catch (_: Throwable) {
            }
            notified.clear()
            _ui.value = _ui.value.copy(status = "已清除已完成记录", statusOk = true)
        }
    }

    val hosts: StateFlow<List<HostEntity>> =
        hostRepo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(FilesUi())
    val ui: StateFlow<FilesUi> = _ui

    fun setStatus(msg: String, ok: Boolean) {
        _ui.value = _ui.value.copy(status = msg, statusOk = ok)
    }

    fun connect(hostId: Long) {
        viewModelScope.launch {
            if (_ui.value.connected && _ui.value.connectedHostId == hostId) {
                // already connected to same host
                return@launch
            }
            _ui.value = _ui.value.copy(connecting = true, status = null, statusOk = false, entries = emptyList())
            val host = withContext(Dispatchers.IO) { hostRepo.getById(hostId) }
            if (host == null) {
                _ui.value = _ui.value.copy(connecting = false, status = "主机不存在", statusOk = false, connected = false, connectedHostId = null)
                return@launch
            }
            val r = withContext(Dispatchers.IO) { sftp.connect(host) }
            if (!r.ok) {
                _ui.value = _ui.value.copy(connecting = false, connected = false, connectedHostId = null, status = r.message, statusOk = false)
                return@launch
            }
            val home = withContext(Dispatchers.IO) { sftp.homeDir().getOrNull() } ?: "/"
            _ui.value = _ui.value.copy(connecting = false, connected = true, connectedHostId = hostId, currentPath = home, status = "连接成功", statusOk = true)
            list(home)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            sftp.disconnect()
            _ui.value = _ui.value.copy(connected = false, connectedHostId = null, currentPath = "/", status = "已断开", statusOk = true, entries = emptyList())
        }
    }

    fun list(path: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, status = null, entries = emptyList())
            val r = sftp.list(path)
            val disconnected = !r.ok && r.message.contains("connection abort", ignoreCase = true)
            if (disconnected) {
                sftp.disconnect()
                _ui.value = _ui.value.copy(connected = false, connectedHostId = null)
            }
            _ui.value = _ui.value.copy(
                loading = false,
                status = if (disconnected) "连接已断开，请重新连接" else r.message,
                statusOk = r.ok && !disconnected,
                currentPath = if (r.ok) path else _ui.value.currentPath,
                entries = if (disconnected) emptyList() else r.entries,
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

    fun download(remotePath: String, filename: String, context: android.content.Context) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(progressActive = true, progressText = "下载中…", progressValue = null)
            val msg = sftp.downloadToDownloads(remotePath, filename, context, context.contentResolver) { sent, total ->
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

    fun enqueueDownload(hostId: Long, remotePath: String, filename: String) {
        if (hostId <= 0) return
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(TransferKeys.TAG)
            .addTag("download")
            .setInputData(
                workDataOf(
                    TransferKeys.KEY_KIND to "download",
                    TransferKeys.KEY_HOST_ID to hostId,
                    TransferKeys.KEY_REMOTE_PATH to remotePath,
                    TransferKeys.KEY_FILENAME to filename,
                ),
            )
            .build()
        work.enqueue(req)
        _ui.value = _ui.value.copy(status = "已加入后台下载队列", statusOk = true)
    }

    fun enqueueUpload(hostId: Long, remoteDir: String, uri: Uri) {
        if (hostId <= 0) return
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .addTag(TransferKeys.TAG)
            .addTag("upload")
            .setInputData(
                workDataOf(
                    TransferKeys.KEY_KIND to "upload",
                    TransferKeys.KEY_HOST_ID to hostId,
                    TransferKeys.KEY_REMOTE_DIR to remoteDir,
                    TransferKeys.KEY_URI to uri.toString(),
                ),
            )
            .build()
        work.enqueue(req)
        _ui.value = _ui.value.copy(status = "已加入后台上传队列", statusOk = true)
    }

    fun readRemoteText(remotePath: String, cb: (ok: Boolean, textOrErr: String) -> Unit) {
        viewModelScope.launch {
            val r = sftp.readTextFile(remotePath)
            cb(r.isSuccess, r.getOrNull() ?: (r.exceptionOrNull()?.message ?: "读取失败"))
        }
    }

    fun writeRemoteText(remotePath: String, text: String, cb: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            val r = sftp.writeTextFile(remotePath, text)
            val ok = r.isSuccess
            _ui.value = _ui.value.copy(status = if (ok) "保存成功" else "保存失败：${r.exceptionOrNull()?.message}", statusOk = ok)
            cb(ok, _ui.value.status ?: "")
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

