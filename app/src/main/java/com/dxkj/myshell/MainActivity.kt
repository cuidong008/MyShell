package com.dxkj.myshell

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.dxkj.myshell.terminal.TerminalSessionPool
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.dxkj.myshell.data.prefs.AppPreferences
import com.dxkj.myshell.ui.AppNav
import com.dxkj.myshell.ui.theme.MyShellTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.init(application)
        setContent {
            AppRoot()
        }
    }

    override fun onResume() {
        super.onResume()
        TerminalSessionPool.init(application)
        TerminalSessionPool.onApplicationResume()
    }
}

@Composable
private fun AppRoot() {
    val themeMode by AppPreferences.themeMode.collectAsState()
    LaunchedEffect(themeMode) {
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode) {
                AppPreferences.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppPreferences.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                AppPreferences.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }
    val darkTheme = when (themeMode) {
        AppPreferences.ThemeMode.LIGHT -> false
        AppPreferences.ThemeMode.DARK -> true
        AppPreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MyShellTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppNav()
        }
    }
}

