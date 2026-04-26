package com.dxkj.myshell.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
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
fun FilesScreen(
    contentPadding: PaddingValues,
    /** 与终端当前会话同一主机：进入文件页时自动连接并列出远程目录（ShellBean 式联动） */
    linkedHostId: Long? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val vm: FilesViewModel = viewModel(factory = FilesViewModel.factory(context.applicationContext as Application))
    val hosts by vm.hosts.collectAsState()
    val ui by vm.ui.collectAsState()

    var selectedHostId by remember { mutableStateOf<Long?>(null) }

    val hostListKey = remember(hosts) { hosts.joinToString(",") { it.id.toString() } }
    LaunchedEffect(linkedHostId, hostListKey) {
        val hid = linkedHostId?.takeIf { it > 0 } ?: return@LaunchedEffect
        if (hosts.none { it.id == hid }) return@LaunchedEffect
        selectedHostId = hid
        vm.connect(hid)
    }
    var showMkdir by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<RemoteEntryUi?>(null) }
    var pendingRename by remember { mutableStateOf<RemoteEntryUi?>(null) }
    var renameTo by remember { mutableStateOf("") }
    var pendingEdit by remember { mutableStateOf<RemoteEntryUi?>(null) }
    var editText by remember { mutableStateOf("") }
    var editLoading by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var pendingChmod by remember { mutableStateOf<RemoteEntryUi?>(null) }
    var chmodInput by remember { mutableStateOf("") }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
            val id = selectedHostId
            if (id != null) {
                vm.enqueueUpload(hostId = id, remoteDir = ui.currentPath, uri = uri)
            } else {
                vm.setStatus("请先选择主机", ok = false)
            }
        }
    }

    val displayEntries = remember(ui.entries, ui.showDotfiles) {
        ui.entries
            .filter { ui.showDotfiles || !it.name.startsWith(".") }
            .sortedWith(compareByDescending<RemoteEntryUi> { it.isDir }.thenBy { it.name.lowercase() })
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        if (ui.status != null) {
            Text(
                text = ui.status ?: "",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = if (ui.statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (ui.progressActive) {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                if (ui.progressValue != null) {
                    LinearProgressIndicator(progress = { ui.progressValue ?: 0f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (ui.progressText != null) {
                    Text(ui.progressText ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!ui.connected) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    if (ui.connecting) "连接中…" else "未连接",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .simpleVerticalScrollbar(listState),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(displayEntries, key = { it.path }) { e ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (e.isDir) vm.list(e.path)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = if (e.isDir) Icons.Outlined.Folder else Icons.Outlined.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                )
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildString {
                                            append(if (e.isDir) "目录" else "文件")
                                            if (e.modeOctal.isNotBlank()) append(" · ").append(e.modeOctal)
                                            if (!e.isDir) append(" · ").append("${e.size} B")
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                CompactIconTap(
                                    onClick = {
                                        if (e.isDir) vm.list(e.path) else {
                                            pendingEdit = e
                                            editLoading = true
                                            vm.readRemoteText(e.path) { ok, textOrErr ->
                                                editLoading = false
                                                editText = if (ok) textOrErr else "加载失败：$textOrErr"
                                            }
                                        }
                                    },
                                    enabled = true,
                                    icon = if (e.isDir) Icons.Outlined.FolderOpen else Icons.Outlined.Edit,
                                    contentDescription = if (e.isDir) "打开" else "编辑",
                                    boxSize = 28.dp,
                                    iconSize = 16.dp,
                                )
                                CompactIconTap(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(e.path))
                                        vm.setStatus("已复制路径", ok = true)
                                    },
                                    enabled = true,
                                    icon = Icons.Outlined.ContentCopy,
                                    contentDescription = "复制地址",
                                    boxSize = 28.dp,
                                    iconSize = 16.dp,
                                )
                                CompactIconTap(
                                    onClick = {
                                        pendingRename = e
                                        renameTo = e.name
                                    },
                                    enabled = true,
                                    icon = Icons.Outlined.DriveFileRenameOutline,
                                    contentDescription = "重命名",
                                    boxSize = 28.dp,
                                    iconSize = 16.dp,
                                )
                                CompactIconTap(
                                    onClick = {
                                        selectedHostId?.let { id ->
                                            vm.enqueueDownload(hostId = id, remotePath = e.path, filename = e.name)
                                        }
                                    },
                                    enabled = !e.isDir && selectedHostId != null,
                                    icon = Icons.Outlined.CloudDownload,
                                    contentDescription = "下载",
                                    boxSize = 28.dp,
                                    iconSize = 16.dp,
                                )
                                CompactIconTap(
                                    onClick = {
                                        pendingChmod = e
                                        chmodInput = e.modeOctal.ifBlank { "644" }
                                    },
                                    enabled = true,
                                    icon = Icons.Outlined.Info,
                                    contentDescription = "文件权限",
                                    boxSize = 28.dp,
                                    iconSize = 16.dp,
                                )
                                CompactIconTap(
                                    onClick = { pendingDelete = e },
                                    enabled = true,
                                    icon = Icons.Outlined.Delete,
                                    contentDescription = "删除",
                                    boxSize = 28.dp,
                                    iconSize = 16.dp,
                                )
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(120.dp)) }
            }
        }

        // 底部一行：紧凑图标 + 路径（/home/cuid 样式，段与段更贴）
        if (ui.connected) {
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 1.dp,
                modifier = Modifier.navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 3.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    val dBox = 28.dp
                    val dIcon = 17.dp
                    CompactIconTap(onClick = { showNewFileDialog = true }, enabled = !ui.loading, icon = Icons.Outlined.NoteAdd, contentDescription = "新建文件", boxSize = dBox, iconSize = dIcon)
                    CompactIconTap(onClick = { showMkdir = true }, enabled = !ui.loading, icon = Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹", boxSize = dBox, iconSize = dIcon)
                    CompactIconTap(onClick = { pickUpload.launch(arrayOf("*/*")) }, enabled = !ui.loading, icon = Icons.Outlined.CloudUpload, contentDescription = "上传文件", boxSize = dBox, iconSize = dIcon)
                    CompactIconTap(onClick = { vm.list(ui.currentPath) }, enabled = !ui.loading, icon = Icons.Outlined.Refresh, contentDescription = "刷新目录", boxSize = dBox, iconSize = dIcon)
                    CompactIconTap(
                        onClick = { vm.setShowDotfiles(!ui.showDotfiles) },
                        enabled = true,
                        icon = if (ui.showDotfiles) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = "显示隐藏文件",
                        boxSize = dBox,
                        iconSize = dIcon,
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .width(1.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    )
                    ClickableRemotePath(
                        currentPath = ui.currentPath,
                        enabled = !ui.loading,
                        onNavigate = { vm.list(it) },
                        modifier = Modifier.padding(start = 2.dp, end = 4.dp),
                    )
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

    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("新建文件") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("文件名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createEmptyFile(newFileName)
                        newFileName = ""
                        showNewFileDialog = false
                    },
                    enabled = newFileName.trim().isNotEmpty(),
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFileDialog = false }) { Text("取消") } },
        )
    }

    if (pendingChmod != null) {
        val e = pendingChmod!!
        AlertDialog(
            onDismissRequest = { pendingChmod = null },
            title = { Text("文件权限") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("当前（八进制，低 12 位）：${e.modeOctal.ifBlank { "未知" }}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = chmodInput,
                        onValueChange = { chmodInput = it },
                        label = { Text("新权限（八进制）") },
                        singleLine = true,
                        placeholder = { Text("例如 644 或 0755") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.chmodRemote(e.path, chmodInput)
                        pendingChmod = null
                    },
                    enabled = chmodInput.trim().isNotEmpty(),
                ) { Text("应用") }
            },
            dismissButton = { TextButton(onClick = { pendingChmod = null }) { Text("取消") } },
        )
    }
}

/** 小圆形图标区：比默认 IconButton 更紧凑 */
@Composable
private fun CompactIconTap(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: ImageVector,
    contentDescription: String?,
    boxSize: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f)
    }
    Box(
        modifier = modifier
            .size(boxSize)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}

/** 底部路径：视觉上接近 `/home/cuid`，`/` 与每段目录名可点，斜杠分隔更紧凑 */
@Composable
private fun ClickableRemotePath(
    currentPath: String,
    enabled: Boolean,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val norm = currentPath.trim().removeSuffix("/").ifBlank { "/" }
    val parts = if (norm == "/" || norm.isEmpty()) {
        emptyList()
    } else {
        norm.removePrefix("/").split('/').filter { it.isNotEmpty() }
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val sepColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val labelStyle = MaterialTheme.typography.labelMedium
    val sepStyle = MaterialTheme.typography.labelSmall

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "/",
            style = labelStyle,
            color = linkColor,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = enabled) { onNavigate("/") }
                .padding(horizontal = 2.dp, vertical = 1.dp),
        )
        var acc = ""
        parts.forEachIndexed { idx, part ->
            if (idx > 0) {
                Text(
                    text = "/",
                    style = sepStyle,
                    color = sepColor,
                    modifier = Modifier.padding(horizontal = 0.dp),
                )
            }
            acc = if (acc.isEmpty()) "/$part" else "$acc/$part"
            val targetPath = acc
            Text(
                text = part,
                style = labelStyle,
                color = linkColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = enabled) { onNavigate(targetPath) }
                    .padding(horizontal = 2.dp, vertical = 1.dp),
            )
        }
    }
}

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
    /** 低 12 位 POSIX mode 的八进制字符串（如 644、755），用于展示/编辑权限 */
    val modeOctal: String = "",
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
    /** 为 true 时列表与「列目录成功：N 项」均包含以 `.` 开头的项 */
    val showDotfiles: Boolean = false,
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

    private fun displayedEntryCount(entries: List<RemoteEntryUi>, showDotfiles: Boolean): Int =
        if (showDotfiles) entries.size else entries.count { !it.name.startsWith(".") }

    private fun listSuccessStatus(entries: List<RemoteEntryUi>, showDotfiles: Boolean): String =
        "列目录成功：${displayedEntryCount(entries, showDotfiles)} 项"

    fun setShowDotfiles(show: Boolean) {
        val cur = _ui.value
        val newStatus =
            if (cur.entries.isNotEmpty() && cur.status?.startsWith("列目录成功") == true) {
                listSuccessStatus(cur.entries, show)
            } else {
                cur.status
            }
        _ui.value = cur.copy(showDotfiles = show, status = newStatus)
    }

    fun connect(hostId: Long) {
        viewModelScope.launch {
            if (_ui.value.connected && _ui.value.connectedHostId == hostId) {
                // already connected to same host
                return@launch
            }
            _ui.value = _ui.value.copy(connecting = true, status = null, statusOk = false, entries = emptyList(), showDotfiles = false)
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
            _ui.value = _ui.value.copy(
                connected = false,
                connectedHostId = null,
                currentPath = "/",
                status = "已断开",
                statusOk = true,
                entries = emptyList(),
                showDotfiles = false,
            )
        }
    }

    fun list(path: String) {
        viewModelScope.launch {
            val showDot = _ui.value.showDotfiles
            _ui.value = _ui.value.copy(loading = true, status = null, entries = emptyList())
            val r = sftp.list(path)
            val disconnected = !r.ok && r.message.contains("connection abort", ignoreCase = true)
            if (disconnected) {
                sftp.disconnect()
                _ui.value = _ui.value.copy(connected = false, connectedHostId = null)
            }
            _ui.value = _ui.value.copy(
                loading = false,
                status = when {
                    disconnected -> "连接已断开，请重新连接"
                    r.ok -> listSuccessStatus(r.entries, showDot)
                    else -> r.message
                },
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

    fun createEmptyFile(fileName: String) {
        viewModelScope.launch {
            val n = fileName.trim()
            if (n.isEmpty()) return@launch
            val base = _ui.value.currentPath
            val remote = if (base.endsWith("/")) base + n else "$base/$n"
            _ui.value = _ui.value.copy(status = "创建文件中…", statusOk = true)
            val r = sftp.writeTextFile(remote, "")
            val ok = r.isSuccess
            _ui.value = _ui.value.copy(
                status = if (ok) "已创建：$remote" else "创建失败：${r.exceptionOrNull()?.message}",
                statusOk = ok,
            )
            list(base)
        }
    }

    fun chmodRemote(remotePath: String, modeOctal: String) {
        viewModelScope.launch {
            val msg = sftp.chmod(remotePath, modeOctal)
            _ui.value = _ui.value.copy(status = msg, statusOk = msg.contains("已更新"))
            list(_ui.value.currentPath)
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

