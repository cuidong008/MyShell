package com.dxkj.myshell.ui.terminal

import android.app.Activity
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.dxkj.myshell.ui.theme.Dimens
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.layout.windowInsetsPadding
import kotlin.math.roundToInt

// --- VT / VTerm keycodes (same as Haven KeyboardToolbar) ---
private const val ESC = "\u001b"
private val KEY_ESC = byteArrayOf(0x1b)
private val KEY_TAB = byteArrayOf(0x09)
private val KEY_SHIFT_TAB = "$ESC[Z".toByteArray()

// VTermKey constants (from libvterm/include/vterm_keycodes.h)
private const val VTERM_KEY_UP = 5
private const val VTERM_KEY_DOWN = 6
private const val VTERM_KEY_LEFT = 7
private const val VTERM_KEY_RIGHT = 8
private const val VTERM_KEY_HOME = 11
private const val VTERM_KEY_END = 12
private const val VTERM_KEY_PAGEUP = 13
private const val VTERM_KEY_PAGEDOWN = 14

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 80L

private val NAV_CELL_WIDTH = 44.dp

private data class CustomKey(val label: String, val send: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HavenKeyboardToolbar(
    focusRequester: FocusRequester,
    onSendBytes: (ByteArray) -> Unit,
    onDispatchKey: (modifiers: Int, key: Int) -> Unit,
    modifier: Modifier = Modifier,
    showVncIcon: Boolean = true,
    onVncTap: (() -> Unit)? = null,
    modifierManager: SimpleModifierManager,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val imeVisible = WindowInsets.isImeVisible
    val clipboard = LocalClipboardManager.current

    val shiftActive = modifierManager.isShiftActive()
    val ctrlActive = modifierManager.isCtrlActive()
    val altActive = modifierManager.isAltActive()

    var showAddDialog by remember { mutableStateOf(false) }
    var showSnippets by remember { mutableStateOf(false) }

    val customKeys = remember { mutableStateListOf<CustomKey>() }
    LaunchedEffect(Unit) {
        customKeys.clear()
        customKeys.addAll(loadCustomKeys(context))
    }

    fun persist() {
        saveCustomKeys(context, customKeys.toList())
    }

    fun sendChar(ch: Char) {
        val b = if (ctrlActive && ch.code in 0x40..0x7F) {
            byteArrayOf((ch.code and 0x1F).toByte())
        } else {
            ch.toString().toByteArray()
        }
        if (altActive) {
            onSendBytes(byteArrayOf(0x1b) + b)
        } else {
            onSendBytes(b)
        }
        // 手动发送绕过了 KeyboardHandler，因此需要显式清空一次性修饰键
        modifierManager.clearTransients()
    }

    fun paste() {
        val t = clipboard.getText()?.text?.toString().orEmpty()
        if (t.isNotEmpty()) onSendBytes(t.toByteArray())
        // 与 Haven 的 KeyboardHandler 行为对齐：一次性修饰键在“完成一次输入”后应清空
        modifierManager.clearTransients()
    }

    fun sendBytesFromToolbar(bytes: ByteArray) {
        onSendBytes(bytes)
        // 工具条的发送绕过 KeyboardHandler，这里手动清空一次性修饰键
        modifierManager.clearTransients()
    }

    fun dispatchKeyFromToolbar(key: Int) {
        onDispatchKey(0, key)
        modifierManager.clearTransients()
    }

    if (showAddDialog) {
        AddCustomKeyDialog(
            onDismiss = { showAddDialog = false },
            onSave = { label, send ->
                customKeys.add(CustomKey(label.trim(), send))
                persist()
                showAddDialog = false
            },
        )
    }

    if (showSnippets) {
        SnippetsDialog(
            items = customKeys.toList(),
            onDismiss = { showSnippets = false },
            onTap = { k ->
                val send = k.send
                if (send == "PASTE") paste() else onSendBytes(send.toByteArray())
                showSnippets = false
            },
            onDelete = { k ->
                customKeys.removeAll { it.label == k.label && it.send == k.send }
                persist()
            },
            onAdd = { showSnippets = false; showAddDialog = true },
        )
    }

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier
            // 让工具条自己吃掉导航栏 inset，避免外部再叠 systemBarsPadding 导致可点击区域被“挤没”
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(Dimens.TerminalKeyBarHeight)
            .fillMaxWidth(),
    ) {
        // 两行，三段式对齐：左侧按键 + 中间 nav block + 右侧自定义
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier
                .horizontalScroll(scroll)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // --- Left column (two rows) ---
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 剪刀（snippets）
                    ToolbarIconButton(
                        icon = Icons.Filled.ContentCut,
                        desc = "Snippets",
                        onClick = { showSnippets = true },
                        onLongClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                    )
                    // 键盘（IME）
                    ToolbarIconButton(
                        icon = Icons.Filled.Keyboard,
                        desc = "Keyboard",
                        onClick = {
                            val window = (view.context as? Activity)?.window ?: return@ToolbarIconButton
                            val controller = WindowCompat.getInsetsController(window, view)
                            if (imeVisible) controller.hide(WindowInsetsCompat.Type.ime())
                            else {
                                focusRequester.requestFocus()
                                controller.show(WindowInsetsCompat.Type.ime())
                            }
                        },
                        onLongClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                    )
                    TextKey("Esc") { sendBytesFromToolbar(KEY_ESC) }
                    TextKey("Tab") {
                        if (shiftActive) {
                            sendBytesFromToolbar(KEY_SHIFT_TAB)
                        } else {
                            sendBytesFromToolbar(KEY_TAB)
                        }
                    }
                    TextKey("Paste") { paste() }
                    SymbolKey("/") { sendChar('/') }
                    TextKey("Home") { dispatchKeyFromToolbar(VTERM_KEY_HOME) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showVncIcon) {
                        ToolbarIconButton(
                            icon = Icons.Filled.DesktopWindows,
                            desc = "VNC Desktop",
                            onClick = { onVncTap?.invoke() },
                            onLongClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                            enabled = onVncTap != null,
                        )
                    }
                    ToggleKey("Shift", shiftActive) { modifierManager.toggleShift() }
                    ToggleKey("Ctrl", ctrlActive) { modifierManager.toggleCtrl() }
                    ToggleKey("Alt", altActive) { modifierManager.toggleAlt() }
                    TextKey("End") { dispatchKeyFromToolbar(VTERM_KEY_END) }
                }
            }

            // --- Nav block (fixed grid 4x2) ---
            Column(
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    NavText("Home") { dispatchKeyFromToolbar(VTERM_KEY_HOME) }
                    NavArrow("↑") { dispatchKeyFromToolbar(VTERM_KEY_UP) }
                    NavText("End") { dispatchKeyFromToolbar(VTERM_KEY_END) }
                    NavText("PgUp") { dispatchKeyFromToolbar(VTERM_KEY_PAGEUP) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    NavArrow("←") { dispatchKeyFromToolbar(VTERM_KEY_LEFT) }
                    NavArrow("↓") { dispatchKeyFromToolbar(VTERM_KEY_DOWN) }
                    NavArrow("→") { dispatchKeyFromToolbar(VTERM_KEY_RIGHT) }
                    NavText("PgDn") { dispatchKeyFromToolbar(VTERM_KEY_PAGEDOWN) }
                }
            }

            // --- Right column: custom keys + add button (two rows) ---
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val row1 = customKeys.take(6)
                    row1.forEach { k ->
                        TextKey(k.label) {
                            if (k.send == "PASTE") paste() else sendBytesFromToolbar(k.send.toByteArray())
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val row2 = customKeys.drop(6).take(6)
                    row2.forEach { k ->
                        TextKey(k.label) {
                            if (k.send == "PASTE") paste() else sendBytesFromToolbar(k.send.toByteArray())
                        }
                    }
                    IconButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add key", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NavCell(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.width(NAV_CELL_WIDTH).height(32.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { content() }
}

@Composable
private fun NavArrow(label: String, onClick: () -> Unit) {
    NavCell {
        RepeatingButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(label, fontSize = 16.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun NavText(label: String, onClick: () -> Unit) {
    NavCell {
        RepeatingButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(label, fontSize = 11.sp, lineHeight = 11.sp)
        }
    }
}

@Composable
private fun TextKey(label: String, onClick: () -> Unit) {
    RepeatingButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 11.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun SymbolKey(label: String, onClick: () -> Unit) = TextKey(label, onClick)

@Composable
private fun ToggleKey(label: String, active: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = if (active) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(label, fontSize = 11.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean = true,
) {
    RepeatingButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        contentPadding = PaddingValues(0.dp),
        allowRepeat = false,
        onLongPress = onLongClick,
        enabled = enabled,
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RepeatingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    allowRepeat: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    var didRepeat by remember { mutableStateOf(false) }
    var downTime by remember { mutableStateOf(0L) }

    LaunchedEffect(isPressed, allowRepeat, enabled) {
        if (enabled && isPressed && allowRepeat) {
            didRepeat = false
            kotlinx.coroutines.delay(REPEAT_DELAY_MS)
            didRepeat = true
            while (true) {
                onClick()
                kotlinx.coroutines.delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    FilledTonalButton(
        onClick = {}, // handled by pointerInteropFilter
        enabled = enabled,
        modifier = modifier.pointerInteropFilter { ev ->
            if (!enabled) return@pointerInteropFilter false
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    didRepeat = false
                    downTime = android.os.SystemClock.elapsedRealtime()
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // 关键：持续消费 move，避免父级 horizontalScroll 抢走手势导致收不到 UP
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - downTime
                    val longPress = elapsed >= 450L
                    if (longPress) onLongPress?.invoke()
                    else if (!didRepeat) onClick()
                    isPressed = false
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    true
                }
                else -> true
            }
        },
        contentPadding = contentPadding,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) { content() }
}

// --- dialogs + persistence ---

@Composable
private fun AddCustomKeyDialog(
    onDismiss: () -> Unit,
    onSave: (label: String, send: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var sendText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义键") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("显示文字") },
                    placeholder = { Text("例如：^C / Paste") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sendText,
                    onValueChange = { sendText = it },
                    singleLine = true,
                    label = { Text("发送内容") },
                    placeholder = { Text("例如：\\u0003 或 PASTE 或 /") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "支持：\\u001b(Esc) \\u0003(Ctrl+C) \\n \\r \\t；输入 PASTE 表示粘贴剪贴板。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.trim().isNotEmpty() && sendText.trim().isNotEmpty(),
                onClick = { onSave(label, parseSendSequence(sendText.trim())) },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SnippetsDialog(
    items: List<CustomKey>,
    onDismiss: () -> Unit,
    onTap: (CustomKey) -> Unit,
    onDelete: (CustomKey) -> Unit,
    onAdd: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("剪刀（Snippets）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) {
                    Text("还没有自定义键。你可以先点「添加」创建一个。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    items.forEach { k ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(k.label)
                                Text(
                                    displaySendSequence(k.send),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { onTap(k) }) { Text("发送") }
                                TextButton(onClick = { onDelete(k) }) { Text("删除") }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Text("添加自定义键")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun prefKey(): String = "terminal_haven_toolbar_custom_keys_v1"

private fun loadCustomKeys(context: Context): List<CustomKey> {
    val prefs = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
    val raw = prefs.getString(prefKey(), null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label").orEmpty()
                val send = o.optString("send").orEmpty()
                if (label.isNotBlank() && send.isNotBlank()) add(CustomKey(label, send))
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun saveCustomKeys(context: Context, keys: List<CustomKey>) {
    val prefs = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
    val arr = JSONArray()
    keys.forEach { k ->
        arr.put(
            JSONObject()
                .put("label", k.label)
                .put("send", k.send),
        )
    }
    prefs.edit().putString(prefKey(), arr.toString()).apply()
}

private fun displaySendSequence(send: String): String {
    if (send == "PASTE") return "Paste clipboard"
    return send.map { ch ->
        when {
            ch.code < 0x20 -> "\\u${ch.code.toString(16).padStart(4, '0')}"
            else -> ch.toString()
        }
    }.joinToString("")
}

private fun parseSendSequence(input: String): String {
    if (input.equals("PASTE", ignoreCase = true)) return "PASTE"
    val sb = StringBuilder()
    var i = 0
    while (i < input.length) {
        if (i + 1 < input.length && input[i] == '\\') {
            when (input[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                'u' -> {
                    if (i + 5 < input.length) {
                        val hex = input.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6 }
                        else { sb.append(input[i]); i++ }
                    } else { sb.append(input[i]); i++ }
                }
                else -> { sb.append(input[i]); i++ }
            }
        } else {
            sb.append(input[i])
            i++
        }
    }
    return sb.toString()
}

