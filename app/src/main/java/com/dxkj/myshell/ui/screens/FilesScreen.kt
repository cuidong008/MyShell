package com.dxkj.myshell.ui.screens

import android.app.Application
import android.os.SystemClock
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dxkj.myshell.ui.theme.Dimens
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import java.util.Locale
import android.app.Activity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.unit.sp

/** 列表行主区域：两次轻触间隔内视为双击（目录进入 / 文件编辑） */
private const val FILE_ENTRY_DOUBLE_TAP_MS = 500L

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FilesScreen(
    contentPadding: PaddingValues,
    /** 与终端当前会话同一主机：进入文件页时自动连接并列出远程目录（ShellBean 式联动） */
    linkedHostId: Long? = null,
) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp
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
    var editFullscreen by remember { mutableStateOf(false) }
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
                modifier = Modifier.padding(
                    horizontal = Dimens.ScreenPaddingH,
                    vertical = if (isLandscape) 3.dp else 4.dp,
                ),
                color = if (ui.statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (ui.progressActive) {
            Column(modifier = Modifier.padding(horizontal = Dimens.ScreenPaddingH)) {
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
                    .padding(horizontal = Dimens.SpacingMd, vertical = if (isLandscape) 4.dp else 6.dp)
                    .simpleVerticalScrollbar(
                        listState,
                        thumbColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.40f),
                    ),
                verticalArrangement = Arrangement.spacedBy(if (isLandscape) 3.dp else 4.dp),
            ) {
                items(displayEntries, key = { it.path }) { e ->
                    var firstTapUptime by remember(e.path) { mutableLongStateOf(0L) }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Dimens.Spacing2, horizontal = Dimens.SpacingXs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        val now = SystemClock.uptimeMillis()
                                        if (firstTapUptime != 0L && now - firstTapUptime <= FILE_ENTRY_DOUBLE_TAP_MS) {
                                            firstTapUptime = 0L
                                            if (e.isDir) {
                                                vm.list(e.path)
                                            } else {
                                                pendingEdit = e
                                                editLoading = true
                                                vm.readRemoteText(e.path) { ok, textOrErr ->
                                                    editLoading = false
                                                    editText = if (ok) textOrErr else "加载失败：$textOrErr"
                                                }
                                            }
                                        } else {
                                            firstTapUptime = now
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingXs),
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
                                            if (e.modeOctal.isNotBlank()) {
                                                append(" · ").append(e.modeOctal)
                                            }
                                            if (!e.isDir) append(" · ").append(formatHumanFileSize(e.size))
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing1),
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
                        .padding(vertical = Dimens.Spacing2, horizontal = Dimens.Spacing1),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing1),
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
                            .padding(horizontal = Dimens.Spacing2)
                            .width(1.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    )
                    ClickableRemotePath(
                        currentPath = ui.currentPath,
                        enabled = !ui.loading,
                        onNavigate = { vm.list(it) },
                        modifier = Modifier.padding(start = Dimens.Spacing1, end = Dimens.Spacing2),
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
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSm)) {
                    OutlinedTextField(
                        value = mkdirName,
                        onValueChange = { mkdirName = it },
                        label = { Text("目录名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSm)) {
                    OutlinedTextField(
                        value = renameTo,
                        onValueChange = { renameTo = it },
                        label = { Text("新名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
        RemoteTextEditorDialog(
            title = "编辑：${e.name}",
            loading = editLoading,
            text = editText,
            onTextChange = { editText = it },
            fullscreen = editFullscreen,
            onToggleFullscreen = { editFullscreen = !editFullscreen },
            onDismiss = {
                pendingEdit = null
                editFullscreen = false
            },
            onSave = {
                vm.writeRemoteText(e.path, editText) { _, _ ->
                    pendingEdit = null
                    editFullscreen = false
                }
            },
            saveEnabled = ui.connected && !editLoading,
        )
    }

    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("新建文件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSm)) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSm)) {
                    Text(e.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "当前（八进制）：${e.modeOctal.ifBlank { "未知" }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = chmodInput,
                        onValueChange = { chmodInput = it },
                        label = { Text("新权限（八进制）") },
                        singleLine = true,
                        placeholder = { Text("例如 644 或 0755") },
                        modifier = Modifier.fillMaxWidth(),
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RemoteTextEditorDialog(
    title: String,
    loading: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
) {
    val view = LocalView.current
    val activity = remember(view) { view.context as? Activity }

    // 更接近 Haven：全屏时进入沉浸模式（隐藏状态栏/导航栏，滑动可临时呼出）
    DisposableEffect(fullscreen, activity, view) {
        if (fullscreen && activity != null) {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen && activity != null) {
                val controller = WindowCompat.getInsetsController(activity.window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var tfv by remember { mutableStateOf(TextFieldValue(text, selection = TextRange(text.length))) }
    LaunchedEffect(text) {
        if (text != tfv.text) {
            val sel = tfv.selection
            val s = sel.start.coerceIn(0, text.length)
            val e = sel.end.coerceIn(0, text.length)
            tfv = TextFieldValue(text = text, selection = TextRange(s, e))
        }
    }

    fun setAndPropagate(v: TextFieldValue) {
        tfv = v
        onTextChange(v.text)
    }

    fun insertAtCursor(snippet: String) {
        val t = tfv.text
        val s = tfv.selection.min.coerceIn(0, t.length)
        val e = tfv.selection.max.coerceIn(0, t.length)
        val newText = t.replaceRange(s, e, snippet)
        val newCursor = (s + snippet.length).coerceIn(0, newText.length)
        setAndPropagate(TextFieldValue(newText, selection = TextRange(newCursor)))
    }

    fun moveCursor(delta: Int) {
        val pos = (tfv.selection.end + delta).coerceIn(0, tfv.text.length)
        setAndPropagate(tfv.copy(selection = TextRange(pos)))
    }

    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var findStatus by remember { mutableStateOf<String?>(null) }

    fun findNext(forward: Boolean) {
        val q = findQuery
        if (q.isBlank()) {
            findStatus = "请输入查找内容"
            return
        }
        val hay = tfv.text
        val start = if (forward) tfv.selection.max else tfv.selection.min
        val idx = if (forward) {
            hay.indexOf(q, startIndex = start).takeIf { it >= 0 } ?: hay.indexOf(q, startIndex = 0)
        } else {
            hay.lastIndexOf(q, startIndex = (start - 1).coerceAtLeast(0)).takeIf { it >= 0 } ?: hay.lastIndexOf(q)
        }
        if (idx < 0) {
            findStatus = "未找到：$q"
            return
        }
        val range = TextRange(idx, idx + q.length)
        setAndPropagate(tfv.copy(selection = range))
        findStatus = null
    }

    fun replaceOne() {
        val q = findQuery
        if (q.isBlank()) return
        val sel = tfv.selection
        val selected = tfv.text.substring(sel.min.coerceIn(0, tfv.text.length), sel.max.coerceIn(0, tfv.text.length))
        if (selected == q) {
            insertAtCursor(replaceText)
        } else {
            findNext(true)
        }
    }

    fun replaceAll() {
        val q = findQuery
        if (q.isBlank()) return
        val src = tfv.text
        val count = src.windowed(q.length, 1).count { it == q }
        if (count <= 0) {
            findStatus = "未找到：$q"
            return
        }
        val newText = src.replace(q, replaceText)
        setAndPropagate(TextFieldValue(newText, selection = TextRange(0)))
        findStatus = "已替换 $count 处"
    }

    val containerModifier =
        if (fullscreen) {
            Modifier
                .fillMaxSize()
                .imePadding()
        } else {
            Modifier.fillMaxWidth()
        }

    // 关键：某些设备/ROM 下 DialogProperties 的宽度在运行期切换不会生效，
    // 用 key(fullscreen) 强制重建 Dialog 窗口，避免全屏时内容偏移导致右上角按钮被裁掉。
    key(fullscreen) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = !fullscreen,
                usePlatformDefaultWidth = !fullscreen,
                decorFitsSystemWindows = !fullscreen,
            ),
        ) {
            Surface(
                shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
                tonalElevation = if (fullscreen) 0.dp else 2.dp,
                shadowElevation = if (fullscreen) 0.dp else 1.dp,
                modifier = containerModifier,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Scaffold(
                    contentWindowInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
                    topBar = {
                        TopAppBar(
                            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(imageVector = Icons.Outlined.Close, contentDescription = "关闭")
                                }
                            },
                            actions = {
                                IconButton(onClick = { showFind = !showFind }) {
                                    Icon(imageVector = Icons.Outlined.Search, contentDescription = "查找/替换")
                                }
                                IconButton(onClick = onToggleFullscreen) {
                                    Icon(
                                        imageVector = if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                        contentDescription = if (fullscreen) "退出全屏" else "全屏",
                                    )
                                }
                                IconButton(onClick = onSave, enabled = saveEnabled) {
                                    Icon(imageVector = Icons.Outlined.Save, contentDescription = "保存")
                                }
                            },
                        )
                    },
                ) { inner ->
                    Column(
                        modifier = Modifier
                            .padding(inner)
                            .padding(
                                horizontal = if (fullscreen) 0.dp else Dimens.ScreenPaddingH,
                                vertical = Dimens.SpacingSm,
                            )
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSm),
                    ) {
                    if (loading) {
                        Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (showFind) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = findQuery,
                                    onValueChange = { findQuery = it; findStatus = null },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("查找") },
                                )
                                IconButton(onClick = { findNext(false) }) {
                                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上一个")
                                }
                                IconButton(onClick = { findNext(true) }) {
                                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下一个")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = replaceText,
                                    onValueChange = { replaceText = it; findStatus = null },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("替换为") },
                                )
                                TextButton(onClick = { replaceOne() }, enabled = findQuery.isNotBlank()) { Text("替换") }
                                TextButton(onClick = { replaceAll() }, enabled = findQuery.isNotBlank()) { Text("全部") }
                            }
                            if (findStatus != null) {
                                Text(findStatus ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = tfv,
                        onValueChange = { setAndPropagate(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = fullscreen),
                        minLines = if (fullscreen) 18 else 10,
                        label = { Text("内容") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(),
                    )

                    if (fullscreen) {
                        HavenLikeEditorQuickBar(
                            onInsert = { insertAtCursor(it) },
                            onLeft = { moveCursor(-1) },
                            onRight = { moveCursor(+1) },
                            onFind = { showFind = true },
                            onReplace = { showFind = true },
                            onSave = onSave,
                            saveEnabled = saveEnabled,
                        )
                    }
                    if (!fullscreen) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingSm, Alignment.End),
                        ) {
                            TextButton(onClick = onDismiss) { Text("取消") }
                            TextButton(onClick = onSave, enabled = saveEnabled) { Text("保存") }
                        }
                    }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HavenLikeEditorQuickBar(
    onInsert: (String) -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onFind: () -> Unit,
    onReplace: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean,
) {
    // Haven 风格：IME 出现时更“贴手”，这里全屏固定显示（你也可以改成只在 imeVisible 时显示）
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorQuickKey(label = "Tab", send = "\t", onInsert = onInsert)
            EditorQuickKey(label = "{", onInsert = onInsert)
            EditorQuickKey(label = "}", onInsert = onInsert)
            EditorQuickKey(label = "(", onInsert = onInsert)
            EditorQuickKey(label = ")", onInsert = onInsert)
            EditorQuickKey(label = "[", onInsert = onInsert)
            EditorQuickKey(label = "]", onInsert = onInsert)
            EditorQuickKey(label = "\"", onInsert = onInsert)
            EditorQuickKey(label = "'", onInsert = onInsert)
            EditorQuickKey(label = "/", onInsert = onInsert)
            EditorQuickActionText(label = "查找", onClick = onFind)
            EditorQuickActionText(label = "替换", onClick = onReplace)
            EditorQuickIcon(
                icon = Icons.Outlined.Save,
                contentDescription = "保存",
                enabled = saveEnabled,
                onClick = onSave,
            )
            EditorQuickIcon(icon = Icons.Outlined.KeyboardArrowLeft, contentDescription = "左", onClick = onLeft)
            EditorQuickIcon(icon = Icons.Outlined.KeyboardArrowRight, contentDescription = "右", onClick = onRight)
        }
    }
}

@Composable
private fun EditorQuickIcon(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(30.dp)
            .defaultMinSize(minWidth = 30.dp, minHeight = 30.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun EditorQuickKey(
    label: String,
    send: String = label,
    onInsert: (String) -> Unit,
) {
    FilledTonalButton(
        onClick = { onInsert(send) },
        modifier = Modifier
            .height(30.dp)
            // Material 默认 minWidth=58.dp，会导致键帽很宽；这里显式取消。
            .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 11.sp))
    }
}

@Composable
private fun EditorQuickActionText(
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .height(30.dp)
            .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 11.sp))
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

/** 可读文件大小（二进制单位，与常见系统工具一致） */
private fun formatHumanFileSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes.toDouble() / 1024.0
    if (kb < 1024) return trimFrac(String.format(Locale.US, "%.2f KiB", kb))
    val mb = kb / 1024.0
    if (mb < 1024) return trimFrac(String.format(Locale.US, "%.2f MiB", mb))
    val gb = mb / 1024.0
    if (gb < 1024) return trimFrac(String.format(Locale.US, "%.2f GiB", gb))
    val tb = gb / 1024.0
    return trimFrac(String.format(Locale.US, "%.2f TiB", tb))
}

private fun trimFrac(s: String): String {
    val i = s.lastIndexOf(' ')
    if (i <= 0) return s
    val n = s.substring(0, i)
    val unit = s.substring(i + 1)
    if ('.' !in n) return s
    return n.trimEnd('0').trimEnd('.') + " " + unit
}

private fun Modifier.simpleVerticalScrollbar(
    state: androidx.compose.foundation.lazy.LazyListState,
    thumbColor: androidx.compose.ui.graphics.Color,
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
            color = thumbColor,
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

