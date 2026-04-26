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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.dxkj.myshell.ssh.DiscoveredListen
import com.dxkj.myshell.ssh.PortForwardItem
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
    val sessions by TerminalSessionPool.sessions.collectAsState()
    val session = remember(sessions, linkedSessionId) {
        linkedSessionId?.let { id -> sessions.firstOrNull { it.sessionId == id } }
    }
    val forwardsState: State<List<PortForwardItem>> = if (session?.ssh != null) {
        session!!.ssh.portForwards.collectAsState()
    } else {
        remember { mutableStateOf(emptyList()) }
    }
    val discoveredState: State<List<DiscoveredListen>> = if (session?.ssh != null) {
        session!!.ssh.discoveredList.collectAsState()
    } else {
        remember { mutableStateOf(emptyList()) }
    }
    val ignoredState: State<Set<String>> = if (session?.ssh != null) {
        session!!.ssh.ignoredRemotePorts.collectAsState()
    } else {
        remember { mutableStateOf(emptySet()) }
    }
    val forwards by forwardsState
    val discovered by discoveredState
    val ignoredRemote by ignoredState
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

    var editTarget by remember { mutableStateOf<PortForwardItem?>(null) }
    var editLocalPort by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }

    val activeRemoteKeys = remember(forwards) {
        forwards.map { "${it.remoteHost.trim().lowercase()}:${it.remotePort}" }.toSet()
    }
    val pendingDiscovered = remember(discovered, activeRemoteKeys, ignoredRemote) {
        discovered.filter { d ->
            if (d.remotePort == 22) return@filter false
            val k = "${d.remoteHost.trim().lowercase()}:${d.remotePort}"
            k !in activeRemoteKeys && k !in ignoredRemote
        }
            .distinctBy { d -> "${d.remoteHost}:${d.remotePort}" }
            .sortedWith(compareBy({ it.remotePort }, { it.remoteHost.lowercase() }))
    }

    val statusText = when {
        linkedSessionId == null -> "请先打开一个终端会话"
        session == null -> "当前会话已关闭"
        session.connecting -> "正在连接终端…"
        !session.connected -> "终端未连接，端口转发不可用"
        else -> null
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        floatingActionButton = {
            if (session != null && session.connected) {
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
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (statusText != null) {
                item {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
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
                            if (session == null || !session.connected) return@FilledTonalButton
                            scanBusy = true
                            scanHint = null
                            scope.launch {
                                val r = session.ssh.scanRemoteListenersAndMerge(autoSamePortForward = false)
                                scanBusy = false
                                scanHint = if (r.isSuccess) {
                                    "扫描完成，发现 ${r.getOrNull() ?: 0} 条监听"
                                } else {
                                    "扫描失败：${r.exceptionOrNull()?.message}"
                                }
                            }
                        },
                        enabled = session != null && session.connected && !scanBusy,
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
                            TextButton(onClick = { session?.ssh?.clearAllDismissedRemotePorts() }) {
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
                    items(forwards, key = { it.id }) { row ->
                        ActiveForwardRow(
                            item = row,
                            onCopyLocal = {
                                clipboard.setText(AnnotatedString("${row.localHost}:${row.localPort}"))
                            },
                            onEditLocal = {
                                editTarget = row
                                editLocalPort = row.localPort.toString()
                                editError = null
                            },
                            onRemove = {
                                scope.launch { session!!.ssh.stopLocalPortForward(row.id) }
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
                    items(pendingDiscovered, key = { "${it.remoteHost}:${it.remotePort}:${it.source}" }) { d ->
                        DiscoveredRow(
                            d = d,
                            onSamePortForward = {
                                scope.launch {
                                    val r = session!!.ssh.startSamePortForwardIfAbsent(
                                        d.remoteHost,
                                        d.remotePort,
                                        explicitUserAction = true,
                                    )
                                    if (r.isSuccess) {
                                        session.ssh.removeDiscoveredFromListOnly(d.remoteHost, d.remotePort)
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

    if (showAdd && session != null && session.connected) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("手动添加转发") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        scope.launch {
                            val r = session.ssh.startLocalPortForward(lp, rh, rp)
                            if (r.isSuccess) {
                                session.ssh.removeDiscoveredFromListOnly(rh, rp)
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

    if (editTarget != null && session != null && session.connected) {
        val t = editTarget!!
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("修改本机端口") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "远端 ${t.remoteHost}:${t.remotePort} 不变，仅更换手机上的监听端口。",
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
                        val np = editLocalPort.trim().toIntOrNull()
                        if (np == null || np !in 1..65535) {
                            editError = "请输入 1–65535"
                            return@TextButton
                        }
                        scope.launch {
                            val r = session.ssh.replaceLocalForward(t.id, np)
                            if (r.isSuccess) {
                                editTarget = null
                            } else {
                                editError = r.exceptionOrNull()?.message ?: "失败"
                            }
                        }
                    },
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
