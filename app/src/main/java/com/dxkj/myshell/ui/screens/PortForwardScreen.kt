package com.dxkj.myshell.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.dxkj.myshell.ui.theme.Dimens
import com.dxkj.myshell.ssh.DiscoveredListen
import com.dxkj.myshell.ssh.PortForwardItem
import com.dxkj.myshell.terminal.HostPortForwardUi
import com.dxkj.myshell.terminal.MergedForwardUi
import com.dxkj.myshell.terminal.TerminalSessionPool
import kotlinx.coroutines.launch

/** 手动添加转发时，隧道目标固定为远端本机（与常见 dev server 一致）。 */
private const val ManualForwardRemoteHost = "127.0.0.1"

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PortForwardScreen(
    contentPadding: PaddingValues,
    linkedSessionId: Long?,
) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    val sessions by TerminalSessionPool.sessions.collectAsState()
    val session = remember(sessions, linkedSessionId) {
        linkedSessionId?.let { id -> sessions.firstOrNull { it.sessionId == id } }
    }
    val hostId = session?.hostId?.takeIf { it > 0L } ?: 0L
    val hostUiMap by TerminalSessionPool.hostPortForwardUi.collectAsState()
    val hostUi = if (hostId > 0L) (hostUiMap[hostId] ?: HostPortForwardUi.Empty) else HostPortForwardUi.Empty
    val forwards = hostUi.forwards
    val discovered = hostUi.discovered
    val ignoredRemote = hostUi.mergedIgnoredKeys

    val operatorSession = remember(sessions, linkedSessionId) {
        val sid = linkedSessionId ?: return@remember null
        val cur = sessions.firstOrNull { it.sessionId == sid } ?: return@remember null
        if (cur.connected) cur else sessions.firstOrNull { it.hostId == cur.hostId && it.connected }
    }
    val actionSession = operatorSession ?: session?.takeIf { it.connected }

    LaunchedEffect(session?.hostId, linkedSessionId) {
        val hid = session?.hostId ?: return@LaunchedEffect
        if (hid > 0L) TerminalSessionPool.refreshHostPortForwardUi(hid)
    }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var autoTerminal by remember { mutableStateOf(TerminalSessionPool.portAutoFromTerminal()) }
    LaunchedEffect(session?.sessionId) {
        autoTerminal = TerminalSessionPool.portAutoFromTerminal()
    }

    var scanHint by remember { mutableStateOf<String?>(null) }
    var scanBusy by remember { mutableStateOf(false) }

    var showAdd by remember { mutableStateOf(false) }
    var localPortText by remember { mutableStateOf("") }
    var remotePortText by remember { mutableStateOf("") }
    var dialogError by remember { mutableStateOf<String?>(null) }

    var editTarget by remember { mutableStateOf<MergedForwardUi?>(null) }
    var editLocalPort by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }

    val activeRemoteKeys = remember(forwards) {
        forwards.map { "${it.item.remoteHost.trim().lowercase()}:${it.item.remotePort}" }.toSet()
    }
    val pendingDiscovered = remember(discovered, activeRemoteKeys, ignoredRemote) {
        discovered.filter { md ->
            val d = md.listen
            if (d.remotePort == 22) return@filter false
            val k = "${d.remoteHost.trim().lowercase()}:${d.remotePort}"
            k !in activeRemoteKeys && k !in ignoredRemote
        }
            .distinctBy { md -> "${md.listen.remoteHost}:${md.listen.remotePort}" }
            .sortedWith(compareBy({ it.listen.remotePort }, { it.listen.remoteHost.lowercase() }))
    }

    val anySameHostConnected = hostId > 0L && sessions.any { it.hostId == hostId && it.connected }

    val statusText = when {
        linkedSessionId == null -> "请先打开一个终端会话"
        session == null -> "当前会话已关闭"
        !anySameHostConnected && session.connecting -> "正在连接终端…"
        !anySameHostConnected && !session.connected -> "终端未连接，端口转发不可用"
        else -> null
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        floatingActionButton = {
            if (actionSession != null) {
                FloatingActionButton(
                    onClick = {
                        localPortText = ""
                        remotePortText = ""
                        dialogError = null
                        showAdd = true
                    },
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "手动添加")
                }
            }
        },
    ) { inner ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = Dimens.ScreenPaddingH),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 6.dp else Dimens.SpacingSm),
        ) {
            if (statusText != null) {
                item {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = if (isLandscape) Dimens.SpacingSm else Dimens.SpacingMd),
                    )
                }
            } else {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("终端里识别到端口后自动同号转发", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = autoTerminal,
                            onCheckedChange = {
                                autoTerminal = it
                                TerminalSessionPool.setPortAutoFromTerminal(it)
                            },
                        )
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = {
                            val act = actionSession ?: return@FilledTonalButton
                            scanBusy = true
                            scanHint = null
                            scope.launch {
                                val r = act.ssh.scanRemoteListenersAndMerge(autoSamePortForward = false)
                                scanBusy = false
                                scanHint = if (r.isSuccess) {
                                    "扫描完成，发现 ${r.getOrNull() ?: 0} 条监听"
                                } else {
                                    "扫描失败：${r.exceptionOrNull()?.message}"
                                }
                            }
                        },
                        enabled = actionSession != null && !scanBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("扫描远端监听")
                    }
                }
                if (scanHint != null) {
                    item {
                        Text(
                            scanHint!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (scanHint!!.startsWith("扫描失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (ignoredRemote.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "已忽略 ${ignoredRemote.size} 个地址（停止转发后不再出现在已发现；终端自动转发也会跳过）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            TextButton(
                                onClick = {
                                    if (hostId > 0L) TerminalSessionPool.clearAllDismissedRemotePortsForHost(hostId)
                                },
                            ) {
                                Text("清除忽略")
                            }
                        }
                    }
                }
                item {
                    Text("转发中", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (forwards.isEmpty()) {
                    item {
                        Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(forwards, key = { "${it.owningSessionId}:${it.item.id}" }) { mf ->
                        val row = mf.item
                        ActiveForwardRow(
                            item = row,
                            onCopyLocal = {
                                clipboard.setText(AnnotatedString("${row.localHost}:${row.localPort}"))
                            },
                            onEditLocal = {
                                editTarget = mf
                                editLocalPort = row.localPort.toString()
                                editError = null
                            },
                            onRemove = {
                                val owner = sessions.firstOrNull { it.sessionId == mf.owningSessionId }
                                scope.launch { owner?.ssh?.stopLocalPortForward(row.id) }
                            },
                        )
                    }
                }
                item {
                    Text("已发现", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (pendingDiscovered.isEmpty()) {
                    item {
                        Text(
                            "运行服务后点「扫描」，或在终端里启动 dev server（会打印 localhost:端口）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(pendingDiscovered, key = { "${it.listen.remoteHost}:${it.listen.remotePort}:${it.listen.source}" }) { md ->
                        val d = md.listen
                        DiscoveredRow(
                            d = d,
                            onSamePortForward = {
                                val act = actionSession ?: return@DiscoveredRow
                                val hid = act.hostId
                                scope.launch {
                                    if (TerminalSessionPool.isRemotePortForwardedOnHost(hid, d.remoteHost, d.remotePort)) {
                                        scanHint = "该远端已在转发中"
                                        return@launch
                                    }
                                    TerminalSessionPool.unignoreRemotePortOnAllSameHostSessions(hid, d.remoteHost, d.remotePort)
                                    val r = act.ssh.startSamePortForwardIfAbsent(
                                        d.remoteHost,
                                        d.remotePort,
                                        explicitUserAction = true,
                                    )
                                    if (r.isSuccess) {
                                        TerminalSessionPool.removeDiscoveredFromAllSameHostSessions(hid, d.remoteHost, d.remotePort)
                                        scanHint = "已转发 ${d.remoteHost}:${d.remotePort}"
                                    } else {
                                        scanHint = r.exceptionOrNull()?.message ?: "失败"
                                    }
                                }
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    if (showAdd && actionSession != null) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("手动添加转发") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSm)) {
                    OutlinedTextField(
                        value = remotePortText,
                        onValueChange = { remotePortText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("远程端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = localPortText,
                        onValueChange = { localPortText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("本地端口（留空则与远程端口相同）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (dialogError != null) {
                        Text(dialogError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val rp = remotePortText.trim().toIntOrNull()
                        if (rp == null || rp !in 1..65535) {
                            dialogError = "请输入有效远程端口（1–65535）"
                            return@TextButton
                        }
                        if (rp == 22) {
                            dialogError = "22 为 SSH 端口，无需转发"
                            return@TextButton
                        }
                        val localTrim = localPortText.trim()
                        val lp = if (localTrim.isEmpty()) {
                            rp
                        } else {
                            localTrim.toIntOrNull()
                        }
                        if (lp == null || lp !in 1..65535) {
                            dialogError = "本地端口须为 1–65535，或留空与远程相同"
                            return@TextButton
                        }
                        val rh = ManualForwardRemoteHost
                        val act = actionSession!!
                        val hid = act.hostId
                        scope.launch {
                            if (TerminalSessionPool.isRemotePortForwardedOnHost(hid, rh, rp)) {
                                dialogError = "该远端已在转发中"
                                return@launch
                            }
                            val r = act.ssh.startLocalPortForward(lp, rh, rp)
                            if (r.isSuccess) {
                                TerminalSessionPool.removeDiscoveredFromAllSameHostSessions(hid, rh, rp)
                                showAdd = false
                            } else {
                                dialogError = r.exceptionOrNull()?.message ?: "启动失败"
                            }
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消") }
            },
        )
    }

    if (editTarget != null) {
        val t = editTarget!!
        val owner = sessions.firstOrNull { it.sessionId == t.owningSessionId }
        val canEdit = owner != null && owner.connected
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("修改本机端口") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSm)) {
                    if (!canEdit) {
                        Text(
                            "该转发所属会话已断开，请先连接对应标签后再改端口。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "远端 ${t.item.remoteHost}:${t.item.remotePort} 不变，仅更换手机上的监听端口。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = editLocalPort,
                        onValueChange = { editLocalPort = it.filter { ch -> ch.isDigit() } },
                        label = { Text("新本地端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (editError != null) {
                        Text(editError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!canEdit) return@TextButton
                        val np = editLocalPort.trim().toIntOrNull()
                        if (np == null || np !in 1..65535) {
                            editError = "请输入 1–65535"
                            return@TextButton
                        }
                        scope.launch {
                            val r = owner!!.ssh.replaceLocalForward(t.item.id, np)
                            if (r.isSuccess) {
                                editTarget = null
                            } else {
                                editError = r.exceptionOrNull()?.message ?: "失败"
                            }
                        }
                    },
                    enabled = canEdit,
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ActiveForwardRow(
    item: PortForwardItem,
    onCopyLocal: () -> Unit,
    onEditLocal: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = "${item.localHost}:${item.localPort}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "→ ${item.remoteHost}:${item.remotePort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCopyLocal) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制本地地址")
            }
            IconButton(onClick = onEditLocal) {
                Icon(Icons.Outlined.Edit, contentDescription = "改本机端口")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = "停止")
            }
        }
    }
}

@Composable
private fun DiscoveredRow(
    d: DiscoveredListen,
    onSamePortForward: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${d.remoteHost}:${d.remotePort}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "来源：${d.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSamePortForward) {
                Text("同号转发")
            }
        }
    }
}
