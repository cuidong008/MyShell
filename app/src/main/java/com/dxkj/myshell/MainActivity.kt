package com.dxkj.myshell

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.dxkj.myshell.terminal.TerminalSessionPool
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dxkj.myshell.ui.AppNav
import com.dxkj.myshell.ui.theme.MyShellTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    MyShellTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppNav()
        }
    }
}

