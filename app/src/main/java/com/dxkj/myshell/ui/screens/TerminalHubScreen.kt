package com.dxkj.myshell.ui.screens

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.InputDevice
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.res.ResourcesCompat
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.terminal.TerminalSessionPool
import com.dxkj.myshell.ui.terminal.HavenKeyboardToolbar
import com.dxkj.myshell.ui.terminal.SimpleModifierManager
import com.dxkj.myshell.ui.theme.Dimens
import com.dxkj.myshell.R
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
    val hackTypeface = remember(context) {
        try {
            ResourcesCompat.getFont(context, R.font.hack_regular) ?: Typeface.MONOSPACE
        } catch (_: Throwable) {
            Typeface.MONOSPACE
        }
    }

    TerminalSessionPool.init(context.applicationContext as Application)

    val sessions by TerminalSessionPool.sessions.collectAsState()
    val activeId by TerminalSessionPool.activeSessionId.collectAsState()
    val active = sessions.firstOrNull { it.sessionId == activeId } ?: sessions.lastOrNull()
    LaunchedEffect(activeId, active?.sessionId, active?.emulator) {
        Log.d("TerminalHubScreen", "activeId=$activeId activeSession=${active?.sessionId} emulator=${active?.emulator?.let { System.identityHashCode(it) }} connecting=${active?.connecting} connected=${active?.connected}")
    }

    val prefs = remember(context) { context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE) }

    var fontSize by remember { mutableIntStateOf(16) }
    // termlib 的配色在创建 emulator 时确定；这里先固定黑底白字，后续按 Haven 的 setDefaultColors 再做可配置化。
    var keyBarVisible by remember { mutableStateOf(true) }
    var showHostPicker by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showCopyHint by remember { mutableStateOf(false) }
    var copyHintText by remember { mutableStateOf("已复制到剪贴板") }
    fun showHint(text: String) {
        copyHintText = text
        showCopyHint = true
        Log.d("TerminalHubScreen", "hint: $text")
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
        keyBarVisible = prefs.getBoolean("keyBar_$hid", true)
    }

    LaunchedEffect(fontSize, active?.hostId) {
        val hid = active?.hostId ?: return@LaunchedEffect
        prefs.edit().putInt("fontSize_$hid", fontSize).apply()
    }
    // 配色偏好暂不处理（见上方注释）
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

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 上半区：终端 + 浮层（放进 Box，继续用 align 避免 ColumnScope 限制）
        val emulator = active?.emulator
        val focusRequester = remember(active?.sessionId) { FocusRequester() }
        val modifierManager = remember(active?.sessionId) { SimpleModifierManager() }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (emulator != null) {
                // 参考 Haven：用 key(sessionId) 固定 Terminal 生命周期，避免状态更新导致频繁 dispose/recreate
                key(active!!.sessionId) {
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
                        initialFontSize = fontSize.sp,
                        backgroundColor = Color.Black,
                        foregroundColor = Color.White,
                        keyboardEnabled = true,
                        showSoftKeyboard = showIme,
                        focusRequester = focusRequester,
                        modifierManager = modifierManager,
                        onSelectionControllerAvailable = { selectionController = it },
                        // 让 termlib 内置 selection 菜单的 “Paste” 可用
                        onPasteRequest = {
                            val t = clipboard.getText()?.text?.toString().orEmpty()
                            if (t.isNotBlank()) {
                                val b = t.toByteArray()
                                showHint("已发送 Paste：${b.size}B")
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
                        .padding(bottom = Dimens.TerminalKeyBarHeight + Dimens.OverlayPaddingH)
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
                        .padding(bottom = Dimens.TerminalKeyBarHeight + Dimens.OverlayPaddingH)
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
        if (emulator != null) {
            HavenKeyboardToolbar(
                focusRequester = focusRequester,
                onSendBytes = { bytes ->
                    val first = bytes.firstOrNull()?.toInt()?.and(0xFF)
                    showHint("已发送：${bytes.size}B first=0x${first?.toString(16) ?: "--"}")
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
                showVncIcon = true,
                onVncTap = null,
                modifierManager = modifierManager,
            )
        } else {
            Spacer(modifier = Modifier.height(Dimens.TerminalKeyBarHeight))
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

