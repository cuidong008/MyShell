package com.dxkj.myshell.ui.screens

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.dxkj.myshell.ui.theme.Dimens
import com.dxkj.myshell.terminal.TerminalSessionPool

private enum class SessionPane { Terminal, Files, PortForward }

@Composable
fun SessionsScreen(
    contentPadding: PaddingValues,
    initialSessionId: Long? = null,
) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    val context = LocalContext.current
    var pane by remember { mutableStateOf(SessionPane.Terminal) }
    var terminalToolbarVisible by remember { mutableStateOf(true) }
    val terminalPrefs = remember(context) {
        context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
    }
    var embeddedBottomKeyBar by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        TerminalSessionPool.init(context.applicationContext as Application)
    }

    val sessions by TerminalSessionPool.sessions.collectAsState()
    val activeId by TerminalSessionPool.activeSessionId.collectAsState()
    val activeSession = sessions.firstOrNull { it.sessionId == activeId } ?: sessions.lastOrNull()
    val filesLinkedHostId = activeSession?.hostId
    val portForwardSessionId = activeSession?.sessionId

    if (initialSessionId != null) {
        LaunchedEffect(initialSessionId) {
            TerminalSessionPool.setActive(initialSessionId)
        }
    }

    LaunchedEffect(activeSession?.hostId, pane) {
        if (pane != SessionPane.Terminal) return@LaunchedEffect
        val hid = activeSession?.hostId ?: return@LaunchedEffect
        embeddedBottomKeyBar = terminalPrefs.getBoolean("keyBar_embedded_$hid", false)
    }

    LaunchedEffect(embeddedBottomKeyBar, activeSession?.hostId, pane) {
        if (pane != SessionPane.Terminal) return@LaunchedEffect
        val hid = activeSession?.hostId ?: return@LaunchedEffect
        terminalPrefs.edit().putBoolean("keyBar_embedded_$hid", embeddedBottomKeyBar).apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 6.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 内容区：从顶部开始显示
            Box(modifier = Modifier.fillMaxSize()) {
                when (pane) {
                    SessionPane.Terminal -> TerminalHubScreen(
                        initialHostId = null,
                        onExit = {},
                        immersive = false,
                        showBack = false,
                        showTopOverlay = terminalToolbarVisible,
                        compactTopOverlay = true,
                        onToggleTopOverlay = { terminalToolbarVisible = !terminalToolbarVisible },
                        embeddedInSessionsPane = true,
                        embeddedBottomToolbarExpanded = embeddedBottomKeyBar,
                    )

                    SessionPane.Files -> FilesScreen(
                        contentPadding = PaddingValues(0.dp),
                        linkedHostId = filesLinkedHostId,
                    )

                    SessionPane.PortForward -> PortForwardScreen(
                        contentPadding = PaddingValues(0.dp),
                        linkedSessionId = portForwardSessionId,
                    )
                }
            }

            // 右侧：终端 / 文件 / 端口转发（避免底部挡住输入/键盘）
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(end = Dimens.SpacingSm)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        RoundedCornerShape(Dimens.OverlayCorner),
                    )
                    .padding(horizontal = Dimens.SpacingXs, vertical = Dimens.SpacingXs),
                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing1),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (pane == SessionPane.Terminal) {
                    IconButton(
                        onClick = { embeddedBottomKeyBar = !embeddedBottomKeyBar },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Keyboard,
                            contentDescription = if (embeddedBottomKeyBar) "隐藏底部按键条" else "显示底部按键条",
                            tint = if (embeddedBottomKeyBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { pane = SessionPane.Terminal },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = "终端",
                        tint = if (pane == SessionPane.Terminal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = { pane = SessionPane.Files },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = "文件",
                        tint = if (pane == SessionPane.Files) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = { pane = SessionPane.PortForward },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Router,
                        contentDescription = "端口转发",
                        tint = if (pane == SessionPane.PortForward) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

