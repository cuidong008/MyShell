package com.dxkj.myshell.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.connectbot.terminal.ModifierManager

/**
 * 一个轻量的 ModifierManager 实现，用于把工具条的 Ctrl/Alt/Shift
 * 叠加到 termlib 的 KeyboardHandler 上，让 IME/硬件键盘输入也能组合修饰键。
 *
 * 行为：
 * - 仅实现“一次性（transient）粘滞键”：按一次开启，再按一次关闭
 * - termlib 在每次成功派发一个输入后会调用 [clearTransients] 自动清空
 */
class SimpleModifierManager : ModifierManager {
    // Haven 风格：
    // - Ctrl/Alt 更像“锁定键”（再次点击关闭）
    // - Shift 更像“一次性键”（用一次后自动清）
    private var ctrlLocked by mutableStateOf(false)
    private var altLocked by mutableStateOf(false)
    private var shiftTransient by mutableStateOf(false)

    fun toggleCtrl() { ctrlLocked = !ctrlLocked }
    fun toggleAlt() { altLocked = !altLocked }
    fun toggleShift() { shiftTransient = !shiftTransient }

    fun setCtrl(v: Boolean) { ctrlLocked = v }
    fun setAlt(v: Boolean) { altLocked = v }
    fun setShift(v: Boolean) { shiftTransient = v }

    override fun isCtrlActive(): Boolean = ctrlLocked
    override fun isAltActive(): Boolean = altLocked
    override fun isShiftActive(): Boolean = shiftTransient

    override fun clearTransients() {
        // 只清理“一次性键”
        shiftTransient = false
    }
}

