package com.dxkj.myshell.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

object HardwareInputProbe {
    fun hasHardwareKeyboard(context: Context): Boolean {
        val cfg = context.resources.configuration
        if (cfg.keyboard != Configuration.KEYBOARD_NOKEYS) return true
        return InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id) ?: return@any false
            if (d.isVirtual) return@any false
            d.sources and InputDevice.SOURCE_KEYBOARD != 0
        }
    }

    fun hasMouseLikeDevice(context: Context): Boolean =
        InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id) ?: return@any false
            if (d.isVirtual) return@any false
            val s = d.sources
            (s and InputDevice.SOURCE_MOUSE) != 0 ||
                (s and InputDevice.SOURCE_MOUSE_RELATIVE) != 0
        }
}

data class HardwareInputState(
    val hardwareKeyboardConnected: Boolean,
    val mouseLikeConnected: Boolean,
)

/**
 * 监听实体键盘 / 鼠标热插拔与配置变化（比仅依赖 Configuration 更可靠）。
 */
@Composable
fun rememberHardwareInputState(): HardwareInputState {
    val context = LocalContext.current
    var keyboard by remember {
        mutableStateOf(HardwareInputProbe.hasHardwareKeyboard(context))
    }
    var mouse by remember {
        mutableStateOf(HardwareInputProbe.hasMouseLikeDevice(context))
    }
    DisposableEffect(context) {
        fun refresh() {
            keyboard = HardwareInputProbe.hasHardwareKeyboard(context)
            mouse = HardwareInputProbe.hasMouseLikeDevice(context)
        }
        val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val deviceListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refresh()
            override fun onInputDeviceRemoved(deviceId: Int) = refresh()
            override fun onInputDeviceChanged(deviceId: Int) = refresh()
        }
        im.registerInputDeviceListener(deviceListener, null)
        val cfgReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) = refresh()
        }
        ContextCompat.registerReceiver(
            context,
            cfgReceiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            im.unregisterInputDeviceListener(deviceListener)
            context.unregisterReceiver(cfgReceiver)
        }
    }
    return HardwareInputState(keyboard, mouse)
}
