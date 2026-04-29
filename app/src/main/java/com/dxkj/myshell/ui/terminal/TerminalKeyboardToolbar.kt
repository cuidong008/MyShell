package com.dxkj.myshell.ui.terminal

import android.app.Activity
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.dxkj.myshell.ui.theme.Dimens

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

/**
 * Haven 风格的底部键盘工具条（简化版）：
 * - 终端区域下方一行水平滚动按键
 * - 方向键/Home/End/PgUp/PgDn 走 dispatchKey（让 libvterm 按模式生成正确序列）
 * - Esc/Tab/Ctrl+C 等直接发字节
 * - 左侧键盘图标用于显示/隐藏系统软键盘（IME）
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun TerminalKeyboardToolbar(
    focusRequester: FocusRequester,
    onSendBytes: (ByteArray) -> Unit,
    onDispatchKey: (modifiers: Int, key: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val imeVisible = WindowInsets.isImeVisible
    val clipboard = LocalClipboardManager.current

    Surface(
        tonalElevation = 2.dp,
        modifier = modifier.height(Dimens.TerminalKeyBarHeight),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            ToolbarIconButton(
                onClick = {
                    val window = (view.context as? Activity)?.window ?: return@ToolbarIconButton
                    val controller = WindowCompat.getInsetsController(window, view)
                    if (imeVisible) {
                        controller.hide(WindowInsetsCompat.Type.ime())
                    } else {
                        focusRequester.requestFocus()
                        controller.show(WindowInsetsCompat.Type.ime())
                    }
                },
                onLongClick = {
                    // 预留：将来可放“自定义工具条/编辑模式”
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                },
            )

            SpacerKey()

            ToolbarTextKey("Esc") { onSendBytes(byteArrayOf(0x1b)) }
            ToolbarTextKey("Tab") { onSendBytes(byteArrayOf(0x09)) }
            ToolbarTextKey("Enter") { onSendBytes(byteArrayOf('\r'.code.toByte())) }
            ToolbarTextKey("⌫") { onSendBytes(byteArrayOf(0x7f.toByte())) }
            ToolbarTextKey("Ctrl+C") { onSendBytes(byteArrayOf(0x03)) }

            SpacerKey()

            ToolbarArrowKey("↑") { onDispatchKey(0, VTERM_KEY_UP) }
            ToolbarArrowKey("↓") { onDispatchKey(0, VTERM_KEY_DOWN) }
            ToolbarArrowKey("←") { onDispatchKey(0, VTERM_KEY_LEFT) }
            ToolbarArrowKey("→") { onDispatchKey(0, VTERM_KEY_RIGHT) }
            ToolbarTextKey("Home") { onDispatchKey(0, VTERM_KEY_HOME) }
            ToolbarTextKey("End") { onDispatchKey(0, VTERM_KEY_END) }
            ToolbarTextKey("PgUp") { onDispatchKey(0, VTERM_KEY_PAGEUP) }
            ToolbarTextKey("PgDn") { onDispatchKey(0, VTERM_KEY_PAGEDOWN) }

            SpacerKey()

            ToolbarTextKey("Paste") {
                val t = clipboard.getText()?.text?.toString().orEmpty()
                if (t.isNotEmpty()) onSendBytes(t.toByteArray())
            }
            ToolbarTextKey("Copy") {
                // 复制逻辑由 termlib 的 selection 浮层处理；这里只做占位（不做“整屏复制”）
            }
        }
    }
}

@Composable
private fun SpacerKey() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(6.dp))
}

@Composable
private fun ToolbarIconButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 简化：不引入 combinedClickable，复用重复按钮的 interop 逻辑
    RepeatingButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        contentPadding = PaddingValues(0.dp),
        allowRepeat = false,
        onLongPress = onLongClick,
    ) {
        Icon(Icons.Filled.Keyboard, contentDescription = "Toggle keyboard", modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToolbarTextKey(label: String, onClick: () -> Unit) {
    RepeatingButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(32.dp),
    ) {
        androidx.compose.material3.Text(label, fontSize = 11.sp, lineHeight = 11.sp)
    }
}

@Composable
private fun ToolbarArrowKey(label: String, onClick: () -> Unit) {
    RepeatingButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(32.dp),
    ) {
        androidx.compose.material3.Text(label, fontSize = 16.sp, lineHeight = 16.sp)
    }
}

/**
 * FilledTonalButton with key repeat. Uses MotionEvent interop to avoid
 * scroll parent intercept issues (同 Haven)。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RepeatingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    allowRepeat: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    var didRepeat by remember { mutableStateOf(false) }
    var downTime by remember { mutableStateOf(0L) }

    LaunchedEffect(isPressed, allowRepeat) {
        if (isPressed && allowRepeat) {
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
        modifier = modifier.pointerInteropFilter { ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    didRepeat = false
                    downTime = android.os.SystemClock.elapsedRealtime()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - downTime
                    val longPress = elapsed >= 450L
                    if (longPress) {
                        onLongPress?.invoke()
                    } else if (!didRepeat) {
                        onClick()
                    }
                    isPressed = false
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    true
                }
                else -> false
            }
        },
        contentPadding = contentPadding,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        content()
    }
}

