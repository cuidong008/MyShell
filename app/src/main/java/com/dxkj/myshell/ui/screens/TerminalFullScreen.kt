package com.dxkj.myshell.ui.screens

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.data.repo.KeyRepository
import com.dxkj.myshell.ssh.SshSessionManager
import jackpal.androidterm.emulatorview.ColorScheme
import jackpal.androidterm.emulatorview.EmulatorView
import jackpal.androidterm.emulatorview.TermSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TerminalFullScreen(
    hostId: Long,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboard = LocalClipboardManager.current
    val prefs = remember(context) {
        context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
    }

    val vm: TerminalFullViewModel =
        viewModel(factory = TerminalFullViewModel.factory(context.applicationContext as Application, hostId))
    val ui by vm.ui.collectAsState()

    var viewRef by remember { mutableStateOf<EmulatorView?>(null) }
    var fontSize by remember { mutableIntStateOf(prefs.getInt("fontSize_$hostId", 16)) }
    var keyBarVisible by remember { mutableStateOf(prefs.getBoolean("keyBar_$hostId", true)) }
    var toolbarVisible by remember { mutableStateOf(true) }
    var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var schemeId by remember { mutableIntStateOf(prefs.getInt("scheme_$hostId", 0)) }
    var bgAlphaStep by remember { mutableIntStateOf(prefs.getInt("bgAlpha_$hostId", 0)) } // 0..2
    var cursorHighContrast by remember { mutableStateOf(prefs.getBoolean("cursorHi_$hostId", true)) }

    fun pokeInteraction() {
        lastInteractionMs = System.currentTimeMillis()
        if (!toolbarVisible) toolbarVisible = true
    }

    BackHandler {
        vm.disconnect()
        onExit()
    }

    LaunchedEffect(fontSize) {
        prefs.edit().putInt("fontSize_$hostId", fontSize).apply()
    }
    LaunchedEffect(keyBarVisible) {
        prefs.edit().putBoolean("keyBar_$hostId", keyBarVisible).apply()
    }
    LaunchedEffect(schemeId) {
        prefs.edit().putInt("scheme_$hostId", schemeId).apply()
        val scheme = colorSchemeFor(schemeId, bgAlphaStep, cursorHighContrast)
        ui.session?.setColorScheme(scheme)
        viewRef?.setColorScheme(scheme)
    }
    LaunchedEffect(bgAlphaStep) {
        prefs.edit().putInt("bgAlpha_$hostId", bgAlphaStep).apply()
        val scheme = colorSchemeFor(schemeId, bgAlphaStep, cursorHighContrast)
        ui.session?.setColorScheme(scheme)
        viewRef?.setColorScheme(scheme)
    }
    LaunchedEffect(cursorHighContrast) {
        prefs.edit().putBoolean("cursorHi_$hostId", cursorHighContrast).apply()
        val scheme = colorSchemeFor(schemeId, bgAlphaStep, cursorHighContrast)
        ui.session?.setColorScheme(scheme)
        viewRef?.setColorScheme(scheme)
    }

    // ShellBean 风格：操作一阵后自动淡出工具条；轻点屏幕呼出
    LaunchedEffect(ui.connected, toolbarVisible, lastInteractionMs) {
        if (!ui.connected) return@LaunchedEffect
        if (!toolbarVisible) return@LaunchedEffect
        val captured = lastInteractionMs
        delay(2500)
        if (ui.connected && toolbarVisible && lastInteractionMs == captured) {
            toolbarVisible = false
        }
    }

    DisposableEffect(ui.connected) {
        if (activity != null) {
            if (ui.connected) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose { }
    }

    DisposableEffect(Unit) {
        if (activity != null) {
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
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(enabled = ui.connected) {
                        pokeInteraction()
                        viewRef?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    },
            ) {
                if (ui.session != null) {
                    AndroidView(
                        factory = { ctx ->
                            val dm = ctx.resources.displayMetrics
                            EmulatorView(ctx, ui.session, dm).apply {
                                setTextSize(fontSize)
                                setUseCookedIME(false)
                                setTermType("xterm-256color")
                                setColorScheme(colorSchemeFor(schemeId, bgAlphaStep, cursorHighContrast))
                                isFocusable = true
                                isFocusableInTouchMode = true
                                requestFocus()
                                onResume()
                                setOnTouchListener { v: View, ev: MotionEvent ->
                                    if (ev.action == MotionEvent.ACTION_DOWN) pokeInteraction()
                                    v.onTouchEvent(ev)
                                }
                                viewRef = this
                            }
                        },
                        update = { v ->
                            if (v.getTermSession() !== ui.session) v.attachSession(ui.session)
                            v.setTextSize(fontSize)
                            v.setColorScheme(colorSchemeFor(schemeId, bgAlphaStep, cursorHighContrast))
                            v.requestFocus()
                            viewRef = v
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = ui.status ?: "连接中…",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            if (ui.session != null && keyBarVisible) {
                TerminalKeyBar(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000))
                        .systemBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    onKey = { seq ->
                        pokeInteraction()
                        ui.session?.write(seq)
                    },
                    onPaste = {
                        pokeInteraction()
                        val text = clipboard.getText()?.text.orEmpty()
                        if (text.isNotBlank()) ui.session?.write(text)
                    },
                )
            }
        }

        // 悬浮工具条（接近 ShellBean：不占用顶部/底部栏，随时可操作）
        if (toolbarVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding()
                    .padding(top = 8.dp)
                    .background(
                        color = Color(0xAA111111),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = {
                    pokeInteraction()
                    vm.disconnect()
                    onExit()
                }) {
                    Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "back")
                }

                Text(
                    text = ui.title ?: "终端",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        schemeId = (schemeId + 1) % COLOR_SCHEMES_COUNT
                    },
                    enabled = ui.session != null,
                ) {
                    Icon(imageVector = Icons.Outlined.Palette, contentDescription = "theme")
                }

                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        bgAlphaStep = (bgAlphaStep + 1) % 3
                    },
                    enabled = ui.session != null,
                ) {
                    Text(
                        text = when (bgAlphaStep) {
                            0 -> "不透明"
                            1 -> "半透"
                            else -> "更透"
                        },
                        color = Color.White,
                    )
                }

                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        cursorHighContrast = !cursorHighContrast
                    },
                    enabled = ui.session != null,
                ) {
                    Icon(imageVector = Icons.Outlined.Contrast, contentDescription = "cursor")
                }

                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        keyBarVisible = !keyBarVisible
                    },
                    enabled = ui.session != null,
                ) {
                    Icon(imageVector = if (keyBarVisible) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.Keyboard, contentDescription = "keybar")
                }

                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        viewRef?.toggleSelectingText()
                    },
                    enabled = viewRef != null,
                ) {
                    Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = "select")
                }

                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        val selected = viewRef?.getSelectedText()?.takeIf { it.isNotBlank() }
                        if (!selected.isNullOrBlank()) clipboard.setText(AnnotatedString(selected))
                    },
                    enabled = viewRef != null,
                ) {
                    Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = "copy")
                }

                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        val text = clipboard.getText()?.text.orEmpty()
                        if (text.isNotBlank()) ui.session?.write(text)
                    },
                    enabled = ui.session != null,
                ) {
                    Icon(imageVector = Icons.Outlined.ContentPaste, contentDescription = "paste")
                }

                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        fontSize = (fontSize - 1).coerceAtLeast(10)
                    },
                    enabled = viewRef != null,
                ) {
                    Icon(imageVector = Icons.Outlined.Remove, contentDescription = "font-")
                }
                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        fontSize = (fontSize + 1).coerceAtMost(26)
                    },
                    enabled = viewRef != null,
                ) {
                    Icon(imageVector = Icons.Outlined.Add, contentDescription = "font+")
                }

                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        vm.reconnect()
                    },
                    enabled = !ui.connected && !ui.connecting,
                ) {
                    Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "reconnect")
                }

                FilledTonalIconButton(
                    onClick = {
                        pokeInteraction()
                        vm.disconnect()
                    },
                    enabled = ui.connected,
                ) {
                    Icon(imageVector = Icons.Outlined.PowerSettingsNew, contentDescription = "disconnect")
                }
            }
        }
    }
}

@Composable
private fun TerminalKeyBar(
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

private const val COLOR_SCHEMES_COUNT = 7

private fun colorSchemeFor(id: Int, bgAlphaStep: Int, cursorHighContrast: Boolean): ColorScheme {
    fun alpha(a: Int, rgb: Int): Int = (a shl 24) or (rgb and 0x00FFFFFF)
    val bgA = when (bgAlphaStep.coerceIn(0, 2)) {
        0 -> 0xFF
        1 -> 0xE6
        else -> 0xCC
    }

    val base = when (id % COLOR_SCHEMES_COUNT) {
        // 0: 经典绿字黑底
        0 -> SchemeBase(fg = 0xFF33FF33.toInt(), bg = alpha(bgA, 0x000000), cursorFg = 0xFF000000.toInt(), cursorBg = 0xFF33FF33.toInt())
        // 1: 纯白黑底
        1 -> SchemeBase(fg = 0xFFEAEAEA.toInt(), bg = alpha(bgA, 0x000000), cursorFg = 0xFF000000.toInt(), cursorBg = 0xFFEAEAEA.toInt())
        // 2: Solarized Dark
        2 -> SchemeBase(fg = 0xFF93A1A1.toInt(), bg = alpha(bgA, 0x002B36), cursorFg = 0xFF002B36.toInt(), cursorBg = 0xFF93A1A1.toInt())
        // 3: Solarized Light
        3 -> SchemeBase(fg = 0xFF586E75.toInt(), bg = alpha(bgA, 0xFDF6E3), cursorFg = 0xFFFDF6E3.toInt(), cursorBg = 0xFF586E75.toInt())
        // 4: Dracula
        4 -> SchemeBase(fg = 0xFFF8F8F2.toInt(), bg = alpha(bgA, 0x282A36), cursorFg = 0xFF282A36.toInt(), cursorBg = 0xFFF8F8F2.toInt())
        // 5: Monokai
        5 -> SchemeBase(fg = 0xFFF8F8F2.toInt(), bg = alpha(bgA, 0x272822), cursorFg = 0xFF272822.toInt(), cursorBg = 0xFFF8F8F2.toInt())
        // 6: One Dark
        else -> SchemeBase(fg = 0xFFABB2BF.toInt(), bg = alpha(bgA, 0x282C34), cursorFg = 0xFF282C34.toInt(), cursorBg = 0xFFABB2BF.toInt())
    }

    val (cFg, cBg) = if (cursorHighContrast) {
        base.cursorFg to base.cursorBg
    } else {
        // 低对比：用前景/背景做轻微反差，避免光标过亮
        base.bg to base.fg
    }

    return ColorScheme(base.fg, base.bg, cFg, cBg)
}

private data class SchemeBase(
    val fg: Int,
    val bg: Int,
    val cursorFg: Int,
    val cursorBg: Int,
)

data class TerminalFullUi(
    val connecting: Boolean = true,
    val connected: Boolean = false,
    val status: String? = null,
    val title: String? = null,
    val session: TermSession? = null,
    val autoReconnect: Boolean = true,
)

class TerminalFullViewModel(
    app: Application,
    private val hostId: Long,
) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
    private val db = DbProvider.get(app)
    private val hostRepo = HostRepository(db.hostDao())
    private val keyRepo = KeyRepository(db.keyDao())
    private val ssh = SshSessionManager(keyRepo = keyRepo)

    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(TerminalFullUi())
    val ui = _ui
    private var connectAttempt = 0

    init {
        _ui.value = _ui.value.copy(autoReconnect = prefs.getBoolean("autoReconnect_$hostId", true))
        connect()
    }

    private fun connect() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(connecting = true, status = "连接中…", session = null)
            val host = withContext(Dispatchers.IO) { hostRepo.getById(hostId) }
            if (host == null) {
                _ui.value = _ui.value.copy(connecting = false, connected = false, status = "主机不存在")
                return@launch
            }

            _ui.value = _ui.value.copy(title = "${host.username}@${host.host}")

            val result = withContext(Dispatchers.IO) { ssh.connect(host) }
            if (!result.ok) {
                _ui.value = _ui.value.copy(connecting = false, connected = false, status = result.message)
                return@launch
            }

            val streams = withContext(Dispatchers.IO) { ssh.openShellStreams() }
            if (streams.isFailure) {
                _ui.value = _ui.value.copy(
                    connecting = false,
                    connected = false,
                    status = "启动终端失败：${streams.exceptionOrNull()?.message}",
                )
                return@launch
            }

            val (out, input) = streams.getOrThrow()
            val term = TermSession()
            term.setTitle("SSH")
            term.setTermIn(input)
            term.setTermOut(out)
            term.initializeEmulator(80, 24)

            term.setFinishCallback(object : TermSession.FinishCallback {
                override fun onSessionFinish(s: TermSession) {
                    viewModelScope.launch(Dispatchers.Main) {
                        _ui.value = _ui.value.copy(connected = false, connecting = false, status = "连接已断开", session = null)
                        if (_ui.value.autoReconnect) scheduleReconnect()
                    }
                }
            })

            connectAttempt = 0
            prefs.edit().putLong("lastHostId", hostId).apply()
            _ui.value = _ui.value.copy(connecting = false, connected = true, status = "已连接", session = term)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            ssh.disconnect()
            _ui.value = _ui.value.copy(connected = false, status = "已断开", session = null)
        }
    }

    fun reconnect() {
        viewModelScope.launch {
            ssh.disconnect()
            connect()
        }
    }

    private suspend fun scheduleReconnect() {
        connectAttempt += 1
        val backoffMs = (1_000L * connectAttempt).coerceAtMost(8_000L)
        _ui.value = _ui.value.copy(status = "断线，${backoffMs / 1000}s 后重连…")
        delay(backoffMs)
        if (_ui.value.autoReconnect && !_ui.value.connected && !_ui.value.connecting) {
            connect()
        }
    }

    fun toggleAutoReconnect() {
        val next = !_ui.value.autoReconnect
        prefs.edit().putBoolean("autoReconnect_$hostId", next).apply()
        _ui.value = _ui.value.copy(autoReconnect = next)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { ssh.disconnect() }
    }

    companion object {
        fun factory(app: Application, hostId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TerminalFullViewModel(app, hostId) as T
                }
            }
    }
}

