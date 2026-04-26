package com.dxkj.myshell.ui.screens

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.WindowManager
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Add as AddIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.terminal.TerminalSessionPool
import jackpal.androidterm.emulatorview.ColorScheme
import jackpal.androidterm.emulatorview.EmulatorView
import kotlinx.coroutines.flow.map

@Composable
fun TerminalHubScreen(
    initialHostId: Long?,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboard = LocalClipboardManager.current

    TerminalSessionPool.init(context.applicationContext as Application)

    val sessions by TerminalSessionPool.sessions.collectAsState()
    val activeId by TerminalSessionPool.activeSessionId.collectAsState()
    val active = sessions.firstOrNull { it.sessionId == activeId } ?: sessions.lastOrNull()

    val prefs = remember(context) { context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE) }

    var viewRef by remember { mutableStateOf<EmulatorView?>(null) }
    var fontSize by remember { mutableIntStateOf(16) }
    var schemeId by remember { mutableIntStateOf(0) }
    var bgAlphaStep by remember { mutableIntStateOf(0) }
    var cursorHighContrast by remember { mutableStateOf(true) }
    var keyBarVisible by remember { mutableStateOf(true) }
    var showHostPicker by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // 初始 hostId：自动开会话
    LaunchedEffect(initialHostId) {
        val hid = initialHostId?.takeIf { it > 0 } ?: return@LaunchedEffect
        TerminalSessionPool.ensureSession(hid)
    }
    LaunchedEffect(Unit) {
        if (initialHostId == null && sessions.isEmpty()) {
            val ids = TerminalSessionPool.loadOpenHostIds()
            ids.forEach { TerminalSessionPool.ensureSession(it) }
        }
    }

    // 切换会话时加载对应 host 的偏好
    LaunchedEffect(active?.hostId) {
        val hid = active?.hostId ?: return@LaunchedEffect
        fontSize = prefs.getInt("fontSize_$hid", 16)
        schemeId = prefs.getInt("scheme_$hid", 0)
        bgAlphaStep = prefs.getInt("bgAlpha_$hid", 0)
        cursorHighContrast = prefs.getBoolean("cursorHi_$hid", true)
        keyBarVisible = prefs.getBoolean("keyBar_$hid", true)
    }

    LaunchedEffect(fontSize, active?.hostId) {
        val hid = active?.hostId ?: return@LaunchedEffect
        prefs.edit().putInt("fontSize_$hid", fontSize).apply()
    }
    LaunchedEffect(schemeId, bgAlphaStep, cursorHighContrast, active?.hostId) {
        val hid = active?.hostId ?: return@LaunchedEffect
        prefs.edit().putInt("scheme_$hid", schemeId).apply()
        prefs.edit().putInt("bgAlpha_$hid", bgAlphaStep).apply()
        prefs.edit().putBoolean("cursorHi_$hid", cursorHighContrast).apply()
        val scheme = colorSchemeFor(schemeId, bgAlphaStep, cursorHighContrast)
        active?.term?.setColorScheme(scheme)
        viewRef?.setColorScheme(scheme)
    }
    LaunchedEffect(keyBarVisible, active?.hostId) {
        val hid = active?.hostId ?: return@LaunchedEffect
        prefs.edit().putBoolean("keyBar_$hid", keyBarVisible).apply()
    }

    BackHandler {
        onExit()
    }

    DisposableEffect(Unit) {
        if (activity != null) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (activity != null) {
                val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(activity.window, true)
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 终端主体
        if (active?.term != null) {
            AndroidView(
                factory = { ctx ->
                    val dm = ctx.resources.displayMetrics
                    EmulatorView(ctx, active.term, dm).apply {
                        setTextSize(fontSize)
                        setUseCookedIME(false)
                        setTermType("xterm-256color")
                        setColorScheme(colorSchemeFor(schemeId, bgAlphaStep, cursorHighContrast))
                        isVerticalScrollBarEnabled = true
                        isHorizontalScrollBarEnabled = false
                        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                        onResume()
                        viewRef = this
                    }
                },
                update = { v ->
                    if (v.getTermSession() !== active.term) v.attachSession(active.term)
                    v.setTextSize(fontSize)
                    v.setColorScheme(colorSchemeFor(schemeId, bgAlphaStep, cursorHighContrast))
                    v.requestFocus()
                    viewRef = v
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = active?.status ?: if (sessions.isEmpty()) "暂无会话，点击 + 新建" else "连接中…",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 顶部：标签栏 + 工具条
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .background(Color(0xAA111111), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onExit) {
                    Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "back")
                }

                sessions.forEach { s ->
                    Row(
                        modifier = Modifier
                            .background(
                                if (s.sessionId == active?.sessionId) Color(0x6633FF33) else Color(0x33000000),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { TerminalSessionPool.setActive(s.sessionId) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(text = s.title, color = Color.White, style = MaterialTheme.typography.labelLarge)
                        FilledTonalIconButton(onClick = { TerminalSessionPool.close(s.sessionId) }) {
                            Icon(imageVector = Icons.Outlined.Close, contentDescription = "close")
                        }
                    }
                }

                FilledTonalIconButton(onClick = { showHostPicker = true }) {
                    Icon(imageVector = Icons.Outlined.Add, contentDescription = "new")
                }
            }

            if (active != null) {
                Row(
                    modifier = Modifier
                        .background(Color(0xAA111111), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = { schemeId = (schemeId + 1) % 7 },
                        enabled = active.term != null,
                    ) { Icon(imageVector = Icons.Outlined.Palette, contentDescription = "theme") }

                    FilledTonalIconButton(
                        onClick = { bgAlphaStep = (bgAlphaStep + 1) % 3 },
                        enabled = active.term != null,
                    ) { Text(text = if (bgAlphaStep == 0) "不透明" else if (bgAlphaStep == 1) "半透" else "更透", color = Color.White) }

                    FilledTonalIconButton(
                        onClick = { cursorHighContrast = !cursorHighContrast },
                        enabled = active.term != null,
                    ) { Icon(imageVector = Icons.Outlined.Contrast, contentDescription = "cursor") }

                    FilledTonalIconButton(onClick = { TerminalSessionPool.toggleAutoReconnect(active.sessionId) }) {
                        Text(text = if (active.autoReconnect) "自重连" else "不重连", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }

                    FilledTonalIconButton(
                        onClick = { showMoreMenu = true },
                        enabled = true,
                    ) {
                        Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = "more")
                    }

                    FilledTonalIconButton(onClick = { keyBarVisible = !keyBarVisible }, enabled = active.term != null) {
                        Icon(imageVector = if (keyBarVisible) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.Keyboard, contentDescription = "keybar")
                    }

                    FilledTonalIconButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(10) }, enabled = active.term != null) {
                        Icon(imageVector = Icons.Outlined.Remove, contentDescription = "font-")
                    }
                    FilledTonalIconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(26) }, enabled = active.term != null) {
                        Icon(imageVector = Icons.Outlined.Add, contentDescription = "font+")
                    }

                    FilledTonalIconButton(onClick = { TerminalSessionPool.reconnect(active.sessionId) }, enabled = !active.connected && !active.connecting) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "reconnect")
                    }
                    FilledTonalIconButton(onClick = { TerminalSessionPool.disconnect(active.sessionId) }, enabled = active.connected) {
                        Icon(imageVector = Icons.Outlined.PowerSettingsNew, contentDescription = "disconnect")
                    }

                    FilledTonalIconButton(
                        onClick = {
                            val t = clipboard.getText()?.text.orEmpty()
                            if (t.isNotBlank()) active.term?.write(t)
                        },
                        enabled = active.term != null,
                    ) { Icon(imageVector = Icons.Outlined.ContentPaste, contentDescription = "paste") }

                    FilledTonalIconButton(
                        onClick = {
                            val t = clipboard.getText()?.text.orEmpty()
                            if (t.isNotBlank()) TerminalSessionPool.broadcastWrite(t)
                        },
                        enabled = sessions.any { it.term != null },
                    ) { Text(text = "广播粘贴", color = Color.White, style = MaterialTheme.typography.labelSmall) }

                    FilledTonalIconButton(
                        onClick = { TerminalSessionPool.exportLog(active.sessionId) },
                        enabled = active.term != null,
                    ) { Icon(imageVector = Icons.Outlined.Save, contentDescription = "save log") }
                }
            }
        }

        if (active != null && !active.status.isNullOrBlank() && (active.connecting || !active.connected)) {
            Text(
                text = active.status ?: "",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(bottom = if (keyBarVisible) 56.dp else 12.dp)
                    .background(Color(0xAA111111), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        if (active?.term != null && keyBarVisible) {
            HubKeyBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                onKey = { seq -> active.term.write(seq) },
                onPaste = {
                    val t = clipboard.getText()?.text.orEmpty()
                    if (t.isNotBlank()) active.term.write(t)
                },
            )
        }

        if (showHostPicker) {
            HostPickerDialog(
                onDismiss = { showHostPicker = false },
                onPick = { hid ->
                    showHostPicker = false
                    TerminalSessionPool.ensureSession(hid)
                },
            )
        }

        if (showMoreMenu && active != null) {
            AlertDialog(
                onDismissRequest = { showMoreMenu = false },
                confirmButton = {
                    TextButton(onClick = { showMoreMenu = false }) { Text("关闭") }
                },
                title = { Text("会话管理") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            showMoreMenu = false
                            TerminalSessionPool.closeOthers(active.sessionId)
                        }) { Text("关闭其它会话") }
                        TextButton(onClick = {
                            showMoreMenu = false
                            TerminalSessionPool.closeAll()
                        }) { Text("关闭全部会话") }
                        TextButton(onClick = {
                            showMoreMenu = false
                            TerminalSessionPool.broadcastWrite("\u0003")
                        }) { Text("广播 Ctrl+C") }
                    }
                },
            )
        }
    }
}

@Composable
private fun HostPickerDialog(
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val repo = remember { HostRepository(DbProvider.get(app).hostDao()) }
    val hosts by repo.observeAll().map { it }.collectAsState(initial = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("新建会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hosts.isEmpty()) {
                    Text("还没有主机，请先去「主机」页添加")
                } else {
                    hosts.forEach { h ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { onPick(h.id) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(h.name)
                                Text("${h.username}@${h.host}:${h.port}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(imageVector = Icons.Outlined.Add, contentDescription = "pick")
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun HubKeyBar(
    modifier: Modifier = Modifier,
    onKey: (String) -> Unit,
    onPaste: () -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = { onKey("\u001B") }) { Text("Esc") }
        FilledTonalIconButton(onClick = { onKey("\t") }) { Text("Tab") }
        FilledTonalIconButton(onClick = { onKey("\u007F") }) { Text("⌫") }
        FilledTonalIconButton(onClick = { onKey("\r") }) { Text("Enter") }
        FilledTonalIconButton(onClick = { onKey("\u0003") }) { Text("Ctrl+C") }
        FilledTonalIconButton(onClick = { onKey("\u001B[A") }) { Text("↑") }
        FilledTonalIconButton(onClick = { onKey("\u001B[B") }) { Text("↓") }
        FilledTonalIconButton(onClick = { onKey("\u001B[D") }) { Text("←") }
        FilledTonalIconButton(onClick = { onKey("\u001B[C") }) { Text("→") }
        FilledTonalIconButton(onClick = onPaste) { Text("粘贴") }
    }
}

private fun colorSchemeFor(id: Int, bgAlphaStep: Int, cursorHighContrast: Boolean): ColorScheme {
    // 复用 TerminalFullScreen 里的定义逻辑：这里保持一致即可
    fun alpha(a: Int, rgb: Int): Int = (a shl 24) or (rgb and 0x00FFFFFF)
    val bgA = when (bgAlphaStep.coerceIn(0, 2)) {
        0 -> 0xFF
        1 -> 0xE6
        else -> 0xCC
    }
    val base = when (id % 7) {
        0 -> intArrayOf(0xFF33FF33.toInt(), alpha(bgA, 0x000000), 0xFF000000.toInt(), 0xFF33FF33.toInt())
        1 -> intArrayOf(0xFFEAEAEA.toInt(), alpha(bgA, 0x000000), 0xFF000000.toInt(), 0xFFEAEAEA.toInt())
        2 -> intArrayOf(0xFF93A1A1.toInt(), alpha(bgA, 0x002B36), 0xFF002B36.toInt(), 0xFF93A1A1.toInt())
        3 -> intArrayOf(0xFF586E75.toInt(), alpha(bgA, 0xFDF6E3), 0xFFFDF6E3.toInt(), 0xFF586E75.toInt())
        4 -> intArrayOf(0xFFF8F8F2.toInt(), alpha(bgA, 0x282A36), 0xFF282A36.toInt(), 0xFFF8F8F2.toInt())
        5 -> intArrayOf(0xFFF8F8F2.toInt(), alpha(bgA, 0x272822), 0xFF272822.toInt(), 0xFFF8F8F2.toInt())
        else -> intArrayOf(0xFFABB2BF.toInt(), alpha(bgA, 0x282C34), 0xFF282C34.toInt(), 0xFFABB2BF.toInt())
    }
    val c = if (cursorHighContrast) intArrayOf(base[2], base[3]) else intArrayOf(base[1], base[0])
    return ColorScheme(base[0], base[1], c[0], c[1])
}

