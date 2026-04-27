package com.dxkj.myshell.ui.screens

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.InputDevice
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Keyboard
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
import com.dxkj.myshell.terminal.SafeEmulatorView
import com.dxkj.myshell.terminal.TerminalSessionPool
import com.dxkj.myshell.ui.theme.Dimens
import jackpal.androidterm.emulatorview.ColorScheme
import jackpal.androidterm.emulatorview.EmulatorView
import kotlinx.coroutines.flow.map
import kotlin.math.abs

@Composable
fun TerminalHubScreen(
    initialHostId: Long?,
    onExit: () -> Unit,
    immersive: Boolean = true,
    showBack: Boolean = true,
    showTopOverlay: Boolean = true,
    compactTopOverlay: Boolean = false,
    onToggleTopOverlay: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboard = LocalClipboardManager.current
    val imm = remember(context) { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }

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
    var showCopyHint by remember { mutableStateOf(false) }
    var selectingText by remember { mutableStateOf(false) }
    var copyHintText by remember { mutableStateOf("已复制到剪贴板") }

    fun safeGetSelectedText(): String? {
        // 这个库在某些边界条件下会在内部抛 ArrayIndexOutOfBounds（选区为 -1 等）
        return try {
            viewRef?.getSelectedText()
        } catch (_: Throwable) {
            null
        }
    }

    fun handleMouseMobaXtermLike(ev: MotionEvent): Boolean {
        val isMouse = (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
        if (!isMouse) return false

        // 右键：粘贴
        val right = (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        if (right && ev.action == MotionEvent.ACTION_DOWN) {
            val t = clipboard.getText()?.text.orEmpty()
            if (t.isNotBlank()) active?.term?.write(t)
            return true
        }

        // 中键：粘贴（更接近桌面）
        val middle = (ev.buttonState and MotionEvent.BUTTON_TERTIARY) != 0
        if (middle && ev.action == MotionEvent.ACTION_DOWN) {
            val t = clipboard.getText()?.text.orEmpty()
            if (t.isNotBlank()) active?.term?.write(t)
            return true
        }

        // 左键：单击仍走终端交互；拖动超过阈值才进入选择；松开自动复制并退出选择
        val left = (ev.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
        if (!left) return false
        return false
    }

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

    if (showBack) {
        BackHandler { onExit() }
    }

    if (immersive) {
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
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 终端主体
        val term = active?.term
        if (term != null) {
            AndroidView(
                factory = { ctx ->
                    val dm = ctx.resources.displayMetrics
                    SafeEmulatorView(ctx, term, dm).apply {
                        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
                        var downX = 0f
                        var downY = 0f
                        var dragging = false

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
                        setOnTouchListener { v, ev ->
                            val isMouse = (ev.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                            if (isMouse) {
                                // 右键 / 中键：粘贴
                                val right = (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                                val middle = (ev.buttonState and MotionEvent.BUTTON_TERTIARY) != 0
                                if ((right || middle) && ev.action == MotionEvent.ACTION_DOWN) {
                                    val t = clipboard.getText()?.text.orEmpty()
                                    if (t.isNotBlank()) active.term?.write(t)
                                    return@setOnTouchListener true
                                }

                                val left = (ev.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
                                when (ev.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        downX = ev.x
                                        downY = ev.y
                                        dragging = false
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (left && !dragging) {
                                            val dx = abs(ev.x - downX)
                                            val dy = abs(ev.y - downY)
                                            if (dx > slop || dy > slop) {
                                                dragging = true
                                                if (!selectingText) {
                                                    try {
                                                        toggleSelectingText()
                                                        selectingText = true
                                                    } catch (_: Throwable) {
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        if (selectingText && dragging) {
                                            val selected = safeGetSelectedText()?.takeIf { it.isNotBlank() }
                                            if (!selected.isNullOrBlank()) {
                                                clipboard.setText(AnnotatedString(selected))
                                                copyHintText = "已复制到剪贴板"
                                                showCopyHint = true
                                            }
                                            try {
                                                toggleSelectingText()
                                            } catch (_: Throwable) {
                                            }
                                            selectingText = false
                                            dragging = false
                                        }
                                    }
                                }
                            }
                            v.onTouchEvent(ev)
                        }
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
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 顶部：标签栏 + 工具条（嵌入会话工作区时可关闭，避免占空间）
        // 顶部按钮已按需求全部移除（尽量接近桌面终端体验：鼠标选择/右键粘贴）

        if (active != null && !active.status.isNullOrBlank() && (active.connecting || !active.connected)) {
            Text(
                text = active.status ?: "",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (immersive) Modifier.systemBarsPadding() else Modifier)
                    .padding(bottom = if (keyBarVisible) Dimens.TerminalKeyBarHeight else Dimens.OverlayPaddingH)
                    .background(
                        MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.86f),
                        RoundedCornerShape(Dimens.OverlayCornerSm),
                    )
                    .padding(horizontal = Dimens.OverlayPaddingH, vertical = Dimens.OverlayPaddingV),
            )
        }

        if (active?.term != null && keyBarVisible) {
            HubKeyBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (immersive) Modifier.systemBarsPadding() else Modifier)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f))
                    .padding(horizontal = Dimens.SpacingSm, vertical = Dimens.SpacingSm),
                onKey = { seq -> active.term.write(seq) },
                onPaste = {
                    val t = clipboard.getText()?.text.orEmpty()
                    if (t.isNotBlank()) active.term.write(t)
                },
                onToggleKeyBar = { keyBarVisible = false },
            )
        }

        if (active?.term != null && !keyBarVisible) {
            FilledTonalIconButton(
                onClick = { keyBarVisible = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (immersive) Modifier.systemBarsPadding() else Modifier)
                    .padding(bottom = Dimens.SpacingSm)
                    .background(
                        MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.86f),
                        RoundedCornerShape(Dimens.OverlayCorner),
                    ),
            ) {
                Text("快捷键", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelSmall)
            }
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
                            showHostPicker = true
                        }) { Text("新建会话") }
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

        if (showCopyHint) {
            // 轻提示：避免引入 SnackbarHost/Scaffold 改动过大
            Text(
                text = copyHintText,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (immersive) Modifier.systemBarsPadding() else Modifier)
                    .padding(bottom = if (keyBarVisible) Dimens.TerminalKeyBarHeight else Dimens.OverlayPaddingH)
                    .background(
                        MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.86f),
                        RoundedCornerShape(Dimens.OverlayCornerSm),
                    )
                    .padding(horizontal = Dimens.OverlayPaddingH, vertical = Dimens.OverlayPaddingV),
            )
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1200)
                showCopyHint = false
            }
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
    onToggleKeyBar: () -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val s = MaterialTheme.typography.labelSmall
        FilledTonalIconButton(onClick = onToggleKeyBar) {
            Icon(imageVector = Icons.Outlined.KeyboardArrowDown, contentDescription = "hide keybar")
        }
        FilledTonalIconButton(onClick = { onKey("\u001B") }) { Text("Esc", style = s) }
        FilledTonalIconButton(onClick = { onKey("\t") }) { Text("Tab", style = s) }
        FilledTonalIconButton(onClick = { onKey("\u007F") }) { Text("⌫", style = s) }
        FilledTonalIconButton(onClick = { onKey("\r") }) { Text("Enter", style = s) }
        FilledTonalIconButton(onClick = { onKey("\u0003") }) { Text("Ctrl+C", style = s) }
        FilledTonalIconButton(onClick = { onKey("\u001B[A") }) { Text("↑", style = s) }
        FilledTonalIconButton(onClick = { onKey("\u001B[B") }) { Text("↓", style = s) }
        FilledTonalIconButton(onClick = { onKey("\u001B[D") }) { Text("←", style = s) }
        FilledTonalIconButton(onClick = { onKey("\u001B[C") }) { Text("→", style = s) }
        FilledTonalIconButton(onClick = onPaste) { Text("粘贴", style = s) }
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

