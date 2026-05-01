package com.dxkj.myshell.ui.screens

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.WindowManager
import android.view.View
import android.view.ViewConfiguration
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dxkj.myshell.R
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.prefs.AppPreferences
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.input.rememberHardwareInputState
import com.dxkj.myshell.terminal.TerminalSessionPool
import com.dxkj.myshell.ui.terminal.HavenKeyboardToolbar
import com.dxkj.myshell.ui.terminal.SimpleModifierManager
import com.dxkj.myshell.ui.theme.Dimens
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.SelectionController
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import android.util.Log

private fun readIntFieldNoThrow(obj: Any, fieldName: String): Int? {
    return try {
        var cls: Class<*>? = obj::class.java
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                return (f.get(obj) as? Int)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        null
    } catch (_: Throwable) {
        null
    }
}

private fun getLineHeightPxNoThrow(view: View): Int {
    val h = readIntFieldNoThrow(view, "mCharacterHeight")
        ?: readIntFieldNoThrow(view, "mCharHeight")
        ?: readIntFieldNoThrow(view, "mFontHeight")
    return (h ?: 0).coerceAtLeast(0)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalHubScreen(
    initialHostId: Long?,
    onExit: () -> Unit,
    immersive: Boolean = true,
    showBack: Boolean = true,
    showTopOverlay: Boolean = true,
    compactTopOverlay: Boolean = false,
    onToggleTopOverlay: (() -> Unit)? = null,
    /** 会话页内嵌终端：底部 Esc/方向键条默认应收起，避免占用一整条灰色区域挡画面 */
    embeddedInSessionsPane: Boolean = false,
    embeddedBottomToolbarExpanded: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboard = LocalClipboardManager.current
    TerminalSessionPool.init(context.applicationContext as Application)

    val termScheme by AppPreferences.terminalColorScheme.collectAsState()
    val terminalFontSp by AppPreferences.terminalFontSize.collectAsState()
    val termBg = AppPreferences.argbLongToColor(termScheme.background)
    val termFg = AppPreferences.argbLongToColor(termScheme.foreground)
    val hackTypeface = remember(context) {
        try {
            ResourcesCompat.getFont(context, R.font.hack_regular) ?: Typeface.MONOSPACE
        } catch (_: Throwable) {
            Typeface.MONOSPACE
        }
    }

    val sessions by TerminalSessionPool.sessions.collectAsState()
    val activeId by TerminalSessionPool.activeSessionId.collectAsState()
    val active = sessions.firstOrNull { it.sessionId == activeId } ?: sessions.lastOrNull()
    val prefs = remember(context) { context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE) }

    val hwInput = rememberHardwareInputState()
    val forceShowKeybar by AppPreferences.terminalForceShowKeybar.collectAsState()
    val forceShowIme by AppPreferences.terminalForceShowIme.collectAsState()
    val pointerMode by AppPreferences.terminalPointerMode.collectAsState()
    val copyOnSelect by AppPreferences.terminalCopyOnSelect.collectAsState()

    var keyBarVisibleStandalone by remember { mutableStateOf(true) }
    var showHostPicker by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showCopyHint by remember { mutableStateOf(false) }
    var copyHintText by remember { mutableStateOf("已复制到剪贴板") }
    fun showHint(text: String) {
        copyHintText = text
        showCopyHint = true
    }

    val hideKeyUiByKeyboard = hwInput.hardwareKeyboardConnected && !forceShowKeybar
    val hideImeByKeyboard = hwInput.hardwareKeyboardConnected && !forceShowIme
    val keyBarVisibleBase =
        if (embeddedInSessionsPane) embeddedBottomToolbarExpanded else keyBarVisibleStandalone
    // 设置里「强制显示」：覆盖实体键盘隐藏、会话页默认收起、以及每主机里关掉过的偏好
    val keyBarVisible = keyBarVisibleBase || forceShowKeybar
    val toolbarVisible = keyBarVisible && !hideKeyUiByKeyboard
    val imeVisible = WindowInsets.isImeVisible
    // 默认在 IME 可见时收起工具条以免挡输入行；强制开启时仍显示（用户明确要求）
    val effectiveToolbarVisible =
        toolbarVisible && (!imeVisible || forceShowKeybar)
    val bottomBarHeight: Dp = if (effectiveToolbarVisible) Dimens.TerminalKeyBarHeight else 0.dp
    val desktopPointerMode = when (pointerMode) {
        AppPreferences.TerminalPointerMode.ON -> true
        AppPreferences.TerminalPointerMode.OFF -> false
        AppPreferences.TerminalPointerMode.AUTO -> hwInput.mouseLikeConnected
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

    // 切换会话时加载对应 host 的偏好（全屏终端）；内嵌会话页的底部条由 SessionsScreen 状态 + keyBar_embedded_* 控制
    LaunchedEffect(active?.hostId, embeddedInSessionsPane) {
        val hid = active?.hostId ?: return@LaunchedEffect
        if (!embeddedInSessionsPane) {
            keyBarVisibleStandalone = prefs.getBoolean("keyBar_$hid", true)
        }
    }

    LaunchedEffect(keyBarVisibleStandalone, active?.hostId, embeddedInSessionsPane) {
        val hid = active?.hostId ?: return@LaunchedEffect
        if (!embeddedInSessionsPane) {
            prefs.edit().putBoolean("keyBar_$hid", keyBarVisibleStandalone).apply()
        }
    }

    // 与 TerminalSessionPool 创建时一致：把配色同步到 libvterm，使单元格默认背景与 Compose 传入的 backgroundColor 一致（否则格子仍是黑底）。
    LaunchedEffect(active?.sessionId, termScheme.name, termFg, termBg) {
        val emu = active?.emulator ?: return@LaunchedEffect
        emu.setDefaultColors(termFg.toArgb(), termBg.toArgb())
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

    Column(modifier = Modifier.fillMaxSize().background(termBg)) {
        // 上半区：终端 + 浮层（放进 Box，继续用 align 避免 ColumnScope 限制）
        val emulator = active?.emulator
        val focusRequester = remember(active?.sessionId) { FocusRequester() }
        val modifierManager = remember(active?.sessionId) { SimpleModifierManager() }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (emulator != null) {
                // 参考 Haven：用 key(sessionId) 固定 Terminal 生命周期，避免状态更新导致频繁 dispose/recreate
                key(active!!.sessionId, termScheme.name, terminalFontSp) {
                    var selectionController by remember { mutableStateOf<SelectionController?>(null) }
                    // 延迟开启 IME，避免 termlib 在节点未完全挂载时触发 focus/IME 竞态
                    var showIme by remember(active!!.sessionId) { mutableStateOf(false) }
                    LaunchedEffect(active!!.sessionId) {
                        showIme = false
                        delay(200)
                        showIme = true
                    }

                    Terminal(
                        terminalEmulator = emulator,
                        modifier = Modifier
                            .fillMaxSize()
                            .focusable(),
                        typeface = hackTypeface,
                        initialFontSize = terminalFontSp.sp,
                        backgroundColor = termBg,
                        foregroundColor = termFg,
                        keyboardEnabled = true,
                        showSoftKeyboard = showIme && !hideImeByKeyboard,
                        desktopPointerMode = desktopPointerMode,
                        copyOnSelect = copyOnSelect,
                        focusRequester = focusRequester,
                        modifierManager = modifierManager,
                        onSelectionControllerAvailable = { selectionController = it },
                        // 让 termlib 内置 selection 菜单的 “Paste” 可用
                        onPasteRequest = {
                            val t = clipboard.getText()?.text?.toString().orEmpty()
                            if (t.isNotBlank()) {
                                val b = t.toByteArray()
                                TerminalSessionPool.sendBytes(active.sessionId, b)
                            } else {
                                showHint("剪贴板为空")
                            }
                        },
                    )
                }
            }

            // 连接状态提示（在工具条上方留出空间）
            if (active != null && !active.status.isNullOrBlank() && (active.connecting || !active.connected)) {
                Text(
                    text = active.status ?: "",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(if (immersive) Modifier.systemBarsPadding() else Modifier)
                        .padding(bottom = bottomBarHeight + Dimens.OverlayPaddingH)
                        .background(
                            MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.86f),
                            RoundedCornerShape(Dimens.OverlayCornerSm),
                        )
                        .padding(horizontal = Dimens.OverlayPaddingH, vertical = Dimens.OverlayPaddingV),
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
                        .padding(bottom = bottomBarHeight + Dimens.OverlayPaddingH)
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

        // 下半区：Haven 风格底部键盘工具条（不覆盖在终端上，避免触摸被吞）
        if (emulator != null && effectiveToolbarVisible) {
            HavenKeyboardToolbar(
                focusRequester = focusRequester,
                onSendBytes = { bytes ->
                    TerminalSessionPool.sendBytes(active.sessionId, bytes)
                },
                onDispatchKey = { _, key ->
                    try {
                        emulator.dispatchKey(0, key)
                    } catch (_: Throwable) {
                    }
                },
                modifier = Modifier
                        .fillMaxWidth(),
                modifierManager = modifierManager,
            )
        } else {
            Spacer(modifier = Modifier.height(if (emulator != null) bottomBarHeight else Dimens.TerminalKeyBarHeight))
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

