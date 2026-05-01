package com.dxkj.myshell.data.prefs

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局应用偏好（SharedPreferences）：应用主题、终端配色、终端字号。
 */
object AppPreferences {
    private const val PREFS = "myshell_app_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_TERMINAL_COLOR_SCHEME = "terminal_color_scheme"
    private const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size"
    private const val KEY_TERMINAL_COPY_ON_SELECT = "terminal_copy_on_select"

    const val DEFAULT_FONT_SIZE = 14
    const val MIN_FONT_SIZE = 8
    const val MAX_FONT_SIZE = 32

    private lateinit var prefs: android.content.SharedPreferences

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _terminalColorScheme = MutableStateFlow(TerminalColorScheme.DEFAULT)
    val terminalColorScheme: StateFlow<TerminalColorScheme> = _terminalColorScheme.asStateFlow()

    private val _terminalFontSize = MutableStateFlow(DEFAULT_FONT_SIZE)
    val terminalFontSize: StateFlow<Int> = _terminalFontSize.asStateFlow()

    private val _terminalCopyOnSelect = MutableStateFlow(true)
    val terminalCopyOnSelect: StateFlow<Boolean> = _terminalCopyOnSelect.asStateFlow()

    fun init(application: Application) {
        if (::prefs.isInitialized) return
        prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _themeMode.value = ThemeMode.fromString(prefs.getString(KEY_THEME, null))
        _terminalColorScheme.value = TerminalColorScheme.fromString(prefs.getString(KEY_TERMINAL_COLOR_SCHEME, null))
        _terminalFontSize.value = prefs.getInt(KEY_TERMINAL_FONT_SIZE, DEFAULT_FONT_SIZE)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        _terminalCopyOnSelect.value = prefs.getBoolean(KEY_TERMINAL_COPY_ON_SELECT, true)
    }

    fun setThemeMode(mode: ThemeMode) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setTerminalColorScheme(scheme: TerminalColorScheme) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_TERMINAL_COLOR_SCHEME, scheme.name).apply()
        _terminalColorScheme.value = scheme
    }

    fun setTerminalFontSize(sizeSp: Int) {
        if (!::prefs.isInitialized) return
        val v = sizeSp.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        prefs.edit().putInt(KEY_TERMINAL_FONT_SIZE, v).apply()
        _terminalFontSize.value = v
    }

    fun setTerminalCopyOnSelect(enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_TERMINAL_COPY_ON_SELECT, enabled).apply()
        _terminalCopyOnSelect.value = enabled
    }

    /**
     * 将枚举里存储的 ARGB（Kotlin 中 `0xFFxxxxxx` 整型字面量易受符号扩展影响）转为 Compose [Color]。
     */
    fun argbLongToColor(argb: Long): Color = Color(argb and 0xFFFFFFFFL)

    /** 创建 [org.connectbot.terminal.TerminalEmulator] 时使用的默认色（与 Haven 方案表一致）。 */
    fun defaultTerminalColors(): Pair<Color, Color> {
        val s = if (::prefs.isInitialized) _terminalColorScheme.value else TerminalColorScheme.DEFAULT
        return Pair(argbLongToColor(s.foreground), argbLongToColor(s.background))
    }

    enum class ThemeMode(val label: String) {
        SYSTEM("跟随系统"),
        LIGHT("浅色"),
        DARK("深色");

        companion object {
            fun fromString(value: String?): ThemeMode =
                entries.find { it.name == value } ?: SYSTEM
        }
    }

    /** 终端配色（背景/前景）。 */
    enum class TerminalColorScheme(
        val label: String,
        val background: Long,
        val foreground: Long,
    ) {
        CLASSIC_GREEN("经典绿", 0xFF000000, 0xFF00FF00),
        LIGHT("浅色", 0xFFFFFFFF, 0xFF1A1A1A),
        SOLARIZED_DARK("Solarized Dark", 0xFF002B36, 0xFF839496),
        DRACULA("Dracula", 0xFF282A36, 0xFFF8F8F2),
        MONOKAI("Monokai", 0xFF272822, 0xFFF8F8F2),
        NORD("Nord", 0xFF2E3440, 0xFFD8DEE9),
        GRUVBOX("Gruvbox", 0xFF282828, 0xFFEBDBB2),
        TOKYO_NIGHT("Tokyo Night", 0xFF1A1B26, 0xFFA9B1D6),
        QBASIC("QBasic", 0xFF0000AA, 0xFFAAAAAA),
        AMBER("琥珀", 0xFF1A1000, 0xFFFFB000),
        PINK("粉色", 0xFF2D001E, 0xFFFF9EC6),
        LAVENDER("薰衣草", 0xFF1E1629, 0xFFCDB4DB),
        OCEAN("海洋", 0xFF0A192F, 0xFF64FFDA);

        companion object {
            /** 默认终端配色（已移除 Haven 方案；旧偏好中的 HAVEN 会迁移到此）。 */
            val DEFAULT: TerminalColorScheme = CLASSIC_GREEN

            fun fromString(value: String?): TerminalColorScheme {
                if (value.isNullOrBlank() || value == "HAVEN") return DEFAULT
                return entries.find { it.name == value } ?: DEFAULT
            }
        }
    }
}
